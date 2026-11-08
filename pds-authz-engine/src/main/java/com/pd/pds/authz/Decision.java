package com.pd.pds.authz;

import java.util.List;

/**
 * 鉴权结论与解释。allowed=false 时 denySources 为空表示"未被授予"，否则表示被禁止规则拦截。
 */
public record Decision(boolean allowed, List<String> allowSources, List<String> denySources,
                       List<SourceRef> allowRefs, List<SourceRef> denyRefs) {

    public Decision {
        allowSources = List.copyOf(allowSources);
        denySources = List.copyOf(denySources);
        allowRefs = List.copyOf(allowRefs);
        denyRefs = List.copyOf(denyRefs);
    }

    public static Decision of(boolean allowed, List<SourceRef> allow, List<SourceRef> deny) {
        return new Decision(allowed, allow.stream().map(SourceRef::text).toList(), deny.stream().map(SourceRef::text).toList(),
                allow, deny);
    }

    /** 中文解释，用于"权限查询与解释"页面与 check 接口的 reason 字段。 */
    public String reason() {
        if (!denySources.isEmpty()) {
            return "禁止 —— " + String.join("；", denySources)
                    + (allowSources.isEmpty() ? "" : "（另有授予来源被禁止规则覆盖：" + String.join("；", allowSources) + "）");
        }
        if (allowed) {
            return "允许 —— " + String.join("；", allowSources);
        }
        return "未授予 —— 没有任何角色、岗位、组织、用户组或人员明细授予此权限";
    }
}
