package com.pd.pds.server.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public final class AppDtos {

    private AppDtos() {
    }

    public record ManifestPermission(@NotBlank String code, @NotBlank String name,
                                     @NotBlank @Pattern(regexp = "MENU|BUTTON|API|FUNCTION|DATA|AI_TOOL|AI_MODEL|AI_SKILL") String type,
                                     String risk, String description) {
    }

    public record ManifestResourceType(@NotBlank String type, @NotBlank String name, List<String> actions) {
    }

    /** 应用权限清单：新增/更新权限点；清单中不再出现的权限点被停用（不删除，保留授权记录）。 */
    public record Manifest(@NotBlank String name, String description, @NotBlank String version,
                           @NotNull List<@Valid ManifestPermission> permissions,
                           List<@Valid ManifestResourceType> resourceTypes,
                           String category, Boolean accessControl) {

        public Manifest(String name, String description, String version, List<ManifestPermission> permissions,
                        List<ManifestResourceType> resourceTypes) {
            this(name, description, version, permissions, resourceTypes, null, null);
        }
    }

    public record ManifestResult(String app, String version, int added, int updated, int disabled) {
    }

    public record RegisterResource(@NotBlank String type, @NotBlank String refId, @NotBlank String name,
                                   @NotBlank String scenicId, String ownerId) {
    }

    public record CreateClient(@NotBlank @Pattern(regexp = "[a-z0-9-]{3,64}", message = "客户端 ID 只能包含小写字母、数字和-") String clientId,
                               @NotBlank String name,
                               @NotNull List<String> grantTypes,
                               List<String> redirectUris, List<String> postLogoutRedirectUris,
                               List<String> scopes, Boolean requirePkce,
                               Integer accessTokenMinutes, Integer refreshTokenHours) {
    }

    public record ClientCredential(String clientId, String clientSecret, String app) {
    }

    public record GrantRequest(@NotBlank @Pattern(regexp = "USER|POSITION|ORG|GROUP") String subjectType,
                               @NotBlank String subjectId, Boolean includeChildren,
                               @NotBlank @Pattern(regexp = "READ|WRITE|ADMIN") String action,
                               @NotBlank @Pattern(regexp = "ALLOW|DENY") String effect,
                               Instant validFrom, Instant validTo, String reason) {
    }
}
