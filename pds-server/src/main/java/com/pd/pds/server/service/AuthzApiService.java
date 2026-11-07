package com.pd.pds.server.service;

import com.pd.pds.authz.Decision;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.AuthzDtos;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 鉴权开放 API。调用方规则：
 * <ul>
 *   <li>人员：可查询本人；查询他人需 pds:authz:query；</li>
 *   <li>服务客户端：需 scope pds.authz，且只能查询其所属应用的权限点/资源（scope pds.authz.all 可跨应用，供 PDA 网关同时取 pda 与 pdk 快照）。</li>
 * </ul>
 */
@Service
public class AuthzApiService {

    public static final String SCOPE_AUTHZ = "pds.authz";
    public static final String SCOPE_AUTHZ_ALL = "pds.authz.all";
    private static final int SNAPSHOT_TTL_SECONDS = 300;

    private final AuthzService authz;
    private final ClientService clients;
    private final Guard guard;

    public AuthzApiService(AuthzService authz, ClientService clients, Guard guard) {
        this.authz = authz;
        this.clients = clients;
        this.guard = guard;
    }

    public AuthzDtos.CheckResult check(AuthzDtos.CheckRequest req) {
        EffectivePermissions eff = effectiveFor(req.subject(), appOf(req.item()));
        Decision d = decide(eff, req.item());
        return new AuthzDtos.CheckResult(d.allowed(), d.reason(), authz.version());
    }

    public AuthzDtos.BatchCheckResult batchCheck(AuthzDtos.BatchCheckRequest req) {
        String app = null;
        for (AuthzDtos.CheckItem item : req.items()) {
            String a = appOf(item);
            if (app == null) {
                app = a;
            } else if (!app.equals(a)) {
                app = "*";
            }
        }
        EffectivePermissions eff = effectiveFor(req.subject(), app);
        List<AuthzDtos.CheckResult> results = new ArrayList<>();
        long v = authz.version();
        for (AuthzDtos.CheckItem item : req.items()) {
            Decision d = decide(eff, item);
            results.add(new AuthzDtos.CheckResult(d.allowed(), d.reason(), v));
        }
        return new AuthzDtos.BatchCheckResult(results, v);
    }

    public AuthzDtos.Snapshot snapshot(String app, String subject) {
        if (!StringUtils.hasText(app)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "必须指定应用 app");
        }
        EffectivePermissions eff = effectiveFor(subject, app).forApp(app);
        SysUser u = authz.requireUser(subject);
        Map<String, Object> scopes = new TreeMap<>(eff.dataScopes());
        Map<String, Map<String, Set<String>>> resources = new LinkedHashMap<>();
        for (String type : eff.resourceTypes()) {
            Map<String, Set<String>> byAction = new LinkedHashMap<>();
            for (ResourceAction a : EnumSet.allOf(ResourceAction.class)) {
                byAction.put(a.name().toLowerCase(), eff.accessible(type, a));
            }
            resources.put(type, byAction);
        }
        return new AuthzDtos.Snapshot(authz.version(), app,
                new AuthzDtos.SnapshotUser(u.getId(), u.getName(), u.getScenicId(), u.getPrimaryOrgId()),
                eff.permissionCodes(), scopes, eff.scenicScope(), resources, eff.maxSecurityLevel(),
                List.of(), SNAPSHOT_TTL_SECONDS);
    }

    public AuthzDtos.CheckResult explain(String subject, AuthzDtos.CheckItem item) {
        return check(new AuthzDtos.CheckRequest(subject, item.permission(), item.resourceType(), item.resourceId(), item.action()));
    }

    public Set<String> accessible(String subject, String type, String action) {
        String app = type.contains(":") ? type.substring(0, type.indexOf(':')) : type;
        return effectiveFor(subject, app).accessible(type, ResourceAction.of(action == null ? "READ" : action));
    }

    // ------------------------------------------------------------------ 内部

    private EffectivePermissions effectiveFor(String subject, String app) {
        CurrentActor a = guard.actor();
        if (a.isUser()) {
            if (!subject.equals(a.userId())) {
                guard.require(PdsPermissions.AUTHZ_QUERY);
            }
        } else {
            if (!a.hasScope(SCOPE_AUTHZ) && !a.hasScope(SCOPE_AUTHZ_ALL)) {
                throw new BizException(ErrorCode.FORBIDDEN, "客户端缺少 " + SCOPE_AUTHZ + " 授权范围");
            }
            if (!a.hasScope(SCOPE_AUTHZ_ALL)) {
                String clientApp = clients.appOf(a.clientId());
                if (clientApp == null || app == null || !clientApp.equals(app)) {
                    throw new BizException(ErrorCode.FORBIDDEN, "客户端只能查询其所属应用【" + clientApp + "】的权限");
                }
            }
        }
        authz.requireUser(subject);
        return authz.effective(subject);
    }

    private static String appOf(AuthzDtos.CheckItem item) {
        String code = StringUtils.hasText(item.permission()) ? item.permission() : item.resourceType();
        if (!StringUtils.hasText(code) || !code.contains(":")) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "必须指定 permission，或 resourceType + resourceId + action");
        }
        return code.substring(0, code.indexOf(':'));
    }

    private static Decision decide(EffectivePermissions eff, AuthzDtos.CheckItem item) {
        if (StringUtils.hasText(item.permission())) {
            return eff.explain(item.permission());
        }
        if (!StringUtils.hasText(item.resourceId())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "资源鉴权必须指定 resourceId");
        }
        ResourceAction action = ResourceAction.of(item.action() == null ? "READ" : item.action());
        return eff.explain(new ResourceKey(item.resourceType(), item.resourceId()), action);
    }
}
