package com.pd.pds.server.web;

import com.pd.pds.common.PageResult;
import com.pd.pds.common.R;
import com.pd.pds.server.service.AssignmentService;
import com.pd.pds.server.service.GraphService;
import com.pd.pds.server.service.OrgNodeService;
import com.pd.pds.server.service.RoleService;
import com.pd.pds.server.web.dto.AssignmentDtos;
import com.pd.pds.server.web.dto.GraphDtos;
import com.pd.pds.server.web.dto.OrgNodeDtos;
import com.pd.pds.server.web.dto.PermTreeDtos;
import com.pd.pds.server.web.dto.RoleDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 权限中心：分配库、架构树节点权限、系统角色扩展、权限图谱。 */
@RestController
@RequestMapping("/api/v1")
public class AssignmentController {

    private final AssignmentService assignments;
    private final OrgNodeService orgNodes;
    private final RoleService roles;
    private final GraphService graph;

    public AssignmentController(AssignmentService assignments, OrgNodeService orgNodes, RoleService roles, GraphService graph) {
        this.assignments = assignments;
        this.orgNodes = orgNodes;
        this.roles = roles;
        this.graph = graph;
    }

    // ---------- 分配库

    @GetMapping("/assignments")
    public R<PageResult<AssignmentDtos.Row>> list(@RequestParam(required = false) String app,
                                                  @RequestParam(required = false) String subjectType,
                                                  @RequestParam(required = false) String subjectId,
                                                  @RequestParam(defaultValue = "false") boolean related,
                                                  @RequestParam(required = false) String objectType,
                                                  @RequestParam(required = false) String permCode,
                                                  @RequestParam(required = false) String roleId,
                                                  @RequestParam(required = false) String source,
                                                  @RequestParam(required = false) String effect,
                                                  @RequestParam(required = false) Integer expiringDays,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long pageSize) {
        return R.ok(assignments.list(new AssignmentService.Query(app, subjectType, subjectId, related, objectType, permCode,
                roleId, source, effect, expiringDays, keyword, Math.max(1, page), Math.min(Math.max(1, pageSize), 200))));
    }

    @GetMapping("/assignments/stats")
    public R<AssignmentDtos.Stats> stats(@RequestParam(required = false) String app) {
        return R.ok(assignments.stats(app));
    }

    @PostMapping("/assignments/impact")
    public R<AssignmentDtos.Impact> impact(@Valid @RequestBody AssignmentDtos.BatchRequest req) {
        return R.ok(assignments.impact(req));
    }

    @PostMapping("/assignments/batch")
    public R<AssignmentDtos.BatchResult> batch(@Valid @RequestBody AssignmentDtos.BatchRequest req) {
        return R.ok(assignments.batch(req));
    }

    @PutMapping("/assignments/{id}")
    public R<Void> update(@PathVariable String id, @RequestBody AssignmentDtos.UpdateRequest req) {
        assignments.update(id, req);
        return R.ok();
    }

    @PostMapping("/assignments/renew")
    public R<Map<String, Integer>> renew(@Valid @RequestBody AssignmentDtos.RenewRequest req) {
        return R.ok(Map.of("count", assignments.renew(req)));
    }

    @PostMapping("/assignments/revoke")
    public R<Map<String, Integer>> revoke(@Valid @RequestBody AssignmentDtos.IdsRequest req) {
        return R.ok(Map.of("count", assignments.revoke(req.ids())));
    }

    @PostMapping("/assignments/copy")
    public R<Map<String, Integer>> copy(@Valid @RequestBody AssignmentDtos.CopyRequest req) {
        return R.ok(Map.of("count", assignments.copy(req)));
    }

    // ---------- 架构树节点

    @GetMapping("/orgs/{id}/info")
    public R<OrgNodeDtos.NodeInfo> orgInfo(@PathVariable String id) {
        return R.ok(orgNodes.info(id));
    }

    @GetMapping("/orgs/{id}/grants")
    public R<List<OrgNodeDtos.NodeGrant>> orgGrants(@PathVariable String id) {
        return R.ok(orgNodes.grants(id));
    }

    @PostMapping("/orgs/{id}/grants")
    public R<Map<String, Integer>> addOrgGrants(@PathVariable String id, @Valid @RequestBody OrgNodeDtos.AddNodeGrants req) {
        return R.ok(Map.of("count", orgNodes.addGrants(id, req)));
    }

    @PutMapping("/orgs/{id}/inherit")
    public R<OrgNodeDtos.NodeInfo> setInherit(@PathVariable String id, @Valid @RequestBody OrgNodeDtos.SetInherit req) {
        return R.ok(orgNodes.setInherit(id, req.inherit()));
    }

    @GetMapping("/orgs/{id}/effective-preview")
    public R<List<OrgNodeDtos.PreviewItem>> orgPreview(@PathVariable String id) {
        return R.ok(orgNodes.effectivePreview(id));
    }

    @GetMapping("/orgs/{id}/positions")
    public R<List<OrgNodeDtos.PositionCount>> orgPositions(@PathVariable String id) {
        return R.ok(orgNodes.positions(id));
    }

