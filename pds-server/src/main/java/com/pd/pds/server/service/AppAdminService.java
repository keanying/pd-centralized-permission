package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.server.entity.AppClient;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.AuthGrant;
import com.pd.pds.server.entity.DistDelivery;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermResource;
import com.pd.pds.server.entity.PermTreeVersion;
import com.pd.pds.server.entity.ResourceGrant;
import com.pd.pds.server.entity.ResourceType;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.RoleMapping;
import com.pd.pds.server.entity.RoleMutex;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.entity.RoleVersion;
import com.pd.pds.server.entity.UserPerm;
import com.pd.pds.server.mapper.AppClientMapper;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.AuthGrantMapper;
import com.pd.pds.server.mapper.DistDeliveryMapper;
import com.pd.pds.server.mapper.DistSubscriptionMapper;
import com.pd.pds.server.mapper.PermPointMapper;
import com.pd.pds.server.mapper.PermResourceMapper;
import com.pd.pds.server.mapper.PermTreeVersionMapper;
import com.pd.pds.server.mapper.ResourceGrantMapper;
import com.pd.pds.server.mapper.ResourceTypeMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.RoleMappingMapper;
import com.pd.pds.server.mapper.RoleMutexMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.mapper.RoleVersionMapper;
import com.pd.pds.server.mapper.UserPermMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.AppDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用中心：修改应用信息、删除应用（连同其权限节点、资源、授权、应用角色、客户端、下发设置一并删除）。
 * PDS 自身不能删除。
 */
@Service
public class AppAdminService {

    private final AppInfoMapper appMapper;
    private final PermPointMapper pointMapper;
    private final RolePermMapper rolePermMapper;
    private final AuthGrantMapper grantMapper;
    private final PermResourceMapper resourceMapper;
    private final ResourceGrantMapper resourceGrantMapper;
    private final UserPermMapper userPermMapper;
    private final ResourceTypeMapper typeMapper;
    private final RoleMapper roleMapper;
    private final RoleBindingMapper bindingMapper;
    private final RoleMutexMapper mutexMapper;
    private final RoleMappingMapper mappingMapper;
    private final RoleVersionMapper roleVersionMapper;
    private final DistSubscriptionMapper subMapper;
    private final DistDeliveryMapper deliveryMapper;
    private final PermTreeVersionMapper treeVersionMapper;
    private final AppClientMapper clientMapper;
    private final JdbcTemplate jdbc;
    private final Guard guard;
    private final AuthzService authz;
    private final AuditService audit;

    public AppAdminService(AppInfoMapper appMapper, PermPointMapper pointMapper, RolePermMapper rolePermMapper,
                           AuthGrantMapper grantMapper, PermResourceMapper resourceMapper, ResourceGrantMapper resourceGrantMapper,
                           UserPermMapper userPermMapper, ResourceTypeMapper typeMapper, RoleMapper roleMapper,
                           RoleBindingMapper bindingMapper, RoleMutexMapper mutexMapper, RoleMappingMapper mappingMapper,
                           RoleVersionMapper roleVersionMapper, DistSubscriptionMapper subMapper, DistDeliveryMapper deliveryMapper,
                           PermTreeVersionMapper treeVersionMapper, AppClientMapper clientMapper, JdbcTemplate jdbc,
                           Guard guard, AuthzService authz, AuditService audit) {
        this.appMapper = appMapper;
        this.pointMapper = pointMapper;
        this.rolePermMapper = rolePermMapper;
        this.grantMapper = grantMapper;
        this.resourceMapper = resourceMapper;
        this.resourceGrantMapper = resourceGrantMapper;
        this.userPermMapper = userPermMapper;
        this.typeMapper = typeMapper;
        this.roleMapper = roleMapper;
        this.bindingMapper = bindingMapper;
        this.mutexMapper = mutexMapper;
        this.mappingMapper = mappingMapper;
        this.roleVersionMapper = roleVersionMapper;
        this.subMapper = subMapper;
        this.deliveryMapper = deliveryMapper;
        this.treeVersionMapper = treeVersionMapper;
        this.clientMapper = clientMapper;
        this.jdbc = jdbc;
        this.guard = guard;
        this.authz = authz;
        this.audit = audit;
    }

