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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出行工作台 · 候选池 + 统一校验（P0）。
 *
 * <p>候选池与正式行程分离：这里的记录只用于"收藏/比较"，
 * 加入某一天由后续 plan/itinerary/apply 负责。
 *
 * <p>身份：写入必须登录（可用游客身份），按 username 隔离，跨设备同步。
 */
@RestController
public class PlanPoolController {

    private static final Logger log = LoggerFactory.getLogger(PlanPoolController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile boolean ready = false;

    private static final String DDL = "create table if not exists trip_candidate ("
            + " id int not null auto_increment primary key,"
            + " username varchar(50) not null,"
            + " trip_id int not null default 0,"
            + " poi_id varchar(64) null,"
            + " name varchar(128) not null,"
            + " type varchar(32) null,"
            + " city varchar(64) null,"
            + " address varchar(255) null,"
            + " lat double null,"
            + " lng double null,"
            + " duration_min int not null default 0,"
            + " cost varchar(40) null,"
            + " evidence varchar(512) null,"
            + " photo text null,"
            + " create_time datetime not null"
            + ") default charset=utf8mb4";

    private static void ensure() {
        if (ready) return;
        synchronized (PlanPoolController.class) {
            if (ready) return;
            try {
                MySQLUtil.executeUpdate(DDL);
                try {
                    MySQLUtil.executeUpdate("alter table trip_candidate add column photo text null");
                } catch (Exception ignored) {
                    // 已存在的库有这一列时 alter 会报重复列，不影响使用。
                }
                try {
                    MySQLUtil.executeUpdate("alter table trip_candidate add column cost varchar(40) null");
                } catch (Exception ignored) {
                    // 已存在的库有这一列时 alter 会报重复列，不影响使用。
                }
                ready = true;
            } catch (Exception e) {
                log.warn("trip_candidate 建表失败：{}", e.getMessage());
            }
        }
    }

    /** 候选池列表 */
    @PostMapping(value = "/plan/candidate/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int tripId,
                                    @RequestParam(required = false) String city,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String user = ChatController.currentUser(session);
        if (user == null) return fail(out, "请先登录（可用游客身份）");
        ensure();
        List<Map<String, Object>> items = new ArrayList<>();
        boolean filterCity = city != null && !city.trim().isEmpty();
        String sql = "select id, poi_id, name, type, city, address, lat, lng, duration_min, cost, evidence, photo"
                + " from trip_candidate where username = ? and trip_id = ?"
                + (filterCity ? " and city = ?" : "") + " order by id desc";
        try (Connection c = MySQLUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user);
            ps.setInt(2, tripId);
            if (filterCity) ps.setString(3, city.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("poiId", rs.getString("poi_id"));
                    m.put("name", rs.getString("name"));
                    m.put("type", rs.getString("type"));
                    m.put("city", rs.getString("city"));
                    m.put("address", rs.getString("address"));
                    m.put("lat", rs.getObject("lat"));
                    m.put("lng", rs.getObject("lng"));
                    m.put("durationMin", rs.getInt("duration_min"));
                    m.put("cost", rs.getString("cost"));
                    m.put("evidence", rs.getString("evidence"));
                    m.put("photo", rs.getString("photo"));
                    items.add(m);
                }
            }
        } catch (Exception e) {
            log.error("候选池读取失败", e);
            return fail(out, "候选池读取失败");
        }
        out.put("status", "success");
        out.put("items", items);
        return out;
    }

    /** 新增候选 */
    @PostMapping(value = "/plan/candidate/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(defaultValue = "0") int tripId,
                                    @RequestParam(required = false) String poiId,
                                    @RequestParam String name,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String city,
                                    @RequestParam(required = false) String address,
                                    @RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lng,
                                    @RequestParam(defaultValue = "0") int durationMin,
                                    @RequestParam(required = false) String cost,
                                    @RequestParam(required = false) String evidence,
                                    @RequestParam(required = false) String photo,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String user = ChatController.currentUser(session);
        if (user == null) return fail(out, "请先登录（可用游客身份）");
        if (name == null || name.trim().isEmpty()) return fail(out, "候选名称不能为空");
        ensure();
        try (Connection c = MySQLUtil.getConnection();
             PreparedStatement find = c.prepareStatement(
                     "select id from trip_candidate where username = ? and trip_id = ? and name = ?"
                             + " and coalesce(address, '') = ? order by id limit 1")) {
            find.setString(1, user);
            find.setInt(2, tripId);
            find.setString(3, name.trim());
            find.setString(4, address == null ? "" : address.trim());
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    int existingId = rs.getInt("id");
                    if (photo != null && !photo.trim().isEmpty()) {
                        MySQLUtil.executeUpdate(
                                "update trip_candidate set photo = ?, cost = ? where id = ? and username = ?",
                                photo.trim(), cost, existingId, user);
                    }
                    out.put("status", "success");
                    out.put("id", existingId);
                    out.put("duplicate", true);
                    return out;
                }
            }
        } catch (Exception e) {
            log.warn("候选查重失败，将继续新增：{}", e.getMessage());
        }
        try (Connection c = MySQLUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "insert into trip_candidate(username, trip_id, poi_id, name, type, city, address, lat, lng,"
                             + " duration_min, cost, evidence, photo, create_time) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user);
            ps.setInt(2, tripId);
            ps.setString(3, poiId);
            ps.setString(4, name.trim());
            ps.setString(5, type);
            ps.setString(6, city);
            ps.setString(7, address);
            if (lat == null) ps.setNull(8, java.sql.Types.DOUBLE); else ps.setDouble(8, lat);
            if (lng == null) ps.setNull(9, java.sql.Types.DOUBLE); else ps.setDouble(9, lng);
            ps.setInt(10, durationMin);
            ps.setString(11, cost);
            ps.setString(12, evidence);
            ps.setString(13, photo);
            ps.setTimestamp(14, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) out.put("id", keys.getInt(1));
            }
        } catch (Exception e) {
            log.error("候选保存失败", e);
            return fail(out, "候选保存失败");
        }
        out.put("status", "success");
        out.put("duplicate", false);
        return out;
    }

    /** 删除候选 */
    @PostMapping(value = "/plan/candidate/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam int id, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String user = ChatController.currentUser(session);
        if (user == null) return fail(out, "请先登录（可用游客身份）");
        ensure();
        try {
            MySQLUtil.executeUpdate("delete from trip_candidate where id = ? and username = ?", id, user);
        } catch (Exception e) {
            log.error("候选删除失败", e);
            return fail(out, "候选删除失败");
        }
        out.put("status", "success");
        return out;
    }

    /**
     * 统一校验（第一版）：候选总时长 vs 可用时长。
     * 距离/营业时间/预算在后续版本接入（接口保持不变，只增加 conflicts 项）。
     */
    @PostMapping(value = "/plan/validate", produces = "application/json;charset=UTF-8")
    public Map<String, Object> validate(@RequestParam(defaultValue = "0") int tripId,
                                        @RequestParam(defaultValue = "1") int days,
                                        @RequestParam(defaultValue = "480") int dayMinutes,
                                        @RequestParam(required = false) String itinerary,
                                        @RequestParam(required = false) String budget,
                                        @RequestParam(required = false) String people,
                                        @RequestParam(required = false) String preferences,
                                        HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String user = ChatController.currentUser(session);
        if (user == null) return fail(out, "请先登录（可用游客身份）");
        if (itinerary != null && !itinerary.trim().isEmpty()) {
            return validateItinerary(out, itinerary, budget, people, preferences, dayMinutes);
        }
        ensure();
        int total = 0, count = 0;
        try (Connection c = MySQLUtil.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) cnt, coalesce(sum(duration_min),0) total"
                             + " from trip_candidate where username = ? and trip_id = ?")) {
            ps.setString(1, user);
            ps.setInt(2, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt("cnt");
                    total = rs.getInt("total");
                }
            }
        } catch (Exception e) {
            log.error("校验读取失败", e);
            return fail(out, "校验读取失败");
        }
        int capacity = Math.max(1, days) * Math.max(1, dayMinutes);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (total > capacity) {
            Map<String, Object> c1 = new LinkedHashMap<>();
            c1.put("type", "TIME");
            c1.put("message", "候选总时长超出 " + (total - capacity) + " 分钟，需要删减或增加天数");
            conflicts.add(c1);
        }
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("distance", "未接入（需要坐标与路线服务）");
        check.put("openingHours", "未接入（数据源缺失，不假装已校验）");
        check.put("budget", "未接入（候选暂未带金额）");

        out.put("status", "success");
        out.put("count", count);
        out.put("totalMinutes", total);
        out.put("capacityMinutes", capacity);
        out.put("ok", conflicts.isEmpty());
        out.put("conflicts", conflicts);
        out.put("pendingChecks", check);
        return out;
    }

    public Map<String, Object> validateItinerary(Map<String, Object> out, String itinerary,
                                                  String budget, String people, String preferences,
                                                  int dayMinutes) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<Map<String, Object>> dayResults = new ArrayList<>();
        int totalMinutes = 0;
        double totalDistanceKm = 0;
        double estimatedBudget = 0;
        int missingPrice = 0;
        int missingOpening = 0;
        int peopleCount = parsePeople(people);
        double budgetLimit = parseBudgetUpper(budget);
        String prefs = preferences == null ? "" : preferences;

        try {
            JsonNode days = JSON.readTree(itinerary);
            if (!days.isArray()) throw new IllegalArgumentException("itinerary 必须是数组");
            int dayIndex = 0;
            for (JsonNode day : days) {
                dayIndex++;
                List<JsonNode> ordered = new ArrayList<>();
                for (String slot : new String[]{"morning", "afternoon", "evening"}) {
                    JsonNode items = day.path(slot);
                    if (items.isArray()) items.forEach(ordered::add);
                }
                int minutes = 0;
                double distanceKm = 0;
                double dayBudget = 0;
                for (int i = 0; i < ordered.size(); i++) {
                    JsonNode item = ordered.get(i);
                    minutes += item.path("durationMin").asInt(90);
                    String costText = item.path("cost").asText("");
                    if (costText.trim().isEmpty()) missingPrice++;
                    else dayBudget += parseMoney(costText) * peopleCount;
                    if (item.path("openTime").asText("").trim().isEmpty()) missingOpening++;
                    if (i > 0) {
                        JsonNode prev = ordered.get(i - 1);
                        if (hasCoord(prev) && hasCoord(item)) {
                            distanceKm += haversine(
                                    prev.path("lat").asDouble(), prev.path("lng").asDouble(),
                                    item.path("lat").asDouble(), item.path("lng").asDouble()) * 1.28 / 1000.0;
                        }
                    }
                    String text = item.path("name").asText("") + " " + item.path("type").asText("");
                    if (prefs.contains("不吃辣") && text.matches(".*(川菜|湘菜|火锅|麻辣|重庆).*")) {
                        conflicts.add(conflict("PREFERENCE", dayIndex,
                                item.path("name").asText("未命名地点") + " 可能与“不吃辣”冲突"));
                    }
                    if (prefs.contains("室内优先") && text.matches(".*(公园|景区|山|湖|广场|步行街).*")) {
                        conflicts.add(conflict("PREFERENCE", dayIndex,
                                item.path("name").asText("未命名地点") + " 是户外点位，与“室内优先”不一致"));
                    }
                }
                double distanceLimit = prefs.contains("少走路") ? 7.0 : 20.0;
                if (minutes > dayMinutes) {
                    conflicts.add(conflict("TIME", dayIndex,
                            "第 " + dayIndex + " 天超出 " + (minutes - dayMinutes) + " 分钟"));
                }
                if (distanceKm > distanceLimit) {
                    conflicts.add(conflict("DISTANCE", dayIndex,
                            "第 " + dayIndex + " 天点位间直线距离约 " + Math.round(distanceKm)
                                    + " km，实际通勤更长"));
                }
                totalMinutes += minutes;
                totalDistanceKm += distanceKm;
                estimatedBudget += dayBudget;
                Map<String, Object> dr = new LinkedHashMap<>();
                dr.put("day", dayIndex);
                dr.put("date", day.path("date").asText(""));
                dr.put("count", ordered.size());
                dr.put("minutes", minutes);
                dr.put("distanceKm", Math.round(distanceKm * 10) / 10.0);
                dr.put("estimatedBudget", Math.round(dayBudget * 100) / 100.0);
                dayResults.add(dr);
            }
            if (budgetLimit > 0 && estimatedBudget > budgetLimit) {
                conflicts.add(conflict("BUDGET", 0,
                        "预计点位消费约 ¥" + Math.round(estimatedBudget)
                                + "，超过预算上限约 ¥" + Math.round(budgetLimit)));
            }
            out.put("status", "success");
            out.put("ok", conflicts.isEmpty());
            out.put("conflicts", conflicts);
            out.put("days", dayResults);
            out.put("totalMinutes", totalMinutes);
            out.put("totalDistanceKm", Math.round(totalDistanceKm * 10) / 10.0);
            out.put("estimatedBudget", Math.round(estimatedBudget * 100) / 100.0);
            out.put("budgetLimit", budgetLimit);
            out.put("missingPriceCount", missingPrice);
            out.put("missingOpeningCount", missingOpening);
            out.put("checks", checks());
            return out;
        } catch (Exception e) {
            log.warn("结构化行程校验失败：{}", e.getMessage());
            return fail(out, "行程校验失败，请检查数据格式");
        }
    }

    private static Map<String, Object> conflict(String type, int day, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("day", day);
        item.put("message", message);
        return item;
    }

    private static Map<String, Object> checks() {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("time", "已按每日分钟上限校验");
        check.put("distance", "已按点位直线距离估算，实际道路距离可能更长");
        check.put("openingHours", "有营业信息时展示，缺失时明确标记未校验");
        check.put("budget", "按 POI 人均字段估算，缺失价格不参与合计");
        check.put("preference", "已检查不吃辣和室内优先等可识别冲突");
        return check;
    }

    private static boolean hasCoord(JsonNode item) {
        return !item.path("lat").asText("").isEmpty() && !item.path("lng").asText("").isEmpty();
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static int parsePeople(String people) {
        if (people == null) return 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(people);
        return m.find() ? Math.max(1, Integer.parseInt(m.group(1))) : 1;
    }

    private static double parseBudgetUpper(String budget) {
        if (budget == null || budget.trim().isEmpty() || budget.contains("不限")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)")
                .matcher(budget.replace(",", ""));
        double last = 0;
        while (m.find()) last = Double.parseDouble(m.group(1));
        return last;
    }

    private static double parseMoney(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(value);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }
}
