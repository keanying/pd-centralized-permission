package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.ResolvedScope;
import com.pd.pds.authz.model.Effect;
import com.pd.pds.authz.model.Facts;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.authz.model.SubjectRef;
import com.pd.pds.authz.model.SubjectType;
import com.pd.pds.authz.model.UserProfile;
import com.pd.pds.authz.model.Validity;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.PageResult;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.GroupMember;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermResource;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.ResourceGrant;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.entity.UserPerm;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.GroupMemberMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.PermResourceMapper;
import com.pd.pds.server.mapper.PositionMapper;
import com.pd.pds.server.mapper.ResourceGrantMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserGroupMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.mapper.UserPermMapper;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.AppDtos;
import com.pd.pds.server.web.dto.AssignmentDtos;
import com.pd.pds.server.web.dto.RoleDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分配库：把权限节点授权、角色绑定、资源授权、人员资源明细四类记录汇总成一本台账，
 * 支持按应用 / 按主体 / 按权限查看、批量授权（含影响分析）、续期、撤销与权限复制。
 *
 * <p>一期在内存中汇总与筛选（记录量在数万条以内时足够）；数据量增长后改为数据库视图分页。
 */
@Service
public class AssignmentService {

    /** 影响分析时逐人计算的人数上限。 */
    private static final int IMPACT_SAMPLE = 300;

    private final AuthGrantMapper grantMapper;
    private final RoleBindingMapper bindingMapper;
    private final ResourceGrantMapper resourceGrantMapper;
    private final UserPermMapper userPermMapper;
    private final PermPointMapper pointMapper;
    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final PermResourceMapper resourceMapper;
    private final AppInfoMapper appMapper;
    private final SysUserMapper userMapper;
    private final OrgUnitMapper orgMapper;
    private final PositionMapper positionMapper;
    private final UserGroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserOrgMapper userOrgMapper;
    private final PermGrantService permGrants;
    private final RoleService roleService;
    private final AppService appService;
    private final GrantService grantService;
    private final PermTreeService treeService;
    private final SubjectNames subjectNames;
    private final Guard guard;
    private final AuthzService authz;
    @org.springframework.beans.factory.annotation.Autowired
    private SubjectMembers members;

    public AssignmentService(AuthGrantMapper grantMapper, RoleBindingMapper bindingMapper, ResourceGrantMapper resourceGrantMapper,
                             UserPermMapper userPermMapper, PermPointMapper pointMapper, RoleMapper roleMapper,
                             RolePermMapper rolePermMapper, PermResourceMapper resourceMapper, AppInfoMapper appMapper,
                             SysUserMapper userMapper, OrgUnitMapper orgMapper, PositionMapper positionMapper,
                             UserGroupMapper groupMapper, GroupMemberMapper groupMemberMapper, UserOrgMapper userOrgMapper,
                             PermGrantService permGrants, RoleService roleService, AppService appService,
                             GrantService grantService, PermTreeService treeService, SubjectNames subjectNames,
                             Guard guard, AuthzService authz) {
        this.grantMapper = grantMapper;
        this.bindingMapper = bindingMapper;
        this.resourceGrantMapper = resourceGrantMapper;
        this.userPermMapper = userPermMapper;
        this.pointMapper = pointMapper;
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.resourceMapper = resourceMapper;
        this.appMapper = appMapper;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.positionMapper = positionMapper;
        this.groupMapper = groupMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.userOrgMapper = userOrgMapper;
        this.permGrants = permGrants;
        this.roleService = roleService;
        this.appService = appService;
        this.grantService = grantService;
        this.treeService = treeService;
        this.subjectNames = subjectNames;
        this.guard = guard;
        this.authz = authz;
    }

    // ------------------------------------------------------------------ 查询

    /** 查询条件。related=true 且 subjectType=USER 时，同时列出覆盖此人的组织、岗位、用户组上的记录。 */
    public record Query(String app, String subjectType, String subjectId, boolean related, String objectType,
                        String permCode, String roleId, String source, String effect, Integer expiringDays,
                        String keyword, long page, long size) {
    }

