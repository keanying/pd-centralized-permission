package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 角色在目标应用中的外部编码 */
@Data
@TableName("auth_role_mapping")
public class RoleMapping {
    @TableId(type = IdType.INPUT)
    private String id;
    private String roleId;
    private String appCode;
    private String externalCode;
}
