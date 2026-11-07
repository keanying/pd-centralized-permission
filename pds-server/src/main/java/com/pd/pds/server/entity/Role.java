package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 角色 */
@Data
@TableName("auth_role")
public class Role {
    @TableId(type = IdType.INPUT)
    private String id;
    private String code;
    private String name;
    private String level;
    private String scenicId;
    private String description;
    private Boolean builtin;
    private Instant createdAt;
    private Instant updatedAt;
}
