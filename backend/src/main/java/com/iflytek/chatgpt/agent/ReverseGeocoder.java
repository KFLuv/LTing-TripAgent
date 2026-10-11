package com.iflytek.chatgpt.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 坐标 → 文字点位：把 App 上报的经纬度换成「省市区 + 街道/POI」的简短地名，供 GPS 位置引擎使用。
 * <p>
 * 两个来源，按配置自动选择，先国内后国外：
 * <ul>
 *   <li>配了 {@code geo.amap-key} → 用<b>高德</b>逆地理编码（国内直连、秒级、地址精确到街道）；</li>
 *   <li>没配或高德失败 → 退回 OpenStreetMap 的 Nominatim（无需 Key，但在境外、需代理且不稳定）。</li>
 * </ul>
 * <p>
 * 坐标系：入参是浏览器定位给的 WGS-84，调高德前会先纠偏成 GCJ-02（见 {@link CoordConverter}），
 * 否则落点会偏 100~500 米；Nominatim 用的就是 WGS-84，不做转换。
 * <p>
 * 属于"尽力而为"：两个都查不到时返回 null。调用方必须据此明确告知"地名未解析"，
 * 绝不允许由模型推断用户所在的区/商圈（否则会出现"人在这、AI 说在别处"的严重误导）。
 */
@Component
public class ReverseGeocoder {

    private static final Logger log = LoggerFactory.getLogger(ReverseGeocoder.class);

    /** 高德逆地理编码（国内直连） */
    private static final String AMAP_ENDPOINT =
            "https://restapi.amap.com/v3/geocode/regeo?extensions=base&output=JSON&location=";

    /** Nominatim 逆地理编码（境外，需代理） */
    private static final String NOMINATIM_ENDPOINT =
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=zh-CN";

    /** 同一坐标（精确到约 10 米）只查一次，避免频繁打扰公共接口 */
    private static final int MAX_CACHE = 200;

    /** 高德 Web 服务 Key；配了就用高德，留空退回 Nominatim */
    @Value("${geo.amap-key:}")
    private String amapKey;

    /** Nominatim 在境外，国内直连通常不通，可在此显式指定代理（形如 127.0.0.1:7890）；留空用系统代理 */
    @Value("${geo.proxy:}")
    private String proxy;

    /** 高德：国内服务，强制直连 */
    private HttpClient amapClient;

    /** Nominatim：按 geo.proxy / 系统代理走 */
    private HttpClient nominatimClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 强制直连的 selector：本应用为 Nominatim 开启了 {@code java.net.useSystemProxies}，
     * 若不显式指定，HttpClient 会默认套上系统代理；高德是国内服务，直连更快更稳。
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
        this.amapClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .proxy(DIRECT)
                .build();

