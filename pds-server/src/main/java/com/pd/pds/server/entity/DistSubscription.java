package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 应用的下发订阅 */
@Data
@TableName("dist_subscription")
public class DistSubscription {
    @TableId(type = IdType.INPUT)
    private String appCode;
    private Boolean tokenClaim;
    private Boolean snapshot;
    private Boolean webhook;
    private Boolean scim;
    private String webhookUrl;
    private String secret;
    private String events;
    private Instant updatedAt;
}
