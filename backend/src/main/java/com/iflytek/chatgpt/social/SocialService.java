package com.iflytek.chatgpt.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import util.MySQLUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 动态广场模块的社交底座：好友关系 + 邀请码 + 可见权限。
 * <p>
 * 三件事：
 * <ol>
 *   <li><b>好友</b>：加好友按"用户名（ID）或邀请码"两种入口，成功后双向各写一行，
 *       这样双方主页都能互相看到"仅好友可见"的内容；</li>
 *   <li><b>邀请码</b>：每个用户一个短码，首次取资料时懒生成，便于口头分享；</li>
 *   <li><b>可见权限</b>：动态/行程/打卡/收藏/点评共用 public / friends / private 三档，
 *       这里统一给出过滤 SQL 片段，避免每个查询各写一遍（写歪了就是越权）。</li>
 * </ol>
 */
@Service
public class SocialService {

    private static final Logger log = LoggerFactory.getLogger(SocialService.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 邀请码字符集：去掉了 0/O/1/I 这类容易念错的 */
    private static final String CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 6;

    private final Random random = new Random();

    /** 可见权限归一：非法值一律当公开，避免脏数据把内容藏起来 */
    public String normalizeVisibility(String v) {
        return Visibility.normalize(v);
    }

    /**
     * "这个浏览者能不能看"的 SQL 条件片段。
     * <p>
     * 调用方把这个片段接在 {@code where username = ?} 之后，并按出现顺序补上
     * <b>两个</b>浏览者参数（占位符出现两次）。
     *
     * @param ownerColumn 内容表里的归属列（如 {@code moment.username}）
     */
    public String visibilityClause(String ownerColumn) {
        return " and (visibility = 'public'"
                + " or ? = " + ownerColumn
                + " or (visibility = 'friends' and exists (select 1 from friendship f"
                + " where f.owner = ? and f.friend = " + ownerColumn + ")))";
    }

    public boolean areFriends(String a, String b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select 1 from friendship where owner = ? and friend = ?")) {
            ps.setString(1, a);
            ps.setString(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("查询好友关系失败：{}", e.getMessage());
            return false;
        }
    }

    /** owner 的好友列表，带对方昵称/头像/邀请码，便于列表里直接分享 */
    public List<Map<String, Object>> listFriends(String owner) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "select f.friend, f.create_time, p.nickname, p.avatar, p.invite_code"
                + " from friendship f left join user_profile p on p.username = f.friend"
                + " where f.owner = ? order by f.id desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", rs.getString("friend"));
                    m.put("nickname", nullToEmpty(rs.getString("nickname")));
                    m.put("avatar", nullToEmpty(rs.getString("avatar")));
                    m.put("inviteCode", nullToEmpty(rs.getString("invite_code")));
                    java.sql.Timestamp ts = rs.getTimestamp("create_time");
                    m.put("createTime", ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取好友列表失败：{}", e.getMessage());
        }
        return list;
    }

    /** 按邀请码反查用户名；查不到返回 null */
    public String findByInviteCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select username from user_profile where invite_code = ?")) {
            ps.setString(1, code.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warn("邀请码查询失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 用户是否存在（加好友 / 看他人主页时校验）。
     * <p>
     * 注册用户看 {@code user} 表；游客没有账号行，只要他保存过资料（user_profile 有行）也算存在，
     * 否则"游客登录"的演示账号永远加不上好友、也点不开主页。
     */
    public boolean userExists(String username) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select 1 from `user` where username = ?"
                             + " union all select 1 from user_profile where username = ? limit 1")) {
            ps.setString(1, username);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** 加好友：双向各写一行（这里没有"申请-同意"流程，直接互加，便于演示） */
    public String addFriend(String owner, String target) {
        if (target == null || target.trim().isEmpty()) {
            return "请填写对方的 ID 或邀请码";
        }
        String name = target.trim();
        if (name.equals(owner)) {
            return "不能加自己为好友";
        }
        if (!userExists(name)) {
            String byCode = findByInviteCode(name);
            if (byCode == null) {
                return "没找到这个 ID 或邀请码对应的用户";
            }
            name = byCode;
            if (name.equals(owner)) {
                return "不能加自己为好友";
            }
        }
        try {
            insertPair(owner, name);
            insertPair(name, owner);
        } catch (Exception e) {
            log.error("加好友失败：{} -> {}", owner, name, e);
            return "加好友失败，请稍后重试";
        }
        return "已添加好友：" + name;
    }

    private void insertPair(String owner, String friend) {
        MySQLUtil.executeUpdate(
                "insert into friendship(owner, friend, create_time) values(?, ?, now())"
                        + " on duplicate key update create_time = create_time",
                owner, friend);
    }

    public void removeFriend(String owner, String friend) {
        MySQLUtil.executeUpdate("delete from friendship where owner = ? and friend = ?", owner, friend);
        MySQLUtil.executeUpdate("delete from friendship where owner = ? and friend = ?", friend, owner);
    }

    /** 取邀请码，没有就生成一个并写回（并发下极小概率撞码，撞了下次再生成） */
    public String ensureInviteCode(String username) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select invite_code from user_profile where username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String existing = rs.getString(1);
                    if (existing != null && !existing.trim().isEmpty()) {
                        return existing.trim();
                    }
                } else {
                    return null;          // 资料还没建过，等保存资料时再生成
                }
            }
        } catch (SQLException e) {
            log.warn("读取邀请码失败：{}", e.getMessage());
            return null;
        }
        String code = randomCode();
        try {
            MySQLUtil.executeUpdate(
                    "update user_profile set invite_code = ? where username = ? and (invite_code is null or invite_code = '')",
                    code, username);
        } catch (Exception e) {
            log.warn("写入邀请码失败：{}", e.getMessage());
            return null;
        }
        return code;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
