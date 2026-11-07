package com.pd.pds.authz.model;

/**
 * 授权对象引用。includeChildren 仅对 ORG 有效：为 true 时该组织的所有下级组织成员也匹配。
 */
public record SubjectRef(SubjectType type, String id, boolean includeChildren) {

    public static SubjectRef user(String id) {
        return new SubjectRef(SubjectType.USER, id, false);
    }

    public static SubjectRef position(String id) {
        return new SubjectRef(SubjectType.POSITION, id, false);
    }

    public static SubjectRef org(String id, boolean includeChildren) {
        return new SubjectRef(SubjectType.ORG, id, includeChildren);
    }

    public static SubjectRef group(String id) {
        return new SubjectRef(SubjectType.GROUP, id, false);
    }

    public String key() {
        return type.name() + ":" + id;
    }
}
