package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.entity.Position;
import com.pd.pds.server.entity.UserGroup;
import com.pd.pds.server.service.PeopleAdminService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 人员管理：岗位、用户组维护。 */
@RestController
@RequestMapping("/api/v1")
public class PeopleController {

    private final PeopleAdminService people;

    public PeopleController(PeopleAdminService people) {
        this.people = people;
    }

    @GetMapping("/positions/stats")
    public R<List<PeopleAdminService.PositionView>> positions() {
        return R.ok(people.positions());
    }

    @PutMapping("/positions/{id}")
    public R<Position> updatePosition(@PathVariable String id, @RequestBody PeopleAdminService.SavePosition req) {
        return R.ok(people.updatePosition(id, req));
    }

    @DeleteMapping("/positions/{id}")
    public R<Void> deletePosition(@PathVariable String id) {
        people.deletePosition(id);
        return R.ok();
    }

    @PutMapping("/groups/{id}")
    public R<UserGroup> updateGroup(@PathVariable String id, @RequestBody PeopleAdminService.SaveGroup req) {
        return R.ok(people.updateGroup(id, req));
    }

    @DeleteMapping("/groups/{id}")
    public R<Void> deleteGroup(@PathVariable String id) {
        people.deleteGroup(id);
        return R.ok();
    }
}
