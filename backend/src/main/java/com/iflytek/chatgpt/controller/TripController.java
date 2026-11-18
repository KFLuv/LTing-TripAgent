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
    private static final ObjectMapper JSON = new ObjectMapper();

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
                                      @RequestParam(defaultValue = "-1") int expectedVersion,
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
        if (versionConflict(current, expectedVersion)) {
            return conflict(result, current);
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
        String sql = "select id, title, status, version, create_time, update_time from trip"
                + " where username = ? and (plan_data is null or plan_step >= 3) order by id desc";

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
                                        @RequestParam(required = false) String photo,
                                        @RequestParam(required = false) Double lat,
                                        @RequestParam(required = false) Double lng,
                                        @RequestParam(defaultValue = "-1") int expectedVersion,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        Map<String, Object> parent = rawTrip(username, tripId);
        if (parent == null) {
            return fail(result, "行程不存在");
        }
        if (versionConflict(parent, expectedVersion)) {
            return conflict(result, parent);
        }
        String type = nodeType == null || !NODE_TYPES.contains(nodeType.trim()) ? "note" : nodeType.trim();
        String nodeTitle = title == null || title.trim().isEmpty() ? "未命名节点" : trim(title, 120);

        if (id > 0) {
            MySQLUtil.executeUpdate(
                    "update trip_node set node_type = ?, title = ?, detail = ?, node_time = ?,"
                            + " status = ?, cost = ?, photo = ?, lat = ?, lng = ? where id = ? and username = ?",
                    type, nodeTitle, detail, nodeTime, status, cost, photo, lat, lng, id, username);
        } else {
            int sortOrder = 1;
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "select coalesce(max(sort_order), 0) + 1 from trip_node where trip_id = ? and username = ?")) {
                ps.setInt(1, tripId);
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) sortOrder = rs.getInt(1);
                }
            } catch (SQLException e) {
                log.warn("计算节点排序失败：{}", e.getMessage());
            }
            MySQLUtil.executeUpdate(
                    "insert into trip_node(trip_id, username, node_type, title, detail, node_time,"
                            + " status, cost, photo, lat, lng, sort_order, create_time)"
                            + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                    tripId, username, type, nodeTitle, detail, nodeTime, status, cost, photo, lat, lng, sortOrder);
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
                                          @RequestParam(defaultValue = "-1") int expectedVersion,
                                          HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }
        int tripId = 0;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select trip_id from trip_node where id = ? and username = ?")) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) tripId = rs.getInt("trip_id");
            }
        } catch (SQLException e) {
            return fail(result, "节点读取失败");
        }
        Map<String, Object> parent = rawTrip(username, tripId);
        if (parent == null) return fail(result, "行程不存在");
        if (versionConflict(parent, expectedVersion)) return conflict(result, parent);
        int rows = MySQLUtil.executeUpdate("delete from trip_node where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "节点不存在");
        }
        result.put("status", "success");
        result.put("message", "节点已删除");
        return result;
    }

    @PostMapping(value = "/trips/node/reorder", produces = "application/json;charset=UTF-8")
    public Map<String, Object> reorderNodes(@RequestParam String ids,
                                            @RequestParam(defaultValue = "-1") int expectedVersion,
                                            HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(result, "请先登录后再使用行程库");
        String[] parts = ids == null ? new String[0] : ids.split(",");
        if (parts.length == 0) return fail(result, "节点顺序为空");
        int tripId = 0;
        try {
            tripId = Integer.parseInt(parts[0].trim());
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "select trip_id from trip_node where id = ? and username = ?")) {
                ps.setInt(1, tripId);
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) tripId = rs.getInt("trip_id");
                    else return fail(result, "节点不存在");
                }
            }
        } catch (Exception e) {
            return fail(result, "节点参数无效");
        }
        Map<String, Object> parent = rawTrip(username, tripId);
        if (parent == null) return fail(result, "行程不存在");
        if (versionConflict(parent, expectedVersion)) return conflict(result, parent);
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "update trip_node set sort_order = ? where id = ? and trip_id = ? and username = ?")) {
            conn.setAutoCommit(false);
            try {
                for (int i = 0; i < parts.length; i++) {
                    ps.setInt(1, i + 1);
                    ps.setInt(2, Integer.parseInt(parts[i].trim()));
                    ps.setInt(3, tripId);
                    ps.setString(4, username);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.warn("节点排序失败：{}", e.getMessage());
            return fail(result, "节点排序失败");
        }
        result.put("status", "success");
        result.put("message", "顺序已更新");
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
                                        @RequestParam(defaultValue = "-1") int expectedVersion,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用行程库");
        }

        Map<String, Object> version = null;
        String sql = "select id, trip_id, version_no, title, content, nodes_json, candidates_json,"
                + " plan_data, plan_revision"
                + " from trip_version where id = ? and username = ?";
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
                    version.put("nodesJson", rs.getString("nodes_json"));
                    version.put("candidatesJson", rs.getString("candidates_json"));
                    version.put("planData", rs.getString("plan_data"));
                    version.put("planRevision", rs.getInt("plan_revision"));
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
        Map<String, Object> parent = rawTrip(username, tripId);
        if (parent == null) return fail(result, "行程不存在");
        if (versionConflict(parent, expectedVersion)) return conflict(result, parent);

        // 1) 回退前先备份当前状态，避免"回退把最新内容弄丢"
        snapshot(tripId, username, "回退前自动备份");
        // 2) 应用目标版本内容
        String targetPlanData = (String) version.get("planData");
        boolean planRestored = targetPlanData != null && !targetPlanData.trim().isEmpty();
        if (planRestored) {
            MySQLUtil.executeUpdate(
                    "update trip set title = ?, content = ?, plan_data = ?,"
                            + " plan_revision = plan_revision + 1, update_time = now()"
                            + " where id = ? and username = ?",
                    version.get("title"), version.get("content"), targetPlanData, tripId, username);
        } else {
            MySQLUtil.executeUpdate(
                    "update trip set title = ?, content = ?, update_time = now() where id = ? and username = ?",
                    version.get("title"), version.get("content"), tripId, username);
        }
        boolean nodesRestored = restoreTripNodes(username, tripId, (String) version.get("nodesJson"));
        boolean candidatesRestored = restoreTripCandidates(
                username, tripId, (String) version.get("candidatesJson"));
        // 3) 记一版，注明回退来源
        int no = snapshot(tripId, username, "回退到版本 v" + sourceNo);

        result.put("status", "success");
        result.put("versionNo", no);
        result.put("planRestored", planRestored);
        result.put("candidatesRestored", candidatesRestored);
        result.put("message", "已回退到 v" + sourceNo
                + (planRestored ? "（正文、节点与规划草稿已恢复" : "（正文已恢复")
                + (nodesRestored ? "，回退前已自动备份）" : "；该旧版本没有节点快照）"));
        return result;
    }

    @PostMapping(value = "/trips/version/diff", produces = "application/json;charset=UTF-8")
    public Map<String, Object> versionDiff(@RequestParam(defaultValue = "0") int fromVersionId,
                                           @RequestParam(defaultValue = "0") int toVersionId,
                                           HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(result, "请先登录后再使用行程库");
        Map<String, Object> before = loadVersion(username, fromVersionId);
        Map<String, Object> after = loadVersion(username, toVersionId);
        if (before == null || after == null) return fail(result, "版本不存在");
        if (!String.valueOf(before.get("tripId")).equals(String.valueOf(after.get("tripId")))) {
            return fail(result, "只能比较同一行程的版本");
        }
        try {
            JsonNode beforeNodes = JSON.readTree(String.valueOf(before.get("nodesJson")));
            JsonNode afterNodes = JSON.readTree(String.valueOf(after.get("nodesJson")));
            Map<String, String> oldByKey = nodeMap(beforeNodes);
            Map<String, String> newByKey = nodeMap(afterNodes);
            int added = 0, removed = 0, changed = 0;
            for (String key : newByKey.keySet()) {
                if (!oldByKey.containsKey(key)) added++;
                else if (!oldByKey.get(key).equals(newByKey.get(key))) changed++;
            }
            for (String key : oldByKey.keySet()) if (!newByKey.containsKey(key)) removed++;
            List<String> parts = new ArrayList<>();
            if (added > 0) parts.add("新增 " + added + " 个节点");
            if (removed > 0) parts.add("删除 " + removed + " 个节点");
            if (changed > 0) parts.add("修改 " + changed + " 个节点");
            if (!String.valueOf(before.get("content")).equals(String.valueOf(after.get("content")))) {
                parts.add("行程正文有更新");
            }
            if (!String.valueOf(before.get("planData")).equals(String.valueOf(after.get("planData")))) {
                parts.add("规划草稿有更新");
            }
            result.put("status", "success");
            result.put("fromVersion", before.get("versionNo"));
            result.put("toVersion", after.get("versionNo"));
            result.put("added", added);
            result.put("removed", removed);
            result.put("changed", changed);
            result.put("summary", parts.isEmpty() ? "两个版本内容一致" : String.join("，", parts));
            return result;
        } catch (Exception e) {
            log.warn("版本差异计算失败：{}", e.getMessage());
            return fail(result, "版本差异计算失败");
        }
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
                "insert into trip_version(trip_id, username, version_no, title, content, nodes_json,"
                        + " candidates_json, plan_data, plan_revision, change_note, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                tripId, username, nextNo, trip.get("title"), trip.get("content"),
                tripNodesJson(username, tripId), tripCandidatesJson(username, tripId),
                trip.get("planData"), trip.get("planRevision"),
                trim(changeNote, 255));
        MySQLUtil.executeUpdate(
                "update trip set version = ?, update_time = now() where id = ? and username = ?",
                nextNo, tripId, username);
        return nextNo;
    }

    private String tripNodesJson(String username, int tripId) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        String sql = "select node_type, title, detail, node_time, status, cost, photo, lat, lng, sort_order"
                + " from trip_node where trip_id = ? and username = ? order by sort_order, id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("nodeType", rs.getString("node_type"));
                    node.put("title", rs.getString("title"));
                    node.put("detail", rs.getString("detail"));
                    node.put("nodeTime", rs.getString("node_time"));
                    node.put("status", rs.getString("status"));
                    node.put("cost", rs.getBigDecimal("cost"));
                    node.put("photo", rs.getString("photo"));
                    node.put("lat", rs.getObject("lat"));
                    node.put("lng", rs.getObject("lng"));
                    node.put("sortOrder", rs.getInt("sort_order"));
                    nodes.add(node);
                }
            }
            return JSON.writeValueAsString(nodes);
        } catch (Exception e) {
            throw new RuntimeException("生成行程节点快照失败：" + e.getMessage(), e);
        }
    }

    private String tripCandidatesJson(String username, int tripId) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        String sql = "select poi_id, name, type, city, address, lat, lng, duration_min, cost, evidence, photo"
                + " from trip_candidate where trip_id = ? and username = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("poiId", rs.getString("poi_id"));
                    item.put("name", rs.getString("name"));
                    item.put("type", rs.getString("type"));
                    item.put("city", rs.getString("city"));
                    item.put("address", rs.getString("address"));
                    item.put("lat", rs.getObject("lat"));
                    item.put("lng", rs.getObject("lng"));
                    item.put("durationMin", rs.getInt("duration_min"));
                    item.put("cost", rs.getString("cost"));
                    item.put("evidence", rs.getString("evidence"));
                    item.put("photo", rs.getString("photo"));
                    candidates.add(item);
                }
            }
            return JSON.writeValueAsString(candidates);
        } catch (Exception e) {
            throw new RuntimeException("生成候选池快照失败：" + e.getMessage(), e);
        }
    }

    private boolean restoreTripNodes(String username, int tripId, String nodesJson) {
        if (nodesJson == null || nodesJson.trim().isEmpty()) {
            return false;
        }
        try {
            JsonNode nodes = JSON.readTree(nodesJson);
            if (!nodes.isArray()) {
                return false;
            }
            try (Connection conn = MySQLUtil.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(
                            "delete from trip_node where trip_id = ? and username = ?")) {
                        del.setInt(1, tripId);
                        del.setString(2, username);
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = conn.prepareStatement(
                            "insert into trip_node(trip_id, username, node_type, title, detail, node_time, status, cost, photo, lat, lng, sort_order, create_time)"
                                    + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
                        for (JsonNode node : nodes) {
                            ins.setInt(1, tripId);
                            ins.setString(2, username);
                            ins.setString(3, node.path("nodeType").asText("note"));
                            ins.setString(4, node.path("title").asText(""));
                            ins.setString(5, node.path("detail").asText(""));
                            ins.setString(6, node.path("nodeTime").asText(""));
                            ins.setString(7, node.path("status").asText(""));
                            if (node.path("cost").isNumber()) {
                                ins.setBigDecimal(8, node.path("cost").decimalValue());
                            } else {
                                ins.setBigDecimal(8, null);
                            }
                            ins.setString(9, node.path("photo").asText(null));
                            if (node.path("lat").isNumber()) ins.setDouble(10, node.path("lat").asDouble());
                            else ins.setNull(10, java.sql.Types.DOUBLE);
                            if (node.path("lng").isNumber()) ins.setDouble(11, node.path("lng").asDouble());
                            else ins.setNull(11, java.sql.Types.DOUBLE);
                            ins.setInt(12, node.path("sortOrder").asInt(0));
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                    conn.commit();
                    return true;
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("恢复行程节点失败：" + e.getMessage(), e);
        }
    }

    private boolean restoreTripCandidates(String username, int tripId, String candidatesJson) {
        if (candidatesJson == null || candidatesJson.trim().isEmpty()) return false;
        try {
            JsonNode candidates = JSON.readTree(candidatesJson);
            if (!candidates.isArray()) return false;
            try (Connection conn = MySQLUtil.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(
                            "delete from trip_candidate where trip_id = ? and username = ?")) {
                        del.setInt(1, tripId);
                        del.setString(2, username);
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = conn.prepareStatement(
                            "insert into trip_candidate(username, trip_id, poi_id, name, type, city,"
                                    + " address, lat, lng, duration_min, cost, evidence, photo, create_time)"
                                    + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
                        for (JsonNode item : candidates) {
                            ins.setString(1, username);
                            ins.setInt(2, tripId);
                            ins.setString(3, item.path("poiId").asText(null));
                            ins.setString(4, item.path("name").asText(""));
                            ins.setString(5, item.path("type").asText(null));
                            ins.setString(6, item.path("city").asText(null));
                            ins.setString(7, item.path("address").asText(null));
                            if (item.path("lat").isNumber()) ins.setDouble(8, item.path("lat").asDouble());
                            else ins.setNull(8, java.sql.Types.DOUBLE);
                            if (item.path("lng").isNumber()) ins.setDouble(9, item.path("lng").asDouble());
                            else ins.setNull(9, java.sql.Types.DOUBLE);
                            ins.setInt(10, item.path("durationMin").asInt(0));
                            ins.setString(11, item.path("cost").asText(null));
                            ins.setString(12, item.path("evidence").asText(null));
                            ins.setString(13, item.path("photo").asText(null));
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                    conn.commit();
                    return true;
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("恢复候选池失败：" + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> listVersions(String username, int tripId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "select id, version_no, title, change_note, create_time, plan_data, plan_revision"
                + " from trip_version"
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
                    String planData = rs.getString("plan_data");
                    m.put("hasPlanData", planData != null && !planData.trim().isEmpty());
                    m.put("planRevision", rs.getInt("plan_revision"));
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
        String sql = "select id, node_type, title, detail, node_time, status, cost, photo, lat, lng, sort_order, create_time"
                + " from trip_node where trip_id = ? and username = ?"
                + (filter ? " and node_type = ?" : "") + " order by sort_order, id";
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
                    m.put("photo", rs.getString("photo"));
                    m.put("lat", rs.getObject("lat"));
                    m.put("lng", rs.getObject("lng"));
                    m.put("sortOrder", rs.getInt("sort_order"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取节点失败：{}", e.getMessage());
        }
        return list;
    }

    private Map<String, Object> rawTrip(String username, int id) {
        String sql = "select id, title, content, status, version, plan_data, plan_revision,"
                + " create_time, update_time from trip"
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
                    m.put("version", rs.getInt("version"));
                    m.put("planData", rs.getString("plan_data"));
                    m.put("planRevision", rs.getInt("plan_revision"));
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
        m.put("version", rs.getInt("version"));
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

    private boolean versionConflict(Map<String, Object> trip, int expectedVersion) {
        if (expectedVersion < 0) return false;
        int currentVersion = ((Number) trip.getOrDefault("version", 0)).intValue();
        return expectedVersion != currentVersion;
    }

    private Map<String, Object> conflict(Map<String, Object> out, Map<String, Object> current) {
        out.put("status", "conflict");
        out.put("version", current.get("version"));
        out.put("message", "行程已在其他页面更新，请刷新后再修改");
        return out;
    }

    private Map<String, Object> loadVersion(String username, int versionId) {
        String sql = "select id, trip_id, version_no, title, content, nodes_json, candidates_json,"
                + " plan_data, plan_revision"
                + " from trip_version where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, versionId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("tripId", rs.getInt("trip_id"));
                m.put("versionNo", rs.getInt("version_no"));
                m.put("title", rs.getString("title"));
                m.put("content", rs.getString("content"));
                String nodes = rs.getString("nodes_json");
                m.put("nodesJson", nodes == null || nodes.trim().isEmpty() ? "[]" : nodes);
                m.put("candidatesJson", rs.getString("candidates_json"));
                m.put("planData", rs.getString("plan_data"));
                m.put("planRevision", rs.getInt("plan_revision"));
                return m;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private Map<String, String> nodeMap(JsonNode nodes) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        if (nodes == null || !nodes.isArray()) return map;
        for (JsonNode n : nodes) {
            String key = n.path("title").asText("") + "|" + n.path("nodeTime").asText("");
            map.put(key, JSON.writeValueAsString(n));
        }
        return map;
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
