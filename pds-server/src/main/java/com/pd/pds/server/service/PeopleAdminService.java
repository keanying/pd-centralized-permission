package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.GroupMember;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.ResourceGrant;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.GroupMemberMapper;
import com.pd.pds.server.mapper.PositionMapper;
import com.pd.pds.server.mapper.ResourceGrantMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.UserGroupMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 人员管理：岗位、用户组的修改与删除（被任职或授权引用时不能删除）。 */
@Service
public class PeopleAdminService {

    public record PositionView(String id, String code, String name, String scenicId, int status, long members, long grants) {
    }

    public record SavePosition(String code, String name, String scenicId) {
    }

    public record SaveGroup(String code, String name, String scenicId, String description) {
    }

    private final PositionMapper positionMapper;
    private final UserGroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final UserOrgMapper userOrgMapper;
    private final RoleBindingMapper bindingMapper;
    private final AuthGrantMapper grantMapper;
    private final ResourceGrantMapper resourceGrantMapper;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public PeopleAdminService(PositionMapper positionMapper, UserGroupMapper groupMapper, GroupMemberMapper memberMapper,
                              UserOrgMapper userOrgMapper, RoleBindingMapper bindingMapper, AuthGrantMapper grantMapper,
                              ResourceGrantMapper resourceGrantMapper, Guard guard, AuthzService authz, AuditService audit) {
        this.positionMapper = positionMapper;
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.userOrgMapper = userOrgMapper;
        this.bindingMapper = bindingMapper;
        this.grantMapper = grantMapper;
        this.resourceGrantMapper = resourceGrantMapper;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    // ---------------- 岗位

    public List<PositionView> positions() {
        guard.require(PdsPermissions.ORG_VIEW);
        Map<String, Long> members = userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().isNotNull(UserOrg::getPositionId))
                .stream().collect(Collectors.groupingBy(UserOrg::getPositionId, Collectors.counting()));
        return positionMapper.selectList(new LambdaQueryWrapper<Position>().orderByAsc(Position::getCode)).stream()
                .map(p -> new PositionView(p.getId(), p.getCode(), p.getName(), p.getScenicId(),
                        p.getStatus() == null ? 1 : p.getStatus(), members.getOrDefault(p.getId(), 0L), references("POSITION", p.getId())))
                .toList();
    }

    @Transactional
    public Position updatePosition(String id, SavePosition req) {
        guard.require(PdsPermissions.ORG_MANAGE);
        Position p = positionMapper.selectById(id);
        if (p == null) throw BizException.notFound("岗位");
        if (StringUtils.hasText(req.code()) && !req.code().trim().equals(p.getCode())) {
            if (positionMapper.selectCount(new LambdaQueryWrapper<Position>().eq(Position::getCode, req.code().trim())) > 0) {
                throw BizException.duplicate("岗位编码【" + req.code().trim() + "】");
            }
            p.setCode(req.code().trim());
        }
        if (StringUtils.hasText(req.name())) p.setName(req.name().trim());
        p.setScenicId(StringUtils.hasText(req.scenicId()) ? req.scenicId() : null);
        p.setUpdatedAt(Instant.now());
        positionMapper.updateById(p);
        positionMapper.update(null, new LambdaUpdateWrapper<Position>().eq(Position::getId, id).set(Position::getScenicId, p.getScenicId()));
        log("修改岗位", "position", id, Map.of("name", p.getName()));
        return p;
    }

    @Transactional
    public void deletePosition(String id) {
        guard.require(PdsPermissions.ORG_MANAGE);
        Position p = positionMapper.selectById(id);
        if (p == null) throw BizException.notFound("岗位");
        long members = userOrgMapper.selectCount(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getPositionId, id));
        if (members > 0) throw new BizException(ErrorCode.CONFLICT, "还有 " + members + " 人担任该岗位，请先调整他们的岗位");
        long refs = references("POSITION", id);
        if (refs > 0) throw new BizException(ErrorCode.CONFLICT, "该岗位还有 " + refs + " 条授权或角色绑定，请先在分配库中撤销");
        positionMapper.deleteById(id);
        log("删除岗位", "position", id, Map.of("name", p.getName()));
    }

    // ---------------- 用户组

    @Transactional
    public UserGroup updateGroup(String id, SaveGroup req) {
        guard.require(PdsPermissions.GROUP_MANAGE);
        UserGroup g = groupMapper.selectById(id);
        if (g == null) throw BizException.notFound("用户组");
        if (StringUtils.hasText(req.code()) && !req.code().trim().equals(g.getCode())) {
            if (groupMapper.selectCount(new LambdaQueryWrapper<UserGroup>().eq(UserGroup::getCode, req.code().trim())) > 0) {
                throw BizException.duplicate("用户组编码【" + req.code().trim() + "】");
            }
            g.setCode(req.code().trim());
        }
        if (StringUtils.hasText(req.name())) g.setName(req.name().trim());
        if (req.description() != null) g.setDescription(req.description());
        groupMapper.updateById(g);
        groupMapper.update(null, new LambdaUpdateWrapper<UserGroup>().eq(UserGroup::getId, id).set(UserGroup::getDescription, g.getDescription()));
        log("修改用户组", "group", id, Map.of("name", g.getName()));
        return g;
    }

    @Transactional
    public void deleteGroup(String id) {
        guard.require(PdsPermissions.GROUP_MANAGE);
        UserGroup g = groupMapper.selectById(id);
        if (g == null) throw BizException.notFound("用户组");
        long refs = references("GROUP", id);
        if (refs > 0) throw new BizException(ErrorCode.CONFLICT, "该用户组还有 " + refs + " 条授权或角色绑定，请先在分配库中撤销");
        memberMapper.delete(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, id));
        groupMapper.deleteById(id);
        authz.bumpVersion();
        log("删除用户组", "group", id, Map.of("name", g.getName()));
    }

    private long references(String type, String id) {
        return bindingMapper.selectCount(new LambdaQueryWrapper<RoleBinding>().eq(RoleBinding::getSubjectType, type).eq(RoleBinding::getSubjectId, id))
                + grantMapper.selectCount(new LambdaQueryWrapper<AuthGrant>().eq(AuthGrant::getSubjectType, type).eq(AuthGrant::getSubjectId, id))
                + resourceGrantMapper.selectCount(new LambdaQueryWrapper<ResourceGrant>().eq(ResourceGrant::getSubjectType, type).eq(ResourceGrant::getSubjectId, id));
    }

    private void log(String action, String type, String id, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS").actor(a.auditId(), a.userName(), a.ip()).target(type, id).detail(detail));
    }
}
