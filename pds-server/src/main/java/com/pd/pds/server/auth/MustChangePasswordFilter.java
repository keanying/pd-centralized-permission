package com.pd.pds.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 首次登录/重置密码后必须先修改密码：在此之前，除修改密码、退出与静态资源外的页面请求
 * （包括 /oauth2/authorize）一律跳转到修改密码页，原始请求在改密后继续。
 *
 * <p>必须排在 Spring Security 过滤器链（order = -100）之前，否则授权端点会先于本过滤器签发授权码。
 */
@Component
@Order(-200)
public class MustChangePasswordFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/password/change") || path.startsWith("/logout") || path.startsWith("/assets/")
                || path.startsWith("/api/") || path.startsWith("/login") || path.equals("/favicon.ico")
                || path.startsWith("/console");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(LoginHandlers.MUST_CHANGE_PASSWORD))) {
            response.sendRedirect(request.getContextPath() + "/password/change");
            return;
        }
        chain.doFilter(request, response);
    }
}
