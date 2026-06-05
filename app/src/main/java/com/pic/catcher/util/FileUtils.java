package com.pic.catcher.util;

import android.os.Build;
import com.lu.magic.util.IOUtil;
import com.lu.magic.util.log.LogUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class FileUtils {
    /**
     * 修正后的文件复制逻辑
     */
    public static void copyFile(InputStream is, OutputStream os) {
        if (is == null || os == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.os.FileUtils.copy(is, os);
            } else {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtil.closeQuietly(is, os);
        }
    }

    public static void copyFile(File sourceFilePath, File destFilePath) {
        if (!sourceFilePath.exists()) return;
        try {
            FileInputStream is = new FileInputStream(sourceFilePath);
            FileOutputStream os = new FileOutputStream(destFilePath);
            copyFile(is, os);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 计算目录大小
     */
    public static long getFolderSize(File file) {
        long size = 0;
        try {
            File[] fileList = file.listFiles();
            if (fileList == null) return 0;
            for (File f : fileList) {
                if (f.isDirectory()) {
                    size += getFolderSize(f);
                } else {
                    size += f.length();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return size;
    }

    /**
     * 计算目录中的文件总数
     */
    public static int getFileCount(File file) {
        int count = 0;
        try {
            File[] fileList = file.listFiles();
            if (fileList == null) return 0;
            for (File f : fileList) {
                if (f.isDirectory()) {
                    count += getFileCount(f);
                } else {
                    count++;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return count;
    }

    /**
     * 格式化单位 (1024进制，使用 MiB/KiB 标准)
     */
    public static String formatFileSize(long size) {
        if (size < 0) return "0 B";
        if (size < 1024) return size + " B";
        int exp = (int) (Math.log(size) / Math.log(1024));
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        return String.format(java.util.Locale.US, "%.1f %s", size / Math.pow(1024, exp), units[exp - 1]);
    }

    /**
     * 递归删除目录下的所有文件及子目录
     */
    public static boolean deleteFolder(File folder) {
        if (folder == null || !folder.exists()) return false;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    f.delete();
                }
            }
        }
        return true;
    }
}
