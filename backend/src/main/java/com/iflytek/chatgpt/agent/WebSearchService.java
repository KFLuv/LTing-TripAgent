package com.iflytek.chatgpt.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网检索：本地库（spot / spot_guide）查不到时，用它去检索真实网页。
 * <p>
 * 检索源按配置自动选择：
 * <ul>
 *   <li>配了 {@code search.api-key} → 用<b>博查 AI 搜索</b>（国内直连、专为 AI 应用设计，结果质量好）；</li>
 *   <li>没配 → 退回<b>必应中国</b>网页抓取兜底（无需 Key，但结果偏泛化）。</li>
 * </ul>
 * 两者都属于"尽力而为"：不通时返回空结果 + note，由模型如实告知用户"暂时查不到"，
 * 绝不允许编造。
 */
@Component
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    /** 首选：博查 AI 搜索，POST JSON */
    private static final String BOCHA_ENDPOINT = "https://api.bochaai.com/v1/web-search";

    /** 兜底：必应中国网页抓取，ensearch=0 强制国内版结果 */
    private static final String BING_ENDPOINT = "https://cn.bing.com/search?ensearch=0&q=";

    /** 每条结果的起始标记（仅必应抓取用） */
    private static final Pattern BING_BLOCK_MARKER = Pattern.compile("class=\"b_algo\"");

    /** 结果标题与链接：<h2><a href="真实地址">标题</a></h2>（仅必应抓取用） */
    private static final Pattern BING_TITLE_LINK = Pattern.compile(
            "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    /** 结果摘要：<div class="b_caption"><p>摘要</p>（仅必应抓取用） */
    private static final Pattern BING_SNIPPET = Pattern.compile(
            "<div class=\"b_caption\"[^>]*>\\s*<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** 单个结果块最多截取这么多字符，避免某个块吞掉整页 */
    private static final int MAX_BLOCK_CHARS = 3000;

    /** 同一关键词 10 分钟内复用结果，避免反复打扰检索入口 */
    private static final long CACHE_TTL_MILLIS = 10 * 60_000L;
    private static final int MAX_CACHE = 100;

    private static final String SUCCESS_NOTE =
            "以上是网页检索结果。请结合用户位置与行程整理成结论，并标注来源站点，"
                    + "同时提醒「攻略类信息以实地为准」。";

    /** 博查 API Key；填了就用博查，留空退回必应抓取 */
    @Value("${search.api-key:}")
    private String apiKey;

    /**
     * 一般留空走直连——两个检索源在国内都可直连，比绕代理更快更稳；
     * 只有确实需要走代理时才填，形如 127.0.0.1:7890。
     */
    @Value("${search.proxy:}")
    private String proxy;

    private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    /**
     * 强制直连的 selector。
     * 本应用为兼容定位反查开启了 {@code java.net.useSystemProxies}，若不显式指定，
     * 这里的 HttpClient 会默认套上系统代理；国内检索源不需要代理，显式直连更稳。
     */
    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // 直连无需处理代理失败回调
        }
    };

    @PostConstruct
    void init() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        ProxySelector selector = resolveProxySelector();
        builder.proxy(selector != null ? selector : DIRECT);
        this.httpClient = builder.build();
    }

    /** 只有显式配置了 search.proxy 才走代理；未配置时直连（国内检索源通常不需要代理）。 */
    private ProxySelector resolveProxySelector() {
        String configured = proxy == null ? "" : proxy.trim();
        if (configured.isEmpty()) {
            return null;
        }
        String[] parts = configured.split(":");
        if (parts.length == 2 && !parts[0].isEmpty()) {
            try {
                return ProxySelector.of(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
            } catch (NumberFormatException e) {
                log.warn("search.proxy 格式应为 host:port，实际为：{}", configured);
            }
        } else {
            log.warn("search.proxy 格式应为 host:port，实际为：{}", configured);
        }
        return null;
    }

    /**
     * 联网检索。
     *
     * @param query 检索关键词，尽量具体（如"广州塔附近 好吃 餐厅"）
     * @param limit 最多返回条数（内部夹到 1..8）
     * @return {query, results:[{title,url,snippet}], note}
     */
    public Map<String, Object> search(String query, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        String q = query == null ? "" : query.trim();
        result.put("query", q);

        if (q.isEmpty()) {
            result.put("results", Collections.emptyList());
            result.put("note", "检索关键词为空，未执行检索。");
            return result;
        }

        int max = Math.max(1, Math.min(limit, 8));

        CachedResult cached = cache.get(q);
        if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL_MILLIS) {
            result.put("results", cached.value);
            result.put("note", SUCCESS_NOTE);
            return result;
        }

        boolean useBocha = apiKey != null && !apiKey.trim().isEmpty();

        // 首选博查；博查不可用（Key 未开通额度、超时、限流等）时退回必应兜底，避免检索功能直接瘫掉
        List<Map<String, String>> list = null;
        if (useBocha) {
            try {
                list = searchBocha(q, max);
            } catch (Exception e) {
                log.warn("博查检索失败，改用必应兜底：{}", e.getMessage());
            }
        }
        if (list == null) {
            try {
                list = searchBing(q, max);
            } catch (Exception e) {
                log.warn("联网检索失败（博查/必应均不可用）：{}", e.getMessage());
                result.put("results", Collections.emptyList());
                result.put("note", "联网检索暂时不可用（网络不通或被限流）。请如实告诉用户暂时查不到实时信息，不要凭记忆编造。");
                return result;
            }
        }

        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(q, new CachedResult(list, System.currentTimeMillis()));

        result.put("results", list);
        result.put("note", list.isEmpty()
                ? "联网检索没有返回结果，请如实告知用户未查到，不要编造。"
                : SUCCESS_NOTE);
        return result;
    }

    // ==================== 首选：博查 AI 搜索 ====================

    private List<Map<String, String>> searchBocha(String query, int limit) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("summary", true);
        body.put("count", limit);
        body.put("freshness", "noLimit");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BOCHA_ENDPOINT))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            String detail = response.body() == null ? "" : response.body();
            if (detail.length() > 200) {
                detail = detail.substring(0, 200);
            }
            throw new IllegalStateException("博查返回 HTTP " + response.statusCode() + " " + detail);
        }
        return parseBocha(response.body(), limit);
    }

    /** 博查返回：data.webPages.value[]，取 name/url/summary(无则 snippet) */
    private List<Map<String, String>> parseBocha(String json, int limit) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        JsonNode values = objectMapper.readTree(json).path("data").path("webPages").path("value");
        if (!values.isArray()) {
            return list;
        }
        for (JsonNode node : values) {
            if (list.size() >= limit) {
                break;
            }
            String url = node.path("url").asText("");
            if (url.isEmpty()) {
                continue;
            }
            String snippet = node.path("summary").asText("");
            if (snippet.isEmpty()) {
                snippet = node.path("snippet").asText("");
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", node.path("name").asText(""));
            item.put("url", url);
            item.put("snippet", snippet);
            list.add(item);
        }
        return list;
    }

    // ==================== 兜底：必应中国网页抓取 ====================

    private List<Map<String, String>> searchBing(String query, int limit) throws Exception {
        String url = BING_ENDPOINT + URLEncoder.encode(query, "UTF-8");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("检索入口返回 HTTP " + response.statusCode());
        }
        return parseBing(response.body(), limit);
    }

    /**
     * 按 {@code class="b_algo"} 切块，每块里取第一条 &lt;h2&gt;&lt;a&gt; 作标题与链接、
     * b_caption 里的第一段作摘要。没有标题链接的块（如纯样式块）直接跳过。
     */
    private List<Map<String, String>> parseBing(String html, int limit) {
        List<Map<String, String>> list = new ArrayList<>();

        Matcher marker = BING_BLOCK_MARKER.matcher(html);
        List<Integer> starts = new ArrayList<>();
        while (marker.find()) {
            starts.add(marker.start());
        }

        for (int i = 0; i < starts.size() && list.size() < limit; i++) {
            int from = starts.get(i);
            int to = Math.min(html.length(), from + MAX_BLOCK_CHARS);
            String block = html.substring(from, to);

            Matcher title = BING_TITLE_LINK.matcher(block);
            if (!title.find()) {
                continue;
            }
            String url = title.group(1);
            if (!url.startsWith("http")) {
                continue;
            }

            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", toText(title.group(2)));
            item.put("url", url);
            Matcher snippet = BING_SNIPPET.matcher(block);
            item.put("snippet", snippet.find() ? toText(snippet.group(1)) : "");
            list.add(item);
        }
        return list;
    }

    /** 去掉标签、还原 HTML 实体、合并空白，得到可读文本 */
    private String toText(String html) {
        String s = TAG_PATTERN.matcher(html).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'").replace("&#39;", "'");

        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static final class CachedResult {
        final List<Map<String, String>> value;
        final long time;

        CachedResult(List<Map<String, String>> value, long time) {
            this.value = value;
            this.time = time;
        }
    }
}
