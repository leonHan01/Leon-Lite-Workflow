package com.leon.workflow.automation;

import org.springframework.stereotype.Component;
import org.jsoup.Jsoup;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Public RSS/Atom subscriptions. Consumption is committed by WorkflowStore only after run success. */
@Component
public class WorkflowRss {
    static final int DEFAULT_TIMEOUT = 60_000, MAX_TIMEOUT = 120_000;
    private final WorkflowHttp http;
    private final WorkflowStore store;
    public WorkflowRss(WorkflowHttp http, WorkflowStore store) { this.http = http; this.store = store; }
    record Article(Map<String, Object> json, Set<String> keys, Instant date) {}

    static List<Map<String, Object>> sources(Map<String, Object> config) {
        if (!(config.get("sources") instanceof List<?> list) || list.isEmpty() || list.size() > 20)
            throw new IllegalArgumentException("请配置 1–20 个 RSS 订阅源");
        return list.stream().map(WorkflowJson::object).toList();
    }
    static void validateConfig(Map<String, Object> config) {
        for (var source : sources(config)) {
            required(source.get("name"), "请填写订阅源名称（最多 100 字）", 100);
            feedUrl(source.get("url"));
            if (text(source.get("titleContains")).length() > 200) throw new IllegalArgumentException("标题筛选最多 200 字");
        }
        integer(config.get("maxItems"), 35, 1, 100);
        integer(config.get("perSource"), 5, 1, 50);
        integer(config.get("lookbackHours"), 168, 0, 8760);
        integer(config.get("timeoutMs"), DEFAULT_TIMEOUT, 1000, MAX_TIMEOUT);
        if (config.containsKey("onlyNew") && !(config.get("onlyNew") instanceof Boolean)) throw new IllegalArgumentException("新增模式需要布尔值");
    }
    static String feedUrl(Object value) {
        String url = required(value, "请填写 RSS / Atom 订阅地址", 8192);
        try {
            var uri = URI.create(url);
            if (!Set.of("http", "https").contains(text(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (Exception e) { throw new IllegalArgumentException("订阅地址须为不含账号密码及片段的 HTTP/HTTPS 地址"); }
        return url;
    }
    public Map<String, Object> execute(Map<String, Object> config, String runId, String nodeId, WorkflowPrivacy privacy, Consumer<String> trace) throws Exception {
        validateConfig(config);
        boolean onlyNew = !Boolean.FALSE.equals(config.get("onlyNew"));
        Set<String> known = onlyNew ? store.rssSeen(runId, nodeId) : Set.of(), batchKeys = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        int max = integer(config.get("maxItems"), 35, 1, 100), perSource = integer(config.get("perSource"), 5, 1, 50);
        int lookback = integer(config.get("lookbackHours"), 168, 0, 8760), successes = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(integer(config.get("timeoutMs"), DEFAULT_TIMEOUT, 1000, MAX_TIMEOUT));
        Instant now = Instant.now(), cutoff = lookback == 0 ? Instant.MIN : now.minus(Duration.ofHours(lookback));
        // Round-robin prevents a busy source from crowding all the others out of the digest.
        List<List<Article>> groups = new ArrayList<>();
        for (var source : sources(config)) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String name = text(source.get("name"));
            try {
                int remaining = (int)Math.min(15_000, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (remaining < 100) throw new IllegalArgumentException("订阅抓取总超时");
                var response = http.executeResolved(Map.of("url", text(source.get("url")), "method", "GET", "timeoutMs", remaining,
                        "headers", Map.of("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*", "User-Agent", "LeonWorkflow/1.0")), privacy);
                Object body = object(object(((List<?>)response.get("items")).getFirst()).get("json")).get("body");
                List<Article> articles = parse(text(body), source);
                List<Article> available = articles.stream().filter(a -> a.date() == null || (!a.date().isBefore(cutoff) && !a.date().isAfter(now.plus(Duration.ofDays(1)))))
                        .filter(a -> Collections.disjoint(known, a.keys())).sorted(Comparator.comparing((Article a) -> a.date() == null ? Instant.EPOCH : a.date()).reversed()).toList();
                groups.add(available); successes++;
                trace.accept(name + "：读取 " + articles.size() + " 篇，符合新增条件 " + available.size() + " 篇");
            } catch (InterruptedException e) { throw e; }
            catch (Exception e) {
                String warning = name + "：" + privacy.message(text(e.getMessage()));
                warnings.add(warning); groups.add(List.of()); trace.accept("订阅源暂不可用；" + warning);
            }
        }
        if (successes == 0) throw new IllegalArgumentException("所有 RSS 订阅源均获取失败，请检查节点日志及订阅地址");
        int[] selected = new int[groups.size()], cursor = new int[groups.size()];
        boolean added;
        do {
            added = false;
            for (int i = 0; i < groups.size() && items.size() < max; i++) {
                var group = groups.get(i);
                while (selected[i] < perSource && cursor[i] < group.size()) {
                    var article = group.get(cursor[i]++);
                    if (!Collections.disjoint(batchKeys, article.keys())) continue;
                    items.add(Map.of("json", article.json())); batchKeys.addAll(article.keys()); selected[i]++; added = true; break;
                }
            }
        } while (added && items.size() < max);
        var output = envelope(Map.of("items", items, "rss", Map.of("sourceCount", sources(config).size(), "availableSources", successes, "warnings", warnings, "articleCount", items.size())));
        if (onlyNew) store.stageRss(runId, nodeId, batchKeys);
        trace.accept("RSS 完成：" + items.size() + " 篇；" + (runId.startsWith("preview-") ? "调试不记录已处理状态" : "整条工作流成功后确认去重记录"));
        return output;
    }
    static List<Article> parse(String xml, Map<String, Object> source) {
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            });
            var document = builder.parse(new InputSource(new StringReader(xml)));
            String root = local(document.getDocumentElement());
            if (!Set.of("rss", "feed", "RDF").contains(root)) throw new IllegalArgumentException("地址返回的不是 RSS / Atom 文档");
            List<Article> result = new ArrayList<>();
            NodeList entries = document.getElementsByTagNameNS("*", "feed".equals(root) ? "entry" : "item");
            for (int i = 0; i < Math.min(entries.getLength(), 5000); i++) {
                Element entry = (Element)entries.item(i);
                String title = clean(child(entry, "title"), 500), filter = text(source.get("titleContains"));
                if (!filter.isBlank() && !title.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
                String link = child(entry, "link");
                if ("feed".equals(root)) for (Element e : children(entry, "link")) {
                    if (e.getAttribute("rel").isBlank() || "alternate".equals(e.getAttribute("rel"))) { link = e.getAttribute("href"); break; }
                }
                link = articleUrl(text(source.get("url")), link);
                String guid = first(child(entry, "guid"), child(entry, "id"));
                String rawDate = first(child(entry, "pubDate"), child(entry, "published"), child(entry, "updated"), child(entry, "date"));
                String content = clean(first(child(entry, "encoded"), child(entry, "content"), child(entry, "description"), child(entry, "summary")), 6000);
                if (title.isBlank() && content.isBlank()) continue;
                if (title.isBlank()) title = clean(content, 100);
                Set<String> keys = new HashSet<>();
                if (!link.isBlank()) keys.add(hash("url:" + canonical(link)));
                if (!guid.isBlank()) keys.add(hash("guid:" + source.get("url") + ":" + guid));
                if (keys.isEmpty()) keys.add(hash("entry:" + source.get("url") + ":" + title + ":" + rawDate));
                result.add(new Article(Map.of("title", title, "url", link, "source", text(source.get("name")), "publishedAt", rawDate, "content", content), keys, date(rawDate)));
            }
            return result;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("订阅 XML 无效或包含不允许的外部实体/DOCTYPE"); }
    }
    private static String local(Node node) { return node.getLocalName() == null ? node.getNodeName() : node.getLocalName(); }
    private static List<Element> children(Element node, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element e && name.equals(local(e))) result.add(e);
        return result;
    }
    private static String child(Element node, String name) { var children = children(node, name); return children.isEmpty() ? "" : children.getFirst().getTextContent().trim(); }
    private static String first(String... values) { for (String s : values) if (!s.isBlank()) return s; return ""; }
    private static String clean(String html, int max) { String value = Jsoup.parse(html).text(); return value.substring(0, Math.min(max, value.length())); }
    static String articleUrl(String base, String link) {
        try { URI uri = URI.create(base).resolve(link.trim()); return !link.isBlank() && Set.of("http", "https").contains(text(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null ? uri.toString() : ""; }
        catch (Exception e) { return ""; }
    }
    static String canonical(String url) {
        URI uri = URI.create(url);
        String query = uri.getRawQuery() == null ? "" : Arrays.stream(uri.getRawQuery().split("&")).filter(v -> !v.toLowerCase(Locale.ROOT).matches("(?:utm_[^=]*|fbclid|gclid)=.*")).reduce((a,b) -> a + "&" + b).orElse("");
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + uri.getRawPath() + (query.isEmpty() ? "" : "?" + query);
    }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static Instant date(String value) { try { return Instant.parse(value); } catch (Exception ignored) {} try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (Exception ignored) {} return null; }
}
