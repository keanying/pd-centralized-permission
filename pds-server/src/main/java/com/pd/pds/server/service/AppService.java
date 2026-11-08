package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermResource;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.ResourceGrant;
import com.pd.pds.server.entity.ResourceType;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.entity.UserPerm;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.PermResourceMapper;
import com.pd.pds.server.mapper.PositionMapper;
import com.pd.pds.server.mapper.ResourceGrantMapper;
import com.pd.pds.server.mapper.ResourceTypeMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserGroupMapper;
import com.pd.pds.server.mapper.UserPermMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.AppDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 接入应用：权限清单注册、资源实例注册、资源实例授权、OAuth 客户端。
 */
@Service
public class AppService {

    public static final String SCOPE_MANIFEST = "pds.manifest";
    public static final String SCOPE_RESOURCE = "pds.resource";

    private final AppInfoMapper appMapper;
    private final PermPointMapper pointMapper;
    private final ResourceTypeMapper typeMapper;
    private final PermResourceMapper resourceMapper;
    private final ResourceGrantMapper grantMapper;
    private final UserPermMapper userPermMapper;
    private final SysUserMapper userMapper;
    private final OrgUnitMapper orgMapper;
    private final PositionMapper positionMapper;
    private final UserGroupMapper groupMapper;
    private final ClientService clientService;
    private final SubjectNames subjectNames;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;
    private final PermNodeTypeService nodeTypes;

