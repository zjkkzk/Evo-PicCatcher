package com.pic.catcher.plugin;

import android.content.Context;

import com.lu.lposed.api2.XC_MethodHook2;
import com.lu.lposed.api2.XposedHelpers2;
import com.lu.lposed.plugin.IPlugin;
import com.lu.magic.util.IOUtil;
import com.pic.catcher.ClazzN;
import com.pic.catcher.config.ModuleConfig;
import com.pic.catcher.util.PicUtil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 终极版 X5 WebView 拦截插件
 * 具备：URL拦截、响应流审计、fetch/XHR 劫持、MutationObserver 实时扫描、Blob/Base64 提取
 */
public class X5WebViewCatcherPlugin implements IPlugin {

    @Override
    public void handleHook(Context context, XC_LoadPackage.LoadPackageParam loadPackageParam) {
        Class<?> webViewClientClazz = ClazzN.from("com.tencent.smtt.sdk.WebViewClient");
        Class<?> webChromeClientClazz = ClazzN.from("com.tencent.smtt.sdk.WebChromeClient");
        Class<?> webViewClazz = ClazzN.from("com.tencent.smtt.sdk.WebView");
        Class<?> webResourceResponseClazz = ClazzN.from("com.tencent.smtt.export.external.interfaces.WebResourceResponse");
        Class<?> webResourceRequestClazz = ClazzN.from("com.tencent.smtt.export.external.interfaces.WebResourceRequest");

        if (webViewClientClazz == null) return;

        // 1. 核心请求拦截 (shouldInterceptRequest)
        XposedHelpers2.hookAllMethods(webViewClientClazz, "shouldInterceptRequest", new XC_MethodHook2() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;

                String url = null;
                Map<String, String> headers = null;
                if (param.args.length >= 2) {
                    Object arg = param.args[1];
                    if (arg instanceof String) {
                        url = (String) arg;
                    } else if (arg != null && webResourceRequestClazz != null && webResourceRequestClazz.isInstance(arg)) {
                        url = String.valueOf(XposedHelpers2.callMethod(arg, "getUrl"));
                        headers = (Map<String, String>) XposedHelpers2.callMethod(arg, "getRequestHeaders");
                    }
                }
                if (url != null) PicExportManager.getInstance().exportUrlIfNeed(url, headers);

                Object response = param.getResult();
                if (response != null && webResourceResponseClazz != null && webResourceResponseClazz.isInstance(response)) {
                    interceptX5Stream(response, url);
                }
            }

            private void interceptX5Stream(Object response, String url) {
                try {
                    String mime = (String) XposedHelpers2.callMethod(response, "getMimeType");
                    boolean isImage = false;
                    if (mime != null) {
                        String lowMime = mime.toLowerCase();
                        if (lowMime.startsWith("image/") || lowMime.contains("webp") || lowMime.contains("avif") || lowMime.contains("heic")) {
                            isImage = true;
                        }
                    }
                    if (!isImage && url != null) {
                        if (PicUtil.isPicSuffix(url)) {
                            isImage = true;
                        }
                    }

                    if (isImage) {
                        InputStream is = (InputStream) XposedHelpers2.callMethod(response, "getData");
                        if (is != null) {
                            byte[] data = IOUtil.readToBytes(is);
                            if (data != null && data.length > 0) {
                                PicExportManager.getInstance().exportByteArray(data, null);
                                XposedHelpers2.callMethod(response, "setData", new ByteArrayInputStream(data));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });

        // 2. 注入实时监控脚本 (onPageFinished)
        XposedHelpers2.findAndHookMethod(webViewClientClazz, "onPageFinished", webViewClazz, String.class, new XC_MethodHook2() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                injectUltimateX5Js(param.args[0]);
            }
        });

        // 3. 监控控制台 (接收 PC_ 协议信号)
        if (webChromeClientClazz != null) {
            XposedHelpers2.hookAllMethods(webChromeClientClazz, "onConsoleMessage", new XC_MethodHook2() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                    Object msgObj = param.args[0];
                    if (msgObj == null) return;
                    String msg = (String) XposedHelpers2.callMethod(msgObj, "message");
                    if (msg != null) {
                        if (msg.startsWith("PC_U:")) {
                            String data = msg.substring(5);
                            String url = data;
                            Map<String, String> headers = null;
                            if (data.contains("|")) {
                                String[] parts = data.split("\\|");
                                url = parts[0];
                                if (parts.length > 1) {
                                    headers = new HashMap<>();
                                    headers.put("Referer", parts[1]);
                                }
                            }
                            PicExportManager.getInstance().exportUrlIfNeed(url, headers);
                        } else if (msg.startsWith("PC_B:")) {
                            PicExportManager.getInstance().exportDataUri(msg.substring(5));
                        }
                    }
                }
            });
        }

