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
 * 同行人：多人协同引擎的界面入口。
 * <p>
 * 此前只有 Agent 工具（listCompanions / saveCompanion）能读写 trip_companion，
 * 界面上既看不到也改不了——用户没法验证"AI 真的记住了我家人的偏好"，
 * 第四引擎（多人协同）在界面上等于不存在。
 * <p>
 * 三个接口，全部按 session 里的登录用户隔离（表里的 owner 列）：
 * list 列出 / save 新增或更新（按 owner+name 覆盖）/ delete 删除。
 */
@RestController
public class CompanionController {

    private static final Logger log = LoggerFactory.getLogger(CompanionController.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 字段上限与建表语句一致，防止超长写入报错 */
    private static final int MAX_NAME = 64;
    private static final int MAX_PREFS = 500;
    private static final int MAX_VOTE = 255;

    @PostMapping(value = "/companion/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String owner = ChatController.currentUser(session);
        if (owner == null) {
            return fail(result, "请先登录后再使用同行人");
        }

        List<Map<String, Object>> companions = new ArrayList<>();
        String sql = "select id, name, preferences, vote, update_time from trip_companion"
                + " where owner = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("name", rs.getString("name"));
                    m.put("preferences", rs.getString("preferences"));
                    m.put("vote", rs.getString("vote"));
                    m.put("updateTime", formatTime(rs.getTimestamp("update_time")));
                    companions.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("查询同行人失败：owner={}", owner, e);
            return fail(result, "同行人读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("companions", companions);
        return result;
    }

    /**
     * 新增或更新一名同行人。
     * <p>
     * 不给 id 时按 (owner, name) 覆盖更新——同名再登记视为补充偏好，而不是插一条重复记录。
     */
    @PostMapping(value = "/companion/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(defaultValue = "0") int id,
                                    @RequestParam(required = false) String name,
                                    @RequestParam(required = false) String preferences,
                                    @RequestParam(required = false) String vote,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String owner = ChatController.currentUser(session);
        if (owner == null) {
            return fail(result, "请先登录后再使用同行人");
        }
        if (name == null || name.trim().isEmpty()) {
            return fail(result, "称呼不能为空");
        }
        String safeName = trim(name, MAX_NAME);
        String safePrefs = trim(preferences, MAX_PREFS);
        String safeVote = trim(vote, MAX_VOTE);

        try {
            if (id > 0) {
                int rows = MySQLUtil.executeUpdate(
                        "update trip_companion set name = ?, preferences = ?, vote = ?, update_time = now()"
                                + " where id = ? and owner = ?",
                        safeName, safePrefs, safeVote, id, owner);
                if (rows <= 0) {
                    return fail(result, "同行人不存在");
                }
            } else {
                // 占位符重复一遍代替 MySQL 专有的 values(列)，H2 也认这个写法
                MySQLUtil.executeUpdate(
                        "insert into trip_companion(owner, name, preferences, vote, update_time)"
                                + " values(?, ?, ?, ?, now())"
                                + " on duplicate key update preferences = ?, vote = ?, update_time = now()",
                        owner, safeName, safePrefs, safeVote, safePrefs, safeVote);
                Integer found = findId(owner, safeName);
                if (found != null) {
                    id = found;
                }
            }
        } catch (Exception e) {
            log.error("保存同行人失败：owner={}, name={}", owner, safeName, e);
            return fail(result, "同行人保存失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("id", id);
        result.put("message", "已登记同行人：" + safeName);
        return result;
    }

    @PostMapping(value = "/companion/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String owner = ChatController.currentUser(session);
        if (owner == null) {
            return fail(result, "请先登录后再使用同行人");
        }
        int rows = MySQLUtil.executeUpdate(
                "delete from trip_companion where id = ? and owner = ?", id, owner);
        if (rows <= 0) {
            return fail(result, "同行人不存在或已被删除");
        }
        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    /** upsert 后取回主键，供前端刷新用 */
    private Integer findId(String owner, String name) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select id from trip_companion where owner = ? and name = ?")) {
            ps.setString(1, owner);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.warn("读取同行人主键失败：{}", e.getMessage());
        }
        return null;
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
