package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 审计事件（哈希链） */
@Data
@TableName("audit_event")
public class AuditEvent {
    @TableId(type = IdType.INPUT)
    private Long seq;
    private String id;
    private Instant ts;
    private String app;
    private String eventType;
    private String userId;
    private String userName;
    private String via;
    private String scenicId;
    private String ip;
    private String targetType;
    private String targetIds;
    private String action;
    private String result;
    private String detail;
    private String traceId;
    private String prevHash;
    private String hash;
}
