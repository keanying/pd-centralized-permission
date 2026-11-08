package com.pd.pds.server.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 架构树节点：节点权限、继承、加人预览。 */
public final class OrgNodeDtos {

    private OrgNodeDtos() {
    }

    public record NodeInfo(String id, String name, String type, String code, String scenicId, String leaderId,
                           String leaderName, long memberCount, long directMemberCount, boolean inheritFromParent,
                           boolean root, String parentName) {
    }

    /**
     * 节点上的权限或角色。inherited=true 表示从上级组织继承（该上级授权设为"本节点及下级"）。
     */
    public record NodeGrant(String id, String objectType, String code, String name, String appCode, String appName,
                            String risk, boolean inherited, String fromOrgId, String fromOrgName, boolean includeChildren,
                            String effect, String dataScope, Instant validTo) {
    }

    public record AddNodeGrants(List<String> permCodes, List<String> roleIds, Boolean includeChildren,
                                @Pattern(regexp = "ALLOW|DENY") String effect, String dataScope, Set<String> scopeValues,
                                Instant validTo, String reason) {
    }

    public record SetInherit(@NotNull Boolean inherit) {
    }

    public record PreviewItem(String code, String name, String appCode, String appName, String risk, List<String> sources) {
    }

    public record MembersRequest(@NotEmpty List<String> userIds, Boolean primary, String positionId) {
    }

    public record MemberImpact(String userId, String name, String empNo, int added, int lost, List<String> highRisk,
                               boolean alreadyMember) {
    }

    /** 加人前的权限变化预览（新增/失去为全部人员的并集）。 */
    public record MembersPreview(int added, List<String> addedNames, int highRiskCount, List<String> highRisk, int lost,
                                 List<String> lostNames, List<MemberImpact> users) {
    }

    public record PositionCount(String positionId, String code, String name, long count) {
    }
}