    @Transactional
    public AppInfo update(String code, AppDtos.UpdateApp req) {
        guard.require(PdsPermissions.APP_MANAGE);
        AppInfo a = require(code);
        if (StringUtils.hasText(req.name())) a.setName(req.name().trim());
        if (req.description() != null) a.setDescription(req.description());
        if (req.homeUrl() != null) a.setHomeUrl(req.homeUrl().isBlank() ? null : req.homeUrl().trim());
        if (req.category() != null) {
            if (!req.category().isBlank() && !AppCategories.ALL.containsKey(req.category())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的应用分类：" + req.category());
            }
            a.setCategory(req.category().isBlank() ? null : req.category());
        }
        boolean accessChanged = false;
        if (req.accessControl() != null && !req.accessControl().equals(a.getAccessControl())) {
            if (PdsPermissions.APP.equals(code) && !req.accessControl()) {
                throw new BizException(ErrorCode.CONFLICT, "PDS 自身的准入控制不能关闭");
            }
            a.setAccessControl(req.accessControl());
            accessChanged = true;
        }
        a.setUpdatedAt(Instant.now());
        appMapper.updateById(a);
        appMapper.update(null, new LambdaUpdateWrapper<AppInfo>().eq(AppInfo::getCode, code)
                .set(AppInfo::getCategory, a.getCategory()).set(AppInfo::getDescription, a.getDescription()));
        if (accessChanged) {
            authz.bumpVersion();
        }
        auditAdmin("修改应用", code, Map.of("name", a.getName(), "accessControl", Boolean.TRUE.equals(a.getAccessControl())));
        return a;
    }

