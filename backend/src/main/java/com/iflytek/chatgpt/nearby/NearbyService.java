package com.iflytek.chatgpt.nearby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.chatgpt.agent.CoordConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附近吃喝玩乐模块的数据源适配层（本模块内部使用，不对外暴露）。
 * <p>
 * 这个类只服务「附近吃喝玩乐」页面：不出现在 Agent 工具清单里，也不被其它页面调用。
 * 商户与商户评分来自配置的数据源，用户自己的点评存在 poi_review 表里（见 NearbyController）。
 * <p>
 * <b>数据源</b>由 {@code nearby.source} 决定：
 * <ul>
 *   <li>{@code dianping} —— 大众点评。需要 {@code dianping.endpoint} 与 {@code dianping.api-key}
 *       （点评没有公开的免费接口，必须走你已开通的商户/服务商通道）；缺配置时不猜数据，直接如实报错。</li>
 *   <li>{@code amap}（默认）—— 高德周边搜索。开箱可用，字段里同样带商户评分与人均，
 *       让页面在没接点评通道时也能真实跑起来。</li>
 * </ul>
 * 两个适配器都归一到同一份结构，页面与点评逻辑不感知具体来源。
 */
@Service
public class NearbyService {

    private static final Logger log = LoggerFactory.getLogger(NearbyService.class);

    /** 本模块允许的搜索半径：只开放 5km / 10km 两档，避免越界查询 */
    private static final int MIN_RADIUS = 5_000;
    private static final int MAX_RADIUS = 10_000;
    /** 店铺不设上限：单次最多取这么多家（8 页 × 25 条/页，高德单页上限 25） */
    private static final int MAX_LIMIT = 200;
    /** 本模块承诺至少返回这么多家（不足时自动翻页取） */
    private static final int MIN_RESULT = 35;
    /** 单页最多 25 条（高德限制） */
    private static final int PAGE_SIZE = 25;
    /** 翻页上限：8 页 = 200 家 */
    private static final int MAX_PAGES = 8;

    private static final String AMAP_AROUND =
            "https://restapi.amap.com/v3/place/around?output=JSON&sortrule=distance&extensions=all&location=";
    private static final String AMAP_DETAIL =
            "https://restapi.amap.com/v3/place/detail?output=JSON&extensions=all&id=";

    @Value("${nearby.source:amap}")
    private String source;

    @Value("${geo.amap-key:}")
    private String amapKey;

    @Value("${dianping.endpoint:}")
    private String dianpingEndpoint;

    @Value("${dianping.api-key:}")
    private String dianpingKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当前生效的数据源名称，页面据此显示"数据源：xxx" */
    public String currentSource() {
        return "dianping".equalsIgnoreCase(trim(source)) ? "dianping" : "amap";
    }

    /**
     * 按坐标查附近商户。
     *
     * @param lat     纬度（浏览器定位的 WGS-84）
     * @param lng     经度
     * @param radiusMeters 半径，会被夹到 5~10km
     * @param keyword 关键词（吃 / 玩 / 咖啡…），可空
     */
    public Map<String, Object> search(double lat, double lng, Integer radiusMeters, String keyword, Integer limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        int radius = radiusMeters == null ? MIN_RADIUS : radiusMeters;
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        int size = limit == null ? MIN_RESULT : Math.max(MIN_RESULT, Math.min(MAX_LIMIT, limit));

        result.put("source", currentSource());
        result.put("radiusMeters", radius);

        List<Map<String, Object>> pois = "dianping".equals(currentSource())
                ? fromDianping(lat, lng, radius, keyword, size, result)
                : fromAmap(lat, lng, radius, keyword, size, result);
        if (pois == null) {
            return result;              // 出错时 result 里已经带了 error 说明
        }
        result.put("status", "success");
        result.put("pois", pois);
        return result;
    }

    /**
     * 高德周边搜索：真实距离（米）+ 商户评分（biz_ext.rating）、人均（biz_ext.cost）、店铺图（photos）。
     * <p>
     * 单页最多 25 条，所以这里翻页合并去重，保证"至少 {@link #MIN_RESULT} 家"这个模块级承诺；
     * 该半径内确实不足 35 家时如实返回有多少家，不凑数。
     */
    private List<Map<String, Object>> fromAmap(double lat, double lng, int radius, String keyword, int size,
                                               Map<String, Object> result) {
        String key = trim(amapKey);
        if (key.isEmpty()) {
            result.put("error", "未配置 geo.amap-key，附近商户暂时查不了");
            return null;
        }
        double[] gcj = CoordConverter.wgs84ToGcj02(lat, lng);
        List<Map<String, Object>> pois = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int page = 1;
        while (page <= MAX_PAGES && pois.size() < size) {
            StringBuilder url = new StringBuilder(AMAP_AROUND)
                    .append(gcj[1]).append(",").append(gcj[0])
                    .append("&radius=").append(radius)
                    .append("&offset=").append(PAGE_SIZE)
                    .append("&page=").append(page)
                    .append("&key=").append(key);
            String kw = trim(keyword);
            if (!kw.isEmpty()) {
                url.append("&keywords=").append(encode(kw));
            }
            JsonNode json = getJson(url.toString());
            if (json == null) {
                if (page == 1) {
                    result.put("error", "附近商户服务暂时不可用，请稍后重试");
                    return null;
                }
                break;                       // 翻页失败就用已拿到的
            }
            if (!"1".equals(json.path("status").asText(""))) {
                if (page == 1) {
                    result.put("error", "附近商户查询失败：" + json.path("info").asText(""));
                    return null;
                }
                break;
            }
            JsonNode arr = json.path("pois");
            if (!arr.isArray() || arr.size() == 0) {
                break;
            }
            for (JsonNode poi : arr) {
                Map<String, Object> item = toItem(poi);
                String id = String.valueOf(item.get("poiId"));
                if (seen.add(id)) {
                    pois.add(item);
                }
            }
            page++;
        }
        if (pois.size() > size) {
            return new ArrayList<>(pois.subList(0, size));
        }
        return pois;
    }

