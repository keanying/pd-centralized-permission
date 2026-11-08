package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 角色版本快照 */
@Data
@TableName("auth_role_version")
public class RoleVersion {
    @TableId(type = IdType.INPUT)
    private String id;
    private String roleId;
    private Integer version;
    private String summary;
    private String snapshot;
    private String createdBy;
    private Instant createdAt;
}
