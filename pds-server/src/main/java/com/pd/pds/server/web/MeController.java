package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.entity.SysUser;
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

    public MeController(Guard guard, AuthzService authz, UserService users) {
        this.guard = guard;
        this.authz = authz;
        this.users = users;
    }

    @GetMapping
    public R<UserDtos.Me> me() {
        CurrentActor a = guard.requireUser();
        SysUser u = authz.requireUser(a.userId());
        List<String> perms = authz.effective(a.userId()).forApp(PdsPermissions.APP).permissionCodes().stream().toList();
        return R.ok(new UserDtos.Me(u.getId(), u.getUsername(), u.getName(), u.getScenicId(), u.getPrimaryOrgId(),
                Boolean.TRUE.equals(u.getMustChangePassword()), perms));
    }

    @PostMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody UserDtos.ChangePassword req) {
        CurrentActor a = guard.requireUser();
        users.changeOwnPassword(authz.requireUser(a.userId()).getUsername(), req.oldPassword(), req.newPassword());
        return R.ok();
    }
}
