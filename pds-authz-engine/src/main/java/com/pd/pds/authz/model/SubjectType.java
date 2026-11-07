package com.pd.pds.authz.model;

/** 授权主体类型。 */
public enum SubjectType {
    USER("人员"),
    POSITION("岗位"),
    ORG("组织"),
    GROUP("用户组");

    private final String label;

    SubjectType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
