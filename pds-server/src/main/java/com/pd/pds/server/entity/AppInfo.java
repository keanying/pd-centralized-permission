package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 接入应用 */
@Data
@TableName("app_info")
public class AppInfo {
    @TableId(type = IdType.INPUT)
    private String code;
    private String name;
    private String description;
    /** 应用分类（对应总体架构分层），见 AppCategories。 */
    private String category;
    /** 是否开启应用准入：开启后人员须拥有 {app}:access 才能单点登录进入该应用。 */
    private Boolean accessControl;
    /** 访问地址（门户中的应用入口跳转到这里，由应用自行发起单点登录）。 */
    private String homeUrl;
    private String manifestVersion;
    private Integer status;
    private Instant createdAt;
    private Instant updatedAt;
}
