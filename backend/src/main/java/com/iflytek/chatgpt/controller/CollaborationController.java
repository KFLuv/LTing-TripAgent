package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多人协作：邀请码加入、节点投票、冲突汇总、决策和活动记录。
 */
@RestController
public class CollaborationController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> VOTES = Arrays.asList("must", "can", "skip");
    private static final String[] SLOTS = {"morning", "afternoon", "evening"};
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @PostMapping(value = "/collaboration/my-trips", produces = "application/json;charset=UTF-8")
    public Map<String, Object> myTrips(HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        Map<Integer, Map<String, Object>> trips = new LinkedHashMap<>();
        try (Connection conn = MySQLUtil.getConnection()) {
            loadMyTrips(conn, trips, username,
                    "select id, title, destination from trip where username = ? order by update_time desc",
                    true);
            loadMyTrips(conn, trips, username,
                    "select t.id, t.title, t.destination from trip t join trip_collaborator c"
                            + " on c.trip_id = t.id where c.username = ? and c.status = 'active'"
                            + " order by t.update_time desc",
                    false);
        } catch (Exception e) {
            return fail(out, "协作行程读取失败");
        }
        out.put("status", "success");
        out.put("trips", new ArrayList<>(trips.values()));
        return out;
    }

    private void loadMyTrips(Connection conn, Map<Integer, Map<String, Object>> trips,
                             String username, String sql, boolean owner) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> trip = new LinkedHashMap<>();
                    trip.put("id", rs.getInt("id"));
                    trip.put("title", rs.getString("title"));
                    trip.put("destination", rs.getString("destination"));
                    trip.put("role", owner ? "owner" : "collaborator");
                    trips.put(rs.getInt("id"), trip);
                }
            }
        }
    }

    @PostMapping(value = "/collaboration/info", produces = "application/json;charset=UTF-8")
    public Map<String, Object> info(@RequestParam int tripId, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        try (Connection conn = MySQLUtil.getConnection()) {
            TripAccess access = access(conn, tripId, username);
            if (access == null) return fail(out, "行程不存在或无权访问");
            String inviteCode = ownerInviteCode(conn, access.owner);
            List<Map<String, Object>> members = members(conn, tripId, access.owner);
            List<Map<String, Object>> nodes = nodes(access.planData);
            List<Map<String, Object>> votes = votes(conn, tripId);
            List<Map<String, Object>> conflicts = conflicts(nodes, votes);
            out.put("status", "success");
            out.put("tripId", tripId);
            out.put("owner", access.owner);
            out.put("currentUser", username);
            out.put("role", username.equals(access.owner) ? "owner" : "collaborator");
            out.put("inviteCode", inviteCode);
            out.put("members", members);
            out.put("nodes", nodes);
            out.put("votes", votes);
            out.put("conflicts", conflicts);
            out.put("proposals", proposals(conflicts));
            out.put("activity", activity(conn, tripId));
            return out;
        } catch (Exception e) {
            return fail(out, "协作信息读取失败");
        }
    }

    @PostMapping(value = "/collaboration/join", produces = "application/json;charset=UTF-8")
    public Map<String, Object> join(@RequestParam int tripId,
                                    @RequestParam String inviteCode,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        try (Connection conn = MySQLUtil.getConnection()) {
            TripAccess access = access(conn, tripId, null);
            if (access == null) return fail(out, "行程不存在");
            if (username.equals(access.owner)) return fail(out, "你已经是行程发起人");
            String expected = ownerInviteCode(conn, access.owner);
            if (inviteCode == null || !expected.equalsIgnoreCase(inviteCode.trim())) {
                return fail(out, "邀请码不正确");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "insert into trip_collaborator(trip_id, owner, username, role, status, joined_at)"
                            + " values(?, ?, ?, 'editor', 'active', now())"
                            + " on duplicate key update status = 'active', joined_at = now()")) {
                ps.setInt(1, tripId);
                ps.setString(2, access.owner);
                ps.setString(3, username);
                ps.executeUpdate();
            }
            logActivity(conn, tripId, username, "JOIN", "通过邀请码加入行程");
            out.put("status", "success");
            out.put("tripId", tripId);
            out.put("message", "已加入协作行程");
            return out;
        } catch (Exception e) {
            return fail(out, "加入协作行程失败");
        }
    }

    @PostMapping(value = "/collaboration/vote", produces = "application/json;charset=UTF-8")
    public Map<String, Object> vote(@RequestParam int tripId,
                                    @RequestParam String nodeKey,
                                    @RequestParam String vote,
                                    @RequestParam(required = false) String note,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        if (!VOTES.contains(vote)) return fail(out, "投票只能是 must/can/skip");
        try (Connection conn = MySQLUtil.getConnection()) {
            TripAccess access = access(conn, tripId, username);
            if (access == null) return fail(out, "行程不存在或无权访问");
            List<Map<String, Object>> nodes = nodes(access.planData);
            Map<String, Object> node = nodes.stream()
                    .filter(item -> nodeKey.equals(item.get("nodeKey")))
                    .findFirst().orElse(null);
            if (node == null) return fail(out, "节点不存在");
            try (PreparedStatement ps = conn.prepareStatement(
                    "insert into trip_vote(trip_id, username, node_key, vote, note, update_time)"
                            + " values(?, ?, ?, ?, ?, ?)"
                            + " on duplicate key update vote = ?, note = ?, update_time = ?")) {
                ps.setInt(1, tripId);
                ps.setString(2, username);
                ps.setString(3, nodeKey);
                ps.setString(4, vote);
                ps.setString(5, trim(note, 500));
                ps.setTimestamp(6, java.sql.Timestamp.valueOf(LocalDateTime.now()));
                ps.setString(7, vote);
                ps.setString(8, trim(note, 500));
                ps.setTimestamp(9, java.sql.Timestamp.valueOf(LocalDateTime.now()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "update trip_collaborator set last_action_at = now()"
                            + " where trip_id = ? and username = ?")) {
                ps.setInt(1, tripId);
                ps.setString(2, username);
                ps.executeUpdate();
            }
            logActivity(conn, tripId, username, "VOTE",
                    node.get("name") + " → " + vote + (note == null ? "" : "：" + note));
            out.put("status", "success");
            out.put("message", "投票已记录");
            return out;
        } catch (Exception e) {
            return fail(out, "投票保存失败");
        }
    }

    @PostMapping(value = "/collaboration/decision", produces = "application/json;charset=UTF-8")
    public Map<String, Object> decision(@RequestParam int tripId,
                                        @RequestParam String nodeKey,
                                        @RequestParam String decision,
                                        @RequestParam(required = false) String note,
                                        HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        try (Connection conn = MySQLUtil.getConnection()) {
            TripAccess access = access(conn, tripId, username);
            if (access == null) return fail(out, "行程不存在或无权访问");
            if (!username.equals(access.owner)) return fail(out, "只有行程发起人可以确认冲突决策");
            logActivity(conn, tripId, username, "DECISION",
                    nodeKey + " → " + decision + (note == null ? "" : "：" + note));
            out.put("status", "success");
            out.put("message", "决策已记录");
            return out;
        } catch (Exception e) {
            return fail(out, "决策记录失败");
        }
    }

    private TripAccess access(Connection conn, int tripId, String username) throws Exception {
        String sql = "select id, username, plan_data from trip where id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String owner = rs.getString("username");
                if (username != null && !username.equals(owner)
                        && !isCollaborator(conn, tripId, username)) return null;
                TripAccess access = new TripAccess();
                access.tripId = tripId;
                access.owner = owner;
                access.planData = rs.getString("plan_data");
                return access;
            }
        }
    }

    private boolean isCollaborator(Connection conn, int tripId, String username) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "select 1 from trip_collaborator where trip_id = ? and username = ? and status = 'active'")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String ownerInviteCode(Connection conn, String owner) throws Exception {
        String code = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "select invite_code from user_profile where username = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) code = rs.getString(1);
            }
        }
        if (code == null || code.trim().isEmpty()) {
            code = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            MySQLUtil.executeUpdate(
                    "insert into user_profile(username, invite_code, update_time)"
                            + " values(?, ?, now())"
                            + " on duplicate key update invite_code = ?, update_time = now()",
                    owner, code, code);
        }
        return code;
    }

    private List<Map<String, Object>> members(Connection conn, int tripId, String owner) throws Exception {
        List<Map<String, Object>> members = new ArrayList<>();
        Map<String, Object> ownerRow = new LinkedHashMap<>();
        ownerRow.put("username", owner);
        ownerRow.put("role", "owner");
        ownerRow.put("status", "active");
        members.add(ownerRow);
        try (PreparedStatement ps = conn.prepareStatement(
                "select username, role, status, joined_at, last_action_at"
                        + " from trip_collaborator where trip_id = ? and status = 'active' order by joined_at")) {
            ps.setInt(1, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("username", rs.getString("username"));
                    row.put("role", rs.getString("role"));
                    row.put("status", rs.getString("status"));
                    row.put("joinedAt", format(rs.getTimestamp("joined_at")));
                    row.put("lastActionAt", format(rs.getTimestamp("last_action_at")));
                    members.add(row);
                }
            }
        }
        return members;
    }

    private List<Map<String, Object>> nodes(String planData) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (planData == null) return nodes;
        try {
            JsonNode root = JSON.readTree(planData);
            JsonNode itinerary = root.path("itinerary");
            for (int day = 0; day < itinerary.size(); day++) {
                for (String slot : SLOTS) {
                    for (JsonNode node : itinerary.get(day).path(slot)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        String nodeKey = node.path("name").asText("") + "|"
                                + node.path("address").asText("") + "|"
                                + node.path("lat").asText("") + "|"
                                + node.path("lng").asText("");
                        row.put("nodeKey", nodeKey);
                        row.put("name", node.path("name").asText("未命名地点"));
                        row.put("day", day + 1);
                        row.put("slot", slot);
                        nodes.add(row);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return nodes;
    }

    private List<Map<String, Object>> votes(Connection conn, int tripId) throws Exception {
        List<Map<String, Object>> votes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "select username, node_key, vote, note, update_time from trip_vote"
                        + " where trip_id = ? order by update_time")) {
            ps.setInt(1, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("username", rs.getString("username"));
                    row.put("nodeKey", rs.getString("node_key"));
                    row.put("vote", rs.getString("vote"));
                    row.put("note", rs.getString("note"));
                    row.put("updateTime", format(rs.getTimestamp("update_time")));
                    votes.add(row);
                }
            }
        }
        return votes;
    }

    private List<Map<String, Object>> conflicts(List<Map<String, Object>> nodes,
                                                List<Map<String, Object>> votes) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String key = String.valueOf(node.get("nodeKey"));
            List<String> must = new ArrayList<>();
            List<String> skip = new ArrayList<>();
            List<String> can = new ArrayList<>();
            for (Map<String, Object> vote : votes) {
                if (!key.equals(vote.get("nodeKey"))) continue;
                String value = String.valueOf(vote.get("vote"));
                if ("must".equals(value)) must.add(String.valueOf(vote.get("username")));
                else if ("skip".equals(value)) skip.add(String.valueOf(vote.get("username")));
                else can.add(String.valueOf(vote.get("username")));
            }
            if (!must.isEmpty() && !skip.isEmpty()) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("nodeKey", key);
                conflict.put("name", node.get("name"));
                conflict.put("must", must);
                conflict.put("skip", skip);
                conflict.put("can", can);
                conflicts.add(conflict);
            }
        }
        return conflicts;
    }

    private List<Map<String, Object>> proposals(List<Map<String, Object>> conflicts) {
        List<Map<String, Object>> proposals = new ArrayList<>();
        for (Map<String, Object> conflict : conflicts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeKey", conflict.get("nodeKey"));
            item.put("name", conflict.get("name"));
            List<Map<String, Object>> options = new ArrayList<>();
            options.add(option("保留节点", "满足必去人群", "不去人群需要妥协", conflict.get("must")));
            options.add(option("移除节点", "满足不去人群", "必去人群需要妥协", conflict.get("skip")));
            options.add(option("改为自由活动", "双方不强制绑定", "团队不再统一行动", conflict.get("can")));
            item.put("options", options);
            proposals.add(item);
        }
        return proposals;
    }

    private Map<String, Object> option(String title, String satisfies, String tradeoff,
                                       Object supporters) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", title);
        option.put("satisfies", satisfies);
        option.put("tradeoff", tradeoff);
        option.put("supporters", supporters);
        return option;
    }

    private List<Map<String, Object>> activity(Connection conn, int tripId) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "select username, action, detail, create_time from trip_activity"
                        + " where trip_id = ? order by id desc limit 50")) {
            ps.setInt(1, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("username", rs.getString("username"));
                    row.put("action", rs.getString("action"));
                    row.put("detail", rs.getString("detail"));
                    row.put("createTime", format(rs.getTimestamp("create_time")));
                    items.add(row);
                }
            }
        }
        return items;
    }

    private void logActivity(Connection conn, int tripId, String username,
                             String action, String detail) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into trip_activity(trip_id, username, action, detail, create_time)"
                        + " values(?, ?, ?, ?, now())")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            ps.setString(3, action);
            ps.setString(4, trim(detail, 1000));
            ps.executeUpdate();
        }
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String format(java.sql.Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDateTime().format(TIME_FORMAT);
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }

    private static final class TripAccess {
        private int tripId;
        private String owner;
        private String planData;
    }
}
