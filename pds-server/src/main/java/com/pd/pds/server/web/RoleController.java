package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.RoleBinding;
import com.pd.pds.server.service.RoleService;
import com.pd.pds.server.web.dto.RoleDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 角色与角色绑定。 */
@RestController
@RequestMapping("/api/v1")
public class RoleController {

    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping("/roles")
    public R<List<RoleDtos.RoleView>> list() {
        return R.ok(roles.list());
    }

    @GetMapping("/roles/{id}")
    public R<RoleDtos.RoleView> get(@PathVariable String id) {
        return R.ok(roles.get(id));
    }

    @PostMapping("/roles")
    public R<Role> create(@Valid @RequestBody RoleDtos.CreateRole req) {
        return R.ok(roles.create(req));
    }

    @PutMapping("/roles/{id}/permissions")
    public R<RoleDtos.RoleView> setPermissions(@PathVariable String id, @Valid @RequestBody RoleDtos.SetPermissions req) {
        return R.ok(roles.setPermissions(id, req));
    }

    @GetMapping("/roles/{id}/bindings")
    public R<List<RoleDtos.BindingView>> bindings(@PathVariable String id) {
        return R.ok(roles.bindings(id));
    }

    @PostMapping("/roles/{id}/bindings")
    public R<RoleBinding> bind(@PathVariable String id, @Valid @RequestBody RoleDtos.Bind req) {
        return R.ok(roles.bind(id, req));
    }

    @GetMapping("/role-bindings")
    public R<List<RoleDtos.BindingView>> bindingsOfSubject(@org.springframework.web.bind.annotation.RequestParam String subjectType,
                                                           @org.springframework.web.bind.annotation.RequestParam String subjectId) {
        return R.ok(roles.bindingsOfSubject(subjectType, subjectId));
    }

    @DeleteMapping("/role-bindings/{bindingId}")
    public R<Void> unbind(@PathVariable String bindingId) {
        roles.unbind(bindingId);
        return R.ok();
    }
}
