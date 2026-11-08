package com.pd.pds.server.web;

import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.PageResult;
import com.pd.pds.common.R;
import com.pd.pds.server.entity.AuditEvent;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.service.AuditService;
import com.pd.pds.server.service.ClientService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 审计中心：查询、完整性校验、应用上报。 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    public static final String SCOPE_AUDIT = "pds.audit";

    private final AuditService audit;
    private final Guard guard;
    private final ClientService clients;

    public AuditController(AuditService audit, Guard guard, ClientService clients) {
        this.audit = audit;
        this.guard = guard;
        this.clients = clients;
    }

    @GetMapping("/events")
    public R<PageResult<AuditEvent>> events(@RequestParam(required = false) String userId,
                                            @RequestParam(required = false) String eventType,
                                            @RequestParam(required = false) String app,
                                            @RequestParam(required = false) String result,
                                            @RequestParam(required = false) String scenicId,
                                            @RequestParam(required = false) Instant from,
                                            @RequestParam(required = false) Instant to,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long pageSize) {
        guard.require(PdsPermissions.AUDIT_VIEW);
        return R.ok(audit.query(userId, eventType, app, result, scenicId, from, to, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/events/{id}/verify")
    public R<AuditService.VerifyResult> verifyOne(@org.springframework.web.bind.annotation.PathVariable String id) {
        guard.require(PdsPermissions.AUDIT_VIEW);
        return R.ok(audit.verifyOne(id));
    }

    @GetMapping("/verify")
    public R<AuditService.VerifyResult> verify() {
        guard.require(PdsPermissions.AUDIT_VIEW);
        return R.ok(audit.verify());
    }

    public record ReportedEvent(@NotBlank String eventType, String userId, String userName, String via, String scenicId,
                                String ip, String targetType, String targetIds, @NotBlank String action,
                                @NotBlank String result, Map<String, Object> detail, String traceId) {
    }

    public record ReportRequest(@NotEmpty @Size(max = 500) List<@Valid ReportedEvent> events) {
    }

    /** 接入应用批量上报审计事件（客户端令牌，scope=pds.audit）。事件的 app 以客户端所属应用为准。 */
    @PostMapping("/events")
    public R<Map<String, Integer>> report(@Valid @RequestBody ReportRequest req) {
        CurrentActor a = guard.actor();
        if (a.isUser() || !a.hasScope(SCOPE_AUDIT)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅接入应用的客户端（scope=" + SCOPE_AUDIT + "）可上报审计事件");
        }
        String app = clients.appOf(a.clientId());
        if (app == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "客户端未关联应用");
        }
        for (ReportedEvent e : req.events()) {
            audit.record(new AuditService.Entry(app, e.eventType(), e.userId(), e.userName(), e.via(), e.scenicId(),
                    e.ip(), e.targetType(), e.targetIds(), e.action(), e.result(), e.detail(), e.traceId()));
        }
        return R.ok(Map.of("accepted", req.events().size()));
    }
}
