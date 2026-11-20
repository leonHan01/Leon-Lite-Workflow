package com.leon.workflow.admin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exports go straight to a user-selected document, never to a public temporary file. */
final class ExportController {
    static final int REQUEST_SAVE = 200;
    private final Activity activity;
    private final ServerAddress server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebMessagePort port;
    private Job pending;
    private volatile boolean closed;

    private static final class Job {
        String id, url, name, mime, cookie;
        long size, written;
        Uri destination;
        OutputStream output;
        volatile boolean cancelled;
    }

    ExportController(Activity activity, ServerAddress server) { this.activity = activity; this.server = server; }

    void attach(WebMessagePort next) {
        cancel();
        if (port != null) port.close();
        port = next;
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override public void onMessage(WebMessagePort ignored, WebMessage message) {
                if (closed || message.getData() == null || message.getData().length() > 70000) return;
                try { receive(new JSONObject(message.getData())); }
                catch (Exception error) { cancel(); toast("导出失败，请重试"); }
            }
        });
    }

    private void receive(JSONObject message) throws Exception {
        String type = message.optString("type");
        if ("notice".equals(type)) { toast("无法读取导出文件，请重新导出"); return; }
        if ("export-url".equals(type)) {
            requestUrl(message.getString("url"), message.optString("name"), message.optString("mime"));
        } else if ("blob-start".equals(type)) {
            Job job = new Job();
            job.id = message.getString("id"); job.size = message.getLong("size");
            if (!job.id.matches("[0-9]{1,12}") || job.size < 0 || job.size > 2L * 1024 * 1024 * 1024) return;
            job.name = ServerAddress.filename(message.optString("name"));
            job.mime = mime(message.optString("mime"));
            choose(job);
        } else {
            Job job = pending;
            if (job == null || job.id == null || !job.id.equals(message.optString("id"))) return;
            if ("blob-abort".equals(type)) { cancel(); return; }
            if ("blob-chunk".equals(type)) {
                String data = message.getString("data");
                worker.execute(() -> {
                    try {
                        if (job.cancelled || job.output == null) return;
                        byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
                        if (bytes.length > 49152 || job.written + bytes.length > job.size) throw new IllegalStateException();
                        job.output.write(bytes); job.written += bytes.length;
                        reply("blob-ack", job);
                    } catch (Exception error) { fail(job); }
                });
            } else if ("blob-end".equals(type)) {
                worker.execute(() -> {
                    try {
                        if (job.cancelled || job.output == null) return;
                        if (job.written != job.size) throw new IllegalStateException();
                        job.output.close(); job.output = null;
                        finish(job);
                    } catch (Exception error) { fail(job); }
                });
            }
        }
    }

    void requestUrl(String url, String name, String contentType) {
        if (!server.owns(url)) { toast("只支持下载当前服务的文件"); return; }
        Job job = new Job(); job.url = url;
        job.name = ServerAddress.filename(name == null || name.trim().isEmpty() ? URLUtil.guessFileName(url, null, contentType) : name);
        job.mime = mime(contentType);
        job.cookie = CookieManager.getInstance().getCookie(url);
        choose(job);
    }

    private void choose(Job job) {
        if (pending != null) { reply("blob-cancel", job); toast("请先完成当前文件的保存"); return; }
        pending = job;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(job.mime).putExtra(Intent.EXTRA_TITLE, job.name);
        try { activity.startActivityForResult(intent, REQUEST_SAVE); }
        catch (Exception error) { cancel(); toast("当前设备没有可用的文件保存器"); }
    }

    void result(int resultCode, Intent data) {
        Job job = pending;
        if (job == null) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null
                || !"content".equals(data.getData().getScheme())) { cancel(); return; }
        job.destination = data.getData();
        toast("正在保存文件…");
        worker.execute(() -> {
            try {
                job.output = activity.getContentResolver().openOutputStream(job.destination, "wt");
                if (job.output == null || job.cancelled) throw new IllegalStateException();
                if (job.url == null) reply("blob-ready", job);
                else download(job);
            } catch (Exception error) { fail(job); }
        });
    }

    private void download(Job job) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(job.url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
            if (job.cookie != null) connection.setRequestProperty("Cookie", job.cookie);
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException();
            // A proxy may serve its login form with HTTP 200. Never save that as a backup.
            String type = connection.getContentType();
            if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) throw new IllegalStateException();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (job.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    job.output.write(buffer, 0, count);
                }
            }
            job.output.close(); job.output = null; finish(job);
        } finally { connection.disconnect(); }
    }

    private void finish(Job job) {
        activity.runOnUiThread(() -> { if (pending == job) pending = null; if (!closed) toast("文件已保存"); });
    }

    private void fail(Job job) {
        job.cancelled = true;
        discard(job);
        activity.runOnUiThread(() -> {
            if (pending == job) pending = null;
            reply("blob-cancel", job);
            if (!closed) toast("保存失败，请检查登录状态、网络或存储空间后重试");
        });
    }

    private void reply(String type, Job job) {
        if (job.id == null) return;
        activity.runOnUiThread(() -> {
            if (port == null || closed) return;
            try { port.postMessage(new WebMessage(new JSONObject().put("type", type).put("id", job.id).toString())); }
            catch (Exception ignored) { /* Navigation closes the document's message port. */ }
        });
    }

    void cancel() {
        Job job = pending; pending = null;
        if (job == null) return;
        job.cancelled = true;
        reply("blob-cancel", job);
        worker.execute(() -> discard(job));
    }

    private void discard(Job job) {
        try { if (job.output != null) job.output.close(); } catch (Exception ignored) { }
        job.output = null;
        if (job.destination != null) {
            try { DocumentsContract.deleteDocument(activity.getContentResolver(), job.destination); }
            catch (Exception ignored) { /* Providers may retain an incomplete file if deletion is unsupported. */ }
        }
    }

    void close() {
        cancel(); closed = true;
        if (port != null) port.close();
        port = null; worker.shutdown();
    }

    private void toast(String message) { activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show()); }
    private static String mime(String value) {
        return value != null && value.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? value : "application/octet-stream";
    }
}