    public AppService(AppInfoMapper appMapper, PermPointMapper pointMapper, ResourceTypeMapper typeMapper,
                      PermResourceMapper resourceMapper, ResourceGrantMapper grantMapper, UserPermMapper userPermMapper,
                      SysUserMapper userMapper, OrgUnitMapper orgMapper, PositionMapper positionMapper,
                      UserGroupMapper groupMapper, ClientService clientService, SubjectNames subjectNames, Guard guard,
                      AuthzService authz, AuditService audit, PermNodeTypeService nodeTypes) {
        this.nodeTypes = nodeTypes;
        this.subjectNames = subjectNames;
        this.appMapper = appMapper;
        this.pointMapper = pointMapper;
        this.typeMapper = typeMapper;
        this.resourceMapper = resourceMapper;
        this.grantMapper = grantMapper;
        this.userPermMapper = userPermMapper;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.positionMapper = positionMapper;
        this.groupMapper = groupMapper;
        this.clientService = clientService;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 应用与清单

    public List<AppDtos.AppView> apps() {
        guard.require(PdsPermissions.ROLE_VIEW);
        Map<String, Long> perms = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().select(PermPoint::getAppCode)
                .eq(PermPoint::getStatus, 1)).stream().collect(Collectors.groupingBy(PermPoint::getAppCode, Collectors.counting()));
        Map<String, Long> types = typeMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.groupingBy(ResourceType::getAppCode, Collectors.counting()));
        Map<String, Long> clientCounts = clientService.countByApp();
        return appMapper.selectList(new LambdaQueryWrapper<AppInfo>().orderByAsc(AppInfo::getCode)).stream()
                .map(a -> new AppDtos.AppView(a.getCode(), a.getName(), a.getDescription(), a.getCategory(),
                        a.getCategory() == null ? "未分类" : AppCategories.ALL.getOrDefault(a.getCategory(), a.getCategory()),
                        Boolean.TRUE.equals(a.getAccessControl()), a.getManifestVersion(),
                        perms.getOrDefault(a.getCode(), 0L), types.getOrDefault(a.getCode(), 0L),
                        clientCounts.getOrDefault(a.getCode(), 0L)))
                .toList();
    }

    public List<ResourceType> resourceTypes(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        return typeMapper.selectList(new LambdaQueryWrapper<ResourceType>()
                .eq(app != null, ResourceType::getAppCode, app).orderByAsc(ResourceType::getTypeCode));
    }

    public List<AppDtos.ClientView> clients(String app) {
        guard.require(PdsPermissions.APP_MANAGE);
        return clientService.listByApp(app);
    }

    /** 资源实例列表（按类型、名称筛选），用于资源授权页。 */
    public com.pd.pds.common.PageResult<AppDtos.ResourceView> resources(String type, String keyword, long page, long size) {
        guard.require(PdsPermissions.ROLE_VIEW);
        LambdaQueryWrapper<PermResource> w = new LambdaQueryWrapper<PermResource>()
                .eq(type != null && !type.isBlank(), PermResource::getTypeCode, type)
                .and(keyword != null && !keyword.isBlank(), q -> q.like(PermResource::getName, keyword).or().like(PermResource::getRefId, keyword))
                .orderByAsc(PermResource::getTypeCode).orderByAsc(PermResource::getName);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<PermResource> p =
                resourceMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), w);
        Set<String> ids = p.getRecords().stream().map(PermResource::getId).collect(Collectors.toSet());
        Map<String, Long> grantCounts = ids.isEmpty() ? Map.of() : grantMapper.selectList(new LambdaQueryWrapper<ResourceGrant>()
                .select(ResourceGrant::getResourceId).in(ResourceGrant::getResourceId, ids)).stream()
                .collect(Collectors.groupingBy(ResourceGrant::getResourceId, Collectors.counting()));
        List<AppDtos.ResourceView> items = p.getRecords().stream().map(r -> new AppDtos.ResourceView(r.getId(), r.getTypeCode(),
                r.getRefId(), r.getName(), r.getScenicId(), scenicName(r.getScenicId()), r.getOwnerId(),
                grantCounts.getOrDefault(r.getId(), 0L), r.getUpdatedAt())).toList();
        return new com.pd.pds.common.PageResult<>(p.getTotal(), page, size, items);
    }

    private String scenicName(String scenicId) {
        if (scenicId == null) {
            return null;
        }
        if ("GROUP".equals(scenicId)) {
            return "集团共享";
        }
        OrgUnit o = orgMapper.selectById(scenicId);
        return o == null ? scenicId : o.getName();
    }

    public List<PermPoint> permissions(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        return pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getAppCode, app).orderByAsc(PermPoint::getCode));
    }

    /** 管理员（pds:app:manage）或该应用自己的客户端（scope=pds.manifest）可以注册清单。 */
    @Transactional
    public AppDtos.ManifestResult registerManifest(String app, AppDtos.Manifest m) {
        requireAppCaller(app, SCOPE_MANIFEST);
        return applyManifest(app, m);
    }

    /** 不做调用方校验的内部方法（启动注册 PDS 自身清单时使用）。 */
    @Transactional
    public AppDtos.ManifestResult applyManifest(String app, AppDtos.Manifest m) {
        if (!app.matches("[a-z][a-z0-9_]{1,31}")) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "应用编码只能为小写字母开头的字母数字组合");
        }
        String prefix = app + ":";
        Instant now = Instant.now();
        AppInfo info = appMapper.selectById(app);
        if (info == null) {
            info = new AppInfo();
            info.setCode(app);
            info.setCreatedAt(now);
            info.setStatus(1);
        }
        info.setName(m.name());
        info.setDescription(m.description());
        info.setManifestVersion(m.version());
        if (m.category() != null) {
            if (!AppCategories.ALL.containsKey(m.category())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的应用分类：" + m.category() + "，可选 " + AppCategories.ALL.keySet());
            }
            info.setCategory(m.category());
        }
        // 应用准入默认开启（PDS 自身由 pds:console:access 控制）
        info.setAccessControl(m.accessControl() == null || m.accessControl());
        info.setUpdatedAt(now);
        if (appMapper.selectById(app) == null) {
            appMapper.insert(info);
        } else {
            appMapper.updateById(info);
        }

        Map<String, PermPoint> existing = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getAppCode, app))
                .stream().collect(Collectors.toMap(PermPoint::getCode, p -> p));
        int added = 0;
        int updated = 0;
        Set<String> seen = new HashSet<>();
        List<AppDtos.ManifestPermission> permissions = new java.util.ArrayList<>(m.permissions());
        String accessCode = accessPermission(app);
        if (permissions.stream().noneMatch(p -> p.code().equals(accessCode))) {
            permissions.add(0, new AppDtos.ManifestPermission(accessCode, "登录进入" + m.name(), "MENU", "LOW",
                    "应用准入：单点登录进入该应用的前提"));
        }
        int sort = 0;
        for (AppDtos.ManifestPermission p : permissions) {
            if (!p.code().startsWith(prefix)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "权限点编码必须以「" + prefix + "」开头：" + p.code());
            }
            if (!seen.add(p.code())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "权限点重复：" + p.code());
            }
            nodeTypes.ensure(p.type());
            sort += 10;
            PermPoint pp = existing.get(p.code());
            boolean isNew = pp == null;
            if (isNew) {
                pp = new PermPoint();
                pp.setCode(p.code());
                pp.setId(nodeTypes.newNodeId());
                pp.setAppCode(app);
                pp.setSource("MANIFEST");
                pp.setGrantable(true);
                pp.setNeedMfa(false);
                pp.setDataScopeEnabled(true);
                pp.setCustomized(false);
                pp.setSort(sort);
                pp.setCreatedAt(now);
                existing.put(p.code(), pp);
            }
            // 后台改过的节点（customized），清单不再覆盖名称、类型、风险与状态
            if (isNew || !Boolean.TRUE.equals(pp.getCustomized())) {
                pp.setName(p.name());
                pp.setType(p.type());
                pp.setRisk(p.risk() == null ? "LOW" : p.risk().toUpperCase());
                pp.setDescription(p.description());
                pp.setStatus(1);
                if (p.parent() != null && existing.containsKey(p.parent())) {
                    pp.setParentId(existing.get(p.parent()).getId());
                }
            }
            pp.setManifestVersion(m.version());
            pp.setUpdatedAt(now);
            if (isNew) {
                pointMapper.insert(pp);
                added++;
            } else {
                pointMapper.updateById(pp);
                updated++;
            }
        }
        int disabled = 0;
        for (PermPoint old : existing.values()) {
            // 只停用来自清单、且未在后台改过的节点；后台维护的节点不受清单影响
            if (!seen.contains(old.getCode()) && old.getStatus() == 1 && "MANIFEST".equals(old.getSource())
                    && !Boolean.TRUE.equals(old.getCustomized())) {
                old.setStatus(0);
                old.setUpdatedAt(now);
                pointMapper.updateById(old);
                disabled++;
            }
        }
        if (m.resourceTypes() != null) {
            for (AppDtos.ManifestResourceType t : m.resourceTypes()) {
                if (!t.type().startsWith(prefix)) {
                    throw new BizException(ErrorCode.VALIDATION_FAILED, "资源类型必须以「" + prefix + "」开头：" + t.type());
                }
                ResourceType rt = new ResourceType();
                rt.setTypeCode(t.type());
                rt.setAppCode(app);
                rt.setName(t.name());
                rt.setActions(t.actions() == null || t.actions().isEmpty() ? "READ,WRITE,ADMIN" : String.join(",", t.actions()));
                if (typeMapper.selectById(t.type()) == null) {
                    typeMapper.insert(rt);
                } else {
                    typeMapper.updateById(rt);
                }
            }
        }
        authz.bumpVersion();
        return new AppDtos.ManifestResult(app, m.version(), added, updated, disabled);
    }

    /** 在权限树中新增应用（自动生成应用准入节点），之后可在权限树中维护节点。 */
    @Transactional
    public AppDtos.ManifestResult createApp(com.pd.pds.server.web.dto.PermTreeDtos.CreateApp req) {
        guard.require(PdsPermissions.APP_MANAGE);
        if (appMapper.selectById(req.code()) != null) {
            throw BizException.duplicate("应用编码【" + req.code() + "】");
        }
        AppDtos.ManifestResult r = applyManifest(req.code(), new AppDtos.Manifest(req.name(), req.description(), "manual",
                List.of(), List.of(), req.category(), req.accessControl()));
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", "新增应用", "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("app", req.code()).detail(Map.of("name", req.name())));
        return r;
    }

    /** 应用准入权限点：{app}:access（PDS 自身为 pds:console:access）。 */
    public static String accessPermission(String app) {
        return PdsPermissions.APP.equals(app) ? PdsPermissions.CONSOLE : app + ":access";
    }

    // ------------------------------------------------------------------ 资源实例

    @Transactional
    public PermResource registerResource(AppDtos.RegisterResource req) {
        ResourceType type = typeMapper.selectById(req.type());
        if (type == null) {
            throw BizException.notFound("资源类型【" + req.type() + "】（请先在权限清单中声明）");
        }
        requireAppCaller(type.getAppCode(), SCOPE_RESOURCE);
        Instant now = Instant.now();
        PermResource r = findResource(req.type(), req.refId());
        boolean isNew = r == null;
        if (isNew) {
            r = new PermResource();
            r.setId(Ids.next("res"));
            r.setTypeCode(req.type());
            r.setRefId(req.refId());
            r.setAppCode(type.getAppCode());
            r.setCreatedAt(now);
        }
        r.setName(req.name());
        r.setScenicId(req.scenicId());
        r.setOwnerId(req.ownerId());
        r.setUpdatedAt(now);
        if (isNew) {
            resourceMapper.insert(r);
        } else {
            resourceMapper.updateById(r);
            authz.bumpVersion();
        }
        auditAdmin(isNew ? "注册资源" : "更新资源", r.getTypeCode() + ":" + r.getRefId(), Map.of("name", r.getName(), "scenicId", r.getScenicId()));
        return r;
    }

    @Transactional
    public void deleteResource(String type, String refId) {
        PermResource r = requireResource(type, refId);
        requireAppCaller(r.getAppCode(), SCOPE_RESOURCE);
        grantMapper.delete(new LambdaQueryWrapper<ResourceGrant>().eq(ResourceGrant::getResourceId, r.getId()));
        userPermMapper.delete(new LambdaQueryWrapper<UserPerm>().eq(UserPerm::getResourceId, r.getId()));
        resourceMapper.deleteById(r.getId());
        authz.bumpVersion();
        auditAdmin("注销资源", type + ":" + refId, Map.of("name", r.getName()));
    }

    public PermResource findResource(String type, String refId) {
        return resourceMapper.selectOne(new LambdaQueryWrapper<PermResource>()
                .eq(PermResource::getTypeCode, type).eq(PermResource::getRefId, refId));
    }

    public PermResource requireResource(String type, String refId) {
        PermResource r = findResource(type, refId);
        if (r == null) {
            throw BizException.notFound("资源【" + type + ":" + refId + "】");
        }
        return r;
    }

    // ------------------------------------------------------------------ 资源授权

    public List<AppDtos.GrantView> grants(String type, String refId) {
        PermResource r = requireResource(type, refId);
        requireGrantOperator(r, ResourceAction.READ);
        return grantMapper.selectList(new LambdaQueryWrapper<ResourceGrant>().eq(ResourceGrant::getResourceId, r.getId())
                        .orderByAsc(ResourceGrant::getCreatedAt)).stream()
                .map(g -> new AppDtos.GrantView(g.getId(), g.getSubjectType(), g.getSubjectId(),
                        subjectNames.nameOf(g.getSubjectType(), g.getSubjectId()), Boolean.TRUE.equals(g.getIncludeChildren()),
                        g.getAction(), g.getEffect(), g.getValidFrom(), g.getValidTo(), g.getReason(), g.getGrantedBy(),
                        subjectNames.userName(g.getGrantedBy()), g.getCreatedAt()))
                .toList();
    }

    /**
     * 资源授权。操作人须满足其一：拥有 pds:grant:any；或拥有 pds:grant:manage 且自身对该资源有被授予的动作（不越级）；
     * 或是该资源的管理者（ADMIN）。景区资源只能授权给本景区的主体（pds:grant:any 除外）。
     */
    @Transactional
    public ResourceGrant addGrant(String type, String refId, AppDtos.GrantRequest req) {
        PermResource r = requireResource(type, refId);
        ResourceAction action = ResourceAction.of(req.action());
        boolean any = requireGrantOperator(r, action);
        String subjectScenic = subjectScenic(req.subjectType(), req.subjectId());
        if (!any && !"GROUP".equals(r.getScenicId()) && subjectScenic != null && !subjectScenic.equals(r.getScenicId())) {
            throw new BizException(ErrorCode.SCENIC_DENIED, "景区资源不能授权给其他景区的主体");
        }
        if (req.validFrom() != null && req.validTo() != null && !req.validTo().isAfter(req.validFrom())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期结束时间必须晚于开始时间");
        }
        CurrentActor a = guard.requireUser();
        ResourceGrant g = new ResourceGrant();
        g.setId(Ids.next("rg"));
        g.setResourceId(r.getId());
        g.setSubjectType(req.subjectType());
        g.setSubjectId(req.subjectId());
        g.setIncludeChildren("ORG".equals(req.subjectType()) && Boolean.TRUE.equals(req.includeChildren()));
        g.setAction(action.name());
        g.setEffect(req.effect());
        g.setValidFrom(req.validFrom());
        g.setValidTo(req.validTo());
        g.setReason(req.reason());
        g.setGrantedBy(a.userId());
        g.setCreatedAt(Instant.now());
        grantMapper.insert(g);
        authz.bumpVersion();
        auditAdmin("资源授权", type + ":" + refId, Map.of("subject", req.subjectType() + ":" + req.subjectId(),
                "action", action.name(), "effect", req.effect()));
        return g;
    }

    @Transactional
    public void removeGrant(String type, String refId, String grantId) {
        PermResource r = requireResource(type, refId);
        ResourceGrant g = grantMapper.selectById(grantId);
        if (g == null || !g.getResourceId().equals(r.getId())) {
            throw BizException.notFound("授权记录");
        }
        requireGrantOperator(r, ResourceAction.of(g.getAction()));
        grantMapper.deleteById(grantId);
        authz.bumpVersion();
        auditAdmin("撤销资源授权", type + ":" + refId, Map.of("grantId", grantId));
    }

    /** 修改资源授权的有效期 / 原因。 */
    @Transactional
    public void updateGrantValidity(String grantId, Instant validTo, boolean clear, String reason) {
        ResourceGrant g = grantMapper.selectById(grantId);
        if (g == null) {
            throw BizException.notFound("授权记录");
        }
        PermResource r = resourceMapper.selectById(g.getResourceId());
        requireGrantOperator(r, ResourceAction.of(g.getAction()));
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ResourceGrant> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ResourceGrant>().eq(ResourceGrant::getId, grantId);
        if (clear) {
            w.set(ResourceGrant::getValidTo, null);
        } else if (validTo != null) {
            if (!validTo.isAfter(Instant.now())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "有效期必须晚于当前时间");
            }
            w.set(ResourceGrant::getValidTo, validTo);
        }
        if (reason != null && !reason.isBlank()) {
            w.set(ResourceGrant::getReason, reason);
        }
        grantMapper.update(null, w);
        authz.bumpVersion();
        auditAdmin("修改资源授权有效期", r.getTypeCode() + ":" + r.getRefId(), Map.of("grantId", grantId,
                "validTo", clear ? "长期" : String.valueOf(validTo)));
    }

    /** @return 操作人是否拥有 pds:grant:any */
    private boolean requireGrantOperator(PermResource r, ResourceAction action) {
        CurrentActor a = guard.requireUser();
        EffectivePermissions eff = authz.effective(a.userId());
        if (eff.has(PdsPermissions.GRANT_ANY)) {
            return true;
        }
        ResourceKey key = new ResourceKey(r.getTypeCode(), r.getRefId());
        boolean ok = eff.canAccess(key, ResourceAction.ADMIN)
                || (eff.has(PdsPermissions.GRANT_MANAGE) && eff.canAccess(key, action));
        if (!ok) {
            throw new BizException(ErrorCode.GRANT_EXCEEDS_OWN, "您没有该资源的" + action.label() + "权限，不能对其授权");
        }
        return false;
    }

    /** 授权对象所属景区（无法确定时返回 null）。 */
    public String subjectScenic(String subjectType, String subjectId) {
        return switch (subjectType) {
            case "USER" -> {
                SysUser u = userMapper.selectById(subjectId);
                if (u == null) {
                    throw BizException.notFound("人员");
                }
                yield u.getScenicId();
            }
            case "ORG" -> {
                OrgUnit o = orgMapper.selectById(subjectId);
                if (o == null) {
                    throw BizException.notFound("组织");
                }
                yield o.getScenicId();
            }
            case "POSITION" -> {
                Position p = positionMapper.selectById(subjectId);
                if (p == null) {
                    throw BizException.notFound("岗位");
                }
                yield p.getScenicId();
            }
            case "GROUP" -> {
                UserGroup g = groupMapper.selectById(subjectId);
                if (g == null) {
                    throw BizException.notFound("用户组");
                }
                yield g.getScenicId();
            }
            default -> throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的授权对象类型：" + subjectType);
        };
    }

    // ------------------------------------------------------------------ 客户端

    @Transactional
    public AppDtos.ClientCredential createClient(String app, AppDtos.CreateClient req) {
        guard.require(PdsPermissions.APP_MANAGE);
        String secret = clientService.create(new ClientService.Spec(req.clientId(), null, req.name(), app, req.grantTypes(),
                req.redirectUris(), req.postLogoutRedirectUris(), req.scopes(), req.requirePkce() == null || req.requirePkce(),
                req.accessTokenMinutes() == null ? null : Duration.ofMinutes(req.accessTokenMinutes()),
                req.refreshTokenHours() == null ? null : Duration.ofHours(req.refreshTokenHours()),
                Boolean.TRUE.equals(req.publicClient())));
        auditAdmin("创建客户端", req.clientId(), Map.of("app", app, "grantTypes", req.grantTypes().toString()));
        return new AppDtos.ClientCredential(req.clientId(), secret, app);
    }

    // ------------------------------------------------------------------ 内部

    private void requireAppCaller(String app, String scope) {
        CurrentActor a = guard.actor();
        if (a.isUser()) {
            guard.require(PdsPermissions.APP_MANAGE);
            return;
        }
        if (!a.hasScope(scope) || !app.equals(clientService.appOf(a.clientId()))) {
            throw new BizException(ErrorCode.FORBIDDEN, "客户端无权操作应用【" + app + "】（需要 " + scope + " 且属于该应用）");
        }
    }

    private void auditAdmin(String action, String target, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("resource", target).detail(detail));
    }
}
