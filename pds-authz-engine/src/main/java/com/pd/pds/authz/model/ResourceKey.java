package com.pd.pds.authz.model;

/** 资源实例标识，如 type=pdk:kb，refId=kb_12。 */
public record ResourceKey(String type, String refId) {

    @Override
    public String toString() {
        return type + ":" + refId;
    }
}
