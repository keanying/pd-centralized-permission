package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 人员账号 */
@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.INPUT)
    private String id;
    private String username;
    private String empNo;
    private String name;
    private String mobile;
    private String email;
    private String userType;
    private String status;
    private String primaryOrgId;
    private String scenicId;
    private String passwordHash;
    private Boolean mustChangePassword;
    private Instant passwordChangedAt;
    private Integer failedAttempts;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private String lastLoginIp;
    private Long tokenEpoch;
    private Instant createdAt;
    private Instant updatedAt;
}
