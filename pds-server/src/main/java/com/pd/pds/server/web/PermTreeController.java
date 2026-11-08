package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.service.AppService;
import com.pd.pds.server.service.DistributionService;
import com.pd.pds.server.service.PermNodeTypeService;
import com.pd.pds.server.service.PermTreeService;
import com.pd.pds.server.web.dto.AppDtos;
import com.pd.pds.server.web.dto.DistDtos;
import com.pd.pds.server.web.dto.PermTreeDtos;
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
import java.util.Map;

/** 权限树、节点类型、下发设置。 */
@RestController
@RequestMapping("/api/v1")
public class PermTreeController {

    private final PermTreeService tree;
    private final PermNodeTypeService types;
    private final AppService apps;
    private final DistributionService dist;

    public PermTreeController(PermTreeService tree, PermNodeTypeService types, AppService apps, DistributionService dist) {
        this.tree = tree;
        this.types = types;
        this.apps = apps;
        this.dist = dist;
    }

    // ---------- 权限树

    @GetMapping("/perm-tree/apps")
    public R<List<PermTreeDtos.TreeApp>> treeApps() {
        return R.ok(tree.apps());
    }

    @GetMapping("/perm-tree")
    public R<List<PermTreeDtos.NodeView>> tree(@RequestParam String app) {
        return R.ok(tree.tree(app));
    }

    @GetMapping("/perm-tree/grantable")
    public R<List<PermTreeDtos.NodeView>> grantable(@RequestParam(required = false) String app) {
        return R.ok(tree.grantable(app));
    }

    @GetMapping("/perm-tree/{app}/diff")
    public R<List<PermTreeDtos.Change>> diff(@PathVariable String app) {
        return R.ok(tree.diff(app));
    }

    @GetMapping("/perm-tree/{app}/next-version")
    public R<Map<String, String>> nextVersion(@PathVariable String app) {
        return R.ok(Map.of("version", tree.nextVersionLabel(app)));
    }

    @PostMapping("/perm-tree/{app}/publish")
    public R<PermTreeDtos.VersionView> publish(@PathVariable String app, @RequestBody(required = false) PermTreeDtos.Publish req) {
        return R.ok(tree.publish(app, req));
    }

    @GetMapping("/perm-tree/{app}/versions")
    public R<List<PermTreeDtos.VersionView>> versions(@PathVariable String app) {
        return R.ok(tree.versions(app));
    }

    @GetMapping("/perm-nodes/{id}")
    public R<PermTreeDtos.NodeDetail> node(@PathVariable String id) {
        return R.ok(tree.detail(id));
    }

    @PostMapping("/perm-nodes")
    public R<PermTreeDtos.NodeView> create(@Valid @RequestBody PermTreeDtos.CreateNode req) {
        return R.ok(tree.create(req));
    }

    @PutMapping("/perm-nodes/{id}")
    public R<PermTreeDtos.NodeView> update(@PathVariable String id, @Valid @RequestBody PermTreeDtos.UpdateNode req) {
        return R.ok(tree.update(id, req));
    }

    @PostMapping("/perm-nodes/{id}/disable")
    public R<PermTreeDtos.NodeView> disable(@PathVariable String id) {
        return R.ok(tree.setEnabled(id, false));
    }

    @PostMapping("/perm-nodes/{id}/enable")
    public R<PermTreeDtos.NodeView> enable(@PathVariable String id) {
        return R.ok(tree.setEnabled(id, true));
    }

    @DeleteMapping("/perm-nodes/{id}")
    public R<Void> delete(@PathVariable String id) {
        tree.delete(id);
        return R.ok();
    }

    @PostMapping("/perm-nodes/{id}/discard")
    public R<Void> discard(@PathVariable String id) {
        tree.discard(id);
        return R.ok();
    }

    @GetMapping("/perm-nodes/{id}/holders")
    public R<PermTreeDtos.Holders> holders(@PathVariable String id, @RequestParam(defaultValue = "100") int limit) {
        return R.ok(tree.holders(id, limit));
    }

    @GetMapping("/perm-nodes/{id}/history")
    public R<List<PermTreeDtos.HistoryItem>> history(@PathVariable String id) {
        return R.ok(tree.history(id));
    }

    // ---------- 节点类型

    @GetMapping("/perm-node-types")
    public R<List<PermTreeDtos.NodeTypeView>> types() {
        return R.ok(types.list());
    }

    @PostMapping("/perm-node-types")
    public R<PermTreeDtos.NodeTypeView> createType(@Valid @RequestBody PermTreeDtos.SaveNodeType req) {
        return R.ok(types.create(req));
    }

    @PutMapping("/perm-node-types/{code}")
    public R<PermTreeDtos.NodeTypeView> updateType(@PathVariable String code, @Valid @RequestBody PermTreeDtos.SaveNodeType req) {
        return R.ok(types.update(code, req));
    }

    @DeleteMapping("/perm-node-types/{code}")
    public R<Void> deleteType(@PathVariable String code) {
        types.delete(code);
        return R.ok();
    }

    // ---------- 应用（在权限树中新增）

    @PostMapping("/apps")
    public R<AppDtos.ManifestResult> createApp(@Valid @RequestBody PermTreeDtos.CreateApp req) {
        return R.ok(apps.createApp(req));
    }

    // ---------- 下发

    @GetMapping("/dist/subscriptions")
    public R<List<DistDtos.SubscriptionView>> subscriptions() {
        return R.ok(dist.subscriptions());
    }

    @GetMapping("/dist/subscriptions/{app}")
    public R<DistDtos.SubscriptionView> subscription(@PathVariable String app) {
        return R.ok(dist.subscription(app));
    }

    @PutMapping("/dist/subscriptions/{app}")
    public R<DistDtos.SubscriptionView> saveSubscription(@PathVariable String app, @Valid @RequestBody DistDtos.SaveSubscription req) {
        return R.ok(dist.save(app, req));
    }

    @GetMapping("/dist/deliveries")
    public R<List<DistDtos.DeliveryView>> deliveries(@RequestParam(required = false) String refType,
                                                     @RequestParam(required = false) String refId,
                                                     @RequestParam(required = false) String app,
                                                     @RequestParam(defaultValue = "100") int limit) {
        return R.ok(dist.deliveries(refType, refId, app, limit));
    }

    @GetMapping("/dist/deliveries/{id}/payload")
    public R<DistDtos.EventPayload> payload(@PathVariable String id) {
        return R.ok(dist.payload(id));
    }

    @PostMapping("/dist/deliveries/{id}/retry")
    public R<Void> retry(@PathVariable String id) {
        dist.retry(id);
        return R.ok();
    }
}
