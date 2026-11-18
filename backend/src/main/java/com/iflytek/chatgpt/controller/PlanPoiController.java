package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import util.MySQLUtil;

/**
 * 出行工作台 · 城市 POI（区域 + 景点/吃喝玩乐）。
 *
 * <p>数据源：高德 Web 服务 place/text（复用 geo.amap-key，服务端调用，key 不出后端）。
 * 返回按区域（adname）分组，供"区域显示 + 候选池 + 地图打点"直接使用。
 *
 * <p>证据：来源=T1 高德 POI；评分/人均仅在 biz_ext 有值时返回，缺失不编造。
 */
@RestController
public class PlanPoiController {

    private static final Logger log = LoggerFactory.getLogger(PlanPoiController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();
    private static final long CACHE_TTL_MS = Duration.ofHours(24).toMillis();
    private static final int CACHE_LIMIT = 400;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static volatile Map<String, Object> LAST_QUERY = new LinkedHashMap<>();

    @Value("${geo.amap-key:}")
    private String amapKey;

    /**
     * @param city    城市（如 广州）
     * @param keyword 类型关键词（景点 / 美食 / 咖啡 / 玩乐 / 商圈）
     * @param limit   返回上限（默认 30，最大 50）
     */
    @PostMapping(value = "/plan/pois", produces = "application/json;charset=UTF-8")
    public Map<String, Object> pois(@RequestParam String city,
                                    @RequestParam(defaultValue = "景点") String keyword,
                                    @RequestParam(defaultValue = "30") int limit,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(required = false) String preference,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        REQUESTS.incrementAndGet();
        if (city == null || city.trim().isEmpty()) {
            recordFailure("", keyword, "缺少城市");
            out.put("status", "error");
            out.put("message", "缺少城市");
            return out;
        }
        if (amapKey == null || amapKey.trim().isEmpty()) {
            recordFailure(city, keyword, "未配置 geo.amap-key");
            out.put("status", "error");
            out.put("message", "未配置 geo.amap-key，无法查询 POI");
            return out;
        }
        int size = Math.max(1, Math.min(limit, 50));
        int safePage = Math.max(1, page);
        String cacheKey = city.trim().toLowerCase(Locale.ROOT) + "|" + keyword + "|"
                + size + "|" + safePage + "|" + (preference == null ? "" : preference.trim());
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            CACHE_HITS.incrementAndGet();
            recordQuery(city, keyword, true, "命中缓存", cached.payload);
            out.putAll(enrichUgc(cached.payload, username));
            out.put("cached", true);
            return out;
        }
        String types = typesOf(keyword);
        String query = types.isEmpty() ? "keywords=" + enc(keyword) : "types=" + enc(types);
        String url = "https://restapi.amap.com/v3/place/text?key=" + enc(amapKey)
                + "&" + query + "&city=" + enc(city.trim())
                + "&citylimit=true&offset=" + size + "&page=" + safePage
                + "&extensions=all";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12)).GET().build();
            JsonNode root = JSON.readTree(HTTP.send(req, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8)).body());
            if (!"1".equals(root.path("status").asText())) {
                recordFailure(city, keyword, root.path("info").asText("高德查询失败"));
                out.put("status", "error");
                out.put("message", root.path("info").asText("高德查询失败"));
                return out;
            }
            Map<String, Integer> regionCount = new LinkedHashMap<>();
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode p : root.path("pois")) {
                String name = p.path("name").asText("");
                String region = p.path("adname").asText("");
                String address = p.path("address").asText("");
                String location = p.path("location").asText("");
                if (name.isEmpty() || location.isEmpty()) continue;
                String[] parts = location.split(",");
                if (parts.length != 2) continue;
                String rawType = p.path("type").asText("").replace(";", " / ");
                String regionName = region.isEmpty() ? p.path("cityname").asText("") : region;
                String openTime = p.path("biz_ext").path("open_time").asText("");
                String rating = p.path("biz_ext").path("rating").asText("");
                String cost = p.path("biz_ext").path("cost").asText("");
                String heat = p.path("importance").asText("");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("poiId", p.path("id").asText(""));
                m.put("name", name);
                m.put("region", regionName);
                m.put("address", address);
                m.put("category", keyword);
                m.put("type", rawType);
                m.put("lng", parts[0]);
                m.put("lat", parts[1]);
                m.put("rating", rating);
                m.put("cost", cost);
                m.put("openTime", openTime);
                m.put("heat", heat);
                m.put("durationMin", durationOf(keyword, rawType));
                m.put("description", descriptionOf(name, keyword, rawType, regionName, address,
                        rating, cost, openTime));
                m.put("source", "高德 POI（T1）");
                List<String> evidence = new ArrayList<>();
                evidence.add("坐标与地址来自高德 POI，查询时间 " + java.time.LocalDate.now());
                evidence.add(rating.isEmpty()
                        ? "高德未返回评分，系统没有补造评分"
                        : "高德平台评分 " + rating + " / 5");
                evidence.add(address.isEmpty()
                        ? "地址字段为空，可点地图查看坐标"
                        : "位置：" + regionName + " · " + address);
                m.put("evidence", evidence);
                m.put("preferenceMatch", preferenceMatch(keyword, rawType, name, preference));
                JsonNode photos = p.path("photos");
                m.put("photo", (photos.isArray() && photos.size() > 0)
                        ? photos.get(0).path("url").asText("") : "");
                items.add(m);
                String key = String.valueOf(m.get("region"));
                regionCount.merge(key, 1, Integer::sum);
            }
            List<Map<String, Object>> regions = new ArrayList<>();
            regionCount.forEach((k, v) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", k);
                r.put("count", v);
                regions.add(r);
            });
            out.put("status", "success");
            out.put("city", city.trim());
            out.put("keyword", keyword);
            out.put("regions", regions);
            out.put("items", items);
            out.put("total", items.size());
            out.put("source", "高德 POI（T1）");
            out.put("cached", false);
            out.put("cacheTtlHours", 24);
            CACHE.put(cacheKey, new CacheEntry(out, System.currentTimeMillis() + CACHE_TTL_MS));
            recordQuery(city, keyword, false, "查询成功", out);
            if (CACHE.size() > CACHE_LIMIT) {
                long now = System.currentTimeMillis();
                CACHE.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
            }
            return enrichUgc(out, username);
        } catch (Exception e) {
            log.warn("POI 查询失败：{}", e.getMessage());
            recordFailure(city, keyword, "POI 查询失败");
            out.put("status", "error");
            out.put("message", "POI 查询失败，请稍后重试");
            return out;
        }
    }

    /**
     * 地点锚定搜索：先解析“县/镇/村/景区/商圈”为坐标，再查周边可游玩地点。
     * 不把住宿、停车场、厕所、政府机构等生活设施混进候选池。
     */
    @PostMapping(value = "/plan/pois/nearby", produces = "application/json;charset=UTF-8")
    public Map<String, Object> nearby(@RequestParam String anchor,
                                      @RequestParam(required = false) String city,
                                      @RequestParam(defaultValue = "3000") int radius,
                                      @RequestParam(required = false) String preference,
                                      HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        REQUESTS.incrementAndGet();
        String safeAnchor = anchor == null ? "" : anchor.trim();
        if (safeAnchor.isEmpty()) {
            recordFailure(city, safeAnchor, "缺少地点锚点");
            return fail(out, "请输入景区、镇、村或商圈名称");
        }
        if (amapKey == null || amapKey.trim().isEmpty()) {
            recordFailure(city, safeAnchor, "未配置 geo.amap-key");
            return fail(out, "未配置 geo.amap-key，无法查询周边地点");
        }
        int safeRadius = Math.max(500, Math.min(10000, radius));
        String safeCity = city == null ? "" : city.trim();
        String cacheKey = "nearby|" + safeCity.toLowerCase(Locale.ROOT) + "|"
                + safeAnchor.toLowerCase(Locale.ROOT) + "|" + safeRadius
                + "|" + (preference == null ? "" : preference.trim());
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            CACHE_HITS.incrementAndGet();
            recordQuery(safeCity, "周边:" + safeAnchor, true, "命中地点周边缓存", cached.payload);
            out.putAll(enrichUgc(cached.payload, username));
            out.put("cached", true);
            return out;
        }
        try {
            ResolvedAnchor resolved = resolveAnchor(safeCity, safeAnchor);
            if (resolved == null) {
                recordFailure(safeCity, safeAnchor, "没有解析到地点坐标");
                return fail(out, "没有找到“" + safeAnchor + "”的位置，请补上县/镇或更完整名称");
            }
            String[][] groups = {
                    {"景点", "风景名胜|公园广场|博物馆|展览馆|科教文化服务|宗教场所"},
                    {"美食", "餐饮服务|咖啡厅|茶艺馆"},
                    {"商圈", "购物中心|商场|专卖店|商业街|休闲娱乐|体育场馆|影剧院"}
            };
            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Integer> categoryCounts = new LinkedHashMap<>();
            int rawCount = 0;
            int excludedCount = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String[] group : groups) {
                String url = "https://restapi.amap.com/v3/place/around?key=" + enc(amapKey)
                        + "&location=" + enc(resolved.lng + "," + resolved.lat)
                        + "&radius=" + safeRadius + "&types=" + enc(group[1])
                        + "&sortrule=distance&offset=50&page=1&extensions=all";
                JsonNode root = getAmapJson(url);
                if (!"1".equals(root.path("status").asText())) continue;
                for (JsonNode poi : root.path("pois")) {
                    rawCount++;
                    String name = poi.path("name").asText("").trim();
                    String location = poi.path("location").asText("");
                    String rawType = poi.path("type").asText("").replace(";", " / ");
                    String category = playableCategory(rawType, name);
                    if (name.isEmpty() || location.isEmpty()
                            || !group[0].equals(category)) {
                        excludedCount++;
                        continue;
                    }
                    String[] parts = location.split(",");
                    if (parts.length != 2) {
                        excludedCount++;
                        continue;
                    }
                    String dedupe = name + "|" + location;
                    if (!seen.add(dedupe)) continue;
                    Map<String, Object> item = aroundItem(
                            poi, category, resolved, rawType, parts, preference);
                    items.add(item);
                    categoryCounts.merge(category, 1, Integer::sum);
                }
            }
            items.sort((a, b) -> Integer.compare(
                    ((Number) a.getOrDefault("distanceMeters", Integer.MAX_VALUE)).intValue(),
                    ((Number) b.getOrDefault("distanceMeters", Integer.MAX_VALUE)).intValue()));
            Map<String, Object> anchorView = new LinkedHashMap<>();
            anchorView.put("name", resolved.name);
            anchorView.put("address", resolved.address);
            anchorView.put("lat", resolved.lat);
            anchorView.put("lng", resolved.lng);
            anchorView.put("region", resolved.region);
            anchorView.put("radiusMeters", safeRadius);
            out.put("status", "success");
            out.put("mode", "anchor");
            out.put("city", safeCity);
            out.put("query", safeAnchor);
            out.put("anchor", anchorView);
            out.put("radiusMeters", safeRadius);
            out.put("items", items);
            out.put("total", items.size());
            out.put("rawCount", rawCount);
            out.put("excludedCount", excludedCount);
            out.put("categoryCounts", categoryCounts);
            out.put("source", "高德地点锚定周边（T1）");
            out.put("cached", false);
            CACHE.put(cacheKey, new CacheEntry(out, System.currentTimeMillis() + CACHE_TTL_MS));
            recordQuery(safeCity, "周边:" + safeAnchor, false, "地点周边查询成功", out);
            return enrichUgc(out, username);
        } catch (Exception e) {
            log.warn("地点周边查询失败：anchor={}, city={}, error={}",
                    safeAnchor, safeCity, e.getMessage());
            recordFailure(safeCity, safeAnchor, "地点周边查询失败");
            return fail(out, "地点周边查询失败，请补全县/镇名称后重试");
        }
    }

    private ResolvedAnchor resolveAnchor(String city, String anchor) throws Exception {
        String placeUrl = "https://restapi.amap.com/v3/place/text?key=" + enc(amapKey)
                + "&keywords=" + enc(anchor)
                + (city.isEmpty() ? "" : "&city=" + enc(city))
                + "&citylimit=true&offset=10&page=1&extensions=all";
        JsonNode places = getAmapJson(placeUrl);
        if ("1".equals(places.path("status").asText())) {
            for (JsonNode poi : places.path("pois")) {
                String location = poi.path("location").asText("");
                String[] parts = location.split(",");
                if (parts.length != 2) continue;
                ResolvedAnchor result = new ResolvedAnchor();
                result.name = poi.path("name").asText(anchor);
                result.address = poi.path("address").asText("");
                result.region = poi.path("adname").asText("");
                result.lng = parts[0];
                result.lat = parts[1];
                return result;
            }
        }
        String geoUrl = "https://restapi.amap.com/v3/geocode/geo?key=" + enc(amapKey)
                + "&address=" + enc((city.isEmpty() ? "" : city) + anchor)
                + (city.isEmpty() ? "" : "&city=" + enc(city));
        JsonNode geos = getAmapJson(geoUrl);
        if ("1".equals(geos.path("status").asText())) {
            for (JsonNode geo : geos.path("geocodes")) {
                String location = geo.path("location").asText("");
                String[] parts = location.split(",");
                if (parts.length != 2) continue;
                ResolvedAnchor result = new ResolvedAnchor();
                result.name = anchor;
                result.address = geo.path("formatted_address").asText("");
                result.region = geo.path("district").asText("");
                result.lng = parts[0];
                result.lat = parts[1];
                return result;
            }
        }
        return null;
    }

    private JsonNode getAmapJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("高德返回 HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    private Map<String, Object> aroundItem(JsonNode poi, String category,
                                           ResolvedAnchor anchor, String rawType,
                                           String[] location, String preference) {
        Map<String, Object> item = new LinkedHashMap<>();
        String name = poi.path("name").asText("");
        String region = poi.path("adname").asText(anchor.region);
        String address = poi.path("address").asText("");
        String rating = poi.path("biz_ext").path("rating").asText("");
        String cost = poi.path("biz_ext").path("cost").asText("");
        String openTime = poi.path("biz_ext").path("open_time").asText("");
        int distance = poi.path("distance").asInt(0);
        item.put("poiId", poi.path("id").asText(""));
        item.put("name", name);
        item.put("region", region);
        item.put("address", address);
        item.put("category", category);
        item.put("type", rawType);
        item.put("lng", location[0]);
        item.put("lat", location[1]);
        item.put("distanceMeters", distance);
        item.put("distanceText", distance >= 1000
                ? (Math.round(distance / 100d) / 10d) + " km" : distance + " m");
        item.put("rating", rating);
        item.put("cost", cost);
        item.put("openTime", openTime);
        item.put("heat", poi.path("importance").asText(""));
        item.put("durationMin", durationOf(category, rawType));
        item.put("description", descriptionOf(name, category, rawType, region, address,
                rating, cost, openTime));
        item.put("source", "高德地点周边（T1）");
        List<String> evidence = new ArrayList<>();
        evidence.add("地点锚点：" + anchor.name + (anchor.address.isEmpty() ? "" : " · " + anchor.address));
        evidence.add("距锚点约 " + item.get("distanceText"));
        evidence.add("来源：高德周边搜索，查询时间 " + java.time.LocalDate.now());
        item.put("evidence", evidence);
        item.put("preferenceMatch", preferenceMatch(category, rawType, name, preference));
        JsonNode photos = poi.path("photos");
        item.put("photo", photos.isArray() && photos.size() > 0
                ? photos.get(0).path("url").asText("") : "");
        return item;
    }

    /**
     * 周边结果只保留可游玩的景点、餐饮和商圈；住宿、停车、厕所、政务等生活设施剔除。
     */
    static String playableCategory(String rawType, String name) {
        String type = rawType == null ? "" : rawType;
        String label = name == null ? "" : name;
        if (type.matches("(?s).*(住宿服务|旅馆|宾馆酒店|停车场|公共厕所|公共设施|政府机构|交通设施|医疗保健|学校|银行|物流速递|生活服务).*")) {
            return null;
        }
        if (label.matches("(?s).*(停车场|停车区|公共厕所|洗手台|游客中心|服务中心|管理委员会|人民政府|售票处|售票点|出入口|客栈|酒店|民宿|宾馆|旅馆).*")) {
            return null;
        }
        if (type.matches("(?s).*(餐饮服务|咖啡厅|茶艺馆|糕饼店).*")) return "美食";
        if (type.matches("(?s).*(购物中心|商场|商业街|专卖店|休闲娱乐|体育场馆|影剧院|酒吧|KTV|游乐场).*")) {
            return "商圈";
        }
        if (type.matches("(?s).*(风景名胜|公园广场|博物馆|展览馆|科教文化|宗教场所|旅游景点|古迹|动物园|植物园|游乐园).*")) {
            return "景点";
        }
        return null;
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }

    private static final class ResolvedAnchor {
        private String name;
        private String address;
        private String region;
        private String lng;
        private String lat;
    }

    /**
     * 给产品健康面板使用的脱敏运行状态。只返回配置布尔值、缓存和最近一次查询摘要。
     */
    public Map<String, Object> healthSnapshot(String city) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean configured = amapKey != null && !amapKey.trim().isEmpty();
        out.put("key", "amap");
        out.put("label", "高德 POI");
        out.put("configured", configured);
        out.put("status", configured ? "configured" : "missing_key");
        out.put("message", configured
                ? "高德 Web 服务 Key 已配置"
                : "缺少 geo.amap-key，候选点位无法查询");
        out.put("cacheEntries", CACHE.size());
        out.put("cacheLimit", CACHE_LIMIT);
        out.put("requests", REQUESTS.get());
        out.put("cacheHits", CACHE_HITS.get());
        out.put("failures", FAILURES.get());
        if (city != null && !city.trim().isEmpty()) out.put("city", city.trim());
        out.put("lastQuery", new LinkedHashMap<>(LAST_QUERY));
        return out;
    }

    private void recordQuery(String city, String keyword, boolean cached,
                             String message, Map<String, Object> payload) {
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("at", java.time.LocalDateTime.now().toString());
        last.put("city", city == null ? "" : city.trim());
        last.put("keyword", keyword == null ? "" : keyword.trim());
        last.put("cached", cached);
        last.put("status", "success");
        last.put("message", message);
        last.put("itemCount", payload == null || !(payload.get("items") instanceof List)
                ? 0 : ((List<?>) payload.get("items")).size());
        LAST_QUERY = last;
    }

    private void recordFailure(String city, String keyword, String message) {
        FAILURES.incrementAndGet();
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("at", java.time.LocalDateTime.now().toString());
        last.put("city", city == null ? "" : city.trim());
        last.put("keyword", keyword == null ? "" : keyword.trim());
        last.put("cached", false);
        last.put("status", "error");
        last.put("message", message == null ? "查询失败" : message);
        last.put("itemCount", 0);
        LAST_QUERY = last;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> enrichUgc(Map<String, Object> payload, String username) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        List<Map<String, Object>> source = (List<Map<String, Object>>) payload.get("items");
        if (source == null) {
            result.put("items", new ArrayList<>());
            return result;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = MySQLUtil.getConnection()) {
            for (Map<String, Object> original : source) {
                Map<String, Object> item = new LinkedHashMap<>(original);
                String name = String.valueOf(item.getOrDefault("name", ""));
                String poiId = String.valueOf(item.getOrDefault("poiId", ""));
                int reviewCount = 0;
                double reviewAvg = 0;
                int checkinCount = 0;
                int likedCount = 0;
                if (username != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "select count(*) c, avg(rating) a from poi_review"
                                    + " where (visibility = 'public' or username = ?)"
                                    + " and (poi_name = ? or poi_id = ?)")) {
                        ps.setString(1, username);
                        ps.setString(2, name);
                        ps.setString(3, poiId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                reviewCount = rs.getInt("c");
                                reviewAvg = rs.getDouble("a");
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "select count(*) c, sum(case when rating = 'like' then 1 else 0 end) likes"
                                    + " from travel_log where (visibility = 'public' or username = ?)"
                                    + " and (place = ? or poi_name = ?)")) {
                        ps.setString(1, username);
                        ps.setString(2, name);
                        ps.setString(3, name);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                checkinCount = rs.getInt("c");
                                likedCount = rs.getInt("likes");
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                List<String> evidence = new ArrayList<>();
                if (item.get("evidence") instanceof List) {
                    evidence.addAll((List<String>) item.get("evidence"));
                }
                if (reviewCount + checkinCount > 0) {
                    evidence.add("自建体验：打卡 " + checkinCount + " 次，评价 " + reviewCount
                            + " 条" + (reviewCount > 0 ? "，均分 " + Math.round(reviewAvg * 10) / 10.0 : ""));
                    item.put("source", "高德 POI（T1）+ 自建体验（T2）");
                } else {
                    evidence.add("暂无自建体验证据");
                }
                item.put("evidence", evidence);
                item.put("ugcReviewCount", reviewCount);
                item.put("ugcCheckinCount", checkinCount);
                item.put("ugcLikedCount", likedCount);
                int preference = ((Number) item.getOrDefault("preferenceMatch", 0)).intValue();
                item.put("preferenceMatch", preference + Math.min(6, checkinCount + likedCount + reviewCount));
                items.add(item);
            }
        } catch (Exception e) {
            log.debug("候选 UGC 证据读取失败：{}", e.getMessage());
            result.put("items", new ArrayList<>(source));
            return result;
        }
        result.put("items", items);
        return result;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** 类型关键词 → 高德 POI 类型码；其它文字（如店名）走 keywords 精确搜索 */
    private static String typesOf(String keyword) {
        if (keyword == null) return "";
        String k = keyword.trim();
        if ("景点".equals(k)) return "风景名胜|公园广场|博物馆|展览馆";
        if ("美食".equals(k)) return "餐饮服务";
        if ("咖啡".equals(k)) return "咖啡厅";
        if ("玩乐".equals(k)) return "休闲娱乐|体育场馆";
        if ("商圈".equals(k)) return "购物中心|休闲娱乐|体育场馆|影剧院";
        return "";
    }

    private static int durationOf(String keyword, String rawType) {
        if ("景点".equals(keyword)) return rawType.contains("博物馆") ? 120 : 150;
        if ("美食".equals(keyword)) return 75;
        if ("咖啡".equals(keyword)) return 50;
        if ("玩乐".equals(keyword)) return rawType.contains("电影") ? 150 : 120;
        if ("商圈".equals(keyword)) return 90;
        return 90;
    }

    private static String descriptionOf(String name, String keyword, String rawType, String region,
                                        String address, String rating, String cost, String openTime) {
        StringBuilder s = new StringBuilder();
        s.append(name).append("属于").append(keyword);
        if (!rawType.isEmpty()) s.append("（").append(rawType).append("）");
        if (!region.isEmpty()) s.append("，位于").append(region);
        if (!address.isEmpty()) s.append(" ").append(address);
        s.append("。");
        if (!rating.isEmpty()) s.append("高德平台评分 ").append(rating).append("。");
        if (!cost.isEmpty()) s.append("人均约 ¥").append(cost).append("。");
        if (!openTime.isEmpty()) s.append("营业信息：").append(openTime).append("。");
        s.append("建议预留 ").append(durationOf(keyword, rawType)).append(" 分钟。");
        return s.toString();
    }

    private static int preferenceMatch(String keyword, String rawType, String name, String preference) {
        if (preference == null || preference.trim().isEmpty()) return 0;
        String p = preference.toLowerCase(Locale.ROOT);
        String text = (keyword + " " + rawType + " " + name).toLowerCase(Locale.ROOT);
        int score = 0;
        if (p.contains("美食") && text.contains("餐饮")) score += 3;
        if (p.contains("咖啡") && text.contains("咖啡")) score += 3;
        if (p.contains("人文") && (text.contains("博物馆") || text.contains("文化")
                || text.contains("展馆") || text.contains("古迹"))) score += 3;
        if (p.contains("自然") && (text.contains("风景") || text.contains("公园")
                || text.contains("山") || text.contains("湖"))) score += 3;
        if (p.contains("室内") && (text.contains("博物馆") || text.contains("购物")
                || text.contains("影") || text.contains("展"))) score += 2;
        return score;
    }

    private static final class CacheEntry {
        private final Map<String, Object> payload;
        private final long expiresAt;

        private CacheEntry(Map<String, Object> payload, long expiresAt) {
            this.payload = new LinkedHashMap<>(payload);
            this.expiresAt = expiresAt;
        }
    }
}
