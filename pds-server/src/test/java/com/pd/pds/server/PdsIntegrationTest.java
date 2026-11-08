package com.pd.pds.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 端到端：统一登录（授权码 + PKCE）→ 令牌 → 令牌交换 → 管理接口 → 鉴权开放接口 → 吊销 → 审计。
 * 场景与《PDS 产品功能文档》§5.2"景区 A 售票员张三"一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PdsIntegrationTest {

    private static final String REDIRECT = "http://127.0.0.1:8100/auth/callback";

    @Autowired
    private MockMvc mvc;

    // 跨测试方法共享的场景数据
    private static String adminToken;
    private static String scenicA;
    private static String deptTicket;
    private static String positionSeller;
    private static String zhangsanId;
    private static String zhangsanToken;

    // ------------------------------------------------------------------ 统一登录

    @Test
    @Order(1)
    void OIDC发现文档与JWKS可访问() throws Exception {
        String disc = mvc.perform(get("/.well-known/openid-configuration")).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(disc, "$.issuer")).isEqualTo("http://localhost");
        assertThat((List<String>) JsonPath.read(disc, "$.grant_types_supported"))
                .contains("authorization_code", "urn:ietf:params:oauth:grant-type:token-exchange");
        String jwks = mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(jwks, "$.keys")).isNotEmpty();
    }

    @Test
    @Order(2)
    void 管理员通过授权码加PKCE登录_令牌带人员声明() throws Exception {
        Map<String, String> tokens = login("admin", "Pds@Admin2026", null);
        adminToken = tokens.get("access_token");
        Map<String, Object> claims = claims(adminToken);
        assertThat(claims.get("uid")).isNotNull();
        assertThat(claims.get("sub")).isEqualTo(claims.get("uid"));
        assertThat(claims.get("name")).isEqualTo("超级管理员");
        assertThat(claims).containsKey("authz_ver");
        assertThat(tokens.get("id_token")).isNotBlank();

        String me = api(get("/api/v1/me"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(me, "$.data.pdsPermissions")).contains("pds:grant:any", "pds:user:manage");
    }

    @Test
    @Order(3)
    void 密码错误五次后锁定() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").session(session).param("username", "admin").param("password", "wrong").with(csrf()));
        }
        // 锁定后即使密码正确也无法登录
        MvcResult r = mvc.perform(post("/login").session(session).param("username", "admin")
                .param("password", "Pds@Admin2026").with(csrf())).andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).as("status=" + r.getResponse().getStatus()).contains("/login?error");
        String page = mvc.perform(get("/login?error").session(session)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("账号已锁定");
        // 解锁，便于后续用例
        unlock("admin");
    }

    // ------------------------------------------------------------------ 组织、应用、授权

    @Test
    @Order(10)
    void 搭建景区A场景() throws Exception {
        scenicA = id(api(post("/api/v1/orgs"), adminToken,
                "{\"parentId\":\"org_root\",\"type\":\"SCENIC\",\"code\":\"S_A\",\"name\":\"景区A\"}"));
        deptTicket = id(api(post("/api/v1/orgs"), adminToken,
                "{\"parentId\":\"" + scenicA + "\",\"type\":\"DEPT\",\"code\":\"S_A_TICKET\",\"name\":\"票务部\"}"));
        positionSeller = id(api(post("/api/v1/positions"), adminToken,
                "{\"code\":\"SELLER\",\"name\":\"售票员\",\"scenicId\":\"" + scenicA + "\"}"));

        String tree = api(get("/api/v1/orgs/tree"), adminToken).getContentAsString();
        assertThat((String) JsonPath.read(tree, "$.data[0].children[0].children[0].name")).isEqualTo("票务部");
        assertThat((String) JsonPath.read(tree, "$.data[0].children[0].children[0].scenicId")).isEqualTo(scenicA);

        // PDK / PDA 通过各自服务客户端注册权限清单（PDA 新增工具自动注册，默认无人拥有）
        String pdk = clientToken("pdk-service", "pdk-service-secret", "pds.manifest pds.resource pds.authz");
        api(post("/api/v1/apps/pdk/manifest"), pdk, """
                {"name":"PDK 知识服务","version":"2026.11.1","category":"DATA_OUTLET",
                 "permissions":[{"code":"pdk:kb:view","name":"查看知识库","type":"FUNCTION"},
                                {"code":"pdk:search:use","name":"调用检索","type":"API"}],
                 "resourceTypes":[{"type":"pdk:kb","name":"知识库"}]}""");
        String pda = clientToken("pda-plugin", "pda-plugin-secret", "pds.manifest");
        String manifest = api(post("/api/v1/apps/pda/manifest"), pda, """
                {"name":"PD 智能助手","version":"2026.11.1","category":"QUERY",
                 "permissions":[{"code":"pda:chat:use","name":"使用智能问答","type":"FUNCTION"},
                                {"code":"pda:tool:pdk_search","name":"知识检索工具","type":"AI_TOOL"},
                                {"code":"pda:tool:terminal","name":"终端命令工具","type":"AI_TOOL","risk":"HIGH"}]}""")
                .getContentAsString();
        // 3 个声明的权限点 + 自动生成的应用准入权限 pda:access
        assertThat((Integer) JsonPath.read(manifest, "$.data.added")).isEqualTo(4);
        String perms = api(get("/api/v1/apps/pda/permissions"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(perms, "$.data[*].code")).contains("pda:access");

        // PDA 客户端不能替 PDK 注册清单
        MockHttpServletResponse denied = raw(post("/api/v1/apps/pdk/manifest"), pda,
                "{\"name\":\"x\",\"version\":\"1\",\"permissions\":[]}");
        assertThat(denied.getStatus()).isEqualTo(403);

        // 知识库资源实例：两个景区库 + 一个集团共享库
        for (String[] kb : new String[][]{{"kb_ticket", "票务知识库", scenicA}, {"kb_finance", "内部财务制度库", scenicA},
                {"kb_common", "集团通用制度库", "GROUP"}}) {
            api(post("/api/v1/resources"), pdk,
                    "{\"type\":\"pdk:kb\",\"refId\":\"" + kb[0] + "\",\"name\":\"" + kb[1] + "\",\"scenicId\":\"" + kb[2] + "\"}");
        }
    }

    @Test
    @Order(11)
    void 张三_岗位角色_资源授权_人员明细禁止() throws Exception {
        String created = api(post("/api/v1/users"), adminToken, """
                {"username":"zhangsan","empNo":"S001023","name":"张三","mobile":"13812341234",
                 "orgId":"%s","positionId":"%s","password":"Init@Pass01"}""".formatted(deptTicket, positionSeller))
                .getContentAsString();
        zhangsanId = JsonPath.read(created, "$.data.userId");

        String list = api(get("/api/v1/users?keyword=张三"), adminToken).getContentAsString();
        assertThat((String) JsonPath.read(list, "$.data.items[0].mobile")).isEqualTo("138****1234");

        String role = id(api(post("/api/v1/roles"), adminToken, "{\"code\":\"PDA_USER\",\"name\":\"PDA普通用户\"}"));
        api(put("/api/v1/roles/" + role + "/permissions"), adminToken, """
                {"permissions":[{"permCode":"pda:access"},{"permCode":"pda:chat:use"},{"permCode":"pda:tool:pdk_search"},
                                {"permCode":"pdk:search:use"},{"permCode":"pdk:kb:view","dataScope":"SCENIC"}]}""");
        api(post("/api/v1/roles/" + role + "/bindings"), adminToken,
                "{\"subjectType\":\"POSITION\",\"subjectId\":\"" + positionSeller + "\"}");

        for (String kb : List.of("kb_ticket", "kb_finance")) {
            api(post("/api/v1/resources/pdk:kb/" + kb + "/grants"), adminToken,
                    "{\"subjectType\":\"ORG\",\"subjectId\":\"" + scenicA + "\",\"includeChildren\":true,\"action\":\"READ\",\"effect\":\"ALLOW\"}");
        }
        api(post("/api/v1/resources/pdk:kb/kb_common/grants"), adminToken,
                "{\"subjectType\":\"ORG\",\"subjectId\":\"org_root\",\"includeChildren\":true,\"action\":\"READ\",\"effect\":\"ALLOW\"}");
        api(post("/api/v1/users/" + zhangsanId + "/perms"), adminToken, """
                {"resourceType":"pdk:kb","resourceId":"kb_finance","action":"READ","effect":"DENY","reason":"非财务人员"}""");

        // 人员明细权限页
        String eff = api(get("/api/v1/users/" + zhangsanId + "/effective?app=pda"), adminToken).getContentAsString();
        List<Map<String, Object>> perms = JsonPath.read(eff, "$.data.permissions");
        assertThat(perms).anySatisfy(p -> {
            assertThat(p.get("code")).isEqualTo("pda:tool:pdk_search");
            assertThat(p.get("state")).isEqualTo("GRANTED");
            assertThat(p.get("allowSources").toString()).contains("角色【PDA普通用户】经 岗位【售票员】 授予");
        });
        assertThat(perms).anySatisfy(p -> {
            assertThat(p.get("code")).isEqualTo("pda:tool:terminal");
            assertThat(p.get("state")).isEqualTo("NONE");
        });
    }

    @Test
    @Order(12)
    void 首次登录强制改密后完成单点登录() throws Exception {
        Map<String, String> tokens = login("zhangsan", "Init@Pass01", "Zs@NewPass2026");
        zhangsanToken = tokens.get("access_token");
        Map<String, Object> claims = claims(zhangsanToken);
        assertThat(claims.get("uid")).isEqualTo(zhangsanId);
        assertThat(claims.get("scenic_id")).isEqualTo(scenicA);
        assertThat((List<String>) claims.get("scenic_scope")).containsExactly(scenicA);
        assertThat(claims.get("emp_no")).isEqualTo("S001023");
    }

    @Test
    @Order(13)
    void PDA插件令牌交换得到访问PDK的短时票据() throws Exception {
        MvcResult r = mvc.perform(post("/oauth2/token").with(httpBasic("pda-plugin", "pda-plugin-secret"))
                .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .param("subject_token", zhangsanToken)
                .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                .param("audience", "pdk")
                .param("scope", "pdk.api")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        String ticket = JsonPath.read(r.getResponse().getContentAsString(), "$.access_token");
        Map<String, Object> claims = claims(ticket);
        assertThat(claims.get("uid")).isEqualTo(zhangsanId);
        assertThat(claims.get("aud").toString()).contains("pdk");
        assertThat(((Map<?, ?>) claims.get("act")).get("sub")).isEqualTo("pda-plugin");
        long ttl = ((Number) claims.get("exp")).longValue() - ((Number) claims.get("iat")).longValue();
        assertThat(ttl).isEqualTo(300);

        // 通用规则：未登记 {应用}.api 范围的目标应用不能换票
        MvcResult bad = mvc.perform(post("/oauth2/token").with(httpBasic("pda-plugin", "pda-plugin-secret"))
                .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .param("subject_token", zhangsanToken)
                .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                .param("audience", "pds")
                .param("scope", "pdk.api")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("invalid_target");
    }

    @Test
    @Order(14)
    void 鉴权开放接口_检查_快照_跨应用限制_自查() throws Exception {
        String pda = clientToken("pda-plugin", "pda-plugin-secret", "pds.authz");
        String ok = api(post("/api/v1/authz/check"), pda,
                "{\"subject\":\"" + zhangsanId + "\",\"permission\":\"pda:tool:pdk_search\"}").getContentAsString();
        assertThat((Boolean) JsonPath.read(ok, "$.data.allowed")).isTrue();
        String no = api(post("/api/v1/authz/check"), pda,
                "{\"subject\":\"" + zhangsanId + "\",\"permission\":\"pda:tool:terminal\"}").getContentAsString();
        assertThat((Boolean) JsonPath.read(no, "$.data.allowed")).isFalse();
        assertThat((String) JsonPath.read(no, "$.data.reason")).startsWith("未授予");

        String batch = api(post("/api/v1/authz/batch-check"), pda, """
                {"subject":"%s","items":[{"permission":"pda:chat:use"},{"permission":"pda:tool:terminal"}]}""".formatted(zhangsanId))
                .getContentAsString();
        assertThat((List<Boolean>) JsonPath.read(batch, "$.data.results[*].allowed")).containsExactly(true, false);

        String pdk = clientToken("pdk-service", "pdk-service-secret", "pds.authz");
        String snap = api(get("/api/v1/authz/snapshot?app=pdk&subject=" + zhangsanId), pdk).getContentAsString();
        assertThat((List<String>) JsonPath.read(snap, "$.data.permissions")).containsExactly("pdk:kb:view", "pdk:search:use");
        assertThat((List<String>) JsonPath.read(snap, "$.data.resources['pdk:kb'].read")).containsExactly("kb_common", "kb_ticket");
        assertThat((List<String>) JsonPath.read(snap, "$.data.dataScopes['pdk:kb:view'].scenicIds")).containsExactly(scenicA);

        // PDK 服务客户端不能查询 PDA 的权限
        assertThat(raw(get("/api/v1/authz/snapshot?app=pda&subject=" + zhangsanId), pdk, null).getStatus()).isEqualTo(403);

        // 资源权限解释：禁止项
        String explain = api(get("/api/v1/authz/explain?subject=" + zhangsanId + "&resourceType=pdk:kb&resourceId=kb_finance&action=READ"),
                pdk).getContentAsString();
        assertThat((String) JsonPath.read(explain, "$.data.reason")).contains("人员明细禁止（原因：非财务人员）");

        // 张三可以查询自己，不能查询他人，也不能访问管理接口
        assertThat(raw(get("/api/v1/authz/snapshot?app=pda&subject=" + zhangsanId), zhangsanToken, null).getStatus()).isEqualTo(200);
        String adminId = (String) claims(adminToken).get("uid");
        assertThat(raw(get("/api/v1/authz/snapshot?app=pds&subject=" + adminId), zhangsanToken, null).getStatus()).isEqualTo(403);
        MockHttpServletResponse users = raw(get("/api/v1/users"), zhangsanToken, null);
        assertThat(users.getStatus()).isEqualTo(403);
        assertThat(users.getContentAsString()).contains("无此功能权限");
    }

    @Test
    @Order(15)
    void 三员分立_同一人不能同时担任系统管理员和安全管理员() throws Exception {
        String roles = api(get("/api/v1/roles"), adminToken).getContentAsString();
        String sys = ((List<String>) JsonPath.read(roles, "$.data[?(@.code=='PDS_SYS_ADMIN')].id")).get(0);
        String sec = ((List<String>) JsonPath.read(roles, "$.data[?(@.code=='PDS_SEC_ADMIN')].id")).get(0);
        api(post("/api/v1/roles/" + sys + "/bindings"), adminToken, "{\"subjectType\":\"USER\",\"subjectId\":\"" + zhangsanId + "\"}");
        MockHttpServletResponse r = raw(post("/api/v1/roles/" + sec + "/bindings"), adminToken,
                "{\"subjectType\":\"USER\",\"subjectId\":\"" + zhangsanId + "\"}");
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString()).contains("三员分立");
    }

    @Test
    @Order(16)
    void 应用准入_没有pda准入权限的人员不能单点登录进入PDA() throws Exception {
        // 超级管理员只有 PDS 权限：PDA 注册清单（开启准入）后，经 PDA 网关登录被拒绝
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize = "/oauth2/authorize?response_type=code&client_id=pda-gateway&scope=openid%20profile&state=st"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get(URI.create(authorize)).session(session).accept(MediaType.TEXT_HTML));
        MvcResult r = mvc.perform(post("/login").session(session).param("username", "admin")
                .param("password", "Pds@Admin2026").with(csrf())).andReturn();
        r = mvc.perform(get(URI.create(r.getResponse().getRedirectedUrl())).session(session).accept(MediaType.TEXT_HTML)).andReturn();
        String callback = r.getResponse().getRedirectedUrl();
        assertThat(callback).startsWith(REDIRECT).contains("error=access_denied").doesNotContain("code=");
    }

    @Test
    @Order(20)
    void 停用账号后已签发令牌立即失效() throws Exception {
        assertThat(raw(get("/api/v1/me"), zhangsanToken, null).getStatus()).isEqualTo(200);
        api(post("/api/v1/users/" + zhangsanId + "/disable"), adminToken, "{\"reason\":\"离职\"}");
        assertThat(raw(get("/api/v1/me"), zhangsanToken, null).getStatus()).isEqualTo(401);
        String pda = clientToken("pda-plugin", "pda-plugin-secret", "pds.authz");
        String r = api(post("/api/v1/authz/check"), pda,
                "{\"subject\":\"" + zhangsanId + "\",\"permission\":\"pda:chat:use\"}").getContentAsString();
        assertThat((Boolean) JsonPath.read(r, "$.data.allowed")).isFalse();
    }

    @Test
    @Order(30)
    void 审计_应用上报_哈希链完整() throws Exception {
        String pdk = clientToken("pdk-service", "pdk-service-secret", "pds.audit");
        api(post("/api/v1/audit/events"), pdk, """
                {"events":[{"eventType":"DATA_ACCESS","userId":"%s","action":"pdk_search","result":"ALLOW",
                            "targetType":"pdk:kb","targetIds":"kb_ticket","detail":{"query":"老人票怎么买"}}]}""".formatted(zhangsanId));

        String events = api(get("/api/v1/audit/events?pageSize=100"), adminToken).getContentAsString();
        List<String> types = JsonPath.read(events, "$.data.items[*].eventType");
        assertThat(types).contains("LOGIN", "LOGIN_FAIL", "ADMIN_CHANGE", "TOKEN_EXCHANGE", "ACCESS_DENIED", "DATA_ACCESS");
        List<String> apps = JsonPath.read(events, "$.data.items[?(@.eventType=='DATA_ACCESS')].app");
        assertThat(apps).containsOnly("pdk");

        String verify = api(get("/api/v1/audit/verify"), adminToken).getContentAsString();
        assertThat((Boolean) JsonPath.read(verify, "$.data.intact")).isTrue();
    }

    @Test
    @Order(40)
    void 管理后台_公共客户端无密钥PKCE登录_工作台与列表接口() throws Exception {
        String redirect = "http://127.0.0.1:5173/auth/callback";
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize = "/oauth2/authorize?response_type=code&client_id=pds-console&scope=openid%20profile&state=c1"
                + "&redirect_uri=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get(URI.create(authorize)).session(session).accept(MediaType.TEXT_HTML));
        MvcResult r = mvc.perform(post("/login").session(session).param("username", "admin")
                .param("password", "Pds@Admin2026").with(csrf())).andReturn();
        r = mvc.perform(get(URI.create(r.getResponse().getRedirectedUrl())).session(session).accept(MediaType.TEXT_HTML)).andReturn();
        String code = r.getResponse().getRedirectedUrl().replaceAll(".*[?&]code=([^&]+).*", "$1");
        // 公共客户端：不带客户端密钥，凭 code_verifier 换取令牌
        r = mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "pds-console")
                .param("code", code).param("redirect_uri", redirect).param("code_verifier", verifier)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String token = JsonPath.read(r.getResponse().getContentAsString(), "$.access_token");

        String overview = api(get("/api/v1/dashboard/overview"), token).getContentAsString();
        assertThat((Integer) JsonPath.read(overview, "$.data.activeUsers")).isGreaterThanOrEqualTo(1);
        assertThat((List<?>) JsonPath.read(overview, "$.data.loginWeek.thisWeek")).hasSize(7);
        assertThat((Integer) JsonPath.read(overview, "$.data.onlineUsers")).isGreaterThanOrEqualTo(1);
        assertThat((Object) JsonPath.read(overview, "$.data.pendingApprovals")).isNull();
        assertThat((List<String>) JsonPath.read(overview, "$.data.scenics[*].name")).contains("景区A");

        // 批量导入：一行成功、一行组织编码错误
        String imported = api(post("/api/v1/users/import"), token, """
                {"rows":[{"username":"wangwu","name":"王五","orgCode":"S_A_TICKET","positionCode":"SELLER"},
                         {"username":"zhaoliu","name":"赵六","orgCode":"NOT_EXIST"}]}""").getContentAsString();
        assertThat((List<Boolean>) JsonPath.read(imported, "$.data[*].ok")).containsExactly(true, false);
        assertThat((String) JsonPath.read(imported, "$.data[1].message")).contains("NOT_EXIST");
        assertThat(((String) JsonPath.read(imported, "$.data[0].initialPassword")).length()).isEqualTo(12);

        // 人员明细：结构化来源与设置有效期（张三在前面的用例中被停用，先恢复）
        api(post("/api/v1/users/" + zhangsanId + "/enable"), token);
        String eff = api(get("/api/v1/users/" + zhangsanId + "/effective?app=pdk"), token).getContentAsString();
        assertThat((List<String>) JsonPath.read(eff, "$.data.resources[?(@.refId=='kb_finance')].denyRefs[0].kind")).containsExactly("DIRECT_DENY");
        String perms = api(get("/api/v1/users/" + zhangsanId + "/perms"), token).getContentAsString();
        String permId = JsonPath.read(perms, "$.data[0].id");
        api(put("/api/v1/users/" + zhangsanId + "/perms/" + permId), token, "{\"validTo\":\"2099-12-31T00:00:00Z\"}");
        perms = api(get("/api/v1/users/" + zhangsanId + "/perms"), token).getContentAsString();
        assertThat((String) JsonPath.read(perms, "$.data[0].validTo")).startsWith("2099-12-31");

        // 审计：单条完整性校验
        String events = api(get("/api/v1/audit/events?eventType=LOGIN,ADMIN_CHANGE&pageSize=1"), token).getContentAsString();
        String eventId = ((List<String>) JsonPath.read(events, "$.data.items[*].id")).get(0);
        String one = api(get("/api/v1/audit/events/" + eventId + "/verify"), token).getContentAsString();
        assertThat((Boolean) JsonPath.read(one, "$.data.intact")).isTrue();

        String apps = api(get("/api/v1/apps"), token).getContentAsString();
        assertThat((List<String>) JsonPath.read(apps, "$.data[*].code")).contains("pds", "pdk", "pda");
        String resources = api(get("/api/v1/resources?type=pdk:kb"), token).getContentAsString();
        assertThat((List<String>) JsonPath.read(resources, "$.data.items[*].scenicName")).contains("集团共享", "景区A");
        String grants = api(get("/api/v1/resources/pdk:kb/kb_common/grants"), token).getContentAsString();
        assertThat((List<String>) JsonPath.read(grants, "$.data[*].subjectName")).contains("集团");

        // 运行时配置（随 PDS 发布的管理后台）
        String cfg = mvc.perform(get("/console/config.js")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(cfg).contains("window.__PDS_CONFIG__").contains("\"clientId\":\"pds-console\"");
    }

    @Autowired
    private com.pd.pds.server.service.DistributionService distribution;

    @Test
    @Order(50)
    void V2_权限树_节点授权与继承阻断_分配库_系统角色下发_权限图谱() throws Exception {
        // 节点类型：可新增（没有不可删除的内置类型）
        api(post("/api/v1/perm-node-types"), adminToken, "{\"code\":\"REPORT\",\"name\":\"报表\",\"grantable\":true}");
        String types = api(get("/api/v1/perm-node-types"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(types, "$.data[*].code")).contains("REPORT", "DOMAIN", "BUTTON");
        api(delete("/api/v1/perm-node-types/REPORT"), adminToken);
        assertThat(raw(delete("/api/v1/perm-node-types/FUNCTION"), adminToken, null).getStatus()).as("类型在用不能删").isEqualTo(409);

        // 权限树：新增节点进入草稿，ID 自动生成，编码按父节点拼接
        String domain = api(post("/api/v1/perm-nodes"), adminToken,
                "{\"appCode\":\"pdk\",\"type\":\"DOMAIN\",\"name\":\"票务业务域\",\"segment\":\"ticket\"}").getContentAsString();
        String domainId = JsonPath.read(domain, "$.data.id");
        assertThat(domainId).startsWith("pn_");
        assertThat((String) JsonPath.read(domain, "$.data.code")).isEqualTo("pdk:ticket");
        assertThat((String) JsonPath.read(domain, "$.data.draft")).isEqualTo("NEW");
        api(post("/api/v1/perm-nodes"), adminToken,
                "{\"appCode\":\"pdk\",\"parentId\":\"" + domainId + "\",\"type\":\"BUTTON\",\"name\":\"上传文档\",\"segment\":\"upload\"}");
        String dl = api(post("/api/v1/perm-nodes"), adminToken,
                "{\"appCode\":\"pdk\",\"parentId\":\"" + domainId + "\",\"type\":\"BUTTON\",\"name\":\"下载原文\",\"segment\":\"download\",\"risk\":\"HIGH\"}")
                .getContentAsString();
        String dlId = JsonPath.read(dl, "$.data.id");
        // 草稿节点不能授权
        String grantDomain = """
                {"subjects":[{"type":"ORG","id":"%s","includeChildren":true}],
                 "objects":[{"type":"PERM","code":"pdk:ticket"}],"effect":"ALLOW","reason":"票务域开放"}""".formatted(scenicA);
        assertThat(raw(post("/api/v1/assignments/batch"), adminToken, grantDomain).getStatus()).isEqualTo(409);
        // 草稿可改编码（连同下级）
        api(put("/api/v1/perm-nodes/" + domainId), adminToken, "{\"code\":\"pdk:tk\"}");
        api(put("/api/v1/perm-nodes/" + domainId), adminToken, "{\"code\":\"pdk:ticket\"}");
        String diff = api(get("/api/v1/perm-tree/pdk/diff"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(diff, "$.data[*].code")).containsExactlyInAnyOrder("pdk:ticket", "pdk:ticket:upload", "pdk:ticket:download");
        String published = api(post("/api/v1/perm-tree/pdk/publish"), adminToken, "{}").getContentAsString();
        assertThat((String) JsonPath.read(published, "$.data.version")).startsWith("v").endsWith(".1");
        assertThat(raw(put("/api/v1/perm-nodes/" + domainId), adminToken, "{\"code\":\"pdk:x\"}").getStatus()).as("发布后编码锁定").isEqualTo(409);

        // 影响分析 + 组织节点授权（景区A，向下继承）：父含子，高风险除外
        String impact = api(post("/api/v1/assignments/impact"), adminToken, grantDomain).getContentAsString();
        assertThat((Integer) JsonPath.read(impact, "$.data.affectedUsers")).isGreaterThanOrEqualTo(1);
        assertThat((Integer) JsonPath.read(impact, "$.data.highRiskCount")).isZero();
        api(post("/api/v1/assignments/batch"), adminToken, grantDomain);
        String eff = api(get("/api/v1/users/" + zhangsanId + "/effective?app=pdk"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(eff, "$.data.permissions[?(@.code=='pdk:ticket:upload')].state")).containsExactly("GRANTED");
        assertThat((List<String>) JsonPath.read(eff, "$.data.permissions[?(@.code=='pdk:ticket:download')].state")).containsExactly("NONE");
        String nodeGrants = api(get("/api/v1/orgs/" + deptTicket + "/grants"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(nodeGrants, "$.data[?(@.inherited==true)].fromOrgName")).contains("景区A");

        // 阻断继承：票务部不再继承景区A的节点权限
        api(put("/api/v1/orgs/" + deptTicket + "/inherit"), adminToken, "{\"inherit\":false}");
        eff = api(get("/api/v1/users/" + zhangsanId + "/effective?app=pdk"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(eff, "$.data.permissions[?(@.code=='pdk:ticket:upload')].state")).containsExactly("NONE");
        api(put("/api/v1/orgs/" + deptTicket + "/inherit"), adminToken, "{\"inherit\":true}");

        // 已发布节点的修改进入待发布；撤销后无差异
        api(put("/api/v1/perm-nodes/" + dlId), adminToken, "{\"name\":\"下载原文件\"}");
        diff = api(get("/api/v1/perm-tree/pdk/diff"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(diff, "$.data[*].kind")).containsExactly("MODIFY");
        api(post("/api/v1/perm-nodes/" + dlId + "/discard"), adminToken);
        assertThat((List<Object>) JsonPath.read(api(get("/api/v1/perm-tree/pdk/diff"), adminToken).getContentAsString(), "$.data")).isEmpty();
        // 被授权引用的节点不能删除
        String domainUsed = raw(delete("/api/v1/perm-nodes/" + domainId), adminToken, null).getContentAsString();
        assertThat(domainUsed).contains("下级节点");

        // 分配库：按应用查看、统计、按权限查看
        String rows = api(get("/api/v1/assignments?app=pdk&pageSize=100"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(rows, "$.data.items[*].objectCode")).contains("pdk:ticket");
        String byPerm = api(get("/api/v1/assignments?permCode=pdk:ticket:upload"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(byPerm, "$.data.items[*].subjectName")).contains("景区A");
        String stats = api(get("/api/v1/assignments/stats?app=pdk"), adminToken).getContentAsString();
        assertThat((Integer) JsonPath.read(stats, "$.data.records")).isGreaterThanOrEqualTo(1);

        // 系统角色：应用角色、继承、互斥、外部编码、下发
        String role = api(post("/api/v1/roles"), adminToken, """
                {"name":"PDK 知识读者","type":"APP","appCode":"pdk"}""").getContentAsString();
        String roleId = JsonPath.read(role, "$.data.id");
        assertThat((String) JsonPath.read(role, "$.data.code")).startsWith("APP_");
        api(put("/api/v1/roles/" + roleId + "/permissions"), adminToken, "{\"permissions\":[{\"permCode\":\"pdk:kb:view\"}]}");
        String roles = api(get("/api/v1/roles"), adminToken).getContentAsString();
        String auditor = ((List<String>) JsonPath.read(roles, "$.data[?(@.code=='PDS_AUDIT_ADMIN')].id")).get(0);
        api(put("/api/v1/roles/" + roleId + "/mutex"), adminToken, "{\"roleIds\":[\"" + auditor + "\"]}");
        api(put("/api/v1/roles/" + roleId + "/mappings"), adminToken, "{\"mappings\":[{\"appCode\":\"pdk\",\"externalCode\":\"ext_pdk_reader\"}]}");
        api(put("/api/v1/dist/subscriptions/pdk"), adminToken, "{\"webhook\":true,\"webhookUrl\":\"http://127.0.0.1:9/pds-hook\"}");
        api(post("/api/v1/roles/" + roleId + "/bindings"), adminToken, "{\"subjectType\":\"USER\",\"subjectId\":\"" + zhangsanId + "\"}");
        MockHttpServletResponse mutex = raw(post("/api/v1/roles/" + auditor + "/bindings"), adminToken,
                "{\"subjectType\":\"USER\",\"subjectId\":\"" + zhangsanId + "\"}");
        assertThat(mutex.getStatus()).isEqualTo(403);
        String pdk = clientToken("pdk-service", "pdk-service-secret", "pds.authz");
        String snap = api(get("/api/v1/authz/snapshot?app=pdk&subject=" + zhangsanId), pdk).getContentAsString();
        assertThat((List<String>) JsonPath.read(snap, "$.data.roles")).contains("ext_pdk_reader");
        distribution.dispatchDue();
        String dist = api(get("/api/v1/roles/" + roleId + "/distribution"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(dist, "$.data[?(@.appCode=='pdk')].status")).containsAnyOf("RETRYING", "FAILED");
        assertThat((List<String>) JsonPath.read(dist, "$.data[?(@.appCode=='pdk')].externalCode")).containsExactly("ext_pdk_reader");
        String versions = api(get("/api/v1/roles/" + roleId + "/versions"), adminToken).getContentAsString();
        assertThat((List<Object>) JsonPath.read(versions, "$.data")).hasSize(1);
        String members = api(get("/api/v1/roles/" + roleId + "/members"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(members, "$.data[*].name")).containsExactly("张三");

        // 权限图谱与路径解释
        String graph = api(get("/api/v1/graph/user/" + zhangsanId + "?app=pdk"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(graph, "$.data.nodes[*].id")).contains("PERM:pdk:ticket:upload", "ROLE:" + roleId);
        String explain = api(get("/api/v1/graph/explain?userId=" + zhangsanId + "&code=pdk:ticket:upload"), adminToken).getContentAsString();
        assertThat((String) JsonPath.read(explain, "$.data.verdict")).isEqualTo("ALLOW");
        assertThat((List<String>) JsonPath.read(explain, "$.data.allow[0].steps")).anyMatch(s -> s.contains("景区A"));
        api(get("/api/v1/graph/overview"), adminToken);
        api(get("/api/v1/graph/perm?code=pdk:ticket:upload"), adminToken);

        // 加人预览：把管理员以兼职加入票务部，会新增票务域权限
        String adminId = JsonPath.read(api(get("/api/v1/me"), adminToken).getContentAsString(), "$.data.id");
        String preview = api(post("/api/v1/orgs/" + deptTicket + "/members/preview"), adminToken,
                "{\"userIds\":[\"" + adminId + "\"],\"primary\":false}").getContentAsString();
        assertThat((List<String>) JsonPath.read(preview, "$.data.addedNames")).contains("上传文档");

        // 撤销分配库记录
        String rowId = ((List<String>) JsonPath.read(rows, "$.data.items[?(@.objectCode=='pdk:ticket')].id")).get(0);
        api(post("/api/v1/assignments/revoke"), adminToken, "{\"ids\":[\"" + rowId + "\"]}");
        eff = api(get("/api/v1/users/" + zhangsanId + "/effective?app=pdk"), adminToken).getContentAsString();
        assertThat((List<String>) JsonPath.read(eff, "$.data.permissions[?(@.code=='pdk:ticket:upload')].state")).containsExactly("NONE");
    }

    // ------------------------------------------------------------------ 工具方法

    /** 授权码 + PKCE 完整登录；needNewPassword 不为空时处理首次登录改密。 */
    private Map<String, String> login(String username, String password, String needNewPassword) throws Exception {
        String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize = "/oauth2/authorize?response_type=code&client_id=pda-gateway&scope=openid%20profile&state=st"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        MockHttpSession session = new MockHttpSession();

        MvcResult r = mvc.perform(get(URI.create(authorize)).session(session).accept(MediaType.TEXT_HTML)).andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).endsWith("/login");

        r = mvc.perform(post("/login").session(session).param("username", username).param("password", password).with(csrf())).andReturn();
        String location = r.getResponse().getRedirectedUrl();
        if (needNewPassword != null) {
            assertThat(location).as("status=" + r.getResponse().getStatus()).endsWith("/password/change");
            // 改密之前直接访问授权端点也会被拦回改密页
            MvcResult blocked = mvc.perform(get(URI.create(authorize)).session(session).accept(MediaType.TEXT_HTML)).andReturn();
            assertThat(blocked.getResponse().getRedirectedUrl()).endsWith("/password/change");
            r = mvc.perform(post("/password/change").session(session).with(csrf())
                    .param("oldPassword", password).param("newPassword", needNewPassword).param("confirmPassword", needNewPassword)).andReturn();
            location = r.getResponse().getRedirectedUrl();
        }
        assertThat(location).as("status=" + r.getResponse().getStatus() + " body=" + r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("/oauth2/authorize");

        r = mvc.perform(get(URI.create(location)).session(session).accept(MediaType.TEXT_HTML)).andReturn();
        String callback = r.getResponse().getRedirectedUrl();
        assertThat(callback).startsWith(REDIRECT).contains("code=").contains("state=st");
        String code = callback.replaceAll(".*[?&]code=([^&]+).*", "$1");

        r = mvc.perform(post("/oauth2/token").with(httpBasic("pda-gateway", "pda-gateway-secret"))
                .param("grant_type", "authorization_code").param("code", code)
                .param("redirect_uri", REDIRECT).param("code_verifier", verifier)).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(200);
        return Map.of("access_token", JsonPath.read(body, "$.access_token"), "id_token", JsonPath.read(body, "$.id_token"));
    }

    private String clientToken(String clientId, String secret, String scope) throws Exception {
        String body = mvc.perform(post("/oauth2/token").with(httpBasic(clientId, secret))
                .param("grant_type", "client_credentials").param("scope", scope)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private MockHttpServletResponse api(MockHttpServletRequestBuilder req, String token) throws Exception {
        return api(req, token, null);
    }

    /** 调用 API 并断言成功（code=0）。 */
    private MockHttpServletResponse api(MockHttpServletRequestBuilder req, String token, String json) throws Exception {
        MockHttpServletResponse res = raw(req, token, json);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(res.getContentAsString(), "$.code")).isZero();
        return res;
    }

    private MockHttpServletResponse raw(MockHttpServletRequestBuilder req, String token, String json) throws Exception {
        req.header("Authorization", "Bearer " + token).characterEncoding(StandardCharsets.UTF_8);
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return res;
    }

    private static String id(MockHttpServletResponse res) throws Exception {
        return JsonPath.read(res.getContentAsString(), "$.data.id");
    }

    private static Map<String, Object> claims(String jwt) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$");
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void unlock(String username) {
        jdbc.update("UPDATE sys_user SET locked_until = NULL, failed_attempts = 0 WHERE username = ?", username);
    }
}
