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
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 朋友圈模块：日常动态记录。
 * <p>
 * 发布时**自动记录发布时间**（服务端 now()，不由前端传）；地理位置优先用本页拿到的
 * 坐标反查地名，拿不到就落前端给的点位名；图片可选，最多 3 张，存 data URL。
 * 动态落库后 AI 也能读到（见 TravelAgentService 的 moment 工具）。
 */
@RestController
public class MomentController {

    private static final Logger log = LoggerFactory.getLogger(MomentController.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int MAX_CONTENT = 2000;
    private static final int MAX_PLACE = 120;
    private static final int MAX_IMAGES = 3;
    /** 单张图上限（data URL 字符数，约 400KB） */
    private static final int MAX_IMAGE_CHARS = 400_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final com.iflytek.chatgpt.agent.ReverseGeocoder reverseGeocoder;
    private final com.iflytek.chatgpt.social.SocialService social;

    public MomentController(com.iflytek.chatgpt.agent.ReverseGeocoder reverseGeocoder,
                            com.iflytek.chatgpt.social.SocialService social) {
        this.reverseGeocoder = reverseGeocoder;
        this.social = social;
    }

    @PostMapping(value = "/moment/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }

        List<Map<String, Object>> moments = new ArrayList<>();
        String sql = "select id, content, place, lat, lng, images, visibility, create_time from moment"
                + " where username = ? order by id desc limit 50";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("content", rs.getString("content"));
                    m.put("place", rs.getString("place"));
                    double lat = rs.getDouble("lat");
                    m.put("lat", rs.wasNull() ? null : lat);
                    double lng = rs.getDouble("lng");
                    m.put("lng", rs.wasNull() ? null : lng);
                    m.put("images", parseImages(rs.getString("images")));
                    m.put("visibility", rs.getString("visibility"));
                    java.sql.Timestamp ts = rs.getTimestamp("create_time");
                    m.put("createTime", ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT));
                    moments.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("读取朋友圈失败：username={}", username, e);
            return fail(result, "动态读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("moments", moments);
        return result;
    }

    @PostMapping(value = "/moment/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(required = false) String content,
                                    @RequestParam(required = false) String place,
                                    @RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lng,
                                    @RequestParam(required = false) String images,
                                    @RequestParam(required = false) String visibility,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }
        if (content == null || content.trim().isEmpty()) {
            return fail(result, "说点什么再发布吧");
        }

        List<String> safeImages = parseImages(images);
        if (safeImages.size() > MAX_IMAGES) {
            return fail(result, "最多上传 " + MAX_IMAGES + " 张图片");
        }
        for (String img : safeImages) {
            if (img.length() > MAX_IMAGE_CHARS) {
                return fail(result, "有图片太大，请换一张更小的");
            }
        }

        // 地理位置：有坐标就反查地名（自动记录），没有就用前端给的点位名
        String safePlace = trim(place, MAX_PLACE);
        if ((safePlace == null || safePlace.isEmpty()) && lat != null && lng != null) {
            String resolved = reverseGeocoder.resolve(lat, lng);
            if (resolved != null && !resolved.isEmpty()) {
                safePlace = trim(resolved, MAX_PLACE);
            }
        }

        int newId;
        String safeVisibility = social.normalizeVisibility(visibility);
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "insert into moment(username, content, place, lat, lng, images, visibility, create_time)"
                             + " values(?, ?, ?, ?, ?, ?, ?, now())",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, trim(content, MAX_CONTENT));
            ps.setString(3, safePlace);
            if (lat == null || lng == null) {
                ps.setNull(4, java.sql.Types.DOUBLE);
                ps.setNull(5, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(4, lat);
                ps.setDouble(5, lng);
            }
            ps.setString(6, writeImages(safeImages));
            ps.setString(7, safeVisibility);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                newId = keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("发布动态失败：username={}", username, e);
            return fail(result, "发布失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("id", newId);
        result.put("visibility", safeVisibility);
        result.put("message", "已发布");
        return result;
    }

    /**
     * 广场：聚合所有人的【公开】动态，无需加好友即可看。
     * 只查 public，不看 friends/private——这是"广场"和"好友主页"的分界线。
     */
    @PostMapping(value = "/moment/square", produces = "application/json;charset=UTF-8")
    public Map<String, Object> square(@RequestParam(defaultValue = "30") int limit, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) {
            return fail(result, "请先登录");
        }
        int take = Math.max(1, Math.min(limit, 100));

        List<Map<String, Object>> moments = new ArrayList<>();
        String sql = "select m.id, m.username, m.content, m.place, m.images, m.create_time,"
                + " p.nickname, p.avatar from moment m"
                + " left join user_profile p on p.username = m.username"
                + " where m.visibility = 'public' order by m.id desc limit " + take;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("username", rs.getString("username"));
                m.put("nickname", nullToEmpty(rs.getString("nickname")));
                m.put("avatar", nullToEmpty(rs.getString("avatar")));
                m.put("content", rs.getString("content"));
                m.put("place", rs.getString("place"));
                m.put("images", parseImages(rs.getString("images")));
                java.sql.Timestamp ts = rs.getTimestamp("create_time");
                m.put("createTime", ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT));
                moments.add(m);
            }
        } catch (SQLException e) {
            log.error("读取广场动态失败", e);
            return fail(result, "广场读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("moments", moments);
        return result;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @PostMapping(value = "/moment/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }
        int rows = MySQLUtil.executeUpdate(
                "delete from moment where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "动态不存在或已被删除");
        }
        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    /**
     * 图片字段：接受 JSON 数组或换行分隔的 data URL 串，返回列表。
     * 解析失败按"没有图片"处理——不因为脏数据把整条动态卡住。
     */
    private List<String> parseImages(String images) {
        List<String> list = new ArrayList<>();
        if (images == null || images.trim().isEmpty()) {
            return list;
        }
        String s = images.trim();
        if (s.startsWith("[")) {
            try {
                JsonNode node = JSON.readTree(s);
                if (node.isArray()) {
                    for (JsonNode n : node) {
                        String v = n.asText("");
                        if (!v.isEmpty()) {
                            list.add(v);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("朋友圈图片字段解析失败，按无图处理：{}", e.getMessage());
            }
            return list;
        }
        for (String line : s.split("\n")) {
            if (!line.trim().isEmpty()) {
                list.add(line.trim());
            }
        }
        return list;
    }

    private String writeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(images);
        } catch (Exception e) {
            return null;
        }
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
