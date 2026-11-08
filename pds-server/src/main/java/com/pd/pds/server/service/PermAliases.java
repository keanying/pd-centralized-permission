package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.server.entity.PermCodeAlias;
import com.pd.pds.server.mapper.PermCodeAliasMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** 权限编码别名解析（按授权版本号缓存）。 */
@Component
public class PermAliases {

    private final PermCodeAliasMapper mapper;
    private final AuthzService authz;
    private volatile long version = -1;
    private volatile Map<String, String> oldToNew = Map.of();

    public PermAliases(PermCodeAliasMapper mapper, AuthzService authz) {
        this.mapper = mapper;
        this.authz = authz;
    }

    private Map<String, String> map() {
        long v = authz.version();
        if (v != version) {
            oldToNew = mapper.selectList(new LambdaQueryWrapper<>()).stream()
                    .collect(Collectors.toMap(PermCodeAlias::getOldCode, PermCodeAlias::getNewCode));
            version = v;
        }
        return oldToNew;
    }

    /** 旧编码解析为现编码（不是别名则原样返回）。 */
    public String resolve(String code) {
        if (code == null) {
            return null;
        }
        return map().getOrDefault(code, code);
    }

    /** 现编码集合对应的全部旧编码（快照中一并返回，保证按旧编码鉴权的系统继续可用）。 */
    public Set<String> aliasesOf(Set<String> codes) {
        Set<String> out = new TreeSet<>();
        map().forEach((o, n) -> {
            if (codes.contains(n)) {
                out.add(o);
            }
        });
        return out;
    }

    public List<PermCodeAlias> ofNew(String newCode) {
        return mapper.selectList(new LambdaQueryWrapper<PermCodeAlias>().eq(PermCodeAlias::getNewCode, newCode));
    }

    public Map<String, String> snapshot() {
        return new HashMap<>(map());
    }
}
