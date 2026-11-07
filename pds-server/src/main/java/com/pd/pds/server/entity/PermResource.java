package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 资源实例（如某个知识库） */
@Data
@TableName("perm_resource")
public class PermResource {
    @TableId(type = IdType.INPUT)
    private String id;
    private String typeCode;
    private String refId;
    private String appCode;
    private String name;
    private String scenicId;
    private String ownerId;
    private Instant createdAt;
    private Instant updatedAt;
}
