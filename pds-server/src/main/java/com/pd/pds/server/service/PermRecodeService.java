package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.PermCodeAlias;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.entity.UserPerm;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.PermCodeAliasMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.mapper.UserPermMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 修改已发布节点的权限编码：节点（可连同下级的编码前缀）改名，授权、角色权限、人员明细中的引用一并迁移；
 * 可保留原编码作为兼容别名，已接入的系统继续按原编码鉴权，迁移完成后再移除别名。
 * 修改立即生效（不进草稿），并向该应用推送 perm_node.recoded 事件。PDS 自身的权限编码不能修改。
 */
@Service
public class PermRecodeService {

    public record Recode(String newCode, Boolean includeChildren, Boolean keepAlias, String reason) {
    }

    public record CodeChange(String nodeId, String name, String oldCode, String newCode) {
    }

    public record Impact(List<CodeChange> changes, long grants, long rolePerms, long userPerms, long aliases,
                         List<String> unaffectedChildren) {
    }

    public record AliasView(String oldCode, String newCode, String reason, String createdBy, Instant createdAt) {
    }

    private static final String CODE_PATTERN = "[a-z][a-z0-9_]*(:[a-z0-9_]+)+";

    private final PermPointMapper pointMapper;
    private final AuthGrantMapper grantMapper;
    private final RolePermMapper rolePermMapper;
    private final UserPermMapper userPermMapper;
    private final PermCodeAliasMapper aliasMapper;
    private final PermTreeService tree;
    private final SubjectNames subjectNames;
    private final DistributionService distribution;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public PermRecodeService(PermPointMapper pointMapper, AuthGrantMapper grantMapper, RolePermMapper rolePermMapper,
                             UserPermMapper userPermMapper, PermCodeAliasMapper aliasMapper, PermTreeService tree,
                             SubjectNames subjectNames, DistributionService distribution, Guard guard, AuthzService authz,
                             AuditService audit) {
        this.pointMapper = pointMapper;
        this.grantMapper = grantMapper;
        this.rolePermMapper = rolePermMapper;
        this.userPermMapper = userPermMapper;
        this.aliasMapper = aliasMapper;
        this.tree = tree;
        this.subjectNames = subjectNames;
        this.distribution = distribution;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    /** 预览：会改哪些编码、影响多少条引用。newCode 为空时只统计本节点与下级。 */
    public Impact impact(String id, Recode req) {
        guard.require(PdsPermissions.APP_MANAGE);
        PermPoint p = tree.requireNode(id);
        String newCode = StringUtils.hasText(req.newCode()) ? req.newCode().trim() : p.getCode();
        return compute(p, newCode, !Boolean.FALSE.equals(req.includeChildren()));
    }

    private Impact compute(PermPoint p, String newCode, boolean includeChildren) {
        String old = p.getCode();
        List<CodeChange> changes = new ArrayList<>();
        changes.add(new CodeChange(p.getId(), p.getName(), old, newCode));
        List<String> unaffected = new ArrayList<>();
        if (includeChildren) {
            for (PermPoint c : descendants(p)) {
                if (c.getCode().startsWith(old + ":")) {
                    changes.add(new CodeChange(c.getId(), c.getName(), c.getCode(), newCode + c.getCode().substring(old.length())));
                } else {
                    unaffected.add(c.getName() + "（" + c.getCode() + "）");
                }
            }
        }
        List<String> olds = changes.stream().map(CodeChange::oldCode).toList();
        return new Impact(changes,
                grantMapper.selectCount(new LambdaQueryWrapper<AuthGrant>().in(AuthGrant::getPermCode, olds)),
                rolePermMapper.selectCount(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getPermCode, olds)),
                userPermMapper.selectCount(new LambdaQueryWrapper<UserPerm>().in(UserPerm::getPermCode, olds)),
                aliasMapper.selectCount(new LambdaQueryWrapper<PermCodeAlias>().in(PermCodeAlias::getNewCode, olds)),
                unaffected);
    }

