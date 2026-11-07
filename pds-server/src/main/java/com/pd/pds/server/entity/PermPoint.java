package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 权限点 */
@Data
@TableName("perm_point")
public class PermPoint {
    @TableId(type = IdType.INPUT)
    private String code;
    private String appCode;
    private String name;
    private String type;
    private String risk;
    private String description;
    private Integer status;
    private String manifestVersion;
    private Instant createdAt;
    private Instant updatedAt;
}
