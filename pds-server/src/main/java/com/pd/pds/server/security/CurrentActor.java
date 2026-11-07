package com.pd.pds.server.security;

import java.util.Set;

/**
 * 当前调用方：人员（userId 不为空）或服务客户端（clientId，client_credentials 令牌）。
 *
 * @param via 代理方客户端（令牌交换签发的票据中的 act），智能体代表用户调用时不为空
 */
public record CurrentActor(String userId, String userName, String clientId, String via, Set<String> scopes, String ip) {

    public boolean isUser() {
        return userId != null;
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    public String auditId() {
        return isUser() ? userId : "client:" + clientId;
    }
}
