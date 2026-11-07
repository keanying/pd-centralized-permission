package com.pd.pds.server.auth;

import com.pd.pds.server.service.TokenEpochService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 人员令牌吊销校验：账号非在职，或令牌签发早于 token_epoch，一律拒绝。
 */
@Component
public class TokenEpochValidator implements OAuth2TokenValidator<Jwt> {

    private final TokenEpochService epochs;

    public TokenEpochValidator(TokenEpochService epochs) {
        this.epochs = epochs;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String uid = jwt.getClaimAsString(PdsTokenCustomizer.CLAIM_UID);
        if (uid == null) {
            return OAuth2TokenValidatorResult.success();
        }
        TokenEpochService.State state = epochs.stateOf(uid);
        if (!state.active()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "账号已停用", null));
        }
        if (jwt.getIssuedAt() != null && jwt.getIssuedAt().getEpochSecond() < state.epochSeconds()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "登录凭证已失效，请重新登录", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
