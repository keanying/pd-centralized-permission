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

    private volatile long cachedVersion = -1;
    private volatile long versionReadAt;

    private record Cached(long version, EffectivePermissions permissions) {
    }

    public AuthzService(JdbcTemplate jdbc, SysUserMapper userMapper, UserOrgMapper userOrgMapper, OrgUnitMapper orgMapper,
                        PositionMapper positionMapper, GroupMemberMapper groupMemberMapper, UserGroupMapper groupMapper,
                        UserScenicMapper userScenicMapper, RoleBindingMapper bindingMapper, RoleMapper roleMapper,
                        RolePermMapper rolePermMapper, UserPermMapper userPermMapper, ResourceGrantMapper grantMapper,
                        PermResourceMapper resourceMapper, SecurityLevelSettingMapper levelMapper,
                        PermPointMapper permPointMapper) {
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
        UserProfile profile = loadProfile(user);

        // 角色绑定 → 角色 → 角色权限
        List<RoleBinding> bindings = bindingMapper.selectList(subjectFilter(new LambdaQueryWrapper<>(),
                profile, RoleBinding::getSubjectType, RoleBinding::getSubjectId));
        Set<String> roleIds = bindings.stream().map(RoleBinding::getRoleId).collect(Collectors.toSet());
        Map<String, Facts.Role> roles = new HashMap<>();
        if (!roleIds.isEmpty()) {
            Map<String, List<RolePerm>> permsByRole = rolePermMapper.selectList(
                            new LambdaQueryWrapper<RolePerm>().in(RolePerm::getRoleId, roleIds))
                    .stream().collect(Collectors.groupingBy(RolePerm::getRoleId));
            for (Role r : roleMapper.selectByIds(roleIds)) {
                List<Facts.RolePermission> rp = permsByRole.getOrDefault(r.getId(), List.of()).stream()
                        .map(p -> new Facts.RolePermission(p.getPermCode(), toScope(p.getDataScope(), p.getScopeValues())))
                        .toList();
                roles.put(r.getId(), new Facts.Role(r.getId(), r.getName(), rp));
            }
        }
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

        Set<String> disabled = permPointMapper.selectList(new LambdaQueryWrapper<PermPoint>()
                        .select(PermPoint::getCode).eq(PermPoint::getStatus, 0))
                .stream().map(PermPoint::getCode).collect(Collectors.toSet());

        return new Facts.Bundle(profile, roles, bindingFacts, upFacts, resources, grantFacts, levels, disabled);
    }

    /** 人员画像：任职组织、上级组织、岗位、用户组、景区范围与显示名称。 */
    public UserProfile loadProfile(SysUser user) {
        List<UserOrg> orgs = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, user.getId()));
        Set<String> orgIds = orgs.stream().map(UserOrg::getOrgId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> positionIds = orgs.stream().map(UserOrg::getPositionId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> names = new HashMap<>();

        Set<String> ancestors = new LinkedHashSet<>();
        if (!orgIds.isEmpty()) {
            for (OrgUnit o : orgMapper.selectByIds(orgIds)) {
                names.put("ORG:" + o.getId(), o.getName());
                Arrays.stream(o.getPath().split("/")).filter(s -> !s.isBlank() && !s.equals(o.getId())).forEach(ancestors::add);
            }
            ancestors.removeAll(orgIds);
            if (!ancestors.isEmpty()) {
                orgMapper.selectByIds(ancestors).forEach(o -> names.put("ORG:" + o.getId(), o.getName()));
            }
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
