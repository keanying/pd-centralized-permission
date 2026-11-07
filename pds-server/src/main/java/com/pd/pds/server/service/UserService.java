package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pd.pds.authz.ResolvedScope;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.common.PageResult;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PositionMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.UserDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final UserOrgMapper userOrgMapper;
    private final OrgUnitMapper orgMapper;
    private final PositionMapper positionMapper;
    private final OrgService orgService;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;
    private final TokenEpochService epochs;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public UserService(SysUserMapper userMapper, UserOrgMapper userOrgMapper, OrgUnitMapper orgMapper,
                       PositionMapper positionMapper, OrgService orgService, Guard guard, AuthzService authz,
                       AuditService audit, TokenEpochService epochs, PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.userOrgMapper = userOrgMapper;
        this.orgMapper = orgMapper;
        this.positionMapper = positionMapper;
        this.orgService = orgService;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
        this.epochs = epochs;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<UserDtos.UserView> page(String keyword, String orgId, boolean includeChildren, String status,
                                              long page, long size) {
        ResolvedScope scope = guard.require(PdsPermissions.USER_VIEW);
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<SysUser>()
                .eq(StringUtils.hasText(status), SysUser::getStatus, status)
                .and(StringUtils.hasText(keyword), q -> q.like(SysUser::getName, keyword).or().like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getEmpNo, keyword))
                .orderByAsc(SysUser::getUsername);
        if (StringUtils.hasText(orgId)) {
            OrgUnit org = orgService.require(orgId);
            Set<String> orgIds = includeChildren
                    ? orgMapper.selectList(new LambdaQueryWrapper<OrgUnit>().likeRight(OrgUnit::getPath, org.getPath()))
                    .stream().map(OrgUnit::getId).collect(Collectors.toSet())
                    : Set.of(orgId);
            restrictToOrgs(w, orgIds);
        }
        if (!scope.isAll()) {
            Set<String> allowedOrgs = orgMapper.selectList(new LambdaQueryWrapper<>()).stream()
                    .filter(o -> Guard.covers(scope, o)).map(OrgUnit::getId).collect(Collectors.toSet());
            restrictToOrgs(w, allowedOrgs);
        }
        Page<SysUser> p = userMapper.selectPage(new Page<>(page, size), w);
        return new PageResult<>(p.getTotal(), page, size, toViews(p.getRecords()));
    }

    private void restrictToOrgs(LambdaQueryWrapper<SysUser> w, Collection<String> orgIds) {
        if (orgIds.isEmpty()) {
            w.apply("1 = 0");
            return;
        }
        Set<String> userIds = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().in(UserOrg::getOrgId, orgIds))
                .stream().map(UserOrg::getUserId).collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            w.apply("1 = 0");
        } else {
            w.in(SysUser::getId, userIds);
        }
    }

    public UserDtos.UserView get(String id) {
        guard.require(PdsPermissions.USER_VIEW);
        requireInScope(authz.requireUser(id), PdsPermissions.USER_VIEW);
        return toViews(List.of(authz.requireUser(id))).get(0);
    }

    private List<UserDtos.UserView> toViews(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        Set<String> ids = users.stream().map(SysUser::getId).collect(Collectors.toSet());
        List<UserOrg> orgs = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().in(UserOrg::getUserId, ids));
        Set<String> posIds = orgs.stream().map(UserOrg::getPositionId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> posNames = posIds.isEmpty() ? Map.of()
                : positionMapper.selectByIds(posIds).stream().collect(Collectors.toMap(Position::getId, Position::getName));
        Set<String> orgIds = users.stream().map(SysUser::getPrimaryOrgId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> orgNames = orgIds.isEmpty() ? Map.of()
                : orgMapper.selectByIds(orgIds).stream().collect(Collectors.toMap(OrgUnit::getId, OrgUnit::getName));
        Map<String, List<String>> positionsByUser = new HashMap<>();
        for (UserOrg uo : orgs) {
            if (uo.getPositionId() != null) {
                positionsByUser.computeIfAbsent(uo.getUserId(), k -> new ArrayList<>()).add(posNames.getOrDefault(uo.getPositionId(), uo.getPositionId()));
            }
        }
        Instant now = Instant.now();
        return users.stream().map(u -> new UserDtos.UserView(u.getId(), u.getUsername(), u.getEmpNo(), u.getName(),
                        maskMobile(u.getMobile()), u.getEmail(), u.getUserType(), u.getStatus(), u.getPrimaryOrgId(),
                        orgNames.get(u.getPrimaryOrgId()), u.getScenicId(), positionsByUser.getOrDefault(u.getId(), List.of()),
                        u.getLastLoginAt(), u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)))
                .toList();
    }

    /** 列表中的手机号默认脱敏（保留前 3 后 4）。 */
    static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return mobile;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    // ------------------------------------------------------------------ 维护

    @Transactional
    public UserDtos.Credential create(UserDtos.CreateUser req) {
        ResolvedScope scope = guard.require(PdsPermissions.USER_MANAGE);
        OrgUnit org = orgService.require(req.orgId());
        guard.requireCovers(scope, org, PdsPermissions.USER_MANAGE);
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.username())) > 0) {
            throw BizException.duplicate("账号【" + req.username() + "】");
        }
        if (StringUtils.hasText(req.empNo())
                && userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmpNo, req.empNo())) > 0) {
            throw BizException.duplicate("工号【" + req.empNo() + "】");
        }
        if (req.positionId() != null && positionMapper.selectById(req.positionId()) == null) {
            throw BizException.notFound("岗位");
        }
        String password = StringUtils.hasText(req.password()) ? req.password() : PasswordPolicy.generate();
        PasswordPolicy.validate(req.username(), password);
        Instant now = Instant.now();
        SysUser u = new SysUser();
        u.setId(Ids.next("u"));
        u.setUsername(req.username());
        u.setEmpNo(StringUtils.hasText(req.empNo()) ? req.empNo() : null);
        u.setName(req.name());
        u.setMobile(req.mobile());
        u.setEmail(req.email());
        u.setUserType(StringUtils.hasText(req.userType()) ? req.userType() : "FULL_TIME");
        u.setStatus(AuthzService.STATUS_ACTIVE);
        u.setPrimaryOrgId(org.getId());
        u.setScenicId(org.getScenicId());
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setMustChangePassword(true);
        u.setFailedAttempts(0);
        u.setTokenEpoch(0L);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userMapper.insert(u);
        insertUserOrg(u.getId(), org.getId(), req.positionId(), true);
        auditAdmin("创建人员", u.getId(), Map.of("username", u.getUsername(), "orgId", org.getId()));
        return new UserDtos.Credential(u.getId(), u.getUsername(), StringUtils.hasText(req.password()) ? null : password);
    }

    @Transactional
    public UserDtos.UserView update(String id, UserDtos.UpdateUser req) {
        guard.require(PdsPermissions.USER_MANAGE);
        SysUser u = requireInScope(authz.requireUser(id), PdsPermissions.USER_MANAGE);
        if (req.name() != null) {
            u.setName(req.name());
        }
        if (req.mobile() != null) {
            u.setMobile(req.mobile());
        }
        if (req.email() != null) {
            u.setEmail(req.email());
        }
        if (req.userType() != null) {
            u.setUserType(req.userType());
        }
        if (req.empNo() != null) {
            u.setEmpNo(req.empNo());
        }
        u.setUpdatedAt(Instant.now());
        userMapper.updateById(u);
        auditAdmin("修改人员", id, Map.of("fields", req.toString()));
        return get(id);
    }

    /** 调岗/兼岗：整体替换任职关系，重新确定主部门与所属景区。 */
    @Transactional
    public UserDtos.UserView assignOrgs(String id, UserDtos.AssignOrgs req) {
        ResolvedScope scope = guard.require(PdsPermissions.USER_MANAGE);
        SysUser u = requireInScope(authz.requireUser(id), PdsPermissions.USER_MANAGE);
        long primaries = req.assignments().stream().filter(a -> Boolean.TRUE.equals(a.primary())).count();
        if (primaries != 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "必须且只能指定一个主部门");
        }
        Map<String, OrgUnit> orgs = new LinkedHashMap<>();
        for (UserDtos.Assignment a : req.assignments()) {
            OrgUnit o = orgService.require(a.orgId());
            guard.requireCovers(scope, o, PdsPermissions.USER_MANAGE);
            if (a.positionId() != null && positionMapper.selectById(a.positionId()) == null) {
                throw BizException.notFound("岗位【" + a.positionId() + "】");
            }
            orgs.put(o.getId(), o);
        }
        userOrgMapper.delete(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, id));
        OrgUnit primary = null;
        for (UserDtos.Assignment a : req.assignments()) {
            boolean isPrimary = Boolean.TRUE.equals(a.primary());
            insertUserOrg(id, a.orgId(), a.positionId(), isPrimary);
            if (isPrimary) {
                primary = orgs.get(a.orgId());
            }
        }
        u.setPrimaryOrgId(primary.getId());
        u.setUpdatedAt(Instant.now());
        userMapper.updateById(u);
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>().set(SysUser::getScenicId, primary.getScenicId()).eq(SysUser::getId, id));
        authz.bumpVersion();
        auditAdmin("调整人员任职", id, Map.of("assignments", req.assignments().toString()));
        return get(id);
    }

    /** 停用（离职）：立即吊销全部令牌与刷新令牌。 */
    @Transactional
    public void disable(String id, String status, String reason) {
        guard.require(PdsPermissions.USER_MANAGE);
        SysUser u = requireInScope(authz.requireUser(id), PdsPermissions.USER_MANAGE);
        if (u.getId().equals(guard.actor().userId())) {
            throw new BizException(ErrorCode.CONFLICT, "不能停用自己的账号");
        }
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getStatus, status)
                .set(SysUser::getTokenEpoch, Instant.now().getEpochSecond() + 1)
                .set(SysUser::getUpdatedAt, Instant.now())
                .eq(SysUser::getId, id));
        revokeAuthorizations(u.getUsername());
        epochs.evict(id);
        authz.bumpVersion();
        auditAdmin("LEFT".equals(status) ? "人员离职" : "停用人员", id, Map.of("reason", reason == null ? "" : reason));
    }

    @Transactional
    public void enable(String id) {
        guard.require(PdsPermissions.USER_MANAGE);
        requireInScope(authz.requireUser(id), PdsPermissions.USER_MANAGE);
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getStatus, AuthzService.STATUS_ACTIVE)
                .set(SysUser::getFailedAttempts, 0)
                .set(SysUser::getLockedUntil, null)
                .set(SysUser::getUpdatedAt, Instant.now())
                .eq(SysUser::getId, id));
        epochs.evict(id);
        authz.bumpVersion();
        auditAdmin("启用人员", id, Map.of());
    }

    @Transactional
    public UserDtos.Credential resetPassword(String id) {
        guard.require(PdsPermissions.USER_MANAGE);
        SysUser u = requireInScope(authz.requireUser(id), PdsPermissions.USER_MANAGE);
        String password = PasswordPolicy.generate();
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getPasswordHash, passwordEncoder.encode(password))
                .set(SysUser::getMustChangePassword, true)
                .set(SysUser::getFailedAttempts, 0)
                .set(SysUser::getLockedUntil, null)
                .set(SysUser::getTokenEpoch, Instant.now().getEpochSecond() + 1)
                .set(SysUser::getUpdatedAt, Instant.now())
                .eq(SysUser::getId, id));
        revokeAuthorizations(u.getUsername());
        epochs.evict(id);
        auditAdmin("重置密码", id, Map.of());
        return new UserDtos.Credential(id, u.getUsername(), password);
    }

    /** 本人修改密码（个人中心或首次登录强制修改）。 */
    @Transactional
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (u == null || !passwordEncoder.matches(oldPassword, u.getPasswordHash())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, u.getPasswordHash())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "新密码不能与原密码相同");
        }
        PasswordPolicy.validate(username, newPassword);
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getPasswordHash, passwordEncoder.encode(newPassword))
                .set(SysUser::getMustChangePassword, false)
                .set(SysUser::getPasswordChangedAt, Instant.now())
                .set(SysUser::getUpdatedAt, Instant.now())
                .eq(SysUser::getId, u.getId()));
        audit.record(AuditService.Entry.of("PASSWORD_CHANGE", "修改密码", "SUCCESS")
                .actor(u.getId(), u.getName(), com.pd.pds.server.security.ActorResolver.clientIp()));
    }

    // ------------------------------------------------------------------ 内部

    private SysUser requireInScope(SysUser u, String permCode) {
        ResolvedScope scope = guard.require(permCode);
        if (scope.isAll() || u.getId().equals(guard.actor().userId())) {
            return u;
        }
        List<UserOrg> orgs = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, u.getId()));
        Set<String> orgIds = new HashSet<>();
        orgs.forEach(o -> orgIds.add(o.getOrgId()));
        if (!orgIds.isEmpty() && orgMapper.selectByIds(orgIds).stream().anyMatch(o -> Guard.covers(scope, o))) {
            return u;
        }
        throw new BizException(ErrorCode.SCENIC_DENIED, "人员【" + u.getName() + "】不在您的管理范围内");
    }

    private void insertUserOrg(String userId, String orgId, String positionId, boolean primary) {
        UserOrg uo = new UserOrg();
        uo.setId(Ids.next("uo"));
        uo.setUserId(userId);
        uo.setOrgId(orgId);
        uo.setPositionId(positionId);
        uo.setIsPrimary(primary);
        userOrgMapper.insert(uo);
    }

    private void revokeAuthorizations(String username) {
        jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", username);
    }

    private void auditAdmin(String action, String targetId, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("user", targetId).detail(detail));
    }
}
