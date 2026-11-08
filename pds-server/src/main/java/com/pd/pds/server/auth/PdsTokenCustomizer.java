package com.pd.pds.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.model.UserProfile;
import com.pd.pds.server.config.PdsProperties;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.service.AuditService;
import com.pd.pds.server.service.AuthzService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeCompositeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 为人员令牌（访问令牌与 ID 令牌）写入 PDS 声明：
 * sub/uid（人员 ID）、name、emp_no、dept_id、scenic_id、scenic_scope、authz_ver；
 * 令牌交换签发的票据带 act（代理方客户端），审计据此区分"本人操作"与"智能体代办"。
 *
 * <p>注意：声明值会随授权记录序列化存库，只能使用可变的标准集合（ArrayList / LinkedHashMap），
 * 不可使用 List.of / Map.of 等不可变集合，否则读取授权记录（刷新、令牌交换）时反序列化被拒绝。
 */
@Component
public class PdsTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    public static final String CLAIM_UID = "uid";

    private final SysUserMapper userMapper;
    private final com.pd.pds.server.mapper.AppInfoMapper appMapper;
    private final AuthzService authz;
    private final AuditService audit;
    private final PdsProperties props;
    @org.springframework.beans.factory.annotation.Autowired
    private com.pd.pds.server.service.RoleClaims roleClaims;
    @org.springframework.beans.factory.annotation.Autowired
    private com.pd.pds.server.service.DistributionService distribution;

    public PdsTokenCustomizer(SysUserMapper userMapper, com.pd.pds.server.mapper.AppInfoMapper appMapper,
                              AuthzService authz, AuditService audit, PdsProperties props) {
        this.userMapper = userMapper;
        this.appMapper = appMapper;
        this.authz = authz;
        this.audit = audit;
        this.props = props;
    }

    /**
     * 令牌交换的目标应用（通用规则，适用于所有接入系统）：
     * audience 必须是已在 PDS 注册的应用编码，且发起交换的客户端须登记了 "{应用}.api" 范围。
     * 例如 PDA 插件换取访问 PDK 的票据：audience=pdk、scope=pdk.api。
     */
    private java.util.Set<String> exchangeAudiences(JwtEncodingContext context) {
        java.util.Set<String> audiences = new java.util.LinkedHashSet<>();
        if (context.getAuthorizationGrant() instanceof OAuth2TokenExchangeAuthenticationToken grant) {
            audiences.addAll(grant.getAudiences());
        }
        if (audiences.isEmpty()) {
            throw invalidTarget("令牌交换必须指定目标应用 audience");
        }
        java.util.Set<String> clientScopes = context.getRegisteredClient().getScopes();
        for (String app : audiences) {
            if (appMapper.selectById(app) == null) {
                throw invalidTarget("目标应用未在 PDS 注册：" + app);
            }
            if (!clientScopes.contains(app + ".api")) {
                throw invalidTarget("客户端未被允许换取访问 " + app + " 的票据（需要登记范围 " + app + ".api）");
            }
        }
        return audiences;
    }

    private static OAuth2AuthenticationException invalidTarget(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_target", description, null));
    }

    @Override
    public void customize(JwtEncodingContext context) {
        boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
        boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
        if (!accessToken && !idToken) {
            return;
        }
        Authentication principal = context.getPrincipal();
        if (principal instanceof OAuth2TokenExchangeCompositeAuthenticationToken composite) {
            principal = composite.getSubject();
        }
        if (principal == null || !(principal.getPrincipal() instanceof UserDetails ud)) {
            return; // 服务客户端令牌（client_credentials）不带人员声明
        }
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, ud.getUsername()));
        if (u == null) {
            return;
        }
        UserProfile profile = authz.loadProfile(u);
        var claims = context.getClaims();
        claims.subject(u.getId());
        claims.claim(CLAIM_UID, u.getId());
        claims.claim("name", u.getName());
        claims.claim("preferred_username", u.getUsername());
        if (u.getEmpNo() != null) {
            claims.claim("emp_no", u.getEmpNo());
        }
        if (u.getPrimaryOrgId() != null) {
            claims.claim("dept_id", u.getPrimaryOrgId());
        }
        if (u.getScenicId() != null) {
            claims.claim("scenic_id", u.getScenicId());
        }
        if (profile.scenicScope().size() <= props.getJwt().getMaxScenicInToken()) {
            claims.claim("scenic_scope", new java.util.ArrayList<>(profile.scenicScope()));
        }
        claims.claim("authz_ver", Math.toIntExact(authz.version())); // 用 int：授权记录反序列化只接受 JSON 自然类型

        // 角色下发（令牌声明）：只放与本客户端所属应用相关的角色，应用可在"下发方式"中关闭
        boolean exchangeGrant = AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType());
        if (accessToken && !exchangeGrant) {
            String app = roleClaims.appOfClient(context.getRegisteredClient().getClientId());
            if (app != null && distribution.tokenClaimEnabled(app)) {
                java.util.List<String> roles = roleClaims.rolesFor(u.getId(), app);
                if (!roles.isEmpty()) {
                    claims.claim("roles", roles);
                }
            }
        }

        boolean exchange = AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType());
        if (accessToken && exchange) {
            String actor = context.getRegisteredClient().getClientId();
            claims.audience(new java.util.ArrayList<>(exchangeAudiences(context)));
            java.util.concurrent.atomic.AtomicReference<Object> audience = new java.util.concurrent.atomic.AtomicReference<>();
            claims.claims(c -> {
                java.util.Map<String, Object> act = new java.util.LinkedHashMap<>();
                act.put("sub", actor);
                c.putIfAbsent("act", act);
                audience.set(c.get("aud"));
            });
            audit.recordQuietly(AuditService.Entry.of("TOKEN_EXCHANGE", "令牌交换", "SUCCESS")
                    .actor(u.getId(), u.getName(), null)
                    .target("client", actor)
                    .detail(Map.of("audience", String.valueOf(audience.get()),
                            "tokenType", OAuth2AccessToken.TokenType.BEARER.getValue())));
        }
    }
}
