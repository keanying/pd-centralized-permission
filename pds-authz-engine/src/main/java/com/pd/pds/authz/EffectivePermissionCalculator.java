package com.pd.pds.authz;

import com.pd.pds.authz.model.DataScope;
import com.pd.pds.authz.model.Effect;
import com.pd.pds.authz.model.Facts;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.authz.model.SubjectRef;
import com.pd.pds.authz.model.SubjectType;
import com.pd.pds.authz.model.UserProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 有效权限计算（无状态、线程安全）。
 *
 * <p>规则（与《PDS 产品功能文档》§4.4.1 一致）：
 * <ol>
 *   <li>来源合并：本人、岗位、所在组织（及开启"含下级"的上级组织）、用户组上绑定的角色，加上人员明细直接授予；</li>
 *   <li>禁止优先：人员明细或资源授权中的"禁止"覆盖任何授予；</li>
 *   <li>景区约束：资源实例只在人员景区范围内（或集团共享 GROUP）生效；</li>
 *   <li>有效期：过期或未生效的授权忽略；</li>
 *   <li>数据范围：同一权限点多来源取并集；</li>
 *   <li>资源动作层级：ADMIN ⊇ WRITE ⊇ READ；</li>
 *   <li>密级：取各来源最大值，人员本人上的密级设置优先。</li>
 * </ol>
 */
public final class EffectivePermissionCalculator {

    public EffectivePermissions compute(Facts.Bundle facts, Instant now) {
        UserProfile user = facts.user();

        Map<String, ResolvedScope> perms = new LinkedHashMap<>();
        Map<String, List<SourceRef>> permAllow = new HashMap<>();
        Map<String, List<SourceRef>> permDeny = new HashMap<>();

        // 1. 角色来源
        for (Facts.RoleBinding binding : facts.bindings()) {
            if (!binding.validity().isActive(now) || !user.matches(binding.subject())) {
                continue;
            }
            Facts.Role role = facts.roles().get(binding.roleId());
            if (role == null) {
                continue;
            }
            SourceRef source = new SourceRef(SourceRef.Kind.ROLE, role.name(), binding.subject().type(),
                    user.nameOf(binding.subject()), binding.subject().includeChildren(), null, binding.validity().to(),
                    "角色【" + role.name() + "】经 " + user.label(binding.subject()) + " 授予");
            for (Facts.RolePermission rp : role.permissions()) {
                grantPerm(perms, permAllow, rp.permCode(), rp.scope(), source, user);
            }
        }

        // 2. 人员明细（功能权限点）
        for (Facts.UserPermission up : facts.userPermissions()) {
            if (up.permCode() == null || up.resource() != null || !up.validity().isActive(now)) {
                continue;
            }
            String reason = up.reason() == null || up.reason().isBlank() ? "" : "（原因：" + up.reason() + "）";
            if (up.effect() == Effect.ALLOW) {
                grantPerm(perms, permAllow, up.permCode(), up.scope(), direct(SourceRef.Kind.DIRECT_ALLOW, up, user,
                        "人员明细直接授予" + reason), user);
            } else {
                permDeny.computeIfAbsent(up.permCode(), k -> new ArrayList<>())
                        .add(direct(SourceRef.Kind.DIRECT_DENY, up, user, "人员明细禁止" + reason));
            }
        }

        // 3. 禁止优先 + 停用的权限点
        permDeny.keySet().forEach(perms::remove);
        for (String disabled : facts.disabledPermCodes()) {
            if (perms.remove(disabled) != null) {
                permDeny.computeIfAbsent(disabled, k -> new ArrayList<>())
                        .add(new SourceRef(SourceRef.Kind.DISABLED, null, null, null, false, null, null, "权限点已停用"));
            }
        }

        // 4. 资源实例
        Set<String> scenicScope = Collections.unmodifiableSet(new TreeSet<>(user.scenicScope()));
        Map<String, List<SourceRef>> resAllow = new HashMap<>();
        Map<String, List<SourceRef>> resDeny = new HashMap<>();
        Map<ResourceKey, EnumMap<ResourceAction, Boolean>> allowMap = new HashMap<>();
        Map<ResourceKey, EnumMap<ResourceAction, Boolean>> denyMap = new HashMap<>();

        for (Facts.ResourceGrant g : facts.resourceGrants()) {
            if (!g.validity().isActive(now) || !user.matches(g.subject())) {
                continue;
            }
            SourceRef ref = new SourceRef(g.effect() == Effect.ALLOW ? SourceRef.Kind.RESOURCE_ALLOW : SourceRef.Kind.RESOURCE_DENY,
                    null, g.subject().type(), user.nameOf(g.subject()), g.subject().includeChildren(), null, g.validity().to(),
                    "资源授权给 " + user.label(g.subject()));
            applyResource(g.resource(), g.action(), g.effect(), ref, allowMap, denyMap, resAllow, resDeny);
        }
        for (Facts.UserPermission up : facts.userPermissions()) {
            if (up.resource() == null || up.action() == null || !up.validity().isActive(now)) {
                continue;
            }
            String reason = up.reason() == null || up.reason().isBlank() ? "" : "（原因：" + up.reason() + "）";
            SourceRef ref = direct(up.effect() == Effect.ALLOW ? SourceRef.Kind.DIRECT_ALLOW : SourceRef.Kind.DIRECT_DENY, up, user,
                    (up.effect() == Effect.ALLOW ? "人员明细直接授予" : "人员明细禁止") + reason);
            applyResource(up.resource(), up.action(), up.effect(), ref, allowMap, denyMap, resAllow, resDeny);
        }

        Map<String, Map<ResourceAction, Set<String>>> resources = new LinkedHashMap<>();
        allowMap.forEach((key, actions) -> {
            Facts.Resource meta = facts.resources().get(key);
            if (meta == null || !inScenicScope(meta.scenicId(), scenicScope)) {
                return;
            }
            EnumMap<ResourceAction, Boolean> denied = denyMap.getOrDefault(key, new EnumMap<>(ResourceAction.class));
            actions.keySet().forEach(action -> {
                if (!denied.containsKey(action)) {
                    resources.computeIfAbsent(key.type(), t -> new EnumMap<>(ResourceAction.class))
                            .computeIfAbsent(action, a -> new TreeSet<>())
                            .add(key.refId());
                }
            });
        });

        // 5. 密级：人员本人设置优先，否则取最大值
        int level = 0;
        Integer personal = null;
        for (Facts.SecurityLevel sl : facts.securityLevels()) {
            if (!user.matches(sl.subject())) {
                continue;
            }
            if (sl.subject().type() == SubjectType.USER) {
                personal = sl.maxLevel();
            } else {
                level = Math.max(level, sl.maxLevel());
            }
        }
        if (personal != null) {
            level = personal;
        }

        return new EffectivePermissions(user.userId(), now, perms, freeze(permAllow), freeze(permDeny),
                resources, freeze(resAllow), freeze(resDeny), scenicScope, level);
    }

