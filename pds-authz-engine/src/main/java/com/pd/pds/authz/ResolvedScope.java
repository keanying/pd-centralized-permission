package com.pd.pds.authz;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 解析后的数据范围（同一权限点多来源取并集）。应用侧据此拼接查询条件：
 * all=true 不加限制；否则满足任一条件即可访问：
 * 本人创建（self）、所属组织 ∈ orgIds、组织路径在 orgTreeIds 子树内、景区 ∈ scenicIds、领域 ∈ domainIds。
 */
public final class ResolvedScope {

    private boolean all;
    private boolean self;
    private final Set<String> orgIds = new TreeSet<>();
    private final Set<String> orgTreeIds = new TreeSet<>();
    private final Set<String> scenicIds = new TreeSet<>();
    private final Set<String> domainIds = new TreeSet<>();

    /** 全部数据。 */
    public static ResolvedScope all() {
        ResolvedScope s = new ResolvedScope();
        s.markAll();
        return s;
    }

    /** 指定景区的数据（未声明数据范围时的安全默认值：本人所属景区）。 */
    public static ResolvedScope scenics(Set<String> scenicIds) {
        ResolvedScope s = new ResolvedScope();
        s.addScenics(scenicIds);
        return s;
    }

    void markAll() {
        all = true;
    }

    void markSelf() {
        self = true;
    }

    void addOrgs(Set<String> ids) {
        orgIds.addAll(ids);
    }

    void addOrgTrees(Set<String> ids) {
        orgTreeIds.addAll(ids);
    }

    void addScenics(Set<String> ids) {
        scenicIds.addAll(ids);
    }

    void addDomains(Set<String> ids) {
        domainIds.addAll(ids);
    }

    public boolean isAll() {
        return all;
    }

    public boolean isSelf() {
        return self;
    }

    public Set<String> getOrgIds() {
        return Collections.unmodifiableSet(orgIds);
    }

    public Set<String> getOrgTreeIds() {
        return Collections.unmodifiableSet(orgTreeIds);
    }

    public Set<String> getScenicIds() {
        return Collections.unmodifiableSet(scenicIds);
    }

    public Set<String> getDomainIds() {
        return Collections.unmodifiableSet(domainIds);
    }

    public boolean isEmpty() {
        return !all && !self && orgIds.isEmpty() && orgTreeIds.isEmpty() && scenicIds.isEmpty() && domainIds.isEmpty();
    }
}
