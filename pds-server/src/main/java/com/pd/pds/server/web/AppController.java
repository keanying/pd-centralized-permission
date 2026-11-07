package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.PermPoint;
import com.pd.pds.server.entity.PermResource;
import com.pd.pds.server.entity.ResourceGrant;
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

    public AppController(AppService apps) {
        this.apps = apps;
    }

    @GetMapping("/apps")
    public R<List<AppInfo>> apps() {
        return R.ok(apps.apps());
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
    public R<List<ResourceGrant>> grants(@PathVariable String type, @PathVariable String refId) {
        return R.ok(apps.grants(type, refId));
    }

    @PostMapping("/resources/{type}/{refId}/grants")
    public R<ResourceGrant> addGrant(@PathVariable String type, @PathVariable String refId,
                                     @Valid @RequestBody AppDtos.GrantRequest req) {
        return R.ok(apps.addGrant(type, refId, req));
    }

    @DeleteMapping("/resources/{type}/{refId}/grants/{grantId}")
    public R<Void> removeGrant(@PathVariable String type, @PathVariable String refId, @PathVariable String grantId) {
        apps.removeGrant(type, refId, grantId);
        return R.ok();
    }
}
