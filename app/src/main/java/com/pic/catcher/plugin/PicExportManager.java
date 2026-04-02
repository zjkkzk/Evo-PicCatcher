package com.pic.catcher.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import com.lu.magic.util.AppUtil;
import com.lu.magic.util.IOUtil;
import com.lu.magic.util.thread.AppExecutor;
import com.pic.catcher.config.ModuleConfig;
import com.pic.catcher.provider.LogProvider;
import com.pic.catcher.ui.config.PicFormat;
import com.pic.catcher.util.FileUtils;
import com.pic.catcher.util.Md5Util;
import com.pic.catcher.util.PicUtil;
import com.pic.catcher.util.XLog;
import com.pic.catcher.util.http.HttpConnectUtil;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * 图片导出管理器 - 采用跨进程日志系统
 */
public class PicExportManager {
    private static final String TAG = "PicCatcher";
    private static PicExportManager sInstance;
    
    // 强制插件私有路径根目录
    private static final String BASE_PATH = "/storage/emulated/0/Android/data/com.evo.piccatcher/files";

    /**
     * 统一日志入口
     */
    public void log(String msg) {
        // XLog 内部已经处理了线程切换和跨进程调用逻辑
        XLog.i(AppUtil.getContext(), msg);
    }

    public File getExportDir() {
        if (ModuleConfig.getInstance().isSaveToInternal()) {
            // 内部保存：指向插件私有路径
            File dir = new File(BASE_PATH, "Pictures/" + AppUtil.getContext().getPackageName());
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } else {
            // 公共保存：Pictures/包名
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), AppUtil.getContext().getPackageName());
            if (!dir.exists()) dir.mkdirs();
            return dir;
        }
    }

    public synchronized static PicExportManager getInstance() {
        if (sInstance == null) {
            sInstance = new PicExportManager();
        }
        return sInstance;
    }

    public void exportBitmapFile(File file) {
        runOnIo(() -> {
            if (!file.exists()) return;
            if (ModuleConfig.isLessThanMinSize(file.length())) return;
            
            File dest = new File(getExportDir(), file.getName());
            FileUtils.copyFile(file, dest);
            log("Success save file: " + dest.getAbsolutePath());
        });
    }

    public void exportBitmap(Bitmap bitmap) {
        runOnIo(() -> {
            if (bitmap == null || bitmap.isRecycled()) return;
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                String format = ModuleConfig.getInstance().getPicDefaultSaveFormat();
                Bitmap.CompressFormat cf = PicFormat.JPG.equals(format) ? Bitmap.CompressFormat.JPEG : 
                                         (PicFormat.PNG.equals(format) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.WEBP);
                
                synchronized (bitmap) {
                    if (bitmap.isRecycled()) return;
                    bitmap.compress(cf, 100, bos);
                }
                
                byte[] bytes = bos.toByteArray();
                if (bytes.length == 0 || ModuleConfig.isLessThanMinSize(bytes.length)) return;

                String fileName = Md5Util.get(bytes) + "." + format;
                File dest = new File(getExportDir(), fileName);
                if (dest.exists()) return;

                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    IOUtil.writeByByte(bytes, fos);
                    log("Success save bitmap: " + dest.getAbsolutePath());
                }
            } catch (Exception e) {
                log("Error save bitmap: " + e.getMessage());
            }
        });
    }

    public void exportByteArray(final byte[] dataBytes, String lastName) {
        if (dataBytes == null || dataBytes.length == 0) return;
        if (ModuleConfig.isLessThanMinSize(dataBytes.length)) return;
        
        runOnIo(() -> {
            try {
                String suffix = TextUtils.isEmpty(lastName) ? PicUtil.detectImageType(dataBytes, "bin") : lastName;
                if (!suffix.startsWith(".")) suffix = "." + suffix;
                
                String fileName = Md5Util.get(dataBytes) + suffix;
                File dest = new File(getExportDir(), fileName);
                if (dest.exists()) return;

                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    IOUtil.writeByByte(dataBytes, fos);
                    log("Success save bytes: " + dest.getAbsolutePath());
                }
            } catch (Exception e) {
                log("Save bytes error: " + e.getMessage());
            }
        });
    }

    public void exportUrlIfNeed(String url) {
        runOnIo(() -> {
            if (TextUtils.isEmpty(url)) return;
            String fileEx = MimeTypeMap.getFileExtensionFromUrl(url).toLowerCase(Locale.ROOT);
            if (URLUtil.isHttpUrl(url) || URLUtil.isHttpsUrl(url)) {
                exportHttpPicUrlIfNeed(url, fileEx);
            } else if (URLUtil.isFileUrl(url)) {
                File file = new File(java.net.URI.create(url));
                if (PicUtil.isPicSuffix(fileEx) || PicUtil.isPicSuffix(PicUtil.detectImageType(file, ""))) {
                    exportBitmapFile(file);
                }
            }
        });
    }

    private void exportHttpPicUrlIfNeed(String url, String fileEx) {
        HttpConnectUtil.request("GET", url, null, null, true, response -> {
            byte[] body = response.getBody();
            exportByteArray(body, fileEx);
            return null;
        });
    }

    public void runOnIo(Runnable runnable) {
        if (Looper.getMainLooper().isCurrentThread()) {
            AppExecutor.io().execute(runnable);
        } else {
            runnable.run();
        }
    }
}
