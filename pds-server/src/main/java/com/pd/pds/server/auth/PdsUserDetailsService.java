package com.pd.pds.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.service.AuthzService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 登录账号加载。主体使用 Spring Security 标准 User（可被授权记录的 JSON 序列化白名单接受），
 * 人员详细信息在签发令牌时按账号从数据库读取。
 */
@Service
public class PdsUserDetailsService implements UserDetailsService {

    private final SysUserMapper userMapper;

    public PdsUserDetailsService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (u == null) {
            throw new UsernameNotFoundException("账号或密码错误");
        }
        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now());
        return User.withUsername(u.getUsername())
                .password(u.getPasswordHash())
                .disabled(!AuthzService.STATUS_ACTIVE.equals(u.getStatus()))
                .accountLocked(locked)
                .authorities("ROLE_USER")
                .build();
    }
}
