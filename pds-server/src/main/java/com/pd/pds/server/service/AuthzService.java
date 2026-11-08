package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pd.pds.authz.EffectivePermissionCalculator;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.model.DataScope;
import com.pd.pds.authz.model.DataScopeType;
import com.pd.pds.authz.model.Effect;
import com.pd.pds.authz.model.Facts;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.authz.model.SubjectRef;
import com.pd.pds.authz.model.SubjectType;
import com.pd.pds.authz.model.UserProfile;
import com.pd.pds.authz.model.Validity;
import com.pd.pds.common.BizException;
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
import com.pd.pds.server.entity.SecurityLevelSetting;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.entity.UserPerm;
import com.pd.pds.server.entity.UserScenic;
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
import com.pd.pds.server.mapper.SecurityLevelSettingMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserGroupMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.mapper.UserPermMapper;
import com.pd.pds.server.mapper.UserScenicMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 有效权限服务：从数据库加载授权事实 → 调用引擎计算 → 按全局授权版本号缓存。
 *
 * <p>一期采用"全局版本号"失效策略：任何授权相关变更递增 authz_version，并在事务提交后清空本地缓存。
 * 二期按技术方案改为"按人物化 + 受影响人员增量重算 + MQ 事件"。
 */
@Service
public class AuthzService {

    public static final String STATUS_ACTIVE = "ACTIVE";

    private final EffectivePermissionCalculator calculator = new EffectivePermissionCalculator();
    private final Cache<String, Cached> cache = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    private final JdbcTemplate jdbc;
    private final SysUserMapper userMapper;
    private final UserOrgMapper userOrgMapper;
    private final OrgUnitMapper orgMapper;
    private final PositionMapper positionMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserGroupMapper groupMapper;
    private final UserScenicMapper userScenicMapper;
    private final RoleBindingMapper bindingMapper;
    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final UserPermMapper userPermMapper;
    private final ResourceGrantMapper grantMapper;
    private final PermResourceMapper resourceMapper;
    private final SecurityLevelSettingMapper levelMapper;
    private final PermPointMapper permPointMapper;
    private final AuthGrantMapper authGrantMapper;

    private volatile long cachedVersion = -1;
    private volatile TreeCache treeCache;

    public record TreeCache(long version, Facts.PermTree tree, Set<String> inactive) {
    }
    private volatile long versionReadAt;

    private record Cached(long version, EffectivePermissions permissions) {
    }

    public AuthzService(JdbcTemplate jdbc, SysUserMapper userMapper, UserOrgMapper userOrgMapper, OrgUnitMapper orgMapper,
                        PositionMapper positionMapper, GroupMemberMapper groupMemberMapper, UserGroupMapper groupMapper,
                        UserScenicMapper userScenicMapper, RoleBindingMapper bindingMapper, RoleMapper roleMapper,
                        RolePermMapper rolePermMapper, UserPermMapper userPermMapper, ResourceGrantMapper grantMapper,
                        PermResourceMapper resourceMapper, SecurityLevelSettingMapper levelMapper,
                        PermPointMapper permPointMapper, AuthGrantMapper authGrantMapper) {
        this.authGrantMapper = authGrantMapper;
        this.jdbc = jdbc;
        this.userMapper = userMapper;
        this.userOrgMapper = userOrgMapper;
        this.orgMapper = orgMapper;
        this.positionMapper = positionMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.groupMapper = groupMapper;
        this.userScenicMapper = userScenicMapper;
        this.bindingMapper = bindingMapper;
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.userPermMapper = userPermMapper;
        this.grantMapper = grantMapper;
        this.resourceMapper = resourceMapper;
        this.levelMapper = levelMapper;
        this.permPointMapper = permPointMapper;
    }

    // ------------------------------------------------------------------ 版本号

    /** 当前全局授权版本号（本地缓存 2 秒，多实例下最多延迟 2 秒感知其他实例的变更）。 */
    public long version() {
        long now = System.currentTimeMillis();
        if (cachedVersion < 0 || now - versionReadAt > 2000) {
            Long v = jdbc.queryForObject("SELECT version FROM authz_version WHERE id = 1", Long.class);
            cachedVersion = v == null ? 0 : v;
            versionReadAt = now;
        }
        return cachedVersion;
    }

