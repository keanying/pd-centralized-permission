package com.pd.pds.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.server.config.PdsProperties;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 表单登录成功/失败处理：失败计数与锁定、首次登录强制改密、登录审计。
 */
@Component
public class LoginHandlers {

    public static final String MUST_CHANGE_PASSWORD = "PDS_MUST_CHANGE_PASSWORD";

    private final SysUserMapper userMapper;
    private final AuditService audit;
    private final PdsProperties props;

    public LoginHandlers(SysUserMapper userMapper, AuditService audit, PdsProperties props) {
        this.userMapper = userMapper;
        this.audit = audit;
        this.props = props;
    }

    public AuthenticationSuccessHandler successHandler() {
        SavedRequestAwareAuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/");
        return (request, response, authentication) -> {
            SysUser u = find(authentication.getName());
            if (u != null) {
                userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                        .set(SysUser::getFailedAttempts, 0)
                        .set(SysUser::getLockedUntil, null)
                        .set(SysUser::getLastLoginAt, Instant.now())
                        .set(SysUser::getLastLoginIp, ip(request))
                        .eq(SysUser::getId, u.getId()));
                audit.recordQuietly(AuditService.Entry.of("LOGIN", "账号密码登录", "SUCCESS")
                        .actor(u.getId(), u.getName(), ip(request)));
                if (Boolean.TRUE.equals(u.getMustChangePassword())) {
                    request.getSession().setAttribute(MUST_CHANGE_PASSWORD, Boolean.TRUE);
                    response.sendRedirect(request.getContextPath() + "/password/change");
                    return;
                }
            }
            delegate.onAuthenticationSuccess(request, response, authentication);
        };
    }

    public AuthenticationFailureHandler failureHandler() {
        SimpleUrlAuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler("/login?error");
        return (request, response, exception) -> {
            String username = request.getParameter("username");
            SysUser u = username == null ? null : find(username);
            String reason = reason(exception);
            if (u != null && exception instanceof BadCredentialsException) {
                int attempts = (u.getFailedAttempts() == null ? 0 : u.getFailedAttempts()) + 1;
                LambdaUpdateWrapper<SysUser> w = new LambdaUpdateWrapper<SysUser>()
                        .set(SysUser::getFailedAttempts, attempts).eq(SysUser::getId, u.getId());
                if (attempts >= props.getLogin().getMaxFailedAttempts()) {
                    w.set(SysUser::getLockedUntil, Instant.now().plus(props.getLogin().getLockDuration()));
                    reason = "连续 " + attempts + " 次密码错误，账号已锁定";
                }
                userMapper.update(null, w);
            }
            audit.recordQuietly(AuditService.Entry.of("LOGIN_FAIL", "账号密码登录", "FAILURE")
                    .actor(u == null ? null : u.getId(), username, ip(request))
                    .detail(Map.of("reason", reason)));
            delegate.onAuthenticationFailure(request, response, exception);
        };
    }

    /** 登录页上显示的中文原因（不区分"账号不存在"与"密码错误"，防止账号枚举）。 */
    public static String reason(AuthenticationException e) {
        if (e instanceof LockedException) {
            return "账号已锁定，请稍后再试或联系管理员";
        }
        if (e instanceof DisabledException) {
            return "账号已停用，请联系管理员";
        }
        return "账号或密码错误";
    }

    private SysUser find(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    private static String ip(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }
}
