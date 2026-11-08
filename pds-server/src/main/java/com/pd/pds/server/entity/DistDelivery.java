package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 事件投递记录 */
@Data
@TableName("dist_delivery")
public class DistDelivery {
    @TableId(type = IdType.INPUT)
    private String id;
    private String eventId;
    private String appCode;
    private String status;
    private Integer attempts;
    private String lastError;
    private Integer latencyMs;
    private Instant nextAt;
    private Instant createdAt;
    private Instant updatedAt;
}
