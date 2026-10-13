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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程库：把 AI 规划好的行程存下来、随时翻看、不要了就删。
 * <p>
 * 相比最初的"行程本"，这里多了三样东西：
 * <ul>
 *   <li>状态：规划中 / 进行中 / 已结束</li>
 *   <li>时间线节点：订单、预约、风险、备注、备选路线，统一放 trip_node</li>
 *   <li>版本快照：每次修改自动存一版，支持回退</li>
 * </ul>
 * 所有接口都以 session 里的登录用户为准，别人的行程查不到也删不掉。
 */
@RestController
public class TripController {

    private static final Logger log = LoggerFactory.getLogger(TripController.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 行程允许的状态，防止前端塞进乱七八糟的值 */
    private static final List<String> TRIP_STATUS = java.util.Arrays.asList("规划中", "进行中", "已结束");

    /** 节点类型：时间线 / 订单 / 预约 / 风险 / 备注 / 备选路线 */
    private static final List<String> NODE_TYPES = java.util.Arrays.asList(
            "timeline", "order", "booking", "risk", "note", "route");

    // ==================== 行程本体 ====================

    /**
     * 保存一条行程（同时自动生成第 1 个版本快照）
     */
    @PostMapping(value = "/trips/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(required = false) String title,
                                    @RequestParam(required = false) String content,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String visibility,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        if (content == null || content.trim().isEmpty()) {
            return fail(result, "行程内容为空，无法保存");
        }

        String safeTitle = safeTitle(title);
        String safeStatus = normalizeStatus(status);

        int tripId;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "insert into trip(username, title, content, status, create_time, update_time)"
                             + " values(?, ?, ?, ?, now(), now())",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, safeTitle);
            ps.setString(3, content);
            ps.setString(4, safeStatus);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                tripId = keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("保存行程失败", e);
            return fail(result, "行程保存失败，请稍后重试");
        }

        if (tripId > 0) {
            snapshot(tripId, username, "初始版本");
            // 可见权限：默认公开，可在保存后单独调整；好友主页按这个字段过滤
            MySQLUtil.executeUpdate(
                    "update trip set visibility = ? where id = ? and username = ?",
                    com.iflytek.chatgpt.social.Visibility.normalize(visibility), tripId, username);
        }

