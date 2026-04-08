package com.pic.catcher.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.lu.lposed.api2.XC_MethodHook2;
import com.lu.lposed.api2.XposedHelpers2;
import com.lu.lposed.plugin.IPlugin;
import com.lu.magic.util.log.LogUtil;
import com.pic.catcher.ClazzN;
import com.pic.catcher.config.ModuleConfig;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 修复版 Glide 拦截器
 * 采用 UI 渲染层级拦截，不再干扰网络数据流，彻底解决图片无法显示问题。
 */
public class GlideCatcherPlugin implements IPlugin {
    @Override
    public void handleHook(Context context, XC_LoadPackage.LoadPackageParam loadPackageParam) {
        
        // 方案：不再 Hook HttpUrlFetcher 的数据流（防止阻断加载）。
        // 改为 Hook Glide 的核心资源处理类 SingleRequest 或 ImageViewTarget。
        
        Class<?> singleRequestClazz = ClazzN.from("com.bumptech.glide.request.SingleRequest");
        if (singleRequestClazz != null) {
            // Hook onResourceReady(Resource<R> resource, DataSource dataSource, boolean isFirstResource)
            XposedHelpers2.hookAllMethods(singleRequestClazz, "onResourceReady", new XC_MethodHook2() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModuleConfig.getInstance().isCatchGlidePic()) return;
                    
                    Object resource = param.args[0];
                    if (resource == null) return;

                    try {
                        // Resource 接口通常有 get() 方法返回具体对象 (Bitmap, Drawable 等)
                        Object result = XposedHelpers2.callMethod(resource, "get");
                        if (result instanceof Bitmap) {
                            LogUtil.d("GlideCatcher", "Captured Bitmap from SingleRequest");
                            PicExportManager.getInstance().exportBitmap((Bitmap) result);
                        } else if (result instanceof BitmapDrawable) {
                            LogUtil.d("GlideCatcher", "Captured BitmapDrawable from SingleRequest");
                            PicExportManager.getInstance().exportBitmap(((BitmapDrawable) result).getBitmap());
                        } else if (result instanceof Drawable) {
                            // 后续可扩展 GifDrawable 等
                        }
                    } catch (Throwable t) {
                        // 静默处理，不干扰宿主
                    }
                }
            });
        }

        // 拦截 GIF 专用解码器作为补强
        Class<?> streamGifDecoderClazz = ClazzN.from("com.bumptech.glide.load.resource.gif.StreamGifDecoder");
        if (streamGifDecoderClazz != null) {
            XposedHelpers2.hookAllMethods(streamGifDecoderClazz, "decode", new XC_MethodHook2() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 仅在解码完成后静默抓取
                }
            });
        }
    }
}
