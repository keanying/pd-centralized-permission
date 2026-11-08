package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 权限编码兼容别名（旧编码 → 新编码） */
@Data
@TableName("perm_code_alias")
public class PermCodeAlias {
    @TableId(type = IdType.INPUT)
    private String oldCode;
    private String newCode;
    private String appCode;
    private String reason;
    private String createdBy;
    private Instant createdAt;
}
