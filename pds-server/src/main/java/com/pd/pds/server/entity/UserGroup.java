package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 用户组 */
@Data
@TableName("sys_group")
public class UserGroup {
    @TableId(type = IdType.INPUT)
    private String id;
    private String code;
    private String name;
    private String scenicId;
    private String description;
    private Instant createdAt;
}
