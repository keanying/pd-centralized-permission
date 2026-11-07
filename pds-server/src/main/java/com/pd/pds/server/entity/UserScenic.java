package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 人员景区范围 */
@Data
@TableName("auth_user_scenic")
public class UserScenic {
    private String userId;
    private String scenicId;
}
