package com.pd.pds.server.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AuthzDtos {

    private AuthzDtos() {
    }

    /** permission 与 resourceType+resourceId+action 二选一。 */
    public record CheckItem(String permission, String resourceType, String resourceId, String action) {
    }

    public record CheckRequest(@NotBlank String subject, String permission, String resourceType, String resourceId,
                               String action) {
        public CheckItem item() {
            return new CheckItem(permission, resourceType, resourceId, action);
        }
    }

    public record BatchCheckRequest(@NotBlank String subject, @NotEmpty @Size(max = 100) List<@Valid CheckItem> items) {
    }

    public record CheckResult(boolean allowed, String reason, long version) {
    }

    public record BatchCheckResult(List<CheckResult> results, long version) {
    }

    public record SnapshotUser(String id, String name, String scenicId, String deptId) {
    }

    public record Snapshot(long version, String app, SnapshotUser user, Set<String> permissions,
                           Map<String, Object> dataScopes, Set<String> scenicScope,
                           Map<String, Map<String, Set<String>>> resources, int maxSecurityLevel,
                           List<Object> maskPolicies, int expiresIn, List<String> roles, Set<String> visible) {
    }
}
