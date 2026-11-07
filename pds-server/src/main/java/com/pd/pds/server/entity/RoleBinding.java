package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 角色绑定到主体（人员/岗位/组织/用户组） */
@Data
@TableName("auth_role_binding")
public class RoleBinding {
    @TableId(type = IdType.INPUT)
    private String id;
    private String roleId;
    private String subjectType;
    private String subjectId;
    private Boolean includeChildren;
    private Instant validFrom;
    private Instant validTo;
    private String createdBy;
    private Instant createdAt;
}
