package com.pd.pds.server.web.dto;

import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/** 下发与集成。 */
public final class DistDtos {

    private DistDtos() {
    }

    /** 应用的下发方式。secretSet 表示已配置签名密钥（密钥本身不返回）。 */
    public record SubscriptionView(String appCode, String appName, boolean tokenClaim, boolean snapshot, boolean webhook,
                                   boolean scim, String webhookUrl, boolean secretSet, String events, Instant updatedAt) {
    }

    public record SaveSubscription(Boolean tokenClaim, Boolean snapshot, Boolean webhook, Boolean scim,
                                   @Pattern(regexp = "^$|https?://.+", message = "推送地址须为 http(s) 地址") String webhookUrl,
                                   String secret, String events) {
    }

    public record DeliveryView(String id, String eventId, String eventType, String eventLabel, String appCode,
                               String appName, String status, int attempts, String lastError, Integer latencyMs,
                               Instant nextAt, Instant createdAt, Instant updatedAt) {
    }

    public record EventPayload(String id, String type, String payload) {
    }
}