    @GetMapping("/orgs/{id}/history")
    public R<List<PermTreeDtos.HistoryItem>> orgHistory(@PathVariable String id) {
        return R.ok(orgNodes.history(id));
    }

    @PostMapping("/orgs/{id}/members/preview")
    public R<OrgNodeDtos.MembersPreview> previewMembers(@PathVariable String id, @Valid @RequestBody OrgNodeDtos.MembersRequest req) {
        return R.ok(orgNodes.previewMembers(id, req));
    }

    @PostMapping("/orgs/{id}/members")
    public R<Map<String, Integer>> addMembers(@PathVariable String id, @Valid @RequestBody OrgNodeDtos.MembersRequest req) {
        return R.ok(Map.of("count", orgNodes.addMembers(id, req)));
    }

    @PutMapping("/orgs/{id}/members/{userId}/inherit")
    public R<Void> memberInherit(@PathVariable String id, @PathVariable String userId, @Valid @RequestBody OrgNodeDtos.SetInherit req) {
        orgNodes.setMemberInherit(id, userId, req.inherit());
        return R.ok();
    }

    // ---------- 系统角色扩展

    @PutMapping("/roles/{id}")
    public R<RoleDtos.RoleView> updateRole(@PathVariable String id, @RequestBody RoleDtos.UpdateRole req) {
        return R.ok(roles.update(id, req));
    }

    @PostMapping("/roles/{id}/copy")
    public R<com.pd.pds.server.entity.Role> copyRole(@PathVariable String id, @Valid @RequestBody RoleDtos.CopyRole req) {
        return R.ok(roles.copy(id, req));
    }

    @DeleteMapping("/roles/{id}")
    public R<Void> deleteRole(@PathVariable String id, @RequestParam(defaultValue = "false") boolean force) {
        roles.delete(id, force);
        return R.ok();
    }

    @PutMapping("/roles/{id}/mutex")
    public R<RoleDtos.RoleView> setMutex(@PathVariable String id, @Valid @RequestBody RoleDtos.SetMutex req) {
        return R.ok(roles.setMutex(id, req));
    }

    @GetMapping("/roles/{id}/mappings")
    public R<List<RoleDtos.MappingView>> mappings(@PathVariable String id) {
        return R.ok(roles.mappings(id));
    }

    @PutMapping("/roles/{id}/mappings")
    public R<List<RoleDtos.MappingView>> setMappings(@PathVariable String id, @Valid @RequestBody RoleDtos.SetMappings req) {
        return R.ok(roles.setMappings(id, req));
    }

    @GetMapping("/roles/{id}/versions")
    public R<List<RoleDtos.RoleVersionView>> versions(@PathVariable String id) {
        return R.ok(roles.versions(id));
    }

    @PostMapping("/roles/{id}/versions/{versionId}/restore")
    public R<RoleDtos.RoleView> restore(@PathVariable String id, @PathVariable String versionId) {
        return R.ok(roles.restore(id, versionId));
    }

    @GetMapping("/roles/{id}/members")
    public R<List<RoleDtos.MemberView>> members(@PathVariable String id) {
        return R.ok(roles.members(id));
    }

    @GetMapping("/roles/{id}/distribution")
    public R<List<RoleDtos.DistributionRow>> distribution(@PathVariable String id) {
        return R.ok(roles.distribution(id));
    }

    @PostMapping("/roles/{id}/reconcile")
    public R<Map<String, Integer>> reconcile(@PathVariable String id) {
        return R.ok(Map.of("apps", roles.fullSync(id)));
    }

    // ---------- 权限图谱

    @GetMapping("/graph/user/{userId}")
    public R<GraphDtos.Graph> userGraph(@PathVariable String userId, @RequestParam(required = false) String app,
                                        @RequestParam(required = false) String risk) {
        return R.ok(graph.person(userId, app, risk));
    }

    @GetMapping("/graph/app/{app}")
    public R<GraphDtos.Graph> appGraph(@PathVariable String app) {
        return R.ok(graph.app(app));
    }

    @GetMapping("/graph/perm")
    public R<GraphDtos.Graph> permGraph(@RequestParam String code) {
        return R.ok(graph.perm(code));
    }

    @GetMapping("/graph/org/{orgId}")
    public R<GraphDtos.Graph> orgGraph(@PathVariable String orgId) {
        return R.ok(graph.org(orgId));
    }

    @GetMapping("/graph/overview")
    public R<GraphDtos.Graph> overview() {
        return R.ok(graph.overview());
    }

    @GetMapping("/graph/explain")
    public R<GraphDtos.Explain> explain(@RequestParam String userId, @RequestParam String code) {
        return R.ok(graph.explain(userId, code));
    }

    @PostMapping("/graph/simulate")
    public R<GraphDtos.SimulateResult> simulate(@Valid @RequestBody GraphDtos.SimulateRequest req) {
        return R.ok(graph.simulate(req));
    }
}
