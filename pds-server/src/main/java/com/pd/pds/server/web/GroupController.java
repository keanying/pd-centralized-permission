package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.service.GroupService;
import com.pd.pds.server.web.dto.GroupDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户组。 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    public R<List<GroupDtos.GroupView>> list() {
        return R.ok(groups.list());
    }

    @PostMapping
    public R<UserGroup> create(@Valid @RequestBody GroupDtos.CreateGroup req) {
        return R.ok(groups.create(req));
    }

    @GetMapping("/{id}/members")
    public R<List<java.util.Map<String, String>>> listMembers(@PathVariable String id) {
        return R.ok(groups.members(id));
    }

    @PutMapping("/{id}/members")
    public R<Void> members(@PathVariable String id, @Valid @RequestBody GroupDtos.SetMembers req) {
        groups.setMembers(id, req);
        return R.ok();
    }
}
