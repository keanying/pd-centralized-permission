package com.pd.pds.server.service;

import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.server.entity.AppClient;
import com.pd.pds.server.mapper.AppClientMapper;
import com.pd.pds.server.mapper.AppInfoMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * OAuth 客户端（接入应用）管理。客户端密钥只在创建时返回一次，库中仅存哈希。
 */
@Service
public class ClientService {

    /** 允许的授权方式。 */
    public static final List<String> GRANT_TYPES = List.of("authorization_code", "refresh_token", "client_credentials",
            "urn:ietf:params:oauth:grant-type:token-exchange");

    private final RegisteredClientRepository clients;
    private final AppClientMapper appClientMapper;
    private final AppInfoMapper appMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public ClientService(RegisteredClientRepository clients, AppClientMapper appClientMapper, AppInfoMapper appMapper,
                         PasswordEncoder passwordEncoder) {
        this.clients = clients;
        this.appClientMapper = appClientMapper;
        this.appMapper = appMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public record Spec(String clientId, String secret, String name, String app, List<String> grantTypes,
                       List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> scopes,
                       boolean requirePkce, Duration accessTokenTtl, Duration refreshTokenTtl, boolean publicClient) {
    }

    public boolean exists(String clientId) {
        return clients.findByClientId(clientId) != null;
    }

    /** 创建客户端，返回明文密钥（只此一次）。 */
    @Transactional
    public String create(Spec spec) {
        if (appMapper.selectById(spec.app()) == null) {
            throw BizException.notFound("应用【" + spec.app() + "】");
        }
        if (exists(spec.clientId())) {
            throw BizException.duplicate("客户端【" + spec.clientId() + "】");
        }
        for (String g : spec.grantTypes()) {
            if (!GRANT_TYPES.contains(g)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "不支持的授权方式：" + g);
            }
        }
        if (spec.grantTypes().contains("authorization_code") && (spec.redirectUris() == null || spec.redirectUris().isEmpty())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "授权码方式必须配置回调地址");
        }
        if (spec.publicClient() && !spec.grantTypes().stream().allMatch(g -> g.equals("authorization_code") || g.equals("refresh_token"))) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "公共客户端（浏览器单页应用）只能使用授权码方式");
        }
        String secret = spec.publicClient() ? null : (spec.secret() != null ? spec.secret() : newSecret());
        RegisteredClient.Builder b = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(spec.clientId())
                .clientName(spec.name())
                .clientIdIssuedAt(Instant.now());
        if (spec.publicClient()) {
            // 浏览器单页应用：无密钥，强制 PKCE；授权服务器不向公共客户端签发刷新令牌
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            b.clientSecret(passwordEncoder.encode(secret))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        }
        spec.grantTypes().forEach(g -> b.authorizationGrantType(new AuthorizationGrantType(g)));
        if (spec.redirectUris() != null) {
            spec.redirectUris().forEach(b::redirectUri);
        }
        if (spec.postLogoutRedirectUris() != null) {
            spec.postLogoutRedirectUris().forEach(b::postLogoutRedirectUri);
        }
        List<String> scopes = spec.scopes() == null || spec.scopes().isEmpty()
                ? (spec.grantTypes().contains("authorization_code") ? List.of(OidcScopes.OPENID, OidcScopes.PROFILE) : List.of("pds.authz"))
                : spec.scopes();
        scopes.forEach(b::scope);
        b.clientSettings(ClientSettings.builder()
                .requireProofKey((spec.publicClient() || spec.requirePkce()) && spec.grantTypes().contains("authorization_code"))
                .requireAuthorizationConsent(false)
                .build());
        b.tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(spec.accessTokenTtl() == null ? Duration.ofMinutes(30) : spec.accessTokenTtl())
                .refreshTokenTimeToLive(spec.refreshTokenTtl() == null ? Duration.ofHours(8) : spec.refreshTokenTtl())
                .reuseRefreshTokens(false)
                .build());
        clients.save(b.build());
        AppClient link = new AppClient();
        link.setClientId(spec.clientId());
        link.setAppCode(spec.app());
        link.setCreatedAt(Instant.now());
        appClientMapper.insert(link);
        return secret;
    }

    public Map<String, Long> countByApp() {
        return appClientMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.groupingBy(AppClient::getAppCode, Collectors.counting()));
    }

    public List<com.pd.pds.server.web.dto.AppDtos.ClientView> listByApp(String app) {
        return appClientMapper.selectList(new LambdaQueryWrapper<AppClient>().eq(app != null, AppClient::getAppCode, app)
                        .orderByAsc(AppClient::getClientId)).stream()
                .map(link -> clients.findByClientId(link.getClientId()))
                .filter(java.util.Objects::nonNull)
                .map(c -> new com.pd.pds.server.web.dto.AppDtos.ClientView(c.getClientId(), c.getClientName(), appOf(c.getClientId()),
                        c.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue).sorted().toList(),
                        c.getRedirectUris().stream().sorted().toList(), c.getScopes().stream().sorted().toList(),
                        c.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE), c.getClientIdIssuedAt()))
                .toList();
    }

    /** 同步已有客户端的回调地址与退出后跳转地址（配置化客户端，如 PDS 管理后台）。 */
    @Transactional
    public void syncRedirectUris(String clientId, List<String> redirectUris, List<String> postLogoutRedirectUris) {
        RegisteredClient existing = clients.findByClientId(clientId);
        if (existing == null) {
            return;
        }
        RegisteredClient updated = RegisteredClient.from(existing)
                .redirectUris(set -> {
                    set.clear();
                    set.addAll(redirectUris);
                })
                .postLogoutRedirectUris(set -> {
                    set.clear();
                    set.addAll(postLogoutRedirectUris);
                })
                .build();
        clients.save(updated);
    }

    /** 客户端所属应用；未登记时返回 null。 */
    public String appOf(String clientId) {
        AppClient link = clientId == null ? null : appClientMapper.selectById(clientId);
        return link == null ? null : link.getAppCode();
    }

    private String newSecret() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
