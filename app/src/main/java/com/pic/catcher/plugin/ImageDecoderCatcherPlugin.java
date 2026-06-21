package com.pic.catcher.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.lu.lposed.api2.XC_MethodHook2;
import com.lu.lposed.api2.XposedHelpers2;
import com.lu.lposed.plugin.IPlugin;
import com.lu.magic.util.log.LogUtil;

import com.pic.catcher.config.ModuleConfig;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 现代 Android 解码拦截 (Android 9.0+)
 * 只要系统在解码图片，我们就把它扣下来。
 */
public class ImageDecoderCatcherPlugin implements IPlugin {
    @Override
    public void handleHook(Context context, XC_LoadPackage.LoadPackageParam lpparam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;

        // 拦截所有解码产生的 Bitmap
        XC_MethodHook2 decoderHook = new XC_MethodHook2() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!ModuleConfig.getInstance().isCatchImageDecoderPic()) return;
                
                Object result = param.getResult();
                if (result instanceof Bitmap) {
                    PicExportManager.getInstance().exportBitmap((Bitmap) result);
                } else if (result instanceof BitmapDrawable) {
                    PicExportManager.getInstance().exportBitmap(((BitmapDrawable) result).getBitmap());
                } else if (result instanceof Drawable) {
                    // 对于 AnimatedImageDrawable 等，尝试转为 Bitmap 抓取首帧或通过 Canvas 兜底
                    Bitmap bmp = com.pic.catcher.util.PicUtil.drawableToBitmap((Drawable) result);
                    if (bmp != null) PicExportManager.getInstance().exportBitmap(bmp);
                }
            }
        };

        XposedHelpers2.findAndHookMethod(ImageDecoder.class, "decodeBitmap", ImageDecoder.Source.class, ImageDecoder.OnHeaderDecodedListener.class, decoderHook);
        XposedHelpers2.findAndHookMethod(ImageDecoder.class, "decodeDrawable", ImageDecoder.Source.class, ImageDecoder.OnHeaderDecodedListener.class, decoderHook);
    }
}