    public PageResult<AssignmentDtos.Row> list(Query q) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        List<AssignmentDtos.Row> rows = filter(allRows(), q);
        rows.sort(Comparator.comparing(AssignmentDtos.Row::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        long from = Math.max(0, (q.page() - 1) * q.size());
        List<AssignmentDtos.Row> items = rows.stream().skip(from).limit(q.size()).toList();
        return new PageResult<>(rows.size(), q.page(), q.size(), items);
    }

    public AssignmentDtos.Stats stats(String app) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        List<AssignmentDtos.Row> rows = filter(allRows(), new Query(app, null, null, false, null, null, null, null,
                null, null, null, 1, 1));
        Instant now = Instant.now();
        Instant soon = now.plus(Duration.ofDays(30));
        long high = rows.stream().filter(r -> "ALLOW".equals(r.effect()) && "HIGH".equals(r.risk())).count();
        long expiring = rows.stream().filter(r -> r.validTo() != null && r.validTo().isAfter(now) && r.validTo().isBefore(soon)).count();
        long access = 0;
        if (StringUtils.hasText(app)) {
            PermPoint ap = pointMapper.selectById(AppService.accessPermission(app));
            access = ap == null ? 0 : treeService.holderUsers(ap.getCode()).size();
        }
        return new AssignmentDtos.Stats(access, rows.size(), high, expiring);
    }

