package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.common.BizException;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.GroupMember;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.mapper.GroupMemberMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserGroupMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.GroupDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final UserGroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public GroupService(UserGroupMapper groupMapper, GroupMemberMapper memberMapper, SysUserMapper userMapper,
                        Guard guard, AuthzService authz, AuditService audit) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    public List<GroupDtos.GroupView> list() {
        guard.require(PdsPermissions.USER_VIEW);
        Map<String, Long> counts = memberMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.groupingBy(GroupMember::getGroupId, Collectors.counting()));
        return groupMapper.selectList(new LambdaQueryWrapper<UserGroup>().orderByAsc(UserGroup::getCode)).stream()
                .map(g -> new GroupDtos.GroupView(g.getId(), g.getCode(), g.getName(), g.getScenicId(), g.getDescription(),
                        counts.getOrDefault(g.getId(), 0L)))
                .toList();
    }

    public List<Map<String, String>> members(String groupId) {
        guard.require(PdsPermissions.USER_VIEW);
        List<String> ids = memberMapper.selectList(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId))
                .stream().map(GroupMember::getUserId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return userMapper.selectByIds(ids).stream()
                .map(u -> Map.of("id", u.getId(), "name", u.getName(), "username", u.getUsername()))
                .toList();
    }

    @Transactional
    public UserGroup create(GroupDtos.CreateGroup req) {
        guard.require(PdsPermissions.GROUP_MANAGE);
        if (groupMapper.selectCount(new LambdaQueryWrapper<UserGroup>().eq(UserGroup::getCode, req.code())) > 0) {
            throw BizException.duplicate("用户组编码【" + req.code() + "】");
        }
        UserGroup g = new UserGroup();
        g.setId(Ids.next("grp"));
        g.setCode(req.code());
        g.setName(req.name());
        g.setScenicId(req.scenicId());
        g.setDescription(req.description());
        g.setCreatedAt(Instant.now());
        groupMapper.insert(g);
        auditAdmin("创建用户组", g.getId(), Map.of("name", g.getName()));
        return g;
    }

    /** 整体替换成员。 */
    @Transactional
    public void setMembers(String groupId, GroupDtos.SetMembers req) {
        guard.require(PdsPermissions.GROUP_MANAGE);
        if (groupMapper.selectById(groupId) == null) {
            throw BizException.notFound("用户组");
        }
        Set<String> ids = new LinkedHashSet<>(req.userIds());
        if (!ids.isEmpty() && userMapper.selectCount(new LambdaQueryWrapper<SysUser>().in(SysUser::getId, ids)) != ids.size()) {
            throw BizException.notFound("部分人员");
        }
        memberMapper.delete(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));
        for (String uid : ids) {
            GroupMember m = new GroupMember();
            m.setGroupId(groupId);
            m.setUserId(uid);
            memberMapper.insert(m);
        }
        authz.bumpVersion();
        auditAdmin("设置用户组成员", groupId, Map.of("userIds", ids.toString()));
    }

    private void auditAdmin(String action, String targetId, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("group", targetId).detail(detail));
    }
}
