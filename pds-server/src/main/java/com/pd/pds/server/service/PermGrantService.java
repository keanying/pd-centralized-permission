package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.ResolvedScope;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 权限节点授权（auth_grant）：人员、组织（可向下继承）、岗位、用户组被允许或禁止某权限节点。
 *
 * <p>约束：节点须已发布且可授权；允许授权不得超出操作人自身拥有的权限（pds:grant:any 除外）；
 * 人员与组织须在操作人 pds:grant:manage 的数据范围内。
 */
@Service
public class PermGrantService {

    public record Spec(String subjectType, String subjectId, boolean includeChildren, String permCode, String effect,
                       String dataScope, Set<String> scopeValues, Instant validFrom, Instant validTo, String reason,
                       String source) {
    }

    private final AuthGrantMapper grantMapper;
    private final PermPointMapper pointMapper;
    private final OrgUnitMapper orgMapper;
    private final UserOrgMapper userOrgMapper;
    private final AppService appService;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    private SubjectNames subjectNames;

    public PermGrantService(AuthGrantMapper grantMapper, PermPointMapper pointMapper, OrgUnitMapper orgMapper,
                            UserOrgMapper userOrgMapper, AppService appService, Guard guard, AuthzService authz,
                            AuditService audit) {
        this.grantMapper = grantMapper;
        this.pointMapper = pointMapper;
        this.orgMapper = orgMapper;
        this.userOrgMapper = userOrgMapper;
        this.appService = appService;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    /** 校验并返回将写入的记录（不落库），供影响分析与批量授权共用。 */
    public PermPoint check(Spec s) {
        ResolvedScope scope = guard.require(PdsPermissions.GRANT_MANAGE);
        if (!"ALLOW".equals(s.effect()) && !"DENY".equals(s.effect())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "效果只能是 ALLOW 或 DENY");
        }
        PermPoint p = pointMapper.selectById(s.permCode());
        if (p == null) {
            throw BizException.notFound("权限节点【" + s.permCode() + "】");
        }
        if (p.getStatus() == null || p.getStatus() != 1) {
            throw new BizException(ErrorCode.CONFLICT, "权限节点【" + p.getName() + "】尚未发布或已停用");
        }
        if ("ALLOW".equals(s.effect()) && Boolean.FALSE.equals(p.getGrantable())) {
            throw new BizException(ErrorCode.CONFLICT, "权限节点【" + p.getName() + "】不可授权");
        }
        if (s.validFrom() != null && s.validTo() != null && !s.validTo().isAfter(s.validFrom())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期结束时间必须晚于开始时间");
        }
        appService.subjectScenic(s.subjectType(), s.subjectId());
        assertInScope(scope, s.subjectType(), s.subjectId());
        EffectivePermissions mine = guard.permissions();
        if ("ALLOW".equals(s.effect()) && !mine.has(PdsPermissions.GRANT_ANY) && !mine.has(s.permCode())) {
            throw new BizException(ErrorCode.GRANT_EXCEEDS_OWN, "授予的权限超出了您自身拥有的权限：" + p.getName());
        }
        return p;
    }

    /** 新增授权；同一主体对同一节点已有相同效果的授权时，更新其有效期、数据范围与原因。 */
    @Transactional
    public AuthGrant create(Spec s) {
        PermPoint p = check(s);
        CurrentActor a = guard.requireUser();
        boolean children = "ORG".equals(s.subjectType()) && s.includeChildren();
        AuthGrant g = grantMapper.selectOne(new LambdaQueryWrapper<AuthGrant>()
                .eq(AuthGrant::getSubjectType, s.subjectType()).eq(AuthGrant::getSubjectId, s.subjectId())
                .eq(AuthGrant::getPermCode, s.permCode()).eq(AuthGrant::getEffect, s.effect())
                .eq(AuthGrant::getIncludeChildren, children).last("LIMIT 1"));
        Instant now = Instant.now();
        boolean isNew = g == null;
        if (isNew) {
            g = new AuthGrant();
            g.setId(Ids.next("ag"));
            g.setSubjectType(s.subjectType());
            g.setSubjectId(s.subjectId());
            g.setIncludeChildren(children);
            g.setPermCode(s.permCode());
            g.setEffect(s.effect());
            g.setCreatedAt(now);
        }
        g.setDataScope(StringUtils.hasText(s.dataScope()) ? s.dataScope() : null);
        g.setScopeValues(s.scopeValues() == null || s.scopeValues().isEmpty() ? null : String.join(",", new TreeSet<>(s.scopeValues())));
        g.setValidFrom(s.validFrom());
        g.setValidTo(s.validTo());
        g.setReason(s.reason());
        g.setSource(StringUtils.hasText(s.source()) ? s.source() : "MANUAL");
        g.setGrantedBy(a.userId());
        g.setUpdatedAt(now);
        if (isNew) {
            grantMapper.insert(g);
        } else {
            grantMapper.updateById(g);
            grantMapper.update(null, new LambdaUpdateWrapper<AuthGrant>().eq(AuthGrant::getId, g.getId())
                    .set(AuthGrant::getValidFrom, g.getValidFrom()).set(AuthGrant::getValidTo, g.getValidTo())
                    .set(AuthGrant::getDataScope, g.getDataScope()).set(AuthGrant::getScopeValues, g.getScopeValues()));
        }
        authz.bumpVersion();
        audit(("ALLOW".equals(s.effect()) ? "授予权限" : "禁止权限"), g, Map.of("perm", p.getCode(), "name", p.getName(),
                "subject", s.subjectType() + ":" + s.subjectId(), "reason", s.reason() == null ? "" : s.reason()));
        return g;
    }

