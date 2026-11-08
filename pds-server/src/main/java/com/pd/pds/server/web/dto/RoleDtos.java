package com.pd.pds.server.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class RoleDtos {

    private RoleDtos() {
    }

    public record CreateRole(@NotBlank String code, @NotBlank String name,
                             @Pattern(regexp = "GROUP|SCENIC") String level, String scenicId, String description) {
    }

    public record RolePermission(@NotBlank String permCode,
                                 @Pattern(regexp = "SELF|DEPT|DEPT_TREE|SCENIC|SCENIC_LIST|ALL|CUSTOM|DOMAIN") String dataScope,
                                 Set<String> scopeValues) {
    }

    public record SetPermissions(@NotNull List<@Valid RolePermission> permissions) {
    }

    public record Bind(@NotBlank @Pattern(regexp = "USER|POSITION|ORG|GROUP") String subjectType,
                       @NotBlank String subjectId, Boolean includeChildren, Instant validFrom, Instant validTo) {
    }

    /** 角色绑定（subjectName 在按主体查询时为角色名称）。 */
    public record BindingView(String id, String roleId, String subjectType, String subjectId, String subjectName,
                              boolean includeChildren, Instant validFrom, Instant validTo, String createdBy, Instant createdAt) {
    }

    public record UserPermView(String id, String permCode, String permName, String resourceType, String resourceId,
                               String resourceName, String action, String effect, String dataScope, String scopeValues,
                               Instant validFrom, Instant validTo, String reason, String grantedBy, Instant createdAt) {
    }

    public record RoleView(String id, String code, String name, String level, String scenicId, String description,
                           boolean builtin, List<RolePermission> permissions, long bindingCount) {
    }

    /** 人员明细权限：permCode 与 resourceType+resourceId 二选一。 */
    public record UserPermRequest(String permCode, String resourceType, String resourceId,
                                  @Pattern(regexp = "READ|WRITE|ADMIN") String action,
                                  @NotBlank @Pattern(regexp = "ALLOW|DENY") String effect,
                                  @Pattern(regexp = "SELF|DEPT|DEPT_TREE|SCENIC|SCENIC_LIST|ALL|CUSTOM|DOMAIN") String dataScope,
                                  Set<String> scopeValues, Instant validFrom, Instant validTo,
                                  @NotBlank(message = "必须填写授权原因") String reason) {
    }

    public record ScenicScope(@NotNull Set<String> scenicIds) {
    }

    public record SecurityLevelRequest(@NotBlank @Pattern(regexp = "USER|POSITION|ORG|GROUP") String subjectType,
                                       @NotBlank String subjectId, Boolean includeChildren,
                                       @NotNull @Min(0) @Max(3) Integer maxLevel) {
    }

    /** 人员明细权限页：某应用下每个权限点的状态与来源。 */
    public record PermissionState(String code, String name, String type, String risk, String state,
                                  List<String> allowSources, List<String> denySources, Object dataScope,
                                  List<com.pd.pds.authz.SourceRef> allowRefs, List<com.pd.pds.authz.SourceRef> denyRefs) {
    }

    /**
     * 资源授权卡片。denied=true 表示被禁止；validTo 为可访问的最晚失效时间（null 为长期）。
     */
    public record ResourceState(String type, String refId, String name, String scenicId, List<String> actions,
                                String reason, boolean denied, Instant validTo,
                                List<com.pd.pds.authz.SourceRef> allowRefs, List<com.pd.pds.authz.SourceRef> denyRefs) {
    }

    public record UpdateUserPerm(Instant validTo, Boolean clearValidTo, String reason) {
    }

    public record UserEffective(String userId, String name, String app, Set<String> scenicScope, int maxSecurityLevel,
                                List<PermissionState> permissions, List<ResourceState> resources, long version,
                                String status) {
    }
}