    private List<AssignmentDtos.Row> filter(List<AssignmentDtos.Row> all, Query q) {
        Set<String> relatedKeys = null;
        if (q.related() && "USER".equals(q.subjectType()) && StringUtils.hasText(q.subjectId())) {
            UserProfile p = authz.loadProfile(authz.requireUser(q.subjectId()));
            relatedKeys = new HashSet<>();
            relatedKeys.add("USER:" + p.userId());
            for (String o : p.orgIds()) relatedKeys.add("ORG:" + o);
            for (String o : p.ancestorOrgIds()) relatedKeys.add("ORG+:" + o);
            for (String o : p.positionIds()) relatedKeys.add("POSITION:" + o);
            for (String o : p.groupIds()) relatedKeys.add("GROUP:" + o);
        }
        Set<String> permMatch = null;
        Set<String> rolesWithPerm = null;
        if (StringUtils.hasText(q.permCode())) {
            permMatch = new HashSet<>(authz.tree().tree().ancestors(q.permCode()));
            permMatch.add(q.permCode());
            Set<String> pm = permMatch;
            rolesWithPerm = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getPermCode, pm))
                    .stream().map(RolePerm::getRoleId).collect(Collectors.toSet());
        }
        Instant now = Instant.now();
        Instant until = q.expiringDays() == null ? null : now.plus(Duration.ofDays(q.expiringDays()));
        String kw = q.keyword() == null ? null : q.keyword().trim().toLowerCase();
        Map<String, Set<String>> roleApps = roleApps();
        List<AssignmentDtos.Row> out = new ArrayList<>();
        for (AssignmentDtos.Row r : all) {
            if (StringUtils.hasText(q.app()) && !matchesApp(r, q.app(), roleApps)) continue;
            if (relatedKeys != null) {
                String key = r.subjectType() + ":" + r.subjectId();
                boolean ok = relatedKeys.contains(key) || (r.includeChildren() && relatedKeys.contains("ORG+:" + r.subjectId()));
                if (!ok) continue;
            } else {
                if (StringUtils.hasText(q.subjectType()) && !q.subjectType().equals(r.subjectType())) continue;
                if (StringUtils.hasText(q.subjectId()) && !q.subjectId().equals(r.subjectId())) continue;
            }
            if (StringUtils.hasText(q.objectType()) && !q.objectType().equals(r.objectType())) continue;
            if (permMatch != null) {
                boolean ok = (("PERM".equals(r.objectType()) || "ACCESS".equals(r.objectType())) && permMatch.contains(r.objectCode()))
                        || ("ROLE".equals(r.objectType()) && rolesWithPerm.contains(r.objectCode()));
                if (!ok) continue;
            }
            if (StringUtils.hasText(q.roleId()) && !("ROLE".equals(r.objectType()) && q.roleId().equals(r.objectCode()))) continue;
            if (StringUtils.hasText(q.source()) && !q.source().equals(r.source())) continue;
            if (StringUtils.hasText(q.effect()) && !q.effect().equals(r.effect())) continue;
            if (until != null && (r.validTo() == null || !r.validTo().isAfter(now) || r.validTo().isAfter(until))) continue;
            if (kw != null && !kw.isEmpty() && !contains(r, kw)) continue;
            out.add(r);
        }
        return out;
    }

    private static boolean contains(AssignmentDtos.Row r, String kw) {
        return (r.subjectName() != null && r.subjectName().toLowerCase().contains(kw))
                || (r.subjectSub() != null && r.subjectSub().toLowerCase().contains(kw))
                || (r.objectName() != null && r.objectName().toLowerCase().contains(kw))
                || (r.objectCode() != null && r.objectCode().toLowerCase().contains(kw));
    }

    private static boolean matchesApp(AssignmentDtos.Row r, String app, Map<String, Set<String>> roleApps) {
        if ("ROLE".equals(r.objectType())) {
            return roleApps.getOrDefault(r.objectCode(), Set.of()).contains(app);
        }
        return app.equals(r.appCode());
    }

    /** 角色涉及的应用：角色所属应用 + 角色权限点所属应用。 */
    private Map<String, Set<String>> roleApps() {
        Map<String, String> permApp = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>()
                        .select(PermPoint::getCode, PermPoint::getAppCode)).stream()
                .collect(Collectors.toMap(PermPoint::getCode, PermPoint::getAppCode));
        Map<String, Set<String>> out = new HashMap<>();
        for (RolePerm rp : rolePermMapper.selectList(new LambdaQueryWrapper<>())) {
            String a = permApp.get(rp.getPermCode());
            if (a != null) {
                out.computeIfAbsent(rp.getRoleId(), k -> new HashSet<>()).add(a);
            }
        }
        for (Role r : roleMapper.selectList(new LambdaQueryWrapper<>())) {
            if (r.getAppCode() != null) {
                out.computeIfAbsent(r.getId(), k -> new HashSet<>()).add(r.getAppCode());
            }
        }
        return out;
    }

    /** 汇总四类授权记录。 */
    List<AssignmentDtos.Row> allRows() {
        Names n = new Names();
        List<AssignmentDtos.Row> rows = new ArrayList<>();
        for (AuthGrant g : grantMapper.selectList(new LambdaQueryWrapper<>())) {
            PermPoint p = n.perms.get(g.getPermCode());
            String app = p == null ? appOfCode(g.getPermCode()) : p.getAppCode();
            boolean access = g.getPermCode().equals(AppService.accessPermission(app));
            rows.add(new AssignmentDtos.Row("g:" + g.getId(), "PERM", g.getSubjectType(), g.getSubjectId(),
                    n.subject(g.getSubjectType(), g.getSubjectId()), n.sub(g.getSubjectType(), g.getSubjectId()),
                    Boolean.TRUE.equals(g.getIncludeChildren()), access ? "ACCESS" : "PERM", g.getPermCode(),
                    access ? "应用准入 · " + n.appName(app) : (p == null ? g.getPermCode() : p.getName()), app, n.appName(app),
                    p == null ? null : p.getRisk(), null, g.getEffect(), g.getDataScope(), g.getValidFrom(), g.getValidTo(),
                    g.getSource(), g.getReason(), n.user(g.getGrantedBy()), g.getCreatedAt()));
        }
        for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<>())) {
            Role r = n.roles.get(b.getRoleId());
            rows.add(new AssignmentDtos.Row("rb:" + b.getId(), "ROLE", b.getSubjectType(), b.getSubjectId(),
                    n.subject(b.getSubjectType(), b.getSubjectId()), n.sub(b.getSubjectType(), b.getSubjectId()),
                    Boolean.TRUE.equals(b.getIncludeChildren()), "ROLE", b.getRoleId(), r == null ? b.getRoleId() : "角色：" + r.getName(),
                    r == null ? null : r.getAppCode(), r == null || r.getAppCode() == null ? null : n.appName(r.getAppCode()),
                    null, null, "ALLOW", null, b.getValidFrom(), b.getValidTo(), "MANUAL", null, n.user(b.getCreatedBy()),
                    b.getCreatedAt()));
        }
        for (ResourceGrant g : resourceGrantMapper.selectList(new LambdaQueryWrapper<>())) {
            PermResource res = n.resources.get(g.getResourceId());
            rows.add(new AssignmentDtos.Row("rg:" + g.getId(), "RESOURCE", g.getSubjectType(), g.getSubjectId(),
                    n.subject(g.getSubjectType(), g.getSubjectId()), n.sub(g.getSubjectType(), g.getSubjectId()),
                    Boolean.TRUE.equals(g.getIncludeChildren()), "RESOURCE",
                    res == null ? g.getResourceId() : res.getTypeCode() + "/" + res.getRefId(),
                    res == null ? g.getResourceId() : res.getName(), res == null ? null : res.getAppCode(),
                    res == null ? null : n.appName(res.getAppCode()), null, g.getAction(), g.getEffect(), null,
                    g.getValidFrom(), g.getValidTo(), "MANUAL", g.getReason(), n.user(g.getGrantedBy()), g.getCreatedAt()));
        }
        for (UserPerm up : userPermMapper.selectList(new LambdaQueryWrapper<UserPerm>().isNotNull(UserPerm::getResourceId))) {
            PermResource res = n.resources.get(up.getResourceId());
            rows.add(new AssignmentDtos.Row("up:" + up.getId(), "RESOURCE", "USER", up.getUserId(),
                    n.subject("USER", up.getUserId()), n.sub("USER", up.getUserId()), false, "RESOURCE",
                    res == null ? up.getResourceId() : res.getTypeCode() + "/" + res.getRefId(),
                    res == null ? up.getResourceId() : res.getName(), res == null ? null : res.getAppCode(),
                    res == null ? null : n.appName(res.getAppCode()), null, up.getAction(), up.getEffect(), null,
                    up.getValidFrom(), up.getValidTo(), "MANUAL", up.getReason(), n.user(up.getGrantedBy()), up.getCreatedAt()));
        }
        return rows;
    }

    private static String appOfCode(String code) {
        int i = code.indexOf(':');
        return i < 0 ? code : code.substring(0, i);
    }

    /** 一次查询内复用的名称表。 */
    private final class Names {
        final Map<String, SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .select(SysUser::getId, SysUser::getName, SysUser::getEmpNo)).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        final Map<String, OrgUnit> orgs = orgMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        final Map<String, String> positions = positionMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(Position::getId, Position::getName));
        final Map<String, String> groups = groupMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(UserGroup::getId, UserGroup::getName));
        final Map<String, PermPoint> perms = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>()
                        .select(PermPoint::getCode, PermPoint::getName, PermPoint::getAppCode, PermPoint::getRisk)).stream()
                .collect(Collectors.toMap(PermPoint::getCode, Function.identity()));
        final Map<String, Role> roles = roleMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        final Map<String, PermResource> resources = resourceMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(PermResource::getId, Function.identity()));
        final Map<String, String> apps = appMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));

        String subject(String type, String id) {
            return switch (type) {
                case "USER" -> users.containsKey(id) ? users.get(id).getName() : id;
                case "ORG" -> orgs.containsKey(id) ? orgs.get(id).getName() : id;
                case "POSITION" -> positions.getOrDefault(id, id);
                case "GROUP" -> groups.getOrDefault(id, id);
                default -> id;
            };
        }

        String sub(String type, String id) {
            return "USER".equals(type) && users.containsKey(id) ? users.get(id).getEmpNo() : null;
        }

        String user(String id) {
            if (id == null) return null;
            if ("system".equals(id)) return "系统";
            return users.containsKey(id) ? users.get(id).getName() : id;
        }

        String appName(String code) {
            return code == null ? null : apps.getOrDefault(code, code);
        }
    }

    // ------------------------------------------------------------------ 影响分析

    public AssignmentDtos.Impact impact(AssignmentDtos.BatchRequest req) {
        ResolvedScope scope = guard.require(PdsPermissions.GRANT_MANAGE);
        EffectivePermissions mine = guard.permissions();
        boolean any = mine.has(PdsPermissions.GRANT_ANY);
        Facts.PermTree tree = authz.tree().tree();

        // 超出可授权范围的对象
        Set<String> exceeding = new LinkedHashSet<>();
        if ("ALLOW".equals(req.effect()) && !any) {
            for (AssignmentDtos.ObjectRef o : req.objects()) {
                switch (o.type()) {
                    case "PERM" -> {
                        if (!mine.has(o.code())) exceeding.add(tree.nameOf(o.code()));
                    }
                    case "ROLE" -> {
                        Role r = roleMapper.selectById(o.roleId());
                        Set<String> codes = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, o.roleId()))
                                .stream().map(RolePerm::getPermCode).collect(Collectors.toSet());
                        if (codes.stream().anyMatch(c -> !mine.has(c))) exceeding.add("角色：" + (r == null ? o.roleId() : r.getName()));
                    }
                    default -> {
                        ResourceKey key = new ResourceKey(o.resourceType(), o.refId());
                        if (!mine.canAccess(key, ResourceAction.ADMIN) && !mine.canAccess(key, ResourceAction.of(o.action()))) {
                            PermResource res = appService.findResource(o.resourceType(), o.refId());
                            exceeding.add(res == null ? o.refId() : res.getName());
                        }
                    }
                }
            }
        }
        // 超出管理范围的主体
        List<String> outOfScope = new ArrayList<>();
        for (AssignmentDtos.SubjectRef s : req.subjects()) {
            try {
                permGrants.assertInScope(scope, s.type(), s.id());
            } catch (BizException e) {
                outOfScope.add(subjectNames.nameOf(s.type(), s.id()));
            }
        }

        // 受影响人员
        Set<String> users = new LinkedHashSet<>();
        for (AssignmentDtos.SubjectRef s : req.subjects()) {
            users.addAll(membersOf(s.type(), s.id(), Boolean.TRUE.equals(s.includeChildren())));
        }
        AuthzService.Simulation sim = simulation(req);
        Set<String> roleIdsInReq = req.objects().stream().filter(o -> "ROLE".equals(o.type())).map(AssignmentDtos.ObjectRef::roleId)
                .collect(Collectors.toSet());
        Map<String, Set<String>> mutex = roleService.mutexMap();

        int added = 0;
        int lost = 0;
        Set<String> highRisk = new TreeSet<>();
        int highCount = 0;
        List<AssignmentDtos.Conflict> conflicts = new ArrayList<>();
        List<AssignmentDtos.UserImpact> impacts = new ArrayList<>();
        // 所选角色之间互斥
        for (String a : roleIdsInReq) {
            for (String b : mutex.getOrDefault(a, Set.of())) {
                if (roleIdsInReq.contains(b) && a.compareTo(b) < 0) {
                    conflicts.add(new AssignmentDtos.Conflict("所选角色", roleName(a) + " 与 " + roleName(b) + " 互斥"));
                }
            }
        }
        int sampled = 0;
        for (String uid : users) {
            if (sampled >= IMPACT_SAMPLE) break;
            SysUser u = userMapper.selectById(uid);
            if (u == null || !AuthzService.STATUS_ACTIVE.equals(u.getStatus())) continue;
            sampled++;
            EffectivePermissions before = authz.effective(uid);
            EffectivePermissions after = authz.simulate(uid, sim);
            Set<String> plus = new TreeSet<>(after.permissionCodes());
            plus.removeAll(before.permissionCodes());
            Set<String> minus = new TreeSet<>(before.permissionCodes());
            minus.removeAll(after.permissionCodes());
            List<String> hr = plus.stream().filter(tree::isHighRisk).map(tree::nameOf).toList();
            added += plus.size();
            lost += minus.size();
            highCount += hr.size();
            highRisk.addAll(hr);
            if (!roleIdsInReq.isEmpty()) {
                Set<String> current = authz.activeRoleIds(uid);
                for (String rid : roleIdsInReq) {
                    for (String other : mutex.getOrDefault(rid, Set.of())) {
                        if (current.contains(other)) {
                            conflicts.add(new AssignmentDtos.Conflict(u.getName(), roleName(rid) + " 与 已有角色 " + roleName(other) + " 互斥"));
                        }
                    }
                }
            }
            impacts.add(new AssignmentDtos.UserImpact(uid, u.getName(), u.getEmpNo(), plus.size(), hr, minus.size()));
        }
        impacts.sort(Comparator.comparingInt(AssignmentDtos.UserImpact::added).reversed());
        return new AssignmentDtos.Impact(users.size(), sampled, added, highCount, List.copyOf(highRisk), lost, conflicts,
                List.copyOf(exceeding), outOfScope.size(), outOfScope, impacts.stream().limit(20).toList());
    }

    private String roleName(String id) {
        Role r = roleMapper.selectById(id);
        return "【" + (r == null ? id : r.getName()) + "】";
    }

    private AuthzService.Simulation simulation(AssignmentDtos.BatchRequest req) {
        List<Facts.PermGrant> grants = new ArrayList<>();
        List<Facts.RoleBinding> bindings = new ArrayList<>();
        List<Facts.ResourceGrant> resGrants = new ArrayList<>();
        Map<ResourceKey, Facts.Resource> resources = new HashMap<>();
        Validity validity = new Validity(req.validFrom(), req.validTo());
        Effect effect = Effect.valueOf(req.effect());
        for (AssignmentDtos.SubjectRef s : req.subjects()) {
            SubjectRef ref = new SubjectRef(SubjectType.valueOf(s.type()), s.id(), "ORG".equals(s.type()) && Boolean.TRUE.equals(s.includeChildren()));
            for (AssignmentDtos.ObjectRef o : req.objects()) {
                switch (o.type()) {
                    case "PERM" -> grants.add(new Facts.PermGrant("sim", o.code(), ref, effect,
                            AuthzService.toScope(req.dataScope(), req.scopeValues() == null ? null : String.join(",", req.scopeValues())),
                            validity, req.reason()));
                    case "ROLE" -> {
                        if (effect == Effect.ALLOW) bindings.add(new Facts.RoleBinding(o.roleId(), ref, validity));
                    }
                    default -> {
                        PermResource r = appService.findResource(o.resourceType(), o.refId());
                        if (r != null) {
                            ResourceKey key = new ResourceKey(r.getTypeCode(), r.getRefId());
                            resources.put(key, new Facts.Resource(key, r.getName(), r.getScenicId()));
                            resGrants.add(new Facts.ResourceGrant(key, ref, ResourceAction.of(o.action() == null ? "READ" : o.action()),
                                    effect, validity));
                        }
                    }
                }
            }
        }
        return new AuthzService.Simulation(Set.of(), null, Set.of(), grants, bindings, resGrants, resources, Set.of());
    }

    /** 主体覆盖的人员。 */
    public Set<String> membersOf(String type, String id, boolean includeChildren) {
        return members.of(type, id, includeChildren);
    }

    // ------------------------------------------------------------------ 批量授权 / 修改 / 撤销 / 复制

    /** 批量授权：全部成功或全部回滚。 */
    @Transactional
    public AssignmentDtos.BatchResult batch(AssignmentDtos.BatchRequest req) {
        guard.require(PdsPermissions.GRANT_MANAGE);
        if (!StringUtils.hasText(req.reason())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请填写授权原因");
        }
        int created = 0;
        for (AssignmentDtos.SubjectRef s : req.subjects()) {
            boolean children = "ORG".equals(s.type()) && Boolean.TRUE.equals(s.includeChildren());
            for (AssignmentDtos.ObjectRef o : req.objects()) {
                switch (o.type()) {
                    case "PERM" -> permGrants.create(new PermGrantService.Spec(s.type(), s.id(), children, o.code(), req.effect(),
                            req.dataScope(), req.scopeValues(), req.validFrom(), req.validTo(), req.reason(), "MANUAL"));
                    case "ROLE" -> {
                        if (!"ALLOW".equals(req.effect())) {
                            throw new BizException(ErrorCode.VALIDATION_FAILED, "角色只能授予，不能设为禁止；如需限制请对权限节点设置禁止");
                        }
                        roleService.bind(o.roleId(), new RoleDtos.Bind(s.type(), s.id(), children, req.validFrom(), req.validTo()));
                    }
                    default -> appService.addGrant(o.resourceType(), o.refId(), new AppDtos.GrantRequest(s.type(), s.id(),
                            children, o.action() == null ? "READ" : o.action(), req.effect(), req.validFrom(), req.validTo(), req.reason()));
                }
                created++;
            }
        }
        return new AssignmentDtos.BatchResult(created);
    }

    @Transactional
    public void update(String id, AssignmentDtos.UpdateRequest req) {
        boolean clear = Boolean.TRUE.equals(req.clearValidTo());
        String[] parts = split(id);
        switch (parts[0]) {
            case "g" -> permGrants.updateValidity(parts[1], req.validTo(), clear, req.reason());
            case "rb" -> roleService.updateBindingValidity(parts[1], req.validTo(), clear);
            case "rg" -> appService.updateGrantValidity(parts[1], req.validTo(), clear, req.reason());
            case "up" -> {
                UserPerm up = userPermMapper.selectById(parts[1]);
                if (up == null) throw BizException.notFound("授权记录");
                grantService.updateUserPerm(up.getUserId(), up.getId(), new RoleDtos.UpdateUserPerm(req.validTo(), clear, req.reason()));
            }
            default -> throw BizException.notFound("授权记录");
        }
    }

    @Transactional
    public int renew(AssignmentDtos.RenewRequest req) {
        for (String id : req.ids()) {
            update(id, new AssignmentDtos.UpdateRequest(req.validTo(), req.clearValidTo(), null));
        }
        return req.ids().size();
    }

    @Transactional
    public int revoke(List<String> ids) {
        for (String id : ids) {
            String[] parts = split(id);
            switch (parts[0]) {
                case "g" -> permGrants.delete(parts[1]);
                case "rb" -> roleService.unbind(parts[1]);
                case "rg" -> {
                    ResourceGrant g = resourceGrantMapper.selectById(parts[1]);
                    if (g == null) throw BizException.notFound("授权记录");
                    PermResource r = resourceMapper.selectById(g.getResourceId());
                    appService.removeGrant(r.getTypeCode(), r.getRefId(), g.getId());
                }
                case "up" -> {
                    UserPerm up = userPermMapper.selectById(parts[1]);
                    if (up == null) throw BizException.notFound("授权记录");
                    grantService.deleteUserPerm(up.getUserId(), up.getId());
                }
                default -> throw BizException.notFound("授权记录");
            }
        }
        return ids.size();
    }

    /** 权限复制：把 A 名下直接授予的记录（不含经组织/岗位等获得的）复制给目标人员。 */
    @Transactional
    public int copy(AssignmentDtos.CopyRequest req) {
        guard.require(PdsPermissions.GRANT_MANAGE);
        SysUser from = authz.requireUser(req.fromUserId());
        String reason = "复制自 " + from.getName() + (from.getEmpNo() == null ? "" : "（" + from.getEmpNo() + "）");
        int n = 0;
        for (String to : req.toUserIds()) {
            if (to.equals(from.getId())) continue;
            authz.requireUser(to);
            if (!Boolean.FALSE.equals(req.includePerms())) {
                for (AuthGrant g : grantMapper.selectList(new LambdaQueryWrapper<AuthGrant>().eq(AuthGrant::getSubjectType, "USER")
                        .eq(AuthGrant::getSubjectId, from.getId()))) {
                    permGrants.create(new PermGrantService.Spec("USER", to, false, g.getPermCode(), g.getEffect(), g.getDataScope(),
                            g.getScopeValues() == null ? null : Set.of(g.getScopeValues().split(",")), null, g.getValidTo(), reason, "MANUAL"));
                    n++;
                }
            }
            if (!Boolean.FALSE.equals(req.includeRoles())) {
                Set<String> existing = bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getSubjectType, "USER")
                        .eq(RoleBinding::getSubjectId, to)).stream().map(RoleBinding::getRoleId).collect(Collectors.toSet());
                for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getSubjectType, "USER")
                        .eq(RoleBinding::getSubjectId, from.getId()))) {
                    if (!existing.contains(b.getRoleId())) {
                        roleService.bind(b.getRoleId(), new RoleDtos.Bind("USER", to, false, null, b.getValidTo()));
                        n++;
                    }
                }
            }
            if (!Boolean.FALSE.equals(req.includeResources())) {
                for (ResourceGrant g : resourceGrantMapper.selectList(new LambdaQueryWrapper<ResourceGrant>().eq(ResourceGrant::getSubjectType, "USER")
                        .eq(ResourceGrant::getSubjectId, from.getId()))) {
                    PermResource r = resourceMapper.selectById(g.getResourceId());
                    appService.addGrant(r.getTypeCode(), r.getRefId(), new AppDtos.GrantRequest("USER", to, false, g.getAction(),
                            g.getEffect(), null, g.getValidTo(), reason));
                    n++;
                }
                for (UserPerm up : userPermMapper.selectList(new LambdaQueryWrapper<UserPerm>().eq(UserPerm::getUserId, from.getId())
                        .isNotNull(UserPerm::getResourceId))) {
                    PermResource r = resourceMapper.selectById(up.getResourceId());
                    appService.addGrant(r.getTypeCode(), r.getRefId(), new AppDtos.GrantRequest("USER", to, false, up.getAction(),
                            up.getEffect(), null, up.getValidTo(), reason));
                    n++;
                }
            }
        }
        return n;
    }

    private static String[] split(String id) {
        int i = id == null ? -1 : id.indexOf(':');
        if (i <= 0) {
            throw BizException.notFound("授权记录");
        }
        return new String[]{id.substring(0, i), id.substring(i + 1)};
    }
}