        // 4. 兜底：长按图片与数据加载
        if (webViewClazz != null) {
            XC_MethodHook2 loadDataHook = new XC_MethodHook2() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                    String data = (String) param.args[0];
                    if (data != null && data.contains("data:image")) PicExportManager.getInstance().exportUrlIfNeed(data);
                }
            };
            XposedHelpers2.findAndHookMethod(webViewClazz, "loadData", String.class, String.class, String.class, loadDataHook);
            XposedHelpers2.findAndHookMethod(webViewClazz, "loadDataWithBaseURL", String.class, String.class, String.class, String.class, String.class, loadDataHook);

            XposedHelpers2.findAndHookMethod(webViewClazz, "getHitTestResult", new XC_MethodHook2() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                    Object result = param.getResult();
                    if (result != null) {
                        int type = (int) XposedHelpers2.callMethod(result, "getType");
                        if (type == 5 || type == 8) {
                            String extra = (String) XposedHelpers2.callMethod(result, "getExtra");
                            if (extra != null) PicExportManager.getInstance().exportUrlIfNeed(extra);
                        }
                    }
                }
            });
        }
    }

    private void injectUltimateX5Js(Object webView) {
        String js = "(function() {" +
                "  const processed = new Set();" +
                "  const urlRegex = /\\\\.(jpg|jpeg|png|gif|webp|avif|heic|bmp)(\\\\?|$)/i;" +
                "  " +
                "  const oldFetch = window.fetch;" +
                "  window.fetch = function() {" +
                "    const arg = arguments[0];" +
                "    const url = (typeof arg === 'string') ? arg : (arg ? arg.url : '');" +
                "    if (url && urlRegex.test(url)) processUrl(url);" +
                "    return oldFetch.apply(this, arguments).then(res => {" +
                "      try {" +
                "        const cloned = res.clone();" +
                "        const cType = cloned.headers.get('content-type');" +
                "        if (cType && cType.includes('image')) {" +
                "          cloned.blob().then(b => extractBlobData(b, url));" +
                "        }" +
                "      } catch(e) {}" +
                "      return res;" +
                "    });" +
                "  };" +
                "  " +
                "  const oldXHR = window.XMLHttpRequest.prototype.open;" +
                "  window.XMLHttpRequest.prototype.open = function(method, url) {" +
                "    if (url && urlRegex.test(url)) processUrl(url);" +
                "    this.addEventListener('load', function() {" +
                "      try {" +
                "        const cType = this.getResponseHeader('content-type');" +
                "        if (cType && cType.includes('image')) {" +
                "           if (this.response instanceof Blob) extractBlobData(this.response, url);" +
                "        }" +
                "      } catch(e) {}" +
                "    });" +
                "    return oldXHR.apply(this, arguments);" +
                "  };" +
                "  " +
                "  function extractBlobData(blob, originUrl) {" +
                "    const rd = new FileReader();" +
                "    rd.onloadend = () => {" +
                "      const res = rd.result;" +
                "      if (res && res.startsWith('data:image') && !processed.has(res)) {" +
                "        processed.add(res);" +
                "        console.log('PC_B:' + res);" +
                "      }" +
                "    };" +
                "    rd.readAsDataURL(blob);" +
                "  }" +
                "  " +
                "  function scan(root) {" +
                "    if (!root) return;" +
                "    const elements = root.querySelectorAll ? root.querySelectorAll('img, image, canvas, source, [data-src], [data-original], [data-origin], [data-actual-src]') : [];" +
                "    elements.forEach(el => {" +
                "      if (el.tagName === 'CANVAS') {" +
                "        try { const d = el.toDataURL(\"image/png\"); if(d.length > 5000 && !processed.has(d)) { processed.add(d); console.log('PC_B:' + d); } } catch(e){}" +
                "        return;" +
                "      }" +
                "      const urls = [];" +
                "      ['src', 'data-src', 'data-original', 'data-origin', 'data-actual-src', 'data-lazy-src', 'xlink:href'].forEach(attr => {" +
                "        const v = el.getAttribute ? el.getAttribute(attr) : el[attr];" +
                "        if (v && typeof v === 'string') urls.push(v);" +
                "      });" +
                "      const srcset = el.getAttribute ? el.getAttribute('srcset') : null;" +
                "      if (srcset) {" +
                "        srcset.split(',').forEach(s => {" +
                "          const parts = s.trim().split(/\\\\s+/);" +
                "          if (parts[0]) urls.push(parts[0]);" +
                "        });" +
                "      }" +
                "      urls.forEach(u => processUrl(u));" +
                "    });" +
                "    const all = root.querySelectorAll ? root.querySelectorAll('*') : [];" +
                "    all.forEach(el => {" +
                "      if (el.shadowRoot) scan(el.shadowRoot);" +
                "      const style = window.getComputedStyle(el);" +
                "      const bg = style.backgroundImage;" +
                "      if (bg && bg !== 'none' && bg.includes('url(')) {" +
                "        const matches = bg.match(/url\\\\([\"']?([^\"']*)[\"']?\\\\)/g);" +
                "        if (matches) {" +
                "          matches.forEach(m => {" +
                "            const uStr = m.match(/url\\\\([\"']?([^\"']*)[\"']?\\\\)/);" +
                "            if (uStr && uStr[1]) processUrl(uStr[1]);" +
                "          });" +
                "        }" +
                "      }" +
                "    });" +
                "  }" +
                "  function processUrl(u) {" +
                "    try {" +
                "      if (!u || typeof u !== 'string' || u.startsWith('javascript:')) return;" +
                "      if (u.startsWith('data:image')) {" +
                "        if (!processed.has(u)) { processed.add(u); console.log('PC_B:' + u); }" +
                "        return;" +
                "      }" +
                "      const abs = new URL(u, document.baseURI).href;" +
                "      if (!processed.has(abs)) {" +
                "        processed.add(abs);" +
                "        if (abs.startsWith('blob:')) {" +
                "           fetch(abs).then(r => r.blob()).then(b => extractBlobData(b, abs)).catch(()=>{});" +
                "        } else {" +
                "           console.log('PC_U:' + abs + '|' + location.href);" +
                "        }" +
                "      }" +
                "    } catch (e) {}" +
                "  }" +
                "  let timer; function throttledScan() { clearTimeout(timer); timer = setTimeout(() => scan(document), 1000); }" +
                "  scan(document);" +
                "  new MutationObserver(throttledScan).observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['src','srcset','style','class']});" +
                "})();";
        XposedHelpers2.callMethod(webView, "evaluateJavascript", js, null);
    }
}
