package com.iflytek.chatgpt.controller;

import com.iflytek.chatgpt.service.AiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * 聊天与用户信息相关接口（由原 ChatServlet 改写而来）
 */
@RestController
public class ChatController {

    /** session 里存放当前登录用户名的 key，全项目统一用它取身份 */
    public static final String SESSION_USER = "loginUser";

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AiChatService aiChatService;

    public ChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /**
     * AI 对话
     * <p>
     * 配了 ai.api-key 就走真实大模型；没配就退回原来的本地模拟回复，保证功能不挂。
     */
    @PostMapping(value = "/Aichat", produces = "text/plain;charset=UTF-8")
    public String aiChat(@RequestBody(required = false) String userMessage) {
        String message = userMessage == null ? "" : userMessage.trim();

        if (aiChatService.isConfigured()) {
            try {
                return aiChatService.chat(message);
            } catch (Exception e) {
                log.error("调用 AI 接口失败", e);
                return "抱歉，AI 服务暂时不可用：" + e.getMessage();
            }
        }

        // 未配置 API Key 时的模拟回复
        return "感谢您的咨询！关于" + userMessage + "，我建议您可以考虑以下行程：\n\n" +
                "1. 第一天：参观当地著名景点\n" +
                "2. 第二天：体验当地美食和文化\n" +
                "3. 第三天：自由活动或购物\n\n" +
                "如需更详细的行程规划，请提供更多具体信息。";
    }

    /**
     * 获取当前登录用户的信息
     * <p>
     * 原来这里写死了某个用户名，谁登录都显示同一个人；现在从 session + 数据库取。
     */
    @PostMapping(value = "/myinfo", produces = "application/json;charset=UTF-8")
    public Map<String, String> myInfo(HttpSession session) {
        Map<String, String> user = new LinkedHashMap<>();
        String username = currentUser(session);

        if (username == null) {
            // 没登录也返回 200，让前端自己决定怎么提示，不至于控制台一片红
            user.put("login", "false");
            user.put("name", "");
            user.put("email", "");
            return user;
        }

        user.put("login", "true");
        user.put("name", username);
        user.put("email", queryString("select email from user where username = ?", username));
        return user;
    }

    /**
     * 更新用户信息：先验当前密码，再把新密码 / 新邮箱真正写进数据库
     * <p>
     * 原来这里只比了一下两次密码是否一致就返回"成功"，一个字都没落库。
     */
    @PostMapping(value = "/updateUserInfo", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, String>> updateUserInfo(
            @RequestParam(required = false) String currentPassword,
            @RequestParam(required = false) String newPassword,
            @RequestParam(required = false) String confirmPassword,
            @RequestParam(required = false) String newEmail,
            HttpSession session) {

        Map<String, String> result = new LinkedHashMap<>();
        String username = currentUser(session);

        if (username == null) {
            return fail(result, "登录已失效，请重新登录");
        }

        // 1. 校验当前密码，防止别人拿到页面就能改密码
        if (!Objects.equals(currentPassword,
                queryString("select password from user where username = ?", username))) {
            return fail(result, "当前密码不正确");
        }

        boolean changePassword = newPassword != null && !newPassword.trim().isEmpty();
        boolean changeEmail = newEmail != null && !newEmail.trim().isEmpty();

        // 2. 要改密码时，两次输入必须一致
        if (changePassword && !Objects.equals(newPassword, confirmPassword)) {
            return fail(result, "新密码和确认密码不匹配");
        }
        if (!changePassword && !changeEmail) {
            return fail(result, "没有需要修改的内容");
        }

        // 3. 落库（用占位符而不是拼字符串，避免 SQL 注入）
        if (changePassword) {
            MySQLUtil.executeUpdate("update user set password = ? where username = ?", newPassword, username);
        }
        if (changeEmail) {
            MySQLUtil.executeUpdate("update user set email = ? where username = ?", newEmail.trim(), username);
        }

        result.put("status", "success");
        result.put("message", "用户信息更新成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 从 session 取当前登录用户名，未登录返回 null
     */
    public static String currentUser(HttpSession session) {
        Object username = session == null ? null : session.getAttribute(SESSION_USER);
        return username == null ? null : username.toString();
    }

    private ResponseEntity<Map<String, String>> fail(Map<String, String> result, String message) {
        result.put("status", "error");
        result.put("message", message);
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * 查单个字符串字段，查不到或出错返回空串
     */
    private String queryString(String sql, String param) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, param);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString(1);
                    return value == null ? "" : value;
                }
            }
        } catch (SQLException e) {
            log.error("查询失败：{}", sql, e);
        }
        return "";
    }
}