    public AuthGrant require(String id) {
        AuthGrant g = grantMapper.selectById(id);
        if (g == null) {
            throw BizException.notFound("授权记录");
        }
        return g;
    }

    @Transactional
    public void updateValidity(String id, Instant validTo, boolean clearValidTo, String reason) {
        ResolvedScope scope = guard.require(PdsPermissions.GRANT_MANAGE);
        AuthGrant g = require(id);
        assertInScope(scope, g.getSubjectType(), g.getSubjectId());
        LambdaUpdateWrapper<AuthGrant> w = new LambdaUpdateWrapper<AuthGrant>().eq(AuthGrant::getId, id)
                .set(AuthGrant::getUpdatedAt, Instant.now());
        if (clearValidTo) {
            w.set(AuthGrant::getValidTo, null);
        } else if (validTo != null) {
            if (!validTo.isAfter(Instant.now())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期必须晚于当前时间");
            }
            w.set(AuthGrant::getValidTo, validTo);
        }
        if (StringUtils.hasText(reason)) {
            w.set(AuthGrant::getReason, reason);
        }
        grantMapper.update(null, w);
        authz.bumpVersion();
        audit("修改授权有效期", g, Map.of("validTo", clearValidTo ? "长期" : String.valueOf(validTo)));
    }

    @Transactional
    public void delete(String id) {
        ResolvedScope scope = guard.require(PdsPermissions.GRANT_MANAGE);
        AuthGrant g = require(id);
        assertInScope(scope, g.getSubjectType(), g.getSubjectId());
        grantMapper.deleteById(id);
        authz.bumpVersion();
        audit("撤销权限授权", g, Map.of("perm", g.getPermCode(), "subject", g.getSubjectType() + ":" + g.getSubjectId()));
    }

    public AuthGrant findForUser(String userId, String id) {
        AuthGrant g = grantMapper.selectById(id);
        return g != null && "USER".equals(g.getSubjectType()) && g.getSubjectId().equals(userId) ? g : null;
    }

    /** 人员明细页：此人名下的权限节点授权。 */
    public java.util.List<com.pd.pds.server.web.dto.RoleDtos.UserPermView> listForUser(String userId) {
        return grantMapper.selectList(new LambdaQueryWrapper<AuthGrant>().eq(AuthGrant::getSubjectType, "USER")
                        .eq(AuthGrant::getSubjectId, userId)).stream()
                .map(g -> {
                    PermPoint p = pointMapper.selectById(g.getPermCode());
                    return new com.pd.pds.server.web.dto.RoleDtos.UserPermView(g.getId(), g.getPermCode(),
                            p == null ? null : p.getName(), null, null, null, null, g.getEffect(), g.getDataScope(),
                            g.getScopeValues(), g.getValidFrom(), g.getValidTo(), g.getReason(),
                            subjectNames.userName(g.getGrantedBy()), g.getCreatedAt());
                })
                .toList();
    }

    /** 人员须有任职组织在范围内；组织本身须在范围内；岗位与用户组不做组织范围限制。 */
    public void assertInScope(ResolvedScope scope, String subjectType, String subjectId) {
        if (scope.isAll()) {
            return;
        }
        if ("USER".equals(subjectType)) {
            if (subjectId.equals(guard.actor().userId())) {
                return;
            }
            Set<String> orgIds = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getUserId, subjectId))
                    .stream().map(UserOrg::getOrgId).collect(Collectors.toSet());
            if (orgIds.isEmpty() || orgMapper.selectByIds(orgIds).stream().noneMatch(o -> Guard.covers(scope, o))) {
                SysUser u = authz.requireUser(subjectId);
                throw new BizException(ErrorCode.SCENIC_DENIED, "人员【" + u.getName() + "】不在您的管理范围内");
            }
        } else if ("ORG".equals(subjectType)) {
            OrgUnit o = orgMapper.selectById(subjectId);
            if (o != null) {
                guard.requireCovers(scope, o, PdsPermissions.GRANT_MANAGE);
            }
        }
    }

    private void audit(String action, AuthGrant g, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        String targetType = switch (g.getSubjectType()) {
            case "USER" -> "user";
            case "ORG" -> "org";
            case "POSITION" -> "position";
            default -> "group";
        };
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target(targetType, g.getSubjectId()).detail(detail));
    }
}
