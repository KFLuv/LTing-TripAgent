package com.iflytek.chatgpt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.MySQLUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 令牌（token）登录用的存储。
 *
 * <p>为什么需要它：桌面端/网页端靠 JSESSIONID 的 Cookie 认人，而微信小程序里
 * Cookie 很不可靠——服务端会话 cookie 带的是 {@code SameSite=Lax}，
 * 小程序发的是跨站请求，Chromium 系内核（开发者工具就是）会直接把这条
 * {@code Set-Cookie} 丢掉。结果是每次请求都是"新会话"：登录接口明明返回成功，
 * 紧接着的 {@code /myinfo} 却说没登录，界面看起来就是"密码错误 / 游客进不去"。
 *
 * <p>所以小程序端改走令牌：登录一次拿到 token，之后每个请求带
 * {@code X-Auth-Token} 头；{@link com.iflytek.chatgpt.config.TokenAuthFilter}
 * 把它翻译成会话里的 {@code loginUser}，82 个既有接口一行都不用改。
 */
public final class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);

    /** 令牌有效期：与 30 天的会话保持一致 */
    private static final int VALID_DAYS = 30;

    private static final String CREATE_AUTH_TOKEN =
            "create table if not exists auth_token ("
                    + " token varchar(64) not null primary key,"
                    + " username varchar(50) not null,"
                    + " create_time datetime not null,"
                    + " expire_time datetime not null"
                    + ") default charset=utf8mb4";

    private static volatile boolean tableReady = false;

    private AuthTokenService() {
    }

    /** 建表（幂等，失败只告警，不拦启动） */
    public static void ensureTable() {
        if (tableReady) {
            return;
        }
        synchronized (AuthTokenService.class) {
            if (tableReady) {
                return;
            }
            try {
                MySQLUtil.executeUpdate(CREATE_AUTH_TOKEN);
                tableReady = true;
            } catch (Exception e) {
                log.warn("auth_token 建表失败（不影响启动）：{}", e.getMessage());
            }
        }
    }

    /**
     * 签发令牌。同一个账号会保留多条（多设备各一份），过期的不再返回。
     *
     * @return 令牌；签发失败返回 null
     */
    public static String issue(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        ensureTable();
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        try {
            MySQLUtil.executeUpdate("delete from auth_token where expire_time < ?",
                    Timestamp.valueOf(now));
            MySQLUtil.executeUpdate(
                    "insert into auth_token(token, username, create_time, expire_time) values(?, ?, ?, ?)",
                    token, username.trim(), Timestamp.valueOf(now),
                    Timestamp.valueOf(now.plusDays(VALID_DAYS)));
            return token;
        } catch (Exception e) {
            log.error("签发令牌失败：username={}", username, e);
            return null;
        }
    }

    /** 用令牌换用户名；无效或过期返回 null */
    public static String resolve(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        ensureTable();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select username from auth_token where token = ? and expire_time > ?")) {
            ps.setString(1, token.trim());
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            log.warn("校验令牌失败：{}", e.getMessage());
            return null;
        }
    }

    /** 退出登录：把这个令牌删掉（其它设备的令牌不受影响） */
    public static void revoke(String token) {
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        try {
            MySQLUtil.executeUpdate("delete from auth_token where token = ?", token.trim());
        } catch (Exception e) {
            log.warn("吊销令牌失败：{}", e.getMessage());
        }
    }
}
