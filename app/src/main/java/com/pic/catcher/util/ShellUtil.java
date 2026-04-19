package com.pic.catcher.util;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

public class ShellUtil {
    private static final String TAG = "ShellUtil";

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        if (isShizukuAvailable()) {
            try {
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable e) {
                return false;
            }
        }
        return false;
    }

    public static boolean isSui() {
        try {
            if (isShizukuAvailable()) {
                if (hasShizukuPermission()) {
                    return Shizuku.getUid() == 0;
                }
                return Shizuku.getVersion() >= 1000;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static ShellResult runCommand(String command) {
        String logMsg = "[ShellExec] " + command;
        // 核心：在尝试任何 Shell 之前，先触发一次 Java 层日志
        android.util.Log.e("PicWatcherShell", "JAVA_TRIGGER: " + logMsg);
        System.out.println("JAVA_STDOUT: " + logMsg);
        
        if (isShizukuAvailable() && hasShizukuPermission()) {
            return runShizukuCommand(command);
        } else {
            return runSuCommand(command);
        }
    }

    private static ShellResult runShizukuCommand(String command) {
        try {
            // 通过反射获取 Shizuku 内部服务接口，避免编译器对 private 静态方法的拦截
            java.lang.reflect.Method getServiceMethod = Shizuku.class.getDeclaredMethod("requireService");
            getServiceMethod.setAccessible(true);
            Object service = getServiceMethod.invoke(null);
            
            // 调用 IShizukuService.newProcess(...) 
            // 返回类型为 moe.shizuku.server.IRemoteProcess
            java.lang.reflect.Method newProcessMethod = service.getClass().getMethod(
                    "newProcess", String[].class, String[].class, String.class);
            
            Object remoteProcess = newProcessMethod.invoke(service, new String[]{"sh", "-c", command}, null, null);
            
            // 使用 IRemoteProcess 实例构造 ShizukuRemoteProcess (java.lang.Process 的子类)
            Class<?> remoteProcessClass = Class.forName("moe.shizuku.server.IRemoteProcess");
            java.lang.reflect.Constructor<?> constructor = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                    .getDeclaredConstructor(remoteProcessClass);
            constructor.setAccessible(true);
            
            Process process = (Process) constructor.newInstance(remoteProcess);
            return readProcess(process);
        } catch (Exception e) {
            Log.e(TAG, "Shizuku Shell Critical Error: " + e.getMessage(), e);
            return new ShellResult(-1, "", e.getMessage());
        }
    }

    private static ShellResult runSuCommand(String command) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            return readProcess(process);
        } catch (Exception e) {
            System.err.println("PicWatcher SU Error: " + e.getMessage());
            return new ShellResult(-1, "", e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }
    }

    private static ShellResult readProcess(Process process) throws Exception {
        BufferedReader successReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        
        StringBuilder successMsg = new StringBuilder();
        StringBuilder errorMsg = new StringBuilder();
        String line;
        
        while ((line = successReader.readLine()) != null) {
            successMsg.append(line).append("\n");
        }
        while ((line = errorReader.readLine()) != null) {
            errorMsg.append(line).append("\n");
        }
        
        int exitCode = process.waitFor();
        return new ShellResult(exitCode, successMsg.toString().trim(), errorMsg.toString().trim());
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
