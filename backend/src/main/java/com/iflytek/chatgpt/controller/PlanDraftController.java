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
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出行工作台草稿：保存刷新、退出后继续所需的条件与界面状态。
 */
@RestController
public class PlanDraftController {

    private static final Logger log = LoggerFactory.getLogger(PlanDraftController.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @PostMapping(value = "/plan/draft/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(defaultValue = "0") int tripId,
                                    @RequestParam(required = false) String title,
                                    @RequestParam(required = false) String destination,
                                    @RequestParam(required = false) String destinationLabel,
                                    @RequestParam(required = false) String origin,
                                    @RequestParam(required = false) String start,
                                    @RequestParam(required = false) String end,
                                    @RequestParam(defaultValue = "1") int days,
                                    @RequestParam(required = false) String budget,
                                    @RequestParam(required = false) String people,
                                    @RequestParam(required = false) String pace,
                                    @RequestParam(defaultValue = "1") int planStep,
                                    @RequestParam(required = false) String planData,
                                    HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录后再保存规划");

        String safeTitle = trim(title, 120);
        if (safeTitle == null || safeTitle.isEmpty()) {
            safeTitle = (trim(destination, 100) == null || destination.trim().isEmpty() ? "未命名规划" : destination.trim())
                    + "旅行规划";
        }
        String safeOrigin = trim(origin, 100);
        String safeLabel = trim(destinationLabel, 120);
        String safeBudget = trim(budget, 100);
        String safePeople = trim(people, 50);
        String safePace = trim(pace, 20);
        String compactPlan = trim(planData, 200000);

        try {
            if (tripId > 0) {
                if (!ownsTrip(username, tripId)) return fail(out, "规划不存在或无权修改");
                MySQLUtil.executeUpdate(
                        "update trip set title = ?, destination = ?, start_date = ?, end_date = ?, days = ?,"
                                + " budget = ?, people = ?, pace = ?, plan_step = ?, plan_data = ?,"
                                + " plan_revision = plan_revision + 1, update_time = now()"
                                + " where id = ? and username = ?",
                        safeTitle, trim(destination, 100), trim(start, 20), trim(end, 20), Math.max(1, days),
                        safeBudget, safePeople, safePace, Math.max(1, Math.min(3, planStep)),
                        compactPlan, tripId, username);
                if (safeOrigin != null || safeLabel != null) {
                    String extra = (compactPlan == null ? "{}" : compactPlan);
                    MySQLUtil.executeUpdate(
                            "update trip set plan_data = ? where id = ? and username = ?",
                            mergeHeader(extra, safeOrigin, safeLabel), tripId, username);
                }
                out.put("id", tripId);
            } else {
                try (Connection conn = MySQLUtil.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "insert into trip(username, title, content, status, create_time, update_time,"
                                     + " destination, start_date, end_date, days, budget, people, pace,"
                                     + " plan_step, plan_data, version, visibility)"
                                     + " values(?, ?, ?, '规划中', now(), now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'private')",
                             Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, username);
                    ps.setString(2, safeTitle);
                    ps.setString(3, "规划草稿，尚未生成正式行程");
                    ps.setString(4, trim(destination, 100));
                    ps.setString(5, trim(start, 20));
                    ps.setString(6, trim(end, 20));
                    ps.setInt(7, Math.max(1, days));
                    ps.setString(8, safeBudget);
                    ps.setString(9, safePeople);
                    ps.setString(10, safePace);
                    ps.setInt(11, Math.max(1, Math.min(3, planStep)));
                    ps.setString(12, compactPlan);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        tripId = keys.next() ? keys.getInt(1) : 0;
                    }
                }
                if (tripId <= 0) return fail(out, "规划草稿创建失败");
                if (safeOrigin != null || safeLabel != null) {
                    MySQLUtil.executeUpdate(
                            "update trip set plan_data = ? where id = ? and username = ?",
                            mergeHeader(compactPlan == null ? "{}" : compactPlan, safeOrigin, safeLabel),
                            tripId, username);
                }
                out.put("id", tripId);
            }
            out.put("status", "success");
            out.put("message", "规划草稿已保存");
            out.put("version", readVersion(username, tripId));
            out.put("planRevision", readPlanRevision(username, tripId));
            return out;
        } catch (Exception e) {
            log.error("保存规划草稿失败", e);
            return fail(out, "规划草稿保存失败");
        }
    }

    @PostMapping(value = "/plan/draft/get", produces = "application/json;charset=UTF-8")
    public Map<String, Object> get(@RequestParam int id, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        String sql = "select id, title, destination, start_date, end_date, days, budget, people, pace,"
                + " plan_step, plan_data, plan_revision, version, create_time, update_time"
                + " from trip where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return fail(out, "规划草稿不存在");
                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("id", rs.getInt("id"));
                draft.put("title", rs.getString("title"));
                draft.put("destination", rs.getString("destination"));
                draft.put("start", rs.getString("start_date"));
                draft.put("end", rs.getString("end_date"));
                draft.put("days", rs.getInt("days"));
                draft.put("budget", rs.getString("budget"));
                draft.put("people", rs.getString("people"));
                draft.put("pace", rs.getString("pace"));
                draft.put("planStep", rs.getInt("plan_step"));
                draft.put("planData", rs.getString("plan_data"));
                draft.put("planRevision", rs.getInt("plan_revision"));
                draft.put("version", rs.getInt("version"));
                draft.put("updateTime", format(rs.getTimestamp("update_time")));
                out.put("status", "success");
                out.put("draft", draft);
                return out;
            }
        } catch (Exception e) {
            log.error("读取规划草稿失败", e);
            return fail(out, "规划草稿读取失败");
        }
    }

    @PostMapping(value = "/plan/draft/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        List<Map<String, Object>> drafts = new ArrayList<>();
        String sql = "select id, title, destination, start_date, end_date, days, plan_step, update_time"
                + " from trip where username = ? and plan_data is not null and plan_step < 3"
                + " order by update_time desc limit 10";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", rs.getInt("id"));
                    d.put("title", rs.getString("title"));
                    d.put("destination", rs.getString("destination"));
                    d.put("start", rs.getString("start_date"));
                    d.put("end", rs.getString("end_date"));
                    d.put("days", rs.getInt("days"));
                    d.put("planStep", rs.getInt("plan_step"));
                    d.put("updateTime", format(rs.getTimestamp("update_time")));
                    drafts.add(d);
                }
            }
        } catch (Exception e) {
            log.error("读取规划草稿列表失败", e);
            return fail(out, "规划草稿列表读取失败");
        }
        out.put("status", "success");
        out.put("drafts", drafts);
        return out;
    }

    private boolean ownsTrip(String username, int tripId) throws Exception {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select id from trip where id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int readVersion(String username, int tripId) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select version from trip where id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private int readPlanRevision(String username, int tripId) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select plan_revision from trip where id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static String mergeHeader(String planData, String origin, String destinationLabel) {
        String cleaned = planData == null || planData.trim().isEmpty() ? "{}" : planData.trim();
        if (cleaned.endsWith("}")) {
            String prefix = cleaned.substring(0, cleaned.length() - 1);
            if (!prefix.trim().endsWith("{")) prefix += ",";
            prefix += "\"_origin\":" + json(origin) + ",\"_destinationLabel\":" + json(destinationLabel) + "}";
            return prefix;
        }
        return cleaned;
    }

    private static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String s = value.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String format(java.sql.Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT);
    }

    private static Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }
}
