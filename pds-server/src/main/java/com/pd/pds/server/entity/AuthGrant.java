package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 权限节点授权（人员/组织/岗位/用户组） */
@Data
@TableName("auth_grant")
public class AuthGrant {
    @TableId(type = IdType.INPUT)
    private String id;
    private String subjectType;
    private String subjectId;
    private Boolean includeChildren;
    private String permCode;
    private String effect;
    private String dataScope;
    private String scopeValues;
    private Instant validFrom;
    private Instant validTo;
    private String reason;
    private String source;
    private String grantedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
