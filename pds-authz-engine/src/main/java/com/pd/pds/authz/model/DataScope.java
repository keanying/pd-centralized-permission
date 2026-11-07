package com.pd.pds.authz.model;

import java.util.Set;

/**
 * 角色或人员明细上声明的数据范围。values 用于 SCENIC_LIST（景区 ID）、CUSTOM（组织 ID）、DOMAIN（领域节点 ID）。
 */
public record DataScope(DataScopeType type, Set<String> values) {

    public DataScope {
        values = values == null ? Set.of() : Set.copyOf(values);
    }

    public static DataScope of(DataScopeType type) {
        return new DataScope(type, Set.of());
    }

    public static DataScope of(DataScopeType type, Set<String> values) {
        return new DataScope(type, values);
    }
}
