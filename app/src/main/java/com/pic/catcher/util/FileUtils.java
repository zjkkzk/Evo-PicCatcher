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
                    size = size + getFolderSize(f);
                } else {
                    size = size + f.length();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return size;
    }

    /**
     * 格式化单位
     */
    public static String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
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
