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
                         Set<String> disabledPermCodes) {
        public Bundle {
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            userPermissions = userPermissions == null ? List.of() : List.copyOf(userPermissions);
            resources = resources == null ? Map.of() : Map.copyOf(resources);
            resourceGrants = resourceGrants == null ? List.of() : List.copyOf(resourceGrants);
            securityLevels = securityLevels == null ? List.of() : List.copyOf(securityLevels);
            disabledPermCodes = disabledPermCodes == null ? Set.of() : Set.copyOf(disabledPermCodes);
        }
    }
}