    @Transactional
    public Impact recode(String id, Recode req) {
        guard.require(PdsPermissions.APP_MANAGE);
        CurrentActor actor = guard.requireUser();
        PermPoint p = tree.requireNode(id);
        if (PdsPermissions.APP.equals(p.getAppCode())) {
            throw new BizException(ErrorCode.CONFLICT, "PDS 自身的权限编码由系统使用，不能修改");
        }
        if (p.getStatus() != null && p.getStatus() == 2) {
            throw new BizException(ErrorCode.CONFLICT, "草稿节点请直接在基本信息中修改编码");
        }
        String newCode = req.newCode() == null ? "" : req.newCode().trim();
        if (!newCode.startsWith(p.getAppCode() + ":") || !newCode.matches(CODE_PATTERN)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "权限编码须以「" + p.getAppCode() + ":」开头，只能包含小写字母、数字、下划线和冒号");
        }
        if (newCode.equals(p.getCode())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "新编码与原编码相同");
        }
        Impact impact = compute(p, newCode, !Boolean.FALSE.equals(req.includeChildren()));
        Set<String> renamed = new java.util.HashSet<>(impact.changes().stream().map(CodeChange::oldCode).toList());
        for (CodeChange c : impact.changes()) {
            if (pointMapper.selectById(c.newCode()) != null && !renamed.contains(c.newCode())) {
                throw BizException.duplicate("权限编码【" + c.newCode() + "】");
            }
            PermCodeAlias a = aliasMapper.selectById(c.newCode());
            if (a != null && !renamed.contains(a.getNewCode())) {
                throw new BizException(ErrorCode.CONFLICT, "【" + c.newCode() + "】是节点【" + a.getNewCode() + "】的兼容别名，不能使用");
            }
        }
        boolean keepAlias = !Boolean.FALSE.equals(req.keepAlias());
        Instant now = Instant.now();
        // 先整体移出旧编码，再写入新编码（避免改名链中新旧编码互相冲突）
        Map<String, PermPoint> rows = new LinkedHashMap<>();
        for (CodeChange c : impact.changes()) {
            rows.put(c.oldCode(), pointMapper.selectById(c.oldCode()));
            pointMapper.deleteById(c.oldCode());
        }
        Map<String, String> mapping = new HashMap<>();
        for (CodeChange c : impact.changes()) {
            PermPoint row = rows.get(c.oldCode());
            row.setCode(c.newCode());
            row.setCustomized(true);
            row.setUpdatedAt(now);
            pointMapper.insert(row);
            mapping.put(c.oldCode(), c.newCode());
            grantMapper.update(null, new LambdaUpdateWrapper<AuthGrant>().eq(AuthGrant::getPermCode, c.oldCode()).set(AuthGrant::getPermCode, c.newCode()));
            rolePermMapper.update(null, new LambdaUpdateWrapper<RolePerm>().eq(RolePerm::getPermCode, c.oldCode()).set(RolePerm::getPermCode, c.newCode()));
            userPermMapper.update(null, new LambdaUpdateWrapper<UserPerm>().eq(UserPerm::getPermCode, c.oldCode()).set(UserPerm::getPermCode, c.newCode()));
            // 已有别名跟随指向新编码
            aliasMapper.update(null, new LambdaUpdateWrapper<PermCodeAlias>().eq(PermCodeAlias::getNewCode, c.oldCode()).set(PermCodeAlias::getNewCode, c.newCode()));
            // 改回曾用编码时，去掉该别名
            aliasMapper.deleteById(c.newCode());
            boolean published = row.getStatus() == null || row.getStatus() != 2;
            if (keepAlias && published) {
                PermCodeAlias a = new PermCodeAlias();
                a.setOldCode(c.oldCode());
                a.setNewCode(c.newCode());
                a.setAppCode(p.getAppCode());
                a.setReason(req.reason());
                a.setCreatedBy(actor.userId());
                a.setCreatedAt(now);
                aliasMapper.insert(a);
            }
        }
        authz.bumpVersion();
        List<Map<String, String>> changes = impact.changes().stream()
                .map(c -> Map.of("oldCode", c.oldCode(), "newCode", c.newCode(), "name", c.name())).toList();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", "修改权限编码", "SUCCESS")
                .actor(actor.auditId(), actor.userName(), actor.ip()).target("perm_node", p.getId())
                .detail(Map.of("changes", mapping.toString(), "aliasKept", keepAlias, "reason", req.reason() == null ? "" : req.reason(),
                        "grants", impact.grants(), "rolePerms", impact.rolePerms())));
        distribution.emit("perm_node.recoded", "perm_tree", p.getAppCode(), Set.of(p.getAppCode()),
                Map.of("app", p.getAppCode(), "changes", changes, "aliasKept", keepAlias));
        return impact;
    }

    public List<AliasView> aliases(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        return aliasMapper.selectList(new LambdaQueryWrapper<PermCodeAlias>().eq(PermCodeAlias::getAppCode, app)
                        .orderByDesc(PermCodeAlias::getCreatedAt)).stream()
                .map(a -> new AliasView(a.getOldCode(), a.getNewCode(), a.getReason(), subjectNames.userName(a.getCreatedBy()), a.getCreatedAt()))
                .toList();
    }

    /** 移除兼容别名：此后按原编码鉴权的系统将判定为无权限。 */
    @Transactional
    public void removeAlias(String oldCode) {
        guard.require(PdsPermissions.APP_MANAGE);
        PermCodeAlias a = aliasMapper.selectById(oldCode);
        if (a == null) {
            throw BizException.notFound("兼容编码【" + oldCode + "】");
        }
        aliasMapper.deleteById(oldCode);
        authz.bumpVersion();
        CurrentActor actor = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", "移除兼容编码", "SUCCESS")
                .actor(actor.auditId(), actor.userName(), actor.ip()).target("perm_node", a.getNewCode())
                .detail(Map.of("oldCode", oldCode, "newCode", a.getNewCode())));
    }

    private List<PermPoint> descendants(PermPoint p) {
        List<PermPoint> all = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getAppCode, p.getAppCode()));
        Map<String, List<PermPoint>> children = new HashMap<>();
        for (PermPoint n : all) {
            if (n.getParentId() != null) {
                children.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
            }
        }
        List<PermPoint> out = new ArrayList<>();
        java.util.ArrayDeque<PermPoint> stack = new java.util.ArrayDeque<>(children.getOrDefault(p.getId(), List.of()));
        Set<String> seen = new java.util.HashSet<>();
        while (!stack.isEmpty()) {
            PermPoint c = stack.pop();
            if (seen.add(c.getId())) {
                out.add(c);
                stack.addAll(children.getOrDefault(c.getId(), List.of()));
            }
        }
        return out;
    }
}
