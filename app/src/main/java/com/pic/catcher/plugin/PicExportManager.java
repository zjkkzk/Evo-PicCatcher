package com.pic.catcher.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.LruCache;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * 图片导出管理器 - 安全稳定版
 * 采用 Android 标准 API，不使用任何越权反射，确保不被杀毒软件误报。
 */
public class PicExportManager {
    private static final String TAG = "PicCatcher";
    private static PicExportManager sInstance;
    
    // 基础去重缓存，防止内存压力
    private final LruCache<Integer, Boolean> processedObjects = new LruCache<>(300);

    public synchronized static PicExportManager getInstance() {
        if (sInstance == null) {
            sInstance = new PicExportManager();
        }
        return sInstance;
    }

    public void log(String msg) {
        XLog.i(AppUtil.getContext(), msg);
    }

    /**
     * 标准 Provider 写入：仅在权限允许时操作
     */
    private void writeToProvider(byte[] data, String fileName) {
        Context context = AppUtil.getContext();
        Uri uri = LogProvider.CONTENT_URI.buildUpon()
                .appendPath("save_pic")
                .appendQueryParameter("name", fileName)
                .appendQueryParameter("pkg", context.getPackageName())
                .build();

        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "w")) {
            if (pfd != null) {
                try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                    fos.write(data);
                    return;
                }
            }
        } catch (Exception e) {
            // 如果 Provider 报错（包可见性限制），自动降级到公共目录
            saveToPublic(data, fileName);
        }
    }

    public void exportBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (bitmap.getWidth() < 100 || bitmap.getHeight() < 100) return;

        int id = System.identityHashCode(bitmap);
        if (processedObjects.get(id) != null) return;
        processedObjects.put(id, true);

        runOnIo(() -> {
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
                if (ModuleConfig.getInstance().isSaveToInternal()) {
                    writeToProvider(bytes, fileName);
                } else {
                    saveToPublic(bytes, fileName);
                }
            } catch (Exception ignored) {}
        });
    }

    private void saveToPublic(byte[] data, String fileName) {
        try {
            File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File dir = new File(publicDir, "PicCatcher/" + AppUtil.getContext().getPackageName());
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, fileName);
            if (dest.exists()) return;
            
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(data);
            }
        } catch (Exception ignored) {}
    }

    public void exportByteArray(final byte[] dataBytes, String lastName) {
        if (dataBytes == null || dataBytes.length < 2048) return;
        
        runOnIo(() -> {
            String md5 = Md5Util.get(dataBytes);
            String suffix = TextUtils.isEmpty(lastName) ? PicUtil.detectImageType(dataBytes, "bin") : lastName;
            if (!suffix.startsWith(".")) suffix = "." + suffix;
            String fileName = md5 + suffix;

            if (ModuleConfig.getInstance().isSaveToInternal()) {
                writeToProvider(dataBytes, fileName);
            } else {
                saveToPublic(dataBytes, fileName);
            }
        });
    }

    public void exportBitmapFile(File file) {
        if (file == null || !file.exists() || file.length() < 2048) return;
        runOnIo(() -> {
            try (FileInputStream fis = new FileInputStream(file);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                FileUtils.copyFile(fis, bos);
                exportByteArray(bos.toByteArray(), MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath()));
            } catch (Exception ignored) {}
        });
    }

    public void exportUrlIfNeed(String url) {
        if (TextUtils.isEmpty(url) || !URLUtil.isNetworkUrl(url)) return;
        runOnIo(() -> {
            String fileEx = MimeTypeMap.getFileExtensionFromUrl(url).toLowerCase(Locale.ROOT);
            HttpConnectUtil.request("GET", url, null, null, true, response -> {
                byte[] body = response.getBody();
                if (body != null) exportByteArray(body, fileEx);
                return null;
            });
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
