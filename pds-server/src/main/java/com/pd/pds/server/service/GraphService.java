package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.Decision;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.SourceRef;
import com.pd.pds.authz.model.Facts;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.authz.model.SubjectRef;
import com.pd.pds.authz.model.SubjectType;
import com.pd.pds.authz.model.UserProfile;
import com.pd.pds.authz.model.Validity;
import com.pd.pds.common.BizException;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.AuditEvent;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermResource;
import com.pd.pds.server.entity.ResourceGrant;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.AuditEventMapper;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.PermResourceMapper;
import com.pd.pds.server.mapper.ResourceGrantMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.GraphDtos;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 权限图谱：以人 / 应用 / 权限 / 组织为中心的关系图，以及全局总览、路径解释与权限模拟。
 * 单张图最多 {@value #MAX_NODES} 个节点，超出时优先保留高风险、被禁止的权限。
 */
@Service
public class GraphService {

    private static final int MAX_NODES = 500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Shanghai"));

    private final SysUserMapper userMapper;
    private final OrgUnitMapper orgMapper;
    private final UserOrgMapper userOrgMapper;
    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final RoleBindingMapper bindingMapper;
    private final AuthGrantMapper grantMapper;
    private final ResourceGrantMapper resourceGrantMapper;
    private final PermResourceMapper resourceMapper;
    private final PermPointMapper pointMapper;
    private final AppInfoMapper appMapper;
    private final AuditEventMapper auditMapper;
    private final SubjectNames subjectNames;
    private final SubjectMembers members;
    private final PermTreeService treeService;
    private final Guard guard;
    private final AuthzService authz;

    public GraphService(SysUserMapper userMapper, OrgUnitMapper orgMapper, UserOrgMapper userOrgMapper, RoleMapper roleMapper,
                        RolePermMapper rolePermMapper, RoleBindingMapper bindingMapper, AuthGrantMapper grantMapper,
                        ResourceGrantMapper resourceGrantMapper, PermResourceMapper resourceMapper, PermPointMapper pointMapper,
                        AppInfoMapper appMapper, AuditEventMapper auditMapper, SubjectNames subjectNames,
                        SubjectMembers members, PermTreeService treeService, Guard guard, AuthzService authz) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.userOrgMapper = userOrgMapper;
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.bindingMapper = bindingMapper;
        this.grantMapper = grantMapper;
        this.resourceGrantMapper = resourceGrantMapper;
        this.resourceMapper = resourceMapper;
        this.pointMapper = pointMapper;
        this.appMapper = appMapper;
        this.auditMapper = auditMapper;
        this.subjectNames = subjectNames;
        this.members = members;
        this.treeService = treeService;
        this.guard = guard;
        this.authz = authz;
    }

    /** 构图工具：节点去重、计数与截断。 */
    private static final class Builder {
        final Map<String, GraphDtos.Node> nodes = new LinkedHashMap<>();
        final Map<String, GraphDtos.Edge> edges = new LinkedHashMap<>();
        boolean truncated;

        boolean node(String id, String type, String label, String sub, String risk, String state, boolean center, Map<String, Object> meta) {
            if (nodes.containsKey(id)) {
                return true;
            }
            if (nodes.size() >= MAX_NODES) {
                truncated = true;
                return false;
            }
            nodes.put(id, new GraphDtos.Node(id, type, label, sub, risk, state, center, meta == null ? Map.of() : meta));
            return true;
        }

        void edge(String from, String to, String kind, String label) {
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
                return;
            }
            String id = from + ">" + to + ":" + kind;
            edges.putIfAbsent(id, new GraphDtos.Edge(id, from, to, kind, label));
        }

        GraphDtos.Graph build(Map<String, Object> summary) {
            return new GraphDtos.Graph(List.copyOf(nodes.values()), List.copyOf(edges.values()), truncated, summary);
        }
    }

    private static String sid(String type, String id) {
        return type + ":" + id;
    }

    private Map<String, String> appNames() {
        return appMapper.selectList(new LambdaQueryWrapper<>()).stream().collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));
    }

    // ------------------------------------------------------------------ 以人为中心

    public GraphDtos.Graph person(String userId, String app, String risk) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        SysUser u = authz.requireUser(userId);
        UserProfile profile = authz.loadProfile(u);
        EffectivePermissions eff = authz.effective(userId);
        Map<String, String> apps = appNames();
        Builder g = new Builder();
        String me = sid("USER", u.getId());
        g.node(me, "USER", u.getName(), u.getEmpNo(), null, null, true, Map.of("status", u.getStatus()));

        // 组织、上级、岗位、用户组
        Map<String, OrgUnit> orgs = new HashMap<>();
        Set<String> allOrgs = new HashSet<>(profile.orgIds());
        allOrgs.addAll(profile.ancestorOrgIds());
        if (!allOrgs.isEmpty()) {
            orgMapper.selectByIds(allOrgs).forEach(o -> orgs.put(o.getId(), o));
        }
        for (String oid : profile.orgIds()) {
            OrgUnit o = orgs.get(oid);
            if (o == null) continue;
            g.node(sid("ORG", oid), "ORG", o.getName(), orgTypeLabel(o.getType()), null, null, false, Map.of());
            g.edge(me, sid("ORG", oid), "BELONG", "属于");
            // 上级链（可继承部分）
            String child = oid;
            Map<String, OrgUnit> cache = new HashMap<>(orgs);
            for (String aid : authz.inheritedAncestors(o, cache)) {
                OrgUnit a = cache.get(aid);
                if (a == null) break;
                g.node(sid("ORG", aid), "ORG", a.getName(), orgTypeLabel(a.getType()), null, null, false, Map.of());
                g.edge(sid("ORG", child), sid("ORG", aid), "PARENT", "上级");
                child = aid;
            }
        }
        for (String pid : profile.positionIds()) {
            g.node(sid("POSITION", pid), "POSITION", subjectNames.nameOf("POSITION", pid), "岗位", null, null, false, Map.of());
            g.edge(me, sid("POSITION", pid), "HOLD", "担任");
        }
        for (String gid : profile.groupIds()) {
            g.node(sid("GROUP", gid), "GROUP", subjectNames.nameOf("GROUP", gid), "用户组", null, null, false, Map.of());
            g.edge(me, sid("GROUP", gid), "MEMBER", "成员");
        }

        Map<String, PermPoint> perms = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>()).stream()
                .collect(Collectors.toMap(PermPoint::getCode, p -> p));
        // 权限节点按优先级排序：被禁止 > 高风险 > 其他
        java.util.function.Predicate<String> inScope = code -> {
            PermPoint p = perms.get(code);
            if (p == null) return false;
            if (StringUtils.hasText(app) && !app.equals(p.getAppCode())) return false;
            return !StringUtils.hasText(risk) || risk.equals(p.getRisk());
        };

        // 角色绑定
        Instant now = Instant.now();
        for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<>())) {
            SubjectRef ref = new SubjectRef(SubjectType.valueOf(b.getSubjectType()), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren()));
            if (!profile.matches(ref) || !new Validity(b.getValidFrom(), b.getValidTo()).isActive(now)) continue;
            Role r = roleMapper.selectById(b.getRoleId());
            if (r == null) continue;
            String rid = sid("ROLE", r.getId());
            g.node(rid, "ROLE", r.getName(), r.getCode(), null, null, false, Map.of("status", r.getStatus() == null ? 1 : r.getStatus()));
            g.edge(subjectNode(b.getSubjectType(), b.getSubjectId(), me), rid, "BIND", "绑定");
        }
        // 角色包含的权限（只画此人最终状态相关的）
        Set<String> roleIds = g.nodes.keySet().stream().filter(k -> k.startsWith("ROLE:")).map(k -> k.substring(5)).collect(Collectors.toSet());
        List<String> ordered = new ArrayList<>();
        Set<String> candidate = new TreeSet<>(eff.permissionCodes());
        if (!roleIds.isEmpty()) {
            for (RolePerm rp : rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getRoleId, roleIds))) {
                candidate.add(rp.getPermCode());
            }
        }
        List<AuthGrant> myGrants = grantMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(x -> profile.matches(new SubjectRef(SubjectType.valueOf(x.getSubjectType()), x.getSubjectId(), Boolean.TRUE.equals(x.getIncludeChildren())))
                        && new Validity(x.getValidFrom(), x.getValidTo()).isActive(now))
                .toList();
        myGrants.forEach(x -> candidate.add(x.getPermCode()));
        candidate.stream().filter(inScope)
                .sorted(Comparator.comparingInt((String c) -> !eff.explain(c).denySources().isEmpty() ? 0 : "HIGH".equals(perms.get(c).getRisk()) ? 1 : 2)
                        .thenComparing(c -> c))
                .forEach(ordered::add);
        for (String code : ordered) {
            addPermNode(g, perms.get(code), eff, apps);
        }
        if (!roleIds.isEmpty()) {
            for (RolePerm rp : rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getRoleId, roleIds))) {
                g.edge(sid("ROLE", rp.getRoleId()), sid("PERM", rp.getPermCode()), "CONTAIN", "包含");
            }
        }
        for (AuthGrant x : myGrants) {
            g.edge(subjectNode(x.getSubjectType(), x.getSubjectId(), me), sid("PERM", x.getPermCode()),
                    "ALLOW".equals(x.getEffect()) ? "GRANT" : "DENY", "ALLOW".equals(x.getEffect()) ? "授予" : "禁止");
        }
        // 资源
        for (ResourceGrant rg : resourceGrantMapper.selectList(new LambdaQueryWrapper<>())) {
            SubjectRef ref = new SubjectRef(SubjectType.valueOf(rg.getSubjectType()), rg.getSubjectId(), Boolean.TRUE.equals(rg.getIncludeChildren()));
            if (!profile.matches(ref) || !new Validity(rg.getValidFrom(), rg.getValidTo()).isActive(now)) continue;
            PermResource r = resourceMapper.selectById(rg.getResourceId());
            if (r == null || (StringUtils.hasText(app) && !app.equals(r.getAppCode()))) continue;
            ResourceKey key = new ResourceKey(r.getTypeCode(), r.getRefId());
            boolean ok = eff.canAccess(key, ResourceAction.READ);
            String nid = sid("RESOURCE", r.getId());
            if (g.node(nid, "RESOURCE", r.getName(), r.getTypeCode(), null, ok ? "ALLOW" : "DENY", false, Map.of("app", r.getAppCode()))) {
                addApp(g, r.getAppCode(), apps);
                g.edge(nid, sid("APP", r.getAppCode()), "IN_APP", "属于");
            }
            g.edge(subjectNode(rg.getSubjectType(), rg.getSubjectId(), me), nid,
                    "ALLOW".equals(rg.getEffect()) ? "GRANT" : "DENY", "ALLOW".equals(rg.getEffect()) ? "授予" : "禁止");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("permissions", eff.permissionCodes().size());
        summary.put("highRisk", eff.permissionCodes().stream().filter(c -> perms.containsKey(c) && "HIGH".equals(perms.get(c).getRisk())).count());
        summary.put("denied", ordered.stream().filter(c -> !eff.explain(c).denySources().isEmpty()).count());
        return g.build(summary);
    }

    private String subjectNode(String type, String id, String me) {
        return "USER".equals(type) ? me : sid(type, id);
    }

    private void addPermNode(Builder g, PermPoint p, EffectivePermissions eff, Map<String, String> apps) {
        if (p == null) return;
        Decision d = eff == null ? null : eff.explain(p.getCode());
        String state = d == null ? null : d.allowed() ? "ALLOW" : d.denySources().isEmpty() ? "NONE" : "DENY";
        if (g.node(sid("PERM", p.getCode()), "PERM", p.getCode(), p.getName(), p.getRisk(), state, false,
                Map.of("app", p.getAppCode(), "name", p.getName()))) {
            addApp(g, p.getAppCode(), apps);
            g.edge(sid("PERM", p.getCode()), sid("APP", p.getAppCode()), "IN_APP", "属于");
        }
    }

    private void addApp(Builder g, String app, Map<String, String> apps) {
        g.node(sid("APP", app), "APP", app.toUpperCase(), apps.getOrDefault(app, app), null, null, false, Map.of());
    }

    private static String orgTypeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "GROUP" -> "集团";
            case "COMPANY" -> "公司";
            case "SCENIC" -> "景区";
            case "DEPT" -> "部门";
            case "TEAM" -> "班组";
            default -> "组织";
        };
    }

    // ------------------------------------------------------------------ 以应用为中心

    public GraphDtos.Graph app(String app) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        Map<String, String> apps = appNames();
        if (!apps.containsKey(app)) throw BizException.notFound("应用【" + app + "】");
        Builder g = new Builder();
        String center = sid("APP", app);
        g.node(center, "APP", app.toUpperCase(), apps.get(app), null, null, true, Map.of());
        String access = AppService.accessPermission(app);
        PermPoint ap = pointMapper.selectById(access);
        if (ap != null) {
            g.node(sid("PERM", access), "PERM", access, ap.getName(), ap.getRisk(), null, false, Map.of("app", app, "name", ap.getName()));
            g.edge(sid("PERM", access), center, "IN_APP", "准入");
            Set<String> codes = new HashSet<>(authz.tree().tree().ancestors(access));
            codes.add(access);
            // 直接授予准入的主体
            for (AuthGrant x : grantMapper.selectList(new LambdaQueryWrapper<AuthGrant>().in(AuthGrant::getPermCode, codes))) {
                String sn = addSubject(g, x.getSubjectType(), x.getSubjectId(), x.getIncludeChildren());
                g.edge(sn, sid("PERM", access), "ALLOW".equals(x.getEffect()) ? "GRANT" : "DENY", "ALLOW".equals(x.getEffect()) ? "授予" : "禁止");
            }
            // 含准入的角色及其绑定主体
            Set<String> roleIds = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getPermCode, codes))
                    .stream().map(RolePerm::getRoleId).collect(Collectors.toSet());
            for (Role r : roleIds.isEmpty() ? List.<Role>of() : roleMapper.selectByIds(roleIds)) {
                g.node(sid("ROLE", r.getId()), "ROLE", r.getName(), r.getCode(), null, null, false, Map.of());
                g.edge(sid("ROLE", r.getId()), sid("PERM", access), "CONTAIN", "包含");
                for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, r.getId()))) {
                    String sn = addSubject(g, b.getSubjectType(), b.getSubjectId(), b.getIncludeChildren());
                    g.edge(sn, sid("ROLE", r.getId()), "BIND", "绑定");
                }
            }
        }
        // 应用下的高风险权限节点
        for (PermPoint p : pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getAppCode, app)
                .eq(PermPoint::getRisk, "HIGH").eq(PermPoint::getStatus, 1))) {
            addPermNode(g, p, null, apps);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accessUsers", ap == null ? 0 : treeService.holderUsers(access).size());
        return g.build(summary);
    }

    private String addSubject(Builder g, String type, String id, Boolean includeChildren) {
        String nid = sid(type, id);
        String sub = switch (type) {
            case "USER" -> java.util.Optional.ofNullable(userMapper.selectById(id)).map(SysUser::getEmpNo).orElse(null);
            case "ORG" -> Boolean.TRUE.equals(includeChildren) ? "含下级" : "组织";
            case "POSITION" -> "岗位";
            default -> "用户组";
        };
        g.node(nid, type, subjectNames.nameOf(type, id), sub, null, null, false, Map.of());
        return nid;
    }

    // ------------------------------------------------------------------ 以权限为中心

    public GraphDtos.Graph perm(String code) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        PermPoint p = pointMapper.selectById(code);
        if (p == null) throw BizException.notFound("权限节点");
        Map<String, String> apps = appNames();
        Builder g = new Builder();
        String center = sid("PERM", code);
        g.node(center, "PERM", code, p.getName(), p.getRisk(), null, true, Map.of("app", p.getAppCode(), "name", p.getName()));
        addApp(g, p.getAppCode(), apps);
        g.edge(center, sid("APP", p.getAppCode()), "IN_APP", "属于");
        Facts.PermTree tree = authz.tree().tree();
        // 上级节点（授予上级即包含此节点，高风险除外）
        String child = center;
        for (String a : tree.ancestors(code)) {
            PermPoint ap = pointMapper.selectById(a);
            if (ap == null) break;
            g.node(sid("PERM", a), "PERM", a, ap.getName(), ap.getRisk(), null, false, Map.of("app", ap.getAppCode(), "name", ap.getName()));
            g.edge(sid("PERM", a), child, "CONTAIN", "包含");
            child = sid("PERM", a);
        }
        Set<String> codes = new HashSet<>(tree.ancestors(code));
        codes.add(code);
        for (AuthGrant x : grantMapper.selectList(new LambdaQueryWrapper<AuthGrant>().in(AuthGrant::getPermCode, codes))) {
            String sn = addSubject(g, x.getSubjectType(), x.getSubjectId(), x.getIncludeChildren());
            g.edge(sn, sid("PERM", x.getPermCode()), "ALLOW".equals(x.getEffect()) ? "GRANT" : "DENY", "ALLOW".equals(x.getEffect()) ? "授予" : "禁止");
        }
        for (RolePerm rp : rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getPermCode, codes))) {
            Role r = roleMapper.selectById(rp.getRoleId());
            if (r == null) continue;
            g.node(sid("ROLE", r.getId()), "ROLE", r.getName(), r.getCode(), null, null, false, Map.of());
            g.edge(sid("ROLE", r.getId()), sid("PERM", rp.getPermCode()), "CONTAIN", "包含");
            for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, r.getId()))) {
                g.edge(addSubject(g, b.getSubjectType(), b.getSubjectId(), b.getIncludeChildren()), sid("ROLE", r.getId()), "BIND", "绑定");
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("holders", treeService.holderUsers(code).size());
        return g.build(summary);
    }

    // ------------------------------------------------------------------ 以组织为中心

    public GraphDtos.Graph org(String orgId) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        OrgUnit o = orgMapper.selectById(orgId);
        if (o == null) throw BizException.notFound("组织");
        Map<String, String> apps = appNames();
        Builder g = new Builder();
        String center = sid("ORG", orgId);
        g.node(center, "ORG", o.getName(), orgTypeLabel(o.getType()), null, null, true,
                Map.of("members", members.of("ORG", orgId, true).size(), "inherit", !Boolean.FALSE.equals(o.getInheritFromParent())));
        if (o.getParentId() != null) {
            OrgUnit p = orgMapper.selectById(o.getParentId());
            if (p != null) {
                g.node(sid("ORG", p.getId()), "ORG", p.getName(), orgTypeLabel(p.getType()), null, null, false, Map.of());
                g.edge(center, sid("ORG", p.getId()), "PARENT", Boolean.FALSE.equals(o.getInheritFromParent()) ? "阻断继承" : "上级");
            }
        }
        for (OrgUnit c : orgMapper.selectList(new LambdaQueryWrapper<OrgUnit>().eq(OrgUnit::getParentId, orgId))) {
            g.node(sid("ORG", c.getId()), "ORG", c.getName(), orgTypeLabel(c.getType()), null, null, false,
                    Map.of("members", members.of("ORG", c.getId(), true).size()));
            g.edge(sid("ORG", c.getId()), center, "PARENT", Boolean.FALSE.equals(c.getInheritFromParent()) ? "阻断继承" : "上级");
        }
        for (UserOrg uo : userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getOrgId, orgId).last("LIMIT 60"))) {
            SysUser u = userMapper.selectById(uo.getUserId());
            if (u == null) continue;
            g.node(sid("USER", u.getId()), "USER", u.getName(), u.getEmpNo(), null, null, false, Map.of());
            g.edge(sid("USER", u.getId()), center, "BELONG", Boolean.TRUE.equals(uo.getIsPrimary()) ? "属于" : "兼职");
        }
        for (AuthGrant x : grantMapper.selectList(new LambdaQueryWrapper<AuthGrant>().eq(AuthGrant::getSubjectType, "ORG").eq(AuthGrant::getSubjectId, orgId))) {
            addPermNode(g, pointMapper.selectById(x.getPermCode()), null, apps);
            g.edge(center, sid("PERM", x.getPermCode()), "ALLOW".equals(x.getEffect()) ? "GRANT" : "DENY", "ALLOW".equals(x.getEffect()) ? "授予" : "禁止");
        }
        for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getSubjectType, "ORG").eq(RoleBinding::getSubjectId, orgId))) {
            Role r = roleMapper.selectById(b.getRoleId());
            if (r == null) continue;
            g.node(sid("ROLE", r.getId()), "ROLE", r.getName(), r.getCode(), null, null, false, Map.of());
            g.edge(center, sid("ROLE", r.getId()), "BIND", "绑定");
        }
        return g.build(Map.of("members", members.of("ORG", orgId, true).size()));
    }

    // ------------------------------------------------------------------ 全局总览

    public GraphDtos.Graph overview() {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        Map<String, String> apps = appNames();
        Builder g = new Builder();
        apps.forEach((code, name) -> g.node(sid("APP", code), "APP", code.toUpperCase(), name, null, null, false, Map.of()));
        Map<String, String> permApp = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().select(PermPoint::getCode, PermPoint::getAppCode))
                .stream().collect(Collectors.toMap(PermPoint::getCode, PermPoint::getAppCode));
        Map<String, Map<String, Long>> roleAppCount = new HashMap<>();
        for (RolePerm rp : rolePermMapper.selectList(new LambdaQueryWrapper<>())) {
            String a = permApp.get(rp.getPermCode());
            if (a != null) roleAppCount.computeIfAbsent(rp.getRoleId(), k -> new HashMap<>()).merge(a, 1L, Long::sum);
        }
        for (Role r : roleMapper.selectList(new LambdaQueryWrapper<>())) {
            g.node(sid("ROLE", r.getId()), "ROLE", r.getName(), r.getCode(), null, null, false, Map.of());
            roleAppCount.getOrDefault(r.getId(), Map.of()).forEach((a, n) ->
                    g.edge(sid("ROLE", r.getId()), sid("APP", a), "CONTAIN", n + " 项"));
        }
        // 景区及以上组织
        for (OrgUnit o : orgMapper.selectList(new LambdaQueryWrapper<OrgUnit>().in(OrgUnit::getType, List.of("GROUP", "COMPANY", "SCENIC")))) {
            g.node(sid("ORG", o.getId()), "ORG", o.getName(), orgTypeLabel(o.getType()), null, null, false,
                    Map.of("members", members.of("ORG", o.getId(), true).size()));
            if (o.getParentId() != null) {
                g.edge(sid("ORG", o.getId()), sid("ORG", o.getParentId()), "PARENT", "上级");
            }
        }
        for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getSubjectType, "ORG"))) {
            g.edge(sid("ORG", b.getSubjectId()), sid("ROLE", b.getRoleId()), "BIND", "绑定");
        }
        for (AuthGrant x : grantMapper.selectList(new LambdaQueryWrapper<AuthGrant>().eq(AuthGrant::getSubjectType, "ORG"))) {
            String a = permApp.get(x.getPermCode());
            if (a != null) g.edge(sid("ORG", x.getSubjectId()), sid("APP", a), "ALLOW".equals(x.getEffect()) ? "GRANT" : "DENY", "节点授权");
        }
        return g.build(Map.of("apps", apps.size()));
    }

    // ------------------------------------------------------------------ 路径解释与模拟

    public GraphDtos.Explain explain(String userId, String code) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        SysUser u = authz.requireUser(userId);
        PermPoint p = pointMapper.selectById(code);
        if (p == null) throw BizException.notFound("权限节点");
        EffectivePermissions eff = authz.effective(userId);
        Decision d = eff.explain(code);
        String verdict = d.allowed() ? "ALLOW" : d.denySources().isEmpty() ? "NONE" : "DENY";
        AuditEvent last = auditMapper.selectOne(new LambdaQueryWrapper<AuditEvent>().eq(AuditEvent::getUserId, u.getId())
                .and(q -> q.eq(AuditEvent::getAction, code).or().like(AuditEvent::getTargetIds, code)
                        .or().like(AuditEvent::getDetail, "\"" + code + "\""))
                .orderByDesc(AuditEvent::getSeq).last("LIMIT 1"));
        // 未使用天数：以审计中最近一次使用记录计算；没有任何使用记录时为 null（前端提示"暂无使用记录"）
        Long unused = d.allowed() && last != null ? Duration.between(last.getTs(), Instant.now()).toDays() : null;
        return new GraphDtos.Explain(userId, code, p.getName(), p.getAppCode(), p.getRisk(), verdict,
                d.allowRefs().stream().map(this::path).toList(), d.denyRefs().stream().map(this::path).toList(),
                last == null ? null : DATE.format(last.getTs()), unused);
    }

    private GraphDtos.PathView path(SourceRef r) {
        List<String> steps = new ArrayList<>();
        switch (r.kind()) {
            case ROLE -> {
                if (r.subjectType() != null && r.subjectType() != SubjectType.USER) {
                    steps.add(r.subjectType().label() + "：" + r.subjectName() + (r.includeChildren() ? "（含下级）" : ""));
                } else {
                    steps.add("个人绑定");
                }
                steps.add("角色：" + r.roleName());
            }
            case DIRECT_ALLOW, DIRECT_DENY -> steps.add(r.kind() == SourceRef.Kind.DIRECT_ALLOW ? "个人直授" : "个人禁止");
            case GRANT_ALLOW, GRANT_DENY -> steps.add((r.subjectType() == null ? "" : r.subjectType().label() + "：") + r.subjectName()
                    + (r.includeChildren() ? "（含下级）" : "") + (r.kind() == SourceRef.Kind.GRANT_DENY ? " 禁止" : ""));
            default -> steps.add(r.text());
        }
        if (r.viaNode() != null) {
            steps.add("上级节点：" + r.viaNode());
        }
        return new GraphDtos.PathView(steps, r.validTo() == null ? null : DATE.format(r.validTo()), r.text());
    }

    public GraphDtos.SimulateResult simulate(GraphDtos.SimulateRequest req) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        SysUser u = authz.requireUser(req.userId());
        UserProfile profile = authz.loadProfile(u);
        boolean primary = Boolean.TRUE.equals(req.primary());
        List<Facts.RoleBinding> bindings = new ArrayList<>();
        if (req.roleIds() != null) {
            req.roleIds().forEach(r -> bindings.add(new Facts.RoleBinding(r, SubjectRef.user(u.getId()), Validity.ALWAYS)));
        }
        AuthzService.Simulation sim = new AuthzService.Simulation(
                StringUtils.hasText(req.orgId()) && !primary ? Set.of(req.orgId()) : Set.of(),
                StringUtils.hasText(req.orgId()) && primary ? req.orgId() : null,
                StringUtils.hasText(req.positionId()) ? Set.of(req.positionId()) : Set.of(),
                List.of(), bindings, List.of(), Map.of(), Set.of());
        EffectivePermissions before = authz.effective(u.getId());
        EffectivePermissions after = authz.simulate(u.getId(), sim);
        Facts.PermTree tree = authz.tree().tree();
        Set<String> plus = new TreeSet<>(after.permissionCodes());
        plus.removeAll(before.permissionCodes());
        Set<String> minus = new TreeSet<>(before.permissionCodes());
        minus.removeAll(after.permissionCodes());
        return new GraphDtos.SimulateResult(plus.stream().map(c -> tree.nameOf(c) + "（" + c + "）").toList(),
                plus.stream().filter(tree::isHighRisk).map(tree::nameOf).toList(),
                minus.stream().map(c -> tree.nameOf(c) + "（" + c + "）").toList());
    }
}
