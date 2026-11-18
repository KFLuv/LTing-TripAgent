package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.chatgpt.agent.NavigationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选空间聚类、住宿通勤评分和路线矩阵。
 */
@RestController
public class PlanSpatialController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] SLOTS = {"morning", "afternoon", "evening"};

    private final NavigationService navigationService;
    private final PlanOpsController planOpsController;

    public PlanSpatialController(NavigationService navigationService,
                                 PlanOpsController planOpsController) {
        this.navigationService = navigationService;
        this.planOpsController = planOpsController;
    }

    @PostMapping(value = "/plan/spatial/clusters", produces = "application/json;charset=UTF-8")
    public Map<String, Object> clusters(@RequestParam int tripId, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        PlanState state = loadPlan(username, tripId);
        if (state == null) return fail(out, "规划不存在或无权访问");
        List<Point> points = candidatePoints(state.root);
        List<Cluster> clusters = cluster(points);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            items.add(clusterMap(clusters.get(i), i));
        }
        out.put("status", "success");
        out.put("items", items);
        out.put("pointCount", points.size());
        return out;
    }

    @PostMapping(value = "/plan/spatial/stay-areas", produces = "application/json;charset=UTF-8")
    public Map<String, Object> stayAreas(@RequestParam int tripId,
                                         @RequestParam(defaultValue = "driving") String travelMode,
                                         HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        PlanState state = loadPlan(username, tripId);
        if (state == null) return fail(out, "规划不存在或无权访问");
        String mode = normalizeMode(travelMode);
        List<Point> points = candidatePoints(state.root);
        List<Cluster> clusters = cluster(points);
        List<Map<String, Object>> areas = new ArrayList<>();
        for (Cluster cluster : clusters) {
            List<Point> sample = cluster.points.size() > 8
                    ? cluster.points.subList(0, 8) : cluster.points;
            double totalMinutes = 0;
            double totalKm = 0;
            int realRoutes = 0;
            int scored = 0;
            for (Point point : sample) {
                Route route = route(cluster.center.lat, cluster.center.lng,
                        point.lat, point.lng, mode, state.city);
                if (route == null) continue;
                totalMinutes += route.minutes;
                totalKm += route.distanceKm;
                if (route.real) realRoutes++;
                scored++;
            }
            if (scored == 0) continue;
            Map<String, Object> area = clusterMap(cluster, areas.size());
            area.put("avgTravelMinutes", round(totalMinutes / scored));
            area.put("avgDistanceKm", round(totalKm / scored));
            area.put("scoredCandidates", scored);
            area.put("source", realRoutes > 0 ? "amap-" + mode : "estimated");
            area.put("travelMode", mode);
            areas.add(area);
        }
        areas.sort(Comparator.comparingDouble(a ->
                ((Number) a.getOrDefault("avgTravelMinutes", Double.MAX_VALUE)).doubleValue()));
        out.put("status", "success");
        out.put("items", areas);
        return out;
    }

    @PostMapping(value = "/plan/routes/matrix", produces = "application/json;charset=UTF-8")
    public Map<String, Object> routeMatrix(@RequestParam int tripId,
                                           @RequestParam(defaultValue = "driving") String travelMode,
                                           HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        PlanState state = loadPlan(username, tripId);
        if (state == null) return fail(out, "规划不存在或无权访问");
        out.put("status", "success");
        out.putAll(routeMatrix(state.root, normalizeMode(travelMode), state.city));
        return out;
    }

    @PostMapping(value = "/plan/routes/refresh", produces = "application/json;charset=UTF-8")
    public Map<String, Object> refreshRoutes(@RequestParam String contextId,
                                             @RequestParam int tripId,
                                             @RequestParam(defaultValue = "driving") String travelMode,
                                             HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        PlanState state = loadPlan(username, tripId);
        if (state == null) return fail(out, "规划不存在或无权访问");
        ArrayNode itinerary = annotatedItinerary(state.root, normalizeMode(travelMode), state.city);
        ObjectNode operation = JSON.createObjectNode();
        operation.put("type", "UPDATE_ROUTE_TIMES");
        operation.put("travelMode", normalizeMode(travelMode));
        operation.put("reason", "刷新逐段路线耗时");
        operation.set("itinerary", itinerary);
        Map<String, Object> preview = planOpsController.previewForUser(
                username, contextId, operation.toString(), "click");
        out.putAll(preview);
        return out;
    }

    private PlanState loadPlan(String username, int tripId) {
        String sql = "select plan_data, destination from trip where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                PlanState state = new PlanState();
                state.root = parseObject(rs.getString("plan_data"));
                state.city = rs.getString("destination");
                return state;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private ObjectNode parseObject(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) return JSON.createObjectNode();
        JsonNode node = JSON.readTree(json);
        return node.isObject() ? (ObjectNode) node : JSON.createObjectNode();
    }

    private List<Point> candidatePoints(ObjectNode root) {
        List<Point> points = new ArrayList<>();
        JsonNode chosen = root.path("chosen");
        if (chosen.isArray()) {
            for (JsonNode item : chosen) addPoint(points, item);
        }
        return points;
    }

    private void addPoint(List<Point> points, JsonNode item) {
        if (!item.path("lat").isNumber() || !item.path("lng").isNumber()) return;
        Point point = new Point();
        point.name = item.path("name").asText("未命名地点");
        point.address = item.path("address").asText("");
        point.lat = item.path("lat").asDouble();
        point.lng = item.path("lng").asDouble();
        points.add(point);
    }

    private List<Cluster> cluster(List<Point> points) {
        List<Cluster> result = new ArrayList<>();
        if (points.isEmpty()) return result;
        int k = Math.min(6, Math.max(points.size() >= 2 ? 2 : 1,
                (int) Math.ceil(points.size() / 5.0)));
        List<Point> centers = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            centers.add(points.get(Math.min(points.size() - 1,
                    (int) Math.floor(i * points.size() / (double) k))).copy());
        }
        int[] assignment = new int[points.size()];
        for (int iteration = 0; iteration < 8; iteration++) {
            for (int i = 0; i < points.size(); i++) {
                int best = 0;
                double bestDistance = Double.MAX_VALUE;
                for (int c = 0; c < centers.size(); c++) {
                    double d = distance(points.get(i), centers.get(c));
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = c;
                    }
                }
                assignment[i] = best;
            }
            double[] lat = new double[k];
            double[] lng = new double[k];
            int[] count = new int[k];
            for (int i = 0; i < points.size(); i++) {
                int c = assignment[i];
                lat[c] += points.get(i).lat;
                lng[c] += points.get(i).lng;
                count[c]++;
            }
            for (int c = 0; c < k; c++) {
                if (count[c] > 0) {
                    centers.get(c).lat = lat[c] / count[c];
                    centers.get(c).lng = lng[c] / count[c];
                }
            }
        }
        for (int c = 0; c < k; c++) {
            Cluster cluster = new Cluster();
            cluster.center = centers.get(c);
            for (int i = 0; i < points.size(); i++) {
                if (assignment[i] == c) cluster.points.add(points.get(i));
            }
            if (!cluster.points.isEmpty()) result.add(cluster);
        }
        return result;
    }

    private Map<String, Object> clusterMap(Cluster cluster, int index) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "cluster_" + (index + 1));
        item.put("name", commonRegion(cluster.points, index + 1));
        item.put("lat", cluster.center.lat);
        item.put("lng", cluster.center.lng);
        item.put("count", cluster.points.size());
        List<String> names = new ArrayList<>();
        for (Point point : cluster.points) {
            if (names.size() >= 8) break;
            names.add(point.name);
        }
        item.put("candidates", names);
        return item;
    }

    private String commonRegion(List<Point> points, int fallback) {
        for (Point point : points) {
            String address = point.address == null ? "" : point.address;
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("([\\u4e00-\\u9fa5]{2,8}(?:区|县|镇|街道))")
                    .matcher(address);
            if (matcher.find()) return matcher.group(1);
        }
        return "建议区域 " + fallback;
    }

    private Map<String, Object> routeMatrix(ObjectNode root, String mode, String city) {
        List<Map<String, Object>> days = new ArrayList<>();
        int totalMinutes = 0;
        double totalKm = 0;
        int fallback = 0;
        for (int d = 0; d < root.withArray("itinerary").size(); d++) {
            JsonNode day = root.withArray("itinerary").get(d);
            List<Map<String, Object>> segments = new ArrayList<>();
            int dayMinutes = 0;
            double dayKm = 0;
            for (String slot : SLOTS) {
                JsonNode previous = null;
                for (JsonNode item : day.path(slot)) {
                    if (previous != null) {
                        Route route = route(previous.path("lat").asDouble(),
                                previous.path("lng").asDouble(),
                                item.path("lat").asDouble(), item.path("lng").asDouble(),
                                mode, city);
                        if (route != null) {
                            Map<String, Object> segment = new LinkedHashMap<>();
                            segment.put("from", previous.path("name").asText(""));
                            segment.put("to", item.path("name").asText(""));
                            segment.put("minutes", route.minutes);
                            segment.put("distanceKm", round(route.distanceKm));
                            segment.put("source", route.real ? "amap" : "estimated");
                            segments.add(segment);
                            dayMinutes += route.minutes;
                            dayKm += route.distanceKm;
                            if (!route.real) fallback++;
                        }
                    }
                    previous = item;
                }
            }
            Map<String, Object> dayResult = new LinkedHashMap<>();
            dayResult.put("day", d + 1);
            dayResult.put("travelMinutes", dayMinutes);
            dayResult.put("distanceKm", round(dayKm));
            dayResult.put("segments", segments);
            days.add(dayResult);
            totalMinutes += dayMinutes;
            totalKm += dayKm;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("travelMode", mode);
        result.put("days", days);
        result.put("totalTravelMinutes", totalMinutes);
        result.put("totalDistanceKm", round(totalKm));
        result.put("fallbackSegments", fallback);
        return result;
    }

    private ArrayNode annotatedItinerary(ObjectNode root, String mode, String city) {
        ArrayNode itinerary = root.withArray("itinerary").deepCopy();
        for (JsonNode day : itinerary) {
            for (String slot : SLOTS) {
                JsonNode previous = null;
                for (JsonNode node : day.path(slot)) {
                    if (node instanceof ObjectNode) {
                        ObjectNode object = (ObjectNode) node;
                        if (previous == null) {
                            object.put("routeFromPreviousMinutes", 0);
                            object.put("routeDistanceKm", 0);
                            object.put("routeSource", "start");
                        } else {
                            Route route = route(previous.path("lat").asDouble(),
                                    previous.path("lng").asDouble(),
                                    node.path("lat").asDouble(), node.path("lng").asDouble(),
                                    mode, city);
                            object.put("routeFromPreviousMinutes", route == null ? 0 : route.minutes);
                            object.put("routeDistanceKm", route == null ? 0 : round(route.distanceKm));
                            object.put("routeSource", route == null ? "unknown"
                                    : (route.real ? "amap" : "estimated"));
                        }
                    }
                    previous = node;
                }
            }
        }
        return itinerary;
    }

    private Route route(double fromLat, double fromLng, double toLat, double toLng,
                        String mode, String city) {
        if (!Double.isFinite(fromLat) || !Double.isFinite(toLat)) return null;
        Map<String, Object> result = "transit".equals(mode)
                ? navigationService.planTransit(fromLat, fromLng, toLat, toLng, city, true)
                : navigationService.planRoute(fromLat, fromLng, toLat, toLng, mode, true);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return new Route(
                    Math.max(1, (int) Math.ceil(number(result.get("durationSeconds")) / 60.0)),
                    number(result.get("distanceMeters")) / 1000.0, true);
        }
        double km = haversine(fromLat, fromLng, toLat, toLng) * 1.28 / 1000.0;
        return new Route(Math.max(1, (int) Math.ceil(km * 4)), km, false);
    }

    private double distance(Point a, Point b) {
        return haversine(a.lat, a.lng, b.lat, b.lng);
    }

    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private double round(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private String normalizeMode(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase();
        return "walking".equals(mode) || "bicycling".equals(mode) || "transit".equals(mode)
                ? mode : "driving";
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }

    private static final class PlanState {
        private ObjectNode root;
        private String city;
    }

    private static final class Point {
        private String name;
        private String address;
        private double lat;
        private double lng;

        private Point copy() {
            Point point = new Point();
            point.name = name;
            point.address = address;
            point.lat = lat;
            point.lng = lng;
            return point;
        }
    }

    private static final class Cluster {
        private Point center;
        private final List<Point> points = new ArrayList<>();
    }

    private static final class Route {
        private final int minutes;
        private final double distanceKm;
        private final boolean real;

        private Route(int minutes, double distanceKm, boolean real) {
            this.minutes = minutes;
            this.distanceKm = distanceKm;
            this.real = real;
        }
    }
}
