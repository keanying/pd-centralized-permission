package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermResource;
import com.pd.pds.server.service.AppService;
import com.pd.pds.server.web.dto.AppDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 接入应用：权限清单、资源实例、资源授权、OAuth 客户端。 */
@RestController
@RequestMapping("/api/v1")
public class AppController {

    private final AppService apps;

    @org.springframework.beans.factory.annotation.Autowired
    private com.pd.pds.server.service.AppAdminService appAdmin;

    public AppController(AppService apps) {
        this.apps = apps;
    }

    @org.springframework.web.bind.annotation.PutMapping("/apps/{app}")
    public R<com.pd.pds.server.entity.AppInfo> updateApp(@PathVariable String app, @RequestBody AppDtos.UpdateApp req) {
        return R.ok(appAdmin.update(app, req));
    }

    @GetMapping("/apps/{app}/delete-impact")
    public R<java.util.Map<String, Long>> deleteImpact(@PathVariable String app) {
        return R.ok(appAdmin.deleteImpact(app));
    }

    @DeleteMapping("/apps/{app}")
    public R<Void> deleteApp(@PathVariable String app) {
        appAdmin.delete(app);
        return R.ok();
    }

    @GetMapping("/apps")
    public R<List<AppDtos.AppView>> apps() {
        return R.ok(apps.apps());
    }

    @GetMapping("/apps/{app}/resource-types")
    public R<List<com.pd.pds.server.entity.ResourceType>> resourceTypes(@PathVariable String app) {
        return R.ok(apps.resourceTypes(app));
    }

    @GetMapping("/resource-types")
    public R<List<com.pd.pds.server.entity.ResourceType>> allResourceTypes() {
        return R.ok(apps.resourceTypes(null));
    }

    @GetMapping("/apps/{app}/clients")
    public R<List<AppDtos.ClientView>> clients(@PathVariable String app) {
        return R.ok(apps.clients(app));
    }

    @GetMapping("/resources")
    public R<com.pd.pds.common.PageResult<AppDtos.ResourceView>> resources(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String type,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String keyword,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") long page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") long pageSize) {
        return R.ok(apps.resources(type, keyword, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/apps/{app}/permissions")
    public R<List<PermPoint>> permissions(@PathVariable String app) {
        return R.ok(apps.permissions(app));
    }

    @PostMapping("/apps/{app}/manifest")
    public R<AppDtos.ManifestResult> manifest(@PathVariable String app, @Valid @RequestBody AppDtos.Manifest manifest) {
        return R.ok(apps.registerManifest(app, manifest));
    }

    @PostMapping("/apps/{app}/clients")
    public R<AppDtos.ClientCredential> createClient(@PathVariable String app, @Valid @RequestBody AppDtos.CreateClient req) {
        return R.ok(apps.createClient(app, req));
    }

    @PostMapping("/resources")
    public R<PermResource> register(@Valid @RequestBody AppDtos.RegisterResource req) {
        return R.ok(apps.registerResource(req));
    }

    @DeleteMapping("/resources/{type}/{refId}")
    public R<Void> unregister(@PathVariable String type, @PathVariable String refId) {
        apps.deleteResource(type, refId);
        return R.ok();
    }

    @GetMapping("/resources/{type}/{refId}/grants")
    public R<List<AppDtos.GrantView>> grants(@PathVariable String type, @PathVariable String refId) {
        return R.ok(apps.grants(type, refId));
    }

    @PostMapping("/resources/{type}/{refId}/grants")
    public R<com.pd.pds.server.entity.ResourceGrant> addGrant(@PathVariable String type, @PathVariable String refId,
                                     @Valid @RequestBody AppDtos.GrantRequest req) {
        return R.ok(apps.addGrant(type, refId, req));
    }

    @DeleteMapping("/resources/{type}/{refId}/grants/{grantId}")
    public R<Void> removeGrant(@PathVariable String type, @PathVariable String refId, @PathVariable String grantId) {
        apps.removeGrant(type, refId, grantId);
        return R.ok();
    }
}
