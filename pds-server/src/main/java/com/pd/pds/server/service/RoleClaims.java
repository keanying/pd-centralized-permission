package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.server.entity.AppClient;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleMapping;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.mapper.AppClientMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.RoleMappingMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 角色下发到令牌（roles 声明）与权限快照：只下发与该应用相关的角色
 * （角色属于该应用、为该应用配置了外部编码，或角色权限涉及该应用），有外部编码时用外部编码。
 */
@Component
public class RoleClaims {

    private final RoleMapper roleMapper;
    private final RoleMappingMapper mappingMapper;
    private final RolePermMapper rolePermMapper;
    private final PermPointMapper pointMapper;
    private final AppClientMapper clientMapper;
    private final AuthzService authz;

    public RoleClaims(RoleMapper roleMapper, RoleMappingMapper mappingMapper, RolePermMapper rolePermMapper,
                      PermPointMapper pointMapper, AppClientMapper clientMapper, AuthzService authz) {
        this.roleMapper = roleMapper;
        this.mappingMapper = mappingMapper;
        this.rolePermMapper = rolePermMapper;
        this.pointMapper = pointMapper;
        this.clientMapper = clientMapper;
        this.authz = authz;
    }

    public String appOfClient(String clientId) {
        AppClient c = clientMapper.selectById(clientId);
        return c == null ? null : c.getAppCode();
    }

    public List<String> rolesFor(String userId, String app) {
        Set<String> roleIds = authz.activeRoleIds(userId);
        if (roleIds.isEmpty() || app == null) {
            return new ArrayList<>();
        }
        Map<String, String> mapped = mappingMapper.selectList(new LambdaQueryWrapper<RoleMapping>()
                        .in(RoleMapping::getRoleId, roleIds).eq(RoleMapping::getAppCode, app)).stream()
                .collect(Collectors.toMap(RoleMapping::getRoleId, RoleMapping::getExternalCode));
        Set<String> appPerms = pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().select(PermPoint::getCode)
                .eq(PermPoint::getAppCode, app)).stream().map(PermPoint::getCode).collect(Collectors.toSet());
        Set<String> touching = appPerms.isEmpty() ? Set.of() : rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>()
                        .in(RolePerm::getRoleId, roleIds).in(RolePerm::getPermCode, appPerms)).stream()
                .map(RolePerm::getRoleId).collect(Collectors.toSet());
        Set<String> out = new TreeSet<>();
        for (Role r : roleMapper.selectByIds(roleIds)) {
            if (r.getStatus() != null && r.getStatus() == 0) {
                continue;
            }
            if (mapped.containsKey(r.getId())) {
                out.add(mapped.get(r.getId()));
            } else if (app.equals(r.getAppCode()) || touching.contains(r.getId())) {
                out.add(r.getCode());
            }
        }
        return new ArrayList<>(out);
    }
}
