package com.pic.catcher.plugin;

import android.content.Context;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.lu.lposed.api2.XC_MethodHook2;
import com.lu.lposed.api2.XposedHelpers2;
import com.lu.lposed.plugin.IPlugin;
import com.lu.magic.util.IOUtil;
import com.pic.catcher.config.ModuleConfig;
import com.pic.catcher.util.PicUtil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 最终强化版 WebView 拦截插件
 */
public class WebViewCatcherPlugin implements IPlugin {

    @Override
    public void handleHook(Context context, XC_LoadPackage.LoadPackageParam loadPackageParam) {
        hookWebViewClient();
        hookWebChromeClient();
        hookWebViewMethods();
    }

    private void hookWebViewClient() {
        XposedHelpers2.hookAllMethods(WebViewClient.class, "shouldInterceptRequest", new XC_MethodHook2() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;

                String url = null;
                Map<String, String> headers = null;
                if (param.args.length >= 2) {
                    Object arg = param.args[1];
                    if (arg instanceof WebResourceRequest) {
                        WebResourceRequest request = (WebResourceRequest) arg;
                        url = request.getUrl().toString();
                        headers = request.getRequestHeaders();
                    } else if (arg instanceof String) {
                        url = (String) arg;
                    }
                }
                if (url != null) PicExportManager.getInstance().exportUrlIfNeed(url, headers);

                Object result = param.getResult();
                if (result instanceof WebResourceResponse) {
                    processWrr((WebResourceResponse) result, url);
                }
            }
        });

        XposedHelpers2.findAndHookMethod(WebViewClient.class, "onPageFinished", WebView.class, String.class, new XC_MethodHook2() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                injectUltimateJs((WebView) param.args[0]);
            }
        });
    }

    private void hookWebChromeClient() {
        XposedHelpers2.hookAllMethods(WebChromeClient.class, "onConsoleMessage", new XC_MethodHook2() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                Object arg = param.args[0];
                String msg = (arg instanceof ConsoleMessage) ? ((ConsoleMessage) arg).message() : String.valueOf(arg);
                
                if (msg != null) {
                    if (msg.startsWith("PC_U:")) {
                        handleProtocolUrl(msg.substring(5));
                    } else if (msg.startsWith("PC_B:")) {
                        PicExportManager.getInstance().exportDataUri(msg.substring(5));
                    }
                }
            }
        });
    }

    private void handleProtocolUrl(String data) {
        String url = data;
        Map<String, String> headers = null;
        if (data.contains("|")) {
            String[] parts = data.split("\\|");
            url = parts[0];
            if (parts.length > 1) {
                headers = new java.util.HashMap<>();
                headers.put("Referer", parts[1]);
            }
        }
        PicExportManager.getInstance().exportUrlIfNeed(url, headers);
    }

    private void hookWebViewMethods() {
        XC_MethodHook2 loadDataHook = new XC_MethodHook2() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                String data = (String) param.args[0];
                if (data != null && data.contains("data:image")) PicExportManager.getInstance().exportUrlIfNeed(data);
            }
        };
        XposedHelpers2.findAndHookMethod(WebView.class, "loadData", String.class, String.class, String.class, loadDataHook);
        XposedHelpers2.findAndHookMethod(WebView.class, "loadDataWithBaseURL", String.class, String.class, String.class, String.class, String.class, loadDataHook);

        XposedHelpers2.findAndHookMethod(WebView.class, "getHitTestResult", new XC_MethodHook2() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ModuleConfig.getInstance().isCatchWebViewPic()) return;
                Object res = param.getResult();
                if (res != null) {
                    int type = (int) XposedHelpers2.callMethod(res, "getType");
                    if (type == 5 || type == 8) {
                        String extra = (String) XposedHelpers2.callMethod(res, "getExtra");
                        if (extra != null) PicExportManager.getInstance().exportUrlIfNeed(extra);
                    }
                }
            }
        });
    }

    private void processWrr(WebResourceResponse wrr, String url) {
        try {
            String mime = wrr.getMimeType();
            if (mime != null && (mime.contains("image") || mime.contains("webp") || mime.contains("avif"))) {
                InputStream is = wrr.getData();
                if (is != null) {
                    byte[] data = IOUtil.readToBytes(is);
                    if (data != null && data.length > 0) {
                        PicExportManager.getInstance().exportByteArray(data, null);
                        wrr.setData(new ByteArrayInputStream(data));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void injectUltimateJs(WebView wv) {
        String js = "(function() {" +
                "  const processed = new Set();" +
                "  const urlRegex = /\\\\.(jpg|jpeg|png|gif|webp|avif|heic|bmp)(\\\\?|$)/i;" +
                "  " +
                "  // 1. Hook fetch" +
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
                "          cloned.blob().then(b => extractBlob(b, url));" +
                "        }" +
                "      } catch(e) {}" +
                "      return res;" +
                "    });" +
                "  };" +
                "  " +
                "  // 2. Hook XMLHttpRequest" +
                "  const oldXHR = window.XMLHttpRequest.prototype.open;" +
                "  window.XMLHttpRequest.prototype.open = function(method, url) {" +
                "    if (url && urlRegex.test(url)) processUrl(url);" +
                "    this.addEventListener('load', function() {" +
                "      try {" +
                "        const cType = this.getResponseHeader('content-type');" +
                "        if (cType && cType.includes('image')) {" +
                "          if (this.response instanceof Blob) extractBlob(this.response, url);" +
                "          else if (this.responseType === 'blob') extractBlob(this.response, url);" +
                "        }" +
                "      } catch(e) {}" +
                "    });" +
                "    return oldXHR.apply(this, arguments);" +
                "  };" +
                "  " +
                "  // 3. Hook Image.src" +
                "  const nativeSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');" +
                "  if (nativeSrcDescriptor) {" +
                "    Object.defineProperty(HTMLImageElement.prototype, 'src', {" +
                "      set: function(val) { processUrl(val); return nativeSrcDescriptor.set.apply(this, arguments); }," +
                "      get: function() { return nativeSrcDescriptor.get.apply(this); }" +
                "    });" +
                "  }" +
                "  " +
                "  function processUrl(u) {" +
                "    if (!u || typeof u !== 'string' || u.startsWith('javascript:')) return;" +
                "    try {" +
                "      const abs = new URL(u, document.baseURI).href;" +
                "      if (!processed.has(abs)) {" +
                "        processed.add(abs);" +
                "        if (abs.startsWith('data:image')) console.log('PC_B:' + abs);" +
                "        else if (abs.startsWith('blob:')) fetch(abs).then(r => r.blob()).then(b => extractBlob(b, abs)).catch(()=>{});" +
                "        else console.log('PC_U:' + abs + '|' + location.href);" +
                "      }" +
                "    } catch(e){}" +
                "  }" +
                "  " +
                "  function extractBlob(blob, originUrl) {" +
                "    const rd = new FileReader();" +
                "    rd.onloadend = () => {" +
                "      const res = rd.result;" +
                "      if(res && res.startsWith('data:image') && !processed.has(res)) {" +
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
                "      if(el.tagName === 'CANVAS') {" +
                "        try { const d = el.toDataURL('image/png'); if(d.length > 5000 && !processed.has(d)) { processed.add(d); console.log('PC_B:'+d); } } catch(e){}" +
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
                "    " +
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
                "  " +
                "  let timer;" +
                "  function throttledScan() { clearTimeout(timer); timer = setTimeout(() => scan(document), 1000); }" +
                "  scan(document);" +
                "  new MutationObserver(throttledScan).observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['src','srcset','style','class']});" +
                "})();";
        wv.evaluateJavascript(js, null);
    }
}
