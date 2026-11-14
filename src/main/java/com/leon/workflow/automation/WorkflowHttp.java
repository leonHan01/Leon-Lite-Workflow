package com.leon.workflow.automation;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.leon.workflow.automation.WorkflowJson.*;

@Component
public class WorkflowHttp {
    private final Set<String> privateOrigins;
    private final WorkflowCredentials credentials;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("workflow-http-deadline").factory());
    public WorkflowHttp(WorkflowCredentials credentials, @Value("${leon.workflows.http-allowed-origins:}") String origins) {
        this.credentials = credentials;
        privateOrigins = new HashSet<>();
        for (String origin : origins.split(",")) if (!origin.isBlank()) privateOrigins.add(origin(URI.create(origin.trim())));
    }
    public Map<String, Object> execute(Map<String, Object> config, Map<String, Object> input, Map<String, Object> ctx, WorkflowPrivacy privacy) throws Exception {
        URI uri;
        try { uri = URI.create(text(WorkflowExpressions.resolve(config.get("url"), input, ctx))); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("HTTP 地址格式无效"); }
        if (!Set.of("http", "https").contains(text(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("仅支持不含用户名、密码及片段的 HTTP/HTTPS 地址");
        boolean allowPrivate = privateOrigins.contains(origin(uri));
        DnsResolver dns = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (!allowPrivate) for (InetAddress address : addresses) if (!publicAddress(address)) throw new UnknownHostException("目标地址不允许访问");
                return addresses; // These exact validated addresses are used for the TCP connection, including literal IPs.
            }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        };
        // Validate literal addresses too; an implementation may bypass the resolver for numeric hosts.
        dns.resolve(uri.getHost());
        int timeout = integer(config.get("timeoutMs"), 10_000, 100, 30_000);
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(timeout)).setSocketTimeout(Timeout.ofMilliseconds(timeout)).build()).build();
        var request = new HttpUriRequestBase(text(config.getOrDefault("method", "GET")), uri);
        Map<String, Object> headers = object(WorkflowExpressions.resolve(config.getOrDefault("headers", Map.of()), input, ctx));
        privacy.collect(headers);
        for (var h : headers.entrySet()) header(request, h.getKey(), text(h.getValue()));
        Map<String, String> secretHeaders = credentials.headers(text(config.get("credentialId")), privacy);
        for (var h : secretHeaders.entrySet()) header(request, h.getKey(), h.getValue());
        if (!secretHeaders.isEmpty() && !"https".equals(uri.getScheme())) throw new IllegalArgumentException("使用凭据的请求必须使用 HTTPS");
        Object body = WorkflowExpressions.resolve(config.get("body"), input, ctx);
        if (body != null && !Set.of("GET", "HEAD").contains(request.getMethod())) {
            byte[] bytes = (body instanceof String s ? s : write(body)).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("HTTP 请求体不能超过 1 MB");
            request.setEntity(new ByteArrayEntity(bytes, ContentType.APPLICATION_JSON));
        }
        ScheduledFuture<?> deadline = deadlines.schedule(request::cancel, timeout, TimeUnit.MILLISECONDS);
        try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(timeout)).build()).build()) {
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status >= 300 && status < 400) throw new IllegalArgumentException("HTTP 返回重定向；请直接填写最终地址");
                if (status >= 400 && !Boolean.FALSE.equals(config.get("failOnHttpError"))) throw new IllegalArgumentException("HTTP 请求失败，状态码 " + status);
                byte[] bytes;
                if (response.getEntity() == null) bytes = new byte[0];
                else try (var stream = response.getEntity().getContent()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("HTTP 响应超过 1 MB");
                String content = new String(bytes, StandardCharsets.UTF_8);
                Object parsed = content;
                try { if (!content.isBlank()) parsed = JSON.readValue(content, Object.class); } catch (Exception ignored) {}
                Map<String, Object> responseHeaders = new LinkedHashMap<>();
                for (var h : response.getHeaders()) responseHeaders.put(h.getName().toLowerCase(Locale.ROOT), h.getValue());
                Map<String, Object> output = Map.of("items", List.of(Map.of("json", Map.of("status", status, "headers", responseHeaders, "body", parsed == null ? "" : parsed))));
                checkSize(output); return output;
            });
        } catch (java.io.IOException e) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw new IllegalArgumentException("HTTP 请求未完成：目标受限、连接失败或超时；写操作请确认对端结果后重试");
        } finally { deadline.cancel(false); manager.close(); }
    }
    private void header(HttpUriRequestBase request, String name, String value) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}") || value.length() > 8192 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || Set.of("host", "content-length", "connection", "transfer-encoding", "proxy-authorization", "proxy-connection", "upgrade", "trailer", "te").contains(name.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("HTTP Header 无效或使用了保留名称");
        request.setHeader(name, value);
    }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress(); int first = b[0] & 255;
        if (b.length == 16) return (first & 0xe0) == 0x20 && (b[1] & 255) != 0x02
                && !(first == 0x20 && (b[1] & 255) == 0x01 && (((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8) || (b[2] == 0 && b[3] == 0)));
        int second = b[1] & 255, third = b[2] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127) && !(first == 198 && (second == 18 || second == 19))
                && !(first == 192 && second == 0 && (third == 0 || third == 2)) && !(first == 198 && second == 51 && third == 100) && !(first == 203 && second == 0 && third == 113);
    }
    private String origin(URI uri) { return uri.getScheme() + "://" + uri.getHost() + ":" + (uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort()); }
    @PreDestroy void close() { deadlines.shutdownNow(); }
}
