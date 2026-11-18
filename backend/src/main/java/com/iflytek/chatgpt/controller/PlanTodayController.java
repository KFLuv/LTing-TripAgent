package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出行中的 Today Mode。
 *
 * <p>Today 只读取 trip.plan_data 这一份 canonical 行程，不另存副本。临时重排必须由页面
 * 继续调用 PlanOps Context → Preview → Apply；这里只负责当天视图、打卡、预算、离线卡和
 * 行程结束后的偏好建议。偏好建议必须由用户显式确认后才会写入 user_preference。
 */
@RestController
public class PlanTodayController {

    private static final Logger log = LoggerFactory.getLogger(PlanTodayController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> SLOTS = Arrays.asList("morning", "afternoon", "evening");
    private static final List<String> RATINGS = Arrays.asList("like", "neutral", "avoid");
    private static final List<String> BUDGET_CATEGORIES =
            Arrays.asList("transport", "lodging", "food", "ticket", "local");

    @PostMapping(value = "/plan/today/get", produces = "application/json;charset=UTF-8")
    public Map<String, Object> get(@RequestParam(defaultValue = "0") int tripId,
                                   @RequestParam(required = false) String date,
                                   @RequestParam(required = false) String now,
                                   HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        return getForUser(username, tripId, date, now);
    }

    public Map<String, Object> getForUser(String username, int tripId,
                                          String dateText, String nowText) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate referenceDate = parseDate(dateText, LocalDate.now());
        LocalTime referenceTime = parseTime(nowText, LocalTime.now());
        try (Connection conn = MySQLUtil.getConnection()) {
            TripView trip = tripId > 0
                    ? loadAccessibleTrip(conn, username, tripId)
                    : findTodayTrip(conn, username, referenceDate);
            if (trip == null) {
                return fail(out, tripId > 0
                        ? "行程不存在或无权访问"
                        : "今天没有可打开的行程");
            }
            if (trip.planData == null || trip.planData.trim().isEmpty()) {
                return fail(out, "该行程还没有结构化规划");
            }

            JsonNode root = JSON.readTree(trip.planData);
            JsonNode itinerary = root.path("itinerary");
            if (!itinerary.isArray() || itinerary.isEmpty()) {
                return fail(out, "该行程还没有每日安排");
            }
            int dayIndex = resolveDayIndex(trip, itinerary, referenceDate);
            if (dayIndex < 0 || dayIndex >= itinerary.size()) {
                return fail(out, "当天没有可显示的行程");
            }
            JsonNode day = itinerary.get(dayIndex);
            LocalDate dayDate = parseDate(day.path("date").asText(""), referenceDate.plusDays(dayIndex));
            LocalDate tripStart = parseDate(trip.startDate,
                    parseDate(itinerary.get(0).path("date").asText(""), dayDate));
            LocalDate tripEnd = parseDate(trip.endDate,
                    parseDate(itinerary.get(itinerary.size() - 1).path("date").asText(""), dayDate));
            String phase = referenceDate.isBefore(tripStart) ? "before"
                    : (referenceDate.isAfter(tripEnd) ? "after" : "during");

            List<Map<String, Object>> stops = buildStops(trip.id, day);
            int nowMinutes = referenceTime.getHour() * 60 + referenceTime.getMinute();
            markStopStates(stops, referenceDate, dayDate, nowMinutes, phase);
            attachCheckins(conn, username, trip.id, stops);

            Map<String, Object> currentStop = firstWithState(stops, "current");
            Map<String, Object> nextStop = firstWithStateAfter(stops, currentStop, "upcoming");
            if (nextStop == null && currentStop == null) nextStop = firstWithState(stops, "upcoming");
            Map<String, Object> focusStop = currentStop != null ? currentStop : nextStop;
            if (focusStop == null && !stops.isEmpty()) focusStop = stops.get(stops.size() - 1);

            Map<String, Object> budget = budgetView(conn, trip, root);
            Map<String, Object> preferenceProposals =
                    preferenceProposals(conn, username, trip, root, referenceDate, tripEnd);

            out.put("status", "success");
            out.put("tripId", trip.id);
            out.put("title", trip.title);
            out.put("destination", trip.destination);
            out.put("role", username.equals(trip.owner) ? "owner" : "collaborator");
            out.put("planRevision", trip.planRevision);
            out.put("date", dayDate.toString());
            out.put("referenceDate", referenceDate.toString());
            out.put("referenceTime", referenceTime.format(DateTimeFormatter.ofPattern("HH:mm")));
            out.put("phase", phase);
            out.put("dayIndex", dayIndex);
            out.put("dayOfTrip", dayIndex + 1);
            out.put("dayCount", itinerary.size());
            out.put("stops", stops);
            out.put("currentStop", currentStop);
            out.put("nextStop", nextStop);
            out.put("focusStop", focusStop);
            out.put("budget", budget);
            out.put("preferenceProposals", preferenceProposals);
            out.put("offlineReady", true);
            out.put("generatedAt", java.time.LocalDateTime.now().format(TIME_FORMAT));
            return out;
        } catch (Exception e) {
            log.error("读取 Today Mode 失败", e);
            return fail(out, "今日行程读取失败");
        }
    }

