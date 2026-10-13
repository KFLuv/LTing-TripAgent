package com.iflytek.chatgpt.controller;

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
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅途轨迹日志 + 结构化游记。
 * <p>
 * 旅行过程中随手记下每一处打卡：点位、坐标、喜欢/避雷评价、备注、耗时、实拍标记；
 * 行程结束后把这些零散记录整理成一篇结构化的游记草稿，包含行程概览、旅途足迹和实拍点位，
 * 并自动汇总喜欢（亮点）与踩坑（避雷）两类点位。
 * <p>
 * 所有接口都以 session 里的登录用户为准，SQL 一律附加 {@code username = ?} 限定，
 * 别人的轨迹查不到、改不了也删不掉；游记草稿在 Java 里确定性地拼装，不依赖任何 AI。
 */
@RestController
public class TravelLogController {

    private static final Logger log = LoggerFactory.getLogger(TravelLogController.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final com.iflytek.chatgpt.social.SocialService social;

    public TravelLogController(com.iflytek.chatgpt.social.SocialService social) {
        this.social = social;
    }

    /** 评分允许的取值，非法或空一律归一为 neutral */
    private static final String RATING_LIKE = "like";
    private static final String RATING_AVOID = "avoid";
    private static final String RATING_NEUTRAL = "neutral";

    // ==================== 旅途轨迹日志 ====================

    /**
     * 保存一条打卡记录：id 大于 0 视为修改，否则新增。
     * <p>
     * 修改时 {@code log_time} 保持原值不动，仅当原本为 null 时才补上当前时间。
     */
    @PostMapping(value = "/travel-log/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(defaultValue = "0") int id,
                                    @RequestParam(defaultValue = "0") int tripId,
                                    @RequestParam(required = false) String place,
                                    @RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lng,
                                    @RequestParam(required = false) String rating,
                                    @RequestParam(required = false) String note,
                                    @RequestParam(required = false) Integer durationMin,
                                    @RequestParam(required = false) String photos,
                                    @RequestParam(required = false) String visibility,
                                    @RequestParam(required = false) String poiId,
                                    @RequestParam(required = false) String poiName,
                                    @RequestParam(required = false) String images,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用旅途轨迹");
        }
        if (place == null || place.trim().isEmpty()) {
            return fail(result, "打卡点位不能为空");
        }

        String safePlace = trim(place, 120);
        String safeRating = normalizeRating(rating);
        String safeNote = note;
        String safePhotos = trim(photos, 500);
        Integer tripIdVal = tripId > 0 ? tripId : null;

        if (id > 0) {
            if (rawLog(username, id) == null) {
                return fail(result, "打卡记录不存在");
            }
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "update travel_log set trip_id = ?, place = ?, lat = ?, lng = ?, rating = ?,"
                                 + " note = ?, duration_min = ?, photos = ?, log_time = coalesce(log_time, now())"
                                 + " where id = ? and username = ?")) {
                setIntOrNull(ps, 1, tripIdVal);
                ps.setString(2, safePlace);
                setDoubleOrNull(ps, 3, lat);
                setDoubleOrNull(ps, 4, lng);
                ps.setString(5, safeRating);
                ps.setString(6, safeNote);
                setIntOrNull(ps, 7, durationMin);
                ps.setString(8, safePhotos);
                ps.setInt(9, id);
                ps.setString(10, username);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("更新打卡记录失败", e);
                return fail(result, "打卡记录保存失败，请稍后重试");
            }
            result.put("status", "success");
            result.put("id", id);
            result.put("message", "已记录打卡");
            return result;
        }

        int newId;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "insert into travel_log(username, trip_id, place, lat, lng, rating, note,"
                             + " duration_min, photos, log_time, create_time)"
                             + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            setIntOrNull(ps, 2, tripIdVal);
            ps.setString(3, safePlace);
            setDoubleOrNull(ps, 4, lat);
            setDoubleOrNull(ps, 5, lng);
            ps.setString(6, safeRating);
            ps.setString(7, safeNote);
            setIntOrNull(ps, 8, durationMin);
            ps.setString(9, safePhotos);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                newId = keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("保存打卡记录失败", e);
            return fail(result, "打卡记录保存失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("id", newId);
        MySQLUtil.executeUpdate(
                "update travel_log set visibility = ?, poi_id = ?, poi_name = ?, images = ?"
                        + " where id = ? and username = ?",
                com.iflytek.chatgpt.social.Visibility.normalize(visibility),
                trim(poiId, 120), trim(poiName, 120), trim(images, 400_000), newId, username);
        result.put("message", "已记录打卡");
        return result;
    }

    /**
     * 某家商户收到的打卡（商家详情页用）。
     * 公开的谁都能看，仅好友的要互为好友，仅自己的只有本人可见。
     */
    @PostMapping(value = "/travel-log/by-poi", produces = "application/json;charset=UTF-8")
    public Map<String, Object> byPoi(@RequestParam(required = false) String poiId, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String me = ChatController.currentUser(session);
        if (me == null) {
            return fail(result, "请先登录");
        }
        if (poiId == null || poiId.trim().isEmpty()) {
            return fail(result, "缺少商户标识");
        }
        List<Map<String, Object>> logs = new ArrayList<>();
        String sql = "select id, username, place, rating, note, duration_min, images, create_time"
                + " from travel_log where poi_id = ?" + social.visibilityClause("username")
                + " order by id desc limit 50";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, poiId.trim());
            ps.setString(2, me);
            ps.setString(3, me);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("username", rs.getString("username"));
                    m.put("place", rs.getString("place"));
                    m.put("rating", rs.getString("rating"));
                    m.put("note", rs.getString("note"));
                    m.put("durationMin", rs.getObject("duration_min"));
                    m.put("images", rs.getString("images"));
                    java.sql.Timestamp ts = rs.getTimestamp("create_time");
                    m.put("createTime", ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT));
                    logs.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("读取商户打卡失败：poiId={}", poiId, e);
            return fail(result, "打卡读取失败，请稍后重试");
        }
        result.put("status", "success");
        result.put("logs", logs);
        return result;
    }

    /**
     * 打卡记录列表：tripId 传 -1 表示不按行程过滤，否则只看该行程下的记录。
     */
    @PostMapping(value = "/travel-log/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(@RequestParam(defaultValue = "-1") int tripId, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用旅途轨迹");
        }
        Integer filterTripId = tripId >= 0 ? tripId : null;
        result.put("status", "success");
        result.put("logs", loadLogs(username, filterTripId, false));
        return result;
    }

    /**
     * 删除一条打卡记录。
     */
    @PostMapping(value = "/travel-log/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用旅途轨迹");
        }
        int rows;
        try {
            rows = MySQLUtil.executeUpdate("delete from travel_log where id = ? and username = ?", id, username);
        } catch (RuntimeException e) {
            log.error("删除打卡记录失败", e);
            return fail(result, "打卡记录删除失败，请稍后重试");
        }
        if (rows <= 0) {
            return fail(result, "打卡记录不存在或已被删除");
        }
        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    // ==================== 结构化游记 ====================

    /**
     * 根据某个行程下的全部打卡记录，确定性地拼装一篇结构化游记草稿并落库。
     * <p>
     * 草稿分为「一、行程概览」「二、旅途足迹」「三、实拍点位」三段，
     * 同时汇总喜欢（亮点）与避雷（踩坑）两类点位；全程在 Java 里拼装，不调用任何 AI。
     */
    @PostMapping(value = "/travel-log/travelog/generate", produces = "application/json;charset=UTF-8")
    public Map<String, Object> generateTravelog(@RequestParam(defaultValue = "0") int tripId, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用旅途轨迹");
        }

        Map<String, Object> trip = rawTrip(username, tripId);
        if (trip == null) {
            return fail(result, "行程不存在");
        }

        List<Map<String, Object>> logs = loadLogs(username, tripId, true);
        String tripTitle = trip.get("title") == null || String.valueOf(trip.get("title")).trim().isEmpty()
                ? "我的旅行" : String.valueOf(trip.get("title")).trim();
        String title = tripTitle + " · 游记草稿";
        String draft = buildDraft(String.valueOf(trip.get("content")), logs);
        String highlights = buildPoints(logs, RATING_LIKE, "暂无特别亮点");
        String pitfalls = buildPoints(logs, RATING_AVOID, "暂未踩坑");

        int travelogId;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "insert into travelog(username, trip_id, title, draft, highlights, pitfalls, create_time)"
                             + " values(?, ?, ?, ?, ?, ?, now())",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            setIntOrNull(ps, 2, tripId > 0 ? tripId : null);
            ps.setString(3, trim(title, 120));
            ps.setString(4, draft);
            ps.setString(5, highlights);
            ps.setString(6, pitfalls);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                travelogId = keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("生成游记草稿失败", e);
            return fail(result, "游记草稿生成失败，请稍后重试");
        }

        Map<String, Object> travelog = new LinkedHashMap<>();
        travelog.put("id", travelogId);
        travelog.put("title", trim(title, 120));
        travelog.put("draft", draft);
        travelog.put("highlights", highlights);
        travelog.put("pitfalls", pitfalls);
        travelog.put("createTime", LocalDateTime.now().format(TIME_FORMAT));

        result.put("status", "success");
        result.put("travelog", travelog);
        result.put("message", "已生成游记草稿");
        return result;
    }

    /**
     * 游记草稿列表（不含正文，最新的排前面）。
     */
    @PostMapping(value = "/travel-log/travelog/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> listTravelogs(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用旅途轨迹");
        }

        List<Map<String, Object>> travelogs = new ArrayList<>();
        String sql = "select id, trip_id, title, create_time from travelog where username = ? order by id desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    int tid = rs.getInt("trip_id");
                    m.put("tripId", rs.wasNull() ? 0 : tid);
                    m.put("title", rs.getString("title"));
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    travelogs.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取游记列表失败：{}", e.getMessage());
        }

        result.put("status", "success");
        result.put("travelogs", travelogs);
        return result;
    }

    /**
     * 查看一篇游记草稿的完整内容。
     */
    @PostMapping(value = "/travel-log/travelog/get", produces = "application/json;charset=UTF-8")
    public Map<String, Object> getTravelog(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用旅途轨迹");
        }

        Map<String, Object> travelog = null;
        String sql = "select id, trip_id, title, draft, highlights, pitfalls, create_time from travelog"
                + " where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    travelog = new LinkedHashMap<>();
                    travelog.put("id", rs.getInt("id"));
                    int tid = rs.getInt("trip_id");
                    travelog.put("tripId", rs.wasNull() ? 0 : tid);
                    travelog.put("title", rs.getString("title"));
                    travelog.put("draft", rs.getString("draft"));
                    travelog.put("highlights", rs.getString("highlights"));
                    travelog.put("pitfalls", rs.getString("pitfalls"));
                    travelog.put("createTime", formatTime(rs.getTimestamp("create_time")));
                }
            }
        } catch (SQLException e) {
            log.warn("读取游记失败：{}", e.getMessage());
        }
        if (travelog == null) {
            return fail(result, "游记不存在");
        }

        result.put("status", "success");
        result.put("travelog", travelog);
        return result;
    }

    // ==================== 内部工具方法 ====================

    /** 拼装游记草稿正文：行程概览 / 旅途足迹 / 实拍点位 三段 */
    private String buildDraft(String tripContent, List<Map<String, Object>> logs) {
        StringBuilder sb = new StringBuilder();

        sb.append("一、行程概览\n");
        if (tripContent == null || tripContent.trim().isEmpty()) {
            sb.append("暂无行程概览");
        } else {
            sb.append(summarize(tripContent.trim(), 300));
        }
        sb.append("\n\n");

        sb.append("二、旅途足迹\n");
        if (logs.isEmpty()) {
            sb.append("暂无打卡记录");
        } else {
            int i = 1;
            for (Map<String, Object> log : logs) {
                sb.append(i++).append(". ")
                        .append(timeText(log)).append(" · ")
                        .append(value(log.get("place"))).append(" · ")
                        .append(ratingLabel(str(log.get("rating"))));
                Object duration = log.get("durationMin");
                if (duration != null) {
                    sb.append(" · 耗时").append(duration).append("分钟");
                }
                String note = str(log.get("note"));
                if (note != null && !note.trim().isEmpty()) {
                    sb.append(" · ").append(note.trim());
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        sb.append("三、实拍点位\n");
        boolean hasPhoto = false;
        for (Map<String, Object> log : logs) {
            String photos = str(log.get("photos"));
            if (photos != null && !photos.trim().isEmpty()) {
                sb.append("- ").append(value(log.get("place")))
                        .append("（").append(timeText(log)).append("）\n");
                hasPhoto = true;
            }
        }
        if (!hasPhoto) {
            sb.append("暂无实拍照片");
        }

        return sb.toString().trim();
    }

    /** 把某一评分的点位汇总成多行「点位：备注」，没有则返回默认文案 */
    private String buildPoints(List<Map<String, Object>> logs, String rating, String fallback) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (Map<String, Object> log : logs) {
            if (rating.equals(str(log.get("rating")))) {
                if (any) {
                    sb.append("\n");
                }
                String note = str(log.get("note"));
                sb.append(value(log.get("place"))).append("：")
                        .append(note == null || note.trim().isEmpty() ? "暂无备注" : note.trim());
                any = true;
            }
        }
        return any ? sb.toString() : fallback;
    }

    /** 读取打卡记录，tripId 为 null 表示不过滤，ascending 控制按 id 升序还是降序 */
    private List<Map<String, Object>> loadLogs(String username, Integer tripId, boolean ascending) {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean filter = tripId != null;
        String sql = "select id, trip_id, place, lat, lng, rating, note, duration_min, photos, images,"
                + " poi_id, poi_name, log_time"
                + " from travel_log where username = ?"
                + (filter ? " and trip_id = ?" : "")
                + (ascending ? " order by log_time, id" : " order by id desc");
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            if (filter) {
                ps.setInt(2, tripId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    int tid = rs.getInt("trip_id");
                    m.put("tripId", rs.wasNull() ? 0 : tid);
                    m.put("place", rs.getString("place"));
                    double lat = rs.getDouble("lat");
                    m.put("lat", rs.wasNull() ? null : lat);
                    double lng = rs.getDouble("lng");
                    m.put("lng", rs.wasNull() ? null : lng);
                    m.put("rating", rs.getString("rating"));
                    m.put("note", rs.getString("note"));
                    int duration = rs.getInt("duration_min");
                    m.put("durationMin", rs.wasNull() ? null : duration);
                    m.put("photos", rs.getString("photos"));
                    m.put("images", rs.getString("images"));
                    m.put("poiId", rs.getString("poi_id"));
                    m.put("poiName", rs.getString("poi_name"));
                    m.put("logTime", formatTime(rs.getTimestamp("log_time")));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取打卡记录失败：{}", e.getMessage());
        }
        return list;
    }

    /** 读取一条打卡记录用于校验归属 */
    private Map<String, Object> rawLog(String username, int id) {
        String sql = "select id from travel_log where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    return m;
                }
            }
        } catch (SQLException e) {
            log.warn("读取打卡记录失败：{}", e.getMessage());
        }
        return null;
    }

    /** 读取行程的标题与正文用于生成游记 */
    private Map<String, Object> rawTrip(String username, int id) {
        String sql = "select title, content from trip where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", rs.getString("title"));
                    m.put("content", rs.getString("content"));
                    return m;
                }
            }
        } catch (SQLException e) {
            log.warn("读取行程失败：{}", e.getMessage());
        }
        return null;
    }

    /** 截取正文前若干字作为行程概览，超出部分以省略号收尾，空白折叠成单空格 */
    private String summarize(String content, int max) {
        String flat = content.replaceAll("\\s+", " ");
        return flat.length() > max ? flat.substring(0, max) + "……" : flat;
    }

    /** 打卡时间文案，未记录时为「未记录时间」 */
    private String timeText(Map<String, Object> log) {
        String t = str(log.get("logTime"));
        return t == null || t.isEmpty() ? "未记录时间" : t;
    }

    private String value(Object o) {
        String s = str(o);
        return s == null || s.isEmpty() ? "未命名点位" : s;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private String normalizeRating(String rating) {
        String r = rating == null ? "" : rating.trim().toLowerCase();
        if (RATING_LIKE.equals(r) || RATING_AVOID.equals(r)) {
            return r;
        }
        return RATING_NEUTRAL;
    }

    private String ratingLabel(String rating) {
        if (RATING_LIKE.equals(rating)) {
            return "喜欢";
        }
        if (RATING_AVOID.equals(rating)) {
            return "避雷";
        }
        return "一般";
    }

    private void setIntOrNull(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private void setDoubleOrNull(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private String formatTime(java.sql.Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT);
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("status", "error");
        result.put("message", message);
        return result;
    }
}
