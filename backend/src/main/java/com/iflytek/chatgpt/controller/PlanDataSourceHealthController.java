package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.chatgpt.agent.WebSearchService;
import com.iflytek.chatgpt.service.AiChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划页的数据源健康面板。
 *
 * <p>只返回配置状态、运行计数、缓存和最近结果，不返回任何 Key。它解释“为什么点位少”：
 * 缺少 Key、查询失败、缓存命中、当前城市与分类数量都在同一份响应里。
 */
@RestController
public class PlanDataSourceHealthController {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${map.js-key:}")
    private String mapJsKey;

    @Value("${map.js-security-code:}")
    private String mapJsSecurityCode;

    private final PlanPoiController poiController;
    private final PlanWeatherService weatherService;
    private final AiChatService aiChatService;
    private final WebSearchService webSearchService;
    private final PlanCommercialPoiService commercialPoiService;

    public PlanDataSourceHealthController(PlanPoiController poiController,
                                          PlanWeatherService weatherService,
                                          AiChatService aiChatService,
                                          WebSearchService webSearchService,
                                          PlanCommercialPoiService commercialPoiService) {
        this.poiController = poiController;
        this.weatherService = weatherService;
        this.aiChatService = aiChatService;
        this.webSearchService = webSearchService;
        this.commercialPoiService = commercialPoiService;
    }

    @PostMapping(value = "/plan/health", produces = "application/json;charset=UTF-8")
    public Map<String, Object> health(@RequestParam(required = false) String city,
                                      @RequestParam(required = false) String categories,
                                      @RequestParam(required = false) String categoryCounts,
                                      @RequestParam(defaultValue = "false") boolean probe,
                                      HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) {
            out.put("status", "error");
            out.put("message", "请先登录");
            return out;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();

        Map<String, Object> database = databaseHealth();
        items.add(database);
        if ("error".equals(database.get("status"))) {
            warnings.add("数据库不可用，行程和偏好无法读取");
        }

        Map<String, Object> amap = poiController.healthSnapshot(city);
        if ("missing_key".equals(amap.get("status"))) {
            warnings.add("高德 POI Key 未配置，候选点位无法查询");
            actions.add(action("配置高德 Web 服务 Key", "settings.html#geo.amap-key"));
        }
        items.add(amap);

        Map<String, Object> model = aiChatService.healthSnapshot();
        if (probe && Boolean.TRUE.equals(model.get("configured"))) {
            long start = System.nanoTime();
            try {
                String reply = aiChatService.chat("只回复 OK");
                long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
                model.put("status", "healthy");
                model.put("message", "模型连通，响应约 " + ms + " ms");
                model.put("latencyMs", ms);
                model.put("probeReply", reply == null ? "" : reply.substring(0, Math.min(20, reply.length())));
            } catch (Exception e) {
                long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
                model.put("status", "error");
                model.put("message", "模型连通检测失败");
                model.put("latencyMs", ms);
                warnings.add("大模型连通检测失败，Agent 修改会不可用");
            }
        } else if ("missing_key".equals(model.get("status"))) {
            warnings.add("大模型配置不完整，Agent 会降级");
            actions.add(action("配置大模型", "settings.html#ai.api-key"));
        }
        items.add(model);

        Map<String, Object> search = webSearchService.healthSnapshot();
        if (!Boolean.TRUE.equals(search.get("primaryConfigured"))) {
            warnings.add("博查未配置，实时检索使用必应网页兜底");
        }
        items.add(search);
        items.add(weatherService.healthSnapshot());

        Map<String, Object> map = new LinkedHashMap<>();
        boolean mapConfigured = configured(mapJsKey) && configured(mapJsSecurityCode);
        map.put("key", "map");
        map.put("label", "内嵌地图");
        map.put("configured", mapConfigured);
        map.put("status", mapConfigured ? "configured" : "missing_key");
        map.put("message", mapConfigured
                ? "高德 JS API Key 与安全密钥已配置"
                : "地图 Key 或安全密钥缺失，点位列表仍可使用");
        if (!mapConfigured) {
            warnings.add("内嵌地图未配置完整");
            actions.add(action("配置地图 JS Key", "settings.html#map.js-key"));
        }
        items.add(map);

        Map<String, Object> ugc = ugcHealth();
        items.add(ugc);
        items.add(externalPoiHealth());
        items.addAll(commercialPoiService.healthItems());

        String overall = overall(database, amap, model, map, weatherService.healthSnapshot());
        out.put("status", "success");
        out.put("overall", overall);
        out.put("summary", summary(overall, warnings));
        out.put("generatedAt", LocalDateTime.now().toString());
        out.put("probe", probe);
        out.put("items", items);
        out.put("warnings", warnings);
        out.put("actions", actions);
        out.put("queryContext", queryContext(city, categories, categoryCounts));
        return out;
    }

