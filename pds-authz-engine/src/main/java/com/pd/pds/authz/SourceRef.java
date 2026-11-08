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
 * @param viaNode     经权限树上级节点展开得到时，该上级节点的名称；直接授予的节点为 null
 */
public record SourceRef(Kind kind, String roleName, SubjectType subjectType, String subjectName,
                        boolean includeChildren, String reason, Instant validTo, String text, String viaNode) {

    public SourceRef(Kind kind, String roleName, SubjectType subjectType, String subjectName,
                     boolean includeChildren, String reason, Instant validTo, String text) {
        this(kind, roleName, subjectType, subjectName, includeChildren, reason, validTo, text, null);
    }

    /** 经权限树上级节点展开得到的来源。 */
    public SourceRef via(String nodeName) {
        return new SourceRef(kind, roleName, subjectType, subjectName, includeChildren, reason, validTo,
                text + "（经上级节点【" + nodeName + "】）", nodeName);
    }

    public enum Kind {
        /** 角色授予（经人员/岗位/组织/用户组绑定） */
        ROLE,
        /** 人员明细直接授予 */
        DIRECT_ALLOW,
        /** 人员明细禁止 */
        DIRECT_DENY,
        /** 组织/岗位/用户组节点直接授予 */
        GRANT_ALLOW,
        /** 组织/岗位/用户组节点禁止 */
        GRANT_DENY,
        /** 资源授权（允许） */
        RESOURCE_ALLOW,
        /** 资源授权（禁止） */
        RESOURCE_DENY,
        /** 权限点已停用 */
        DISABLED
    }
}
