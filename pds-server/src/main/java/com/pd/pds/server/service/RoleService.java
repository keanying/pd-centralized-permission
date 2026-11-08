package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.DistDelivery;
import com.pd.pds.server.entity.DistEvent;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.RoleMapping;
import com.pd.pds.server.entity.RoleMutex;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.entity.RoleVersion;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.DistDeliveryMapper;
import com.pd.pds.server.mapper.DistEventMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.RoleMappingMapper;
import com.pd.pds.server.mapper.RoleMutexMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.mapper.RoleVersionMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.RoleDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.stream.Collectors;

/**
 * 系统角色：类型（内置/全局/应用/景区）、继承（最多 3 层）、互斥、外部编码映射、版本、下发。
 * 约束：授权不越级（pds:grant:any 除外）、互斥角色不能授予同一人员、景区角色只能绑定本景区主体。
 */
@Service
public class RoleService {

    private static final int MAX_DEPTH = 3;

    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final RoleBindingMapper bindingMapper;
    private final PermPointMapper pointMapper;
    private final RoleMutexMapper mutexMapper;
    private final RoleMappingMapper mappingMapper;
    private final RoleVersionMapper versionMapper;
    private final AppInfoMapper appMapper;
    private final OrgUnitMapper orgMapper;
    private final SysUserMapper userMapper;
    private final DistEventMapper eventMapper;
    private final DistDeliveryMapper deliveryMapper;
    private final AppService appService;
    private final SubjectNames subjectNames;
    private final SubjectMembers members;
    private final DistributionService distribution;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;
    private final JsonMapper json;

