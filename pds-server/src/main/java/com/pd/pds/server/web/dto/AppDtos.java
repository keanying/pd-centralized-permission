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

    /**
     * 清单中的权限节点。type 为权限树节点类型编码（不存在时自动新增该类型）；parent 为可选的父节点编码。
     */
    public record ManifestPermission(@NotBlank String code, @NotBlank String name,
                                     @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}", message = "节点类型编码只能为大写字母、数字和下划线") String type,
                                     String risk, String description, String parent) {

        public ManifestPermission(String code, String name, String type, String risk, String description) {
            this(code, name, type, risk, description, null);
        }
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
                               Integer accessTokenMinutes, Integer refreshTokenHours, Boolean publicClient) {
    }

    public record ClientCredential(String clientId, String clientSecret, String app) {
    }

    public record ClientView(String clientId, String name, String app, List<String> grantTypes, List<String> redirectUris,
                             List<String> scopes, boolean publicClient, Instant issuedAt) {
    }

    public record AppView(String code, String name, String description, String category, String categoryName,
                          boolean accessControl, String manifestVersion, long permissionCount, long resourceTypeCount,
                          long clientCount) {
    }

    public record ResourceView(String id, String type, String refId, String name, String scenicId, String scenicName,
                               String ownerId, long grantCount, Instant updatedAt) {
    }

    public record GrantView(String id, String subjectType, String subjectId, String subjectName, boolean includeChildren,
                            String action, String effect, Instant validFrom, Instant validTo, String reason,
                            String grantedBy, String grantedByName, Instant createdAt) {
    }

    public record GrantRequest(@NotBlank @Pattern(regexp = "USER|POSITION|ORG|GROUP") String subjectType,
                               @NotBlank String subjectId, Boolean includeChildren,
                               @NotBlank @Pattern(regexp = "READ|WRITE|ADMIN") String action,
                               @NotBlank @Pattern(regexp = "ALLOW|DENY") String effect,
                               Instant validFrom, Instant validTo, String reason) {
    }

    public record UpdateApp(String name, String category, String description, Boolean accessControl) {
    }
}
