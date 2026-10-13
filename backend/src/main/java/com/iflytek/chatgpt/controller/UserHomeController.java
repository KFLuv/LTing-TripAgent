package com.iflytek.chatgpt.controller;

import com.iflytek.chatgpt.social.SocialService;
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
 * 他人主页：一个用户的动态 / 打卡 / 评价 / 收藏 / 行程。
 * <p>
 * 每一项都按 <b>public / friends / private</b> 分别过滤：
 * 公开谁都能看、仅好友要已是好友、仅自己只有本人看得到。
 * 过滤统一走 {@link SocialService#visibilityClause}，避免各写一遍写歪成越权。
 */
@RestController
public class UserHomeController {

    private static final Logger log = LoggerFactory.getLogger(UserHomeController.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SocialService social;

    public UserHomeController(SocialService social) {
        this.social = social;
    }

    @PostMapping(value = "/user/home", produces = "application/json;charset=UTF-8")
    public Map<String, Object> home(@RequestParam(required = false) String username,
                                    @RequestParam(required = false) String code,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String me = ChatController.currentUser(session);
        if (me == null) {
            return fail(result, "请先登录");
        }
        String target = username == null ? "" : username.trim();
        if (target.isEmpty() && code != null && !code.trim().isEmpty()) {
            target = social.findByInviteCode(code);
            if (target == null) {
                return fail(result, "邀请码无效");
            }
        }
        if (target.isEmpty()) {
            return fail(result, "缺少用户名或邀请码");
        }
        if (!social.userExists(target)) {
            return fail(result, "用户不存在");
        }

        boolean isSelf = target.equals(me);
        boolean isFriend = isSelf || social.areFriends(me, target);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", target);
        profile.put("nickname", "");
        profile.put("avatar", "");
        profile.put("tags", "");
        profile.put("bio", "");
        profile.put("interests", "");
        profile.put("inviteCode", "");
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select nickname, avatar, tags, bio, interests, invite_code from user_profile"
                             + " where username = ?")) {
            ps.setString(1, target);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    profile.put("nickname", nn(rs.getString("nickname")));
                    profile.put("avatar", nn(rs.getString("avatar")));
                    profile.put("tags", nn(rs.getString("tags")));
                    profile.put("bio", nn(rs.getString("bio")));
                    profile.put("interests", nn(rs.getString("interests")));
                    profile.put("inviteCode", nn(rs.getString("invite_code")));
                }
            }
        } catch (SQLException e) {
            log.warn("读取他人资料失败：{}", e.getMessage());
        }

        result.put("status", "success");
        result.put("profile", profile);
        result.put("isSelf", isSelf);
        result.put("isFriend", isFriend);
        result.put("moments", query(target, me,
                "select id, content, place, images, visibility, create_time from moment"
                        + " where username = ?", "create_time"));
        result.put("trips", query(target, me,
                "select id, title, status, visibility, create_time from trip"
                        + " where username = ?", "create_time"));
        result.put("logs", query(target, me,
                "select id, place, rating, note, visibility, create_time from travel_log"
                        + " where username = ?", "create_time"));
        result.put("reviews", query(target, me,
                "select id, poi_name, rating, content, visibility, create_time from poi_review"
                        + " where username = ?", "create_time"));
        return result;
    }

    /**
     * 查一类内容：先按"我是谁/是不是好友"过滤可见性，再按时间倒序。
     * 参数顺序：归属人 → 浏览者（可见性片段里用了两次）。
     */
    private List<Map<String, Object>> query(String target, String viewer, String baseSql, String timeColumn) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = baseSql + social.visibilityClause("username")
                + " order by " + timeColumn + " desc limit 50";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target);
            ps.setString(2, viewer);
            ps.setString(3, viewer);
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        String name = md.getColumnName(i).toLowerCase();
                        Object value = rs.getObject(i);
                        if (value instanceof java.sql.Timestamp) {
                            value = ((java.sql.Timestamp) value).toLocalDateTime().format(TIME_FORMAT);
                        }
                        if ("images".equals(name)) {
                            value = value == null ? new ArrayList<String>() : value;
                        }
                        row.put(name, value);
                    }
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            log.warn("查询主页内容失败：{}", e.getMessage());
        }
        return list;
    }

    private String nn(String s) {
        return s == null ? "" : s;
    }

    private Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("status", "error");
        result.put("message", message);
        return result;
    }
}
