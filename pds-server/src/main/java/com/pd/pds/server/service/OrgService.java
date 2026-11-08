package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.authz.ResolvedScope;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PositionMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.OrgDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 组织架构：物化路径树（/root/S001/D0103/）。景区节点的 scenic_id 为自身 ID，其下级继承所属景区。
 */
@Service
public class OrgService {

    public static final String ROOT_ID = "org_root";

    private final OrgUnitMapper orgMapper;
    private final UserOrgMapper userOrgMapper;
    private final PositionMapper positionMapper;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public OrgService(OrgUnitMapper orgMapper, UserOrgMapper userOrgMapper, PositionMapper positionMapper,
                      Guard guard, AuthzService authz, AuditService audit) {
        this.orgMapper = orgMapper;
        this.userOrgMapper = userOrgMapper;
        this.positionMapper = positionMapper;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    public OrgUnit require(String id) {
        OrgUnit o = orgMapper.selectById(id);
        if (o == null) {
            throw BizException.notFound("组织【" + id + "】");
        }
        return o;
    }

    /** 组织树；只返回当前人员数据范围内的节点（及其上级链，便于展示）。 */
    public List<OrgDtos.OrgNode> tree() {
        ResolvedScope scope = guard.require(PdsPermissions.ORG_VIEW);
        List<OrgUnit> all = orgMapper.selectList(new LambdaQueryWrapper<OrgUnit>().orderByAsc(OrgUnit::getSort).orderByAsc(OrgUnit::getCode));
        Map<String, Long> counts = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().select(UserOrg::getOrgId, UserOrg::getUserId))
                .stream().collect(Collectors.groupingBy(UserOrg::getOrgId, Collectors.mapping(UserOrg::getUserId,
                        Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));
        java.util.Set<String> visible = new java.util.HashSet<>();
        for (OrgUnit o : all) {
            if (Guard.covers(scope, o)) {
                for (String seg : o.getPath().split("/")) {
                    if (!seg.isBlank()) {
                        visible.add(seg);
                    }
                }
            }
        }
        Map<String, OrgDtos.OrgNode> nodes = new HashMap<>();
        List<OrgDtos.OrgNode> roots = new ArrayList<>();
        for (OrgUnit o : all) {
            if (!visible.contains(o.getId())) {
                continue;
            }
            OrgDtos.OrgNode n = new OrgDtos.OrgNode();
            n.id = o.getId();
            n.parentId = o.getParentId();
            n.type = o.getType();
            n.code = o.getCode();
            n.name = o.getName();
            n.scenicId = o.getScenicId();
            n.sort = o.getSort();
            n.status = o.getStatus();
            n.inheritFromParent = !Boolean.FALSE.equals(o.getInheritFromParent());
            n.userCount = counts.getOrDefault(o.getId(), 0L);
            nodes.put(o.getId(), n);
        }
        for (OrgDtos.OrgNode n : nodes.values()) {
            OrgDtos.OrgNode parent = n.parentId == null ? null : nodes.get(n.parentId);
            if (parent == null) {
                roots.add(n);
            } else {
                parent.children.add(n);
            }
        }
        sort(roots);
        return roots;
    }

    private static void sort(List<OrgDtos.OrgNode> list) {
        list.sort(Comparator.comparing((OrgDtos.OrgNode n) -> n.sort == null ? 0 : n.sort).thenComparing(n -> n.code));
        list.forEach(n -> sort(n.children));
    }

    @Transactional
    public OrgUnit create(OrgDtos.CreateOrg req) {
        ResolvedScope scope = guard.require(PdsPermissions.ORG_MANAGE);
        OrgUnit parent = require(req.parentId());
        guard.requireCovers(scope, parent, PdsPermissions.ORG_MANAGE);
        if (orgMapper.selectCount(new LambdaQueryWrapper<OrgUnit>().eq(OrgUnit::getCode, req.code())) > 0) {
            throw BizException.duplicate("组织编码【" + req.code() + "】");
        }
        Instant now = Instant.now();
        OrgUnit o = new OrgUnit();
        o.setId(Ids.next("org"));
        o.setParentId(parent.getId());
        o.setPath(parent.getPath() + o.getId() + "/");
        o.setType(req.type());
        o.setScenicId("SCENIC".equals(req.type()) ? o.getId() : parent.getScenicId());
        o.setCode(req.code());
        o.setName(req.name());
        o.setLeaderId(req.leaderId());
        o.setSort(req.sort() == null ? 0 : req.sort());
        o.setStatus(1);
        o.setCreatedAt(now);
        o.setUpdatedAt(now);
        orgMapper.insert(o);
        auditAdmin("创建组织", o.getId(), Map.of("name", o.getName(), "type", o.getType(), "parentId", parent.getId()));
        return o;
    }

    @Transactional
    public OrgUnit update(String id, OrgDtos.UpdateOrg req) {
        ResolvedScope scope = guard.require(PdsPermissions.ORG_MANAGE);
        OrgUnit o = require(id);
        guard.requireCovers(scope, o, PdsPermissions.ORG_MANAGE);
        if (req.name() != null) {
            o.setName(req.name());
        }
        if (req.sort() != null) {
            o.setSort(req.sort());
        }
        if (req.leaderId() != null) {
            o.setLeaderId(req.leaderId());
        }
        if (req.status() != null) {
            o.setStatus(req.status());
        }
        o.setUpdatedAt(Instant.now());
        orgMapper.updateById(o);
        if (req.parentId() != null && !req.parentId().equals(o.getParentId())) {
            move(o, req.parentId(), scope);
        }
        auditAdmin("修改组织", id, Map.of("request", req.toString()));
        return require(id);
    }

    /** 移动节点：重写整棵子树的路径与所属景区，并使授权缓存失效（组织继承关系变化）。 */
    private void move(OrgUnit node, String newParentId, ResolvedScope scope) {
        if (ROOT_ID.equals(node.getId())) {
            throw new BizException(ErrorCode.CONFLICT, "根组织不能移动");
        }
        OrgUnit newParent = require(newParentId);
        guard.requireCovers(scope, newParent, PdsPermissions.ORG_MANAGE);
        if (newParent.getPath().startsWith(node.getPath())) {
            throw new BizException(ErrorCode.CONFLICT, "不能移动到自身或其下级组织之下");
        }
        String oldPrefix = node.getPath();
        String newPrefix = newParent.getPath() + node.getId() + "/";
        List<OrgUnit> subtree = orgMapper.selectList(new LambdaQueryWrapper<OrgUnit>().likeRight(OrgUnit::getPath, oldPrefix));
        subtree.sort(Comparator.comparingInt(x -> x.getPath().length()));
        Map<String, OrgUnit> updated = new HashMap<>();
        for (OrgUnit o : subtree) {
            o.setPath(newPrefix + o.getPath().substring(oldPrefix.length()));
            if (o.getId().equals(node.getId())) {
                o.setParentId(newParent.getId());
            }
            if (!"SCENIC".equals(o.getType())) {
                OrgUnit p = o.getId().equals(node.getId()) ? newParent : updated.get(o.getParentId());
                o.setScenicId(p == null ? null : p.getScenicId());
            }
            o.setUpdatedAt(Instant.now());
            orgMapper.updateById(o);
            if (o.getScenicId() == null) {
                orgMapper.update(null, new LambdaUpdateWrapper<OrgUnit>().set(OrgUnit::getScenicId, null).eq(OrgUnit::getId, o.getId()));
            }
            updated.put(o.getId(), o);
        }
        authz.bumpVersion();
    }

    @Transactional
    public void delete(String id) {
        ResolvedScope scope = guard.require(PdsPermissions.ORG_MANAGE);
        OrgUnit o = require(id);
        guard.requireCovers(scope, o, PdsPermissions.ORG_MANAGE);
        if (ROOT_ID.equals(id)) {
            throw new BizException(ErrorCode.CONFLICT, "根组织不能删除");
        }
        if (orgMapper.selectCount(new LambdaQueryWrapper<OrgUnit>().eq(OrgUnit::getParentId, id)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该组织下还有下级组织，请先处理");
        }
        if (userOrgMapper.selectCount(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getOrgId, id)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该组织下还有人员，请先迁移");
        }
        orgMapper.deleteById(id);
        authz.bumpVersion();
        auditAdmin("删除组织", id, Map.of("name", o.getName()));
    }

    // ------------------------------------------------------------------ 岗位

    public List<Position> positions(String scenicId) {
        guard.require(PdsPermissions.ORG_VIEW);
        return positionMapper.selectList(new LambdaQueryWrapper<Position>()
                .eq(scenicId != null, Position::getScenicId, scenicId).orderByAsc(Position::getCode));
    }

    @Transactional
    public Position createPosition(OrgDtos.CreatePosition req) {
        guard.require(PdsPermissions.ORG_MANAGE);
        if (positionMapper.selectCount(new LambdaQueryWrapper<Position>().eq(Position::getCode, req.code())) > 0) {
            throw BizException.duplicate("岗位编码【" + req.code() + "】");
        }
        Instant now = Instant.now();
        Position p = new Position();
        p.setId(Ids.next("pos"));
        p.setCode(req.code());
        p.setName(req.name());
        p.setScenicId(req.scenicId());
        p.setStatus(1);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        positionMapper.insert(p);
        auditAdmin("创建岗位", p.getId(), Map.of("name", p.getName()));
        return p;
    }

    private void auditAdmin(String action, String targetId, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("org", targetId).detail(detail));
    }
}
