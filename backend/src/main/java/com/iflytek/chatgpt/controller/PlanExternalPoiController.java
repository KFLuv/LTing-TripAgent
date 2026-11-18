package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.chatgpt.agent.CoordConverter;
import com.iflytek.chatgpt.agent.NavigationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 高德之外的补充 POI：OpenStreetMap、Wikidata/Wikivoyage 和地方文旅 CSV/GeoJSON。
 *
 * <p>外部数据统一落到 external_poi，保留 source/source_id/license/attribution。
 * 它们不会覆盖高德结果，只作为候选池补充，并在前端标明来源。
 */
@RestController
public class PlanExternalPoiController {

    private static final Logger log = LoggerFactory.getLogger(PlanExternalPoiController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] OSM_ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    };
    private static final String WIKIVOYAGE_API = "https://zh.wikivoyage.org/w/api.php";
    private static final String WIKIDATA_SPARQL = "https://query.wikidata.org/sparql";
    private static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 5000;

    private final NavigationService navigationService;
    private final PlanCommercialPoiService commercialPoiService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    public PlanExternalPoiController(NavigationService navigationService,
                                     PlanCommercialPoiService commercialPoiService) {
        this.navigationService = navigationService;
        this.commercialPoiService = commercialPoiService;
    }

    @PostMapping(value = "/plan/external/sync", produces = "application/json;charset=UTF-8")
    public Map<String, Object> sync(@RequestParam String source,
                                    @RequestParam String city,
                                    @RequestParam(required = false) String anchor,
                                    @RequestParam(defaultValue = "5000") int radius,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) return fail(out, "请先登录");
        String safeSource = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("osm", "wiki", "baidu", "tencent").contains(safeSource)) {
            return fail(out, "补充源只能是 osm、wiki、baidu 或 tencent");
        }
        if (("baidu".equals(safeSource) || "tencent".equals(safeSource))
                && !commercialPoiService.configured(safeSource)) {
            return fail(out, "未配置 " + ("baidu".equals(safeSource)
                    ? "geo.baidu-key" : "geo.tencent-key"));
        }
        String safeCity = city == null ? "" : city.trim();
        if (safeCity.isEmpty()) return fail(out, "缺少城市");
        int safeRadius = Math.max(500, Math.min(10000, radius));
        String centerName = anchor == null || anchor.trim().isEmpty() ? safeCity : anchor.trim();
        try {
            Map<String, Object> geo = navigationService.geocodePlace(centerName, safeCity);
            if (!Boolean.TRUE.equals(geo.get("ok"))) {
                return fail(out, "无法解析“" + centerName + "”坐标");
            }
            double lat = Double.parseDouble(String.valueOf(geo.get("lat")));
            double lng = Double.parseDouble(String.valueOf(geo.get("lng")));
            // 高德锚点是 GCJ-02；OSM / 维基查询必须使用 WGS-84。
            double[] queryCenter = CoordConverter.gcj02ToWgs84(lat, lng);
            List<String> warnings = new ArrayList<>();
            int fetched;
            if ("osm".equals(safeSource)) {
                fetched = syncOsm(safeCity, queryCenter[0], queryCenter[1], safeRadius);
            } else if ("wiki".equals(safeSource)) {
                fetched = syncWikivoyage(
                        safeCity, queryCenter[0], queryCenter[1], safeRadius, warnings);
                fetched += syncWikidata(
                        safeCity, queryCenter[0], queryCenter[1], safeRadius, warnings);
            } else {
                List<PlanCommercialPoiService.ProviderPoi> providerItems =
                        commercialPoiService.fetch(
                                safeSource, safeCity, centerName, lat, lng, safeRadius);
                fetched = importCommercial(providerItems);
            }
            // 数据库中的点位与页面地图统一存 GCJ-02。
            List<Map<String, Object>> items = queryStored(
                    safeSource, safeCity, lat, lng, safeRadius, 500);
            out.put("status", "success");
            out.put("source", safeSource);
            out.put("city", safeCity);
            out.put("anchor", centerName);
            out.put("radiusMeters", safeRadius);
            out.put("fetched", fetched);
            out.put("total", items.size());
            out.put("items", items);
            out.put("warnings", warnings);
            out.put("message", "已补充 " + items.size() + " 个外部点位");
            return out;
        } catch (Exception e) {
            log.warn("外部 POI 同步失败：source={}, city={}, error={}",
                    safeSource, safeCity, e.getMessage());
            String message = e.getMessage();
            return fail(out, message != null && message.startsWith("Overpass")
                    ? message : "外部数据源暂不可用，请稍后重试");
        }
    }

    @PostMapping(value = "/plan/external/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(@RequestParam(defaultValue = "gov") String source,
                                    @RequestParam(required = false) String city,
                                    @RequestParam(defaultValue = "0") double lat,
                                    @RequestParam(defaultValue = "0") double lng,
                                    @RequestParam(defaultValue = "0") int radius,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) return fail(out, "请先登录");
        try {
            List<Map<String, Object>> items = queryStored(
                    source, city, lat, lng, radius, 500);
            out.put("status", "success");
            out.put("source", source);
            out.put("items", items);
            out.put("total", items.size());
            return out;
        } catch (Exception e) {
            return fail(out, "外部点位读取失败");
        }
    }

    @PostMapping(value = "/plan/external/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json;charset=UTF-8")
    public Map<String, Object> importData(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String city,
                                          @RequestParam(required = false) String sourceName,
                                          @RequestParam(required = false) String license,
                                          @RequestParam(required = false) String attribution,
                                          @RequestParam(defaultValue = "wgs84") String coordinateSystem,
                                          HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) return fail(out, "请先登录");
        if (file == null || file.isEmpty()) return fail(out, "请选择 CSV 或 GeoJSON 文件");
        if (file.getSize() > MAX_IMPORT_BYTES) return fail(out, "文件不能超过 5 MB");
        String safeName = blank(sourceName) ? "地方文旅开放数据" : sourceName.trim();
        String safeLicense = blank(license) ? "以数据提供方授权为准" : license.trim();
        String safeAttribution = blank(attribution) ? safeName : attribution.trim();
        String safeCoordinateSystem = normalizeCoordinateSystem(coordinateSystem);
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8)
                    .replace("\uFEFF", "");
            List<Map<String, Object>> records = text.trim().startsWith("{")
                    ? parseGeoJson(text) : parseCsv(text);
            int imported = 0;
            int skipped = 0;
            for (Map<String, Object> record : records) {
                if (records.size() > MAX_IMPORT_ROWS) break;
                String name = textValue(record, "name");
                Double lat = doubleValue(record, "lat");
                Double lng = doubleValue(record, "lng");
                if (name.isEmpty() || lat == null || lng == null) {
                    skipped++;
                    continue;
                }
                if (!validCoordinate(lat, lng)) {
                    skipped++;
                    continue;
                }
                String rowCity = blank(textValue(record, "city")) ? trim(city, 100)
                        : trim(textValue(record, "city"), 100);
                double[] sourceWgs = "gcj02".equals(safeCoordinateSystem)
                        ? CoordConverter.gcj02ToWgs84(lat, lng)
                        : new double[]{lat, lng};
                double[] storedGcj = "gcj02".equals(safeCoordinateSystem)
                        ? new double[]{lat, lng}
                        : CoordConverter.wgs84ToGcj02(lat, lng);
                String sourceId = "gov_" + sha1(name + "|"
                        + coordinateKey(sourceWgs[0], sourceWgs[1]));
                upsert("gov", sourceId, name,
                        firstNonBlank(textValue(record, "category"), "景点"),
                        rowCity, textValue(record, "region"), textValue(record, "address"),
                        storedGcj[0], storedGcj[1], textValue(record, "description"),
                        textValue(record, "url"), safeLicense, safeAttribution,
                        "{\"provider\":" + JSON.writeValueAsString(safeName)
                                + ",\"inputCoordinateSystem\":"
                                + JSON.writeValueAsString(safeCoordinateSystem)
                                + ",\"storedCoordinateSystem\":\"gcj02\"}");
                imported++;
            }
            out.put("status", imported > 0 ? "success" : "error");
            out.put("message", imported > 0
                    ? "已导入 " + imported + " 个文旅点位"
                    : "没有可导入的有效点位");
            out.put("imported", imported);
            out.put("skipped", skipped);
            out.put("totalRows", records.size());
            out.put("sourceName", safeName);
            out.put("coordinateSystem", safeCoordinateSystem);
            return out;
        } catch (Exception e) {
            log.warn("文旅数据导入失败：{}", e.getMessage());
            return fail(out, "文件解析失败，请检查 CSV/GeoJSON 字段");
        }
    }

    private int syncOsm(String city, double lat, double lng, int radius) throws Exception {
        String query = "[out:json][timeout:25];("
                + "nwr[\"tourism\"~\"attraction|museum|viewpoint|gallery|artwork\"](around:"
                + radius + "," + lat + "," + lng + ");"
                + "nwr[\"historic\"](around:" + radius + "," + lat + "," + lng + ");"
                + "nwr[\"leisure\"~\"park|garden|nature_reserve\"](around:"
                + radius + "," + lat + "," + lng + ");"
                + "nwr[\"amenity\"~\"restaurant|cafe|fast_food\"](around:"
                + radius + "," + lat + "," + lng + ");"
                + "nwr[\"shop\"~\"mall|department_store\"](around:"
                + radius + "," + lat + "," + lng + ");"
                + ");out center tags 300;";
        HttpResponse<String> response = null;
        String lastError = "";
        for (String endpoint : OSM_ENDPOINTS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                        .header("User-Agent", "LTing/2.0 (travel planning; external POI supplement)")
                        .POST(HttpRequest.BodyPublishers.ofString("data=" + enc(query)))
                        .build();
                HttpResponse<String> candidate = http.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (candidate.statusCode() == 200) {
                    response = candidate;
                    break;
                }
                String body = candidate.body() == null ? "" : candidate.body();
                lastError = "HTTP " + candidate.statusCode() + " "
                        + body.replaceAll("\\s+", " ").substring(0,
                        Math.min(120, body.replaceAll("\\s+", " ").length()));
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        if (response == null) {
            throw new IllegalStateException("Overpass 公共镜像暂不可用：" +
                    (lastError == null || lastError.isEmpty() ? "请求超时或排队已满" : lastError));
        }
        JsonNode root = JSON.readTree(response.body());
        int count = 0;
        for (JsonNode element : root.path("elements")) {
            JsonNode tags = element.path("tags");
            String name = firstNonBlank(tags.path("name:zh").asText(""),
                    tags.path("name").asText(""));
            if (name.isEmpty()) continue;
            String type = element.path("type").asText("node");
            String sourceId = "osm_" + type + "_" + element.path("id").asText("");
            double itemLat = element.has("lat")
                    ? element.path("lat").asDouble() : element.path("center").path("lat").asDouble();
            double itemLng = element.has("lon")
                    ? element.path("lon").asDouble() : element.path("center").path("lon").asDouble();
            if (itemLat == 0 || itemLng == 0) continue;
            String category = osmCategory(tags);
            String address = firstNonBlank(tags.path("addr:full").asText(""),
                    joinAddress(tags));
            String description = firstNonBlank(tags.path("description:zh").asText(""),
                    tags.path("description").asText(""),
                    tags.path("tourism").asText(""),
                    tags.path("historic").asText(""));
            double[] stored = CoordConverter.wgs84ToGcj02(itemLat, itemLng);
            upsert("osm", sourceId, name, category, city,
                    tags.path("addr:district").asText(""), address,
                    stored[0], stored[1], description,
                    "https://www.openstreetmap.org/" + type + "/" + element.path("id").asText(""),
                    "ODbL-1.0", "© OpenStreetMap contributors", tags.toString());
            count++;
        }
        return count;
    }

    private int syncWikivoyage(String city, double lat, double lng,
                               int radius, List<String> warnings) {
        try {
            String url = WIKIVOYAGE_API + "?action=query&list=geosearch"
                    + "&gscoord=" + enc(lat + "|" + lng)
                    + "&gsradius=" + Math.min(10000, radius)
                    + "&gslimit=50&format=json&origin=*";
            JsonNode root = getJson(url).path("query").path("geosearch");
            int count = 0;
            for (JsonNode page : root) {
                String title = page.path("title").asText("").trim();
                if (title.isEmpty()) continue;
                String pageId = page.path("pageid").asText("");
                double[] stored = CoordConverter.wgs84ToGcj02(
                        page.path("lat").asDouble(), page.path("lon").asDouble());
                upsert("wikivoyage", "voyage_" + pageId, title, "景点", city, "",
                        "", stored[0], stored[1],
                        "Wikivoyage 旅行条目",
                        "https://zh.wikivoyage.org/?curid=" + pageId,
                        "CC BY-SA 4.0", "Wikivoyage contributors", page.toString());
                count++;
            }
            return count;
        } catch (Exception e) {
            warnings.add("Wikivoyage 暂不可用：" + e.getMessage());
            return 0;
        }
    }

    private int syncWikidata(String city, double lat, double lng,
                             int radius, List<String> warnings) {
        try {
            double km = Math.max(0.5, Math.min(10d, radius / 1000d));
            String sparql = "SELECT ?item ?itemLabel ?coord ?description WHERE {"
                    + " SERVICE wikibase:around { ?item wdt:P625 ?coord."
                    + " bd:serviceParam wikibase:center \"Point(" + lng + " " + lat + ")\"^^geo:wktLiteral;"
                    + " bd:serviceParam wikibase:radius \"" + km + "\". }"
                    + " OPTIONAL { ?item schema:description ?description."
                    + " FILTER(lang(?description) = \"zh\") }"
                    + " SERVICE wikibase:label { bd:serviceParam wikibase:language \"zh,en\". }"
                    + "} LIMIT 120";
            String url = WIKIDATA_SPARQL + "?format=json&query=" + enc(sparql);
            JsonNode bindings = getJson(url).path("results").path("bindings");
            int count = 0;
            for (JsonNode row : bindings) {
                String entity = row.path("item").path("value").asText("");
                String name = row.path("itemLabel").path("value").asText("").trim();
                String coord = row.path("coord").path("value").asText("");
                double[] point = parseWktPoint(coord);
                if (entity.isEmpty() || name.isEmpty() || point == null) continue;
                String id = entity.substring(entity.lastIndexOf('/') + 1);
                double[] stored = CoordConverter.wgs84ToGcj02(point[1], point[0]);
                upsert("wikidata", "wd_" + id, name, "景点", city, "",
                        "", stored[0], stored[1],
                        row.path("description").path("value").asText(""),
                        entity, "CC0-1.0", "Wikidata contributors", row.toString());
                count++;
            }
            return count;
        } catch (Exception e) {
            warnings.add("Wikidata 暂不可用：" + e.getMessage());
            return 0;
        }
    }

    private int importCommercial(
            List<PlanCommercialPoiService.ProviderPoi> providerItems) throws Exception {
        int count = 0;
        for (PlanCommercialPoiService.ProviderPoi item : providerItems) {
            upsert(item.source, item.sourceId, item.name, item.category, item.city,
                    item.region, item.address, item.lat, item.lng, item.description,
                    item.url, item.license, item.attribution, item.tagsJson);
            count++;
        }
        return count;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "LTing/2.0 (external travel POI supplement)")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    private List<Map<String, Object>> queryStored(String source, String city,
                                                  double lat, double lng,
                                                  int radius, int limit) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean around = lat != 0 && lng != 0 && radius > 0;
        boolean wiki = "wiki".equalsIgnoreCase(source);
        boolean commercial = "commercial".equalsIgnoreCase(source);
        String sourceClause = wiki
                ? "source in ('wikivoyage','wikidata')"
                : (commercial ? "source in ('baidu','tencent')" : "source = ?");
        String sql = "select * from external_poi where " + sourceClause
                + (blank(city) ? "" : " and (city = ? or city is null)")
                + " order by id desc limit " + Math.max(1, Math.min(limit, 1000));
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!wiki && !commercial) ps.setString(1, source);
            if (!blank(city)) ps.setString(wiki || commercial ? 1 : 2, city);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double itemLat = rs.getDouble("lat");
                    double itemLng = rs.getDouble("lng");
                    int distance = around
                            ? (int) Math.round(haversine(lat, lng, itemLat, itemLng)) : 0;
                    if (around && distance > radius) continue;
                    items.add(storedItem(rs, distance));
                }
            }
        }
        items.sort((a, b) -> Integer.compare(
                ((Number) a.getOrDefault("distanceMeters", Integer.MAX_VALUE)).intValue(),
                ((Number) b.getOrDefault("distanceMeters", Integer.MAX_VALUE)).intValue()));
        return items;
    }

    private Map<String, Object> storedItem(ResultSet rs, int distance) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        String source = rs.getString("source");
        item.put("poiId", source + ":" + rs.getString("source_id"));
        item.put("name", rs.getString("name"));
        item.put("category", firstNonBlank(rs.getString("category"), "景点"));
        item.put("city", rs.getString("city"));
        item.put("region", rs.getString("region"));
        item.put("address", rs.getString("address"));
        item.put("lat", rs.getDouble("lat"));
        item.put("lng", rs.getDouble("lng"));
        item.put("coordinateSystem", "GCJ-02");
        item.put("distanceMeters", distance);
        item.put("description", rs.getString("description"));
        item.put("source", sourceLabel(source));
        item.put("sourceKey", source);
        item.put("sourceUrl", rs.getString("url"));
        item.put("license", rs.getString("license"));
        item.put("attribution", rs.getString("attribution"));
        JsonNode meta = readTags(rs.getString("tags_json"));
        String rating = meta.path("rating").asText("");
        String cost = meta.path("cost").asText("");
        String openTime = meta.path("openTime").asText("");
        String photo = meta.path("photo").asText("");
        int confidence = meta.path("confidence").asInt(
                "baidu".equals(source) || "tencent".equals(source) ? 80 : 65);
        item.put("rating", rating);
        item.put("cost", cost);
        item.put("openTime", openTime);
        item.put("photo", photo);
        item.put("confidence", confidence);
        item.put("sourceTier", meta.path("sourceTier").asText(
                "baidu".equals(source) || "tencent".equals(source) ? "T2" : "T3"));
        item.put("durationMin", "美食".equals(item.get("category")) ? 75 : 120);
        List<String> evidence = new ArrayList<>();
        evidence.add(sourceLabel(source));
        evidence.add("来源记录：" + rs.getString("source_id"));
        evidence.add("授权：" + firstNonBlank(rs.getString("license"), "以来源页面为准"));
        evidence.add(firstNonBlank(rs.getString("attribution"), sourceLabel(source)));
        if (!rating.isEmpty()) evidence.add("平台评分：" + rating);
        if (!cost.isEmpty()) evidence.add("参考人均：" + cost);
        if (!openTime.isEmpty()) evidence.add("营业时间：" + openTime);
        item.put("evidence", evidence);
        item.put("external", true);
        return item;
    }

    private JsonNode readTags(String tagsJson) {
        if (blank(tagsJson)) return JSON.createObjectNode();
        try {
            JsonNode node = JSON.readTree(tagsJson);
            return node != null && node.isObject() ? node : JSON.createObjectNode();
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private void upsert(String source, String sourceId, String name, String category,
                        String city, String region, String address, double lat, double lng,
                        String description, String url, String license,
                        String attribution, String tagsJson) throws Exception {
        String sql = "insert into external_poi(source, source_id, name, category, city, region,"
                + " address, lat, lng, description, url, license, attribution, tags_json, updated_time)"
                + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " on duplicate key update name = ?, category = ?, city = ?, region = ?,"
                + " address = ?, lat = ?, lng = ?, description = ?, url = ?, license = ?,"
                + " attribution = ?, tags_json = ?, updated_time = ?";
        TimestampParts now = timestampNow();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, trim(sourceId, 120));
            ps.setString(3, trim(name, 180));
            ps.setString(4, trim(category, 40));
            ps.setString(5, trim(city, 100));
            ps.setString(6, trim(region, 120));
            ps.setString(7, trim(address, 255));
            ps.setDouble(8, lat);
            ps.setDouble(9, lng);
            ps.setString(10, trim(description, 4000));
            ps.setString(11, trim(url, 500));
            ps.setString(12, trim(license, 120));
            ps.setString(13, trim(attribution, 255));
            ps.setString(14, trim(tagsJson, 20000));
            ps.setString(15, now.value);
            ps.setString(16, trim(name, 180));
            ps.setString(17, trim(category, 40));
            ps.setString(18, trim(city, 100));
            ps.setString(19, trim(region, 120));
            ps.setString(20, trim(address, 255));
            ps.setDouble(21, lat);
            ps.setDouble(22, lng);
            ps.setString(23, trim(description, 4000));
            ps.setString(24, trim(url, 500));
            ps.setString(25, trim(license, 120));
            ps.setString(26, trim(attribution, 255));
            ps.setString(27, trim(tagsJson, 20000));
            ps.setString(28, now.value);
            ps.executeUpdate();
        }
    }

    private List<Map<String, Object>> parseCsv(String text) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length < 2) return rows;
        List<String> headers = parseCsvLine(lines[0]);
        for (int i = 1; i < lines.length && rows.size() < MAX_IMPORT_ROWS; i++) {
            if (lines[i].trim().isEmpty()) continue;
            List<String> values = parseCsvLine(lines[i]);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                row.put(normalizeHeader(headers.get(c)), c < values.size() ? values.get(c).trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private List<Map<String, Object>> parseGeoJson(String text) throws Exception {
        JsonNode root = JSON.readTree(text);
        JsonNode features = root.path("features");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!features.isArray()) return rows;
        for (JsonNode feature : features) {
            if (rows.size() >= MAX_IMPORT_ROWS) break;
            JsonNode properties = feature.path("properties");
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            Map<String, Object> row = new LinkedHashMap<>();
            copyGeoProperty(row, properties, "name", "name", "名称", "title");
            copyGeoProperty(row, properties, "category", "category", "type", "类型", "类别");
            copyGeoProperty(row, properties, "city", "city", "城市");
            copyGeoProperty(row, properties, "region", "district", "region", "区县", "区域");
            copyGeoProperty(row, properties, "address", "address", "地址");
            copyGeoProperty(row, properties, "description", "description", "简介", "介绍");
            copyGeoProperty(row, properties, "url", "url", "website", "官网");
            if (coordinates.isArray() && coordinates.size() >= 2) {
                row.put("lng", coordinates.get(0).asDouble());
                row.put("lat", coordinates.get(1).asDouble());
            }
            rows.add(row);
        }
        return rows;
    }

    private void copyGeoProperty(Map<String, Object> row, JsonNode props, String target,
                                 String... aliases) {
        for (String alias : aliases) {
            if (props.has(alias) && !props.path(alias).asText("").trim().isEmpty()) {
                row.put(target, props.path(alias).asText("").trim());
                return;
            }
        }
    }

    private String normalizeHeader(String header) {
        String value = header == null ? "" : header.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "");
        if (Arrays.asList("name", "名称", "title", "标题", "名称name").contains(value)) return "name";
        if (Arrays.asList("lat", "latitude", "纬度").contains(value)) return "lat";
        if (Arrays.asList("lng", "lon", "longitude", "经度").contains(value)) return "lng";
        if (Arrays.asList("address", "地址").contains(value)) return "address";
        if (Arrays.asList("category", "type", "类型", "类别").contains(value)) return "category";
        if (Arrays.asList("city", "城市").contains(value)) return "city";
        if (Arrays.asList("region", "district", "区域", "区县").contains(value)) return "region";
        if (Arrays.asList("description", "简介", "介绍").contains(value)) return "description";
        if (Arrays.asList("url", "website", "官网").contains(value)) return "url";
        return value;
    }

    private String osmCategory(JsonNode tags) {
        String amenity = tags.path("amenity").asText("");
        if (amenity.matches("restaurant|cafe|fast_food|bar")) return "美食";
        if (!tags.path("shop").asText("").isEmpty()) return "商圈";
        return "景点";
    }

    private String joinAddress(JsonNode tags) {
        List<String> parts = new ArrayList<>();
        for (String key : new String[]{"addr:province", "addr:city", "addr:district",
                "addr:street", "addr:housenumber"}) {
            String part = tags.path(key).asText("");
            if (!part.isEmpty()) parts.add(part);
        }
        return String.join("", parts);
    }

    private double[] parseWktPoint(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Point\\(([-0-9.]+) ([-0-9.]+)\\)").matcher(value);
        return matcher.find()
                ? new double[]{Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))}
                : null;
    }

    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String sourceLabel(String source) {
        if ("osm".equals(source)) return "OpenStreetMap";
        if ("wikivoyage".equals(source)) return "Wikivoyage";
        if ("wikidata".equals(source)) return "Wikidata";
        if ("baidu".equals(source)) return "百度地图";
        if ("tencent".equals(source)) return "腾讯位置服务";
        return "地方文旅开放数据";
    }

    private String textValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Double doubleValue(Map<String, Object> row, String key) {
        try {
            String value = textValue(row, key);
            return value.isEmpty() ? null : Double.parseDouble(value.replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeCoordinateSystem(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        if ("gcj02".equals(normalized) || "amap".equals(normalized)
                || "gaode".equals(normalized)) {
            return "gcj02";
        }
        return "wgs84";
    }

    private boolean validCoordinate(double lat, double lng) {
        return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    private String coordinateKey(double lat, double lng) {
        return String.format(Locale.ROOT, "%.6f|%.6f", lat, lng);
    }

    private String sha1(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String safe = value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private TimestampParts timestampNow() {
        return new TimestampParts(LocalDateTime.now().format(TIME_FORMAT));
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }

    private static final class TimestampParts {
        private final String value;

        private TimestampParts(String value) {
            this.value = value;
        }
    }
}
