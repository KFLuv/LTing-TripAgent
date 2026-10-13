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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户个人资料模块：头像 / 昵称 / 标签 / 简介 / 兴趣偏好。
 * <p>
 * 一人一行（user_profile.username 唯一），保存即 upsert，不存在就建。
 * 头像存 data URL（与朋友圈图片同一策略），省掉对象存储。
 * 资料不是"给 AI 看的备注"，而是本模块的正式数据：AI 也能通过 profile 工具读写它。
 */
@RestController
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final com.iflytek.chatgpt.social.SocialService social;

    public ProfileController(com.iflytek.chatgpt.social.SocialService social) {
        this.social = social;
    }

    private static final int MAX_NICKNAME = 60;
    private static final int MAX_TAGS = 300;
    private static final int MAX_BIO = 500;
    private static final int MAX_INTERESTS = 500;
    /** 头像 data URL 上限：约 400KB，防止把库写爆 */
    private static final int MAX_AVATAR_CHARS = 400_000;

    @PostMapping(value = "/profile/get", produces = "application/json;charset=UTF-8")
    public Map<String, Object> get(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", username);
        profile.put("nickname", "");
        profile.put("avatar", "");
        profile.put("tags", "");
        profile.put("bio", "");
        profile.put("interests", "");
        profile.put("updateTime", "");

        // 先确保资料行存在：邀请码挂在这一行上，没有行就发不出码
        try {
            MySQLUtil.executeUpdate(
                    "insert into user_profile(username, update_time) values(?, now())"
                            + " on duplicate key update username = username", username);
        } catch (Exception e) {
            log.warn("初始化资料行失败：{}", e.getMessage());
        }

        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select nickname, avatar, tags, bio, interests, update_time from user_profile"
                             + " where username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    profile.put("nickname", nullToEmpty(rs.getString("nickname")));
                    profile.put("avatar", nullToEmpty(rs.getString("avatar")));
                    profile.put("tags", nullToEmpty(rs.getString("tags")));
                    profile.put("bio", nullToEmpty(rs.getString("bio")));
                    profile.put("interests", nullToEmpty(rs.getString("interests")));
                    java.sql.Timestamp ts = rs.getTimestamp("update_time");
                    profile.put("updateTime", ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT));
                }
            }
        } catch (SQLException e) {
            log.error("读取个人资料失败：username={}", username, e);
            return fail(result, "个人资料读取失败，请稍后重试");
        }

        result.put("status", "success");
        profile.put("inviteCode", nvl(social.ensureInviteCode(username)));
        result.put("profile", profile);
        return result;
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    @PostMapping(value = "/profile/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(required = false) String nickname,
                                    @RequestParam(required = false) String avatar,
                                    @RequestParam(required = false) String tags,
                                    @RequestParam(required = false) String bio,
                                    @RequestParam(required = false) String interests,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录");
        }
        if (avatar != null && avatar.length() > MAX_AVATAR_CHARS) {
            return fail(result, "头像太大了（请换一张更小的图片）");
        }

        String safeNickname = trim(nickname, MAX_NICKNAME);
        String safeTags = trim(tags, MAX_TAGS);
        String safeBio = trim(bio, MAX_BIO);
        String safeInterests = trim(interests, MAX_INTERESTS);

        // 占位符重复一遍代替 MySQL 专有的 values(列)，H2 同样可用
        try {
            MySQLUtil.executeUpdate(
                    "insert into user_profile(username, nickname, avatar, tags, bio, interests, update_time)"
                            + " values(?, ?, ?, ?, ?, ?, now())"
                            + " on duplicate key update nickname = ?, avatar = ?, tags = ?, bio = ?,"
                            + " interests = ?, update_time = now()",
                    username, safeNickname, avatar, safeTags, safeBio, safeInterests,
                    safeNickname, avatar, safeTags, safeBio, safeInterests);
        } catch (Exception e) {
            log.error("保存个人资料失败：username={}", username, e);
            return fail(result, "个人资料保存失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("message", "已保存个人资料");
        return result;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
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
