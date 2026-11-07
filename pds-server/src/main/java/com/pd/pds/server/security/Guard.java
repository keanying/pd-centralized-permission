package com.pd.pds.server.security;

import com.pd.pds.authz.EffectivePermissions;
import com.pd.pds.authz.ResolvedScope;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.service.AuditService;
import com.pd.pds.server.service.AuthzService;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/**
 * 管理接口的权限守卫：功能权限、数据范围、资源权限。拒绝时写入"越权拦截"审计。
 */
@Component
public class Guard {

    private final ActorResolver actors;
    private final AuthzService authz;
    private final AuditService audit;

    public Guard(ActorResolver actors, AuthzService authz, AuditService audit) {
        this.actors = actors;
        this.authz = authz;
        this.audit = audit;
    }

    public CurrentActor actor() {
        return actors.current();
    }

    /** 要求调用方是人员（非服务客户端）。 */
    public CurrentActor requireUser() {
        CurrentActor a = actors.current();
        if (!a.isUser()) {
            throw new BizException(ErrorCode.FORBIDDEN, "此接口仅允许人员登录后调用");
        }
        return a;
    }

    public EffectivePermissions permissions() {
        return authz.effective(requireUser().userId());
    }

    public boolean has(String permCode) {
        CurrentActor a = actors.current();
        return a.isUser() && authz.effective(a.userId()).has(permCode);
    }

    /**
     * 要求当前人员拥有功能权限，返回该权限的数据范围。
     * 角色上未声明数据范围时，按"本人景区范围"处理（安全默认值），不会视为全部数据。
     */
    public ResolvedScope require(String permCode) {
        CurrentActor a = requireUser();
        EffectivePermissions eff = authz.effective(a.userId());
        if (!eff.has(permCode)) {
            deny(a, permCode, eff.explain(permCode).reason());
            throw new BizException(ErrorCode.PERMISSION_DENIED, "无此功能权限：" + PdsPermissions.ALL.getOrDefault(permCode, permCode));
        }
        ResolvedScope scope = eff.scopeOf(permCode);
        return scope == null || scope.isEmpty() ? ResolvedScope.scenics(eff.scenicScope()) : scope;
    }

    public void requireResource(ResourceKey key, ResourceAction action) {
        CurrentActor a = requireUser();
        if (!authz.effective(a.userId()).canAccess(key, action)) {
            deny(a, key + "#" + action, "资源权限不足");
            throw new BizException(ErrorCode.RESOURCE_DENIED, "无此资源的" + action.label() + "权限");
        }
    }

    /** 数据范围是否覆盖某组织节点（ALL / 景区 / 组织 / 组织子树）。 */
    public static boolean covers(ResolvedScope scope, OrgUnit org) {
        if (scope.isAll()) {
            return true;
        }
        if (org.getScenicId() != null && scope.getScenicIds().contains(org.getScenicId())) {
            return true;
        }
        if (scope.getOrgIds().contains(org.getId())) {
            return true;
        }
        return Arrays.stream(org.getPath().split("/")).anyMatch(scope.getOrgTreeIds()::contains);
    }

    public void requireCovers(ResolvedScope scope, OrgUnit org, String permCode) {
        if (!covers(scope, org)) {
            CurrentActor a = actors.current();
            deny(a, permCode + "@" + org.getId(), "超出数据范围");
            throw new BizException(ErrorCode.SCENIC_DENIED, "组织【" + org.getName() + "】不在您的管理范围内");
        }
    }

    private void deny(CurrentActor a, String target, String reason) {
        audit.recordQuietly(AuditService.Entry.of("ACCESS_DENIED", target, "DENY")
                .actor(a.auditId(), a.userName(), a.ip())
                .detail(Map.of("reason", reason)));
    }
}
