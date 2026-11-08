package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 权限节点类型（字典，可维护） */
@Data
@TableName("perm_node_type")
public class PermNodeType {
    @TableId(type = IdType.INPUT)
    private String code;
    private String name;
    private String icon;
    private String color;
    private String parentTypes;
    private Boolean grantable;
    private Boolean dataScope;
    private String defaultRisk;
    private String description;
    private Integer sort;
    private Instant createdAt;
    private Instant updatedAt;
}
