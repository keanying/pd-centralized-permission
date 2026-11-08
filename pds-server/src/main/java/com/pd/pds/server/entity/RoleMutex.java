package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 角色互斥 */
@Data
@TableName("auth_role_mutex")
public class RoleMutex {
    private String roleId;
    private String otherRoleId;
}
