package com.pic.catcher.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import com.lu.magic.util.AppUtil;
import com.pic.catcher.config.ModuleConfig;
import com.pic.catcher.ui.config.PicFormat;
import com.pic.catcher.util.FileUtils;
import com.pic.catcher.util.Md5Util;
import com.pic.catcher.util.PicUtil;
import com.pic.catcher.util.http.HttpConnectUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 图片导出管理器
 */
public class PicExportManager {
    private static final String TAG = "PicCatcher_Host";
    private static PicExportManager sInstance;
    private File mCachedCacheDir;
    
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final ExecutorService sWorker = new ThreadPoolExecutor(
            Math.max(2, Math.min(CPU_COUNT - 1, 4)), 
            8,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "PicExportWorker");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy() 
    );

    private final LruCache<String, Boolean> mProcessedUrlCache = new LruCache<>(1000);

    public synchronized static PicExportManager getInstance() {
        if (sInstance == null) {
            sInstance = new PicExportManager();
        }
        return sInstance;
    }

    private File getPicCacheDir() {
        if (mCachedCacheDir == null) {
            Context context = AppUtil.getContext();
            if (context != null) {
                File cacheDir = context.getExternalCacheDir();
                if (cacheDir != null) {
                    File dir = new File(cacheDir, "PicCatcher");
                    if (dir.exists() || dir.mkdirs()) {
                        mCachedCacheDir = dir;
                        syncNoMediaFile(dir);
                    }
                }
            }
        } else {
            // 每次获取时也校验一下，防止中途设置变更
            syncNoMediaFile(mCachedCacheDir);
        }
        return mCachedCacheDir;
    }

    private void syncNoMediaFile(File dir) {
        try {
            File noMedia = new File(dir, ".nomedia");
            boolean shouldExist = ModuleConfig.getInstance().isGenerateNoMedia();
            if (shouldExist) {
                if (!noMedia.exists()) {
                    noMedia.createNewFile();
                }
            } else {
                if (noMedia.exists()) {
                    noMedia.delete();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Sync .nomedia failed", e);
        }
    }

    /**
     * 导出 Bitmap
     */
    public void exportBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        
        sWorker.execute(() -> {
            try {
                if (bitmap.isRecycled()) return;
                
                String format = ModuleConfig.getInstance().getPicDefaultSaveFormat();
                Bitmap.CompressFormat cf = PicFormat.JPG.equals(format) ? Bitmap.CompressFormat.JPEG : 
                                         (PicFormat.PNG.equals(format) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.WEBP);
                
                try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    int quality = ModuleConfig.getInstance().getPicQuality();
                    bitmap.compress(cf, quality, bos);
                    byte[] bytes = bos.toByteArray();
                    
                    if (ModuleConfig.isLessThanMinSize((long) bytes.length)) return;
                    if (ModuleConfig.isLessThanMinResolution(bitmap.getWidth(), bitmap.getHeight())) return;
                    
                    exportByteArrayInternal(bytes, format);
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * 导出字节数组 (通用)
     */
    public void exportByteArray(final byte[] dataBytes, String suffix) {
        if (dataBytes == null) return;
        sWorker.execute(() -> exportByteArrayInternal(dataBytes, suffix));
    }

    public void exportBitmapFile(File file) {
        if (file == null || !file.exists()) return;
        if (ModuleConfig.isLessThanMinSize(file.length())) return;
        
        sWorker.execute(() -> {
            try (FileInputStream fis = new FileInputStream(file);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                FileUtils.copyFile(fis, bos);
                byte[] data = bos.toByteArray();
                String ext = MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath());
                exportByteArrayInternal(data, ext);
            } catch (Exception ignored) {}
        });
    }

    private void exportByteArrayInternal(byte[] data, String suffix) {
        if (data == null) return;
        if (ModuleConfig.isLessThanMinSize((long) data.length)) return;

        // 分辨率过滤
        int[] dimen = PicUtil.getImageDimensions(data);
        if (dimen != null && ModuleConfig.isLessThanMinResolution(dimen[0], dimen[1])) {
            return;
        }

        String ext = TextUtils.isEmpty(suffix) ? PicUtil.detectImageType(data, "bin") : suffix;
        if (!ext.startsWith(".")) ext = "." + ext;
        String fileName = Md5Util.get(data) + ext;
        writeToHostCache(data, fileName);
    }

    private void writeToHostCache(byte[] data, String fileName) {
        File cacheDir = getPicCacheDir();
        if (cacheDir == null) return;
        
        File cacheFile = new File(cacheDir, fileName);
        if (cacheFile.exists()) return;

        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(data);
            fos.flush();
            Log.i(TAG, "Successfully captured to: " + cacheFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Write failed: " + fileName, e);
        }
    }

    public void exportUrlIfNeed(String url) {
        exportUrlIfNeed(url, null);
    }

    public void exportUrlIfNeed(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) return;
        if (url.startsWith("data:image")) {
            exportDataUri(url);
            return;
        }
        if (mProcessedUrlCache.get(url) != null) return;
        mProcessedUrlCache.put(url, true);

        sWorker.execute(() -> {
            String fileEx = MimeTypeMap.getFileExtensionFromUrl(url).toLowerCase(Locale.ROOT);
            if (URLUtil.isHttpUrl(url) || URLUtil.isHttpsUrl(url)) {
                Map<String, String> finalHeaders = headers != null ? new HashMap<>(headers) : new HashMap<>();
                
                // 自动处理 Pixiv 域名及其 CDN
                if (url.contains("pximg.net") || url.contains("pixiv.net")) {
                    if (!finalHeaders.containsKey("Referer")) {
                        // 如果没有传入特定的 Referer，尝试使用一个通用的详情页模板，或者保持主站
                        finalHeaders.put("Referer", "https://www.pixiv.net/");
                    }
                    // Pixiv 必须指定真实 UA
                    finalHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                }

                // 尝试获取并携带 Cookie (兼容原生与 X5)
                String cookie = getCookieSafe(url);
                if (!TextUtils.isEmpty(cookie) && !finalHeaders.containsKey("Cookie")) {
                    finalHeaders.put("Cookie", cookie);
                }

                HttpConnectUtil.request("GET", url, finalHeaders, null, true, response -> {
                    byte[] body = response.getBody();
                    if (body != null && body.length > 0) {
                        exportByteArrayInternal(body, fileEx);
                    }
                    return null;
                });
            }
        });
    }

    private String getCookieSafe(String url) {
        String targetUrl = url;
        // Pixiv 的图片域名 pximg.net 通常不带 Cookie，验证信息在 pixiv.net 域名下
        if (url.contains("pximg.net")) {
            targetUrl = "https://www.pixiv.net/";
        }
        
        try {
            // 优先尝试原生
            String cookie = android.webkit.CookieManager.getInstance().getCookie(targetUrl);
            if (!TextUtils.isEmpty(cookie)) return cookie;
        } catch (Throwable ignored) {}

        try {
            // 尝试 X5
            Class<?> x5CookieMgrClazz = Class.forName("com.tencent.smtt.sdk.CookieManager");
            Method getInstanceMethod = x5CookieMgrClazz.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            if (instance != null) {
                Method getCookieMethod = x5CookieMgrClazz.getMethod("getCookie", String.class);
                return (String) getCookieMethod.invoke(instance, targetUrl);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public void exportDataUri(String dataUri) {
        if (TextUtils.isEmpty(dataUri) || !dataUri.startsWith("data:image")) return;
        sWorker.execute(() -> {
            try {
                int commaIndex = dataUri.indexOf(',');
                if (commaIndex == -1) return;
                String data = dataUri.substring(commaIndex + 1);
                byte[] decodedBytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                exportByteArrayInternal(decodedBytes, null);
            } catch (Exception ignored) {}
        });
    }
}