    @PostMapping(value = "/plan/today/checkin", produces = "application/json;charset=UTF-8")
    public Map<String, Object> checkin(@RequestParam int tripId,
                                       @RequestParam String nodeKey,
                                       @RequestParam(defaultValue = "neutral") String rating,
                                       @RequestParam(required = false) String note,
                                       @RequestParam(required = false) Integer durationMin,
                                       @RequestParam(defaultValue = "private") String visibility,
                                       HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        String safeRating = RATINGS.contains(rating) ? rating : "neutral";
        try (Connection conn = MySQLUtil.getConnection()) {
            TripView trip = loadAccessibleTrip(conn, username, tripId);
            if (trip == null) return fail(out, "行程不存在或无权访问");
            JsonNode root = JSON.readTree(trip.planData);
            JsonNode found = findNode(root.path("itinerary"), nodeKey);
            if (found == null) return fail(out, "行程节点不存在或已更新");

            String nodeRef = nodeRef(tripId, nodeKey);
            int id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "insert into travel_log(username, trip_id, place, lat, lng, rating, note,"
                            + " duration_min, photos, visibility, poi_id, poi_name, images,"
                            + " log_time, create_time)"
                            + " values(?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?, null, now(), now())",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setInt(2, tripId);
                ps.setString(3, trim(found.path("name").asText("未命名地点"), 120));
                setDoubleOrNull(ps, 4, found.path("lat").isNumber() ? found.path("lat").asDouble() : null);
                setDoubleOrNull(ps, 5, found.path("lng").isNumber() ? found.path("lng").asDouble() : null);
                ps.setString(6, safeRating);
                ps.setString(7, trim(note, 2000));
                if (durationMin == null || durationMin <= 0) {
                    ps.setInt(8, found.path("durationMin").asInt(90));
                } else {
                    ps.setInt(8, durationMin);
                }
                ps.setString(9, normalizeVisibility(visibility));
                ps.setString(10, nodeRef);
                ps.setString(11, trim(found.path("name").asText("未命名地点"), 120));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getInt(1) : 0;
                }
            }
            logActivity(conn, tripId, username, "TODAY_CHECKIN",
                    found.path("name").asText("未命名地点") + " → " + safeRating);
            out.put("status", "success");
            out.put("id", id);
            out.put("nodeKey", nodeKey);
            out.put("nodeRef", nodeRef);
            out.put("message", "打卡已关联到今日行程");
            return out;
        } catch (Exception e) {
            log.error("Today 打卡保存失败", e);
            return fail(out, "打卡保存失败");
        }
    }

    @PostMapping(value = "/plan/today/offline-card", produces = "application/json;charset=UTF-8")
    public Map<String, Object> offlineCard(@RequestParam(defaultValue = "0") int tripId,
                                           @RequestParam(required = false) String date,
                                           @RequestParam(required = false) String now,
                                           HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        Map<String, Object> today = getForUser(username, tripId, date, now);
        if (!"success".equals(today.get("status"))) return today;
        String html = buildOfflineHtml(today);
        String text = buildOfflineText(today);
        out.put("status", "success");
        out.put("tripId", today.get("tripId"));
        out.put("date", today.get("date"));
        out.put("generatedAt", today.get("generatedAt"));
        out.put("html", html);
        out.put("text", text);
        return out;
    }

    @PostMapping(value = "/plan/today/preference/apply", produces = "application/json;charset=UTF-8")
    public Map<String, Object> applyPreference(@RequestParam int tripId,
                                               @RequestParam String tag,
                                               @RequestParam String value,
                                               @RequestParam(required = false) String evidence,
                                               @RequestParam(defaultValue = "false") boolean confirmed,
                                               HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        if (!confirmed) return fail(out, "偏好建议需要用户明确确认后才能保存");
        String safeTag = trim(tag, 64);
        String safeValue = trim(value, 500);
        if (safeTag == null || safeTag.isEmpty() || safeValue == null || safeValue.isEmpty()) {
            return fail(out, "偏好标签和内容不能为空");
        }
        try (Connection conn = MySQLUtil.getConnection()) {
            TripView trip = loadAccessibleTrip(conn, username, tripId);
            if (trip == null) return fail(out, "行程不存在或无权访问");
            try (PreparedStatement ps = conn.prepareStatement(
                    "insert into user_preference(username, tag, `value`, update_time)"
                            + " values(?, ?, ?, now())"
                            + " on duplicate key update `value` = ?, update_time = now()")) {
                ps.setString(1, username);
                ps.setString(2, safeTag);
                ps.setString(3, safeValue);
                ps.setString(4, safeValue);
                ps.executeUpdate();
            }
            logActivity(conn, tripId, username, "PREFERENCE_ACCEPT",
                    safeTag + " → " + trim(evidence, 500));
            out.put("status", "success");
            out.put("tag", safeTag);
            out.put("value", safeValue);
            out.put("message", "已写入你的长期偏好");
            return out;
        } catch (Exception e) {
            log.error("保存偏好建议失败", e);
            return fail(out, "偏好保存失败");
        }
    }

    private TripView findTodayTrip(Connection conn, String username,
                                   LocalDate referenceDate) throws Exception {
        String sql = "select t.id, t.username as owner, t.title, t.destination, t.status,"
                + " t.start_date, t.end_date, t.budget, t.people, t.pace, t.plan_data,"
                + " t.plan_revision, t.update_time"
                + " from trip t where t.plan_data is not null and trim(t.plan_data) <> ''"
                + " and (t.username = ? or exists (select 1 from trip_collaborator c"
                + " where c.trip_id = t.id and c.username = ? and c.status = 'active'))"
                + " order by t.update_time desc limit 100";
        List<TripView> trips = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) trips.add(readTrip(rs));
            }
        }
        TripView best = null;
        int bestScore = Integer.MAX_VALUE;
        for (TripView trip : trips) {
            LocalDate start = parseDate(trip.startDate, null);
            LocalDate end = parseDate(trip.endDate, start);
            if (start == null && trip.planData != null && !trip.planData.trim().isEmpty()) {
                try {
                    JsonNode itinerary = JSON.readTree(trip.planData).path("itinerary");
                    if (itinerary.isArray() && !itinerary.isEmpty()) {
                        start = parseDate(itinerary.get(0).path("date").asText(""), null);
                        end = parseDate(itinerary.get(itinerary.size() - 1)
                                .path("date").asText(""), start);
                    }
                } catch (Exception ignored) {
                    // 单条草稿日期损坏时跳过，不影响其它行程。
                }
            }
            if (start == null) continue;
            long distance;
            int score;
            if (!referenceDate.isBefore(start) && !referenceDate.isAfter(end)) {
                score = 0;
                distance = 0;
            } else if (referenceDate.isBefore(start)) {
                score = 100;
                distance = referenceDate.until(start).getDays();
            } else {
                score = 1000;
                distance = end.until(referenceDate).getDays();
            }
            int total = score + (int) Math.min(999, Math.max(0, distance));
            if (total < bestScore) {
                bestScore = total;
                best = trip;
            }
        }
        return best;
    }

    private TripView loadAccessibleTrip(Connection conn, String username,
                                        int tripId) throws Exception {
        String sql = "select t.id, t.username as owner, t.title, t.destination, t.status,"
                + " t.start_date, t.end_date, t.budget, t.people, t.pace, t.plan_data,"
                + " t.plan_revision, t.update_time from trip t where t.id = ?"
                + " and (t.username = ? or exists (select 1 from trip_collaborator c"
                + " where c.trip_id = t.id and c.username = ? and c.status = 'active'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readTrip(rs) : null;
            }
        }
    }

    private TripView readTrip(ResultSet rs) throws Exception {
        TripView trip = new TripView();
        trip.id = rs.getInt("id");
        trip.owner = rs.getString("owner");
        trip.title = rs.getString("title");
        trip.destination = rs.getString("destination");
        trip.status = rs.getString("status");
        trip.startDate = rs.getString("start_date");
        trip.endDate = rs.getString("end_date");
        trip.budget = rs.getString("budget");
        trip.people = rs.getString("people");
        trip.pace = rs.getString("pace");
        trip.planData = rs.getString("plan_data");
        trip.planRevision = rs.getInt("plan_revision");
        trip.updateTime = rs.getTimestamp("update_time");
        return trip;
    }

    private int resolveDayIndex(TripView trip, JsonNode itinerary, LocalDate date) {
        for (int i = 0; i < itinerary.size(); i++) {
            LocalDate dayDate = parseDate(itinerary.get(i).path("date").asText(""), null);
            if (date.equals(dayDate)) return i;
        }
        LocalDate start = parseDate(trip.startDate,
                parseDate(itinerary.get(0).path("date").asText(""), date));
        if (!date.isBefore(start)) {
            int index = (int) start.until(date).getDays();
            if (index >= 0 && index < itinerary.size()) return index;
        }
        return date.isBefore(start) ? 0 : itinerary.size() - 1;
    }

    private List<Map<String, Object>> buildStops(int tripId, JsonNode day) {
        List<Map<String, Object>> stops = new ArrayList<>();
        int cursor = 0;
        for (String slot : SLOTS) {
            JsonNode items = day.path(slot);
            if (!items.isArray()) continue;
            int slotStart = slotStart(slot);
            for (int i = 0; i < items.size(); i++) {
                JsonNode node = items.get(i);
                int duration = Math.max(15, node.path("durationMin").asInt(90));
                int routeMinutes = Math.max(0, node.path("routeMinutes").asInt(
                        node.path("routeDurationMin").asInt(0)));
                int start = Math.max(cursor, slotStart) + routeMinutes;
                int end = start + duration;
                cursor = end;
                String nodeKey = nodeKey(node);
                Map<String, Object> stop = new LinkedHashMap<>();
                stop.put("nodeKey", nodeKey);
                stop.put("nodeRef", nodeRef(tripId, nodeKey));
                stop.put("name", node.path("name").asText("未命名地点"));
                stop.put("address", node.path("address").asText(""));
                stop.put("category", node.path("category").asText(""));
                stop.put("lat", node.path("lat").isNumber() ? node.path("lat").asDouble() : null);
                stop.put("lng", node.path("lng").isNumber() ? node.path("lng").asDouble() : null);
                stop.put("photo", node.path("photo").asText(""));
                stop.put("slot", slot);
                stop.put("slotLabel", slotLabel(slot));
                stop.put("slotIndex", i);
                stop.put("canMoveUp", i > 0);
                stop.put("canMoveDown", i + 1 < items.size());
                stop.put("durationMin", duration);
                stop.put("routeMinutes", routeMinutes);
                stop.put("routeSource", node.path("routeSource").asText(""));
                stop.put("estimatedStart", clock(start));
                stop.put("estimatedEnd", clock(end));
                stop.put("order", stops.size() + 1);
                stops.add(stop);
            }
        }
        return stops;
    }

    private void markStopStates(List<Map<String, Object>> stops, LocalDate referenceDate,
                                LocalDate dayDate, int nowMinutes, String phase) {
        for (Map<String, Object> stop : stops) {
            int start = parseClock(String.valueOf(stop.get("estimatedStart")));
            int end = parseClock(String.valueOf(stop.get("estimatedEnd")));
            String state;
            if (referenceDate.isBefore(dayDate) || "before".equals(phase)) {
                state = "upcoming";
            } else if (referenceDate.isAfter(dayDate) || "after".equals(phase)) {
                state = "past";
            } else if (nowMinutes < start) {
                state = "upcoming";
            } else if (nowMinutes <= end) {
                state = "current";
            } else {
                state = "past";
            }
            stop.put("state", state);
        }
    }

    private void attachCheckins(Connection conn, String username, int tripId,
                                List<Map<String, Object>> stops) throws Exception {
        Map<String, Map<String, Object>> byRef = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "select id, poi_id, place, rating, note, duration_min, create_time"
                        + " from travel_log where username = ? and trip_id = ? order by id desc")) {
            ps.setString(1, username);
            ps.setInt(2, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ref = rs.getString("poi_id");
                    if (ref == null || byRef.containsKey(ref)) continue;
                    Map<String, Object> checkin = new LinkedHashMap<>();
                    checkin.put("id", rs.getInt("id"));
                    checkin.put("place", rs.getString("place"));
                    checkin.put("rating", rs.getString("rating"));
                    checkin.put("note", rs.getString("note"));
                    checkin.put("durationMin", rs.getObject("duration_min"));
                    checkin.put("createTime", format(rs.getTimestamp("create_time")));
                    byRef.put(ref, checkin);
                }
            }
        }
        for (Map<String, Object> stop : stops) {
            Map<String, Object> checkin = byRef.get(String.valueOf(stop.get("nodeRef")));
            stop.put("checkedIn", checkin != null);
            stop.put("checkin", checkin);
        }
    }

    private Map<String, Object> budgetView(Connection conn, TripView trip,
                                           JsonNode root) throws Exception {
        double allocated = root.path("budgetPlan").path("total").asDouble(0);
        if (allocated <= 0) allocated = parseMoney(trip.budget);
        Map<String, Double> spent = new LinkedHashMap<>();
        for (String category : BUDGET_CATEGORIES) spent.put(category, 0d);
        int entries = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "select category, sum(amount) as amount from budget_entry"
                        + " where trip_id = ? and (username = ? or username in ("
                        + " select username from trip_collaborator where trip_id = ? and status = 'active'))"
                        + " group by category")) {
            ps.setInt(1, trip.id);
            ps.setString(2, trip.owner);
            ps.setInt(3, trip.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String category = normalizeBudgetCategory(rs.getString("category"));
                    double amount = rs.getBigDecimal("amount") == null
                            ? 0 : rs.getBigDecimal("amount").doubleValue();
                    spent.put(category, spent.getOrDefault(category, 0d) + amount);
                    entries++;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "select count(*) from budget_entry where trip_id = ? and (username = ? or username in ("
                        + " select username from trip_collaborator where trip_id = ? and status = 'active'))")) {
            ps.setInt(1, trip.id);
            ps.setString(2, trip.owner);
            ps.setInt(3, trip.id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) entries = rs.getInt(1);
            }
        }
        double totalSpent = spent.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", allocated > 0);
        result.put("allocated", round(allocated));
        result.put("spent", round(totalSpent));
        result.put("remaining", round(allocated - totalSpent));
        result.put("byCategory", spent);
        result.put("entryCount", entries);
        result.put("overBudget", allocated > 0 && totalSpent > allocated);
        return result;
    }

    private Map<String, Object> preferenceProposals(Connection conn, String username,
                                                    TripView trip, JsonNode root,
                                                    LocalDate referenceDate,
                                                    LocalDate tripEnd) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean eligible = referenceDate.isAfter(tripEnd);
        result.put("eligible", eligible);
        List<Map<String, Object>> proposals = new ArrayList<>();
        if (!eligible) {
            result.put("proposals", proposals);
            result.put("message", "行程结束后会根据打卡生成偏好建议，不会自动修改画像");
            return result;
        }

        Map<String, Map<String, Object>> stopByRef = new LinkedHashMap<>();
        for (JsonNode day : root.path("itinerary")) {
            for (String slot : SLOTS) {
                for (JsonNode node : day.path(slot)) {
                    String key = nodeKey(node);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", node.path("name").asText(""));
                    row.put("category", node.path("category").asText(""));
                    row.put("durationMin", node.path("durationMin").asInt(90));
                    stopByRef.put(nodeRef(trip.id, key), row);
                }
            }
        }

        Map<String, Integer> likedCategories = new LinkedHashMap<>();
        List<String> avoided = new ArrayList<>();
        List<Map<String, Object>> durationEvidence = new ArrayList<>();
        int likeCount = 0;
        int avoidCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "select poi_id, place, rating, note, duration_min from travel_log"
                        + " where username = ? and trip_id = ? order by id")) {
            ps.setString(1, username);
            ps.setInt(2, trip.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ref = rs.getString("poi_id");
                    Map<String, Object> stop = stopByRef.get(ref);
                    String place = rs.getString("place");
                    String category = stop == null ? "" : String.valueOf(stop.get("category"));
                    String rating = rs.getString("rating");
                    if ("like".equals(rating)) {
                        likeCount++;
                        if (!category.isEmpty()) {
                            likedCategories.put(category, likedCategories.getOrDefault(category, 0) + 1);
                        }
                    } else if ("avoid".equals(rating)) {
                        avoidCount++;
                        avoided.add(place);
                    }
                    int actual = rs.getObject("duration_min") == null
                            ? 0 : ((Number) rs.getObject("duration_min")).intValue();
                    int planned = stop == null
                            ? 0 : ((Number) stop.get("durationMin")).intValue();
                    if (actual > 0 && planned > 0 && actual >= planned + 30) {
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        evidence.put("place", place);
                        evidence.put("actualMinutes", actual);
                        evidence.put("plannedMinutes", planned);
                        durationEvidence.add(evidence);
                    }
                }
            }
        }

        if (likeCount >= 2) {
            String category = likedCategories.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey).orElse("体验");
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("id", "travel-like-" + trip.id);
            proposal.put("tag", "旅行偏好");
            proposal.put("value", "更偏爱" + category + "类体验");
            proposal.put("evidence", "本次行程有 " + likeCount + " 个点赞打卡，其中"
                    + category + "最集中");
            proposal.put("confidence", Math.min(0.95, 0.55 + likeCount * 0.08));
            proposal.put("autoApply", false);
            proposals.add(proposal);
        }
        if (avoidCount > 0) {
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("id", "travel-avoid-" + trip.id);
            proposal.put("tag", "旅行避雷");
            proposal.put("value", "避开或不优先安排：" + String.join("、", avoided));
            proposal.put("evidence", "本次行程记录了 " + avoidCount + " 个避雷打卡");
            proposal.put("confidence", Math.min(0.95, 0.6 + avoidCount * 0.08));
            proposal.put("autoApply", false);
            proposals.add(proposal);
        }
        if (!durationEvidence.isEmpty()) {
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("id", "travel-pace-" + trip.id);
            proposal.put("tag", "行程节奏");
            proposal.put("value", "同类地点下次多预留 30 分钟");
            proposal.put("evidence", "有 " + durationEvidence.size()
                    + " 个地点的实际停留明显超过计划");
            proposal.put("details", durationEvidence);
            proposal.put("confidence", Math.min(0.9, 0.55 + durationEvidence.size() * 0.1));
            proposal.put("autoApply", false);
            proposals.add(proposal);
        }
        result.put("proposals", proposals);
        result.put("message", proposals.isEmpty()
                ? "本次打卡还不足以形成可靠偏好建议"
                : "以下建议不会自动生效，需要你逐条确认");
        result.put("autoApplied", false);
        return result;
    }

    private JsonNode findNode(JsonNode itinerary, String nodeKey) {
        for (JsonNode day : itinerary) {
            for (String slot : SLOTS) {
                for (JsonNode node : day.path(slot)) {
                    if (nodeKey.equals(nodeKey(node))) return node;
                }
            }
        }
        return null;
    }

    private Map<String, Object> firstWithState(List<Map<String, Object>> stops, String state) {
        for (Map<String, Object> stop : stops) {
            if (state.equals(stop.get("state"))) return stop;
        }
        return null;
    }

    private Map<String, Object> firstWithStateAfter(List<Map<String, Object>> stops,
                                                    Map<String, Object> current,
                                                    String state) {
        boolean after = current == null;
        for (Map<String, Object> stop : stops) {
            if (current == stop) after = true;
            if (after && state.equals(stop.get("state"))) return stop;
        }
        return null;
    }

    private String buildOfflineText(Map<String, Object> today) {
        StringBuilder text = new StringBuilder();
        text.append(today.get("title")).append("\n")
                .append(today.get("date")).append(" · 第 ")
                .append(today.get("dayOfTrip")).append("/").append(today.get("dayCount")).append(" 天\n\n");
        for (Map<String, Object> stop : castList(today.get("stops"))) {
            text.append(stop.get("estimatedStart")).append("-").append(stop.get("estimatedEnd"))
                    .append("  ").append(stop.get("name"));
            if (stop.get("address") != null && !String.valueOf(stop.get("address")).isEmpty()) {
                text.append("（").append(stop.get("address")).append("）");
            }
            text.append("\n");
        }
        Map<String, Object> budget = castMap(today.get("budget"));
        text.append("\n预算：已花 ¥").append(budget.get("spent"))
                .append("，剩余 ¥").append(budget.get("remaining"));
        return text.toString();
    }

    private String buildOfflineHtml(Map<String, Object> today) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(String.valueOf(today.get("title"))))
                .append(" · 离线行程卡</title><style>")
                .append("body{margin:0;background:#f5f5f7;color:#1d1d1f;font:14px/1.6 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif}")
                .append("main{max-width:720px;margin:0 auto;padding:28px 18px 48px}")
                .append("header{padding:22px;border-radius:16px;background:#fff;border:1px solid rgba(0,0,0,.08)}")
                .append("h1{margin:0 0 6px;font-size:24px}small{color:#6e6e73}")
                .append("ol{margin:18px 0;padding:0;list-style:none}")
                .append("li{display:grid;grid-template-columns:78px 1fr;gap:12px;padding:13px 0;border-top:1px solid rgba(0,0,0,.08)}")
                .append("li:first-child{border-top:0}.time{font-variant-numeric:tabular-nums;color:#0071e3;font-weight:650}")
                .append(".budget{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}")
                .append(".budget span{padding:7px 11px;border-radius:8px;background:#f0f0f3}")
                .append("@media(max-width:480px){main{padding:16px 10px 32px}header{padding:18px}li{grid-template-columns:1fr;gap:3px}}")
                .append("</style></head><body><main><header><h1>")
                .append(escapeHtml(String.valueOf(today.get("title")))).append("</h1><small>")
                .append(escapeHtml(String.valueOf(today.get("date")))).append(" · 第 ")
                .append(today.get("dayOfTrip")).append("/").append(today.get("dayCount"))
                .append(" 天 · 生成于 ").append(escapeHtml(String.valueOf(today.get("generatedAt"))))
                .append("</small></header><ol>");
        for (Map<String, Object> stop : castList(today.get("stops"))) {
            html.append("<li><div class=\"time\">")
                    .append(escapeHtml(String.valueOf(stop.get("estimatedStart")))).append("-")
                    .append(escapeHtml(String.valueOf(stop.get("estimatedEnd"))))
                    .append("</div><div><b>")
                    .append(escapeHtml(String.valueOf(stop.get("name")))).append("</b>");
            if (stop.get("address") != null && !String.valueOf(stop.get("address")).isEmpty()) {
                html.append("<div><small>").append(escapeHtml(String.valueOf(stop.get("address"))))
                        .append("</small></div>");
            }
            html.append("</div></li>");
        }
        Map<String, Object> budget = castMap(today.get("budget"));
        html.append("</ol><div class=\"budget\"><span>已花 ¥").append(budget.get("spent"))
                .append("</span><span>剩余 ¥").append(budget.get("remaining"))
                .append("</span></div></main></body></html>");
        return html.toString();
    }

    private void logActivity(Connection conn, int tripId, String username,
                             String action, String detail) {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into trip_activity(trip_id, username, action, detail, create_time)"
                        + " values(?, ?, ?, ?, now())")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            ps.setString(3, action);
            ps.setString(4, trim(detail, 1000));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("写入 Today 活动记录失败：{}", e.getMessage());
        }
    }

    private String nodeKey(JsonNode node) {
        return node.path("name").asText("") + "|"
                + node.path("address").asText("") + "|"
                + node.path("lat").asText("") + "|"
                + node.path("lng").asText("");
    }

    private String nodeRef(int tripId, String nodeKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((tripId + "|" + nodeKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", hash[i]));
            return "tn_" + hex;
        } catch (Exception e) {
            return "tn_" + Integer.toHexString((tripId + "|" + nodeKey).hashCode());
        }
    }

    private int slotStart(String slot) {
        if ("afternoon".equals(slot)) return 13 * 60 + 30;
        if ("evening".equals(slot)) return 18 * 60;
        return 9 * 60;
    }

    private String slotLabel(String slot) {
        if ("afternoon".equals(slot)) return "下午";
        if ("evening".equals(slot)) return "晚上";
        return "上午";
    }

    private String clock(int minutes) {
        int safe = Math.max(0, Math.min(23 * 60 + 59, minutes));
        return String.format("%02d:%02d", safe / 60, safe % 60);
    }

    private int parseClock(String value) {
        if (value == null || !value.matches("\\d{1,2}:\\d{2}")) return 0;
        String[] parts = value.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private LocalTime parseTime(String value, LocalTime fallback) {
        if (value == null || !value.trim().matches("\\d{1,2}:\\d{2}")) return fallback;
        try {
            return LocalTime.parse(value.trim().length() == 4 ? "0" + value.trim() : value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String normalizeBudgetCategory(String value) {
        String category = value == null ? "" : value.trim();
        if ("other".equals(category)) return "local";
        return BUDGET_CATEGORIES.contains(category) ? category : "local";
    }

    private String normalizeVisibility(String value) {
        String safe = value == null ? "" : value.trim();
        if ("public".equals(safe) || "friends".equals(safe) || "private".equals(safe)) return safe;
        return "private";
    }

    private double parseMoney(String value) {
        if (value == null || value.trim().isEmpty() || value.contains("不限")) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)").matcher(value.replace(",", ""));
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    private double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String safe = value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String format(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(TIME_FORMAT);
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void setDoubleOrNull(PreparedStatement ps, int index, Double value)
            throws Exception {
        if (value == null) ps.setNull(index, java.sql.Types.DOUBLE);
        else ps.setDouble(index, value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }

    private static final class TripView {
        private int id;
        private String owner;
        private String title;
        private String destination;
        private String status;
        private String startDate;
        private String endDate;
        private String budget;
        private String people;
        private String pace;
        private String planData;
        private int planRevision;
        private Timestamp updateTime;
    }
}
