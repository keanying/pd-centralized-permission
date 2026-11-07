package com.pd.pds.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.service.AppService;
import com.pd.pds.server.service.AuditService;
import com.pd.pds.server.service.AuthzService;
import com.pd.pds.server.service.ClientService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 应用准入（通用，适用于所有接入系统）：人员单点登录进入某应用（授权码请求）时，
 * 若该应用开启了准入控制，须拥有 {app}:access（PDS 自身为 pds:console:access），否则以 access_denied 返回应用。
 */
@Component
public class AppAccessValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    private final ClientService clients;
    private final AppInfoMapper appMapper;
    private final SysUserMapper userMapper;
    private final AuthzService authz;
    private final AuditService audit;

    public AppAccessValidator(ClientService clients, AppInfoMapper appMapper, SysUserMapper userMapper,
                              AuthzService authz, AuditService audit) {
        this.clients = clients;
        this.appMapper = appMapper;
        this.userMapper = userMapper;
        this.authz = authz;
        this.audit = audit;
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        Authentication principal = (Authentication) request.getPrincipal();
        if (principal == null || !principal.isAuthenticated() || "anonymousUser".equals(principal.getName())) {
            return; // 尚未登录：先走登录页，登录后会再次校验
        }
        String app = clients.appOf(context.getRegisteredClient().getClientId());
        AppInfo info = app == null ? null : appMapper.selectById(app);
        if (info == null || !Boolean.TRUE.equals(info.getAccessControl())) {
            return;
        }
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, principal.getName()));
        String permission = AppService.accessPermission(app);
        if (u != null && authz.effective(u.getId()).has(permission)) {
            return;
        }
        audit.recordQuietly(AuditService.Entry.of("ACCESS_DENIED", "登录进入应用 " + app, "DENY")
                .actor(u == null ? null : u.getId(), principal.getName(), null)
                .target("app", app)
                .detail(Map.of("reason", "缺少应用准入权限 " + permission)));
        throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED, "您没有进入【" + info.getName() + "】的权限，请在 PDS 申请", null),
                request);
    }
}
