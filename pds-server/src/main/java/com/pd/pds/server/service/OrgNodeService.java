package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.authz.Decision;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.ResolvedScope;
import com.pd.pds.authz.SourceRef;
import com.pd.pds.authz.model.Facts;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.AuditEvent;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.PositionMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.AssignmentDtos;
import com.pd.pds.server.web.dto.OrgNodeDtos;
import com.pd.pds.server.web.dto.PermTreeDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 架构树节点：组织即权限。节点上可直接授予/禁止权限节点、挂载角色；"本节点及下级"的授权向下继承，
 * 节点可阻断继承上级；加人、调岗前预览权限变化。
 */
@Service
public class OrgNodeService {

    private final OrgUnitMapper orgMapper;
    private final UserOrgMapper userOrgMapper;
    private final SysUserMapper userMapper;
    private final PositionMapper positionMapper;
    private final AuthGrantMapper grantMapper;
    private final RoleBindingMapper bindingMapper;
    private final RoleMapper roleMapper;
    private final PermPointMapper pointMapper;
    private final AppInfoMapper appMapper;
    private final OrgService orgService;
    private final AssignmentService assignments;
    private final SubjectMembers members;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public OrgNodeService(OrgUnitMapper orgMapper, UserOrgMapper userOrgMapper, SysUserMapper userMapper,
                          PositionMapper positionMapper, AuthGrantMapper grantMapper, RoleBindingMapper bindingMapper,
                          RoleMapper roleMapper, PermPointMapper pointMapper, AppInfoMapper appMapper, OrgService orgService,
                          AssignmentService assignments, SubjectMembers members, Guard guard, AuthzService authz,
                          AuditService audit) {
        this.orgMapper = orgMapper;
        this.userOrgMapper = userOrgMapper;
        this.userMapper = userMapper;
        this.positionMapper = positionMapper;
        this.grantMapper = grantMapper;
        this.bindingMapper = bindingMapper;
        this.roleMapper = roleMapper;
        this.pointMapper = pointMapper;
        this.appMapper = appMapper;
        this.orgService = orgService;
        this.assignments = assignments;
        this.members = members;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    public OrgNodeDtos.NodeInfo info(String orgId) {
        guard.require(PdsPermissions.ORG_VIEW);
        OrgUnit o = orgService.require(orgId);
        long direct = userOrgMapper.selectCount(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getOrgId, orgId));
        long total = members.of("ORG", orgId, true).size();
        SysUser leader = o.getLeaderId() == null ? null : userMapper.selectById(o.getLeaderId());
        OrgUnit parent = o.getParentId() == null ? null : orgMapper.selectById(o.getParentId());
        return new OrgNodeDtos.NodeInfo(o.getId(), o.getName(), o.getType(), o.getCode(), o.getScenicId(), o.getLeaderId(),
                leader == null ? null : leader.getName(), total, direct, !Boolean.FALSE.equals(o.getInheritFromParent()),
                OrgService.ROOT_ID.equals(o.getId()), parent == null ? null : parent.getName());
    }

    // ------------------------------------------------------------------ 节点权限

