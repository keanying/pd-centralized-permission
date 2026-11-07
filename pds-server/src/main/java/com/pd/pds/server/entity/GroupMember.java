package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 用户组成员 */
@Data
@TableName("sys_group_member")
public class GroupMember {
    private String groupId;
    private String userId;
}
