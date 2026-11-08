package com.pd.pds.server.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** 权限图谱。 */
public final class GraphDtos {

    private GraphDtos() {
    }

    /**
     * 节点。type：USER / ORG / POSITION / GROUP / ROLE / APP / PERM / RESOURCE；
     * state（权限、资源节点）：ALLOW / DENY / NONE。
     */
    public record Node(String id, String type, String label, String sub, String risk, String state, boolean center,
                       Map<String, Object> meta) {
    }

    /** 关系。kind：BELONG / PARENT / HOLD / MEMBER / BIND / CONTAIN / GRANT / DENY / IN_APP。 */
    public record Edge(String id, String source, String target, String kind, String label) {
    }

    public record Graph(List<Node> nodes, List<Edge> edges, boolean truncated, Map<String, Object> summary) {
    }

    /** 路径解释：某人为什么有/没有某权限。 */
    public record Explain(String userId, String code, String name, String app, String risk, String verdict,
                          List<PathView> allow, List<PathView> deny, String lastUsed, Long unusedDays) {
    }

    public record PathView(List<String> steps, String validTo, String text) {
    }

    public record SimulateRequest(@NotBlank String userId, String orgId, Boolean primary, String positionId,
                                  List<String> roleIds) {
    }

    public record SimulateResult(List<String> added, List<String> highRisk, List<String> lost) {
    }
}