        result.put("status", "success");
        result.put("message", "已保存到行程库");
        result.put("id", tripId);
        return result;
    }

    /**
     * 修改一条行程：改完自动存一版快照，所以支持回退
     */
    @PostMapping(value = "/trips/update", produces = "application/json;charset=UTF-8")
    public Map<String, Object> update(@RequestParam(defaultValue = "0") int id,
                                      @RequestParam(required = false) String title,
                                      @RequestParam(required = false) String content,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String changeNote,
                                      HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }

        Map<String, Object> current = rawTrip(username, id);
        if (current == null) {
            return fail(result, "行程不存在");
        }

        String newTitle = title == null || title.trim().isEmpty()
                ? String.valueOf(current.get("title")) : safeTitle(title);
        String newContent = content == null || content.trim().isEmpty()
                ? String.valueOf(current.get("content")) : content;
        String newStatus = status == null || status.trim().isEmpty()
                ? String.valueOf(current.get("status")) : normalizeStatus(status);

        MySQLUtil.executeUpdate(
                "update trip set title = ?, content = ?, status = ?, update_time = now()"
                        + " where id = ? and username = ?",
                newTitle, newContent, newStatus, id, username);

        snapshot(id, username, changeNote == null || changeNote.trim().isEmpty()
                ? "自动保存" : changeNote.trim());

        result.put("status", "success");
        result.put("message", "已更新并保存版本");
        return result;
    }

    /**
     * 当前用户的行程列表（最新的排前面）
     */
    @PostMapping(value = "/trips/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }

        List<Map<String, Object>> trips = new ArrayList<>();
        String sql = "select id, title, status, create_time, update_time from trip"
                + " where username = ? order by id desc";

        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    trips.add(tripSummary(rs));
                }
            }
        } catch (SQLException e) {
            log.error("查询行程列表失败", e);
            return fail(result, "行程库读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("trips", trips);
        return result;
    }

    /**
     * 查看某条行程的完整内容（正文 + 全部节点）
     */
    @PostMapping(value = "/trips/get", produces = "application/json;charset=UTF-8")
    public Map<String, Object> get(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }

        Map<String, Object> trip = rawTrip(username, id);
        if (trip == null) {
            return fail(result, "行程不存在");
        }
        trip.put("nodes", listNodes(username, id, null));
        trip.put("versions", listVersions(username, id));

        result.put("status", "success");
        result.put("trip", trip);
        return result;
    }

    /**
     * 删除某条行程（连带它的节点和版本一起清掉）
     */
    @PostMapping(value = "/trips/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }

        int rows = MySQLUtil.executeUpdate("delete from trip where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "行程不存在或已被删除");
        }
        MySQLUtil.executeUpdate("delete from trip_node where trip_id = ? and username = ?", id, username);
        MySQLUtil.executeUpdate("delete from trip_version where trip_id = ? and username = ?", id, username);

        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    /**
     * 变更行程状态：规划中 / 进行中 / 已结束
     */
    @PostMapping(value = "/trips/status", produces = "application/json;charset=UTF-8")
    public Map<String, Object> setStatus(@RequestParam(defaultValue = "0") int id,
                                         @RequestParam(required = false) String status,
                                         HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        String safeStatus = normalizeStatus(status);
        int rows = MySQLUtil.executeUpdate(
                "update trip set status = ?, update_time = now() where id = ? and username = ?",
                safeStatus, id, username);
        if (rows <= 0) {
            return fail(result, "行程不存在");
        }
        result.put("status", "success");
        result.put("tripStatus", safeStatus);
        result.put("message", "行程状态已改为「" + safeStatus + "」");
        return result;
    }

    // ==================== 时间线节点（订单 / 预约 / 风险 / 备注 / 备选路线） ====================

    /**
     * 新增或更新一个行程节点。
     */
    @PostMapping(value = "/trips/node/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> saveNode(@RequestParam(defaultValue = "0") int id,
                                        @RequestParam(defaultValue = "0") int tripId,
                                        @RequestParam(required = false) String nodeType,
                                        @RequestParam(required = false) String title,
                                        @RequestParam(required = false) String detail,
                                        @RequestParam(required = false) String nodeTime,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Double cost,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        if (rawTrip(username, tripId) == null) {
            return fail(result, "行程不存在");
        }
        String type = nodeType == null || !NODE_TYPES.contains(nodeType.trim()) ? "note" : nodeType.trim();
        String nodeTitle = title == null || title.trim().isEmpty() ? "未命名节点" : trim(title, 120);

        if (id > 0) {
            MySQLUtil.executeUpdate(
                    "update trip_node set node_type = ?, title = ?, detail = ?, node_time = ?,"
                            + " status = ?, cost = ? where id = ? and username = ?",
                    type, nodeTitle, detail, nodeTime, status, cost, id, username);
        } else {
            MySQLUtil.executeUpdate(
                    "insert into trip_node(trip_id, username, node_type, title, detail, node_time,"
                            + " status, cost, create_time) values(?, ?, ?, ?, ?, ?, ?, ?, now())",
                    tripId, username, type, nodeTitle, detail, nodeTime, status, cost);
        }
        result.put("status", "success");
        result.put("message", "节点已保存");
        return result;
    }

    /**
     * 列出某行程的节点，可按类型过滤。
     */
    @PostMapping(value = "/trips/node/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> listNodesApi(@RequestParam(defaultValue = "0") int tripId,
                                            @RequestParam(required = false) String nodeType,
                                            HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        result.put("status", "success");
        result.put("nodes", listNodes(username, tripId, nodeType));
        return result;
    }

    /**
     * 删除一个节点
     */
    @PostMapping(value = "/trips/node/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> deleteNode(@RequestParam(defaultValue = "0") int id,
                                          HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        int rows = MySQLUtil.executeUpdate("delete from trip_node where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "节点不存在");
        }
        result.put("status", "success");
        result.put("message", "节点已删除");
        return result;
    }

    // ==================== 版本快照 ====================

    /**
     * 手动存一版快照
     */
    @PostMapping(value = "/trips/version/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> saveVersion(@RequestParam(defaultValue = "0") int tripId,
                                           @RequestParam(required = false) String changeNote,
                                           HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        if (rawTrip(username, tripId) == null) {
            return fail(result, "行程不存在");
        }
        int no = snapshot(tripId, username,
                changeNote == null || changeNote.trim().isEmpty() ? "手动保存" : changeNote.trim());
        result.put("status", "success");
        result.put("versionNo", no);
        result.put("message", "已保存版本 v" + no);
        return result;
    }

    /**
     * 版本历史列表
     */
    @PostMapping(value = "/trips/version/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> listVersionsApi(@RequestParam(defaultValue = "0") int tripId,
                                               HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        result.put("status", "success");
        result.put("versions", listVersions(username, tripId));
        return result;
    }

    /**
     * 回退到指定版本：回退前先自动备份当前状态，回退后再记一版，历史永不丢。
     */
    @PostMapping(value = "/trips/version/rollback", produces = "application/json;charset=UTF-8")
    public Map<String, Object> rollback(@RequestParam(defaultValue = "0") int versionId,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }

        Map<String, Object> version = null;
        String sql = "select id, trip_id, version_no, title, content from trip_version where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, versionId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    version = new LinkedHashMap<>();
                    version.put("tripId", rs.getInt("trip_id"));
                    version.put("versionNo", rs.getInt("version_no"));
                    version.put("title", rs.getString("title"));
                    version.put("content", rs.getString("content"));
                }
            }
        } catch (SQLException e) {
            log.error("读取版本失败", e);
            return fail(result, "版本读取失败");
        }
        if (version == null) {
            return fail(result, "版本不存在");
        }

        int tripId = (int) version.get("tripId");
        int sourceNo = (int) version.get("versionNo");

        // 1) 回退前先备份当前状态，避免"回退把最新内容弄丢"
        snapshot(tripId, username, "回退前自动备份");
        // 2) 应用目标版本内容
        MySQLUtil.executeUpdate(
                "update trip set title = ?, content = ?, update_time = now() where id = ? and username = ?",
                version.get("title"), version.get("content"), tripId, username);
        // 3) 记一版，注明回退来源
        int no = snapshot(tripId, username, "回退到版本 v" + sourceNo);

        result.put("status", "success");
        result.put("versionNo", no);
        result.put("message", "已回退到 v" + sourceNo + "（回退前已自动备份）");
        return result;
    }

    // ==================== 内部工具方法 ====================

    /** 生成一版快照，返回新版本号 */
    private int snapshot(int tripId, String username, String changeNote) {
        Map<String, Object> trip = rawTrip(username, tripId);
        if (trip == null) {
            return 0;
        }
        int nextNo = 1;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select coalesce(max(version_no), 0) + 1 from trip_version where trip_id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    nextNo = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.warn("计算版本号失败：{}", e.getMessage());
        }
        MySQLUtil.executeUpdate(
                "insert into trip_version(trip_id, username, version_no, title, content, change_note, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, now())",
                tripId, username, nextNo, trip.get("title"), trip.get("content"), trim(changeNote, 255));
        return nextNo;
    }

    private List<Map<String, Object>> listVersions(String username, int tripId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "select id, version_no, title, change_note, create_time from trip_version"
                + " where trip_id = ? and username = ? order by version_no desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("title", rs.getString("title"));
                    m.put("changeNote", rs.getString("change_note"));
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取版本列表失败：{}", e.getMessage());
        }
        return list;
    }

    private List<Map<String, Object>> listNodes(String username, int tripId, String nodeType) {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean filter = nodeType != null && !nodeType.trim().isEmpty();
        String sql = "select id, node_type, title, detail, node_time, status, cost, create_time"
                + " from trip_node where trip_id = ? and username = ?"
                + (filter ? " and node_type = ?" : "") + " order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            if (filter) {
                ps.setString(3, nodeType.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("nodeType", rs.getString("node_type"));
                    m.put("title", rs.getString("title"));
                    m.put("detail", rs.getString("detail"));
                    m.put("nodeTime", rs.getString("node_time"));
                    m.put("status", rs.getString("status"));
                    m.put("cost", rs.getBigDecimal("cost"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取节点失败：{}", e.getMessage());
        }
        return list;
    }

    private Map<String, Object> rawTrip(String username, int id) {
        String sql = "select id, title, content, status, create_time, update_time from trip"
                + " where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("title", rs.getString("title"));
                    m.put("content", rs.getString("content"));
                    m.put("status", rs.getString("status"));
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    m.put("updateTime", formatTime(rs.getTimestamp("update_time")));
                    return m;
                }
            }
        } catch (SQLException e) {
            log.warn("读取行程失败：{}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> tripSummary(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("title", rs.getString("title"));
        m.put("status", rs.getString("status"));
        m.put("createTime", formatTime(rs.getTimestamp("create_time")));
        m.put("updateTime", formatTime(rs.getTimestamp("update_time")));
        return m;
    }

    private String formatTime(java.sql.Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT);
    }

    private String safeTitle(String title) {
        return title == null || title.trim().isEmpty() ? "未命名行程" : trim(title, 120);
    }

    private String normalizeStatus(String status) {
        String s = status == null ? "" : status.trim();
        return TRIP_STATUS.contains(s) ? s : "规划中";
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
