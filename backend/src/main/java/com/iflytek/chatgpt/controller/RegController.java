package com.iflytek.chatgpt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import util.MySQLUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 处理注册逻辑（由原 RegServlet 改写而来）
 */
@Controller
public class RegController {

    private static final Logger log = LoggerFactory.getLogger(RegController.class);

    @PostMapping("/reg")
    public void reg(@RequestParam(required = false) String username,
                    @RequestParam(required = false) String password,
                    @RequestParam(required = false) String email,
                    HttpServletResponse response) throws IOException {
        // 解决响应的中文乱码
        response.setContentType("text/html;charset=utf-8");

        // 不再把凭据打到控制台：日志会被留存、被截图、被打包带走
        log.info("收到注册请求：username={}", username);

        if (username == null || username.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            response.getWriter().write("用户名和密码不能为空");
            return;
        }

        // 校验账号是否存在，存在的话不允许注册
        boolean flag = checkUserName(username);
        if (flag) {
            // 响应
            response.getWriter().write("账号存在，请重新换个账号注册！！");
            return; // 程序执行到这里就返回了，不往下执行
        }

        // 将用户信息存储到数据库：占位符写法，防止把用户输入当 SQL 执行
        String sql = "insert into `user`(username, password, email) values(?, ?, ?)";
        try (Connection connection = MySQLUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username.trim());
            statement.setString(2, password);
            statement.setString(3, email);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("注册写入失败：username={}", username, e);
            throw new RuntimeException(e);
        }

        // 重定向到登录页面
        response.sendRedirect("login.html");
    }

    /**
     * 校验用户名是否存在，存在返回true,不存在返回false
     * <p>
     * 同样改占位符：拼接写法下用户名里塞引号即可改变 SQL 语义。
     */
    private boolean checkUserName(String username) {
        String sql = "select id from `user` where username = ?";
        try (Connection connection = MySQLUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            log.error("查询用户名失败：username={}", username, e);
            throw new RuntimeException(e);
        }
    }
}
