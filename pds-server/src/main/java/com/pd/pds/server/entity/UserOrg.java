package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 人员任职（一人可多部门/多岗位） */
@Data
@TableName("sys_user_org")
public class UserOrg {
    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String orgId;
    private String positionId;
    private Boolean isPrimary;
}
