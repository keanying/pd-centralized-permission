package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 下发事件 */
@Data
@TableName("dist_event")
public class DistEvent {
    @TableId(type = IdType.INPUT)
    private String id;
    private String type;
    private String refType;
    private String refId;
    private String payload;
    private Instant createdAt;
}
