package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.Decision;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.ResolvedScope;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermResource;
import com.pd.pds.server.entity.ResourceType;
import com.pd.pds.server.entity.SecurityLevelSetting;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.entity.UserPerm;
import com.pd.pds.server.entity.UserScenic;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.PermResourceMapper;
import com.pd.pds.server.mapper.ResourceTypeMapper;
import com.pd.pds.server.mapper.SecurityLevelSettingMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.mapper.UserPermMapper;
import com.pd.pds.server.mapper.UserScenicMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.RoleDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 人员明细权限（以"人"为中心）：查看有效权限及来源、直接授予/禁止、景区范围、密级。
 */
@Service
public class GrantService {

    private final UserPermMapper userPermMapper;
    private final PermPointMapper pointMapper;
    private final PermResourceMapper resourceMapper;
    private final ResourceTypeMapper typeMapper;
    private final UserScenicMapper userScenicMapper;
    private final SecurityLevelSettingMapper levelMapper;
    private final UserOrgMapper userOrgMapper;
    private final OrgUnitMapper orgMapper;
    private final AppService appService;
    private final SubjectNames subjectNames;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public GrantService(UserPermMapper userPermMapper, PermPointMapper pointMapper, PermResourceMapper resourceMapper,
                        ResourceTypeMapper typeMapper, UserScenicMapper userScenicMapper, SecurityLevelSettingMapper levelMapper,
                        UserOrgMapper userOrgMapper, OrgUnitMapper orgMapper, AppService appService,
                        SubjectNames subjectNames, Guard guard, AuthzService authz, AuditService audit) {
        this.subjectNames = subjectNames;
        this.userPermMapper = userPermMapper;
        this.pointMapper = pointMapper;
        this.resourceMapper = resourceMapper;
        this.typeMapper = typeMapper;
        this.userScenicMapper = userScenicMapper;
        this.levelMapper = levelMapper;
        this.userOrgMapper = userOrgMapper;
        this.orgMapper = orgMapper;
        this.appService = appService;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 查看

    /** 人员明细权限页数据：某应用全部权限点的状态（已拥有/已禁止/未拥有）与来源，以及资源授权。 */
    public RoleDtos.UserEffective effectiveView(String userId, String app) {
        if (!guard.has(PdsPermissions.AUTHZ_QUERY) && !guard.has(PdsPermissions.GRANT_MANAGE)) {
            guard.require(PdsPermissions.AUTHZ_QUERY);
        }
        SysUser user = authz.requireUser(userId);
        assertManageable(user, guard.has(PdsPermissions.GRANT_MANAGE) ? PdsPermissions.GRANT_MANAGE : PdsPermissions.AUTHZ_QUERY);
        EffectivePermissions eff = authz.effective(userId);

        List<RoleDtos.PermissionState> perms = new ArrayList<>();
        for (PermPoint p : pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getAppCode, app)
                .orderByAsc(PermPoint::getCode))) {
            Decision d = eff.explain(p.getCode());
            String state = d.allowed() ? "GRANTED" : (d.denySources().isEmpty() ? "NONE" : "DENIED");
            ResolvedScope scope = eff.scopeOf(p.getCode());
            perms.add(new RoleDtos.PermissionState(p.getCode(), p.getName(), p.getType(), p.getRisk(), state,
                    d.allowSources(), d.denySources(), scope == null || scope.isEmpty() ? null : scope,
                    d.allowRefs(), d.denyRefs()));
        }

