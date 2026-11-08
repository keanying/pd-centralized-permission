package com.pd.pds.server.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.entity.RolePerm;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.RoleBindingMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.RolePermMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.service.AppService;
import com.pd.pds.server.service.AuditService;
import com.pd.pds.server.service.AuthzService;
import com.pd.pds.server.service.ClientService;
import com.pd.pds.server.service.OrgService;
import com.pd.pds.server.service.PasswordPolicy;
import com.pd.pds.server.web.dto.AppDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动初始化（幂等）：注册 PDS 自身权限清单、根组织、内置角色、初始超级管理员、配置中的 OAuth 客户端。
 */
@Component
@Order(0)
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final PdsProperties props;
    private final TransactionTemplate tx;
    private final AppService appService;
    private final ClientService clientService;
    private final AppInfoMapper appMapper;
    private final OrgUnitMapper orgMapper;
    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final RoleBindingMapper bindingMapper;
    private final SysUserMapper userMapper;
    private final UserOrgMapper userOrgMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthzService authz;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    private com.pd.pds.server.mapper.PermPointMapper pointMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.pd.pds.server.mapper.RoleMutexMapper mutexMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.pd.pds.server.service.PermNodeTypeService nodeTypes;

    public BootstrapRunner(PdsProperties props, TransactionTemplate tx, AppService appService, ClientService clientService,
                           AppInfoMapper appMapper, OrgUnitMapper orgMapper, RoleMapper roleMapper, RolePermMapper rolePermMapper,
                           RoleBindingMapper bindingMapper, SysUserMapper userMapper, UserOrgMapper userOrgMapper,
                           PasswordEncoder passwordEncoder, AuthzService authz, AuditService audit) {
        this.props = props;
        this.tx = tx;
        this.appService = appService;
        this.clientService = clientService;
        this.appMapper = appMapper;
        this.orgMapper = orgMapper;
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.bindingMapper = bindingMapper;
        this.userMapper = userMapper;
        this.userOrgMapper = userOrgMapper;
        this.passwordEncoder = passwordEncoder;
        this.authz = authz;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> {
            backfillNodeIds();
            registerPdsManifest();
            ensureRootOrg();
            Map<String, String> roleIds = ensureBuiltinRoles();
            ensureSeparationMutex(roleIds);
            ensureAdmin(roleIds.get(PdsPermissions.ROLE_SUPER_ADMIN));
            authz.bumpVersion();
        });
        for (PdsProperties.ClientDefinition c : props.getBootstrap().getClients()) {
            tx.executeWithoutResult(s -> ensureClient(c));
        }
        if (props.getConsole().isEnabled()) {
            tx.executeWithoutResult(s -> ensureConsoleClient());
        }
    }

    /** 管理后台客户端（公共客户端 + PKCE）：不存在则创建，存在则按配置同步回调地址。 */
    private void ensureConsoleClient() {
        PdsProperties.Console c = props.getConsole();
        String base = props.getIssuer().replaceAll("/+$", "") + c.getBasePath();
        List<String> redirects = c.getRedirectUris().isEmpty() ? List.of(base + "/auth/callback") : c.getRedirectUris();
        List<String> logouts = c.getPostLogoutRedirectUris().isEmpty() ? List.of(base + "/") : c.getPostLogoutRedirectUris();
        if (clientService.exists(c.getClientId())) {
            clientService.syncRedirectUris(c.getClientId(), redirects, logouts);
            return;
        }
        clientService.create(new ClientService.Spec(c.getClientId(), null, "PDS 管理后台", PdsPermissions.APP,
                List.of("authorization_code"), redirects, logouts, List.of("openid", "profile"), true,
                java.time.Duration.ofMinutes(30), null, true));
        log.info("已注册管理后台客户端 {}，回调地址 {}", c.getClientId(), redirects);
    }

    private void registerPdsManifest() {
        List<AppDtos.ManifestPermission> perms = PdsPermissions.ALL.entrySet().stream()
                .map(e -> new AppDtos.ManifestPermission(e.getKey(), e.getValue(), "FUNCTION",
                        PdsPermissions.HIGH_RISK.contains(e.getKey()) ? "HIGH" : "LOW", null))
                .toList();
        appService.applyManifest(PdsPermissions.APP, new AppDtos.Manifest("PDS 统一权限",
                "统一登录 · 组织架构 · 角色授权 · 景区隔离 · 字段脱敏 · 访问审计", "builtin-1", perms, List.of(),
                "SECURITY", true));
    }

    /** 升级前已存在的权限点补齐节点 ID。 */
    private void backfillNodeIds() {
        for (com.pd.pds.server.entity.PermPoint p : pointMapper.selectList(new LambdaQueryWrapper<com.pd.pds.server.entity.PermPoint>()
                .isNull(com.pd.pds.server.entity.PermPoint::getId))) {
            p.setId(nodeTypes.newNodeId());
            pointMapper.updateById(p);
        }
    }

    /** 三员分立：系统管理员、安全管理员、审计管理员两两互斥（可在系统角色中查看）。 */
    private void ensureSeparationMutex(Map<String, String> roleIds) {
        List<String> ids = PdsPermissions.SEPARATED_ROLES.stream().map(roleIds::get).filter(java.util.Objects::nonNull).sorted().toList();
        for (String a : ids) {
            for (String b : ids) {
                if (!a.equals(b) && mutexMapper.selectCount(new LambdaQueryWrapper<com.pd.pds.server.entity.RoleMutex>()
                        .eq(com.pd.pds.server.entity.RoleMutex::getRoleId, a)
                        .eq(com.pd.pds.server.entity.RoleMutex::getOtherRoleId, b)) == 0) {
                    com.pd.pds.server.entity.RoleMutex m = new com.pd.pds.server.entity.RoleMutex();
                    m.setRoleId(a);
                    m.setOtherRoleId(b);
                    mutexMapper.insert(m);
                }
            }
        }
    }

    private void ensureRootOrg() {
        if (orgMapper.selectById(OrgService.ROOT_ID) != null) {
            return;
        }
        Instant now = Instant.now();
        OrgUnit root = new OrgUnit();
        root.setId(OrgService.ROOT_ID);
        root.setPath("/" + OrgService.ROOT_ID + "/");
        root.setType("GROUP");
        root.setCode("GROUP");
        root.setName("集团");
        root.setSort(0);
        root.setStatus(1);
        root.setCreatedAt(now);
        root.setUpdatedAt(now);
        orgMapper.insert(root);
        log.info("已创建根组织【集团】");
    }

    /** 内置角色的权限与定义保持同步（数据范围：全部）。 */
    private Map<String, String> ensureBuiltinRoles() {
        Instant now = Instant.now();
        for (PdsPermissions.BuiltinRole def : PdsPermissions.BUILTIN_ROLES) {
            Role r = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getCode, def.code()));
            if (r == null) {
                r = new Role();
                r.setId(Ids.next("role"));
                r.setCode(def.code());
                r.setName(def.name());
                r.setLevel("GROUP");
                r.setDescription(def.description());
                r.setBuiltin(true);
                r.setType("BUILTIN");
                r.setStatus(1);
                r.setVersion(1);
                r.setAppCode(PdsPermissions.APP);
                r.setCreatedAt(now);
                r.setUpdatedAt(now);
                roleMapper.insert(r);
            }
            String roleId = r.getId();
            Set<String> existing = rolePermMapper.selectList(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, roleId))
                    .stream().map(RolePerm::getPermCode).collect(Collectors.toSet());
            Set<String> wanted = new HashSet<>(def.permissions());
            for (String code : wanted) {
                if (!existing.contains(code)) {
                    RolePerm rp = new RolePerm();
                    rp.setId(Ids.next("rp"));
                    rp.setRoleId(roleId);
                    rp.setPermCode(code);
                    rp.setDataScope("ALL");
                    rolePermMapper.insert(rp);
                }
            }
            existing.removeAll(wanted);
            if (!existing.isEmpty()) {
                rolePermMapper.delete(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleId, roleId).in(RolePerm::getPermCode, existing));
            }
        }
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().eq(Role::getBuiltin, true)).stream()
                .collect(Collectors.toMap(Role::getCode, Role::getId));
    }

    private void ensureAdmin(String superAdminRoleId) {
        String username = props.getBootstrap().getAdminUsername();
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0) {
            return;
        }
        String password = props.getBootstrap().getAdminPassword();
        boolean generated = !StringUtils.hasText(password);
        if (generated) {
            password = PasswordPolicy.generate();
        }
        Instant now = Instant.now();
        SysUser u = new SysUser();
        u.setId(Ids.next("u"));
        u.setUsername(username);
        u.setName("超级管理员");
        u.setUserType("FULL_TIME");
        u.setStatus(AuthzService.STATUS_ACTIVE);
        u.setPrimaryOrgId(OrgService.ROOT_ID);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setMustChangePassword(props.getBootstrap().isAdminMustChangePassword());
        u.setFailedAttempts(0);
        u.setTokenEpoch(0L);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userMapper.insert(u);
        UserOrg uo = new UserOrg();
        uo.setId(Ids.next("uo"));
        uo.setUserId(u.getId());
        uo.setOrgId(OrgService.ROOT_ID);
        uo.setIsPrimary(true);
        userOrgMapper.insert(uo);
        RoleBinding b = new RoleBinding();
        b.setId(Ids.next("rb"));
        b.setRoleId(superAdminRoleId);
        b.setSubjectType("USER");
        b.setSubjectId(u.getId());
        b.setIncludeChildren(false);
        b.setCreatedBy("system");
        b.setCreatedAt(now);
        bindingMapper.insert(b);
        audit.record(AuditService.Entry.of("SYSTEM_INIT", "创建初始超级管理员", "SUCCESS").target("user", u.getId()));
        if (generated) {
            log.warn("==== 已创建初始超级管理员 账号：{} 初始密码：{} （仅显示一次，首次登录须修改；初始化完成后请改用三员账号并停用超级管理员） ====",
                    username, password);
        } else {
            log.info("已创建初始超级管理员 账号：{}", username);
        }
    }

    private void ensureClient(PdsProperties.ClientDefinition c) {
        if (clientService.exists(c.getClientId())) {
            return;
        }
        String app = StringUtils.hasText(c.getApp()) ? c.getApp() : PdsPermissions.APP;
        if (appMapper.selectById(app) == null) {
            Instant now = Instant.now();
            AppInfo info = new AppInfo();
            info.setCode(app);
            info.setName(app.toUpperCase());
            info.setStatus(1);
            // 应用尚未注册权限清单前不做准入控制；注册清单后自动开启
            info.setAccessControl(false);
            info.setCreatedAt(now);
            info.setUpdatedAt(now);
            appMapper.insert(info);
        }
        clientService.create(new ClientService.Spec(c.getClientId(), c.getClientSecret(),
                StringUtils.hasText(c.getName()) ? c.getName() : c.getClientId(), app, c.getGrantTypes(),
                c.getRedirectUris(), c.getPostLogoutRedirectUris(), c.getScopes(), c.isRequirePkce(),
                c.getAccessTokenTtl(), c.getRefreshTokenTtl(), c.isPublicClient()));
        log.info("已注册客户端 {}（应用 {}）", c.getClientId(), app);
    }
}
