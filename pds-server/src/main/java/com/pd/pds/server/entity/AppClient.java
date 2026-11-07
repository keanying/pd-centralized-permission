package com.pd.pds.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 应用与 OAuth 客户端的对应关系 */
@Data
@TableName("app_client")
public class AppClient {
    @TableId(type = IdType.INPUT)
    private String clientId;
    private String appCode;
    private Instant createdAt;
}
