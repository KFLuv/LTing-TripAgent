package com.iflytek.chatgpt.config;

import com.iflytek.chatgpt.controller.ChatController;
import com.iflytek.chatgpt.service.AuthTokenService;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * 令牌 → 会话 的桥。
 *
 * <p>请求头带 {@code X-Auth-Token} 且有效时，把用户名写进当前会话的
 * {@code loginUser}，于是所有既有接口（它们只认 {@code ChatController.currentUser(session)}）
 * 都能像"已用 Cookie 登录"一样正常工作。不带这个头时完全不影响原有行为。
 *
 * <p>这样小程序端不必依赖 Cookie，桌面端/网页端也一行都不用改。
 */
@Component
public class TokenAuthFilter implements Filter {

    /** 小程序端带的头名（也兼容 {@code ?token=} 查询参数，方便临时用浏览器测） */
    public static final String TOKEN_HEADER = "X-Auth-Token";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest req = (HttpServletRequest) request;
            String token = req.getHeader(TOKEN_HEADER);
            if (token == null || token.trim().isEmpty()) {
                token = req.getParameter("token");
            }
            if (token != null && !token.trim().isEmpty()) {
                String username = AuthTokenService.resolve(token);
                if (username != null) {
                    req.getSession(true).setAttribute(ChatController.SESSION_USER, username);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
