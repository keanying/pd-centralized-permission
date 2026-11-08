package com.pd.pds.authz;

import com.pd.pds.authz.model.DataScope;
import com.pd.pds.authz.model.DataScopeType;
import com.pd.pds.authz.model.Effect;
import com.pd.pds.authz.model.Facts;
import com.pd.pds.authz.model.ResourceAction;
import com.pd.pds.authz.model.ResourceKey;
import com.pd.pds.authz.model.SubjectRef;
import com.pd.pds.authz.model.UserProfile;
import com.pd.pds.authz.model.Validity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以《PDS 产品功能文档》§5.2 "景区 A 售票员张三" 为基础场景。
 */
class EffectivePermissionCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");
    private static final String KB = "pdk:kb";

    private final EffectivePermissionCalculator calculator = new EffectivePermissionCalculator();

    private final UserProfile zhangsan = new UserProfile(
            "u_zs", "张三", "D_TICKET", "S_A",
            Set.of("D_TICKET"), Set.of("S_A", "G"),
            Set.of("P_SELLER"), Set.of("GRP_KNOWLEDGE"),
            Set.of("S_A"),
            Map.of("POSITION:P_SELLER", "售票员", "ORG:S_A", "景区A", "ORG:D_TICKET", "票务部"));

    private final Map<String, Facts.Role> roles = Map.of(
            "R_PDA_USER", new Facts.Role("R_PDA_USER", "PDA普通用户", List.of(
                    new Facts.RolePermission("pda:chat:use", null),
                    new Facts.RolePermission("pda:tool:pdk_search", null),
                    new Facts.RolePermission("pda:tool:pdk_get_context", null),
                    new Facts.RolePermission("pdk:doc:edit", DataScope.of(DataScopeType.SELF)))),
            "R_DEPT_EDITOR", new Facts.Role("R_DEPT_EDITOR", "部门知识编辑", List.of(
                    new Facts.RolePermission("pdk:doc:edit", DataScope.of(DataScopeType.DEPT)))),
            "R_AUDITOR", new Facts.Role("R_AUDITOR", "审计员", List.of(
                    new Facts.RolePermission("pds:audit:view", DataScope.of(DataScopeType.ALL)))));

    private final Map<ResourceKey, Facts.Resource> resources = Map.of(
            kb("ticket"), new Facts.Resource(kb("ticket"), "票务知识库", "S_A"),
            kb("service"), new Facts.Resource(kb("service"), "游客服务知识库", "S_A"),
            kb("finance"), new Facts.Resource(kb("finance"), "内部财务制度库", "S_A"),
            kb("common"), new Facts.Resource(kb("common"), "集团通用制度库", "GROUP"),
            kb("b_ticket"), new Facts.Resource(kb("b_ticket"), "景区B票务库", "S_B"));

    private static ResourceKey kb(String id) {
        return new ResourceKey(KB, id);
    }

    private Facts.Bundle bundle(List<Facts.RoleBinding> bindings, List<Facts.UserPermission> ups,
                                List<Facts.ResourceGrant> grants, List<Facts.SecurityLevel> levels) {
        return new Facts.Bundle(zhangsan, roles, bindings, ups, resources, grants, levels, Set.of());
    }

    private List<Facts.RoleBinding> baseBindings() {
        return new ArrayList<>(List.of(
                new Facts.RoleBinding("R_PDA_USER", SubjectRef.position("P_SELLER"), Validity.ALWAYS)));
    }

    private List<Facts.ResourceGrant> baseGrants() {
        return new ArrayList<>(List.of(
                new Facts.ResourceGrant(kb("ticket"), SubjectRef.org("S_A", true), ResourceAction.READ, Effect.ALLOW, Validity.ALWAYS),
                new Facts.ResourceGrant(kb("service"), SubjectRef.org("S_A", true), ResourceAction.READ, Effect.ALLOW, Validity.ALWAYS),
                new Facts.ResourceGrant(kb("finance"), SubjectRef.org("S_A", true), ResourceAction.READ, Effect.ALLOW, Validity.ALWAYS),
                new Facts.ResourceGrant(kb("common"), SubjectRef.org("G", true), ResourceAction.READ, Effect.ALLOW, Validity.ALWAYS)));
    }

    @Test
    void 岗位角色授予的功能权限生效并可解释来源() {
        EffectivePermissions eff = calculator.compute(bundle(baseBindings(), List.of(), List.of(), List.of()), NOW);

        assertThat(eff.permissionCodes()).contains("pda:chat:use", "pda:tool:pdk_search", "pdk:doc:edit");
        assertThat(eff.has("pda:tool:terminal")).isFalse();
        Decision d = eff.explain("pda:tool:pdk_search");
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).contains("角色【PDA普通用户】经 岗位【售票员】 授予");
        assertThat(eff.explain("pda:tool:terminal").reason()).startsWith("未授予");
    }

    @Test
    void 人员明细直接授予与禁止_禁止优先() {
        List<Facts.UserPermission> ups = List.of(
                new Facts.UserPermission("1", "pdk:update:submit", null, null, Effect.ALLOW, null, Validity.ALWAYS, "票务知识联络人"),
                new Facts.UserPermission("2", "pda:tool:pdk_get_context", null, null, Effect.DENY, null, Validity.ALWAYS, "试点期间不开放"));
        EffectivePermissions eff = calculator.compute(bundle(baseBindings(), ups, List.of(), List.of()), NOW);

        assertThat(eff.has("pdk:update:submit")).isTrue();
        assertThat(eff.explain("pdk:update:submit").reason()).contains("人员明细直接授予（原因：票务知识联络人）");
        assertThat(eff.has("pda:tool:pdk_get_context")).isFalse();
        Decision denied = eff.explain("pda:tool:pdk_get_context");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).startsWith("禁止").contains("试点期间不开放").contains("PDA普通用户");
    }

    @Test
    void 组织含下级继承_不含下级时不继承() {
        List<Facts.RoleBinding> bindings = List.of(
                new Facts.RoleBinding("R_AUDITOR", SubjectRef.org("S_A", false), Validity.ALWAYS));
        assertThat(calculator.compute(bundle(bindings, List.of(), List.of(), List.of()), NOW).has("pds:audit:view")).isFalse();

        bindings = List.of(new Facts.RoleBinding("R_AUDITOR", SubjectRef.org("S_A", true), Validity.ALWAYS));
        EffectivePermissions eff = calculator.compute(bundle(bindings, List.of(), List.of(), List.of()), NOW);
        assertThat(eff.has("pds:audit:view")).isTrue();
        assertThat(eff.explain("pds:audit:view").reason()).contains("组织【景区A】（含下级）");
    }

    @Test
    void 用户组绑定生效() {
        List<Facts.RoleBinding> bindings = List.of(
                new Facts.RoleBinding("R_AUDITOR", SubjectRef.group("GRP_KNOWLEDGE"), Validity.ALWAYS));
        assertThat(calculator.compute(bundle(bindings, List.of(), List.of(), List.of()), NOW).has("pds:audit:view")).isTrue();
    }

    @Test
    void 有效期外的授权被忽略() {
        Validity expired = new Validity(NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)));
        Validity future = new Validity(NOW.plus(Duration.ofDays(1)), null);
        List<Facts.RoleBinding> bindings = List.of(
                new Facts.RoleBinding("R_AUDITOR", SubjectRef.user("u_zs"), expired),
                new Facts.RoleBinding("R_DEPT_EDITOR", SubjectRef.user("u_zs"), future));
        EffectivePermissions eff = calculator.compute(bundle(bindings, List.of(), List.of(), List.of()), NOW);
        assertThat(eff.has("pds:audit:view")).isFalse();
        assertThat(eff.has("pdk:doc:edit")).isFalse();
    }

    @Test
    void 数据范围多来源取并集() {
        List<Facts.RoleBinding> bindings = baseBindings();
        bindings.add(new Facts.RoleBinding("R_DEPT_EDITOR", SubjectRef.org("D_TICKET", false), Validity.ALWAYS));
        EffectivePermissions eff = calculator.compute(bundle(bindings, List.of(), List.of(), List.of()), NOW);

        ResolvedScope scope = eff.scopeOf("pdk:doc:edit");
        assertThat(scope.isSelf()).isTrue();
        assertThat(scope.getOrgIds()).containsExactly("D_TICKET");
        assertThat(scope.isAll()).isFalse();
        assertThat(eff.dataScopes()).containsKey("pdk:doc:edit").doesNotContainKey("pda:chat:use");
    }

    @Test
    void 资源授权_景区约束_集团共享_人员禁止() {
        List<Facts.ResourceGrant> grants = baseGrants();
        // 越权配置：景区 B 的库授权给了张三本人，但张三不在景区 B 范围内
        grants.add(new Facts.ResourceGrant(kb("b_ticket"), SubjectRef.user("u_zs"), ResourceAction.READ, Effect.ALLOW, Validity.ALWAYS));
        List<Facts.UserPermission> ups = List.of(
                new Facts.UserPermission("3", null, kb("finance"), ResourceAction.READ, Effect.DENY, null, Validity.ALWAYS, "非财务人员"));

        EffectivePermissions eff = calculator.compute(bundle(baseBindings(), ups, grants, List.of()), NOW);

        assertThat(eff.accessible(KB, ResourceAction.READ)).containsExactlyInAnyOrder("ticket", "service", "common");
        assertThat(eff.canAccess(kb("finance"), ResourceAction.READ)).isFalse();
        assertThat(eff.explain(kb("finance"), ResourceAction.READ).reason()).contains("人员明细禁止（原因：非财务人员）");
        assertThat(eff.canAccess(kb("b_ticket"), ResourceAction.READ)).isFalse();
        assertThat(eff.explain(kb("b_ticket"), ResourceAction.READ).reason()).contains("景区范围");
    }

    @Test
    void 资源动作层级_授予管理包含编辑查看_禁止查看连带禁止更高动作() {
        List<Facts.ResourceGrant> grants = List.of(
                new Facts.ResourceGrant(kb("ticket"), SubjectRef.user("u_zs"), ResourceAction.ADMIN, Effect.ALLOW, Validity.ALWAYS),
                new Facts.ResourceGrant(kb("service"), SubjectRef.user("u_zs"), ResourceAction.ADMIN, Effect.ALLOW, Validity.ALWAYS),
                new Facts.ResourceGrant(kb("service"), SubjectRef.position("P_SELLER"), ResourceAction.WRITE, Effect.DENY, Validity.ALWAYS));
        EffectivePermissions eff = calculator.compute(bundle(List.of(), List.of(), grants, List.of()), NOW);

        assertThat(eff.canAccess(kb("ticket"), ResourceAction.READ)).isTrue();
        assertThat(eff.canAccess(kb("ticket"), ResourceAction.WRITE)).isTrue();
        assertThat(eff.canAccess(kb("ticket"), ResourceAction.ADMIN)).isTrue();
        assertThat(eff.canAccess(kb("service"), ResourceAction.READ)).isTrue();
        assertThat(eff.canAccess(kb("service"), ResourceAction.WRITE)).isFalse();
        assertThat(eff.canAccess(kb("service"), ResourceAction.ADMIN)).isFalse();
    }

    @Test
    void 未注册的资源不生效() {
        List<Facts.ResourceGrant> grants = List.of(
                new Facts.ResourceGrant(kb("ghost"), SubjectRef.user("u_zs"), ResourceAction.READ, Effect.ALLOW, Validity.ALWAYS));
        EffectivePermissions eff = calculator.compute(bundle(List.of(), List.of(), grants, List.of()), NOW);
        assertThat(eff.accessible(KB, ResourceAction.READ)).isEmpty();
    }

    @Test
    void 密级_取最大值_人员设置优先() {
        List<Facts.SecurityLevel> levels = List.of(
                new Facts.SecurityLevel(SubjectRef.position("P_SELLER"), 1),
                new Facts.SecurityLevel(SubjectRef.org("S_A", true), 2));
        assertThat(calculator.compute(bundle(List.of(), List.of(), List.of(), levels), NOW).maxSecurityLevel()).isEqualTo(2);

        List<Facts.SecurityLevel> withPersonal = new ArrayList<>(levels);
        withPersonal.add(new Facts.SecurityLevel(SubjectRef.user("u_zs"), 1));
        assertThat(calculator.compute(bundle(List.of(), List.of(), List.of(), withPersonal), NOW).maxSecurityLevel()).isEqualTo(1);
    }

    @Test
    void 停用的权限点不生效() {
        Facts.Bundle b = new Facts.Bundle(zhangsan, roles, baseBindings(), List.of(), resources, List.of(), List.of(),
                Set.of("pda:tool:pdk_search"));
        EffectivePermissions eff = calculator.compute(b, NOW);
        assertThat(eff.has("pda:tool:pdk_search")).isFalse();
        assertThat(eff.explain("pda:tool:pdk_search").reason()).contains("权限点已停用");
    }

    @Test
    void 结构化来源_类型_对象_有效期() {
        Instant until = NOW.plus(Duration.ofDays(30));
        List<Facts.UserPermission> ups = List.of(
                new Facts.UserPermission("9", "pdk:update:submit", null, null, Effect.ALLOW, null, new Validity(null, until), "联络人"),
                new Facts.UserPermission("10", "pda:chat:use", null, null, Effect.DENY, null, Validity.ALWAYS, "停用问答"));
        EffectivePermissions eff = calculator.compute(bundle(baseBindings(), ups, baseGrants(), List.of()), NOW);

        SourceRef role = eff.explain("pda:tool:pdk_search").allowRefs().get(0);
        assertThat(role.kind()).isEqualTo(SourceRef.Kind.ROLE);
        assertThat(role.roleName()).isEqualTo("PDA普通用户");
        assertThat(role.subjectType()).isEqualTo(com.pd.pds.authz.model.SubjectType.POSITION);
        assertThat(role.subjectName()).isEqualTo("售票员");

        SourceRef direct = eff.explain("pdk:update:submit").allowRefs().get(0);
        assertThat(direct.kind()).isEqualTo(SourceRef.Kind.DIRECT_ALLOW);
        assertThat(direct.validTo()).isEqualTo(until);
        assertThat(direct.reason()).isEqualTo("联络人");

        Decision denied = eff.explain("pda:chat:use");
        assertThat(denied.denyRefs()).extracting(SourceRef::kind).containsExactly(SourceRef.Kind.DIRECT_DENY);
        assertThat(denied.allowRefs()).extracting(SourceRef::kind).containsExactly(SourceRef.Kind.ROLE);

        SourceRef res = eff.explain(kb("ticket"), ResourceAction.READ).allowRefs().get(0);
        assertThat(res.kind()).isEqualTo(SourceRef.Kind.RESOURCE_ALLOW);
        assertThat(res.subjectName()).isEqualTo("景区A");
        assertThat(res.includeChildren()).isTrue();
    }

    @Test
    void 按应用过滤快照() {
        EffectivePermissions eff = calculator.compute(bundle(baseBindings(), List.of(), baseGrants(), List.of()), NOW);
        EffectivePermissions pda = eff.forApp("pda");
        assertThat(pda.permissionCodes()).allMatch(c -> c.startsWith("pda:"));
        assertThat(pda.resourceTypes()).isEmpty();
        EffectivePermissions pdk = eff.forApp("pdk");
        assertThat(pdk.permissionCodes()).containsExactly("pdk:doc:edit");
        assertThat(pdk.accessible(KB, ResourceAction.READ)).hasSize(4);
    }

    // ------------------------------------------------------------------ V2：权限树与节点授权

    /** pdk:ticket（业务域）→ pdk:ticket:kb（菜单）→ view / upload / download（高风险）。 */
    private final Facts.PermTree tree = new Facts.PermTree(
            Map.of("pdk:ticket:kb", "pdk:ticket",
                    "pdk:ticket:kb:view", "pdk:ticket:kb",
                    "pdk:ticket:kb:upload", "pdk:ticket:kb",
                    "pdk:ticket:kb:download", "pdk:ticket:kb"),
            Set.of("pdk:ticket:kb:download"),
            Map.of("pdk:ticket", "票务业务域", "pdk:ticket:kb", "票务知识库"));

    private Facts.Bundle treeBundle(List<Facts.PermGrant> grants, Set<String> disabled) {
        return new Facts.Bundle(zhangsan, roles, List.of(), List.of(), resources, List.of(), List.of(), disabled, grants, tree);
    }

    private static Facts.PermGrant grant(String code, SubjectRef subject, Effect effect) {
        return new Facts.PermGrant("g_" + code, code, subject, effect, null, Validity.ALWAYS, null);
    }

    @Test
    void 授予父节点包含全部非高风险下级() {
        EffectivePermissions eff = calculator.compute(treeBundle(List.of(
                grant("pdk:ticket", SubjectRef.org("S_A", true), Effect.ALLOW)), Set.of()), NOW);
        assertThat(eff.permissionCodes()).containsExactlyInAnyOrder(
                "pdk:ticket", "pdk:ticket:kb", "pdk:ticket:kb:view", "pdk:ticket:kb:upload");
        assertThat(eff.has("pdk:ticket:kb:download")).as("高风险节点须单独授予").isFalse();
        SourceRef via = eff.explain("pdk:ticket:kb:view").allowRefs().get(0);
        assertThat(via.kind()).isEqualTo(SourceRef.Kind.GRANT_ALLOW);
        assertThat(via.viaNode()).isEqualTo("票务业务域");
        assertThat(via.text()).contains("组织【景区A】（含下级）").contains("经上级节点【票务业务域】");
    }

    @Test
    void 禁止父节点禁止全部下级含高风险() {
        EffectivePermissions eff = calculator.compute(treeBundle(List.of(
                grant("pdk:ticket:kb:download", SubjectRef.user("u_zs"), Effect.ALLOW),
                grant("pdk:ticket:kb:view", SubjectRef.position("P_SELLER"), Effect.ALLOW),
                grant("pdk:ticket:kb", SubjectRef.org("D_TICKET", false), Effect.DENY)), Set.of()), NOW);
        assertThat(eff.permissionCodes()).isEmpty();
        assertThat(eff.explain("pdk:ticket:kb:download").denyRefs()).extracting(SourceRef::kind)
                .containsExactly(SourceRef.Kind.GRANT_DENY);
    }

    @Test
    void 授予子节点时上级路径可见但不拥有() {
        EffectivePermissions eff = calculator.compute(treeBundle(List.of(
                grant("pdk:ticket:kb:upload", SubjectRef.group("GRP_KNOWLEDGE"), Effect.ALLOW)), Set.of()), NOW);
        assertThat(eff.permissionCodes()).containsExactly("pdk:ticket:kb:upload");
        assertThat(eff.visibleCodes()).containsExactly("pdk:ticket", "pdk:ticket:kb");
        assertThat(eff.has("pdk:ticket:kb")).isFalse();
    }

    @Test
    void 组织节点授权按向下继承匹配_草稿节点不生效() {
        // 授予集团 G 但不向下继承：张三不在 G 本级，不匹配
        EffectivePermissions none = calculator.compute(treeBundle(List.of(
                grant("pdk:ticket:kb:view", SubjectRef.org("G", false), Effect.ALLOW)), Set.of()), NOW);
        assertThat(none.permissionCodes()).isEmpty();
        // 向下继承：匹配；但未发布（停用集合中）的节点不生效
        EffectivePermissions eff = calculator.compute(treeBundle(List.of(
                grant("pdk:ticket:kb", SubjectRef.org("G", true), Effect.ALLOW)), Set.of("pdk:ticket:kb:upload")), NOW);
        assertThat(eff.permissionCodes()).containsExactlyInAnyOrder("pdk:ticket:kb", "pdk:ticket:kb:view");
        assertThat(eff.explain("pdk:ticket:kb:upload").denyRefs()).extracting(SourceRef::kind)
                .containsExactly(SourceRef.Kind.DISABLED);
    }
}
