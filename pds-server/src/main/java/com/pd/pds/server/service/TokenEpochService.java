package com.pd.pds.server.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 令牌吊销：人员停用、重置密码、强制下线时递增 token_epoch，早于该时间签发的令牌一律拒绝。
 * 本地缓存 30 秒（本实例的变更立即失效；多实例下最多 30 秒，二期改为事件推送）。
 */
@Service
public class TokenEpochService {

    public record State(boolean active, long epochSeconds) {
    }

    private final SysUserMapper userMapper;
    private final Cache<String, State> cache = Caffeine.newBuilder()
            .maximumSize(50_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public TokenEpochService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public State stateOf(String userId) {
        return cache.get(userId, id -> {
            SysUser u = userMapper.selectById(id);
            if (u == null) {
                return new State(false, Long.MAX_VALUE);
            }
            return new State(AuthzService.STATUS_ACTIVE.equals(u.getStatus()), u.getTokenEpoch() == null ? 0 : u.getTokenEpoch());
        });
    }

    public void evict(String userId) {
        cache.invalidate(userId);
    }
}
