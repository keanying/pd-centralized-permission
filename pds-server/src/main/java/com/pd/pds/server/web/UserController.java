package com.pd.pds.server.web;

import com.pd.pds.common.PageResult;
import com.pd.pds.common.R;
import com.pd.pds.server.entity.SecurityLevelSetting;
import com.pd.pds.server.entity.UserPerm;
import com.pd.pds.server.service.GrantService;
import com.pd.pds.server.service.UserService;
import com.pd.pds.server.web.dto.RoleDtos;
import com.pd.pds.server.web.dto.UserDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 人员账号与人员明细权限。 */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserService users;
    private final GrantService grants;

    public UserController(UserService users, GrantService grants) {
        this.users = users;
        this.grants = grants;
    }

    @GetMapping("/users")
    public R<PageResult<UserDtos.UserView>> page(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String orgId,
                                                 @RequestParam(defaultValue = "true") boolean includeChildren,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        return R.ok(users.page(keyword, orgId, includeChildren, status, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/users/{id}")
    public R<UserDtos.UserView> get(@PathVariable String id) {
        return R.ok(users.get(id));
    }

    @PostMapping("/users")
    public R<UserDtos.Credential> create(@Valid @RequestBody UserDtos.CreateUser req) {
        return R.ok(users.create(req));
    }

    @PutMapping("/users/{id}")
    public R<UserDtos.UserView> update(@PathVariable String id, @RequestBody UserDtos.UpdateUser req) {
        return R.ok(users.update(id, req));
    }

    @PutMapping("/users/{id}/orgs")
    public R<UserDtos.UserView> assignOrgs(@PathVariable String id, @Valid @RequestBody UserDtos.AssignOrgs req) {
        return R.ok(users.assignOrgs(id, req));
    }

    @PostMapping("/users/{id}/disable")
    public R<Void> disable(@PathVariable String id, @RequestBody(required = false) UserDtos.StatusChange req) {
        users.disable(id, "DISABLED", req == null ? null : req.reason());
        return R.ok();
    }

    @PostMapping("/users/{id}/leave")
    public R<Void> leave(@PathVariable String id, @RequestBody(required = false) UserDtos.StatusChange req) {
        users.disable(id, "LEFT", req == null ? null : req.reason());
        return R.ok();
    }

    @PostMapping("/users/{id}/enable")
    public R<Void> enable(@PathVariable String id) {
        users.enable(id);
        return R.ok();
    }

    @PostMapping("/users/{id}/reset-password")
    public R<UserDtos.Credential> resetPassword(@PathVariable String id) {
        return R.ok(users.resetPassword(id));
    }

    // ---------------- 人员明细权限

    @GetMapping("/users/{id}/effective")
    public R<RoleDtos.UserEffective> effective(@PathVariable String id, @RequestParam String app) {
        return R.ok(grants.effectiveView(id, app));
    }

    @GetMapping("/users/{id}/perms")
    public R<List<UserPerm>> userPerms(@PathVariable String id) {
        return R.ok(grants.listUserPerms(id));
    }

    @PostMapping("/users/{id}/perms")
    public R<UserPerm> addUserPerm(@PathVariable String id, @Valid @RequestBody RoleDtos.UserPermRequest req) {
        return R.ok(grants.addUserPerm(id, req));
    }

    @DeleteMapping("/users/{id}/perms/{permId}")
    public R<Void> deleteUserPerm(@PathVariable String id, @PathVariable String permId) {
        grants.deleteUserPerm(id, permId);
        return R.ok();
    }

    @PutMapping("/users/{id}/scenic-scope")
    public R<Set<String>> scenicScope(@PathVariable String id, @Valid @RequestBody RoleDtos.ScenicScope req) {
        return R.ok(grants.setScenicScope(id, req.scenicIds()));
    }

    @PutMapping("/security-levels")
    public R<SecurityLevelSetting> securityLevel(@Valid @RequestBody RoleDtos.SecurityLevelRequest req) {
        return R.ok(grants.setSecurityLevel(req));
    }
}
