package com.pd.pds.authz.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 授权事实的各类记录。 */
public final class Facts {

    private Facts() {
    }

    /** 景区编码"GROUP"表示集团共享资源，对所有景区可见（仍需授权）。 */
    public static final String GROUP_SCENIC = "GROUP";

    public record RolePermission(String permCode, DataScope scope) {
    }

    public record Role(String roleId, String name, List<RolePermission> permissions) {
        public Role {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    public record RoleBinding(String roleId, SubjectRef subject, Validity validity) {
    }

    /**
     * 人员明细权限：permCode 与 resource 二选一。resource 不为空时 action 必填。
     */
    public record UserPermission(String id, String permCode, ResourceKey resource, ResourceAction action,
                                 Effect effect, DataScope scope, Validity validity, String reason) {
    }

    /**
     * 权限节点授权：人员、组织（includeChildren=向下继承）、岗位、用户组直接被允许或禁止某权限节点。
     */
    public record PermGrant(String id, String permCode, SubjectRef subject, Effect effect, DataScope scope,
                            Validity validity, String reason) {
    }

    /**
     * 权限树结构。授予父节点 = 授予其全部下级（高风险节点除外，须单独授予）；禁止父节点 = 禁止全部下级。
     *
     * @param parentOf 节点编码 → 父节点编码（根节点不在其中）
     * @param highRisk 高风险节点编码
     * @param names    节点编码 → 名称（用于解释）
     */
    public static final class PermTree {

        public static final PermTree EMPTY = new PermTree(Map.of(), Set.of(), Map.of());

        private final Map<String, String> parentOf;
        private final Set<String> highRisk;
        private final Map<String, String> names;
        private final Map<String, List<String>> children;

        public PermTree(Map<String, String> parentOf, Set<String> highRisk, Map<String, String> names) {
            this.parentOf = parentOf == null ? Map.of() : Map.copyOf(parentOf);
            this.highRisk = highRisk == null ? Set.of() : Set.copyOf(highRisk);
            this.names = names == null ? Map.of() : Map.copyOf(names);
            Map<String, List<String>> idx = new java.util.HashMap<>();
            this.parentOf.forEach((child, parent) -> idx.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(child));
            idx.values().forEach(java.util.Collections::sort);
            this.children = idx;
        }

        public Map<String, String> parentOf() {
            return parentOf;
        }

        public boolean isHighRisk(String code) {
            return highRisk.contains(code);
        }

        public String nameOf(String code) {
            return names.getOrDefault(code, code);
        }

        public boolean isEmpty() {
            return parentOf.isEmpty();
        }

        /** 某节点的全部下级（不含自身）。 */
        public List<String> descendants(String code) {
            List<String> out = new java.util.ArrayList<>();
            java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>(children.getOrDefault(code, List.of()));
            Set<String> seen = new java.util.HashSet<>();
            while (!stack.isEmpty()) {
                String c = stack.pop();
                if (seen.add(c)) {
                    out.add(c);
                    stack.addAll(children.getOrDefault(c, List.of()));
                }
            }
            return out;
        }

        /** 某节点的全部上级（由近及远，不含自身）。 */
        public List<String> ancestors(String code) {
            List<String> out = new java.util.ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            String p = parentOf.get(code);
            while (p != null && seen.add(p)) {
                out.add(p);
                p = parentOf.get(p);
            }
            return out;
        }
    }

    public record Resource(ResourceKey key, String name, String scenicId) {
    }

    public record ResourceGrant(ResourceKey resource, SubjectRef subject, ResourceAction action,
                                Effect effect, Validity validity) {
    }

    public record SecurityLevel(SubjectRef subject, int maxLevel) {
    }

    /** 一次计算所需的全部事实。 */
    public record Bundle(UserProfile user,
                         Map<String, Role> roles,
                         List<RoleBinding> bindings,
                         List<UserPermission> userPermissions,
                         Map<ResourceKey, Resource> resources,
                         List<ResourceGrant> resourceGrants,
                         List<SecurityLevel> securityLevels,
                         Set<String> disabledPermCodes,
                         List<PermGrant> permGrants,
                         PermTree tree) {

        public Bundle(UserProfile user, Map<String, Role> roles, List<RoleBinding> bindings,
                      List<UserPermission> userPermissions, Map<ResourceKey, Resource> resources,
                      List<ResourceGrant> resourceGrants, List<SecurityLevel> securityLevels, Set<String> disabledPermCodes) {
            this(user, roles, bindings, userPermissions, resources, resourceGrants, securityLevels, disabledPermCodes,
                    List.of(), PermTree.EMPTY);
        }

        public Bundle {
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            userPermissions = userPermissions == null ? List.of() : List.copyOf(userPermissions);
            resources = resources == null ? Map.of() : Map.copyOf(resources);
            resourceGrants = resourceGrants == null ? List.of() : List.copyOf(resourceGrants);
            securityLevels = securityLevels == null ? List.of() : List.copyOf(securityLevels);
            disabledPermCodes = disabledPermCodes == null ? Set.of() : Set.copyOf(disabledPermCodes);
            permGrants = permGrants == null ? List.of() : List.copyOf(permGrants);
            tree = tree == null ? PermTree.EMPTY : tree;
        }
    }
}
