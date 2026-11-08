package com.pd.pds.authz;

import com.pd.pds.authz.model.SubjectType;

import java.time.Instant;

/**
 * 权限来源（结构化），用于管理后台展示来源标签与有效期；text 为完整中文说明。
 *
 * @param roleName    来源为角色时的角色名
 * @param subjectType 授权对象类型（角色绑定、资源授权时有值；人员明细为 USER）
 * @param subjectName 授权对象名称
 * @param validTo     失效时间，null 表示长期
 */
public record SourceRef(Kind kind, String roleName, SubjectType subjectType, String subjectName,
                        boolean includeChildren, String reason, Instant validTo, String text) {

    public enum Kind {
        /** 角色授予（经人员/岗位/组织/用户组绑定） */
        ROLE,
        /** 人员明细直接授予 */
        DIRECT_ALLOW,
        /** 人员明细禁止 */
        DIRECT_DENY,
        /** 资源授权（允许） */
        RESOURCE_ALLOW,
        /** 资源授权（禁止） */
        RESOURCE_DENY,
        /** 权限点已停用 */
        DISABLED
    }
}
