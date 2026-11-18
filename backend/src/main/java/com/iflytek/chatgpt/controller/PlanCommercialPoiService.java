package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.chatgpt.agent.CoordConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 百度、腾讯地点服务适配器。
 *
 * <p>两家都是可选商用 Provider，只有本机配置 Key 才启用。结果统一转换为 GCJ-02，
 * 并保留评分、人均、营业时间、图片、原始 ID 和来源置信度，供上层聚合排序。
 */
@Component
public class PlanCommercialPoiService {

    private static final Logger log = LoggerFactory.getLogger(PlanCommercialPoiService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();

    @Value("${geo.baidu-key:}")
    private String baiduKey;

    @Value("${geo.tencent-key:}")
    private String tencentKey;

    public boolean configured(String source) {
        if ("baidu".equalsIgnoreCase(source)) return !blank(baiduKey);
        if ("tencent".equalsIgnoreCase(source)) return !blank(tencentKey);
        return false;
    }

    public List<ProviderPoi> fetch(String source, String city, String anchor,
                                   double gcjLat, double gcjLng, int radius) throws Exception {
        String safeSource = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if ("baidu".equals(safeSource)) {
            return fetchBaidu(city, anchor, gcjLat, gcjLng, radius);
        }
        if ("tencent".equals(safeSource)) {
            return fetchTencent(city, anchor, gcjLat, gcjLng, radius);
        }
        throw new IllegalArgumentException("不支持的商用地点源：" + source);
    }

    public List<Map<String, Object>> healthItems() {
        return Arrays.asList(health("baidu"), health("tencent"));
    }

    public Map<String, Object> health(String source) {
        boolean isBaidu = "baidu".equalsIgnoreCase(source);
        boolean ready = configured(source);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", isBaidu ? "baidu_poi" : "tencent_poi");
        item.put("label", isBaidu ? "百度地点补充" : "腾讯地点补充");
        item.put("configured", ready);
        item.put("status", ready ? "configured" : "missing_key");
        item.put("provider", isBaidu ? "百度地图 Place API" : "腾讯位置服务");
        item.put("message", ready
                ? "商用地点源已配置，将在高德结果之后自动合并"
                : (isBaidu
                    ? "未配置 geo.baidu-key，自动跳过百度地点补充"
                    : "未配置 geo.tencent-key，自动跳过腾讯地点补充"));
        return item;
    }

    private List<ProviderPoi> fetchBaidu(String city, String anchor, double gcjLat,
                                         double gcjLng, int radius) throws Exception {
        if (!configured("baidu")) throw new IllegalStateException("未配置百度地点 Key");
        double[] bd = CoordConverter.gcj02ToBd09(gcjLat, gcjLng);
        List<ProviderPoi> items = new ArrayList<>();
        for (Map.Entry<String, String> query : queries().entrySet()) {
            String url = "https://api.map.baidu.com/place/v2/search?query=" + enc(query.getValue())
                    + "&region=" + enc(city)
                    + "&location=" + bd[0] + "," + bd[1]
                    + "&radius=" + radius
                    + "&output=json&scope=2&page_size=20&page_num=0"
                    + "&coord_type=3&ak=" + enc(baiduKey);
            JsonNode root = getJson(url, "百度地点服务");
            if (!"0".equals(root.path("status").asText())) {
                throw new IllegalStateException("百度地点服务返回错误状态");
            }
            for (JsonNode item : root.path("results")) {
                String name = item.path("name").asText("").trim();
                double itemLat = item.path("location").path("lat").asDouble();
                double itemLng = item.path("location").path("lng").asDouble();
                if (name.isEmpty() || itemLat == 0 || itemLng == 0) continue;
                double[] gcj = CoordConverter.bd09ToGcj02(itemLat, itemLng);
                JsonNode detail = item.path("detail_info");
                String rating = firstText(detail, "overall_rating", "rating");
                String cost = firstText(detail, "price", "cost");
                String openTime = firstText(detail, "shop_hours", "open_time");
                String photo = photoUrl(detail.path("photo_url"));
                String type = firstText(detail, "type", "tag");
                String sourceId = firstText(item, "uid", "id");
                if (sourceId.isEmpty()) sourceId = name + "|" + itemLat + "|" + itemLng;
                String region = firstNonBlank(item.path("area").asText(""),
                        item.path("city").asText(""), anchor);
                String description = firstNonBlank(item.path("tag").asText(""), type,
                        query.getKey() + " · 百度地点");
                String detailUrl = firstText(detail, "detail_url");
                items.add(new ProviderPoi(
                        "baidu", sourceId, name, query.getKey(), item.path("city").asText(city),
                        region, firstNonBlank(item.path("address").asText(""), anchor),
                        gcj[0], gcj[1], description, detailUrl,
                        "商业地图 API，按授权使用", "百度地图",
                        tags(query.getValue(), rating, cost, openTime, photo, 88, item),
                        rating, cost, openTime, photo, 88));
            }
        }
        return items;
    }

    private List<ProviderPoi> fetchTencent(String city, String anchor, double gcjLat,
                                           double gcjLng, int radius) throws Exception {
        if (!configured("tencent")) throw new IllegalStateException("未配置腾讯地点 Key");
        List<ProviderPoi> items = new ArrayList<>();
        for (Map.Entry<String, String> query : queries().entrySet()) {
            String url = "https://apis.map.qq.com/ws/place/v1/search?keyword=" + enc(query.getValue())
                    + "&boundary=" + enc("nearby(" + gcjLat + "," + gcjLng + "," + radius + ",1)")
                    + "&page_size=20&page_index=1&orderby=_distance&key=" + enc(tencentKey);
            JsonNode root = getJson(url, "腾讯地点服务");
            if (root.path("status").asInt(-1) != 0) {
                throw new IllegalStateException("腾讯地点服务返回错误状态");
            }
            for (JsonNode item : root.path("data")) {
                String name = item.path("title").asText("").trim();
                double itemLat = item.path("location").path("lat").asDouble();
                double itemLng = item.path("location").path("lng").asDouble();
                if (name.isEmpty() || itemLat == 0 || itemLng == 0) continue;
                JsonNode adInfo = item.path("ad_info");
                String sourceId = firstText(item, "id");
                if (sourceId.isEmpty()) sourceId = name + "|" + itemLat + "|" + itemLng;
                String region = firstNonBlank(adInfo.path("district").asText(""),
                        adInfo.path("city").asText(""), anchor);
                String photo = photoUrl(item.path("photos"));
                String categoryText = item.path("category").asText("");
                String description = firstNonBlank(categoryText, query.getKey() + " · 腾讯地点");
                String detailUrl = "https://map.qq.com/?type=marker&pointx=" + itemLng
                        + "&pointy=" + itemLat;
                items.add(new ProviderPoi(
                        "tencent", sourceId, name, query.getKey(),
                        firstNonBlank(adInfo.path("city").asText(""), city),
                        region, firstNonBlank(item.path("address").asText(""), anchor),
                        itemLat, itemLng, description, detailUrl,
                        "商业地图 API，按授权使用", "腾讯位置服务",
                        tags(query.getValue(), "", "", "", photo, 84, item),
                        "", "", "", photo, 84));
            }
        }
        return items;
    }

    private JsonNode getJson(String url, String provider) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "LTing/2.0 (commercial POI supplement)")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            log.warn("{} HTTP {}", provider, response.statusCode());
            throw new IllegalStateException(provider + "暂不可用");
        }
        return JSON.readTree(response.body());
    }

    private Map<String, String> queries() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("美食", "美食餐厅");
        queries.put("景点", "景点");
        queries.put("商圈", "购物中心 商业街");
        queries.put("玩乐", "休闲娱乐");
        return queries;
    }

    private String tags(String query, String rating, String cost, String openTime,
                        String photo, int confidence, JsonNode raw) throws Exception {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("providerQuery", query);
        tags.put("rating", rating);
        tags.put("cost", cost);
        tags.put("openTime", openTime);
        tags.put("photo", photo);
        tags.put("confidence", confidence);
        tags.put("sourceTier", "T2");
        tags.set("raw", raw.deepCopy());
        return JSON.writeValueAsString(tags);
    }

    private String photoUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText("");
        if (node.isArray() && node.size() > 0) {
            JsonNode first = node.get(0);
            if (first.isTextual()) return first.asText("");
            return firstNonBlank(first.path("url").asText(""), first.path("photo_url").asText(""));
        }
        return firstNonBlank(node.path("url").asText(""), node.path("photo_url").asText(""));
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.trim();
        }
        return "";
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static final class ProviderPoi {
        public final String source;
        public final String sourceId;
        public final String name;
        public final String category;
        public final String city;
        public final String region;
        public final String address;
        public final double lat;
        public final double lng;
        public final String description;
        public final String url;
        public final String license;
        public final String attribution;
        public final String tagsJson;
        public final String rating;
        public final String cost;
        public final String openTime;
        public final String photo;
        public final int confidence;

        ProviderPoi(String source, String sourceId, String name, String category,
                    String city, String region, String address, double lat, double lng,
                    String description, String url, String license, String attribution,
                    String tagsJson, String rating, String cost, String openTime,
                    String photo, int confidence) {
            this.source = source;
            this.sourceId = sourceId;
            this.name = name;
            this.category = category;
            this.city = city;
            this.region = region;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
            this.description = description;
            this.url = url;
            this.license = license;
            this.attribution = attribution;
            this.tagsJson = tagsJson;
            this.rating = rating;
            this.cost = cost;
            this.openTime = openTime;
            this.photo = photo;
            this.confidence = confidence;
        }
    }
}
