package com.pd.pds.authz;

import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 某人员在某时刻的有效权限（计算结果，不可变视图）。
 */
public final class EffectivePermissions {

    private final String userId;
    private final Instant computedAt;
    private final Map<String, ResolvedScope> permissions;
    private final Map<String, List<String>> permAllowSources;
    private final Map<String, List<String>> permDenySources;
    private final Map<String, Map<ResourceAction, Set<String>>> resources;
    private final Map<String, List<String>> resourceAllowSources;
    private final Map<String, List<String>> resourceDenySources;
    private final Set<String> scenicScope;
    private final int maxSecurityLevel;

    EffectivePermissions(String userId, Instant computedAt,
                         Map<String, ResolvedScope> permissions,
                         Map<String, List<String>> permAllowSources,
                         Map<String, List<String>> permDenySources,
                         Map<String, Map<ResourceAction, Set<String>>> resources,
                         Map<String, List<String>> resourceAllowSources,
                         Map<String, List<String>> resourceDenySources,
                         Set<String> scenicScope,
                         int maxSecurityLevel) {
        this.userId = userId;
        this.computedAt = computedAt;
        this.permissions = permissions;
        this.permAllowSources = permAllowSources;
        this.permDenySources = permDenySources;
        this.resources = resources;
        this.resourceAllowSources = resourceAllowSources;
        this.resourceDenySources = resourceDenySources;
        this.scenicScope = scenicScope;
        this.maxSecurityLevel = maxSecurityLevel;
    }

    public String userId() {
        return userId;
    }

    public Instant computedAt() {
        return computedAt;
    }

    public boolean has(String permCode) {
        return permissions.containsKey(permCode);
    }

    /** 已授予的权限点（按编码排序）。 */
    public Set<String> permissionCodes() {
        return Collections.unmodifiableSet(new TreeSet<>(permissions.keySet()));
    }

    /** 权限点的数据范围；未授予或该权限点无数据范围时返回 null。 */
    public ResolvedScope scopeOf(String permCode) {
        return permissions.get(permCode);
    }

    public Map<String, ResolvedScope> dataScopes() {
        Map<String, ResolvedScope> result = new TreeMap<>();
        permissions.forEach((code, scope) -> {
            if (scope != null && !scope.isEmpty()) {
                result.put(code, scope);
            }
        });
        return result;
    }

    public boolean canAccess(ResourceKey key, ResourceAction action) {
        return resources.getOrDefault(key.type(), Map.of()).getOrDefault(action, Set.of()).contains(key.refId());
    }

    /** 某类资源在某动作下可访问的实例 ID。 */
    public Set<String> accessible(String resourceType, ResourceAction action) {
        return Collections.unmodifiableSet(resources.getOrDefault(resourceType, Map.of()).getOrDefault(action, Set.of()));
    }

    public Set<String> resourceTypes() {
        return Collections.unmodifiableSet(resources.keySet());
    }

    public Set<String> scenicScope() {
        return scenicScope;
    }

    public int maxSecurityLevel() {
        return maxSecurityLevel;
    }

    public Decision explain(String permCode) {
        return new Decision(has(permCode),
                permAllowSources.getOrDefault(permCode, List.of()),
                permDenySources.getOrDefault(permCode, List.of()));
    }

    public Decision explain(ResourceKey key, ResourceAction action) {
        String k = key + "#" + action.name();
        List<String> denies = resourceDenySources.getOrDefault(k, List.of());
        boolean allowed = canAccess(key, action);
        List<String> extra = (!allowed && denies.isEmpty() && resourceAllowSources.containsKey(k))
                ? List.of("资源不在您的景区范围内或未在 PDS 注册") : List.of();
        if (!extra.isEmpty()) {
            return new Decision(false, resourceAllowSources.get(k), extra);
        }
        return new Decision(allowed, resourceAllowSources.getOrDefault(k, List.of()), denies);
    }

    /** 只保留某应用的权限点与资源类型（编码以 "{app}:" 开头）。 */
    public EffectivePermissions forApp(String app) {
        String prefix = app + ":";
        Map<String, ResolvedScope> perms = new LinkedHashMap<>();
        permissions.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                perms.put(k, v);
            }
        });
        Map<String, Map<ResourceAction, Set<String>>> res = new LinkedHashMap<>();
        resources.forEach((type, byAction) -> {
            if (type.startsWith(prefix)) {
                Map<ResourceAction, Set<String>> copy = new EnumMap<>(ResourceAction.class);
                copy.putAll(byAction);
                res.put(type, copy);
            }
        });
        return new EffectivePermissions(userId, computedAt, perms, permAllowSources, permDenySources, res,
                resourceAllowSources, resourceDenySources, scenicScope, maxSecurityLevel);
    }
}
