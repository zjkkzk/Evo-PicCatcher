package com.pic.catcher.plugin;

import android.content.Context;
import android.graphics.Bitmap;

import com.lu.lposed.api2.XC_MethodHook2;
import com.lu.lposed.api2.XposedHelpers2;
import com.lu.lposed.plugin.IPlugin;
import com.lu.magic.util.log.LogUtil;
import com.pic.catcher.ClazzN;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Fresco 拦截器
 * 拦截已解码的位图容器，避免直接消耗原始数据流导致加载阻断
 */
public class FrescoCatcherPlugin implements IPlugin {
    @Override
    public void handleHook(Context context, XC_LoadPackage.LoadPackageParam loadPackageParam) {
        
        // 方案：拦截解码后的 Bitmap 容器 CloseableStaticBitmap。
        // 这发生在渲染前，能拿到完整图片且对原始加载流无任何影响。
        
        Class<?> closeableStaticBitmapClazz = ClazzN.from("com.facebook.imagepipeline.image.CloseableStaticBitmap");
        Class<?> qualityInfoClazz = ClazzN.from("com.facebook.imagepipeline.image.QualityInfo");

        if (closeableStaticBitmapClazz != null) {
            XposedHelpers2.findAndHookConstructor(closeableStaticBitmapClazz, 
                Bitmap.class, qualityInfoClazz, int.class, int.class,
                new XC_MethodHook2() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Bitmap bitmap = (Bitmap) param.args[0];
                        if (bitmap != null) {
                            LogUtil.d("FrescoCatcher", "Captured from CloseableStaticBitmap");
                            PicExportManager.getInstance().exportBitmap(bitmap);
                        }
                    }
                });
        }
        
        // 针对动图资源
        Class<?> animatedImageClazz = ClazzN.from("com.facebook.imagepipeline.image.CloseableAnimatedImage");
        if (animatedImageClazz != null) {
            XposedHelpers2.hookAllMethods(animatedImageClazz, "getUnderlyingImage", new XC_MethodHook2() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 后续可扩展动图抓取
                }
            });
        }
    }
}
