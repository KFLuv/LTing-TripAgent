package com.iflytek.chatgpt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import util.MySQLUtil;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 处理登录逻辑（由原 LoginServlet 改写而来）
 */
@Controller
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    /** 游客名前缀，用来判断 session 里已有的是不是一个游客身份 */
    private static final String GUEST_PREFIX = "游客";

    /**
     * 「记住账号」Cookie 的有效期：30 天。
     * <p>
     * 旧实现写的是 {@code maxAge(60)}（60 <b>秒</b>），勾不勾选都等于没有；
     * 这里与 30 天的服务端会话保持一致。
     */
    private static final int REMEMBER_USERNAME_SECONDS = 30 * 24 * 60 * 60;

    /** 记住账号用的 Cookie 名 */
    private static final String COOKIE_USERNAME = "username";

    /**
     * 登录
     */
    @PostMapping("/login")
    public void login(@RequestParam(required = false) String username,
                      @RequestParam(required = false) String password,
                      @RequestParam(required = false) String remember,
                      @RequestParam(required = false) String auto,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        // 解决响应的中文乱码
        response.setContentType("text/html;charset=utf-8");

        // 校验账号和密码是否为空
        if (username == null || username.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            response.getWriter().write("用户名和密码不能为空");
            return;
        }

        // 验证用户名和密码
        boolean flag = doLogin(username, password);

        if (flag) {
            // 登录成功：把当前用户写进 session，后面的接口都靠它来识别"我是谁"
            request.getSession().setAttribute("loginUser", username);

            // 历史上这里会把「明文口令 + autoLogin」写进浏览器 Cookie。现在不再存口令：
            // 一是安全（浏览器里躺着一份明文密码），二是它本来也没换来功能——
            // Cookie 只活 60 秒，而且从来没有代码读取它做自动登录。
            // 顺手把老版本留在浏览器里的那两个 Cookie 清掉。
            clearLegacyCredentialCookies(request, response);

            // 「记住账号」：只存用户名，供登录页回填；真正的免重复登录由 30 天会话保证。
            if ("on".equals(remember) || "on".equals(auto)) {
                String encodeUsername = URLEncoder.encode(username, "UTF-8");
                Cookie usernameCookie = new Cookie(COOKIE_USERNAME, encodeUsername);
                usernameCookie.setMaxAge(REMEMBER_USERNAME_SECONDS);
                usernameCookie.setPath("/chat");
                response.addCookie(usernameCookie);
            } else {
                clearCookie(response, COOKIE_USERNAME);
            }
            // 登录成功，重定向到首页
            response.sendRedirect("home.html");
        } else {
            // 登录失败
            response.getWriter().write("用户名或密码错误");
        }
    }

    /**
     * 游客登录：不校验数据库，直接在 session 里放一个游客身份，免注册直达首页。
     * <p>
     * <b>同一会话内复用同一个游客名</b>：以前每次调用都随机生成新名字，
     * 用户关掉软件再回来就变成另一个"游客"，行程本 / 偏好 / 历史全按 username 隔离，
     * 表现为"我的会话直接没了"。现在优先复用 session 里已有的游客身份；
     * 配合 {@code SessionConfig} 的会话持久化（写 MySQL），重启后也能接着用。
     * <p>
     * 游客名仍与注册账号隔离，且不写"记住密码"类 cookie，不会污染注册用户数据。
     */
    @PostMapping("/guest")
    public void guestLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=utf-8");

        HttpSession session = request.getSession();
        Object existing = session.getAttribute(ChatController.SESSION_USER);
        String guest;
        if (existing instanceof String && ((String) existing).startsWith(GUEST_PREFIX)) {
            // 已经是游客身份 → 原样复用，数据继续挂在同一个 username 下
            guest = (String) existing;
        } else {
            guest = GUEST_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        session.setAttribute(ChatController.SESSION_USER, guest);

        // 游客不写"记住密码/自动登录"cookie，避免与注册账号的登录态互相干扰
        response.sendRedirect("home.html");
    }

    /**
     * 校验账号和密码是否正确，正确返回true，否则返回false
     * <p>
     * 用占位符而不是拼字符串：拼接写法下输入 {@code ' or '1'='1} 就能绕过登录。
     */
    private boolean doLogin(String username, String password) {
        String sql = "select id from `user` where username = ? and password = ?";
        try (Connection connection = MySQLUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            log.error("登录查询失败：username={}", username, e);
            throw new RuntimeException(e);
        }
    }

    /** 清掉老版本写入浏览器的口令类 Cookie（password / autoLogin） */
    private void clearLegacyCredentialCookies(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return;
        }
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if ("password".equals(name) || "autoLogin".equals(name)) {
                clearCookie(response, name);
            }
        }
    }

    /** 按同一个 path 让浏览器删除该 Cookie */
    private void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setMaxAge(0);
        cookie.setPath("/chat");
        response.addCookie(cookie);
    }
}
