package com.pd.pds.authz.model;

import java.util.Map;
import java.util.Set;

/**
 * 计算有效权限所需的人员画像。
 *
 * @param orgIds         直接所在的组织（含兼职）
 * @param ancestorOrgIds 直接所在组织的全部上级组织（不含自身）
 * @param scenicScope    可访问景区（已由服务层结合景区范围配置计算）
 * @param subjectNames   用于权限解释的显示名称，键为 {@link SubjectRef#key()}
 */
public record UserProfile(
        String userId,
        String name,
        String primaryOrgId,
        String scenicId,
        Set<String> orgIds,
        Set<String> ancestorOrgIds,
        Set<String> positionIds,
        Set<String> groupIds,
        Set<String> scenicScope,
        Map<String, String> subjectNames) {

    public UserProfile {
        orgIds = copy(orgIds);
        ancestorOrgIds = copy(ancestorOrgIds);
        positionIds = copy(positionIds);
        groupIds = copy(groupIds);
        scenicScope = copy(scenicScope);
        subjectNames = subjectNames == null ? Map.of() : Map.copyOf(subjectNames);
    }

    private static Set<String> copy(Set<String> s) {
        return s == null ? Set.of() : Set.copyOf(s);
    }

    /** 判断某授权对象是否覆盖此人员。 */
    public boolean matches(SubjectRef subject) {
        return switch (subject.type()) {
            case USER -> userId.equals(subject.id());
            case POSITION -> positionIds.contains(subject.id());
            case GROUP -> groupIds.contains(subject.id());
            case ORG -> orgIds.contains(subject.id())
                    || (subject.includeChildren() && ancestorOrgIds.contains(subject.id()));
        };
    }

    /** 授权对象的显示名称（无名称时为 ID）。 */
    public String nameOf(SubjectRef subject) {
        return subjectNames.getOrDefault(subject.key(), subject.id());
    }

    public String label(SubjectRef subject) {
        String name = subjectNames.getOrDefault(subject.key(), subject.id());
        String suffix = subject.type() == SubjectType.ORG && subject.includeChildren() ? "（含下级）" : "";
        return subject.type().label() + "【" + name + "】" + suffix;
    }
}
