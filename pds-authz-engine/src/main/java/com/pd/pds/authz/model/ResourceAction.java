package com.pd.pds.authz.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 资源实例动作，层级关系 ADMIN ⊇ WRITE ⊇ READ。
 * 授予高级动作自动包含低级动作；禁止低级动作同时禁止更高级动作。
 */
public enum ResourceAction {
    READ("查看/检索"),
    WRITE("编辑"),
    ADMIN("管理");

    private final String label;

    ResourceAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 授予本动作时实际获得的动作集合。 */
    public Set<ResourceAction> grantedActions() {
        EnumSet<ResourceAction> set = EnumSet.noneOf(ResourceAction.class);
        for (ResourceAction a : values()) {
            if (a.ordinal() <= ordinal()) {
                set.add(a);
            }
        }
        return set;
    }

    /** 禁止本动作时实际被禁止的动作集合。 */
    public Set<ResourceAction> deniedActions() {
        EnumSet<ResourceAction> set = EnumSet.noneOf(ResourceAction.class);
        for (ResourceAction a : values()) {
            if (a.ordinal() >= ordinal()) {
                set.add(a);
            }
        }
        return set;
    }

    public static ResourceAction of(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
