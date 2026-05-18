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
import com.pic.catcher.util.XLog;
import com.pic.catcher.util.http.HttpConnectUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 图片导出管理器 - 极致性能优化版 (针对 Pixiv 等高负载 App)
 * 1. 串行化后台处理：使用单线程池，确保不抢占多个 CPU 核心。
 * 2. 优先级下调：后台线程设置为 MIN_PRIORITY，让位给宿主渲染。
 * 3. 任务过载丢弃：如果队列积压超过 100 个任务，自动丢弃旧任务。
 * 4. 零锁机制：移除 synchronized(bitmap)，避免阻塞 RenderThread 的读操作。
 * 5. 对象去重：LRU 记录已处理的对象，防止重复导出产生的 IO 开销。
 */
public class PicExportManager {
    private static final String TAG = "PicCatcher_Host";
    private static PicExportManager sInstance;
    private File mCachedCacheDir;
    
    // 专属串行化工作线程，避免对系统 IO 线程池产生级联阻塞
    private static final ExecutorService sWorker = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "PicExportWorker");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy() 
    );

    // 记录最近处理过的对象，防止重复 Hook 产生的性能损耗
    private final LruCache<Integer, Boolean> mProcessedIdCache = new LruCache<>(200);

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
                    }
                }
            }
        }
        return mCachedCacheDir;
    }

    /**
     * 导出 Bitmap
     */
    public void exportBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        
        // 1. 过滤小尺寸（头像、小标、表情包），大幅减轻 Pixiv 列表加载负担
        if (bitmap.getWidth() < 300 || bitmap.getHeight() < 300) return;
        
        // 2. 去重过滤
        int identity = System.identityHashCode(bitmap);
        if (mProcessedIdCache.get(identity) != null) return;
        mProcessedIdCache.put(identity, true);

        // 3. 投递任务
        sWorker.execute(() -> {
            try {
                if (bitmap.isRecycled()) return;
                
                String format = ModuleConfig.getInstance().getPicDefaultSaveFormat();
                Bitmap.CompressFormat cf = PicFormat.JPG.equals(format) ? Bitmap.CompressFormat.JPEG : 
                                         (PicFormat.PNG.equals(format) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.WEBP);
                
                try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    // ZERO-LOCK 压缩：直接读取。
                    // 虽然可能遇到位图正在改变导致的异常，但这种概率极低且被捕获，
                    // 收益是彻底消除了对 RenderThread 的锁竞争。
                    bitmap.compress(cf, 85, bos); 
                    byte[] bytes = bos.toByteArray();
                    
                    if (bytes.length < 10240) return; // 10KB 过滤
                    
                    String fileName = Md5Util.get(bytes) + "." + format;
                    writeToHostCache(bytes, fileName);
                }
            } catch (Throwable ignored) {
                // 捕获所有可能的异常，确保宿主稳如老狗
            }
        });
    }

    /**
     * 导出字节数组 (通用)
     */
    public void exportByteArray(final byte[] dataBytes, String lastName) {
        if (dataBytes == null || dataBytes.length < 20480) return; // 20KB 过滤
        
        int identity = System.identityHashCode(dataBytes);
        if (mProcessedIdCache.get(identity) != null) return;
        mProcessedIdCache.put(identity, true);

        sWorker.execute(() -> {
            try {
                String suffix = TextUtils.isEmpty(lastName) ? PicUtil.detectImageType(dataBytes, "bin") : lastName;
                if (!suffix.startsWith(".")) suffix = "." + suffix;
                String fileName = Md5Util.get(dataBytes) + suffix;
                writeToHostCache(dataBytes, fileName);
            } catch (Throwable ignored) {}
        });
    }

    public void exportBitmapFile(File file) {
        if (file == null || !file.exists() || file.length() < 20480) return;
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
        if (data == null || data.length < 20480) return;
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
        } catch (Exception ignored) {}
    }

    public void exportUrlIfNeed(String url) {
        if (TextUtils.isEmpty(url)) return;
        sWorker.execute(() -> {
            String fileEx = MimeTypeMap.getFileExtensionFromUrl(url).toLowerCase(Locale.ROOT);
            if (URLUtil.isHttpUrl(url) || URLUtil.isHttpsUrl(url)) {
                HttpConnectUtil.request("GET", url, null, null, true, response -> {
                    byte[] body = response.getBody();
                    if (body != null) exportByteArrayInternal(body, fileEx);
                    return null;
                });
            }
        });
    }
}