    /** 把高德的 POI 节点归一成模块内部结构 */
    private Map<String, Object> toItem(JsonNode poi) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("poiId", poi.path("id").asText(""));
        item.put("name", poi.path("name").asText(""));
        item.put("type", poi.path("type").asText(""));
        item.put("address", poi.path("address").asText(""));
        item.put("tel", poi.path("tel").asText(""));
        // 城市名：拼大众点评的搜索路径要用（点评的搜索必须带城市编号）
        item.put("city", poi.path("cityname").asText(""));
        item.put("distance", parseIntSafe(poi.path("distance").asText("")));
        String rating = poi.path("biz_ext").path("rating").asText("");
        item.put("sourceRating", rating.isEmpty() ? null : rating);
        String cost = poi.path("biz_ext").path("cost").asText("");
        item.put("cost", cost.isEmpty() ? null : cost);
        List<String> photos = new ArrayList<>();
        for (JsonNode ph : poi.path("photos")) {
            String u = ph.path("url").asText("");
            if (!u.isEmpty()) {
                photos.add(u);
            }
        }
        item.put("photos", photos);
        JsonNode loc = poi.path("location");
        if (loc.isTextual() && loc.asText().contains(",")) {
            String[] parts = loc.asText().split(",");
            item.put("lng", parts[0]);
            item.put("lat", parts[1]);
        }
        return item;
    }

    /** 商户详情（详情页用）：高德 place/detail 拿图、电话、评分等 */
    public Map<String, Object> detail(String poiId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", currentSource());
        String key = trim(amapKey);
        if (key.isEmpty()) {
            result.put("error", "未配置 geo.amap-key，商户详情暂时看不了");
            return result;
        }
        JsonNode json = getJson(AMAP_DETAIL + encode(trim(poiId)) + "&key=" + key);
        if (json == null || !"1".equals(json.path("status").asText(""))) {
            result.put("error", "商户详情暂时取不到，请稍后重试");
            return result;
        }
        JsonNode arr = json.path("pois");
        if (!arr.isArray() || arr.size() == 0) {
            result.put("error", "没找到这家商户");
            return result;
        }
        Map<String, Object> item = toItem(arr.get(0));
        item.put("tag", arr.get(0).path("tag").asText(""));
        result.put("status", "success");
        result.put("poi", item);
        return result;
    }

    /** 旧的单页实现保留给"翻页拿不到"的兜底路径（当前未被调用） */
    @SuppressWarnings("unused")
    private List<Map<String, Object>> amapSinglePage(String key, JsonNode json) {
        List<Map<String, Object>> pois = new ArrayList<>();
        for (JsonNode poi : json.path("pois")) {
            pois.add(toItem(poi));
        }
        return pois;
    }

    /**
     * 大众点评适配器。
     * <p>
     * 点评没有公开免费接口，所以这里不写死任何"看起来像点评"的地址：必须由你配置
     * {@code dianping.endpoint}（你的服务商网关）与 {@code dianping.api-key}。
     * 返回结构按 {@code {data:[{id,name,address,distance,rating,type,tel}]}} 容错解析，
     * 字段名不一致时改这一段即可，页面与点评表都不用动。
     */
    private List<Map<String, Object>> fromDianping(double lat, double lng, int radius, String keyword, int size,
                                                   Map<String, Object> result) {
        String endpoint = trim(dianpingEndpoint);
        String key = trim(dianpingKey);
        if (endpoint.isEmpty() || key.isEmpty()) {
            result.put("error", "数据源设为大众点评，但还没配置 dianping.endpoint / dianping.api-key"
                    + "（点评无公开免费接口）。可在配置里把 nearby.source 改回 amap 先用真实周边数据。");
            return null;
        }
        String url = endpoint + (endpoint.contains("?") ? "&" : "?")
                + "lat=" + lat + "&lng=" + lng + "&radius=" + radius
                + "&limit=" + size
                + (trim(keyword).isEmpty() ? "" : "&keyword=" + encode(trim(keyword)));
        JsonNode json = getJson(url, key);
        if (json == null) {
            result.put("error", "大众点评通道暂时不可用，请稍后重试");
            return null;
        }

        JsonNode arr = json.path("data").isArray() ? json.path("data") : json.path("pois");
        List<Map<String, Object>> pois = new ArrayList<>();
        for (JsonNode poi : arr) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("poiId", poi.path("id").asText(poi.path("poiId").asText("")));
            item.put("name", poi.path("name").asText(""));
            item.put("type", poi.path("type").asText(poi.path("category").asText("")));
            item.put("address", poi.path("address").asText(""));
            item.put("tel", poi.path("tel").asText(""));
            item.put("distance", parseIntSafe(poi.path("distance").asText("")));
            String rating = poi.path("rating").asText("");
            item.put("sourceRating", rating.isEmpty() ? null : rating);
            item.put("cost", poi.path("cost").asText(""));
            pois.add(item);
        }
        return pois;
    }

    private JsonNode getJson(String url) {
        return getJson(url, null);
    }

    private JsonNode getJson(String url, String bearer) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(8))
                    .GET();
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("附近商户数据源返回 HTTP {}", response.statusCode());
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.warn("附近商户查询失败：{}", e.getMessage());
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
