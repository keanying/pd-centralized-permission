package com.pd.pds.server.web;

import com.pd.pds.common.R;
import com.pd.pds.server.service.AuthzApiService;
import com.pd.pds.server.web.dto.AuthzDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 鉴权开放 API（供 PDK、PDA 及其他接入应用调用）。 */
@RestController
@RequestMapping("/api/v1/authz")
public class AuthzController {

    private final AuthzApiService api;

    public AuthzController(AuthzApiService api) {
        this.api = api;
    }

    @PostMapping("/check")
    public R<AuthzDtos.CheckResult> check(@Valid @RequestBody AuthzDtos.CheckRequest req) {
        return R.ok(api.check(req));
    }

    @PostMapping("/batch-check")
    public R<AuthzDtos.BatchCheckResult> batchCheck(@Valid @RequestBody AuthzDtos.BatchCheckRequest req) {
        return R.ok(api.batchCheck(req));
    }

    @GetMapping("/snapshot")
    public R<AuthzDtos.Snapshot> snapshot(@RequestParam String app, @RequestParam String subject) {
        return R.ok(api.snapshot(app, subject));
    }

    @GetMapping("/explain")
    public R<AuthzDtos.CheckResult> explain(@RequestParam String subject,
                                            @RequestParam(required = false) String permission,
                                            @RequestParam(required = false) String resourceType,
                                            @RequestParam(required = false) String resourceId,
                                            @RequestParam(required = false) String action) {
        return R.ok(api.explain(subject, new AuthzDtos.CheckItem(permission, resourceType, resourceId, action)));
    }

    @GetMapping("/resources/accessible")
    public R<Set<String>> accessible(@RequestParam String subject, @RequestParam String type,
                                     @RequestParam(defaultValue = "READ") String action) {
        return R.ok(api.accessible(subject, type, action));
    }
}
