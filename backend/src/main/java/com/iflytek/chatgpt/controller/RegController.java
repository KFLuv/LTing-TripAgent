package com.iflytek.chatgpt.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import util.MySQLUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 处理注册逻辑（由原 RegServlet 改写而来）
 */
@Controller
public class RegController {

    @PostMapping("/reg")
    public void reg(@RequestParam(required = false) String username,
                    @RequestParam(required = false) String password,
                    @RequestParam(required = false) String email,
                    HttpServletResponse response) throws IOException {
        // 解决响应的中文乱码
        response.setContentType("text/html;charset=utf-8");

        System.out.println("用户名：" + username);
        System.out.println("密码：" + password);
        System.out.println("邮箱：" + email);

        // 校验账号是否存在，存在的话不允许注册
        boolean flag = checkUserName(username);
        if (flag) {
            // 响应
            response.getWriter().write("账号存在，请重新换个账号注册！！");
            return; // 程序执行到这里就返回了，不往下执行
        }

        // 将用户信息存储到数据库
        // 1、获取数据库连接对象Connection
        try {
            Connection connection = MySQLUtil.getConnection();
            // 2、根据连接对象获取sql 执行对象
            Statement statement = connection.createStatement();
            // 3、定义sql 语句
            String sql = "insert into user(username,password,email) values('" + username + "','" + password + "','" + email + "')";
            // 4、执行sql语句
            statement.executeUpdate(sql);
            // 5、关闭资源。先开后关
            statement.close();
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // 重定向到登录页面
        response.sendRedirect("login.html");
    }

    /**
     * 校验用户名是否存在，存在返回true,不存在返回false
     */
    private boolean checkUserName(String username) {
        try {
            Connection connection = MySQLUtil.getConnection();
            // 2、根据连接对象获取sql 执行对象
            Statement statement = connection.createStatement();
            // 3、定义sql 语句
            String sql = "select * from user where username='" + username + "'";
            // 4、执行sql语句
            ResultSet resultSet = statement.executeQuery(sql);
            if (resultSet.next()) { // 账号存在
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
