package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.service.OrgService;
import com.pd.pds.server.web.dto.OrgDtos;
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

/** 组织架构与岗位。 */
@RestController
@RequestMapping("/api/v1")
public class OrgController {

    private final OrgService orgs;

    public OrgController(OrgService orgs) {
        this.orgs = orgs;
    }

    @GetMapping("/orgs/tree")
    public R<List<OrgDtos.OrgNode>> tree() {
        return R.ok(orgs.tree());
    }

    @PostMapping("/orgs")
    public R<OrgUnit> create(@Valid @RequestBody OrgDtos.CreateOrg req) {
        return R.ok(orgs.create(req));
    }

    @PutMapping("/orgs/{id}")
    public R<OrgUnit> update(@PathVariable String id, @RequestBody OrgDtos.UpdateOrg req) {
        return R.ok(orgs.update(id, req));
    }

    @DeleteMapping("/orgs/{id}")
    public R<Void> delete(@PathVariable String id) {
        orgs.delete(id);
        return R.ok();
    }

    @GetMapping("/positions")
    public R<List<Position>> positions(@RequestParam(required = false) String scenicId) {
        return R.ok(orgs.positions(scenicId));
    }

    @PostMapping("/positions")
    public R<Position> createPosition(@Valid @RequestBody OrgDtos.CreatePosition req) {
        return R.ok(orgs.createPosition(req));
    }
}
