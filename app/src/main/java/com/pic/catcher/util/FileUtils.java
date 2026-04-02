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
}