    private static boolean inScenicScope(String scenicId, Set<String> scope) {
        return scenicId == null || Facts.GROUP_SCENIC.equals(scenicId) || scope.contains(scenicId);
    }

    private static SourceRef direct(SourceRef.Kind kind, Facts.UserPermission up, UserProfile user, String text) {
        return new SourceRef(kind, null, SubjectType.USER, user.name(), false, up.reason(), up.validity().to(), text);
    }

    private static void grantPerm(Map<String, ResolvedScope> perms, Map<String, List<SourceRef>> sources,
                                  String code, DataScope scope, SourceRef source, UserProfile user) {
        ResolvedScope resolved = perms.computeIfAbsent(code, k -> new ResolvedScope());
        if (scope != null) {
            resolve(resolved, scope, user);
        }
        sources.computeIfAbsent(code, k -> new ArrayList<>()).add(source);
    }

    private static void resolve(ResolvedScope target, DataScope scope, UserProfile user) {
        switch (scope.type()) {
            case ALL -> target.markAll();
            case SELF -> target.markSelf();
            case DEPT -> target.addOrgs(user.orgIds());
            case DEPT_TREE -> target.addOrgTrees(user.orgIds());
            case SCENIC -> {
                if (user.scenicId() != null) {
                    target.addScenics(Set.of(user.scenicId()));
                }
            }
            case SCENIC_LIST -> target.addScenics(scope.values());
            case CUSTOM -> target.addOrgs(scope.values());
            case DOMAIN -> target.addDomains(scope.values());
        }
    }

    private static void applyResource(ResourceKey key, ResourceAction action, Effect effect, SourceRef source,
                                      Map<ResourceKey, EnumMap<ResourceAction, Boolean>> allowMap,
                                      Map<ResourceKey, EnumMap<ResourceAction, Boolean>> denyMap,
                                      Map<String, List<SourceRef>> allowSources,
                                      Map<String, List<SourceRef>> denySources) {
        boolean allow = effect == Effect.ALLOW;
        Set<ResourceAction> affected = allow ? action.grantedActions() : action.deniedActions();
        Map<ResourceKey, EnumMap<ResourceAction, Boolean>> target = allow ? allowMap : denyMap;
        Map<String, List<SourceRef>> sources = allow ? allowSources : denySources;
        SourceRef label = new SourceRef(source.kind(), source.roleName(), source.subjectType(), source.subjectName(),
                source.includeChildren(), source.reason(), source.validTo(), source.text() + "（" + action.label() + "）");
        for (ResourceAction a : affected) {
            target.computeIfAbsent(key, k -> new EnumMap<>(ResourceAction.class)).put(a, Boolean.TRUE);
            sources.computeIfAbsent(key + "#" + a.name(), k -> new ArrayList<>()).add(label);
        }
    }

    private static Map<String, List<SourceRef>> freeze(Map<String, List<SourceRef>> map) {
        Map<String, List<SourceRef>> copy = new HashMap<>();
        map.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    /** 便捷方法：判断某授权对象是否可被此人员匹配（供服务层计算受影响人员时复用）。 */
    public static boolean matches(UserProfile user, SubjectRef subject) {
        return user.matches(subject);
    }
}
