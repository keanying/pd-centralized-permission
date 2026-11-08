package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 权限树发布记录 */
@Data
@TableName("perm_tree_version")
public class PermTreeVersion {
    @TableId(type = IdType.INPUT)
    private String id;
    private String appCode;
    private String version;
    private String summary;
    private String changes;
    private String publishedBy;
    private Instant publishedAt;
}
