package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 角色包含的权限点及数据范围 */
@Data
@TableName("auth_role_perm")
public class RolePerm {
    @TableId(type = IdType.INPUT)
    private String id;
    private String roleId;
    private String permCode;
    private String dataScope;
    private String scopeValues;
}
