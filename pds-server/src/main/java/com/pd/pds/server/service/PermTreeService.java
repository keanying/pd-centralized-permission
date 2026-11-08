package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.Decision;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.SourceRef;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.AuditEvent;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.PermNodeType;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermTreeVersion;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.PermTreeVersionMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.PermTreeDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import java.util.stream.Collectors;

/**
 * 权限树：节点新增、修改、移动、停用、删除均先进入草稿，发布后生效（应用清单同步的节点直接生效）。
 *
 * <ul>
 *   <li>新建节点：status=2（草稿），编码可改；发布后 status=1，编码锁定。</li>
 *   <li>已发布节点的修改：写入 pending（JSON），草稿视图中合并显示，发布时生效。</li>
 *   <li>发布：逐应用发布，生成版本号 vYYYY.M.n 与变更明细，递增授权版本号。</li>
 * </ul>
 */
@Service
public class PermTreeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DELETE_MARK = "_delete";
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("code", "权限编码");
        LABELS.put("name", "名称");
        LABELS.put("type", "节点类型");
        LABELS.put("parentId", "父节点");
        LABELS.put("risk", "风险等级");
        LABELS.put("securityLevel", "密级");
        LABELS.put("grantable", "可授权");
        LABELS.put("needMfa", "需二次验证");
        LABELS.put("dataScopeEnabled", "支持数据范围");
        LABELS.put("description", "描述");
        LABELS.put("sort", "排序");
        LABELS.put("status", "状态");
    }

    private final PermPointMapper pointMapper;
    private final PermTreeVersionMapper versionMapper;
    private final AppInfoMapper appMapper;
    private final RolePermMapper rolePermMapper;
    private final AuthGrantMapper grantMapper;
    private final SysUserMapper userMapper;
    private final OrgUnitMapper orgMapper;
    private final PermNodeTypeService types;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;
    private final JsonMapper json;
    private final DistributionService distribution;
    @org.springframework.beans.factory.annotation.Autowired
    private PermAliases aliases;

    public PermTreeService(PermPointMapper pointMapper, PermTreeVersionMapper versionMapper, AppInfoMapper appMapper,
                           RolePermMapper rolePermMapper, AuthGrantMapper grantMapper, SysUserMapper userMapper,
                           OrgUnitMapper orgMapper, PermNodeTypeService types, Guard guard, AuthzService authz,
                           AuditService audit, JsonMapper json, DistributionService distribution) {
        this.pointMapper = pointMapper;
        this.versionMapper = versionMapper;
        this.appMapper = appMapper;
        this.rolePermMapper = rolePermMapper;
        this.grantMapper = grantMapper;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.types = types;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
        this.json = json;
        this.distribution = distribution;
    }

    // ------------------------------------------------------------------ 查看

    public List<PermTreeDtos.TreeApp> apps() {
        guard.require(PdsPermissions.ROLE_VIEW);
        List<PermPoint> all = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>()
                .select(PermPoint::getAppCode, PermPoint::getStatus, PermPoint::getPending));
        Map<String, Long> counts = all.stream().collect(Collectors.groupingBy(PermPoint::getAppCode, Collectors.counting()));
        Map<String, Long> pending = all.stream().filter(PermTreeService::isPending)
                .collect(Collectors.groupingBy(PermPoint::getAppCode, Collectors.counting()));
        return appMapper.selectList(new LambdaQueryWrapper<AppInfo>().orderByAsc(AppInfo::getCode)).stream()
                .map(a -> new PermTreeDtos.TreeApp(a.getCode(), a.getName(), a.getCategory(),
                        counts.getOrDefault(a.getCode(), 0L), pending.getOrDefault(a.getCode(), 0L)))
                .toList();
    }

    /** 某应用的全部节点（草稿视图），按父子顺序排列由前端组装。 */
    public List<PermTreeDtos.NodeView> tree(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        Map<String, PermNodeType> typeMap = types.all();
        return nodesOf(app).stream().map(p -> view(p, typeMap))
                .sorted(Comparator.comparingInt(PermTreeDtos.NodeView::sort).thenComparing(PermTreeDtos.NodeView::code))
                .toList();
    }

    /** 全部应用的已发布、可授权节点（授权选择器使用）。 */
    public List<PermTreeDtos.NodeView> grantable(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        Map<String, PermNodeType> typeMap = types.all();
        return pointMapper.selectList(new LambdaQueryWrapper<PermPoint>()
                        .eq(StringUtils.hasText(app), PermPoint::getAppCode, app).eq(PermPoint::getStatus, 1))
                .stream().map(p -> view(p, typeMap))
                .sorted(Comparator.comparing(PermTreeDtos.NodeView::appCode).thenComparingInt(PermTreeDtos.NodeView::sort)
                        .thenComparing(PermTreeDtos.NodeView::code))
                .toList();
    }

    public PermTreeDtos.NodeDetail detail(String id) {
        guard.require(PdsPermissions.ROLE_VIEW);
        PermPoint p = requireNode(id);
        Map<String, PermNodeType> typeMap = types.all();
        Map<String, PermPoint> byId = nodesOf(p.getAppCode()).stream().collect(Collectors.toMap(PermPoint::getId, x -> x));
        List<PermTreeDtos.PathItem> path = new ArrayList<>();
        String parent = effectiveParent(p);
        Set<String> seen = new HashSet<>();
        while (parent != null && seen.add(parent) && byId.containsKey(parent)) {
            PermPoint pp = byId.get(parent);
            path.add(0, new PermTreeDtos.PathItem(pp.getId(), pp.getCode(), str(merged(pp).get("name"))));
            parent = effectiveParent(pp);
        }
        List<PermTreeDtos.NodeView> children = byId.values().stream().filter(c -> id.equals(effectiveParent(c)))
                .map(c -> view(c, typeMap))
                .sorted(Comparator.comparingInt(PermTreeDtos.NodeView::sort).thenComparing(PermTreeDtos.NodeView::code))
                .toList();
        long holders = p.getStatus() != null && p.getStatus() == 1 ? holderUsers(p.getCode()).size() : 0;
        return new PermTreeDtos.NodeDetail(view(p, typeMap), path, children, holders,
                aliases.ofNew(p.getCode()).stream().map(com.pd.pds.server.entity.PermCodeAlias::getOldCode).sorted().toList());
    }

    // ------------------------------------------------------------------ 新增 / 修改 / 删除（草稿）

    @Transactional
    public PermTreeDtos.NodeView create(PermTreeDtos.CreateNode req) {
        CurrentActor actor = requireManage();
        AppInfo app = appMapper.selectById(req.appCode());
        if (app == null) {
            throw BizException.notFound("应用【" + req.appCode() + "】");
        }
        PermNodeType type = types.require(req.type());
        PermPoint parent = null;
        if (StringUtils.hasText(req.parentId())) {
            parent = requireNode(req.parentId());
            if (!parent.getAppCode().equals(app.getCode())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "父节点不属于该应用");
            }
            if (isDeleting(parent)) {
                throw new BizException(ErrorCode.CONFLICT, "父节点待删除，不能在其下新增节点");
            }
        }
        checkParentType(type, parent == null ? null : str(merged(parent).get("type")));

        String prefix = parent == null ? app.getCode() : parent.getCode();
        String code = prefix + ":" + (StringUtils.hasText(req.segment()) ? req.segment() : autoSegment(prefix));
        if (pointMapper.selectById(code) != null) {
            throw BizException.duplicate("权限编码【" + code + "】");
        }
        Instant now = Instant.now();
        PermPoint p = new PermPoint();
        p.setCode(code);
        p.setId(types.newNodeId());
        p.setAppCode(app.getCode());
        p.setParentId(parent == null ? null : parent.getId());
        p.setName(req.name().trim());
        p.setType(type.getCode());
        p.setRisk(req.risk() != null ? req.risk() : type.getDefaultRisk());
        p.setSecurityLevel(req.securityLevel());
        p.setGrantable(req.grantable() != null ? req.grantable() : !Boolean.FALSE.equals(type.getGrantable()));
        p.setNeedMfa(Boolean.TRUE.equals(req.needMfa()));
        p.setDataScopeEnabled(req.dataScopeEnabled() != null ? req.dataScopeEnabled() : Boolean.TRUE.equals(type.getDataScope()));
        p.setDescription(req.description());
        p.setSource("MANUAL");
        p.setCustomized(true);
        p.setStatus(2);
        p.setSort(nextSort(app.getCode(), p.getParentId()));
        p.setCreatedBy(actor.userId());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        pointMapper.insert(p);
        auditAdmin("新增权限节点（草稿）", p, Map.of("code", code, "name", p.getName(), "type", type.getName()));
        return view(p, types.all());
    }

    @Transactional
    public PermTreeDtos.NodeView update(String id, PermTreeDtos.UpdateNode req) {
        requireManage();
        PermPoint p = requireNode(id);
        if (isDeleting(p)) {
            throw new BizException(ErrorCode.CONFLICT, "该节点待删除，请先撤销删除");
        }
        Map<String, Object> current = merged(p);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (req.name() != null && !req.name().isBlank()) {
            changes.put("name", req.name().trim());
        }
        if (req.type() != null) {
            types.require(req.type());
            changes.put("type", req.type());
        }
        if (req.risk() != null) {
            changes.put("risk", req.risk());
        }
        if (Boolean.TRUE.equals(req.clearSecurityLevel())) {
            changes.put("securityLevel", null);
        } else if (req.securityLevel() != null) {
            changes.put("securityLevel", req.securityLevel());
        }
        if (req.grantable() != null) {
            changes.put("grantable", req.grantable());
        }
        if (req.needMfa() != null) {
            changes.put("needMfa", req.needMfa());
        }
        if (req.dataScopeEnabled() != null) {
            changes.put("dataScopeEnabled", req.dataScopeEnabled());
        }
        if (req.description() != null) {
            changes.put("description", req.description());
        }
        if (req.sort() != null) {
            changes.put("sort", req.sort());
        }
        if (Boolean.TRUE.equals(req.moveToRoot())) {
            changes.put("parentId", null);
        } else if (StringUtils.hasText(req.parentId())) {
            PermPoint np = requireNode(req.parentId());
            if (!np.getAppCode().equals(p.getAppCode())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "只能在同一应用内移动");
            }
            if (np.getId().equals(p.getId()) || descendantsOf(p).contains(np.getId())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "不能移动到自身或其下级节点下");
            }
            changes.put("parentId", np.getId());
        }
        // 类型与父节点的约束按修改后的结果校验
        String newType = changes.containsKey("type") ? str(changes.get("type")) : str(current.get("type"));
        String newParent = changes.containsKey("parentId") ? str(changes.get("parentId")) : str(current.get("parentId"));
        if (changes.containsKey("type") || changes.containsKey("parentId")) {
            PermPoint np = newParent == null ? null : requireNode(newParent);
            checkParentType(types.require(newType), np == null ? null : str(merged(np).get("type")));
        }

        boolean draft = p.getStatus() != null && p.getStatus() == 2;
        if (StringUtils.hasText(req.code()) && !req.code().equals(p.getCode())) {
            if (!draft) {
                throw new BizException(ErrorCode.CONFLICT, "权限编码发布后锁定，不能修改");
            }
            renameDraft(p, req.code());
        }
        if (draft) {
            applyFields(p, changes);
            p.setUpdatedAt(Instant.now());
            persist(p);
        } else {
            Map<String, Object> pending = pendingOf(p);
            Map<String, Object> base = baseOf(p);
            changes.forEach((k, v) -> {
                if (Objects.equals(normalize(base.get(k)), normalize(v))) {
                    pending.remove(k);
                } else {
                    pending.put(k, v);
                }
            });
            savePending(p, pending);
        }
        auditAdmin(draft ? "修改权限节点（草稿）" : "修改权限节点（待发布）", p, Map.of("changes", changes.keySet().toString()));
        return view(requireNode(id), types.all());
    }

    /** 停用 / 启用（已发布节点进入待发布；草稿节点不涉及）。 */
    @Transactional
    public PermTreeDtos.NodeView setEnabled(String id, boolean enabled) {
        requireManage();
        PermPoint p = requireNode(id);
        if (p.getStatus() != null && p.getStatus() == 2) {
            throw new BizException(ErrorCode.CONFLICT, "草稿节点发布后才能停用或启用");
        }
        Map<String, Object> pending = pendingOf(p);
        int target = enabled ? 1 : 0;
        if (Objects.equals(p.getStatus(), target)) {
            pending.remove("status");
        } else {
            pending.put("status", target);
        }
        savePending(p, pending);
        auditAdmin(enabled ? "启用权限节点（待发布）" : "停用权限节点（待发布）", p, Map.of());
        return view(requireNode(id), types.all());
    }

    /** 删除：草稿节点（含其草稿下级）直接删除；已发布节点标记待删除，发布时删除。 */
    @Transactional
    public void delete(String id) {
        requireManage();
        PermPoint p = requireNode(id);
        List<PermPoint> subtree = new ArrayList<>();
        Map<String, PermPoint> byId = nodesOf(p.getAppCode()).stream().collect(Collectors.toMap(PermPoint::getId, x -> x));
        for (String d : descendantsOf(p)) {
            subtree.add(byId.get(d));
        }
        if (p.getStatus() != null && p.getStatus() == 2) {
            if (subtree.stream().anyMatch(c -> c.getStatus() == null || c.getStatus() != 2)) {
                throw new BizException(ErrorCode.CONFLICT, "该节点下有已发布的节点，请先移走或删除它们");
            }
            subtree.forEach(c -> pointMapper.deleteById(c.getCode()));
            pointMapper.deleteById(p.getCode());
            auditAdmin("删除草稿节点", p, Map.of("code", p.getCode(), "count", subtree.size() + 1));
            return;
        }
        if (subtree.stream().anyMatch(c -> !isDeleting(c))) {
            throw new BizException(ErrorCode.CONFLICT, "该节点下还有 " + subtree.size() + " 个下级节点，请先删除或移走下级节点");
        }
        long used = usage(Set.of(p.getCode()));
        if (used > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该节点还被 " + used + " 条授权或角色引用，请先在分配库中撤销，或改为停用");
        }
        Map<String, Object> pending = pendingOf(p);
        pending.put(DELETE_MARK, true);
        savePending(p, pending);
        auditAdmin("删除权限节点（待发布）", p, Map.of("code", p.getCode()));
    }

    /** 撤销某节点的全部未发布修改（草稿节点则删除）。 */
    @Transactional
    public void discard(String id) {
        requireManage();
        PermPoint p = requireNode(id);
        if (p.getStatus() != null && p.getStatus() == 2) {
            delete(id);
            return;
        }
        savePending(p, new HashMap<>());
        auditAdmin("撤销权限节点修改", p, Map.of());
    }

    // ------------------------------------------------------------------ 差异与发布

    public List<PermTreeDtos.Change> diff(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        Map<String, PermNodeType> typeMap = types.all();
        List<PermPoint> nodes = nodesOf(app);
        Map<String, PermPoint> byId = nodes.stream().collect(Collectors.toMap(PermPoint::getId, x -> x));
        List<PermTreeDtos.Change> out = new ArrayList<>();
        for (PermPoint p : nodes) {
            if (p.getStatus() != null && p.getStatus() == 2) {
                out.add(new PermTreeDtos.Change(p.getId(), p.getCode(), p.getName(), "NEW", List.of()));
            } else if (isDeleting(p)) {
                out.add(new PermTreeDtos.Change(p.getId(), p.getCode(), p.getName(), "DELETE", List.of()));
            } else if (isPending(p)) {
                Map<String, Object> base = baseOf(p);
                List<PermTreeDtos.FieldChange> fields = new ArrayList<>();
                pendingOf(p).forEach((k, v) -> fields.add(new PermTreeDtos.FieldChange(k, LABELS.getOrDefault(k, k),
                        display(k, base.get(k), byId, typeMap), display(k, v, byId, typeMap))));
                out.add(new PermTreeDtos.Change(p.getId(), p.getCode(), str(merged(p).get("name")), "MODIFY", fields));
            }
        }
        out.sort(Comparator.comparing(PermTreeDtos.Change::kind).thenComparing(PermTreeDtos.Change::code));
        return out;
    }

    @Transactional
    public PermTreeDtos.VersionView publish(String app, PermTreeDtos.Publish req) {
        CurrentActor actor = requireManage();
        List<PermTreeDtos.Change> changes = diff(app);
        if (changes.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "没有待发布的修改");
        }
        Instant now = Instant.now();
        for (PermPoint p : nodesOf(app)) {
            if (p.getStatus() != null && p.getStatus() == 2) {
                p.setStatus(1);
                p.setUpdatedAt(now);
                pointMapper.updateById(p);
            } else if (isDeleting(p)) {
                if (usage(Set.of(p.getCode())) > 0) {
                    throw new BizException(ErrorCode.CONFLICT, "节点【" + p.getName() + "】已被授权引用，不能删除，请撤销该修改后重新发布");
                }
                pointMapper.deleteById(p.getCode());
            } else if (isPending(p)) {
                applyFields(p, pendingOf(p));
                p.setPending(null);
                p.setCustomized(true);
                p.setUpdatedAt(now);
                persist(p);
            }
        }
        String version = nextVersion(app, now);
        PermTreeVersion v = new PermTreeVersion();
        v.setId(Ids.next("ptv"));
        v.setAppCode(app);
        v.setVersion(version);
        v.setSummary(req == null || !StringUtils.hasText(req.summary()) ? summarize(changes) : req.summary());
        v.setChanges(json.writeValueAsString(changes));
        v.setPublishedBy(actor.userId());
        v.setPublishedAt(now);
        versionMapper.insert(v);
        authz.bumpVersion();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", "发布权限树 " + version, "SUCCESS")
                .actor(actor.auditId(), actor.userName(), actor.ip()).target("perm_tree", app)
                .detail(Map.of("version", version, "changes", changes.size(), "summary", v.getSummary())));
        distribution.emit("perm_tree.published", "perm_tree", app, Set.of(app),
                Map.of("app", app, "version", version, "changes", changes));
        return versionView(v, changes.size());
    }

    public List<PermTreeDtos.VersionView> versions(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        return versionMapper.selectList(new LambdaQueryWrapper<PermTreeVersion>().eq(PermTreeVersion::getAppCode, app)
                        .orderByDesc(PermTreeVersion::getPublishedAt)).stream()
                .map(v -> versionView(v, changeCount(v))).toList();
    }

    /** 下一个版本号（不发布，仅用于按钮显示）。 */
    public String nextVersionLabel(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        return nextVersion(app, Instant.now());
    }

    // ------------------------------------------------------------------ 谁拥有 / 变更记录

    public PermTreeDtos.Holders holders(String id, int limit) {
        guard.require(PdsPermissions.AUTHZ_QUERY);
        PermPoint p = requireNode(id);
        List<PermTreeDtos.Holder> all = holderUsers(p.getCode());
        return new PermTreeDtos.Holders(all.size(), all.stream().limit(Math.max(1, Math.min(limit, 500))).toList());
    }

    public List<PermTreeDtos.HistoryItem> history(String id) {
        guard.require(PdsPermissions.ROLE_VIEW);
        PermPoint p = requireNode(id);
        List<PermTreeDtos.HistoryItem> items = new ArrayList<>();
        for (AuditEvent e : audit.byTarget("perm_node", p.getId(), 50)) {
            items.add(new PermTreeDtos.HistoryItem(e.getAction(), e.getUserName(), e.getTs(), e.getDetail()));
        }
        return items;
    }

    /**
     * 当前拥有该权限的在职人员（逐人计算有效权限，结果按授权版本号缓存）。
     */
    public List<PermTreeDtos.Holder> holderUsers(String code) {
        List<PermTreeDtos.Holder> out = new ArrayList<>();
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE).orderByAsc(SysUser::getEmpNo));
        Map<String, String> deptNames = new HashMap<>();
        for (SysUser u : users) {
            EffectivePermissions eff = authz.effective(u.getId());
            if (!eff.has(code)) {
                continue;
            }
            Decision d = eff.explain(code);
            Set<String> sources = new LinkedHashSet<>();
            for (SourceRef r : d.allowRefs()) {
                sources.add(sourceLabel(r));
            }
            String dept = u.getPrimaryOrgId() == null ? null : deptNames.computeIfAbsent(u.getPrimaryOrgId(),
                    k -> java.util.Optional.ofNullable(orgMapper.selectById(k)).map(OrgUnit::getName).orElse(null));
            out.add(new PermTreeDtos.Holder(u.getId(), u.getName(), u.getEmpNo(), dept, List.copyOf(sources)));
        }
        return out;
    }

    static String sourceLabel(SourceRef r) {
        String base = switch (r.kind()) {
            case ROLE -> "角色：" + r.roleName();
            case DIRECT_ALLOW -> "个人直授";
            case GRANT_ALLOW -> (r.subjectType() == null ? "" : r.subjectType().label() + "：") + r.subjectName();
            default -> r.text();
        };
        return r.viaNode() == null ? base : base + "（经" + r.viaNode() + "）";
    }

    // ------------------------------------------------------------------ 内部

    private CurrentActor requireManage() {
        guard.require(PdsPermissions.APP_MANAGE);
        return guard.requireUser();
    }

    public PermPoint requireNode(String id) {
        PermPoint p = pointMapper.selectOne(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getId, id));
        if (p == null) {
            throw BizException.notFound("权限节点");
        }
        return p;
    }

    private List<PermPoint> nodesOf(String app) {
        return pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getAppCode, app));
    }

    private List<String> descendantsOf(PermPoint p) {
        Map<String, List<String>> children = new HashMap<>();
        for (PermPoint n : nodesOf(p.getAppCode())) {
            String parent = effectiveParent(n);
            if (parent != null) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(n.getId());
            }
        }
        List<String> out = new ArrayList<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>(children.getOrDefault(p.getId(), List.of()));
        while (!stack.isEmpty()) {
            String c = stack.pop();
            if (!out.contains(c)) {
                out.add(c);
                stack.addAll(children.getOrDefault(c, List.of()));
            }
        }
        return out;
    }

    private void checkParentType(PermNodeType type, String parentType) {
        List<String> allowed = PermNodeTypeService.parentTypes(type);
        if (allowed.isEmpty()) {
            return;
        }
        if (parentType == null || !allowed.contains(parentType)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "【" + type.getName() + "】只能放在以下类型的节点下："
                    + allowed.stream().map(c -> java.util.Optional.ofNullable(types.all().get(c)).map(PermNodeType::getName).orElse(c))
                    .collect(Collectors.joining("、")));
        }
    }

    private String autoSegment(String prefix) {
        for (int i = 0; i < 20; i++) {
            String seg = Ids.shortId("n", 4).toLowerCase().replace("_", "");
            if (pointMapper.selectById(prefix + ":" + seg) == null) {
                return seg;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "编码生成失败，请手工填写编码标识");
    }

    private int nextSort(String app, String parentId) {
        return nodesOf(app).stream().filter(n -> Objects.equals(effectiveParent(n), parentId))
                .mapToInt(n -> n.getSort() == null ? 0 : n.getSort()).max().orElse(0) + 10;
    }

    /** 草稿节点改编码：连同其草稿下级的编码前缀一起改。 */
    private void renameDraft(PermPoint p, String newCode) {
        if (!newCode.startsWith(p.getAppCode() + ":") || !newCode.matches("[a-z][a-z0-9_]*(:[a-z0-9_]+)+")) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "权限编码须以「" + p.getAppCode() + ":」开头，只能包含小写字母、数字、下划线和冒号");
        }
        if (pointMapper.selectById(newCode) != null) {
            throw BizException.duplicate("权限编码【" + newCode + "】");
        }
        String old = p.getCode();
        Map<String, PermPoint> byId = nodesOf(p.getAppCode()).stream().collect(Collectors.toMap(PermPoint::getId, x -> x));
        List<PermPoint> renamed = new ArrayList<>();
        renamed.add(p);
        for (String d : descendantsOf(p)) {
            PermPoint c = byId.get(d);
            if (c.getStatus() == 2 && c.getCode().startsWith(old + ":")) {
                renamed.add(c);
            }
        }
        for (PermPoint n : renamed) {
            String target = newCode + n.getCode().substring(old.length());
            pointMapper.deleteById(n.getCode());
            n.setCode(target);
            pointMapper.insert(n);
        }
    }

    /** 保存节点（可为空的字段显式写入，避免被忽略）。 */
    private void persist(PermPoint p) {
        pointMapper.updateById(p);
        pointMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PermPoint>()
                .eq(PermPoint::getCode, p.getCode())
                .set(PermPoint::getParentId, p.getParentId())
                .set(PermPoint::getSecurityLevel, p.getSecurityLevel())
                .set(PermPoint::getDescription, p.getDescription())
                .set(PermPoint::getPending, p.getPending()));
    }

    private void applyFields(PermPoint p, Map<String, Object> f) {
        if (f.containsKey("name")) p.setName(str(f.get("name")));
        if (f.containsKey("type")) p.setType(str(f.get("type")));
        if (f.containsKey("risk")) p.setRisk(str(f.get("risk")));
        if (f.containsKey("securityLevel")) p.setSecurityLevel(f.get("securityLevel") == null ? null : ((Number) f.get("securityLevel")).intValue());
        if (f.containsKey("grantable")) p.setGrantable(Boolean.TRUE.equals(f.get("grantable")));
        if (f.containsKey("needMfa")) p.setNeedMfa(Boolean.TRUE.equals(f.get("needMfa")));
        if (f.containsKey("dataScopeEnabled")) p.setDataScopeEnabled(Boolean.TRUE.equals(f.get("dataScopeEnabled")));
        if (f.containsKey("description")) p.setDescription(str(f.get("description")));
        if (f.containsKey("sort")) p.setSort(((Number) f.get("sort")).intValue());
        if (f.containsKey("parentId")) p.setParentId(str(f.get("parentId")));
        if (f.containsKey("status")) p.setStatus(((Number) f.get("status")).intValue());
    }

    private Map<String, Object> baseOf(PermPoint p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("type", p.getType());
        m.put("risk", p.getRisk());
        m.put("securityLevel", p.getSecurityLevel());
        m.put("grantable", Boolean.TRUE.equals(p.getGrantable()));
        m.put("needMfa", Boolean.TRUE.equals(p.getNeedMfa()));
        m.put("dataScopeEnabled", Boolean.TRUE.equals(p.getDataScopeEnabled()));
        m.put("description", p.getDescription());
        m.put("sort", p.getSort() == null ? 0 : p.getSort());
        m.put("parentId", p.getParentId());
        m.put("status", p.getStatus());
        return m;
    }

    private Map<String, Object> merged(PermPoint p) {
        Map<String, Object> m = baseOf(p);
        pendingOf(p).forEach((k, v) -> {
            if (!DELETE_MARK.equals(k)) {
                m.put(k, v);
            }
        });
        return m;
    }

    private String effectiveParent(PermPoint p) {
        return str(merged(p).get("parentId"));
    }

    private Map<String, Object> pendingOf(PermPoint p) {
        if (!StringUtils.hasText(p.getPending())) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(json.readValue(p.getPending(), new TypeReference<Map<String, Object>>() {
        }));
    }

    private void savePending(PermPoint p, Map<String, Object> pending) {
        String value = pending.isEmpty() ? null : json.writeValueAsString(pending);
        pointMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PermPoint>()
                .eq(PermPoint::getCode, p.getCode()).set(PermPoint::getPending, value).set(PermPoint::getUpdatedAt, Instant.now()));
        p.setPending(value);
    }

    private static boolean isPending(PermPoint p) {
        return (p.getStatus() != null && p.getStatus() == 2) || StringUtils.hasText(p.getPending());
    }

    private boolean isDeleting(PermPoint p) {
        return Boolean.TRUE.equals(pendingOf(p).get(DELETE_MARK));
    }

    private long usage(Set<String> codes) {
        return rolePermMapper.selectCount(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getPermCode, codes))
                + grantMapper.selectCount(new LambdaQueryWrapper<AuthGrant>().in(AuthGrant::getPermCode, codes));
    }

    private PermTreeDtos.NodeView view(PermPoint p, Map<String, PermNodeType> typeMap) {
        Map<String, Object> m = merged(p);
        boolean draftNode = p.getStatus() != null && p.getStatus() == 2;
        String draft = draftNode ? "NEW" : isDeleting(p) ? "DELETING" : StringUtils.hasText(p.getPending()) ? "MODIFIED" : null;
        String type = str(m.get("type"));
        PermNodeType t = typeMap.get(type);
        Object status = m.get("status");
        return new PermTreeDtos.NodeView(p.getId(), p.getCode(), p.getAppCode(), str(m.get("parentId")), str(m.get("name")),
                type, t == null ? type : t.getName(), str(m.get("risk")),
                m.get("securityLevel") == null ? null : ((Number) m.get("securityLevel")).intValue(),
                Boolean.TRUE.equals(m.get("grantable")), Boolean.TRUE.equals(m.get("needMfa")),
                Boolean.TRUE.equals(m.get("dataScopeEnabled")), p.getSource(),
                status == null ? 1 : ((Number) status).intValue(), str(m.get("description")),
                m.get("sort") == null ? 0 : ((Number) m.get("sort")).intValue(), draft, !draftNode, p.getUpdatedAt());
    }

    private String display(String field, Object v, Map<String, PermPoint> byId, Map<String, PermNodeType> typeMap) {
        if (v == null) {
            return "parentId".equals(field) ? "（应用根）" : "—";
        }
        return switch (field) {
            case "parentId" -> byId.containsKey(str(v)) ? byId.get(str(v)).getName() : str(v);
            case "type" -> typeMap.containsKey(str(v)) ? typeMap.get(str(v)).getName() : str(v);
            case "risk" -> switch (str(v)) {
                case "HIGH" -> "高";
                case "MEDIUM" -> "中";
                default -> "低";
            };
            case "status" -> ((Number) v).intValue() == 1 ? "启用" : "停用";
            case "grantable", "needMfa", "dataScopeEnabled" -> Boolean.TRUE.equals(v) ? "是" : "否";
            case "securityLevel" -> switch (((Number) v).intValue()) {
                case 0 -> "公开";
                case 1 -> "内部";
                case 2 -> "秘密";
                case 3 -> "机密";
                default -> String.valueOf(v);
            };
            default -> String.valueOf(v);
        };
    }

    private static Object normalize(Object v) {
        return v instanceof Number n ? n.longValue() : v;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private String nextVersion(String app, Instant now) {
        ZonedDateTime t = now.atZone(ZONE);
        String prefix = "v" + t.getYear() + "." + t.getMonthValue() + ".";
        long n = versionMapper.selectCount(new LambdaQueryWrapper<PermTreeVersion>().eq(PermTreeVersion::getAppCode, app)
                .likeRight(PermTreeVersion::getVersion, prefix));
        return prefix + (n + 1);
    }

    private static String summarize(List<PermTreeDtos.Change> changes) {
        Map<String, Long> byKind = changes.stream().collect(Collectors.groupingBy(PermTreeDtos.Change::kind, Collectors.counting()));
        List<String> parts = new ArrayList<>();
        if (byKind.containsKey("NEW")) parts.add("新增 " + byKind.get("NEW"));
        if (byKind.containsKey("MODIFY")) parts.add("修改 " + byKind.get("MODIFY"));
        if (byKind.containsKey("DELETE")) parts.add("删除 " + byKind.get("DELETE"));
        return String.join("，", parts);
    }

    private int changeCount(PermTreeVersion v) {
        try {
            return json.readValue(v.getChanges(), new TypeReference<List<Object>>() {
            }).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private PermTreeDtos.VersionView versionView(PermTreeVersion v, int count) {
        return new PermTreeDtos.VersionView(v.getId(), v.getAppCode(), v.getVersion(), v.getSummary(), count,
                v.getPublishedBy() == null ? null : java.util.Optional.ofNullable(userMapper.selectById(v.getPublishedBy()))
                        .map(SysUser::getName).orElse(null), v.getPublishedAt());
    }

    private void auditAdmin(String action, PermPoint p, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("perm_node", p.getId()).detail(detail));
    }
}