    private Map<String, Object> databaseHealth() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", "database");
        item.put("label", "本地数据库");
        long start = System.nanoTime();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("select 1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            item.put("configured", true);
            item.put("status", "healthy");
            item.put("latencyMs", Duration.ofNanos(System.nanoTime() - start).toMillis());
            item.put("message", "连接正常");
        } catch (Exception e) {
            item.put("configured", true);
            item.put("status", "error");
            item.put("latencyMs", Duration.ofNanos(System.nanoTime() - start).toMillis());
            item.put("message", "数据库连接失败");
        }
        return item;
    }

    private Map<String, Object> ugcHealth() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", "ugc");
        item.put("label", "自建体验");
        try (Connection conn = MySQLUtil.getConnection()) {
            item.put("configured", true);
            item.put("status", "healthy");
            item.put("reviewCount", count(conn, "poi_review"));
            item.put("checkinCount", count(conn, "travel_log"));
            item.put("message", "打卡与评价会叠加到候选证据");
        } catch (Exception e) {
            item.put("configured", false);
            item.put("status", "error");
            item.put("message", "UGC 统计读取失败");
        }
        return item;
    }

    private Map<String, Object> externalPoiHealth() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", "external_poi");
        item.put("label", "外部补充");
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select source, count(*) from external_poi group by source");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) counts.put(rs.getString(1), rs.getInt(2));
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            item.put("configured", true);
            item.put("status", "healthy");
            item.put("total", total);
            item.put("counts", counts);
            item.put("message", total == 0
                    ? "尚未同步 OSM、维基或导入地方文旅数据"
                    : "外部 POI 已缓存 " + total + " 个");
        } catch (Exception e) {
            item.put("configured", false);
            item.put("status", "error");
            item.put("message", "外部 POI 统计读取失败");
        }
        return item;
    }

    private int count(Connection conn, String table) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("select count(*) from " + table);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private Map<String, Object> queryContext(String city, String categories,
                                             String categoryCounts) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("city", city == null ? "" : city.trim());
        List<String> categoryList = new ArrayList<>();
        if (categories != null) {
            Arrays.stream(categories.split(","))
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .forEach(categoryList::add);
        }
        context.put("categories", categoryList);
        try {
            JsonNode counts = categoryCounts == null || categoryCounts.trim().isEmpty()
                    ? null : JSON.readTree(categoryCounts);
            context.put("categoryCounts", counts == null
                    ? new LinkedHashMap<>()
                    : JSON.convertValue(counts, Map.class));
        } catch (Exception e) {
            context.put("categoryCounts", new LinkedHashMap<>());
        }
        return context;
    }

    private String overall(Map<String, Object> database,
                           Map<String, Object> amap,
                           Map<String, Object> model,
                           Map<String, Object> map,
                           Map<String, Object> weather) {
        if (!"healthy".equals(database.get("status"))) return "unavailable";
        if (!Boolean.TRUE.equals(amap.get("configured"))
                || !Boolean.TRUE.equals(model.get("configured"))) return "degraded";
        if (!Boolean.TRUE.equals(map.get("configured"))
                || "disabled".equals(weather.get("status"))) return "partial";
        return "healthy";
    }

    private String summary(String overall, List<String> warnings) {
        if ("unavailable".equals(overall)) return "核心依赖不可用，规划无法保存";
        if ("degraded".equals(overall)) return "核心依赖有缺失，部分能力会降级";
        if ("partial".equals(overall)) return "核心规划可用，地图或天气处于降级状态";
        return warnings.isEmpty() ? "所有数据源配置正常" : "配置正常，但仍有可选降级项";
    }

    private Map<String, Object> action(String label, String href) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("label", label);
        action.put("href", href);
        return action;
    }

    private boolean configured(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