        List<RoleDtos.ResourceState> resources = new ArrayList<>();
        Set<String> types = typeMapper.selectList(new LambdaQueryWrapper<ResourceType>().eq(ResourceType::getAppCode, app))
                .stream().map(ResourceType::getTypeCode).collect(Collectors.toSet());
        for (String type : types) {
            Set<String> refIds = new TreeSet<>(eff.accessible(type, ResourceAction.READ));
            userPermMapper.selectList(new LambdaQueryWrapper<UserPerm>().eq(UserPerm::getUserId, userId)
                            .eq(UserPerm::getEffect, "DENY").isNotNull(UserPerm::getResourceId))
                    .forEach(up -> {
                        PermResource r = resourceMapper.selectById(up.getResourceId());
                        if (r != null && r.getTypeCode().equals(type)) {
                            refIds.add(r.getRefId());
                        }
                    });
            for (String refId : refIds) {
                PermResource r = appService.findResource(type, refId);
                if (r == null) {
                    continue;
                }
                ResourceKey key = new ResourceKey(type, refId);
                List<String> actions = new ArrayList<>();
                for (ResourceAction a : EnumSet.allOf(ResourceAction.class)) {
                    if (eff.canAccess(key, a)) {
                        actions.add(a.name());
                    }
                }
                Decision d = eff.explain(key, ResourceAction.READ);
                // 可访问的最晚失效时间：任一允许来源为长期则为长期
                Instant validTo = null;
                boolean forever = d.allowRefs().isEmpty();
                for (com.pd.pds.authz.SourceRef ref : d.allowRefs()) {
                    if (ref.validTo() == null) {
                        forever = true;
                    } else if (validTo == null || ref.validTo().isAfter(validTo)) {
                        validTo = ref.validTo();
                    }
                }
                resources.add(new RoleDtos.ResourceState(type, refId, r.getName(), r.getScenicId(), actions, d.reason(),
                        !d.allowed(), forever ? null : validTo, d.allowRefs(), d.denyRefs()));
            }
        }
        return new RoleDtos.UserEffective(userId, user.getName(), app, eff.scenicScope(), eff.maxSecurityLevel(),
                perms, resources, authz.version(), user.getStatus());
    }

    public List<RoleDtos.UserPermView> listUserPerms(String userId) {
        if (!guard.has(PdsPermissions.AUTHZ_QUERY) && !guard.has(PdsPermissions.GRANT_MANAGE)) {
            guard.require(PdsPermissions.GRANT_MANAGE);
        }
        assertManageable(authz.requireUser(userId), guard.has(PdsPermissions.GRANT_MANAGE) ? PdsPermissions.GRANT_MANAGE : PdsPermissions.AUTHZ_QUERY);
        return userPermMapper.selectList(new LambdaQueryWrapper<UserPerm>().eq(UserPerm::getUserId, userId)
                        .orderByDesc(UserPerm::getCreatedAt)).stream()
                .map(up -> {
                    PermResource r = up.getResourceId() == null ? null : resourceMapper.selectById(up.getResourceId());
                    PermPoint p = up.getPermCode() == null ? null : pointMapper.selectById(up.getPermCode());
                    return new RoleDtos.UserPermView(up.getId(), up.getPermCode(), p == null ? null : p.getName(),
                            r == null ? null : r.getTypeCode(), r == null ? null : r.getRefId(), r == null ? null : r.getName(),
                            up.getAction(), up.getEffect(), up.getDataScope(), up.getScopeValues(), up.getValidFrom(),
                            up.getValidTo(), up.getReason(), subjectNames.userName(up.getGrantedBy()), up.getCreatedAt());
                })
                .toList();
    }

    // ------------------------------------------------------------------ 直接授予 / 禁止

    @Transactional
    public UserPerm addUserPerm(String userId, RoleDtos.UserPermRequest req) {
        guard.require(PdsPermissions.GRANT_MANAGE);
        SysUser user = authz.requireUser(userId);
        assertManageable(user, PdsPermissions.GRANT_MANAGE);
        EffectivePermissions mine = guard.permissions();
        boolean any = mine.has(PdsPermissions.GRANT_ANY);
        boolean allow = "ALLOW".equals(req.effect());
        boolean byPerm = StringUtils.hasText(req.permCode());
        boolean byResource = StringUtils.hasText(req.resourceType()) && StringUtils.hasText(req.resourceId());
        if (byPerm == byResource) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "权限点与资源二选一");
        }
        if (req.validFrom() != null && req.validTo() != null && !req.validTo().isAfter(req.validFrom())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期结束时间必须晚于开始时间");
        }
        UserPerm up = new UserPerm();
        up.setId(Ids.next("up"));
        up.setUserId(userId);
        up.setEffect(req.effect());
        up.setValidFrom(req.validFrom());
        up.setValidTo(req.validTo());
        up.setReason(req.reason());
        up.setGrantedBy(guard.requireUser().userId());
        up.setCreatedAt(Instant.now());
        String target;
        if (byPerm) {
            if (pointMapper.selectById(req.permCode()) == null) {
                throw BizException.notFound("权限点【" + req.permCode() + "】");
            }
            if (allow && !any && !mine.has(req.permCode())) {
                throw new BizException(ErrorCode.GRANT_EXCEEDS_OWN, "授予的权限超出了您自身拥有的权限：" + req.permCode());
            }
            up.setPermCode(req.permCode());
            up.setDataScope(req.dataScope());
            up.setScopeValues(req.scopeValues() == null || req.scopeValues().isEmpty() ? null : String.join(",", new TreeSet<>(req.scopeValues())));
            target = req.permCode();
        } else {
            if (req.action() == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "资源授权必须指定动作 READ/WRITE/ADMIN");
            }
            PermResource r = appService.requireResource(req.resourceType(), req.resourceId());
            ResourceAction action = ResourceAction.of(req.action());
            ResourceKey key = new ResourceKey(r.getTypeCode(), r.getRefId());
            if (allow && !any && !mine.canAccess(key, action) && !mine.canAccess(key, ResourceAction.ADMIN)) {
                throw new BizException(ErrorCode.GRANT_EXCEEDS_OWN, "您没有该资源的" + action.label() + "权限，不能授予他人");
            }
            if (allow && !any && !"GROUP".equals(r.getScenicId()) && !r.getScenicId().equals(user.getScenicId())) {
                throw new BizException(ErrorCode.SCENIC_DENIED, "景区资源不能直接授予其他景区的人员");
            }
            up.setResourceId(r.getId());
            up.setAction(action.name());
            target = key + "#" + action.name();
        }
        userPermMapper.insert(up);
        authz.bumpVersion();
        auditAdmin(allow ? "人员明细授予" : "人员明细禁止", userId, Map.of("target", target, "reason", req.reason()));
        return up;
    }

    /** 修改人员明细记录的有效期 / 原因（设置有效期）。 */
    @Transactional
    public void updateUserPerm(String userId, String permId, RoleDtos.UpdateUserPerm req) {
        guard.require(PdsPermissions.GRANT_MANAGE);
        assertManageable(authz.requireUser(userId), PdsPermissions.GRANT_MANAGE);
        UserPerm up = userPermMapper.selectById(permId);
        if (up == null || !up.getUserId().equals(userId)) {
            throw BizException.notFound("人员明细权限");
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserPerm> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserPerm>().eq(UserPerm::getId, permId);
        if (Boolean.TRUE.equals(req.clearValidTo())) {
            w.set(UserPerm::getValidTo, null);
        } else if (req.validTo() != null) {
            if (!req.validTo().isAfter(Instant.now())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期必须晚于当前时间");
            }
            w.set(UserPerm::getValidTo, req.validTo());
        }
        if (StringUtils.hasText(req.reason())) {
            w.set(UserPerm::getReason, req.reason());
        }
        userPermMapper.update(null, w);
        authz.bumpVersion();
        auditAdmin("修改人员明细有效期", userId, Map.of("permId", permId,
                "validTo", Boolean.TRUE.equals(req.clearValidTo()) ? "长期" : String.valueOf(req.validTo())));
    }

    @Transactional
    public void deleteUserPerm(String userId, String permId) {
        guard.require(PdsPermissions.GRANT_MANAGE);
        assertManageable(authz.requireUser(userId), PdsPermissions.GRANT_MANAGE);
        UserPerm up = userPermMapper.selectById(permId);
        if (up == null || !up.getUserId().equals(userId)) {
            throw BizException.notFound("人员明细权限");
        }
        userPermMapper.deleteById(permId);
        authz.bumpVersion();
        auditAdmin("撤销人员明细权限", userId, Map.of("permId", permId,
                "target", up.getPermCode() != null ? up.getPermCode() : up.getResourceId() + "#" + up.getAction()));
    }

    // ------------------------------------------------------------------ 景区范围与密级

    @Transactional
    public Set<String> setScenicScope(String userId, Set<String> scenicIds) {
        guard.require(PdsPermissions.GRANT_MANAGE);
        assertManageable(authz.requireUser(userId), PdsPermissions.GRANT_MANAGE);
        EffectivePermissions mine = guard.permissions();
        boolean any = mine.has(PdsPermissions.GRANT_ANY);
        for (String s : scenicIds) {
            OrgUnit o = orgMapper.selectById(s);
            if (o == null || !"SCENIC".equals(o.getType())) {
                throw BizException.notFound("景区【" + s + "】");
            }
            if (!any && !mine.scenicScope().contains(s)) {
                throw new BizException(ErrorCode.GRANT_EXCEEDS_OWN, "不能授予超出您自身景区范围的景区：" + o.getName());
            }
        }
        userScenicMapper.delete(new LambdaQueryWrapper<UserScenic>().eq(UserScenic::getUserId, userId));
        for (String s : new TreeSet<>(scenicIds)) {
            UserScenic us = new UserScenic();
            us.setUserId(userId);
            us.setScenicId(s);
            userScenicMapper.insert(us);
        }
        authz.bumpVersion();
        auditAdmin("设置景区范围", userId, Map.of("scenicIds", new TreeSet<>(scenicIds).toString()));
        return new TreeSet<>(scenicIds);
    }

    @Transactional
    public SecurityLevelSetting setSecurityLevel(RoleDtos.SecurityLevelRequest req) {
        guard.require(PdsPermissions.GRANT_MANAGE);
        EffectivePermissions mine = guard.permissions();
        if (!mine.has(PdsPermissions.GRANT_ANY) && req.maxLevel() > mine.maxSecurityLevel()) {
            throw new BizException(ErrorCode.GRANT_EXCEEDS_OWN, "不能授予高于您自身的密级");
        }
        appService.subjectScenic(req.subjectType(), req.subjectId());
        SecurityLevelSetting s = levelMapper.selectOne(new LambdaQueryWrapper<SecurityLevelSetting>()
                .eq(SecurityLevelSetting::getSubjectType, req.subjectType()).eq(SecurityLevelSetting::getSubjectId, req.subjectId()));
        boolean isNew = s == null;
        if (isNew) {
            s = new SecurityLevelSetting();
            s.setId(Ids.next("sl"));
            s.setSubjectType(req.subjectType());
            s.setSubjectId(req.subjectId());
        }
        s.setIncludeChildren("ORG".equals(req.subjectType()) && Boolean.TRUE.equals(req.includeChildren()));
        s.setMaxLevel(req.maxLevel());
        if (isNew) {
            levelMapper.insert(s);
        } else {
            levelMapper.updateById(s);
        }
        authz.bumpVersion();
        auditAdmin("设置密级", req.subjectType() + ":" + req.subjectId(), Map.of("maxLevel", req.maxLevel()));
        return s;
    }

    // ------------------------------------------------------------------ 内部

    /** 被操作人员须在操作人该权限的数据范围内（本人除外）。 */
    private void assertManageable(SysUser target, String permCode) {
        ResolvedScope scope = guard.require(permCode);
        if (scope.isAll() || target.getId().equals(guard.actor().userId())) {
            return;
        }
        Set<String> orgIds = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, target.getId()))
                .stream().map(UserOrg::getOrgId).collect(Collectors.toSet());
        if (orgIds.isEmpty() || orgMapper.selectByIds(orgIds).stream().noneMatch(o -> Guard.covers(scope, o))) {
            throw new BizException(ErrorCode.SCENIC_DENIED, "人员【" + target.getName() + "】不在您的管理范围内");
        }
    }

    private void auditAdmin(String action, String targetId, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("user", targetId).detail(detail));
    }
}
