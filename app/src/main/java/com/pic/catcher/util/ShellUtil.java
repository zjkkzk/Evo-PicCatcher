package com.pic.catcher.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ShellUtil {
    private static final String TAG = "ShellUtil";
    private static Boolean cachedRootStatus = null;
    private static String cachedSuManagerName = null;
    
    private static Process persistentProcess = null;
    private static BufferedWriter writer = null;
    private static BufferedReader reader = null;
    private static final ReentrantLock lock = new ReentrantLock();

    /**
     * 获取持久化的 Root Shell 会话
     * 核心逻辑：只在第一次调用时触发授权弹窗，后续静默执行
     */
    private static boolean ensureSession() {
        if (persistentProcess != null && persistentProcess.isAlive()) {
            return true;
        }
        lock.lock();
        try {
            if (persistentProcess != null && persistentProcess.isAlive()) return true;
            
            Log.d(TAG, "Initializing persistent SU session...");
            persistentProcess = Runtime.getRuntime().exec("su");
            writer = new BufferedWriter(new OutputStreamWriter(persistentProcess.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(persistentProcess.getInputStream()));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SU session", e);
            closeSession();
            return false;
        } finally {
            lock.unlock();
        }
    }

    private static void closeSession() {
        try {
            if (writer != null) {
                writer.write("exit\n");
                writer.flush();
                writer.close();
            }
            if (reader != null) reader.close();
            if (persistentProcess != null) {
                persistentProcess.destroy();
            }
        } catch (Exception ignored) {}
        writer = null;
        reader = null;
        persistentProcess = null;
    }

    public static boolean hasRootPermission() {
        if (cachedRootStatus != null) return cachedRootStatus;
        ShellResult res = runCommandInternal("id", 3000);
        boolean success = res.isSuccess() && res.stdout.contains("uid=0");
        if (success) cachedRootStatus = true;
        return success;
    }

    public static String getSuManagerName() {
        if (cachedSuManagerName != null) return cachedSuManagerName;
        if (!hasRootPermission()) return "SU管理器";
        
        if (runCommandInternal("magisk -v", 1000).isSuccess()) return cachedSuManagerName = "Magisk";
        if (runCommandInternal("ksu --version", 1000).isSuccess()) return cachedSuManagerName = "KernelSU";
        if (runCommandInternal("apatch --version", 1000).isSuccess()) return cachedSuManagerName = "APatch";
        
        ShellResult res = runCommandInternal("su -v", 1000);
        if (res.isSuccess() && res.stdout.contains("SuperSU")) return cachedSuManagerName = "SuperSU";
        
        return "SU管理器";
    }

    public static ShellResult runCommand(String command) {
        return runCommandInternal(command, 15000);
    }

    /**
     * 在持久化会话中执行命令
     */
    private static ShellResult runCommandInternal(String command, long timeout) {
        if (!ensureSession()) {
            return new ShellResult(-1, "", "SU session unavailable");
        }

        lock.lock();
        try {
            String marker = "END_OF_COMMAND_" + System.currentTimeMillis();
            writer.write(command + "\necho " + marker + "\n");
            writer.flush();

            StringBuilder sb = new StringBuilder();
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeout) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null || line.contains(marker)) break;
                    sb.append(line).append("\n");
                } else {
                    Thread.sleep(50);
                }
            }
            return new ShellResult(0, sb.toString().trim(), "");
        } catch (Exception e) {
            Log.e(TAG, "Execute command failed, restarting session", e);
            closeSession();
            return new ShellResult(-1, "", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public static class ShellResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public ShellResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