        HttpClient.Builder nominatimBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        ProxySelector selector = resolveProxySelector();
        if (selector != null) {
            nominatimBuilder.proxy(selector);
        }
        this.nominatimClient = nominatimBuilder.build();
    }

    /**
     * 优先用 geo.proxy 配置；没配则用系统代理（Windows 下即"Internet 选项"里那个），
     * 拿不到就直连——直连失败也只是退回用经纬度描述，不影响定位上报。
     */
    private ProxySelector resolveProxySelector() {
        String configured = proxy == null ? "" : proxy.trim();
        if (!configured.isEmpty()) {
            String[] parts = configured.split(":");
            if (parts.length == 2 && !parts[0].isEmpty()) {
                try {
                    return ProxySelector.of(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
                } catch (NumberFormatException e) {
                    log.warn("geo.proxy 格式应为 host:port，实际为：{}", configured);
                }
            } else {
                log.warn("geo.proxy 格式应为 host:port，实际为：{}", configured);
            }
        }
        return ProxySelector.getDefault();
    }

    /**
     * @param lat 纬度
     * @param lng 经度
     * @return 形如「广东省广州市黄埔区联和街道起云路」的短地名；两个来源都失败时返回 null
     */
    public String resolve(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return null;
        }
        String key = String.format("%.4f,%.4f", lat, lng);
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        String label = null;
        if (amapKey != null && !amapKey.trim().isEmpty()) {
            label = resolveByAmap(lat, lng);
        }
        if (label == null) {
            label = resolveByNominatim(lat, lng);
        }

        // 只缓存成功结果：失败不缓存，下次上报可以重新尝试
        if (label != null) {
            if (cache.size() >= MAX_CACHE) {
                cache.clear();
            }
            cache.put(key, label);
        }
        return label;
    }

    /** 高德逆地理编码：入参 WGS-84（浏览器定位），内部纠偏成 GCJ-02 后再查 */
    private String resolveByAmap(Double lat, Double lng) {
        try {
            // 入参是浏览器定位给的 WGS-84，高德要求 GCJ-02：先纠偏，否则落点会偏 100~500 米
            double[] gcj = CoordConverter.wgs84ToGcj02(lat, lng);
            JsonNode json = requestAmapRegeoJson(gcj[0], gcj[1]);
            return json == null ? null : toAmapLabel(json.path("regeocode"));
        } catch (Exception e) {
            log.warn("高德逆地理编码异常：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 已经是 GCJ-02 的坐标（如内嵌地图上的点击点）直接查，不再纠偏。
     * 若这里再转一次，地址会偏 100~500 米。
     */
    public String resolveGcj(Double gcjLat, Double gcjLng) {
        if (gcjLat == null || gcjLng == null || amapKey == null || amapKey.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode json = requestAmapRegeoJson(gcjLat, gcjLng);
            return json == null ? null : toAmapLabel(json.path("regeocode"));
        } catch (Exception e) {
            log.warn("高德逆地理编码异常：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 取坐标所在城市名（如「广州市」），供公交路径规划使用——高德公交接口必须带城市。
     * 入参是浏览器定位的 WGS-84；直辖市 city 为空时退回 province。
     */
    public String resolveCity(Double lat, Double lng) {
        if (lat == null || lng == null || amapKey == null || amapKey.trim().isEmpty()) {
            return null;
        }
        try {
            double[] gcj = CoordConverter.wgs84ToGcj02(lat, lng);
            JsonNode json = requestAmapRegeoJson(gcj[0], gcj[1]);
            if (json == null) {
                return null;
            }
            JsonNode comp = json.path("regeocode").path("addressComponent");
            String city = comp.path("city").asText("").trim();
            if (city.isEmpty() || "[]".equals(city)) {
                city = comp.path("province").asText("").trim(); // 直辖市
            }
            return city.isEmpty() || "[]".equals(city) ? null : city;
        } catch (Exception e) {
            log.warn("高德城市解析异常：{}", e.getMessage());
            return null;
        }
    }

    /** 调高德逆地理，返回整个响应 JSON；仅供内部复用，入参必须是 GCJ-02 */
    private JsonNode requestAmapRegeoJson(double gcjLat, double gcjLng) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AMAP_ENDPOINT + gcjLng + "," + gcjLat + "&key=" + amapKey.trim()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = amapClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            log.warn("高德逆地理编码返回 HTTP {}", response.statusCode());
            return null;
        }
        JsonNode json = objectMapper.readTree(response.body());
        if (!"1".equals(json.path("status").asText(""))) {
            log.warn("高德逆地理编码失败：{}", json.path("info").asText(""));
            return null;
        }
        return json;
    }

    /**
     * 高德返回的 addressComponent 分字段，这里拼成"省市区街道"并去重；
     * formatted_address 作为兜底（地址在道路/无明确街道时更完整）。
     */
    private String toAmapLabel(JsonNode regeocode) {
        JsonNode comp = regeocode.path("addressComponent");
        StringBuilder sb = new StringBuilder();
        appendPart(sb, comp.path("province").asText(""));
        appendPart(sb, comp.path("city").asText(""));       // 直辖市可能为空
        appendPart(sb, comp.path("district").asText(""));
        appendPart(sb, comp.path("township").asText(""));
        appendPart(sb, comp.path("streetNumber").path("street").asText(""));

        if (sb.length() > 0) {
            return sb.toString();
        }
        String formatted = regeocode.path("formatted_address").asText("").trim();
        return formatted.isEmpty() ? null : formatted;
    }

    /** 拼接地名片段：跳过空值/空数组，并避免重复（如 city 有时重复 province） */
    private void appendPart(StringBuilder sb, String part) {
        if (part == null) {
            return;
        }
        String p = part.trim();
        if (p.isEmpty() || "[]".equals(p) || sb.indexOf(p) >= 0) {
            return;
        }
        sb.append(p);
    }

    /** Nominatim 逆地理编码（备用） */
    private String resolveByNominatim(Double lat, Double lng) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NOMINATIM_ENDPOINT + "&lat=" + lat + "&lon=" + lng))
                    .header("User-Agent", "AITravelGuide/1.0 (student project)")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = nominatimClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return toNominatimLabel(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            // 断网、被限流、返回格式变化都不该影响定位上报本身
            log.warn("反向地理编码失败，改用坐标描述：{}", e.getMessage());
        }
        return null;
    }

    /**
     * Nominatim 的 display_name 是"从具体到宽泛"的，例如：
     * {@code 起云路, 联和街道, 黄埔区, 广州市, 广东省, 510663, 中国}。
     * 这里去掉国家与邮编后反转成"省市区街道"的顺序，最多保留 4 段，避免地名过长。
     */
    private String toNominatimLabel(JsonNode json) {
        String displayName = json.path("display_name").asText("").trim();
        if (displayName.isEmpty()) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (String part : displayName.split("\\s*,\\s*")) {
            if (part.isEmpty() || "中国".equals(part) || part.matches("\\d{5,6}")) {
                continue;
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            return null;
        }

        Collections.reverse(parts);
        if (parts.size() > 4) {
            parts = new ArrayList<>(parts.subList(parts.size() - 4, parts.size()));
        }
        return String.join("", parts);
    }
}
