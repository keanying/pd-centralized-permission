package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.server.entity.GroupMember;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.GroupMemberMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** 授权主体（人员/岗位/用户组/组织）覆盖的人员。 */
@Component
public class SubjectMembers {

    private final UserOrgMapper userOrgMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final OrgUnitMapper orgMapper;

    public SubjectMembers(UserOrgMapper userOrgMapper, GroupMemberMapper groupMemberMapper, OrgUnitMapper orgMapper) {
        this.userOrgMapper = userOrgMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.orgMapper = orgMapper;
    }

    /** includeChildren 仅对组织有效：含全部下级组织的成员（不考虑阻断继承，用于影响范围估算）。 */
    public Set<String> of(String type, String id, boolean includeChildren) {
        Set<String> out = new LinkedHashSet<>();
        switch (type) {
            case "USER" -> out.add(id);
            case "POSITION" -> userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().eq(UserOrg::getPositionId, id))
                    .forEach(uo -> out.add(uo.getUserId()));
            case "GROUP" -> groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, id))
                    .forEach(m -> out.add(m.getUserId()));
            case "ORG" -> {
                Set<String> orgIds = new HashSet<>();
                orgIds.add(id);
                if (includeChildren) {
                    OrgUnit o = orgMapper.selectById(id);
                    if (o != null) {
                        orgMapper.selectList(new LambdaQueryWrapper<OrgUnit>().likeRight(OrgUnit::getPath, o.getPath()))
                                .forEach(c -> orgIds.add(c.getId()));
                    }
                }
                userOrgMapper.selectList(new LambdaQueryWrapper<UserOrg>().in(UserOrg::getOrgId, orgIds))
                        .forEach(uo -> out.add(uo.getUserId()));
            }
            default -> {
            }
        }
        return out;
    }
}
