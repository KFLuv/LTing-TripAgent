package com.iflytek.chatgpt.controller;

import com.iflytek.chatgpt.nearby.NearbyService;
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
 * 附近吃喝玩乐模块的对外接口。
 * <p>
 * 全部收在 {@code /nearby/*} 命名空间下，只服务「附近吃喝玩乐」页面：
 * 不作为全局能力暴露（Agent 工具清单里没有它，其它页面也不调用）。
 * 商户与商户评分来自 {@link NearbyService} 的数据源；用户自己的评分与点评存在 poi_review。
 */
@RestController
public class NearbyController {

    private static final Logger log = LoggerFactory.getLogger(NearbyController.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NearbyService nearbyService;

    public NearbyController(NearbyService nearbyService) {
        this.nearbyService = nearbyService;
    }

    /** 查附近的商户：5~10km，带评分与点评数（点评数含本机用户自己写的） */
    @PostMapping(value = "/nearby/search", produces = "application/json;charset=UTF-8")
    public Map<String, Object> search(@RequestParam(required = false) Double lat,
                                      @RequestParam(required = false) Double lng,
                                      @RequestParam(required = false) Integer radius,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Integer limit,
                                      HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) {
            return fail(result, "请先登录");
        }
        double[] coords = resolveCoords(session, lat, lng);
        if (coords == null) {
            return fail(result, "还没有定位：请允许浏览器定位，或先点一次「我的位置」");
        }

        Map<String, Object> found = nearbyService.search(coords[0], coords[1], radius, keyword, limit);
        if (found.containsKey("error")) {
            result.put("status", "error");
            result.put("message", found.get("error"));
            result.put("source", found.get("source"));
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pois = (List<Map<String, Object>>) found.get("pois");
        attachReviewStats(pois);

        result.put("status", "success");
        result.put("source", found.get("source"));
        result.put("radiusMeters", found.get("radiusMeters"));
        result.put("lat", coords[0]);
        result.put("lng", coords[1]);
        result.put("pois", pois);
        return result;
    }

    /** 某商户的全部点评（所有人可见，便于看口碑） */
    @PostMapping(value = "/nearby/review/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> reviews(@RequestParam(required = false) String poiId, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) {
            return fail(result, "请先登录");
        }
        if (poiId == null || poiId.trim().isEmpty()) {
            return fail(result, "缺少商户标识");
        }

        List<Map<String, Object>> reviews = new ArrayList<>();
        String sql = "select id, username, rating, content, images, create_time from poi_review"
                + " where poi_id = ? order by id desc limit 50";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, poiId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("username", rs.getString("username"));
                    m.put("rating", rs.getInt("rating"));
                    m.put("content", rs.getString("content"));
                    m.put("images", rs.getString("images"));
                    java.sql.Timestamp ts = rs.getTimestamp("create_time");
                    m.put("createTime", ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT));
                    reviews.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("读取商户点评失败：poiId={}", poiId, e);
            return fail(result, "点评读取失败，请稍后重试");
        }
        result.put("status", "success");
        result.put("reviews", reviews);
        return result;
    }

    /** 写一条点评（1~5 星），同一人对同一商户只保留最新一条 */
    @PostMapping(value = "/nearby/review/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> saveReview(@RequestParam(required = false) String poiId,
                                          @RequestParam(required = false) String poiName,
                                          @RequestParam(defaultValue = "0") int rating,
                                          @RequestParam(required = false) String content,
                                          @RequestParam(required = false) String visibility,
                                          @RequestParam(required = false) String images,
                                          HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }
        if (poiId == null || poiId.trim().isEmpty()) {
            return fail(result, "缺少商户标识");
        }
        if (rating < 1 || rating > 5) {
            return fail(result, "评分请给 1~5 星");
        }
        String safePoiId = poiId.trim().length() > 120 ? poiId.trim().substring(0, 120) : poiId.trim();
        String safeName = poiName == null ? "" : poiName.trim();
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.length() > 1000) {
            safeContent = safeContent.substring(0, 1000);
        }

        try {
            MySQLUtil.executeUpdate(
                    "delete from poi_review where poi_id = ? and username = ?", safePoiId, username);
            MySQLUtil.executeUpdate(
                    "insert into poi_review(poi_id, poi_name, username, rating, content, visibility, images, create_time)"
                            + " values(?, ?, ?, ?, ?, ?, ?, now())",
                    safePoiId, safeName, username, rating, safeContent,
                    com.iflytek.chatgpt.social.Visibility.normalize(visibility),
                    images == null || images.length() > 1_200_000 ? null : images);
        } catch (Exception e) {
            log.error("保存商户点评失败：poiId={}", safePoiId, e);
            return fail(result, "点评保存失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("message", "已发布点评");
        return result;
    }

    @PostMapping(value = "/nearby/review/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> deleteReview(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }
        int rows = MySQLUtil.executeUpdate(
                "delete from poi_review where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "点评不存在或已被删除");
        }
        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    /** 商户详情：店铺图 / 评级 / 地址 / 电话 + 本机点评与菜单 */
    @PostMapping(value = "/nearby/detail", produces = "application/json;charset=UTF-8")
    public Map<String, Object> detail(@RequestParam(required = false) String poiId, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) {
            return fail(result, "请先登录");
        }
        if (poiId == null || poiId.trim().isEmpty()) {
            return fail(result, "缺少商户标识");
        }
        Map<String, Object> found = nearbyService.detail(poiId.trim());
        if (found.containsKey("error")) {
            result.put("status", "error");
            result.put("message", found.get("error"));
            return result;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> poi = (Map<String, Object>) found.get("poi");
        attachReviewStats(poi);
        poi.put("menu", loadMenu(poiId.trim()));
        result.put("status", "success");
        result.put("source", found.get("source"));
        result.put("poi", poi);
        return result;
    }

    /** 菜单：数据源不提供菜品，这里读用户/AI 录入的条目 */
    @PostMapping(value = "/nearby/menu/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> menuList(@RequestParam(required = false) String poiId, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) {
            return fail(result, "请先登录");
        }
        result.put("status", "success");
        result.put("menu", loadMenu(poiId == null ? "" : poiId.trim()));
        return result;
    }

    @PostMapping(value = "/nearby/menu/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> menuSave(@RequestParam(required = false) String poiId,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) String price,
                                        @RequestParam(required = false) String note,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }
        if (poiId == null || poiId.trim().isEmpty() || name == null || name.trim().isEmpty()) {
            return fail(result, "商户与菜名都不能为空");
        }
        try {
            MySQLUtil.executeUpdate(
                    "insert into poi_menu(poi_id, name, price, note, username, create_time)"
                            + " values(?, ?, ?, ?, ?, now())",
                    cut(poiId, 120), cut(name, 120), cut(price, 40), cut(note, 200), username);
        } catch (Exception e) {
            log.error("保存菜单失败：poiId={}", poiId, e);
            return fail(result, "菜单保存失败，请稍后重试");
        }
        result.put("status", "success");
        result.put("message", "已加入菜单");
        result.put("menu", loadMenu(poiId.trim()));
        return result;
    }

    private List<Map<String, Object>> loadMenu(String poiId) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (poiId.isEmpty()) {
            return list;
        }
        String sql = "select id, name, price, note from poi_menu where poi_id = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, poiId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("name", rs.getString("name"));
                    m.put("price", rs.getString("price"));
                    m.put("note", rs.getString("note"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取菜单失败：{}", e.getMessage());
        }
        return list;
    }

    private String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /**
     * 给商户列表批量补上"本机用户评过多少分、多少条点评"。
     * <p>
     * 一次 group by 查完再合并——按家逐条查的话，200 家就是 200 次数据库往返。
     */
    private void attachReviewStats(List<Map<String, Object>> pois) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> poi : pois) {
            Object id = poi.get("poiId");
            if (id != null && !String.valueOf(id).isEmpty()) {
                ids.add(String.valueOf(id));
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        Map<String, Object[]> stats = new LinkedHashMap<>();
        String sql = "select poi_id, count(*) as c, avg(rating) as a from poi_review"
                + " where poi_id in (" + in + ") group by poi_id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.put(rs.getString("poi_id"),
                            new Object[]{rs.getInt("c"), rs.getDouble("a")});
                }
            }
        } catch (SQLException e) {
            log.warn("批量统计商户点评失败：{}", e.getMessage());
        }
        for (Map<String, Object> poi : pois) {
            Object[] s = stats.get(String.valueOf(poi.get("poiId")));
            if (s == null) {
                continue;
            }
            int count = (Integer) s[0];
            poi.put("reviewCount", count);
            if (count > 0) {
                poi.put("ourRating", Math.round(((Double) s[1]) * 10) / 10.0);
            }
        }
    }

    /** 单条商户（详情页用）：复用批量逻辑 */
    private void attachReviewStats(Map<String, Object> poi) {
        List<Map<String, Object>> one = new ArrayList<>();
        one.add(poi);
        attachReviewStats(one);
    }

    /** 坐标优先用本页传来的（页面自己定位），其次用会话里保存的 GPS 上报 */
    @SuppressWarnings("unchecked")
    private double[] resolveCoords(HttpSession session, Double lat, Double lng) {
        if (lat != null && lng != null) {
            return new double[]{lat, lng};
        }
        Object attr = session.getAttribute("userLocation");
        if (attr instanceof Map) {
            Map<String, Object> loc = (Map<String, Object>) attr;
            Object a = loc.get("lat");
            Object b = loc.get("lng");
            if (a instanceof Number && b instanceof Number) {
                return new double[]{((Number) a).doubleValue(), ((Number) b).doubleValue()};
            }
        }
        return null;
    }

    private Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("status", "error");
        result.put("message", message);
        return result;
    }
}
