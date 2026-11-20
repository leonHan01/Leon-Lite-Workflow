package com.leon.workflow.admin;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.webkit.*;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Browser features needed by the existing management workspace, with one trusted origin. */
final class WorkspaceBrowser {
    static final int REQUEST_UPLOAD = 201;
    interface Listener {
        void progress(int value);
        void location(String path);
        void error(String message);
    }
    final WebView view;
    private final Activity activity;
    private final ServerAddress server;
    private final Listener listener;
    private final ExportController exports;
    private final Predicate<String> managementPage;
    private ValueCallback<Uri[]> upload;
    private String pendingPath;
    private String currentPath = "/index.html";
    private boolean pageFailed;
    private boolean rendererGone;
    private boolean desktop;
    private final String injection;
    private final String handshake = java.util.UUID.randomUUID().toString();

    @SuppressLint("SetJavaScriptEnabled")
    WorkspaceBrowser(Activity activity, ServerAddress server, Listener listener, boolean desktop, Predicate<String> managementPage) {
        this.activity = activity; this.server = server; this.listener = listener;
        this.managementPage = managementPage;
        exports = new ExportController(activity, server);
        view = new WebView(activity);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString(settings.getUserAgentString() + " LeonWorkflowAdmin/1.0");
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        WebView.setWebContentsDebuggingEnabled(false);
        String css = asset("native-workspace.css");
        String script = asset("native-workspace.js");
        injection = "(() => {if(!document.getElementById('leon-native-style')){const s=document.createElement('style');"
                + "s.id='leon-native-style';s.textContent=" + JSONObject.quote(css) + ";document.head.append(s);}})();"
                + script.replace("__LEON_NATIVE_HANDSHAKE__", handshake);
        setDesktop(desktop);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView browser, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (server.owns(url)) {
                    String destination = path(url);
                    if (request.isForMainFrame() && "GET".equals(request.getMethod()) && managementPage.test(destination)
                            && !"/index.html".equals(request.getUrl().getPath()) && !"/".equals(request.getUrl().getPath())) {
                        // Login redirects and document reloads must use the same protected entry
                        // as native navigation, even when a route lacks a proxy HTML location.
                        currentPath = destination; pendingPath = destination; loadWorkspaceShell();
                        return true;
                    }
                    return false;
                }
                if (request.isForMainFrame() && request.hasGesture()) external(request.getUrl());
                return true;
            }
            @Override public void onPageStarted(WebView browser, String url, Bitmap icon) {
                if (server.owns(url) && "/admin-login.html".equals(Uri.parse(url).getPath()) && pendingPath == null)
                    pendingPath = currentPath;
                pageFailed = false; listener.progress(0);
            }
            @Override public void doUpdateVisitedHistory(WebView browser, String url, boolean reload) {
                if (!server.owns(url)) return;
                String visited = path(url);
                if (pendingPath == null && managementPage.test(visited)) currentPath = visited;
                listener.location(pendingPath != null && "/index.html".equals(Uri.parse(url).getPath()) ? pendingPath : visited);
            }
            @Override public void onPageFinished(WebView browser, String url) {
                if (pageFailed || !server.owns(url) || !url.equals(browser.getUrl())) return;
                applyViewport();
                browser.evaluateJavascript(injection, ignored -> {
                    if (!url.equals(browser.getUrl())) return;
                    WebMessagePort[] channel = browser.createWebMessageChannel();
                    exports.attach(channel[0]);
                    browser.postWebMessage(new WebMessage(handshake, new WebMessagePort[]{channel[1]}), Uri.parse(server.origin()));
                    navigatePendingPage(true, false);
                });
                CookieManager.getInstance().flush();
                listener.progress(100);
            }
            @Override public void onReceivedError(WebView browser, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) fail("连接失败，请检查网络和服务地址后重试。");
            }
            @Override public void onReceivedHttpError(WebView browser, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    int code = response.getStatusCode();
                    fail(code == 401 ? "登录已过期，请重新登录。" : code == 403
                            ? "服务器拒绝访问此页面（HTTP 403），请返回工作台重新进入。"
                            : "服务器暂时无法打开此页面（HTTP " + code + "）。");
                }
            }
            @Override public void onReceivedSslError(WebView browser, SslErrorHandler handler, SslError error) {
                handler.cancel(); fail("HTTPS 证书校验失败，请检查服务器证书和设备时间。");
            }
            @Override public boolean onRenderProcessGone(WebView browser, RenderProcessGoneDetail detail) {
                rendererGone = true; fail("网页组件已停止，请点击重试重新打开。"); return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView browser, int progress) { listener.progress(progress); }
            @Override public boolean onShowFileChooser(WebView browser, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (upload != null) upload.onReceiveValue(null);
                upload = callback;
                if (!server.owns(browser.getUrl())) { finishUpload(null); return true; }
                List<String> types = new ArrayList<>();
                for (String type : params.getAcceptTypes()) {
                    for (String item : type.split(",")) {
                        item = item.trim();
                        if (item.startsWith(".")) item = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.substring(1));
                        if (item != null && item.matches("[a-zA-Z0-9.+*-]+/[a-zA-Z0-9.+*-]+")) types.add(item);
                    }
                }
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(types.size() == 1 ? types.get(0) : "*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (types.size() > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                try { activity.startActivityForResult(intent, REQUEST_UPLOAD); }
                catch (Exception error) { finishUpload(null); listener.error("当前设备没有可用的文件选择器。"); }
                return true;
            }
            @Override public boolean onCreateWindow(WebView browser, boolean dialog, boolean gesture, Message result) {
                if (!gesture) return false;
                WebView popup = new WebView(activity);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        String url = request.getUrl().toString();
                        if (server.owns(url)) open(path(url)); else external(request.getUrl());
                        child.post(child::destroy);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(popup); result.sendToTarget();
                return true;
            }
        });
        view.setDownloadListener((url, userAgent, disposition, mime, size) -> {
            if (server.owns(url)) exports.requestUrl(url, URLUtil.guessFileName(url, disposition, mime), mime);
            else listener.error("请通过导出按钮保存文件。");
        });
    }

    void open(String path) {
        server.resolve(path); // Validate the destination before using it in the page router.
        currentPath = path; pendingPath = path;
        if (!server.owns(view.getUrl())) { loadWorkspaceShell(); return; }
        navigatePendingPage(false, true);
    }

    private void navigatePendingPage(boolean replace, boolean bootstrapIfMissing) {
        String destination = pendingPath;
        if (destination == null) return;
        String history = replace ? "history.replaceState(null,''," + JSONObject.quote(destination) + ");" : "";
        view.evaluateJavascript("(() => {if(!document.getElementById('workspaceViews'))return false;"
                + "import('/workspace/app.js').then(m=>{" + history + "return m.navigate(" + JSONObject.quote(destination)
                + ",{historyMode:" + JSONObject.quote(replace ? "none" : "push") + "});})"
                + ".catch(()=>{const s=document.getElementById('workspaceStatus');if(s){s.hidden=false;"
                + "s.textContent='管理页面加载失败，请点击刷新重试。';}});return true;})();", result -> {
            if (!destination.equals(pendingPath)) return;
            if ("true".equals(result)) pendingPath = null;
            else if (bootstrapIfMissing) loadWorkspaceShell();
        });
    }

    private void loadWorkspaceShell() { view.loadUrl(server.resolve("/index.html")); }

    void reload() {
        if (rendererGone) activity.recreate();
        else { pendingPath = currentPath; loadWorkspaceShell(); }
    }

    void setDesktop(boolean value) {
        desktop = value;
        view.setInitialScale(0);
        if (value) view.getSettings().setUserAgentString(view.getSettings().getUserAgentString().replace("; wv", "").replace("Mobile", "Desktop"));
        else view.getSettings().setUserAgentString(WebSettings.getDefaultUserAgent(activity) + " LeonWorkflowAdmin/1.0");
        // A wide layout is useful for the workflow canvas, without changing the phone's orientation.
        view.getSettings().setMinimumFontSize(value ? 8 : 12);
        applyViewport();
    }

    private void applyViewport() {
        view.evaluateJavascript("(() => {let m=document.querySelector('meta[name=viewport]');if(m)m.content="
                + JSONObject.quote(desktop ? "width=1280, initial-scale=0.4" : "width=device-width, initial-scale=1") + ";})();", null);
    }

    void result(int code, int result, Intent data) {
        if (code == ExportController.REQUEST_SAVE) exports.result(result, data);
        if (code == REQUEST_UPLOAD) {
            List<Uri> uris = new ArrayList<>();
            if (result == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
            }
            uris.removeIf(uri -> !"content".equals(uri.getScheme()));
            finishUpload(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        }
    }

    void close() { finishUpload(null); exports.close(); view.stopLoading(); view.destroy(); }
    private void finishUpload(Uri[] values) { if (upload != null) { upload.onReceiveValue(values); upload = null; } }
    private void fail(String message) { pageFailed = true; listener.progress(100); listener.error(message); }
    private String asset(String name) {
        try (var stream = activity.getAssets().open(name); var buffer = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = stream.read(bytes)) != -1) buffer.write(bytes, 0, count);
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
        catch (Exception error) { throw new IllegalStateException("Missing Android workspace asset", error); }
    }
    private void external(Uri uri) {
        String scheme = uri.getScheme();
        if (!java.util.Arrays.asList("https", "http", "mailto", "tel").contains(scheme)) return;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (Exception error) { listener.error("没有可打开此链接的应用。"); }
    }
    static String path(String url) {
        Uri uri = Uri.parse(url);
        return (uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath())
                + (uri.getEncodedQuery() == null ? "" : "?" + uri.getEncodedQuery());
    }
}