    public RoleService(RoleMapper roleMapper, RolePermMapper rolePermMapper, RoleBindingMapper bindingMapper,
                       PermPointMapper pointMapper, RoleMutexMapper mutexMapper, RoleMappingMapper mappingMapper,
                       RoleVersionMapper versionMapper, AppInfoMapper appMapper, OrgUnitMapper orgMapper,
                       SysUserMapper userMapper, DistEventMapper eventMapper, DistDeliveryMapper deliveryMapper,
                       AppService appService, SubjectNames subjectNames, SubjectMembers members,
                       DistributionService distribution, Guard guard, AuthzService authz, AuditService audit,
                       JsonMapper json) {
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.bindingMapper = bindingMapper;
        this.pointMapper = pointMapper;
        this.mutexMapper = mutexMapper;
        this.mappingMapper = mappingMapper;
        this.versionMapper = versionMapper;
        this.appMapper = appMapper;
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
        this.eventMapper = eventMapper;
        this.deliveryMapper = deliveryMapper;
        this.appService = appService;
        this.subjectNames = subjectNames;
        this.members = members;
        this.distribution = distribution;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ 查看

    public List<RoleDtos.RoleView> list() {
        guard.require(PdsPermissions.ROLE_VIEW);
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getCode));
        Ctx ctx = new Ctx(roles);
        return roles.stream().map(r -> view(r, ctx)).toList();
    }

    public RoleDtos.RoleView get(String id) {
        guard.require(PdsPermissions.ROLE_VIEW);
        Role r = require(id);
        return view(r, new Ctx(roleMapper.selectList(new LambdaQueryWrapper<>())));
    }

    /** 列表与详情共用的预加载数据。 */
    private final class Ctx {
        final Map<String, Role> roles;
        final Map<String, List<RolePerm>> perms;
        final Map<String, List<RoleBinding>> bindings;
        final Map<String, Set<String>> mutex;
        final Map<String, String> apps;

        Ctx(List<Role> all) {
            roles = all.stream().collect(Collectors.toMap(Role::getId, r -> r));
            perms = rolePermMapper.selectList(new LambdaQueryWrapper<>()).stream().collect(Collectors.groupingBy(RolePerm::getRoleId));
            bindings = bindingMapper.selectList(new LambdaQueryWrapper<>()).stream().collect(Collectors.groupingBy(RoleBinding::getRoleId));
            mutex = mutexMap();
            apps = appMapper.selectList(new LambdaQueryWrapper<>()).stream().collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));
        }
    }

    private RoleDtos.RoleView view(Role r, Ctx ctx) {
        List<RoleDtos.RolePermission> ps = ctx.perms.getOrDefault(r.getId(), List.of()).stream()
                .map(p -> new RoleDtos.RolePermission(p.getPermCode(), p.getDataScope(),
                        p.getScopeValues() == null || p.getScopeValues().isBlank() ? Set.of() : new TreeSet<>(List.of(p.getScopeValues().split(",")))))
                .sorted(Comparator.comparing(RoleDtos.RolePermission::permCode))
                .toList();
        List<String> chain = new ArrayList<>();
        Set<String> inherited = new TreeSet<>();
        String parent = r.getParentRoleId();
        Set<String> seen = new HashSet<>(Set.of(r.getId()));
        while (parent != null && seen.add(parent) && ctx.roles.containsKey(parent) && chain.size() < MAX_DEPTH) {
            Role p = ctx.roles.get(parent);
            chain.add(0, p.getName());
            ctx.perms.getOrDefault(p.getId(), List.of()).forEach(rp -> inherited.add(rp.getPermCode()));
            parent = p.getParentRoleId();
        }
        ps.forEach(p -> inherited.remove(p.permCode()));
        List<RoleBinding> bs = ctx.bindings.getOrDefault(r.getId(), List.of());
        Set<String> people = new HashSet<>();
        for (RoleBinding b : bs) {
            people.addAll(members.of(b.getSubjectType(), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren())));
        }
        List<String> mutexIds = new ArrayList<>(new TreeSet<>(ctx.mutex.getOrDefault(r.getId(), Set.of())));
        List<String> mutexNames = mutexIds.stream().map(id -> ctx.roles.containsKey(id) ? ctx.roles.get(id).getName() : id).toList();
        String scenicName = r.getScenicId() == null ? null
                : java.util.Optional.ofNullable(orgMapper.selectById(r.getScenicId())).map(OrgUnit::getName).orElse(r.getScenicId());
        Role parentRole = r.getParentRoleId() == null ? null : ctx.roles.get(r.getParentRoleId());
        return new RoleDtos.RoleView(r.getId(), r.getCode(), r.getName(), r.getLevel(), r.getScenicId(), r.getDescription(),
                Boolean.TRUE.equals(r.getBuiltin()), ps, bs.size(), typeOf(r), r.getAppCode(),
                r.getAppCode() == null ? null : ctx.apps.getOrDefault(r.getAppCode(), r.getAppCode()),
                r.getParentRoleId(), parentRole == null ? null : parentRole.getName(), chain,
                r.getStatus() == null ? 1 : r.getStatus(), r.getOwnerId(), subjectNames.userName(r.getOwnerId()),
                r.getVersion() == null ? 1 : r.getVersion(), people.size(), mutexIds, mutexNames, scenicName,
                List.copyOf(inherited));
    }

    private static String typeOf(Role r) {
        if (r.getType() != null) {
            return r.getType();
        }
        return Boolean.TRUE.equals(r.getBuiltin()) ? "BUILTIN" : "SCENIC".equals(r.getLevel()) ? "SCENIC" : "GLOBAL";
    }

    public Role require(String id) {
        Role r = roleMapper.selectById(id);
        if (r == null) {
            throw BizException.notFound("角色");
        }
        return r;
    }

    // ------------------------------------------------------------------ 新建 / 修改 / 复制 / 删除

    @Transactional
    public Role create(RoleDtos.CreateRole req) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        String type = req.type() != null ? req.type() : "SCENIC".equals(req.level()) ? "SCENIC" : "GLOBAL";
        String code = StringUtils.hasText(req.code()) ? req.code() : autoCode(type);
        if (roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getCode, code)) > 0) {
            throw BizException.duplicate("角色编码【" + code + "】");
        }
        if ("SCENIC".equals(type) && !StringUtils.hasText(req.scenicId())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "景区角色必须指定景区");
        }
        if ("APP".equals(type) && (!StringUtils.hasText(req.appCode()) || appMapper.selectById(req.appCode()) == null)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "应用角色必须指定所属应用");
        }
        Instant now = Instant.now();
        Role r = new Role();
        r.setId(Ids.next("role"));
        r.setCode(code);
        r.setName(req.name());
        r.setType(type);
        r.setLevel("SCENIC".equals(type) ? "SCENIC" : "GROUP");
        r.setScenicId("SCENIC".equals(type) ? req.scenicId() : null);
        r.setAppCode("APP".equals(type) ? req.appCode() : null);
        r.setDescription(req.description());
        r.setOwnerId(StringUtils.hasText(req.ownerId()) ? req.ownerId() : null);
        r.setBuiltin(false);
        r.setStatus(1);
        r.setVersion(1);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        if (StringUtils.hasText(req.parentRoleId())) {
            checkParent(r.getId(), req.parentRoleId());
            r.setParentRoleId(req.parentRoleId());
        }
        roleMapper.insert(r);
        auditAdmin("创建角色", r.getId(), Map.of("code", r.getCode(), "name", r.getName(), "type", type));
        return r;
    }

    @Transactional
    public RoleDtos.RoleView update(String id, RoleDtos.UpdateRole req) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        Role r = require(id);
        boolean builtin = Boolean.TRUE.equals(r.getBuiltin());
        if (builtin && !guard.has(PdsPermissions.GRANT_ANY)) {
            throw new BizException(ErrorCode.FORBIDDEN, "内置角色只能由安全管理员修改");
        }
        if (StringUtils.hasText(req.name())) r.setName(req.name().trim());
        if (req.description() != null) r.setDescription(req.description());
        if (req.ownerId() != null) r.setOwnerId(req.ownerId().isBlank() ? null : req.ownerId());
        boolean parentChanged = false;
        if (Boolean.TRUE.equals(req.clearParent())) {
            parentChanged = r.getParentRoleId() != null;
            r.setParentRoleId(null);
        } else if (StringUtils.hasText(req.parentRoleId()) && !req.parentRoleId().equals(r.getParentRoleId())) {
            if (builtin) {
                throw new BizException(ErrorCode.CONFLICT, "内置角色不能设置父角色");
            }
            checkParent(r.getId(), req.parentRoleId());
            r.setParentRoleId(req.parentRoleId());
            parentChanged = true;
        }
        boolean statusChanged = false;
        if (req.status() != null && !req.status().equals(r.getStatus())) {
            if (req.status() == 0 && PdsPermissions.SEPARATED_ROLES.contains(r.getCode())) {
                throw new BizException(ErrorCode.CONFLICT, "三员角色不能停用");
            }
            r.setStatus(req.status());
            statusChanged = true;
        }
        r.setUpdatedAt(Instant.now());
        roleMapper.updateById(r);
        roleMapper.update(null, new LambdaUpdateWrapper<Role>().eq(Role::getId, id)
                .set(Role::getParentRoleId, r.getParentRoleId()).set(Role::getOwnerId, r.getOwnerId())
                .set(Role::getDescription, r.getDescription()));
        if (parentChanged || statusChanged) {
            snapshot(r, parentChanged ? "修改父角色" : (r.getStatus() == 1 ? "启用角色" : "停用角色"));
            authz.bumpVersion();
            emitRole("role.updated", r, Map.of("status", r.getStatus()));
        }
        auditAdmin("修改角色", id, Map.of("name", r.getName()));
        return get(id);
    }

    @Transactional
    public Role copy(String id, RoleDtos.CopyRole req) {
        Role src = require(id);
        Role r = create(new RoleDtos.CreateRole(req.code(), req.name(), src.getLevel(), src.getScenicId(), src.getDescription(),
                "BUILTIN".equals(typeOf(src)) ? "GLOBAL" : typeOf(src), src.getAppCode(), src.getParentRoleId(), src.getOwnerId()));
        List<RoleDtos.RolePermission> perms = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, id))
                .stream().map(p -> new RoleDtos.RolePermission(p.getPermCode(), p.getDataScope(),
                        p.getScopeValues() == null ? Set.of() : Set.of(p.getScopeValues().split(",")))).toList();
        if (!perms.isEmpty()) {
            setPermissions(r.getId(), new RoleDtos.SetPermissions(perms));
        }
        return r;
    }

    /** 删除角色：内置角色不能删除；被继承或仍有绑定时，force=true 才一并解除绑定。 */
    @Transactional
    public void delete(String id, boolean force) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        Role r = require(id);
        if (Boolean.TRUE.equals(r.getBuiltin())) {
            throw new BizException(ErrorCode.CONFLICT, "内置角色不能删除");
        }
        long children = roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getParentRoleId, id));
        if (children > 0) {
            throw new BizException(ErrorCode.CONFLICT, "有 " + children + " 个角色继承自该角色，请先修改它们的父角色");
        }
        long bound = countBindings(id);
        if (bound > 0 && !force) {
            throw new BizException(ErrorCode.CONFLICT, "该角色还有 " + bound + " 条绑定，确认后将一并解除");
        }
        Set<String> targets = targetApps(r);
        Map<String, String> codes = externalCodes(r);
        bindingMapper.delete(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, id));
        rolePermMapper.delete(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, id));
        mutexMapper.delete(new LambdaQueryWrapper<RoleMutex>().eq(RoleMutex::getRoleId, id).or().eq(RoleMutex::getOtherRoleId, id));
        mappingMapper.delete(new LambdaQueryWrapper<RoleMapping>().eq(RoleMapping::getRoleId, id));
        roleMapper.deleteById(id);
        authz.bumpVersion();
        distribution.emit("role.deleted", "role", id, targets, Map.of("roleId", id, "roleCode", r.getCode(),
                "roleName", r.getName(), "externalCodes", codes));
        auditAdmin("删除角色", id, Map.of("code", r.getCode(), "name", r.getName(), "bindings", bound));
    }

    private void checkParent(String roleId, String parentId) {
        Role p = require(parentId);
        if (parentId.equals(roleId)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "不能把角色自身设为父角色");
        }
        int depth = 1;
        String cur = p.getParentRoleId();
        Set<String> seen = new HashSet<>(Set.of(parentId));
        while (cur != null) {
            if (cur.equals(roleId) || !seen.add(cur)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "角色继承不能成环");
            }
            depth++;
            Role c = roleMapper.selectById(cur);
            cur = c == null ? null : c.getParentRoleId();
        }
        if (depth >= MAX_DEPTH) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "角色继承最多 " + MAX_DEPTH + " 层");
        }
    }

    private String autoCode(String type) {
        String prefix = switch (type) {
            case "APP" -> "APP";
            case "SCENIC" -> "SCN";
            default -> "ROLE";
        };
        for (int i = 0; i < 20; i++) {
            String code = Ids.shortId(prefix, 6);
            if (roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getCode, code)) == 0) {
                return code;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "角色编码生成失败，请手工填写");
    }

    // ------------------------------------------------------------------ 权限与版本

    /** 整体替换角色的权限点，生成新版本并下发。 */
    @Transactional
    public RoleDtos.RoleView setPermissions(String roleId, RoleDtos.SetPermissions req) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        Role role = require(roleId);
        EffectivePermissions mine = guard.permissions();
        boolean any = mine.has(PdsPermissions.GRANT_ANY);
        if (Boolean.TRUE.equals(role.getBuiltin()) && !any) {
            throw new BizException(ErrorCode.FORBIDDEN, "内置角色只能由安全管理员修改");
        }
        Set<String> codes = req.permissions().stream().map(RoleDtos.RolePermission::permCode).collect(Collectors.toSet());
        if (codes.size() != req.permissions().size()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "权限点重复");
        }
        if (!codes.isEmpty()) {
            Map<String, PermPoint> known = pointMapper.selectByIds(codes).stream().collect(Collectors.toMap(PermPoint::getCode, p -> p));
            Set<String> unknown = new TreeSet<>(codes);
            unknown.removeAll(known.keySet());
            if (!unknown.isEmpty()) {
                throw BizException.notFound("权限点 " + unknown);
            }
            Set<String> current = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, roleId))
                    .stream().map(RolePerm::getPermCode).collect(Collectors.toSet());
            for (PermPoint p : known.values()) {
                boolean draft = p.getStatus() != null && p.getStatus() == 2;
                if (!current.contains(p.getCode()) && (draft || Boolean.FALSE.equals(p.getGrantable()))) {
                    throw new BizException(ErrorCode.CONFLICT, "权限节点【" + p.getName() + "】" + (draft ? "尚未发布" : "不可授权"));
                }
            }
        }
        requireNotExceeding(mine, any, codes);
        rolePermMapper.delete(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, roleId));
        for (RoleDtos.RolePermission p : req.permissions()) {
            RolePerm rp = new RolePerm();
            rp.setId(Ids.next("rp"));
            rp.setRoleId(roleId);
            rp.setPermCode(p.permCode());
            rp.setDataScope(p.dataScope());
            rp.setScopeValues(p.scopeValues() == null || p.scopeValues().isEmpty() ? null : String.join(",", new TreeSet<>(p.scopeValues())));
            rolePermMapper.insert(rp);
        }
        role.setVersion((role.getVersion() == null ? 1 : role.getVersion()) + 1);
        role.setUpdatedAt(Instant.now());
        roleMapper.updateById(role);
        snapshot(role, "设置权限（" + codes.size() + " 项）");
        authz.bumpVersion();
        emitRole("role.updated", role, Map.of("permissions", new TreeSet<>(codes)));
        auditAdmin("设置角色权限", roleId, Map.of("permissions", new TreeSet<>(codes).toString()));
        return get(roleId);
    }

    public List<RoleDtos.RoleVersionView> versions(String roleId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        require(roleId);
        return versionMapper.selectList(new LambdaQueryWrapper<RoleVersion>().eq(RoleVersion::getRoleId, roleId)
                        .orderByDesc(RoleVersion::getVersion).orderByDesc(RoleVersion::getCreatedAt)).stream()
                .map(v -> new RoleDtos.RoleVersionView(v.getId(), v.getVersion(), v.getSummary(), permsOf(v).size(),
                        subjectNames.userName(v.getCreatedBy()), v.getCreatedAt()))
                .toList();
    }

    /** 回滚到某版本的权限。 */
    @Transactional
    public RoleDtos.RoleView restore(String roleId, String versionId) {
        RoleVersion v = versionMapper.selectById(versionId);
        if (v == null || !v.getRoleId().equals(roleId)) {
            throw BizException.notFound("角色版本");
        }
        List<RoleDtos.RolePermission> perms = permsOf(v);
        RoleDtos.RoleView view = setPermissions(roleId, new RoleDtos.SetPermissions(perms));
        auditAdmin("回滚角色权限", roleId, Map.of("toVersion", v.getVersion()));
        return view;
    }

    private List<RoleDtos.RolePermission> permsOf(RoleVersion v) {
        Map<String, Object> snap = json.readValue(v.getSnapshot(), new TypeReference<Map<String, Object>>() {
        });
        List<RoleDtos.RolePermission> out = new ArrayList<>();
        Object list = snap.get("permissions");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Object vals = m.get("scopeValues");
                    Set<String> sv = vals instanceof List<?> vl ? vl.stream().map(String::valueOf).collect(Collectors.toSet()) : Set.of();
                    out.add(new RoleDtos.RolePermission(String.valueOf(m.get("permCode")),
                            m.get("dataScope") == null ? null : String.valueOf(m.get("dataScope")), sv));
                }
            }
        }
        return out;
    }

    private void snapshot(Role r, String summary) {
        List<Map<String, Object>> perms = new ArrayList<>();
        for (RolePerm p : rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, r.getId()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("permCode", p.getPermCode());
            m.put("dataScope", p.getDataScope());
            m.put("scopeValues", p.getScopeValues() == null ? List.of() : List.of(p.getScopeValues().split(",")));
            perms.add(m);
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", r.getName());
        snap.put("parentRoleId", r.getParentRoleId());
        snap.put("status", r.getStatus());
        snap.put("permissions", perms);
        RoleVersion v = new RoleVersion();
        v.setId(Ids.next("rv"));
        v.setRoleId(r.getId());
        v.setVersion(r.getVersion() == null ? 1 : r.getVersion());
        v.setSummary(summary);
        v.setSnapshot(json.writeValueAsString(snap));
        CurrentActor a = guard.actor();
        v.setCreatedBy(a.isUser() ? a.userId() : null);
        v.setCreatedAt(Instant.now());
        versionMapper.insert(v);
    }

    // ------------------------------------------------------------------ 互斥与外部编码

    /** 互斥关系（双向）：角色 ID → 互斥角色 ID。 */
    public Map<String, Set<String>> mutexMap() {
        Map<String, Set<String>> out = new HashMap<>();
        for (RoleMutex m : mutexMapper.selectList(new LambdaQueryWrapper<>())) {
            out.computeIfAbsent(m.getRoleId(), k -> new HashSet<>()).add(m.getOtherRoleId());
            out.computeIfAbsent(m.getOtherRoleId(), k -> new HashSet<>()).add(m.getRoleId());
        }
        return out;
    }

    @Transactional
    public RoleDtos.RoleView setMutex(String roleId, RoleDtos.SetMutex req) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        Role r = require(roleId);
        Set<String> ids = new TreeSet<>(req.roleIds());
        ids.remove(roleId);
        for (String other : ids) {
            require(other);
        }
        // 已同时持有两个角色的人员：提示先处理
        for (String other : ids) {
            if (mutexMap().getOrDefault(roleId, Set.of()).contains(other)) {
                continue;
            }
            Set<String> a = holdersOf(roleId);
            a.retainAll(holdersOf(other));
            if (!a.isEmpty()) {
                SysUser u = userMapper.selectById(a.iterator().next());
                throw new BizException(ErrorCode.SEPARATION_OF_DUTIES, "已有 " + a.size() + " 人同时拥有【" + r.getName() + "】与【"
                        + require(other).getName() + "】（如 " + (u == null ? "" : u.getName()) + "），请先解除其中一个");
            }
        }
        mutexMapper.delete(new LambdaQueryWrapper<RoleMutex>().eq(RoleMutex::getRoleId, roleId).or().eq(RoleMutex::getOtherRoleId, roleId));
        for (String other : ids) {
            RoleMutex m = new RoleMutex();
            m.setRoleId(roleId);
            m.setOtherRoleId(other);
            mutexMapper.insert(m);
        }
        auditAdmin("设置互斥角色", roleId, Map.of("mutex", ids.toString()));
        return get(roleId);
    }

    /** 当前直接或间接拥有该角色的人员（按绑定主体展开）。 */
    private Set<String> holdersOf(String roleId) {
        Set<String> out = new HashSet<>();
        for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, roleId))) {
            out.addAll(members.of(b.getSubjectType(), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren())));
        }
        return out;
    }

    public List<RoleDtos.MappingView> mappings(String roleId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        require(roleId);
        Map<String, String> apps = appMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));
        return mappingMapper.selectList(new LambdaQueryWrapper<RoleMapping>().eq(RoleMapping::getRoleId, roleId)
                        .orderByAsc(RoleMapping::getAppCode)).stream()
                .map(m -> new RoleDtos.MappingView(m.getAppCode(), apps.getOrDefault(m.getAppCode(), m.getAppCode()), m.getExternalCode()))
                .toList();
    }

    @Transactional
    public List<RoleDtos.MappingView> setMappings(String roleId, RoleDtos.SetMappings req) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        Role r = require(roleId);
        Set<String> seen = new HashSet<>();
        for (RoleDtos.Mapping m : req.mappings()) {
            if (appMapper.selectById(m.appCode()) == null) {
                throw BizException.notFound("应用【" + m.appCode() + "】");
            }
            if (!seen.add(m.appCode())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "同一应用只能映射一个外部编码");
            }
        }
        mappingMapper.delete(new LambdaQueryWrapper<RoleMapping>().eq(RoleMapping::getRoleId, roleId));
        for (RoleDtos.Mapping m : req.mappings()) {
            RoleMapping rm = new RoleMapping();
            rm.setId(Ids.next("rmp"));
            rm.setRoleId(roleId);
            rm.setAppCode(m.appCode());
            rm.setExternalCode(m.externalCode().trim());
            mappingMapper.insert(rm);
        }
        authz.bumpVersion();
        auditAdmin("设置外部编码映射", roleId, Map.of("mappings", req.mappings().toString()));
        emitRole("role.updated", r, Map.of("mappingChanged", true));
        return mappings(roleId);
    }

    /** 外部编码：应用 → 编码。 */
    public Map<String, String> externalCodes(Role r) {
        return mappingMapper.selectList(new LambdaQueryWrapper<RoleMapping>().eq(RoleMapping::getRoleId, r.getId())).stream()
                .collect(Collectors.toMap(RoleMapping::getAppCode, RoleMapping::getExternalCode));
    }

    /** 角色的下发目标应用：所属应用 + 配置了外部编码的应用 + 角色权限涉及的应用。 */
    public Set<String> targetApps(Role r) {
        Set<String> out = new LinkedHashSet<>();
        if (r.getAppCode() != null) {
            out.add(r.getAppCode());
        }
        mappingMapper.selectList(new LambdaQueryWrapper<RoleMapping>().eq(RoleMapping::getRoleId, r.getId()))
                .forEach(m -> out.add(m.getAppCode()));
        Set<String> codes = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, r.getId()))
                .stream().map(RolePerm::getPermCode).collect(Collectors.toSet());
        if (!codes.isEmpty()) {
            pointMapper.selectByIds(codes).forEach(p -> out.add(p.getAppCode()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 成员与下发

    public List<RoleDtos.MemberView> members(String roleId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        require(roleId);
        Map<String, Set<String>> via = new LinkedHashMap<>();
        for (RoleBinding b : bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, roleId))) {
            String label = labelOf(b.getSubjectType(), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren()));
            for (String uid : members.of(b.getSubjectType(), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren()))) {
                via.computeIfAbsent(uid, k -> new LinkedHashSet<>()).add(label);
            }
        }
        if (via.isEmpty()) {
            return List.of();
        }
        Map<String, String> depts = new HashMap<>();
        return userMapper.selectByIds(via.keySet()).stream()
                .sorted(Comparator.comparing(SysUser::getEmpNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(u -> new RoleDtos.MemberView(u.getId(), u.getName(), u.getEmpNo(),
                        u.getPrimaryOrgId() == null ? null : depts.computeIfAbsent(u.getPrimaryOrgId(),
                                k -> java.util.Optional.ofNullable(orgMapper.selectById(k)).map(OrgUnit::getName).orElse(null)),
                        List.copyOf(via.get(u.getId()))))
                .toList();
    }

    private String labelOf(String type, String id, boolean children) {
        String name = subjectNames.nameOf(type, id);
        return switch (type) {
            case "USER" -> "个人绑定";
            case "POSITION" -> "岗位：" + name;
            case "ORG" -> "组织：" + name + (children ? "（含下级）" : "");
            default -> "用户组：" + name;
        };
    }

    /** 每个目标应用的最近一次投递。 */
    public List<RoleDtos.DistributionRow> distribution(String roleId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        Role r = require(roleId);
        Map<String, String> codes = externalCodes(r);
        Map<String, String> apps = appMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));
        Map<String, DistEvent> events = eventMapper.selectList(new LambdaQueryWrapper<DistEvent>().eq(DistEvent::getRefType, "role")
                        .eq(DistEvent::getRefId, roleId).orderByDesc(DistEvent::getCreatedAt).last("LIMIT 200")).stream()
                .collect(Collectors.toMap(DistEvent::getId, e -> e));
        Map<String, DistDelivery> latest = new HashMap<>();
        if (!events.isEmpty()) {
            for (DistDelivery d : deliveryMapper.selectList(new LambdaQueryWrapper<DistDelivery>().in(DistDelivery::getEventId, events.keySet())
                    .orderByDesc(DistDelivery::getCreatedAt))) {
                latest.putIfAbsent(d.getAppCode(), d);
            }
        }
        List<RoleDtos.DistributionRow> out = new ArrayList<>();
        for (String app : targetApps(r)) {
            DistDelivery d = latest.get(app);
            DistEvent e = d == null ? null : events.get(d.getEventId());
            boolean webhook = distribution.webhookEnabled(app);
            out.add(new RoleDtos.DistributionRow(app, apps.getOrDefault(app, app), codes.get(app),
                    e == null ? null : e.getType(), e == null ? null : DistributionService.EVENT_LABELS.getOrDefault(e.getType(), e.getType()),
                    d == null ? null : d.getId(), d == null ? null : d.getStatus(), d == null ? null : d.getAttempts(),
                    d == null ? null : d.getLastError(), d == null ? null : d.getLatencyMs(),
                    d == null ? null : (d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt()), webhook));
        }
        return out;
    }

    /** 立即对账：向全部目标应用推送角色定义与完整成员清单，由应用侧以此为准进行校正。 */
    @Transactional
    public int fullSync(String roleId) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        Role r = require(roleId);
        List<Map<String, Object>> people = new ArrayList<>();
        for (String uid : holdersOf(roleId)) {
            SysUser u = userMapper.selectById(uid);
            if (u != null && AuthzService.STATUS_ACTIVE.equals(u.getStatus())) {
                people.add(Map.of("uid", u.getId(), "empNo", u.getEmpNo() == null ? "" : u.getEmpNo(), "name", u.getName()));
            }
        }
        Set<String> perms = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, roleId))
                .stream().map(RolePerm::getPermCode).collect(Collectors.toCollection(TreeSet::new));
        emitRole("role.full_sync", r, Map.of("members", people, "permissions", perms));
        auditAdmin("角色对账（全量同步）", roleId, Map.of("members", people.size()));
        return targetApps(r).size();
    }

    private void emitRole(String type, Role r, Map<String, ?> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roleId", r.getId());
        data.put("roleCode", r.getCode());
        data.put("roleName", r.getName());
        data.put("version", r.getVersion());
        data.put("externalCodes", externalCodes(r));
        data.putAll(extra);
        distribution.emit(type, "role", r.getId(), targetApps(r), data);
    }

    // ------------------------------------------------------------------ 绑定

    public List<RoleDtos.BindingView> bindings(String roleId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        require(roleId);
        return bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, roleId)
                        .orderByAsc(RoleBinding::getCreatedAt)).stream()
                .map(b -> new RoleDtos.BindingView(b.getId(), b.getRoleId(), b.getSubjectType(), b.getSubjectId(),
                        subjectNames.nameOf(b.getSubjectType(), b.getSubjectId()), Boolean.TRUE.equals(b.getIncludeChildren()),
                        b.getValidFrom(), b.getValidTo(), subjectNames.userName(b.getCreatedBy()), b.getCreatedAt()))
                .toList();
    }

    /** 某主体（人员/岗位/组织/用户组）直接绑定的角色。 */
    public List<RoleDtos.BindingView> bindingsOfSubject(String subjectType, String subjectId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        Map<String, String> roleNames = roleMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(Role::getId, Role::getName));
        return bindingMapper.selectList(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getSubjectType, subjectType)
                        .eq(RoleBinding::getSubjectId, subjectId)).stream()
                .map(b -> new RoleDtos.BindingView(b.getId(), b.getRoleId(), b.getSubjectType(), b.getSubjectId(),
                        roleNames.getOrDefault(b.getRoleId(), b.getRoleId()), Boolean.TRUE.equals(b.getIncludeChildren()),
                        b.getValidFrom(), b.getValidTo(), subjectNames.userName(b.getCreatedBy()), b.getCreatedAt()))
                .toList();
    }

    @Transactional
    public RoleBinding bind(String roleId, RoleDtos.Bind req) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        Role role = require(roleId);
        if (role.getStatus() != null && role.getStatus() == 0) {
            throw new BizException(ErrorCode.CONFLICT, "角色【" + role.getName() + "】已停用");
        }
        EffectivePermissions mine = guard.permissions();
        boolean any = mine.has(PdsPermissions.GRANT_ANY);
        Set<String> rolePerms = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, roleId))
                .stream().map(RolePerm::getPermCode).collect(Collectors.toSet());
        requireNotExceeding(mine, any, rolePerms);

        String subjectScenic = appService.subjectScenic(req.subjectType(), req.subjectId());
        if ("SCENIC".equals(role.getLevel()) && !Objects.equals(role.getScenicId(), subjectScenic)) {
            throw new BizException(ErrorCode.SCENIC_DENIED, "景区级角色只能绑定本景区的人员、岗位、组织或用户组");
        }
        checkMutex(role, req.subjectType(), req.subjectId(), Boolean.TRUE.equals(req.includeChildren()));
        if (req.validFrom() != null && req.validTo() != null && !req.validTo().isAfter(req.validFrom())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期结束时间必须晚于开始时间");
        }
        CurrentActor a = guard.requireUser();
        RoleBinding b = new RoleBinding();
        b.setId(Ids.next("rb"));
        b.setRoleId(roleId);
        b.setSubjectType(req.subjectType());
        b.setSubjectId(req.subjectId());
        b.setIncludeChildren("ORG".equals(req.subjectType()) && Boolean.TRUE.equals(req.includeChildren()));
        b.setValidFrom(req.validFrom());
        b.setValidTo(req.validTo());
        b.setCreatedBy(a.userId());
        b.setCreatedAt(Instant.now());
        bindingMapper.insert(b);
        authz.bumpVersion();
        emitMembers(role, b, "added");
        auditAdmin("绑定角色", roleId, Map.of("role", role.getName(), "subject", req.subjectType() + ":" + req.subjectId(),
                "includeChildren", b.getIncludeChildren()));
        return b;
    }

    @Transactional
    public void updateBindingValidity(String bindingId, Instant validTo, boolean clear) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        RoleBinding b = bindingMapper.selectById(bindingId);
        if (b == null) {
            throw BizException.notFound("角色绑定");
        }
        if (!clear && validTo != null && !validTo.isAfter(Instant.now())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期必须晚于当前时间");
        }
        bindingMapper.update(null, new LambdaUpdateWrapper<RoleBinding>().eq(RoleBinding::getId, bindingId)
                .set(RoleBinding::getValidTo, clear ? null : validTo));
        authz.bumpVersion();
        auditAdmin("修改角色绑定有效期", b.getRoleId(), Map.of("bindingId", bindingId, "validTo", clear ? "长期" : String.valueOf(validTo)));
    }

    @Transactional
    public void unbind(String bindingId) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        RoleBinding b = bindingMapper.selectById(bindingId);
        if (b == null) {
            throw BizException.notFound("角色绑定");
        }
        Role role = require(b.getRoleId());
        if (PdsPermissions.ROLE_SUPER_ADMIN.equals(role.getCode()) && countBindings(role.getId()) <= 1) {
            Role sec = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getCode, PdsPermissions.ROLE_SEC_ADMIN));
            if (sec == null || countBindings(sec.getId()) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "请先指定安全管理员，再解除最后一个超级管理员");
            }
        }
        bindingMapper.deleteById(bindingId);
        authz.bumpVersion();
        emitMembers(role, b, "removed");
        auditAdmin("解除角色绑定", b.getRoleId(), Map.of("role", role.getName(), "subject", b.getSubjectType() + ":" + b.getSubjectId()));
    }

    private void emitMembers(Role role, RoleBinding b, String direction) {
        List<Map<String, Object>> people = new ArrayList<>();
        for (String uid : members.of(b.getSubjectType(), b.getSubjectId(), Boolean.TRUE.equals(b.getIncludeChildren()))) {
            SysUser u = userMapper.selectById(uid);
            if (u != null) {
                people.add(Map.of("uid", u.getId(), "empNo", u.getEmpNo() == null ? "" : u.getEmpNo(), "name", u.getName()));
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("subject", Map.of("type", b.getSubjectType(), "id", b.getSubjectId(),
                "name", subjectNames.nameOf(b.getSubjectType(), b.getSubjectId())));
        extra.put("added", "added".equals(direction) ? people : List.of());
        extra.put("removed", "removed".equals(direction) ? people : List.of());
        emitRole("role.members.changed", role, extra);
    }

    private long countBindings(String roleId) {
        return bindingMapper.selectCount(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, roleId));
    }

    /**
     * 互斥校验：绑定到人员时，若此人已拥有互斥角色则拒绝；绑定到组织/岗位/用户组时，若覆盖的人员中有人已拥有互斥角色则拒绝。
     */
    private void checkMutex(Role role, String subjectType, String subjectId, boolean includeChildren) {
        Set<String> others = mutexMap().getOrDefault(role.getId(), Set.of());
        if (others.isEmpty()) {
            return;
        }
        for (String uid : members.of(subjectType, subjectId, includeChildren)) {
            SysUser u = userMapper.selectById(uid);
            if (u == null || !AuthzService.STATUS_ACTIVE.equals(u.getStatus())) {
                continue;
            }
            Set<String> current = authz.activeRoleIds(uid);
            for (String o : others) {
                if (current.contains(o)) {
                    Role other = roleMapper.selectById(o);
                    boolean separated = PdsPermissions.SEPARATED_ROLES.contains(role.getCode())
                            && other != null && PdsPermissions.SEPARATED_ROLES.contains(other.getCode());
                    throw new BizException(ErrorCode.SEPARATION_OF_DUTIES, (separated ? "三员分立：" : "职责分离：")
                            + "人员【" + u.getName() + "】已拥有【" + (other == null ? o : other.getName()) + "】，与【"
                            + role.getName() + "】互斥");
                }
            }
        }
    }

    private static void requireNotExceeding(EffectivePermissions mine, boolean any, Set<String> codes) {
        if (any) {
            return;
        }
        Set<String> exceed = new TreeSet<>();
        for (String c : codes) {
            if (!mine.has(c)) {
                exceed.add(c);
            }
        }
        if (!exceed.isEmpty()) {
            throw new BizException(ErrorCode.GRANT_EXCEEDS_OWN, "授予的权限超出了您自身拥有的权限：" + exceed);
        }
    }

    private void auditAdmin(String action, String targetId, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("role", targetId).detail(detail));
    }
}
