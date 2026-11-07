package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 资源类型 */
@Data
@TableName("perm_resource_type")
public class ResourceType {
    @TableId(type = IdType.INPUT)
    private String typeCode;
    private String appCode;
    private String name;
    private String actions;
}
