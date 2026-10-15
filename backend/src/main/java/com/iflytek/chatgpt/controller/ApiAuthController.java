package com.iflytek.chatgpt.controller;

import com.iflytek.chatgpt.service.AuthTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 小程序端的登录接口（令牌版）。
 *
 * <p>与 {@link LoginController} 的区别只有一个：登录成功后返回一个 token，
 * 而不是往响应里塞 JSESSIONID 的 Cookie。理由是微信小程序对跨站
 * {@code Set-Cookie}（SameSite=Lax）基本不保留，Cookie 方案在小程序里
 * 会表现成"登录了但下一个请求又说没登录"。
 *
 * <p>密码校验的口径与 LoginController 完全一致（占位符 + 明文比对，
 * 明文存储是历史遗留，见 README 的「已知限制」）。
 */
@RestController
public class ApiAuthController {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthController.class);

    /** 游客名前缀，与 LoginController 保持一致 */
    private static final String GUEST_PREFIX = "游客";

    /** 登录：POST /api/auth/login  (username, password) → {status, token, username} */
    @PostMapping(value = "/api/auth/login", produces = "application/json;charset=UTF-8")
    public Map<String, Object> login(@RequestParam(required = false) String username,
                                     @RequestParam(required = false) String password) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            return fail(out, "账号和密码都要填");
        }
        String user = username.trim();
        if (!passwordMatches(user, password)) {
            return fail(out, "用户名或密码错误");
        }
        String token = AuthTokenService.issue(user);
        if (token == null) {
            return fail(out, "登录成功但签发令牌失败，请稍后重试");
        }
        out.put("status", "success");
        out.put("token", token);
        out.put("username", user);
        return out;
    }

    /** 游客登录：POST /api/auth/guest → 直接给一个「游客xxxx」身份 + 令牌 */
    @PostMapping(value = "/api/auth/guest", produces = "application/json;charset=UTF-8")
    public Map<String, Object> guest(HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object existing = session == null ? null : session.getAttribute(ChatController.SESSION_USER);
        String guest;
        if (existing instanceof String && ((String) existing).startsWith(GUEST_PREFIX)) {
            guest = (String) existing;
        } else {
            guest = GUEST_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        String token = AuthTokenService.issue(guest);
        if (token == null) {
            return fail(out, "游客登录失败，请稍后重试");
        }
        out.put("status", "success");
        out.put("token", token);
        out.put("username", guest);
        return out;
    }

    /** 退出：POST /api/auth/logout + 头 X-Auth-Token */
    @PostMapping(value = "/api/auth/logout", produces = "application/json;charset=UTF-8")
    public Map<String, Object> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        AuthTokenService.revoke(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        return out;
    }

    /** 当前身份：POST /api/auth/me + 头 X-Auth-Token（小程序进页面时自检用） */
    @PostMapping(value = "/api/auth/me", produces = "application/json;charset=UTF-8")
    public Map<String, Object> me(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        String username = AuthTokenService.resolve(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("login", username != null);
        out.put("username", username == null ? "" : username);
        return out;
    }

    /** 与 LoginController.doLogin 同一套判断：占位符，避免 SQL 注入 */
    private boolean passwordMatches(String username, String password) {
        String sql = "select id from `user` where username = ? and password = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.error("令牌登录查询失败：username={}", username, e);
            return false;
        }
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }
}