    /** 授权相关数据发生变化：递增版本号；事务提交后清空本地缓存。 */
    public void bumpVersion() {
        jdbc.update("UPDATE authz_version SET version = version + 1 WHERE id = 1");
        Runnable refresh = () -> {
            cache.invalidateAll();
            cachedVersion = -1;
            treeCache = null;
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
        } else {
            refresh.run();
        }
    }

    // ------------------------------------------------------------------ 计算

    /** 人员当前有效权限；账号非在职状态时返回空权限。 */
    public EffectivePermissions effective(String userId) {
        long v = version();
        Cached c = cache.getIfPresent(userId);
        if (c != null && c.version() == v) {
            return c.permissions();
        }
        EffectivePermissions eff = calculator.compute(loadFacts(userId), Instant.now());
        cache.put(userId, new Cached(v, eff));
        return eff;
    }

    public SysUser requireUser(String userId) {
        SysUser u = userMapper.selectById(userId);
        if (u == null) {
            throw BizException.notFound("人员");
        }
        return u;
    }

    Facts.Bundle loadFacts(String userId) {
        SysUser user = requireUser(userId);
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            UserProfile empty = new UserProfile(user.getId(), user.getName(), null, null,
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
            return new Facts.Bundle(empty, Map.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), Set.of());
        }
        return loadFacts(user, loadProfile(user));
    }

    // ------------------------------------------------------------------ 模拟（影响分析、加人预览、权限模拟）

    /**
     * 假设性变更：任职调整（新增组织 / 替换主部门）、新增岗位、额外的节点授权、角色绑定与资源授权。
     */
    public record Simulation(Set<String> addOrgIds, String replacePrimaryWith, Set<String> addPositionIds,
                             List<Facts.PermGrant> extraGrants, List<Facts.RoleBinding> extraBindings,
                             List<Facts.ResourceGrant> extraResourceGrants, Map<ResourceKey, Facts.Resource> extraResources,
                             Set<String> removeGrantIds) {

        public static Simulation none() {
            return new Simulation(Set.of(), null, Set.of(), List.of(), List.of(), List.of(), Map.of(), Set.of());
        }
    }

    /** 按假设性变更计算有效权限（不缓存、不落库）。离职/停用人员按当前状态计算（即无权限）。 */
    public EffectivePermissions simulate(String userId, Simulation sim) {
        SysUser user = requireUser(userId);
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            return effective(userId);
        }
        UserProfile base = loadProfile(user);
        UserProfile profile = base;
        if (!sim.addOrgIds().isEmpty() || sim.replacePrimaryWith() != null || !sim.addPositionIds().isEmpty()) {
            Set<String> orgIds = new LinkedHashSet<>(base.orgIds());
            if (sim.replacePrimaryWith() != null) {
                if (base.primaryOrgId() != null) {
                    orgIds.remove(base.primaryOrgId());
                }
                orgIds.add(sim.replacePrimaryWith());
            }
            orgIds.addAll(sim.addOrgIds());
            Map<String, String> names = new HashMap<>(base.subjectNames());
            Set<String> ancestors = new LinkedHashSet<>();
            Map<String, OrgUnit> chain = new HashMap<>();
            for (OrgUnit o : orgMapper.selectByIds(orgIds)) {
                names.put("ORG:" + o.getId(), o.getName());
                ancestors.addAll(inheritedAncestors(o, chain));
            }
            ancestors.removeAll(orgIds);
            ancestors.forEach(id -> {
                OrgUnit a = chain.get(id);
                if (a != null) {
                    names.put("ORG:" + a.getId(), a.getName());
                }
            });
            Set<String> positions = new LinkedHashSet<>(base.positionIds());
            positions.addAll(sim.addPositionIds());
            for (String pid : sim.addPositionIds()) {
                Position pos = positionMapper.selectById(pid);
                if (pos != null) {
                    names.put("POSITION:" + pid, pos.getName());
                }
            }
            String primary = sim.replacePrimaryWith() != null ? sim.replacePrimaryWith() : base.primaryOrgId();
            profile = new UserProfile(base.userId(), base.name(), primary, base.scenicId(), orgIds, ancestors,
                    positions, base.groupIds(), base.scenicScope(), names);
        }
        Facts.Bundle b = loadFacts(user, profile);
        Map<String, Facts.Role> roles = new HashMap<>(b.roles());
        Set<String> missing = sim.extraBindings().stream().map(Facts.RoleBinding::roleId)
                .filter(r -> !roles.containsKey(r)).collect(Collectors.toSet());
        roles.putAll(loadRoles(missing));
        List<Facts.RoleBinding> bindings = new ArrayList<>(b.bindings());
        bindings.addAll(sim.extraBindings());
        List<Facts.PermGrant> grants = new ArrayList<>(b.permGrants().stream()
                .filter(g -> !sim.removeGrantIds().contains(g.id())).toList());
        grants.addAll(sim.extraGrants());
        Map<ResourceKey, Facts.Resource> resources = new HashMap<>(b.resources());
        resources.putAll(sim.extraResources());
        List<Facts.ResourceGrant> resourceGrants = new ArrayList<>(b.resourceGrants());
        resourceGrants.addAll(sim.extraResourceGrants());
        Facts.Bundle changed = new Facts.Bundle(b.user(), roles, bindings, b.userPermissions(), resources, resourceGrants,
                b.securityLevels(), b.disabledPermCodes(), grants, b.tree());
        return calculator.compute(changed, Instant.now());
    }

    /**
     * 组织节点预览：某人仅因"在此组织任职"会获得的权限（本节点与上级继承的节点授权、组织上绑定的角色），
     * 不含岗位、用户组与个人授权。
     */
    public EffectivePermissions previewOrg(OrgUnit org) {
        Map<String, OrgUnit> chain = new HashMap<>();
        Set<String> ancestors = new LinkedHashSet<>(inheritedAncestors(org, chain));
        Map<String, String> names = new HashMap<>();
        names.put("ORG:" + org.getId(), org.getName());
        ancestors.forEach(id -> {
            OrgUnit a = chain.get(id);
            if (a != null) {
                names.put("ORG:" + id, a.getName());
            }
        });
        Set<String> scenic = org.getScenicId() == null ? Set.of() : Set.of(org.getScenicId());
        UserProfile profile = new UserProfile("__preview__", "（预览）", org.getId(), org.getScenicId(), Set.of(org.getId()),
                ancestors, Set.of(), Set.of(), scenic, names);
        Set<String> orgs = new HashSet<>(ancestors);
        orgs.add(org.getId());
        List<RoleBinding> bindings = bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>()
                .eq(RoleBinding::getSubjectType, "ORG").in(RoleBinding::getSubjectId, orgs));
        Map<String, Facts.Role> roles = loadRoles(bindings.stream().map(RoleBinding::getRoleId).collect(Collectors.toSet()));
        List<Facts.RoleBinding> bf = bindings.stream().map(b -> new Facts.RoleBinding(b.getRoleId(),
                new SubjectRef(SubjectType.ORG, b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren())),
                new Validity(b.getValidFrom(), b.getValidTo()))).toList();
        List<Facts.PermGrant> grants = authGrantMapper.selectList(new LambdaQueryWrapper<AuthGrant>()
                .eq(AuthGrant::getSubjectType, "ORG").in(AuthGrant::getSubjectId, orgs)).stream().map(AuthzService::toFact).toList();
        TreeCache tc = tree();
        return calculator.compute(new Facts.Bundle(profile, roles, bf, List.of(), Map.of(), List.of(), List.of(), tc.inactive(),
                grants, tc.tree()), Instant.now());
    }

    /** 人员当前生效的角色（经任一主体绑定、在有效期内、角色未停用）。 */
    public Set<String> activeRoleIds(String userId) {
        SysUser user = requireUser(userId);
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            return Set.of();
        }
        UserProfile profile = loadProfile(user);
        Instant now = Instant.now();
        Set<String> ids = new HashSet<>();
        for (RoleBinding b : bindingMapper.selectList(subjectFilter(new LambdaQueryWrapper<>(), profile,
                RoleBinding::getSubjectType, RoleBinding::getSubjectId))) {
            SubjectRef ref = new SubjectRef(SubjectType.valueOf(b.getSubjectType()), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren()));
            if (profile.matches(ref) && new Validity(b.getValidFrom(), b.getValidTo()).isActive(now)) {
                ids.add(b.getRoleId());
            }
        }
        return ids;
    }

    Facts.Bundle loadFacts(SysUser user, UserProfile profile) {
        String userId = user.getId();

        // 角色绑定 → 角色 → 角色权限
        List<RoleBinding> bindings = bindingMapper.selectList(subjectFilter(new LambdaQueryWrapper<>(),
                profile, RoleBinding::getSubjectType, RoleBinding::getSubjectId));
        Set<String> roleIds = bindings.stream().map(RoleBinding::getRoleId).collect(Collectors.toSet());
        Map<String, Facts.Role> roles = loadRoles(roleIds);
        List<Facts.RoleBinding> bindingFacts = bindings.stream()
                .map(b -> new Facts.RoleBinding(b.getRoleId(),
                        new SubjectRef(SubjectType.valueOf(b.getSubjectType()), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren())),
                        new Validity(b.getValidFrom(), b.getValidTo())))
                .toList();

        // 人员明细与资源授权
        List<UserPerm> userPerms = userPermMapper.selectList(new LambdaQueryWrapper<UserPerm>().eq(UserPerm::getUserId, userId));
        List<ResourceGrant> grants = grantMapper.selectList(subjectFilter(new LambdaQueryWrapper<>(),
                profile, ResourceGrant::getSubjectType, ResourceGrant::getSubjectId));
        Set<String> resourceIds = new HashSet<>();
        userPerms.stream().map(UserPerm::getResourceId).filter(java.util.Objects::nonNull).forEach(resourceIds::add);
        grants.forEach(g -> resourceIds.add(g.getResourceId()));
        Map<String, PermResource> resourcesById = resourceIds.isEmpty() ? Map.of()
                : resourceMapper.selectByIds(resourceIds).stream().collect(Collectors.toMap(PermResource::getId, Function.identity()));
        Map<ResourceKey, Facts.Resource> resources = new HashMap<>();
        resourcesById.values().forEach(r -> {
            ResourceKey key = new ResourceKey(r.getTypeCode(), r.getRefId());
            resources.put(key, new Facts.Resource(key, r.getName(), r.getScenicId()));
        });

        List<Facts.UserPermission> upFacts = new ArrayList<>();
        for (UserPerm up : userPerms) {
            ResourceKey key = null;
            if (up.getResourceId() != null) {
                PermResource r = resourcesById.get(up.getResourceId());
                if (r == null) {
                    continue;
                }
                key = new ResourceKey(r.getTypeCode(), r.getRefId());
            }
            upFacts.add(new Facts.UserPermission(up.getId(), up.getPermCode(), key,
                    up.getAction() == null ? null : ResourceAction.of(up.getAction()),
                    Effect.valueOf(up.getEffect()), toScope(up.getDataScope(), up.getScopeValues()),
                    new Validity(up.getValidFrom(), up.getValidTo()), up.getReason()));
        }
        List<Facts.ResourceGrant> grantFacts = new ArrayList<>();
        for (ResourceGrant g : grants) {
            PermResource r = resourcesById.get(g.getResourceId());
            if (r == null) {
                continue;
            }
            grantFacts.add(new Facts.ResourceGrant(new ResourceKey(r.getTypeCode(), r.getRefId()),
                    new SubjectRef(SubjectType.valueOf(g.getSubjectType()), g.getSubjectId(), Boolean.TRUE.equals(g.getIncludeChildren())),
                    ResourceAction.of(g.getAction()), Effect.valueOf(g.getEffect()), new Validity(g.getValidFrom(), g.getValidTo())));
        }

        List<Facts.SecurityLevel> levels = levelMapper.selectList(subjectFilter(new LambdaQueryWrapper<>(),
                        profile, SecurityLevelSetting::getSubjectType, SecurityLevelSetting::getSubjectId)).stream()
                .map(l -> new Facts.SecurityLevel(new SubjectRef(SubjectType.valueOf(l.getSubjectType()), l.getSubjectId(),
                        Boolean.TRUE.equals(l.getIncludeChildren())), l.getMaxLevel()))
                .toList();

        List<Facts.PermGrant> permGrants = authGrantMapper.selectList(subjectFilter(new LambdaQueryWrapper<>(),
                        profile, AuthGrant::getSubjectType, AuthGrant::getSubjectId)).stream()
                .map(AuthzService::toFact).toList();

        TreeCache tc = tree();
        return new Facts.Bundle(profile, roles, bindingFacts, upFacts, resources, grantFacts, levels, tc.inactive(),
                permGrants, tc.tree());
    }

    public static Facts.PermGrant toFact(AuthGrant g) {
        return new Facts.PermGrant(g.getId(), g.getPermCode(),
                new SubjectRef(SubjectType.valueOf(g.getSubjectType()), g.getSubjectId(), Boolean.TRUE.equals(g.getIncludeChildren())),
                Effect.valueOf(g.getEffect()), toScope(g.getDataScope(), g.getScopeValues()),
                new Validity(g.getValidFrom(), g.getValidTo()), g.getReason());
    }

    /**
     * 角色及其权限。停用的角色不生效；子角色继承父角色（最多 3 层）的权限。
     */
    public Map<String, Facts.Role> loadRoles(Collection<String> roleIds) {
        Map<String, Facts.Role> roles = new HashMap<>();
        if (roleIds.isEmpty()) {
            return roles;
        }
        for (Role r : roleMapper.selectByIds(roleIds)) {
            if (r.getStatus() != null && r.getStatus() == 0) {
                continue;
            }
            List<String> chain = new ArrayList<>();
            chain.add(r.getId());
            String parent = r.getParentRoleId();
            Set<String> seen = new HashSet<>(chain);
            while (parent != null && chain.size() < 4 && seen.add(parent)) {
                Role p = roleMapper.selectById(parent);
                if (p == null || (p.getStatus() != null && p.getStatus() == 0)) {
                    break;
                }
                chain.add(p.getId());
                parent = p.getParentRoleId();
            }
            Map<String, Facts.RolePermission> merged = new java.util.LinkedHashMap<>();
            for (RolePerm p : rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getRoleId, chain))) {
                // 子角色自身的数据范围优先；父角色补充子角色未声明的权限
                if (p.getRoleId().equals(r.getId()) || !merged.containsKey(p.getPermCode())) {
                    merged.put(p.getPermCode(), new Facts.RolePermission(p.getPermCode(), toScope(p.getDataScope(), p.getScopeValues())));
                }
            }
            roles.put(r.getId(), new Facts.Role(r.getId(), r.getName(), List.copyOf(merged.values())));
        }
        return roles;
    }

    /** 权限树结构与不生效的节点（停用、草稿及其下级），按授权版本缓存。 */
    public TreeCache tree() {
        long v = version();
        TreeCache tc = treeCache;
        if (tc != null && tc.version() == v) {
            return tc;
        }
        List<PermPoint> nodes = permPointMapper.selectList(new LambdaQueryWrapper<PermPoint>()
                .select(PermPoint::getCode, PermPoint::getId, PermPoint::getParentId, PermPoint::getRisk,
                        PermPoint::getStatus, PermPoint::getName));
        Map<String, String> codeById = new HashMap<>();
        nodes.forEach(n -> {
            if (n.getId() != null) {
                codeById.put(n.getId(), n.getCode());
            }
        });
        Map<String, String> parentOf = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Set<String> high = new HashSet<>();
        Set<String> inactiveRoots = new HashSet<>();
        for (PermPoint n : nodes) {
            names.put(n.getCode(), n.getName());
            if (n.getParentId() != null && codeById.containsKey(n.getParentId())) {
                parentOf.put(n.getCode(), codeById.get(n.getParentId()));
            }
            if ("HIGH".equalsIgnoreCase(n.getRisk())) {
                high.add(n.getCode());
            }
            if (n.getStatus() == null || n.getStatus() != 1) {
                inactiveRoots.add(n.getCode());
            }
        }
        Facts.PermTree tree = new Facts.PermTree(parentOf, high, names);
        Set<String> inactive = new HashSet<>(inactiveRoots);
        inactiveRoots.forEach(c -> inactive.addAll(tree.descendants(c)));
        tc = new TreeCache(v, tree, Set.copyOf(inactive));
        treeCache = tc;
        return tc;
    }

    /** 人员画像：任职组织、上级组织、岗位、用户组、景区范围与显示名称。 */
    public UserProfile loadProfile(SysUser user) {
        List<UserOrg> orgs = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, user.getId()));
        // 兼职关闭"继承权限"的任职不参与授权匹配（岗位仍参与）
        Set<String> orgIds = orgs.stream().filter(o -> !Boolean.FALSE.equals(o.getInheritPerms()))
                .map(UserOrg::getOrgId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> positionIds = orgs.stream().map(UserOrg::getPositionId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> names = new HashMap<>();

        Set<String> ancestors = new LinkedHashSet<>();
        if (!orgIds.isEmpty()) {
            Map<String, OrgUnit> chainCache = new HashMap<>();
            for (OrgUnit o : orgMapper.selectByIds(orgIds)) {
                names.put("ORG:" + o.getId(), o.getName());
                ancestors.addAll(inheritedAncestors(o, chainCache));
            }
            ancestors.removeAll(orgIds);
            ancestors.forEach(id -> {
                OrgUnit a = chainCache.get(id);
                if (a != null) {
                    names.put("ORG:" + a.getId(), a.getName());
                }
            });
        }
        if (!positionIds.isEmpty()) {
            for (Position p : positionMapper.selectByIds(positionIds)) {
                names.put("POSITION:" + p.getId(), p.getName());
            }
        }
        Set<String> groupIds = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, user.getId()))
                .stream().map(GroupMember::getGroupId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!groupIds.isEmpty()) {
            for (UserGroup g : groupMapper.selectByIds(groupIds)) {
                names.put("GROUP:" + g.getId(), g.getName());
            }
        }
        names.put("USER:" + user.getId(), user.getName());

        Set<String> scenicScope = userScenicMapper.selectList(new LambdaQueryWrapper<UserScenic>().eq(UserScenic::getUserId, user.getId()))
                .stream().map(UserScenic::getScenicId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (scenicScope.isEmpty() && user.getScenicId() != null) {
            scenicScope.add(user.getScenicId());
        }
        return new UserProfile(user.getId(), user.getName(), user.getPrimaryOrgId(), user.getScenicId(),
                orgIds, ancestors, positionIds, groupIds, scenicScope, names);
    }

    /**
     * 组织节点权限向下继承时，可传递到此组织的上级组织：由近及远，遇到"阻断继承"的节点停止
     * （阻断节点本身的权限仍向下传递，其上级的不再传递）。
     */
    public List<String> inheritedAncestors(OrgUnit org, Map<String, OrgUnit> cache) {
        List<String> out = new ArrayList<>();
        if (Boolean.FALSE.equals(org.getInheritFromParent())) {
            return out;
        }
        List<String> path = new ArrayList<>(Arrays.stream(org.getPath().split("/"))
                .filter(x -> !x.isBlank() && !x.equals(org.getId())).toList());
        java.util.Collections.reverse(path);
        for (String id : path) {
            OrgUnit a = cache.computeIfAbsent(id, orgMapper::selectById);
            if (a == null) {
                break;
            }
            out.add(id);
            if (Boolean.FALSE.equals(a.getInheritFromParent())) {
                break;
            }
        }
        return out;
    }

    /** 拼接"授权对象覆盖此人员"的候选条件（精确匹配由引擎完成）。 */
    private static <T> LambdaQueryWrapper<T> subjectFilter(LambdaQueryWrapper<T> w, UserProfile p,
                                                           com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> typeCol,
                                                           com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> idCol) {
        Set<String> orgs = new HashSet<>(p.orgIds());
        orgs.addAll(p.ancestorOrgIds());
        return w.and(q -> {
            q.or(x -> x.eq(typeCol, SubjectType.USER.name()).eq(idCol, p.userId()));
            or(q, typeCol, idCol, SubjectType.POSITION, p.positionIds());
            or(q, typeCol, idCol, SubjectType.GROUP, p.groupIds());
            or(q, typeCol, idCol, SubjectType.ORG, orgs);
        });
    }

    private static <T> void or(LambdaQueryWrapper<T> q, com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> typeCol,
                               com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> idCol,
                               SubjectType type, Collection<String> ids) {
        if (!ids.isEmpty()) {
            q.or(x -> x.eq(typeCol, type.name()).in(idCol, ids));
        }
    }

    public static DataScope toScope(String type, String values) {
        if (type == null || type.isBlank()) {
            return null;
        }
        Set<String> vals = values == null || values.isBlank() ? Set.of()
                : Arrays.stream(values.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return DataScope.of(DataScopeType.valueOf(type), vals);
    }
}
