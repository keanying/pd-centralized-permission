package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.service.AppCategories;
import com.pd.pds.server.service.AppService;
import com.pd.pds.server.web.dto.AppDtos;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.service.AuthzService;
import com.pd.pds.server.service.UserService;
import com.pd.pds.server.web.dto.UserDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 个人中心。 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final Guard guard;
    private final AuthzService authz;
    private final UserService users;
    private final AppInfoMapper appMapper;

    public MeController(Guard guard, AuthzService authz, UserService users, AppInfoMapper appMapper) {
        this.guard = guard;
        this.authz = authz;
        this.users = users;
        this.appMapper = appMapper;
    }

    @GetMapping
    public R<UserDtos.Me> me() {
        CurrentActor a = guard.requireUser();
        SysUser u = authz.requireUser(a.userId());
        List<String> perms = authz.effective(a.userId()).forApp(PdsPermissions.APP).permissionCodes().stream().toList();
        return R.ok(new UserDtos.Me(u.getId(), u.getUsername(), u.getName(), u.getScenicId(), u.getPrimaryOrgId(),
                Boolean.TRUE.equals(u.getMustChangePassword()), perms));
    }

    /** 本人可进入的应用：未开启准入控制，或拥有该应用的“登录进入”权限。供各门户展示应用入口。 */
    @GetMapping("/apps")
    public R<List<AppDtos.MyApp>> apps() {
        CurrentActor a = guard.requireUser();
        EffectivePermissions eff = authz.effective(a.userId());
        return R.ok(appMapper.selectList(new LambdaQueryWrapper<AppInfo>().eq(AppInfo::getStatus, 1).orderByAsc(AppInfo::getCode)).stream()
                .filter(app -> !Boolean.TRUE.equals(app.getAccessControl()) || eff.has(AppService.accessPermission(app.getCode())))
                .map(app -> new AppDtos.MyApp(app.getCode(), app.getName(), app.getDescription(), app.getCategory(),
                        app.getCategory() == null ? "未分类" : AppCategories.ALL.getOrDefault(app.getCategory(), app.getCategory()),
                        app.getHomeUrl()))
                .toList());
    }

    @PostMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody UserDtos.ChangePassword req) {
        CurrentActor a = guard.requireUser();
        users.changeOwnPassword(authz.requireUser(a.userId()).getUsername(), req.oldPassword(), req.newPassword());
        return R.ok();
    }
}
