package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 组织节点（集团/公司/景区/部门/班组） */
@Data
@TableName("org_unit")
public class OrgUnit {
    @TableId(type = IdType.INPUT)
    private String id;
    private String parentId;
    private String path;
    private String type;
    private String scenicId;
    private String code;
    private String name;
    private String leaderId;
    private Integer sort;
    private Integer status;
    private Instant createdAt;
    private Instant updatedAt;
}
