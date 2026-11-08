package com.pd.pds.server.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public final class UserDtos {

    private UserDtos() {
    }

    public record CreateUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.@-]{3,64}", message = "账号只能包含字母、数字和 _.@-，长度 3-64") String username,
                             String empNo, @NotBlank String name, String mobile, String email,
                             String userType, @NotBlank String orgId, String positionId, String password) {
    }

    public record UpdateUser(String name, String mobile, String email, String userType, String empNo) {
    }

    public record Assignment(@NotBlank String orgId, String positionId, Boolean primary) {
    }

    public record AssignOrgs(@NotEmpty List<@Valid Assignment> assignments) {
    }

    public record StatusChange(String reason) {
    }

    public record ChangePassword(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    /** 创建人员 / 重置密码时一次性返回的初始密码。 */
    public record Credential(String userId, String username, String initialPassword) {
    }

    public record UserView(String id, String username, String empNo, String name, String mobile, String email,
                           String userType, String status, String primaryOrgId, String primaryOrgName, String scenicId,
                           List<String> positions, Instant lastLoginAt, boolean locked) {
    }

    /** 批量导入的一行：组织、岗位按编码匹配。 */
    public record ImportRow(String username, String empNo, String name, String mobile, String email, String userType,
                            String orgCode, String positionCode) {
    }

    public record ImportRequest(@NotEmpty List<ImportRow> rows) {
    }

    public record ImportResult(int row, boolean ok, String message, String userId, String username, String initialPassword) {
    }

    public record Me(String id, String username, String name, String scenicId, String primaryOrgId,
                     boolean mustChangePassword, List<String> pdsPermissions) {
    }
}
