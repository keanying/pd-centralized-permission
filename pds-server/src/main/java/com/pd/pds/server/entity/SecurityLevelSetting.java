package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 密级授权 */
@Data
@TableName("auth_security_level")
public class SecurityLevelSetting {
    @TableId(type = IdType.INPUT)
    private String id;
    private String subjectType;
    private String subjectId;
    private Boolean includeChildren;
    private Integer maxLevel;
}
