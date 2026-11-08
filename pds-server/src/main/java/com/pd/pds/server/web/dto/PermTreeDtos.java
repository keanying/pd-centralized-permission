package com.pd.pds.server.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

/** 权限树与节点类型。 */
public final class PermTreeDtos {

    private PermTreeDtos() {
    }

    /** 节点类型。usage 为使用该类型的节点数（大于 0 时不能删除）。 */
    public record NodeTypeView(String code, String name, String icon, String color, List<String> parentTypes,
                               boolean grantable, boolean dataScope, String defaultRisk, String description,
                               int sort, long usage) {
    }

    public record SaveNodeType(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}", message = "类型编码只能为大写字母、数字和下划线") String code,
                               @NotBlank String name, String icon, String color, List<String> parentTypes,
                               Boolean grantable, Boolean dataScope,
                               @Pattern(regexp = "LOW|MEDIUM|HIGH") String defaultRisk, String description, Integer sort) {
    }

    /** 权限树中的应用（树根）。 */
    public record TreeApp(String code, String name, String category, long nodeCount, long pendingCount) {
    }

    /**
     * 权限节点（草稿视图：待发布的修改已合并显示）。
     *
     * @param draft      NEW（新建未发布）/ MODIFIED（有待发布修改）/ DELETING（待删除）/ null
     * @param codeLocked 编码是否已锁定（发布后锁定）
     * @param status     1 生效 / 0 停用 / 2 草稿
     */
    public record NodeView(String id, String code, String appCode, String parentId, String name, String type,
                           String typeName, String risk, Integer securityLevel, boolean grantable, boolean needMfa,
                           boolean dataScopeEnabled, String source, int status, String description, int sort,
                           String draft, boolean codeLocked, Instant updatedAt) {
    }

    public record NodeDetail(NodeView node, List<PathItem> path, List<NodeView> children, long holderCount) {
    }

    public record PathItem(String id, String code, String name) {
    }

    public record CreateNode(@NotBlank String appCode, String parentId, @NotBlank String type, @NotBlank String name,
                             @Pattern(regexp = "[a-z0-9_]{1,32}", message = "编码标识只能为小写字母、数字和下划线") String segment,
                             @Pattern(regexp = "LOW|MEDIUM|HIGH") String risk, Integer securityLevel, Boolean grantable,
                             Boolean needMfa, Boolean dataScopeEnabled, String description) {
    }

    /** 修改节点：字段为 null 表示不改；code 仅草稿节点可改。 */
    public record UpdateNode(String code, String name, String type, @Pattern(regexp = "LOW|MEDIUM|HIGH") String risk,
                             Integer securityLevel, Boolean clearSecurityLevel, Boolean grantable, Boolean needMfa,
                             Boolean dataScopeEnabled, String description, String parentId, Boolean moveToRoot,
                             Integer sort) {
    }

    public record FieldChange(String field, String label, String before, String after) {
    }

    public record Change(String nodeId, String code, String name, String kind, List<FieldChange> fields) {
    }

    public record Publish(String summary) {
    }

    public record VersionView(String id, String appCode, String version, String summary, int changeCount,
                              String publishedBy, Instant publishedAt) {
    }

    /** 拥有某节点的人员（含来源摘要）。 */
    public record Holder(String userId, String name, String empNo, String deptName, List<String> sources) {
    }

    public record Holders(long total, List<Holder> items) {
    }

    public record HistoryItem(String action, String userName, Instant ts, String detail) {
    }

    public record CreateApp(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{1,31}", message = "应用编码只能为小写字母开头的字母数字组合") String code,
                            @NotBlank String name, String category, String description, Boolean accessControl) {
    }
}
