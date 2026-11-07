package com.pd.pds.server.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDS 自身的权限点与内置角色（启动时注册到 perm_point / auth_role）。
 */
public final class PdsPermissions {

    private PdsPermissions() {
    }

    public static final String APP = "pds";

    public static final String CONSOLE = "pds:console:access";
    public static final String ORG_VIEW = "pds:org:view";
    public static final String ORG_MANAGE = "pds:org:manage";
    public static final String USER_VIEW = "pds:user:view";
    public static final String USER_MANAGE = "pds:user:manage";
    public static final String GROUP_MANAGE = "pds:group:manage";
    public static final String APP_MANAGE = "pds:app:manage";
    public static final String ROLE_VIEW = "pds:role:view";
    public static final String ROLE_MANAGE = "pds:role:manage";
    /** 授权管理（人员明细、资源授权、景区范围、密级）：只能授予自己拥有的权限。 */
    public static final String GRANT_MANAGE = "pds:grant:manage";
    /** 不受"授权不越级"限制（安全管理员）。 */
    public static final String GRANT_ANY = "pds:grant:any";
    public static final String AUTHZ_QUERY = "pds:authz:query";
    public static final String AUDIT_VIEW = "pds:audit:view";
    public static final String SECURITY_MANAGE = "pds:security:manage";

    /** 权限点编码 → 名称。 */
    public static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put(CONSOLE, "进入 PDS 管理后台");
        ALL.put(ORG_VIEW, "查看组织架构");
        ALL.put(ORG_MANAGE, "管理组织架构与岗位");
        ALL.put(USER_VIEW, "查看人员");
        ALL.put(USER_MANAGE, "管理人员账号");
        ALL.put(GROUP_MANAGE, "管理用户组");
        ALL.put(APP_MANAGE, "管理接入应用、权限点与资源");
        ALL.put(ROLE_VIEW, "查看角色");
        ALL.put(ROLE_MANAGE, "管理角色与角色绑定");
        ALL.put(GRANT_MANAGE, "授权管理（人员明细/资源授权/景区范围/密级）");
        ALL.put(GRANT_ANY, "授权不受自身权限限制");
        ALL.put(AUTHZ_QUERY, "权限查询与解释");
        ALL.put(AUDIT_VIEW, "查看审计日志");
        ALL.put(SECURITY_MANAGE, "管理安全策略");
    }

    /** 高风险权限点。 */
    public static final Set<String> HIGH_RISK = Set.of(GRANT_ANY, USER_MANAGE, ROLE_MANAGE, GRANT_MANAGE, APP_MANAGE);

    public static final String ROLE_SUPER_ADMIN = "PDS_SUPER_ADMIN";
    public static final String ROLE_SYS_ADMIN = "PDS_SYS_ADMIN";
    public static final String ROLE_SEC_ADMIN = "PDS_SEC_ADMIN";
    public static final String ROLE_AUDIT_ADMIN = "PDS_AUDIT_ADMIN";

    /** 三员分立：这三个内置角色互斥，不能同时授予同一人员。 */
    public static final Set<String> SEPARATED_ROLES = Set.of(ROLE_SYS_ADMIN, ROLE_SEC_ADMIN, ROLE_AUDIT_ADMIN);

    public record BuiltinRole(String code, String name, String description, List<String> permissions) {
    }

    public static final List<BuiltinRole> BUILTIN_ROLES = List.of(
            new BuiltinRole(ROLE_SUPER_ADMIN, "超级管理员", "仅用于系统初始化，初始化完成后建议停用",
                    List.copyOf(ALL.keySet())),
            new BuiltinRole(ROLE_SYS_ADMIN, "系统管理员", "组织架构、人员账号、应用接入",
                    List.of(CONSOLE, ORG_VIEW, ORG_MANAGE, USER_VIEW, USER_MANAGE, GROUP_MANAGE, APP_MANAGE, ROLE_VIEW)),
            new BuiltinRole(ROLE_SEC_ADMIN, "安全管理员", "角色、授权、人员明细权限、景区范围、密级、安全策略",
                    List.of(CONSOLE, ORG_VIEW, USER_VIEW, ROLE_VIEW, ROLE_MANAGE, GRANT_MANAGE, GRANT_ANY, AUTHZ_QUERY, SECURITY_MANAGE)),
            new BuiltinRole(ROLE_AUDIT_ADMIN, "审计管理员", "查看与导出审计日志",
                    List.of(CONSOLE, ORG_VIEW, USER_VIEW, AUDIT_VIEW)));
}
