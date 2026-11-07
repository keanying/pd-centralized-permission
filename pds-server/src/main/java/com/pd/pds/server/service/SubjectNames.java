package com.pd.pds.server.service;

import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.PositionMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserGroupMapper;
import org.springframework.stereotype.Component;

/**
 * 授权对象（人员/岗位/组织/用户组）显示名称，供管理后台展示。
 */
@Component
public class SubjectNames {

    private final SysUserMapper userMapper;
    private final PositionMapper positionMapper;
    private final OrgUnitMapper orgMapper;
    private final UserGroupMapper groupMapper;

    public SubjectNames(SysUserMapper userMapper, PositionMapper positionMapper, OrgUnitMapper orgMapper,
                        UserGroupMapper groupMapper) {
        this.userMapper = userMapper;
        this.positionMapper = positionMapper;
        this.orgMapper = orgMapper;
        this.groupMapper = groupMapper;
    }

    public String nameOf(String type, String id) {
        if (id == null) {
            return null;
        }
        String name = switch (type) {
            case "USER" -> {
                SysUser u = userMapper.selectById(id);
                yield u == null ? null : u.getName() + "（" + u.getUsername() + "）";
            }
            case "POSITION" -> {
                Position p = positionMapper.selectById(id);
                yield p == null ? null : p.getName();
            }
            case "ORG" -> {
                OrgUnit o = orgMapper.selectById(id);
                yield o == null ? null : o.getName();
            }
            case "GROUP" -> {
                UserGroup g = groupMapper.selectById(id);
                yield g == null ? null : g.getName();
            }
            default -> null;
        };
        return name == null ? id : name;
    }

    public String userName(String userId) {
        if (userId == null || "system".equals(userId)) {
            return "系统";
        }
        SysUser u = userMapper.selectById(userId);
        return u == null ? userId : u.getName();
    }
}