    /** 删除前的影响范围。 */
    public Map<String, Long> deleteImpact(String code) {
        guard.require(PdsPermissions.APP_MANAGE);
        require(code);
        Set<String> perms = permCodes(code);
        Set<String> resources = resourceIds(code);
        Set<String> roles = appRoles(code);
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("permNodes", (long) perms.size());
        m.put("grants", perms.isEmpty() ? 0 : grantMapper.selectCount(new LambdaQueryWrapper<AuthGrant>().in(AuthGrant::getPermCode, perms)));
        m.put("rolePerms", perms.isEmpty() ? 0 : rolePermMapper.selectCount(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getPermCode, perms)));
        m.put("resources", (long) resources.size());
        m.put("resourceGrants", resources.isEmpty() ? 0 : resourceGrantMapper.selectCount(new LambdaQueryWrapper<ResourceGrant>().in(ResourceGrant::getResourceId, resources))
                + userPermMapper.selectCount(new LambdaQueryWrapper<UserPerm>().in(UserPerm::getResourceId, resources)));
        m.put("appRoles", (long) roles.size());
        m.put("roleBindings", roles.isEmpty() ? 0 : bindingMapper.selectCount(new LambdaQueryWrapper<RoleBinding>().in(RoleBinding::getRoleId, roles)));
        m.put("clients", clientMapper.selectCount(new LambdaQueryWrapper<AppClient>().eq(AppClient::getAppCode, code)));
        return m;
    }

    @Transactional
    public void delete(String code) {
        guard.require(PdsPermissions.APP_MANAGE);
        AppInfo a = require(code);
        if (PdsPermissions.APP.equals(code)) {
            throw new BizException(ErrorCode.CONFLICT, "PDS 统一权限是本系统自身，不能删除");
        }
        Map<String, Long> impact = deleteImpact(code);
        Set<String> perms = permCodes(code);
        Set<String> resources = resourceIds(code);
        Set<String> roles = appRoles(code);
        if (!perms.isEmpty()) {
            grantMapper.delete(new LambdaQueryWrapper<AuthGrant>().in(AuthGrant::getPermCode, perms));
            rolePermMapper.delete(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getPermCode, perms));
        }
        pointMapper.delete(new LambdaQueryWrapper<PermPoint>().eq(PermPoint::getAppCode, code));
        treeVersionMapper.delete(new LambdaQueryWrapper<PermTreeVersion>().eq(PermTreeVersion::getAppCode, code));
        if (!resources.isEmpty()) {
            resourceGrantMapper.delete(new LambdaQueryWrapper<ResourceGrant>().in(ResourceGrant::getResourceId, resources));
            userPermMapper.delete(new LambdaQueryWrapper<UserPerm>().in(UserPerm::getResourceId, resources));
            resourceMapper.delete(new LambdaQueryWrapper<PermResource>().in(PermResource::getId, resources));
        }
        typeMapper.delete(new LambdaQueryWrapper<ResourceType>().eq(ResourceType::getAppCode, code));
        if (!roles.isEmpty()) {
            bindingMapper.delete(new LambdaQueryWrapper<RoleBinding>().in(RoleBinding::getRoleId, roles));
            rolePermMapper.delete(new LambdaQueryWrapper<RolePerm>().in(RolePerm::getRoleId, roles));
            mutexMapper.delete(new LambdaQueryWrapper<RoleMutex>().in(RoleMutex::getRoleId, roles).or().in(RoleMutex::getOtherRoleId, roles));
            mappingMapper.delete(new LambdaQueryWrapper<RoleMapping>().in(RoleMapping::getRoleId, roles));
            roleVersionMapper.delete(new LambdaQueryWrapper<RoleVersion>().in(RoleVersion::getRoleId, roles));
            roleMapper.update(null, new LambdaUpdateWrapper<Role>().in(Role::getParentRoleId, roles).set(Role::getParentRoleId, null));
            roleMapper.deleteByIds(roles);
        }
        mappingMapper.delete(new LambdaQueryWrapper<RoleMapping>().eq(RoleMapping::getAppCode, code));
        subMapper.deleteById(code);
        deliveryMapper.delete(new LambdaQueryWrapper<DistDelivery>().eq(DistDelivery::getAppCode, code));
        List<String> clientIds = clientMapper.selectList(new LambdaQueryWrapper<AppClient>().eq(AppClient::getAppCode, code))
                .stream().map(AppClient::getClientId).toList();
        for (String clientId : clientIds) {
            List<String> regIds = jdbc.queryForList("SELECT id FROM oauth2_registered_client WHERE client_id = ?", String.class, clientId);
            for (String regId : regIds) {
                jdbc.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?", regId);
                jdbc.update("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?", regId);
            }
            jdbc.update("DELETE FROM oauth2_registered_client WHERE client_id = ?", clientId);
        }
        clientMapper.delete(new LambdaQueryWrapper<AppClient>().eq(AppClient::getAppCode, code));
        appMapper.deleteById(code);
        authz.bumpVersion();
        auditAdmin("删除应用", code, Map.of("name", a.getName(), "impact", impact.toString()));
    }

    private Set<String> permCodes(String code) {
        return pointMapper.selectList(new LambdaQueryWrapper<PermPoint>().select(PermPoint::getCode).eq(PermPoint::getAppCode, code))
                .stream().map(PermPoint::getCode).collect(Collectors.toSet());
    }

    private Set<String> resourceIds(String code) {
        return resourceMapper.selectList(new LambdaQueryWrapper<PermResource>().select(PermResource::getId).eq(PermResource::getAppCode, code))
                .stream().map(PermResource::getId).collect(Collectors.toSet());
    }

    private Set<String> appRoles(String code) {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().select(Role::getId).eq(Role::getAppCode, code)
                        .ne(Role::getType, "BUILTIN")).stream().map(Role::getId).collect(Collectors.toSet());
    }

    private AppInfo require(String code) {
        AppInfo a = appMapper.selectById(code);
        if (a == null) {
            throw BizException.notFound("应用【" + code + "】");
        }
        return a;
    }

    private void auditAdmin(String action, String code, Map<String, ?> detail) {
        CurrentActor a = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", action, "SUCCESS")
                .actor(a.auditId(), a.userName(), a.ip()).target("app", code).detail(detail));
    }
}
