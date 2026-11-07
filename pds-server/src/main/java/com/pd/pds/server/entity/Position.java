package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 岗位 */
@Data
@TableName("org_position")
public class Position {
    @TableId(type = IdType.INPUT)
    private String id;
    private String code;
    private String name;
    private String scenicId;
    private Integer status;
    private Instant createdAt;
    private Instant updatedAt;
}
