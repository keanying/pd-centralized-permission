package com.pd.pds.server.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 分配库：全部授权记录的统一视图与批量操作。 */
public final class AssignmentDtos {

    private AssignmentDtos() {
    }

    /**
     * 一条授权记录。id 带类型前缀：g:（权限节点授权）、rb:（角色绑定）、rg:（资源授权）、up:（人员资源明细）。
     *
     * @param objectType PERM（权限节点）/ ACCESS（应用准入）/ ROLE / RESOURCE
     */
    public record Row(String id, String kind, String subjectType, String subjectId, String subjectName, String subjectSub,
                      boolean includeChildren, String objectType, String objectCode, String objectName, String appCode,
                      String appName, String risk, String action, String effect, String dataScope, Instant validFrom,
                      Instant validTo, String source, String reason, String grantedBy, Instant createdAt) {
    }

    public record Stats(long accessUsers, long records, long highRisk, long expiring) {
    }

    public record SubjectRef(@NotBlank @Pattern(regexp = "USER|POSITION|ORG|GROUP") String type, @NotBlank String id,
                             Boolean includeChildren) {
    }

    /** 授权对象：PERM（code）/ ROLE（roleId）/ RESOURCE（resourceType + refId + action）。 */
    public record ObjectRef(@NotBlank @Pattern(regexp = "PERM|ROLE|RESOURCE") String type, String code, String roleId,
                            String resourceType, String refId, @Pattern(regexp = "READ|WRITE|ADMIN") String action) {
    }

    public record BatchRequest(@NotEmpty List<@Valid SubjectRef> subjects, @NotEmpty List<@Valid ObjectRef> objects,
                               @NotBlank @Pattern(regexp = "ALLOW|DENY") String effect, String dataScope,
                               Set<String> scopeValues, Instant validFrom, Instant validTo, String reason) {
    }

    public record UserImpact(String userId, String name, String empNo, int added, List<String> highRisk, int lost) {
    }

    public record Conflict(String userName, String detail) {
    }

    /**
     * 影响分析。sampled 为实际逐人计算的人数（超过上限时只计算前若干人）。
     */
    public record Impact(int affectedUsers, int sampled, int addedPerms, int highRiskCount, List<String> highRiskAdded,
                         int lostPerms, List<Conflict> conflicts, List<String> exceeding, int outOfScope,
                         List<String> outOfScopeNames, List<UserImpact> users) {
    }

    public record BatchResult(int created) {
    }

    public record UpdateRequest(Instant validTo, Boolean clearValidTo, String reason) {
    }

    public record IdsRequest(@NotEmpty List<String> ids) {
    }

    public record RenewRequest(@NotEmpty List<String> ids, Instant validTo, Boolean clearValidTo) {
    }

    public record CopyRequest(@NotBlank String fromUserId, @NotEmpty List<String> toUserIds, Boolean includeRoles,
                              Boolean includePerms, Boolean includeResources) {
    }
}
