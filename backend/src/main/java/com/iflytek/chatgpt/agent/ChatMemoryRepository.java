package com.iflytek.chatgpt.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import util.MySQLUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史持久化（多会话版）。
 * <p>
 * 原实现把整段历史挂在用户名下，一个用户只有一条连续对话流，进页面永远接在上一轮后面，
 * 想「开个新话题」也做不到。改成以「会话（chat_session）」为单位后：
 * <ul>
 *   <li>{@code chat_session} 存会话本体（标题、创建/更新时间）；</li>
 *   <li>{@code chat_message.session_id} 标明每条消息属于哪个会话；</li>
 *   <li>侧栏可列出、切换、改名、删除会话，重开页面接着上次那个会话继续。</li>
 * </ul>
 * <b>只存 user / assistant 的纯文本，不存 tool_calls 与 role=tool 的消息</b>——
 * 回放工具调用链容易触发「tool_calls 没有对应 tool 响应」的 400 报错，
 * 而且历史工具结果对下一轮判断价值很低。
 */
@Component
public class ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryRepository.class);

    /** 单次加载上限：防止历史无限增长把上下文撑爆 */
    public static final int MAX_LOAD = 200;

    /** 新会话的默认标题；一旦用户发出第一条消息，会被改写成消息摘要 */
    private static final String DEFAULT_TITLE = "新对话";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ==================== 会话管理 ====================

    /**
     * 新建一个会话，返回自增主键；失败返回 0。
     */
    public long createSession(String username, String title) {
        if (isBlank(username)) {
            return 0;
        }
        String t = normalizeTitle(title);
        String sql = "insert into chat_session(username, title, create_time, update_time) values(?,?,now(),now())";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, t);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("新建会话失败：username={}", username, e);
        }
        return 0;
    }

    /**
     * 列出该用户的全部会话，最近更新在前。附带每个会话的消息条数，供侧栏展示。
     */
    public List<Map<String, Object>> listSessions(String username) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (isBlank(username)) {
            return list;
        }
        String sql = "select s.id, s.title, s.update_time,"
                + " (select count(*) from chat_message m where m.username = s.username and m.session_id = s.id) as msg_count"
                + " from chat_session s where s.username = ? order by s.update_time desc, s.id desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("title", rs.getString("title"));
                    m.put("updateTime", formatTime(rs.getTimestamp("update_time")));
                    m.put("messageCount", rs.getInt("msg_count"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("读取会话列表失败：username={}", username, e);
        }
        return list;
    }

    /** 取某会话的标题，找不到返回 null */
    public String sessionTitle(String username, long sessionId) {
        if (isBlank(username) || sessionId <= 0) {
            return null;
        }
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select title from chat_session where id = ? and username = ?")) {
            ps.setLong(1, sessionId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("title");
                }
            }
        } catch (SQLException e) {
            log.error("读取会话标题失败：id={}", sessionId, e);
        }
        return null;
    }

    /** 最近更新的会话 id，没有返回 null */
    public Long latestSessionId(String username) {
        if (isBlank(username)) {
            return null;
        }
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select id from chat_session where username = ? order by update_time desc, id desc limit 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        } catch (SQLException e) {
            log.error("读取最近会话失败：username={}", username, e);
        }
        return null;
    }

    /** 最近会话；没有则新建一个。用于前端未显式带 sessionId 时兜底。 */
    public long ensureLatestSession(String username) {
        Long id = latestSessionId(username);
        return id != null ? id : createSession(username, DEFAULT_TITLE);
    }

    /** 重命名会话 */
    public boolean renameSession(String username, long sessionId, String title) {
        if (isBlank(username) || sessionId <= 0) {
            return false;
        }
        String t = normalizeTitle(title);
        try {
            int n = MySQLUtil.executeUpdate(
                    "update chat_session set title = ?, update_time = now() where id = ? and username = ?",
                    t, sessionId, username);
            return n > 0;
        } catch (Exception e) {
            log.error("重命名会话失败：id={}", sessionId, e);
            return false;
        }
    }

    /**
     * 会话还没起过标题（仍是默认「新对话」）时，用第一条用户消息的前 18 字作为小标题。
     * 已经有自定义标题的会话不动，避免覆盖用户手动改过的名字。
     */
    public void autoTitle(String username, long sessionId, String firstMessage) {
        if (isBlank(username) || sessionId <= 0 || firstMessage == null) {
            return;
        }
        String current = sessionTitle(username, sessionId);
        if (current == null || !DEFAULT_TITLE.equals(current.trim())) {
            return;
        }
        String t = firstMessage.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) {
            return;
        }
        if (t.length() > 18) {
            t = t.substring(0, 18);
        }
        renameSession(username, sessionId, t);
    }

    /** 删除会话及其全部消息 */
    public void deleteSession(String username, long sessionId) {
        if (isBlank(username) || sessionId <= 0) {
            return;
        }
        try {
            MySQLUtil.executeUpdate("delete from chat_message where username = ? and session_id = ?", username, sessionId);
            MySQLUtil.executeUpdate("delete from chat_session where id = ? and username = ?", sessionId, username);
        } catch (Exception e) {
            log.error("删除会话失败：id={}", sessionId, e);
        }
    }

    /** 把会话的 update_time 顶到当前，让它在侧栏排到最前 */
    public void touchSession(String username, long sessionId) {
        if (isBlank(username) || sessionId <= 0) {
            return;
        }
        try {
            MySQLUtil.executeUpdate("update chat_session set update_time = now() where id = ? and username = ?",
                    sessionId, username);
        } catch (Exception e) {
            log.error("更新会话时间失败：id={}", sessionId, e);
        }
    }

    // ==================== 消息读写 ====================

    /**
     * 取某会话最近 {@code limit} 条消息，按时间正序返回（直接可拼进 messages）。
     * <p>
     * 用「先倒序取 limit 条再正序」的子查询写法，避免把全部历史读出来再截断。
     * 任何异常都只记日志并返回已读到的部分——记忆读取失败不应该让整轮对话挂掉。
     */
    public List<JsonNode> loadRecent(String username, long sessionId, int limit) {
        List<JsonNode> list = new ArrayList<>();
        if (isBlank(username) || sessionId <= 0) {
            return list;
        }
        int take = Math.max(1, Math.min(limit, MAX_LOAD));

        String sql = "select role, content from ("
                + " select role, content, id from chat_message"
                + " where username = ? and session_id = ? order by id desc limit ?"
                + ") t order by id asc";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = MySQLUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ps.setLong(2, sessionId);
            ps.setInt(3, take);
            rs = ps.executeQuery();
            while (rs.next()) {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("role", rs.getString("role"));
                // content 为 null 时给空串：模型接口不接受 null content
                String content = rs.getString("content");
                node.put("content", content == null ? "" : content);
                list.add(node);
            }
        } catch (SQLException e) {
            log.error("读取对话历史失败：username={}, sessionId={}", username, sessionId, e);
        } finally {
            MySQLUtil.closeResources(conn, ps, rs);
        }
        return list;
    }

    /**
     * 追加一条消息到指定会话。空内容直接丢弃，避免往库里塞空行。
     * 内容超长时截断——画像、游记这类回答可能很长，但历史只需要保留主旨。
     */
    public void append(String username, long sessionId, String role, String content) {
        if (isBlank(username) || sessionId <= 0) {
            return;
        }
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        String trimmed = content.length() > 8000 ? content.substring(0, 8000) : content;
        try {
            MySQLUtil.executeUpdate(
                    "insert into chat_message(username, session_id, role, content, create_time) values(?,?,?,?,now())",
                    username, sessionId, role, trimmed);
            touchSession(username, sessionId);
        } catch (Exception e) {
            // 落库失败不影响本次回答返回给用户
            log.error("写入对话历史失败：username={}, role={}", username, role, e);
        }
    }

    /** 清空该用户的对话历史（供"清空对话"功能或排障使用） */
    public void clear(String username) {
        if (isBlank(username)) {
            return;
        }
        try {
            MySQLUtil.executeUpdate("delete from chat_message where username = ?", username);
        } catch (Exception e) {
            log.error("清空对话历史失败：username={}", username, e);
        }
    }

    // ==================== 小工具 ====================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String normalizeTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            t = DEFAULT_TITLE;
        }
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    private static String formatTime(Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT);
    }
}
