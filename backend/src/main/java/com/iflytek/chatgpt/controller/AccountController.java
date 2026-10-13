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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 账号管理：解绑邮箱、注销账号。
 * <p>
 * 改密码与绑定邮箱仍走 {@code /updateUserInfo}（它会先验当前口令，避免拿到页面就能改）；
 * 这里补两件它做不到的事：
 * <ul>
 *   <li><b>解绑邮箱</b>——把 email 置空，同样要先验口令；</li>
 *   <li><b>注销账号</b>——验口令后把这个用户名下的全部数据清掉（不只是 user 行，
 *       否则行程/动态/偏好会变成无主数据留在库里）。游客没有账号行，用当前会话即身份。</li>
 * </ul>
 */
@RestController
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    /** 注销时要清的、带 username 归属的表 */
    private static final String[] USER_TABLES = {
            "user_profile", "user_preference", "preference_template", "preference_item",
            "trip", "trip_node", "trip_version", "travel_log", "travelog", "moment",
            "poi_review", "poi_menu", "chat_session", "chat_message",
            "toolbox_document", "packing_template", "budget_entry",
            // 灵感收藏模块已删除，这两张表只在老库里可能还留着，注销时顺手清
            "favorite_folder", "favorite_item"
    };

    /** 注销时要按 owner 清的 */
    private static final String[] OWNER_TABLES = {"trip_companion", "friendship"};

    @PostMapping(value = "/account/unbind-email", produces = "application/json;charset=UTF-8")
    public Map<String, Object> unbindEmail(@RequestParam(required = false) String password,
                                           HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "登录已失效，请重新登录");
        }
        if (!isGuest(username) && !passwordOk(username, password)) {
            return fail(result, "当前密码不正确");
        }
        try {
            MySQLUtil.executeUpdate("update `user` set email = null where username = ?", username);
        } catch (Exception e) {
            log.warn("解绑邮箱失败：{}", e.getMessage());
            return fail(result, "解绑失败，请稍后重试");
        }
        result.put("status", "success");
        result.put("message", "已解绑邮箱");
        return result;
    }

    /**
     * 注销账号：删掉这个用户名下的全部数据，并让当前会话失效。
     * 需要当前口令；游客没有口令，用会话本身作为凭证。
     */
    @PostMapping(value = "/account/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam(required = false) String password,
                                      @RequestParam(required = false) String confirm,
                                      HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "登录已失效，请重新登录");
        }
        if (confirm == null || !confirm.trim().equals(username)) {
            return fail(result, "请把用户名原样输入一遍以确认");
        }
        boolean guest = isGuest(username);
        if (!guest && !passwordOk(username, password)) {
            return fail(result, "当前密码不正确");
        }

        int cleared = 0;
        for (String table : USER_TABLES) {
            cleared += deleteBy(table, "username", username);
        }
        for (String table : OWNER_TABLES) {
            cleared += deleteBy(table, "owner", username);
        }
        // 好友关系里还有"别人指向我"的那一侧
        cleared += deleteBy("friendship", "friend", username);
        try {
            MySQLUtil.executeUpdate("delete from `user` where username = ?", username);
        } catch (Exception e) {
            log.warn("删除账号行失败：{}", e.getMessage());
        }

        session.invalidate();
        result.put("status", "success");
        result.put("message", guest ? "游客数据已清空" : "账号已注销");
        result.put("cleared", cleared);
        return result;
    }

    /** 表不存在或该用户在这张表没数据都算正常，返回删掉的行数 */
    private int deleteBy(String table, String column, String value) {
        try {
            return MySQLUtil.executeUpdate("delete from " + table + " where " + column + " = ?", value);
        } catch (Exception e) {
            log.debug("清理 {}.{} 跳过：{}", table, column, e.getMessage());
            return 0;
        }
    }

    /** 游客身份前缀与 LoginController 保持一致 */
    private boolean isGuest(String username) {
        return username != null && username.startsWith("游客");
    }

    private boolean passwordOk(String username, String password) {
        if (password == null) {
            return false;
        }
        return Objects.equals(password, queryString("select password from `user` where username = ?", username));
    }

    private String queryString(String sql, String param) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.warn("查询失败：{}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("status", "error");
        result.put("message", message);
        return result;
    }
}
