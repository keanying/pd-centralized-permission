package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 资源实例授权 */
@Data
@TableName("auth_resource_grant")
public class ResourceGrant {
    @TableId(type = IdType.INPUT)
    private String id;
    private String resourceId;
    private String subjectType;
    private String subjectId;
    private Boolean includeChildren;
    private String action;
    private String effect;
    private Instant validFrom;
    private Instant validTo;
    private String reason;
    private String grantedBy;
    private Instant createdAt;
}
