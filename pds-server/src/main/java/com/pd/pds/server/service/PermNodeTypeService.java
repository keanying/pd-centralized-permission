package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.PermNodeType;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.mapper.PermNodeTypeMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.PermTreeDtos;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 权限节点类型：全部为可维护的字典数据，没有不可删除的内置类型。
 */
@Service
public class PermNodeTypeService {

    private final PermNodeTypeMapper typeMapper;
    private final PermPointMapper pointMapper;
    private final Guard guard;
    private final AuditService audit;

    public PermNodeTypeService(PermNodeTypeMapper typeMapper, PermPointMapper pointMapper, @Lazy Guard guard,
                               AuditService audit) {
        this.typeMapper = typeMapper;
        this.pointMapper = pointMapper;
        this.guard = guard;
        this.audit = audit;
    }

    public List<PermTreeDtos.NodeTypeView> list() {
        guard.require(PdsPermissions.ROLE_VIEW);
        Map<String, Long> usage = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().select(PermPoint::getType))
                .stream().collect(Collectors.groupingBy(PermPoint::getType, Collectors.counting()));
        return typeMapper.selectList(new LambdaQueryWrapper<PermNodeType>().orderByAsc(PermNodeType::getSort)
                        .orderByAsc(PermNodeType::getCode)).stream()
                .map(t -> view(t, usage.getOrDefault(t.getCode(), 0L))).toList();
    }

    public Map<String, PermNodeType> all() {
        return typeMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(PermNodeType::getCode, t -> t));
    }

    public PermNodeType require(String code) {
        PermNodeType t = typeMapper.selectById(code);
        if (t == null) {
            throw BizException.notFound("节点类型【" + code + "】");
        }
        return t;
    }

    static PermTreeDtos.NodeTypeView view(PermNodeType t, long usage) {
        return new PermTreeDtos.NodeTypeView(t.getCode(), t.getName(), t.getIcon(), t.getColor(), parentTypes(t),
                Boolean.TRUE.equals(t.getGrantable()), Boolean.TRUE.equals(t.getDataScope()), t.getDefaultRisk(),
                t.getDescription(), t.getSort() == null ? 0 : t.getSort(), usage);
    }

    static List<String> parentTypes(PermNodeType t) {
        return t.getParentTypes() == null || t.getParentTypes().isBlank() ? List.of()
                : Arrays.stream(t.getParentTypes().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Transactional
    public PermTreeDtos.NodeTypeView create(PermTreeDtos.SaveNodeType req) {
        guard.require(PdsPermissions.APP_MANAGE);
        if (typeMapper.selectById(req.code()) != null) {
            throw BizException.duplicate("节点类型编码【" + req.code() + "】");
        }
        PermNodeType t = new PermNodeType();
        t.setCode(req.code());
        t.setCreatedAt(Instant.now());
        apply(t, req);
        typeMapper.insert(t);
        auditAdmin("新增节点类型", t.getCode(), Map.of("name", t.getName()));
        return view(t, 0);
    }

    @Transactional
    public PermTreeDtos.NodeTypeView update(String code, PermTreeDtos.SaveNodeType req) {
        guard.require(PdsPermissions.APP_MANAGE);
        PermNodeType t = require(code);
        apply(t, req);
        typeMapper.updateById(t);
        auditAdmin("修改节点类型", code, Map.of("name", t.getName()));
        return view(t, usage(code));
    }

    @Transactional
    public void delete(String code) {
        guard.require(PdsPermissions.APP_MANAGE);
        require(code);
        long used = usage(code);
        if (used > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该类型下还有 " + used + " 个节点，请先修改这些节点的类型");
        }
        typeMapper.deleteById(code);
        auditAdmin("删除节点类型", code, Map.of());
    }

    private long usage(String code) {
        return pointMapper.selectCount(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getType, code));
    }

    private void apply(PermNodeType t, PermTreeDtos.SaveNodeType req) {
        if (req.parentTypes() != null) {
            for (String p : req.parentTypes()) {
                if (!p.equals(t.getCode()) && typeMapper.selectById(p) == null) {
                    throw BizException.notFound("父节点类型【" + p + "】");
                }
            }
        }
        t.setName(req.name());
        t.setIcon(req.icon());
        t.setColor(req.color());
        t.setParentTypes(req.parentTypes() == null || req.parentTypes().isEmpty() ? null : String.join(",", req.parentTypes()));
        t.setGrantable(req.grantable() == null || req.grantable());
        t.setDataScope(Boolean.TRUE.equals(req.dataScope()));
        t.setDefaultRisk(req.defaultRisk() == null ? "LOW" : req.defaultRisk());
        t.setDescription(req.description());
        t.setSort(req.sort() == null ? 999 : req.sort());
        t.setUpdatedAt(Instant.now());
    }

    /** 应用清单引用了不存在的类型时自动新增（名称暂用编码，可在后台改名）。 */
    public void ensure(String code) {
        if (typeMapper.selectById(code) != null) {
            return;
        }
        PermNodeType t = new PermNodeType();
        t.setCode(code);
        t.setName(code);
        t.setGrantable(true);
        t.setDataScope(false);
        t.setDefaultRisk("LOW");
        t.setDescription("由应用清单自动新增");
        t.setSort(999);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        typeMapper.insert(t);
    }

    /** 生成不重复的节点 ID。 */
    public String newNodeId() {
        for (int i = 0; i < 20; i++) {
            String id = Ids.shortId("pn", 8);
            if (pointMapper.selectCount(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getId, id)) == 0) {
                return id;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "节点 ID 生成失败，请重试");
    }

    private void auditAdmin(String action, String target, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("perm_node_type", target).detail(detail));
    }
}
