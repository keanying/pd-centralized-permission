package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 人员明细权限（直接授予/禁止） */
@Data
@TableName("auth_user_perm")
public class UserPerm {
    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String permCode;
    private String resourceId;
    private String action;
    private String effect;
    private String dataScope;
    private String scopeValues;
    private Instant validFrom;
    private Instant validTo;
    private String reason;
    private String grantedBy;
    private Instant createdAt;
}
