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
    /** 节点 ID（系统生成，不可变） */
    private String id;
    private String parentId;
    private Integer sort;
    private Integer securityLevel;
    private Boolean grantable;
    private Boolean needMfa;
    private Boolean dataScopeEnabled;
    /** MANIFEST（应用清单）/ MANUAL（后台维护） */
    private String source;
    /** 后台修改过（应用再次提交清单时不覆盖名称、风险等） */
    private Boolean customized;
    /** 已发布节点的待发布修改（JSON） */
    private String pending;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
