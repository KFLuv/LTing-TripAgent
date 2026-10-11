package com.iflytek.chatgpt.controller;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 处理登录逻辑（由原 LoginServlet 改写而来）
 */
@Controller
public class LoginController {

    /** 游客名前缀，用来判断 session 里已有的是不是一个游客身份 */
    private static final String GUEST_PREFIX = "游客";

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
            if ("on".equals(remember) || "on".equals(auto)) {
                String encodeUsername = URLEncoder.encode(username, "UTF-8");
                String encodePassword = URLEncoder.encode(password, "UTF-8");
                Cookie usernameCookie = new Cookie("username", encodeUsername);
                Cookie passwordCookie = new Cookie("password", encodePassword);
                Cookie autoLoginCookie = new Cookie("autoLogin", "true");
                usernameCookie.setMaxAge(60);//60秒
                passwordCookie.setMaxAge(60);//60秒
                autoLoginCookie.setMaxAge(60);//60秒
                response.addCookie(usernameCookie);
                response.addCookie(passwordCookie);
                response.addCookie(autoLoginCookie);
            } else {//否则清除cookie
                Cookie[] cookies = request.getCookies();
                if (cookies != null) {
                    for (Cookie cookie : cookies) {
                        if (cookie.getName().equals("username")
                                || cookie.getName().equals("password")
                                || cookie.getName().equals("autoLogin")) {
                            cookie.setMaxAge(0);//0即为清除
                            response.addCookie(cookie);
                        }
                    }
                }
            }
            // 登录成功，重定向到首页
            response.sendRedirect("chat.html");
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
        response.sendRedirect("chat.html");
    }

    /**
     * 校验账号和密码是否正确，正确返回true，否则返回false
     */
    private boolean doLogin(String username, String password) {
        try {
            Connection connection = MySQLUtil.getConnection();
            // 2、根据连接对象获取sql 执行对象
            Statement statement = connection.createStatement();
            // 3、定义sql 语句,查询用户名和密码是否正确
            String sql = "select * from user where username='" + username + "' and password='" + password + "'";
            // 4、执行sql语句
            ResultSet resultSet = statement.executeQuery(sql);
            if (resultSet.next()) { // 账号和密码存在
                return true;
            }
            // 5、关闭资源。先开后关
            statement.close();
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return false;
    }
}
