package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.RoleDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 角色与角色绑定。约束：授权不越级（pds:grant:any 除外）、三员分立、景区角色只能绑定本景区主体。
 */
@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final RoleBindingMapper bindingMapper;
    private final PermPointMapper pointMapper;
    private final AppService appService;
    private final SubjectNames subjectNames;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public RoleService(RoleMapper roleMapper, RolePermMapper rolePermMapper, RoleBindingMapper bindingMapper,
                       PermPointMapper pointMapper, AppService appService, SubjectNames subjectNames, Guard guard,
                       AuthzService authz, AuditService audit) {
        this.subjectNames = subjectNames;
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.bindingMapper = bindingMapper;
        this.pointMapper = pointMapper;
        this.appService = appService;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    public List<RoleDtos.RoleView> list() {
        guard.require(PdsPermissions.ROLE_VIEW);
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getCode));
        Map<String, List<RolePerm>> perms = rolePermMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.groupingBy(RolePerm::getRoleId));
        Map<String, Long> bindings = bindingMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.groupingBy(RoleBinding::getRoleId, Collectors.counting()));
        return roles.stream().map(r -> view(r, perms.getOrDefault(r.getId(), List.of()), bindings.getOrDefault(r.getId(), 0L))).toList();
    }

    public RoleDtos.RoleView get(String id) {
        guard.require(PdsPermissions.ROLE_VIEW);
        Role r = require(id);
        return view(r, rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, id)),
                bindingMapper.selectCount(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, id)));
    }

    private static RoleDtos.RoleView view(Role r, List<RolePerm> perms, long bindingCount) {
        List<RoleDtos.RolePermission> ps = perms.stream()
                .map(p -> new RoleDtos.RolePermission(p.getPermCode(), p.getDataScope(),
                        p.getScopeValues() == null || p.getScopeValues().isBlank() ? Set.of() : new TreeSet<>(List.of(p.getScopeValues().split(",")))))
                .sorted(java.util.Comparator.comparing(RoleDtos.RolePermission::permCode))
                .toList();
        return new RoleDtos.RoleView(r.getId(), r.getCode(), r.getName(), r.getLevel(), r.getScenicId(), r.getDescription(),
                Boolean.TRUE.equals(r.getBuiltin()), ps, bindingCount);
    }

    public Role require(String id) {
        Role r = roleMapper.selectById(id);
        if (r == null) {
            throw BizException.notFound("角色");
        }
        return r;
    }

    @Transactional
    public Role create(RoleDtos.CreateRole req) {
        guard.require(PdsPermissions.ROLE_MANAGE);
        if (roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getCode, req.code())) > 0) {
            throw BizException.duplicate("角色编码【" + req.code() + "】");
        }
        String level = req.level() == null ? "GROUP" : req.level();
        if ("SCENIC".equals(level) && (req.scenicId() == null || req.scenicId().isBlank())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "景区级角色必须指定景区");
        }
        Instant now = Instant.now();
        Role r = new Role();
        r.setId(Ids.next("role"));
        r.setCode(req.code());
        r.setName(req.name());
        r.setLevel(level);
        r.setScenicId("SCENIC".equals(level) ? req.scenicId() : null);
        r.setDescription(req.description());
        r.setBuiltin(false);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        roleMapper.insert(r);
        auditAdmin("创建角色", r.getId(), Map.of("code", r.getCode(), "name", r.getName()));
        return r;
    }

    /** 整体替换角色的权限点。 */
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
            Set<String> known = pointMapper.selectByIds(codes).stream().map(PermPoint::getCode).collect(Collectors.toSet());
            Set<String> unknown = new TreeSet<>(codes);
            unknown.removeAll(known);
            if (!unknown.isEmpty()) {
                throw BizException.notFound("权限点 " + unknown);
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
        authz.bumpVersion();
        auditAdmin("设置角色权限", roleId, Map.of("permissions", new TreeSet<>(codes).toString()));
        return get(roleId);
    }

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
        EffectivePermissions mine = guard.permissions();
        boolean any = mine.has(PdsPermissions.GRANT_ANY);
        Set<String> rolePerms = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, roleId))
                .stream().map(RolePerm::getPermCode).collect(Collectors.toSet());
        requireNotExceeding(mine, any, rolePerms);

        String subjectScenic = appService.subjectScenic(req.subjectType(), req.subjectId());
        if ("SCENIC".equals(role.getLevel()) && !role.getScenicId().equals(subjectScenic)) {
            throw new BizException(ErrorCode.SCENIC_DENIED, "景区级角色只能绑定本景区的人员、岗位、组织或用户组");
        }
        if (PdsPermissions.SEPARATED_ROLES.contains(role.getCode()) && "USER".equals(req.subjectType())) {
            checkSeparation(role, req.subjectId());
        }
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
        auditAdmin("绑定角色", roleId, Map.of("role", role.getName(), "subject", req.subjectType() + ":" + req.subjectId(),
                "includeChildren", b.getIncludeChildren()));
        return b;
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
        auditAdmin("解除角色绑定", b.getRoleId(), Map.of("role", role.getName(), "subject", b.getSubjectType() + ":" + b.getSubjectId()));
    }

    private long countBindings(String roleId) {
        return bindingMapper.selectCount(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getRoleId, roleId));
    }

    /** 三员分立：同一人员不能直接同时绑定系统管理员、安全管理员、审计管理员中的两个。 */
    private void checkSeparation(Role role, String userId) {
        Set<String> otherIds = roleMapper.selectList(new LambdaQueryWrapper<Role>().in(Role::getCode, PdsPermissions.SEPARATED_ROLES))
                .stream().filter(r -> !r.getId().equals(role.getId())).map(Role::getId).collect(Collectors.toSet());
        if (!otherIds.isEmpty() && bindingMapper.selectCount(new LambdaQueryWrapper<RoleBinding>()
                .in(RoleBinding::getRoleId, otherIds).eq(RoleBinding::getSubjectType, "USER").eq(RoleBinding::getSubjectId, userId)) > 0) {
            throw new BizException(ErrorCode.SEPARATION_OF_DUTIES, "三员分立：该人员已担任其他管理员角色，不能再授予【" + role.getName() + "】");
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
