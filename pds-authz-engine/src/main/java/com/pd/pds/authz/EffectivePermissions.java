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
    private final Map<String, List<SourceRef>> permAllowSources;
    private final Map<String, List<SourceRef>> permDenySources;
    private final Map<String, Map<ResourceAction, Set<String>>> resources;
    private final Map<String, List<SourceRef>> resourceAllowSources;
    private final Map<String, List<SourceRef>> resourceDenySources;
    private final Set<String> scenicScope;
    private final int maxSecurityLevel;
    private final Set<String> visibleCodes;

    EffectivePermissions(String userId, Instant computedAt,
                         Map<String, ResolvedScope> permissions,
                         Map<String, List<SourceRef>> permAllowSources,
                         Map<String, List<SourceRef>> permDenySources,
                         Map<String, Map<ResourceAction, Set<String>>> resources,
                         Map<String, List<SourceRef>> resourceAllowSources,
                         Map<String, List<SourceRef>> resourceDenySources,
                         Set<String> scenicScope,
                         int maxSecurityLevel,
                         Set<String> visibleCodes) {
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
        this.visibleCodes = visibleCodes == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(visibleCodes));
    }

    /** 因下级节点被授予而"路径可见"的上级节点（如菜单、业务域），不等于拥有该节点权限。 */
    public Set<String> visibleCodes() {
        return visibleCodes;
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
        return Decision.of(has(permCode),
                permAllowSources.getOrDefault(permCode, List.of()),
                permDenySources.getOrDefault(permCode, List.of()));
    }

    public Decision explain(ResourceKey key, ResourceAction action) {
        String k = key + "#" + action.name();
        List<SourceRef> denies = resourceDenySources.getOrDefault(k, List.of());
        boolean allowed = canAccess(key, action);
        if (!allowed && denies.isEmpty() && resourceAllowSources.containsKey(k)) {
            SourceRef outOfScope = new SourceRef(SourceRef.Kind.RESOURCE_DENY, null, null, null, false, null, null,
                    "资源不在您的景区范围内或未在 PDS 注册");
            return Decision.of(false, resourceAllowSources.get(k), List.of(outOfScope));
        }
        return Decision.of(allowed, resourceAllowSources.getOrDefault(k, List.of()), denies);
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
        Set<String> vis = new TreeSet<>();
        visibleCodes.forEach(v -> {
            if (v.startsWith(prefix)) {
                vis.add(v);
            }
        });
        return new EffectivePermissions(userId, computedAt, perms, permAllowSources, permDenySources, res,
                resourceAllowSources, resourceDenySources, scenicScope, maxSecurityLevel, vis);
    }
}
