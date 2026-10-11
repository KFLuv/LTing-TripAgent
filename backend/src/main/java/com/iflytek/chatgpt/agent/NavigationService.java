package com.iflytek.chatgpt.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德地图能力：周边搜索（真实距离）+ 地理编码 + 导航链接生成。
 * <p>
 * <b>周边搜索</b>是"附近"唯一可信的数据源：以用户坐标为圆心按距离排序，返回真实门店名与直线距离（米）。
 * 网页检索（searchWeb）拿不到距离，用它推荐"附近"会把几公里外的店说成"身边"，因此
 * 「附近/周边有什么」一律先走这里，searchWeb 只留给当日活动、新开店铺这类 POI 库里没有的信息。
 * <p>
 * <b>导航</b>两步：
 * <ol>
 *   <li><b>地理编码</b>：用高德 Web 服务把地名换成经纬度（GCJ-02），顺带拿到规范地址；</li>
 *   <li><b>拼 URI</b>：高德 URI API 的 navigation 形式，点开即是"当前位置 → 目的地"的路线导航。</li>
 * </ol>
 * 不需要新的 Key——与逆地理编码共用 {@code geo.amap-key}（同为「Web服务」类型）。
 * 地名解析失败（或没配 Key）时降级为高德关键词搜索链接，保证用户始终有点得动的入口，
 * 而不是让模型编造一条路线。
 * <p>
 * 坐标系：调用方传进来的是浏览器定位的 WGS-84，本类在调高德前统一纠偏成 GCJ-02（见 {@link CoordConverter}）。
 */
@Component
public class NavigationService {

    private static final Logger log = LoggerFactory.getLogger(NavigationService.class);

    /** 高德地理编码：地名 → 经纬度（GCJ-02） */
    private static final String GEOCODE_ENDPOINT =
            "https://restapi.amap.com/v3/geocode/geo?output=JSON&address=";

    /** 高德周边搜索：按距离由近及远返回真实 POI，用于回答"附近有什么" */
    private static final String AROUND_ENDPOINT =
            "https://restapi.amap.com/v3/place/around?output=JSON&sortrule=distance&location=";

    /** 高德路径规划：driving/walking/bicycling 三种方式共用同一返回结构（折线点串） */
    private static final String ROUTE_BASE = "https://restapi.amap.com/v3/direction/";

    /** 高德公交换乘（integrated）：返回结构不同，需按 segments 逐个抽折线，且必须带城市 */
    private static final String TRANSIT_ENDPOINT =
            "https://restapi.amap.com/v3/direction/transit/integrated?output=JSON&extensions=base";

    /** 周边搜索半径/条数边界，防止把脏参数拼进 URL */
    private static final int MIN_RADIUS = 100;
    private static final int MAX_RADIUS = 10000;
    private static final int MAX_LIMIT = 25;

    /** 出行方式白名单，非法值一律按驾车处理，避免把脏参数拼进 URL */
    private static final List<String> MODES = Arrays.asList("car", "bus", "walk", "ride");

    /** 高德 Web 服务 Key；没配则只能给关键词搜索链接 */
    @Value("${geo.amap-key:}")
    private String amapKey;

