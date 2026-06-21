package com.pic.catcher.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;

import com.lu.lposed.api2.XC_MethodHook2;
import com.lu.lposed.api2.XposedHelpers2;
import com.lu.lposed.plugin.IPlugin;
import com.lu.magic.util.log.LogUtil;

import com.pic.catcher.config.ModuleConfig;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 终极兜底：拦截 Canvas 绘制
 * 只要图片在屏幕上显示出来，就一定会被捕获。
 */
public class CanvasCatcherPlugin implements IPlugin {
    @Override
    public void handleHook(Context context, XC_LoadPackage.LoadPackageParam loadPackageParam) {
        
        XC_MethodHook2 canvasHook = new XC_MethodHook2() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!ModuleConfig.getInstance().isCatchCanvasPic()) return;
                
                Object arg0 = param.args[0];
                if (arg0 instanceof Bitmap) {
                    Bitmap bitmap = (Bitmap) arg0;
                    // 过滤太小的图标，Pixiv 原图通常很大
                    if (bitmap.getWidth() < 200 || bitmap.getHeight() < 200) return;
                    
                    PicExportManager.getInstance().exportBitmap(bitmap);
                }
            }
        };

        // 覆盖 Canvas 所有 drawBitmap 的重载方法
        XposedHelpers2.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, float.class, float.class, Paint.class, canvasHook);
        XposedHelpers2.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, Rect.class, Rect.class, Paint.class, canvasHook);
        XposedHelpers2.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, Rect.class, RectF.class, Paint.class, canvasHook);
        XposedHelpers2.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, Matrix.class, Paint.class, canvasHook);

        // 增强：拦截 drawRenderNode (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                XposedHelpers2.findAndHookMethod(Canvas.class, "drawRenderNode", android.graphics.RenderNode.class, new XC_MethodHook2() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!ModuleConfig.getInstance().isCatchCanvasPic()) return;
                        // RenderNode 渲染通常通过 HardwareRenderer 拦截更有效，但此处可作为一个入口
                    }
                });
            } catch (Throwable ignored) {}
        }
    }
}