    public List<OrgNodeDtos.NodeGrant> grants(String orgId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        OrgUnit o = orgService.require(orgId);
        Map<String, PermPoint> perms = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>()
                        .select(PermPoint::getCode, PermPoint::getName, PermPoint::getAppCode, PermPoint::getRisk)).stream()
                .collect(Collectors.toMap(PermPoint::getCode, p -> p));
        Map<String, Role> roles = roleMapper.selectList(new LambdaQueryWrapper<>()).stream().collect(Collectors.toMap(Role::getId, r -> r));
        Map<String, String> apps = appMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));
        List<OrgNodeDtos.NodeGrant> out = new ArrayList<>();
        addRows(out, o, false, perms, roles, apps);
        Map<String, OrgUnit> cache = new HashMap<>();
        for (String aid : authz.inheritedAncestors(o, cache)) {
            OrgUnit a = cache.get(aid);
            if (a != null) {
                addRows(out, a, true, perms, roles, apps);
            }
        }
        return out;
    }

    private void addRows(List<OrgNodeDtos.NodeGrant> out, OrgUnit org, boolean inherited, Map<String, PermPoint> perms,
                         Map<String, Role> roles, Map<String, String> apps) {
        LambdaQueryWrapper<AuthGrant> gw = new LambdaQueryWrapper<AuthGrant>().eq(AuthGrant::getSubjectType, "ORG")
                .eq(AuthGrant::getSubjectId, org.getId()).orderByAsc(AuthGrant::getCreatedAt);
        if (inherited) {
            gw.eq(AuthGrant::getIncludeChildren, true);
        }
        for (AuthGrant g : grantMapper.selectList(gw)) {
            PermPoint p = perms.get(g.getPermCode());
            String app = p == null ? null : p.getAppCode();
            boolean access = app != null && g.getPermCode().equals(AppService.accessPermission(app));
            out.add(new OrgNodeDtos.NodeGrant("g:" + g.getId(), access ? "ACCESS" : "PERM", g.getPermCode(),
                    access ? "应用准入" : (p == null ? g.getPermCode() : p.getName()), app, app == null ? null : apps.get(app),
                    p == null ? null : p.getRisk(), inherited, org.getId(), org.getName(), Boolean.TRUE.equals(g.getIncludeChildren()),
                    g.getEffect(), g.getDataScope(), g.getValidTo()));
        }
        LambdaQueryWrapper<RoleBinding> bw = new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getSubjectType, "ORG")
                .eq(RoleBinding::getSubjectId, org.getId()).orderByAsc(RoleBinding::getCreatedAt);
        if (inherited) {
            bw.eq(RoleBinding::getIncludeChildren, true);
        }
        for (RoleBinding b : bindingMapper.selectList(bw)) {
            Role r = roles.get(b.getRoleId());
            String app = r == null ? null : r.getAppCode();
            out.add(new OrgNodeDtos.NodeGrant("rb:" + b.getId(), "ROLE", b.getRoleId(), r == null ? b.getRoleId() : r.getName(),
                    app, app == null ? null : apps.get(app), null, inherited, org.getId(), org.getName(),
                    Boolean.TRUE.equals(b.getIncludeChildren()), "ALLOW", null, b.getValidTo()));
        }
    }

    @Transactional
    public int addGrants(String orgId, OrgNodeDtos.AddNodeGrants req) {
        orgService.require(orgId);
        List<AssignmentDtos.ObjectRef> objects = new ArrayList<>();
        if (req.permCodes() != null) {
            req.permCodes().forEach(c -> objects.add(new AssignmentDtos.ObjectRef("PERM", c, null, null, null, null)));
        }
        if (req.roleIds() != null) {
            req.roleIds().forEach(r -> objects.add(new AssignmentDtos.ObjectRef("ROLE", null, r, null, null, null)));
        }
        if (objects.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请选择要添加的权限或角色");
        }
        boolean children = req.includeChildren() == null || req.includeChildren();
        return assignments.batch(new AssignmentDtos.BatchRequest(
                List.of(new AssignmentDtos.SubjectRef("ORG", orgId, children)), objects,
                req.effect() == null ? "ALLOW" : req.effect(), req.dataScope(), req.scopeValues(), null, req.validTo(),
                StringUtils.hasText(req.reason()) ? req.reason() : "组织节点授权")).created();
    }

    @Transactional
    public OrgNodeDtos.NodeInfo setInherit(String orgId, boolean inherit) {
        ResolvedScope scope = guard.require(PdsPermissions.GRANT_MANAGE);
        OrgUnit o = orgService.require(orgId);
        guard.requireCovers(scope, o, PdsPermissions.GRANT_MANAGE);
        if (OrgService.ROOT_ID.equals(orgId)) {
            throw new BizException(ErrorCode.CONFLICT, "根组织没有上级，无需设置继承");
        }
        orgMapper.update(null, new LambdaUpdateWrapper<OrgUnit>().eq(OrgUnit::getId, orgId)
                .set(OrgUnit::getInheritFromParent, inherit).set(OrgUnit::getUpdatedAt, Instant.now()));
        authz.bumpVersion();
        auditOrg(inherit ? "恢复继承上级权限" : "阻断继承上级权限", orgId, Map.of("name", o.getName()));
        return info(orgId);
    }

    /** 仅因在此节点任职会获得的权限（含上级继承）。 */
    public List<OrgNodeDtos.PreviewItem> effectivePreview(String orgId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        OrgUnit o = orgService.require(orgId);
        EffectivePermissions eff = authz.previewOrg(o);
        Map<String, PermPoint> perms = pointMapper.selectByIds(eff.permissionCodes().isEmpty() ? Set.of("_") : eff.permissionCodes())
                .stream().collect(Collectors.toMap(PermPoint::getCode, p -> p));
        Map<String, String> apps = appMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));
        List<OrgNodeDtos.PreviewItem> out = new ArrayList<>();
        for (String code : eff.permissionCodes()) {
            PermPoint p = perms.get(code);
            Decision d = eff.explain(code);
            List<String> sources = d.allowRefs().stream().map(PermTreeService::sourceLabel).distinct().toList();
            String app = p == null ? null : p.getAppCode();
            out.add(new OrgNodeDtos.PreviewItem(code, p == null ? code : p.getName(), app, app == null ? null : apps.get(app),
                    p == null ? null : p.getRisk(), sources));
        }
        return out;
    }

    public List<OrgNodeDtos.PositionCount> positions(String orgId) {
        guard.require(PdsPermissions.ORG_VIEW);
        orgService.require(orgId);
        Map<String, Long> counts = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getOrgId, orgId)
                        .isNotNull(UserOrg::getPositionId)).stream()
                .collect(Collectors.groupingBy(UserOrg::getPositionId, Collectors.counting()));
        if (counts.isEmpty()) {
            return List.of();
        }
        return positionMapper.selectByIds(counts.keySet()).stream()
                .map(p -> new OrgNodeDtos.PositionCount(p.getId(), p.getCode(), p.getName(), counts.get(p.getId())))
                .sorted(Comparator.comparing(OrgNodeDtos.PositionCount::count).reversed())
                .toList();
    }

    public List<PermTreeDtos.HistoryItem> history(String orgId) {
        guard.require(PdsPermissions.ORG_VIEW);
        orgService.require(orgId);
        List<PermTreeDtos.HistoryItem> out = new ArrayList<>();
        for (AuditEvent e : audit.byTarget("org", orgId, 50)) {
            out.add(new PermTreeDtos.HistoryItem(e.getAction(), e.getUserName(), e.getTs(), e.getDetail()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 添加人员

    public OrgNodeDtos.MembersPreview previewMembers(String orgId, OrgNodeDtos.MembersRequest req) {
        guard.require(PdsPermissions.USER_VIEW);
        OrgUnit o = orgService.require(orgId);
        boolean primary = req.primary() == null || req.primary();
        Facts.PermTree tree = authz.tree().tree();
        Set<String> added = new TreeSet<>();
        Set<String> lost = new TreeSet<>();
        Set<String> high = new TreeSet<>();
        List<OrgNodeDtos.MemberImpact> users = new ArrayList<>();
        for (String uid : new LinkedHashSet<>(req.userIds())) {
            SysUser u = authz.requireUser(uid);
            boolean already = userOrgMapper.selectCount(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, uid)
                    .eq(UserOrg::getOrgId, orgId)) > 0;
            EffectivePermissions before = authz.effective(uid);
            AuthzService.Simulation sim = new AuthzService.Simulation(primary ? Set.of() : Set.of(o.getId()),
                    primary ? o.getId() : null, StringUtils.hasText(req.positionId()) ? Set.of(req.positionId()) : Set.of(),
                    List.of(), List.of(), List.of(), Map.of(), Set.of());
            EffectivePermissions after = authz.simulate(uid, sim);
            Set<String> plus = new TreeSet<>(after.permissionCodes());
            plus.removeAll(before.permissionCodes());
            Set<String> minus = new TreeSet<>(before.permissionCodes());
            minus.removeAll(after.permissionCodes());
            List<String> hr = plus.stream().filter(tree::isHighRisk).map(tree::nameOf).toList();
            added.addAll(plus);
            lost.addAll(minus);
            high.addAll(hr);
            users.add(new OrgNodeDtos.MemberImpact(uid, u.getName(), u.getEmpNo(), plus.size(), minus.size(), hr, already));
        }
        return new OrgNodeDtos.MembersPreview(added.size(), added.stream().map(tree::nameOf).toList(), high.size(),
                List.copyOf(high), lost.size(), lost.stream().map(tree::nameOf).toList(), users);
    }

    /** 添加人员：主属 = 把此人的主部门调整为本节点（原主部门任职被替换）；兼职 = 新增一条兼职任职。 */
    @Transactional
    public int addMembers(String orgId, OrgNodeDtos.MembersRequest req) {
        ResolvedScope scope = guard.require(PdsPermissions.USER_MANAGE);
        OrgUnit o = orgService.require(orgId);
        guard.requireCovers(scope, o, PdsPermissions.USER_MANAGE);
        if (StringUtils.hasText(req.positionId()) && positionMapper.selectById(req.positionId()) == null) {
            throw BizException.notFound("岗位");
        }
        boolean primary = req.primary() == null || req.primary();
        int n = 0;
        for (String uid : new LinkedHashSet<>(req.userIds())) {
            SysUser u = authz.requireUser(uid);
            UserOrg existing = userOrgMapper.selectOne(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, uid)
                    .eq(UserOrg::getOrgId, orgId).last("LIMIT 1"));
            if (primary) {
                UserOrg cur = userOrgMapper.selectOne(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, uid)
                        .eq(UserOrg::getIsPrimary, true).last("LIMIT 1"));
                if (existing != null && (cur == null || !existing.getId().equals(cur.getId()))) {
                    userOrgMapper.deleteById(existing.getId());
                }
                if (cur == null) {
                    insert(uid, orgId, req.positionId(), true);
                } else {
                    userOrgMapper.update(null, new LambdaUpdateWrapper<UserOrg>().eq(UserOrg::getId, cur.getId())
                            .set(UserOrg::getOrgId, orgId).set(UserOrg::getPositionId, req.positionId())
                            .set(UserOrg::getInheritPerms, true));
                }
                userMapper.update(null, new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, uid)
                        .set(SysUser::getPrimaryOrgId, orgId).set(SysUser::getScenicId, o.getScenicId())
                        .set(SysUser::getUpdatedAt, Instant.now()));
            } else if (existing == null) {
                insert(uid, orgId, req.positionId(), false);
            } else if (StringUtils.hasText(req.positionId())) {
                userOrgMapper.update(null, new LambdaUpdateWrapper<UserOrg>().eq(UserOrg::getId, existing.getId())
                        .set(UserOrg::getPositionId, req.positionId()));
            }
            n++;
            auditOrg(primary ? "添加人员（主属）" : "添加人员（兼职）", orgId, Map.of("user", u.getName(),
                    "position", req.positionId() == null ? "" : req.positionId()));
        }
        authz.bumpVersion();
        return n;
    }

    /** 兼职任职是否继承该组织的节点权限。 */
    @Transactional
    public void setMemberInherit(String orgId, String userId, boolean inherit) {
        guard.require(PdsPermissions.USER_MANAGE);
        UserOrg uo = userOrgMapper.selectOne(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, userId)
                .eq(UserOrg::getOrgId, orgId).last("LIMIT 1"));
        if (uo == null) {
            throw BizException.notFound("任职记录");
        }
        if (Boolean.TRUE.equals(uo.getIsPrimary()) && !inherit) {
            throw new BizException(ErrorCode.CONFLICT, "主属部门的权限始终继承，只有兼职可以关闭");
        }
        userOrgMapper.update(null, new LambdaUpdateWrapper<UserOrg>().eq(UserOrg::getId, uo.getId()).set(UserOrg::getInheritPerms, inherit));
        authz.bumpVersion();
        auditOrg(inherit ? "兼职继承节点权限" : "兼职不继承节点权限", orgId, Map.of("userId", userId));
    }

    private void insert(String userId, String orgId, String positionId, boolean primary) {
        UserOrg uo = new UserOrg();
        uo.setId(Ids.next("uo"));
        uo.setUserId(userId);
        uo.setOrgId(orgId);
        uo.setPositionId(StringUtils.hasText(positionId) ? positionId : null);
        uo.setIsPrimary(primary);
        uo.setInheritPerms(true);
        userOrgMapper.insert(uo);
    }

    private void auditOrg(String action, String orgId, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("org", orgId).detail(detail));
    }

    static List<String> labels(List<SourceRef> refs) {
        return refs.stream().map(PermTreeService::sourceLabel).toList();
    }
}