    /** 高德：国内服务，强制直连（应用为 Nominatim 开了系统代理，这里必须绕开） */
    private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 上次地理编码得到的规范地址，由 geocode() 填充，供 buildNavigation() 一并返回 */
    private String lastResolvedAddress;

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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .proxy(DIRECT)
                .build();
    }

    /**
     * 生成导航链接。
     *
     * @param destination 目的地名称，如「广州塔」「星巴克科学城高德汇店」
     * @param city        所在城市，可选，用于同名地点消歧，如「广州」
     * @param mode        出行方式，可选：car（默认）/bus/walk/ride
     * @param originLat   用户当前纬度（WGS-84，来自 session 的 GPS 上报，可为 null）
     * @param originLng   用户当前经度（WGS-84，同上）
     * @return 含 uri（导航链接）、resolved（规范地址）等字段的结果，供模型转述给用户
     */
    public Map<String, Object> buildNavigation(String destination, String city, String mode,
                                               Double originLat, Double originLng) {
        Map<String, Object> result = new LinkedHashMap<>();
        String name = destination == null ? "" : destination.trim();
        if (name.isEmpty()) {
            result.put("error", "目的地名称为空，无法生成导航链接");
            return result;
        }

        String travelMode = mode == null ? "" : mode.trim().toLowerCase();
        if (!MODES.contains(travelMode)) {
            travelMode = "car";
        }

        result.put("destination", name);
        result.put("mode", travelMode);

        double[] location = geocode(name, city);
        if (location != null) {
            result.put("lng", location[0]);
            result.put("lat", location[1]);
            result.put("coordinateSystem", "GCJ-02（高德）");
            boolean hasOrigin = originLat != null && originLng != null;
            result.put("uri", buildNavigationUri(location[0], location[1], name, travelMode,
                    originLat, originLng));
            if (hasOrigin) {
                result.put("origin", "我的位置");
                result.put("note", "uri 是可直接点击的导航链接，已把用户当前位置作为起点（from），"
                        + "点开即有完整路线。请用 Markdown 链接输出，"
                        + "例如 [🚗 导航到" + name + "](uri)：必须把 uri 字段**原样复制**，"
                        + "禁止自己拼接或改写任何 URL，禁止自行填写经纬度"
                        + "（系统提示里的「经纬度」是你自己的位置，不是目的地，写错会把终点标到你脚下）。");
            } else {
                result.put("note", "uri 是可直接点击的导航链接，但用户还没上报定位，起点为空。"
                        + "请用 Markdown 链接输出（如 [🚗 导航到" + name + "](uri)），"
                        + "并提醒用户先点「我的位置」上报位置，或在高德页面手动填写起点。");
            }
        } else {
            result.put("uri", buildSearchUri(name, city));
            result.put("note", "该地名未能解析出坐标（可能名称不完整或高德服务不可用），已降级为高德搜索链接。"
                    + "请用 Markdown 链接输出，例如 [🔍 在高德搜索" + name + "](uri)，"
                    + "并提醒用户链接是搜索结果页、需自行确认具体门店。");
        }

        String address = lastResolvedAddress;
        if (address != null && !address.isEmpty()) {
            result.put("resolvedAddress", address);
        }
        return result;
    }

    /**
     * 周边搜索：以用户当前位置为圆心，按距离由近及远返回真实 POI。
     * <p>
     * 这是回答「附近有什么」的唯一可信来源——每条结果都带真实直线距离（米），
     * 让管家能说清"步行可达 / 需打车"，而不是拿几公里外的攻略店当"身边"。
     *
     * @param keywords     关键词，如「美食」「餐厅|咖啡」「景点」；为空则返回周边全部 POI
     * @param lat          用户纬度（WGS-84，内部会纠偏成 GCJ-02）
     * @param lng          用户经度（WGS-84）
     * @param radiusMeters 搜索半径（米），默认 2000，限制在 100~10000
     * @param limit        返回条数，默认 10，最多 25
     */
    public Map<String, Object> searchNearby(String keywords, Double lat, Double lng,
                                            Integer radiusMeters, Integer limit) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 位置必须来自 GPS 上报：没有坐标就问用户，绝不让模型自己编一个坐标
        if (lat == null || lng == null) {
            result.put("error", "还没有用户的定位：请先让用户点一次「我的位置」上报位置，再查周边");
            return result;
        }

        int radius = radiusMeters == null ? 2000 : radiusMeters;
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        int size = limit == null ? 10 : Math.max(1, Math.min(MAX_LIMIT, limit));

        // 入参是浏览器定位的 WGS-84，高德要求 GCJ-02
        double[] gcj = CoordConverter.wgs84ToGcj02(lat, lng);

        result.put("origin", "经度 " + lng + ", 纬度 " + lat);
        result.put("radiusMeters", radius);

        String key = amapKey == null ? "" : amapKey.trim();
        if (key.isEmpty()) {
            result.put("error", "未配置 geo.amap-key，无法做周边搜索");
            return result;
        }

        try {
            StringBuilder url = new StringBuilder(AROUND_ENDPOINT)
                    .append(gcj[1]).append(",").append(gcj[0])
                    .append("&radius=").append(radius)
                    .append("&offset=").append(size)
                    .append("&page=1&extensions=base&key=").append(key);
            String kw = keywords == null ? "" : keywords.trim();
            if (!kw.isEmpty()) {
                url.append("&keywords=").append(encode(kw));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("高德周边搜索返回 HTTP {}", response.statusCode());
                result.put("error", "周边搜索服务暂时不可用（HTTP " + response.statusCode() + "）");
                return result;
            }

            JsonNode json = objectMapper.readTree(response.body());
            if (!"1".equals(json.path("status").asText(""))) {
                log.warn("高德周边搜索失败：{}", json.path("info").asText(""));
                result.put("error", "周边搜索失败：" + json.path("info").asText(""));
                return result;
            }

            List<Map<String, Object>> pois = new ArrayList<>();
            for (JsonNode poi : json.path("pois")) {
                int distance = parseIntSafe(poi.path("distance").asText(""));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", poi.path("name").asText(""));
                item.put("distanceMeters", distance);
                item.put("distanceLabel", distanceLabel(distance));
                item.put("address", poi.path("address").asText(""));
                item.put("type", poi.path("type").asText(""));
                String location = poi.path("location").asText("");
                if (location.contains(",")) {
                    String[] parts = location.split(",");
                    item.put("lng", parts[0]);
                    item.put("lat", parts[1]);
                }
                pois.add(item);
            }

            result.put("count", pois.size());
            result.put("results", pois);
            result.put("source", "高德 POI 库，按直线距离由近及远");
            result.put("note", pois.isEmpty()
                    ? "该半径内没有搜到结果，可把 radius 放宽（如 5000）后重试；仍没有就如实告诉用户没搜到。"
                    : "distanceMeters 是直线距离（非步行/驾车里程），必须原样告知用户并据此说明"
                    + "「步行可达 / 需打车」，不要用「附近」这种模糊说法，也不要添加本列表之外的店名。");
            return result;
        } catch (Exception e) {
            log.warn("高德周边搜索异常：{}", e.getMessage());
            result.put("error", "周边搜索异常：" + e.getMessage());
            return result;
        }
    }

    /**
     * 路径规划：把「我 → 目的地」的真实路线算出来交给前端画线。
     * <p>
     * 起终点坐标系不同：起点是浏览器定位的 WGS-84，目的地是高德给的 GCJ-02，
     * 所以只纠偏起点，目的地原样使用，否则目的地会偏几百米。
     * 走「Web 服务」Key，避免前端 JS API 缺安全密钥时路径规划失败。
     *
     * @param mode driving（驾车）/ walking（步行）/ bicycling（骑行），其它值按驾车
     * @return ok=true 时带 mode / distanceMeters / durationSeconds / polyline("lng,lat;lng,lat;...")
     */
    public Map<String, Object> planRoute(double fromLat, double fromLng,
                                         double toLat, double toLng, String mode) {
        Map<String, Object> result = new LinkedHashMap<>();

        String key = amapKey == null ? "" : amapKey.trim();
        if (key.isEmpty()) {
            result.put("error", "未配置 geo.amap-key，无法规划路线");
            return result;
        }

        String path = routeModePath(mode);
        // 驾车才支持策略参数，步行/骑行加了会报错
        String extra = "driving".equals(path) ? "&strategy=0" : "";

        double[] from = CoordConverter.wgs84ToGcj02(fromLat, fromLng);

        try {
            String url = ROUTE_BASE + path + "?output=JSON&extensions=base" + extra
                    + "&origin=" + from[1] + "," + from[0]
                    + "&destination=" + toLng + "," + toLat
                    + "&key=" + key;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("高德路径规划返回 HTTP {}", response.statusCode());
                result.put("error", "路径规划服务暂时不可用（HTTP " + response.statusCode() + "）");
                return result;
            }

            JsonNode json = objectMapper.readTree(response.body());
            if (!"1".equals(json.path("status").asText(""))) {
                log.warn("高德路径规划失败：{}", json.path("info").asText(""));
                result.put("error", "路径规划失败：" + json.path("info").asText(""));
                return result;
            }

            JsonNode routePlan = json.path("route").path("paths").path(0);
            if (routePlan.isMissingNode()) {
                result.put("error", "没有可用的路线");
                return result;
            }

            // 各段折线拼起来：每段是 "lng,lat;lng,lat;..."，段间用 ';' 连接
            StringBuilder polyline = new StringBuilder();
            for (JsonNode step : routePlan.path("steps")) {
                appendPolyline(polyline, step.path("polyline").asText(""));
            }
            if (polyline.length() == 0) {
                result.put("error", "路径规划返回为空");
                return result;
            }

            result.put("ok", true);
            result.put("mode", path);
            result.put("distanceMeters", parseIntSafe(routePlan.path("distance").asText("0")));
            result.put("durationSeconds", parseIntSafe(routePlan.path("duration").asText("0")));
            result.put("polyline", polyline.toString());
            return result;
        } catch (Exception e) {
            log.warn("高德路径规划异常：{}", e.getMessage());
            result.put("error", "路径规划异常：" + e.getMessage());
            return result;
        }
    }

    /**
     * 公交换乘：高德 integrated 接口返回多套方案，这里取第一套，把步行段与公交段的折线拼成一条线。
     *
     * @param city 起点所在城市名（如「广州市」），高德公交接口必填，由调用方先逆地理解析
     * @return ok=true 时带 distanceMeters / durationSeconds / cost(票价，元) / polyline
     */
    public Map<String, Object> planTransit(double fromLat, double fromLng,
                                           double toLat, double toLng, String city) {
        Map<String, Object> result = new LinkedHashMap<>();

        String key = amapKey == null ? "" : amapKey.trim();
        if (key.isEmpty()) {
            result.put("error", "未配置 geo.amap-key，无法规划公交路线");
            return result;
        }
        if (city == null || city.trim().isEmpty()) {
            result.put("error", "无法确定所在城市，公交路线需要城市信息");
            return result;
        }

        double[] from = CoordConverter.wgs84ToGcj02(fromLat, fromLng);

        try {
            String url = TRANSIT_ENDPOINT
                    + "&city=" + encode(city.trim())
                    + "&origin=" + from[1] + "," + from[0]
                    + "&destination=" + toLng + "," + toLat
                    + "&key=" + key;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("高德公交换乘返回 HTTP {}", response.statusCode());
                result.put("error", "公交服务暂时不可用（HTTP " + response.statusCode() + "）");
                return result;
            }

            JsonNode json = objectMapper.readTree(response.body());
            if (!"1".equals(json.path("status").asText(""))) {
                log.warn("高德公交换乘失败：{}", json.path("info").asText(""));
                result.put("error", "公交换乘失败：" + json.path("info").asText(""));
                return result;
            }

            JsonNode transits = json.path("route").path("transits");
            if (!transits.isArray() || transits.size() == 0) {
                result.put("error", "两地之间没有可用的公交方案");
                return result;
            }

            JsonNode transit = transits.get(0);
            StringBuilder polyline = new StringBuilder();
            for (JsonNode segment : transit.path("segments")) {
                // 步行段
                for (JsonNode step : segment.path("walking").path("steps")) {
                    appendPolyline(polyline, step.path("polyline").asText(""));
                }
                // 公交/地铁段
                for (JsonNode busline : segment.path("bus").path("buslines")) {
                    appendPolyline(polyline, busline.path("polyline").asText(""));
                }
            }
            if (polyline.length() == 0) {
                result.put("error", "公交方案没有可绘制的路径");
                return result;
            }

            result.put("ok", true);
            result.put("mode", "transit");
            result.put("distanceMeters", parseIntSafe(transit.path("distance").asText("0")));
            result.put("durationSeconds", parseIntSafe(transit.path("duration").asText("0")));
            result.put("cost", transit.path("cost").asText("")); // 票价（元），可能为空
            result.put("polyline", polyline.toString());
            return result;
        } catch (Exception e) {
            log.warn("高德公交换乘异常：{}", e.getMessage());
            result.put("error", "公交换乘异常：" + e.getMessage());
            return result;
        }
    }

    /** 拼接折线段：高德每段是 "lng,lat;lng,lat;..."，段间补一个 ';' */
    private void appendPolyline(StringBuilder sb, String poly) {
        if (poly == null || poly.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(poly);
    }

    /** 出行方式白名单映射：非法值一律按驾车，避免把脏参数拼进 URL */
    private String routeModePath(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase();
        if ("walking".equals(m) || "walk".equals(m)) {
            return "walking";
        }
        if ("bicycling".equals(m) || "bike".equals(m) || "ride".equals(m)) {
            return "bicycling";
        }
        return "driving";
    }

    /**
     * 地址搜索：把用户输入的地名解析成经纬度（GCJ-02），供内嵌地图直接定位。
     */
    public Map<String, Object> geocodePlace(String address, String city) {
        Map<String, Object> result = new LinkedHashMap<>();
        String name = address == null ? "" : address.trim();
        if (name.isEmpty()) {
            result.put("error", "请输入要搜索的地点");
            return result;
        }
        String key = amapKey == null ? "" : amapKey.trim();
        if (key.isEmpty()) {
            result.put("error", "未配置 geo.amap-key，无法搜索地点");
            return result;
        }
        double[] point = geocode(name, city);
        if (point == null) {
            result.put("error", "没查到该地点：" + name);
            return result;
        }
        result.put("ok", true);
        result.put("lng", point[0]);
        result.put("lat", point[1]);
        result.put("name", lastResolvedAddress == null || lastResolvedAddress.isEmpty()
                ? name : lastResolvedAddress);
        return result;
    }

    /** 把米数说成人话：明确区分"走得到"和"得坐车"，这正是之前"附近"翻车的地方 */
    private String distanceLabel(int meters) {
        if (meters <= 0) {
            return "距离未知";
        }
        if (meters < 800) {
            return "步行可达（约 " + Math.max(1, meters / 80) + " 分钟）";
        }
        if (meters < 2000) {
            return "步行偏远近，可骑共享单车（约 " + Math.max(1, meters / 80) + " 分钟）";
        }
        if (meters < 5000) {
            return "需打车/骑行（约 " + String.format("%.1f", meters / 1000.0) + " 公里）";
        }
        return "较远，建议打车（约 " + String.format("%.1f", meters / 1000.0) + " 公里）";
    }

    private int parseIntSafe(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 高德地理编码：成功返回 [经度, 纬度]，失败返回 null。
     * 结果写入 lastResolvedAddress，供调用方补充规范地址。
     */
    private double[] geocode(String name, String city) {
        lastResolvedAddress = null;
        String key = amapKey == null ? "" : amapKey.trim();
        if (key.isEmpty()) {
            return null;
        }
        try {
            StringBuilder url = new StringBuilder(GEOCODE_ENDPOINT)
                    .append(encode(name))
                    .append("&key=").append(key);
            String cityTrimmed = city == null ? "" : city.trim();
            if (!cityTrimmed.isEmpty()) {
                url.append("&city=").append(encode(cityTrimmed));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("高德地理编码返回 HTTP {}", response.statusCode());
                return null;
            }

            JsonNode json = objectMapper.readTree(response.body());
            if (!"1".equals(json.path("status").asText(""))) {
                log.warn("高德地理编码失败：{}", json.path("info").asText(""));
                return null;
            }
            JsonNode first = json.path("geocodes").path(0);
            String location = first.path("location").asText("");
            lastResolvedAddress = first.path("formatted_address").asText("");
            if (location.isEmpty() || !location.contains(",")) {
                return null;
            }
            String[] parts = location.split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            log.warn("高德地理编码异常：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成高德「新版 PC 站点」（www.amap.com）的路线深链，与「内嵌地图」按钮打开的
     * https://www.amap.com/ 同站同 UI（新版界面、驾车/公交/步行/骑行、多方案、探索周边）。
     * <p>
     * 参数格式：type=car|bus|walk|ride，to[lnglat]/from[lnglat]=经度,纬度，to[name]/from[name]=名称；
     * 方括号统一写成 %5B/%5D，避免被 Markdown / 链接正则当成 URL 边界截断。
     * <p>
     * 内嵌的高德页面是跨域 iframe / webview，默认拿不到浏览器定位，
     * 只传 to 时起点会一直空着（"识别不到我的起点"）。这里直接把 session 里
     * 上报的当前坐标（WGS-84 纠偏成高德 GCJ-02）作为 from 传进去，起点才认得出。
     */
    private String buildNavigationUri(double lng, double lat, String name, String mode,
                                      Double originLat, Double originLng) {
        StringBuilder uri = new StringBuilder("https://www.amap.com/dir?type=")
                .append(mode)
                .append("&to%5Blnglat%5D=").append(lng).append(",").append(lat)
                .append("&to%5Bname%5D=").append(encode(name))
                .append("&src=aitravelguide");
        if (originLat != null && originLng != null) {
            // CoordConverter 返回 {纬度, 经度}
            double[] gcj = CoordConverter.wgs84ToGcj02(originLat, originLng);
            uri.append("&from%5Blnglat%5D=").append(gcj[1]).append(",").append(gcj[0])
                    .append("&from%5Bname%5D=").append(encode("我的位置"));
        }
        return uri.toString();
    }

    /** 降级方案：关键词搜索页（同样用新版站点 www.amap.com），用户自行选门店 */
    private String buildSearchUri(String name, String city) {
        String uri = "https://www.amap.com/search?query=" + encode(name) + "&src=aitravelguide";
        String cityTrimmed = city == null ? "" : city.trim();
        if (!cityTrimmed.isEmpty()) {
            uri += "&city=" + encode(cityTrimmed);
        }
        return uri;
    }

    private String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
