package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.Ids;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.DistDelivery;
import com.pd.pds.server.entity.DistEvent;
import com.pd.pds.server.entity.DistSubscription;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.DistDeliveryMapper;
import com.pd.pds.server.mapper.DistEventMapper;
import com.pd.pds.server.mapper.DistSubscriptionMapper;
import com.pd.pds.server.security.CurrentActor;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import com.pd.pds.server.web.dto.DistDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 下发：权限树发布、角色变更等事件按应用订阅投递（Webhook，HMAC-SHA256 签名 + 时间戳 + 随机串防重放）。
 *
 * <p>投递失败按 10 秒、30 秒、2 分钟、10 分钟退避重试，第 5 次仍失败标记为失败，可在后台手动重推。
 * 请求头：X-PDS-Event、X-PDS-Delivery、X-PDS-Timestamp、X-PDS-Nonce、
 * X-PDS-Signature = hex(HMAC-SHA256(secret, timestamp + "." + nonce + "." + body))。
 */
@Service
public class DistributionService {

    private static final Logger log = LoggerFactory.getLogger(DistributionService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration[] BACKOFF = {Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)};
    public static final Map<String, String> EVENT_LABELS = Map.of(
            "perm_tree.published", "权限树发布",
            "role.updated", "角色权限变更",
            "role.members.changed", "角色成员变更",
            "role.deleted", "角色删除",
            "role.full_sync", "角色全量同步");

    private final DistSubscriptionMapper subMapper;
    private final DistEventMapper eventMapper;
    private final DistDeliveryMapper deliveryMapper;
    private final AppInfoMapper appMapper;
    private final AuthzService authz;
    private final Guard guard;
    private final AuditService audit;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();

    public DistributionService(DistSubscriptionMapper subMapper, DistEventMapper eventMapper, DistDeliveryMapper deliveryMapper,
                               AppInfoMapper appMapper, AuthzService authz, @Lazy Guard guard, AuditService audit, JsonMapper json) {
        this.subMapper = subMapper;
        this.eventMapper = eventMapper;
        this.deliveryMapper = deliveryMapper;
        this.appMapper = appMapper;
        this.authz = authz;
        this.guard = guard;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ 订阅

    public List<DistDtos.SubscriptionView> subscriptions() {
        guard.require(PdsPermissions.ROLE_VIEW);
        Map<String, DistSubscription> subs = subMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(DistSubscription::getAppCode, s -> s));
        return appMapper.selectList(new LambdaQueryWrapper<AppInfo>().orderByAsc(AppInfo::getCode)).stream()
                .map(a -> view(a, subs.get(a.getCode()))).toList();
    }

    public DistDtos.SubscriptionView subscription(String app) {
        guard.require(PdsPermissions.ROLE_VIEW);
        return view(requireApp(app), subMapper.selectById(app));
    }

    @Transactional
    public DistDtos.SubscriptionView save(String app, DistDtos.SaveSubscription req) {
        guard.require(PdsPermissions.APP_MANAGE);
        AppInfo a = requireApp(app);
        DistSubscription s = subMapper.selectById(app);
        boolean isNew = s == null;
        if (isNew) {
            s = defaults(app);
        }
        if (req.tokenClaim() != null) s.setTokenClaim(req.tokenClaim());
        if (req.snapshot() != null) s.setSnapshot(req.snapshot());
        if (req.webhook() != null) s.setWebhook(req.webhook());
        if (req.scim() != null) {
            if (req.scim()) {
                throw new BizException(ErrorCode.CONFLICT, "SCIM 下发将在二期提供");
            }
            s.setScim(false);
        }
        if (req.webhookUrl() != null) s.setWebhookUrl(req.webhookUrl().isBlank() ? null : req.webhookUrl().trim());
        if (StringUtils.hasText(req.secret())) s.setSecret(req.secret());
        if (req.events() != null) s.setEvents(req.events().isBlank() ? null : req.events());
        if (Boolean.TRUE.equals(s.getWebhook()) && !StringUtils.hasText(s.getWebhookUrl())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "开启 Webhook 推送需填写推送地址");
        }
        if (Boolean.TRUE.equals(s.getWebhook()) && !StringUtils.hasText(s.getSecret())) {
            s.setSecret(randomSecret());
        }
        s.setUpdatedAt(Instant.now());
        if (isNew) {
            subMapper.insert(s);
        } else {
            subMapper.updateById(s);
        }
        CurrentActor actor = guard.actor();
        audit.record(AuditService.Entry.of("ADMIN_CHANGE", "设置下发方式", "SUCCESS")
                .actor(actor.auditId(), actor.userName(), actor.ip()).target("app", app)
                .detail(Map.of("tokenClaim", s.getTokenClaim(), "snapshot", s.getSnapshot(), "webhook", s.getWebhook())));
        return view(a, s);
    }

    /** 该应用是否在令牌中下发 roles 声明（未配置时默认是）。 */
    public boolean tokenClaimEnabled(String app) {
        DistSubscription s = subMapper.selectById(app);
        return s == null || !Boolean.FALSE.equals(s.getTokenClaim());
    }

    public boolean webhookEnabled(String app) {
        DistSubscription s = subMapper.selectById(app);
        return s != null && Boolean.TRUE.equals(s.getWebhook()) && StringUtils.hasText(s.getWebhookUrl());
    }

    // ------------------------------------------------------------------ 事件与投递

    /**
     * 发出事件：为每个开启了 Webhook 且订阅了该事件的目标应用生成待投递记录（随所在事务一起提交）。
     */
    public DistEvent emit(String type, String refType, String refId, Collection<String> targetApps, Map<String, ?> data) {
        Instant now = Instant.now();
        DistEvent e = new DistEvent();
        e.setId(Ids.next("evt"));
        e.setType(type);
        e.setRefType(refType);
        e.setRefId(refId);
        e.setPayload(json.writeValueAsString(data));
        e.setCreatedAt(now);
        eventMapper.insert(e);
        if (targetApps.isEmpty()) {
            return e;
        }
        for (DistSubscription s : subMapper.selectByIds(targetApps)) {
            if (!Boolean.TRUE.equals(s.getWebhook()) || !StringUtils.hasText(s.getWebhookUrl()) || !subscribed(s, type)) {
                continue;
            }
            DistDelivery d = new DistDelivery();
            d.setId(Ids.next("dlv"));
            d.setEventId(e.getId());
            d.setAppCode(s.getAppCode());
            d.setStatus("PENDING");
            d.setAttempts(0);
            d.setNextAt(now);
            d.setCreatedAt(now);
            d.setUpdatedAt(now);
            deliveryMapper.insert(d);
        }
        return e;
    }

    public List<DistDtos.DeliveryView> deliveries(String refType, String refId, String app, int limit) {
        guard.require(PdsPermissions.ROLE_VIEW);
        LambdaQueryWrapper<DistEvent> ew = new LambdaQueryWrapper<DistEvent>()
                .eq(StringUtils.hasText(refType), DistEvent::getRefType, refType)
                .eq(StringUtils.hasText(refId), DistEvent::getRefId, refId)
                .orderByDesc(DistEvent::getCreatedAt).last("LIMIT 200");
        Map<String, DistEvent> events = eventMapper.selectList(ew).stream().collect(Collectors.toMap(DistEvent::getId, e -> e));
        if (events.isEmpty()) {
            return List.of();
        }
        Map<String, String> appNames = appMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.toMap(AppInfo::getCode, AppInfo::getName));
        return deliveryMapper.selectList(new LambdaQueryWrapper<DistDelivery>().in(DistDelivery::getEventId, events.keySet())
                        .eq(StringUtils.hasText(app), DistDelivery::getAppCode, app)
                        .orderByDesc(DistDelivery::getCreatedAt).last("LIMIT " + Math.max(1, Math.min(limit, 500)))).stream()
                .map(d -> {
                    DistEvent e = events.get(d.getEventId());
                    return new DistDtos.DeliveryView(d.getId(), d.getEventId(), e.getType(),
                            EVENT_LABELS.getOrDefault(e.getType(), e.getType()), d.getAppCode(),
                            appNames.getOrDefault(d.getAppCode(), d.getAppCode()), d.getStatus(), d.getAttempts(),
                            d.getLastError(), d.getLatencyMs(), d.getNextAt(), d.getCreatedAt(), d.getUpdatedAt());
                })
                .toList();
    }

    /** 投递载荷（查看用，即实际发送给该应用的请求体）。 */
    public DistDtos.EventPayload payload(String deliveryId) {
        guard.require(PdsPermissions.ROLE_VIEW);
        DistDelivery d = requireDelivery(deliveryId);
        DistEvent e = eventMapper.selectById(d.getEventId());
        return new DistDtos.EventPayload(e.getId(), e.getType(), body(e, d.getAppCode()));
    }

    @Transactional
    public void retry(String deliveryId) {
        guard.require(PdsPermissions.APP_MANAGE);
        DistDelivery d = requireDelivery(deliveryId);
        d.setStatus("PENDING");
        d.setNextAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        deliveryMapper.updateById(d);
    }

    /** 定时投递到期的记录。 */
    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void dispatchDue() {
        List<DistDelivery> due = deliveryMapper.selectList(new LambdaQueryWrapper<DistDelivery>()
                .in(DistDelivery::getStatus, List.of("PENDING", "RETRYING")).le(DistDelivery::getNextAt, Instant.now())
                .orderByAsc(DistDelivery::getNextAt).last("LIMIT 50"));
        for (DistDelivery d : due) {
            try {
                deliver(d);
            } catch (RuntimeException ex) {
                log.warn("投递 {} 处理异常", d.getId(), ex);
            }
        }
    }

    void deliver(DistDelivery d) {
        DistEvent e = eventMapper.selectById(d.getEventId());
        DistSubscription s = subMapper.selectById(d.getAppCode());
        Instant now = Instant.now();
        d.setAttempts(d.getAttempts() + 1);
        d.setUpdatedAt(now);
        if (e == null || s == null || !StringUtils.hasText(s.getWebhookUrl())) {
            fail(d, "应用未配置推送地址", now);
            return;
        }
        String body = body(e, d.getAppCode());
        String ts = String.valueOf(now.getEpochSecond());
        String nonce = randomSecret().substring(0, 16);
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-PDS-Event", e.getType())
                    .header("X-PDS-Delivery", d.getId())
                    .header("X-PDS-Timestamp", ts)
                    .header("X-PDS-Nonce", nonce)
                    .header("X-PDS-Signature", sign(s.getSecret(), ts + "." + nonce + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            d.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));
            if (resp.statusCode() / 100 == 2) {
                d.setStatus("SUCCESS");
                d.setLastError(null);
                d.setNextAt(null);
                deliveryMapper.updateById(d);
                return;
            }
            fail(d, "HTTP " + resp.statusCode(), now);
        } catch (java.net.http.HttpTimeoutException ex) {
            d.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));
            fail(d, "连接超时", now);
        } catch (java.io.IOException ex) {
            d.setLatencyMs(null);
            fail(d, "连接失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), now);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(d, "投递被中断", now);
        } catch (IllegalArgumentException ex) {
            fail(d, "推送地址无效", now);
        }
    }

    private void fail(DistDelivery d, String error, Instant now) {
        d.setLastError(error.length() > 500 ? error.substring(0, 500) : error);
        if (d.getAttempts() >= MAX_ATTEMPTS) {
            d.setStatus("FAILED");
            d.setNextAt(null);
        } else {
            d.setStatus("RETRYING");
            d.setNextAt(now.plus(BACKOFF[Math.min(d.getAttempts() - 1, BACKOFF.length - 1)]));
        }
        deliveryMapper.updateById(d);
    }

    /** 投递给某应用的请求体：事件信封 + 数据（角色事件按该应用的外部编码填充 externalCode）。 */
    private String body(DistEvent e, String app) {
        Map<String, Object> data = new LinkedHashMap<>(json.readValue(e.getPayload(), new TypeReference<Map<String, Object>>() {
        }));
        Object codes = data.remove("externalCodes");
        if (codes instanceof Map<?, ?> m && m.get(app) != null) {
            data.put("externalCode", m.get(app));
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", e.getId());
        env.put("type", e.getType());
        env.put("app", app);
        env.put("occurredAt", e.getCreatedAt().toString());
        env.put("authzVersion", authz.version());
        env.put("data", data);
        return json.writeValueAsString(env);
    }

    private static boolean subscribed(DistSubscription s, String type) {
        if (!StringUtils.hasText(s.getEvents())) {
            return true;
        }
        Set<String> events = Set.of(s.getEvents().split(","));
        return events.contains(type) || events.stream().anyMatch(x -> x.endsWith(".*") && type.startsWith(x.substring(0, x.length() - 1)));
    }

    static String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String randomSecret() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private DistSubscription defaults(String app) {
        DistSubscription s = new DistSubscription();
        s.setAppCode(app);
        s.setTokenClaim(true);
        s.setSnapshot(true);
        s.setWebhook(false);
        s.setScim(false);
        return s;
    }

    private DistDtos.SubscriptionView view(AppInfo a, DistSubscription s) {
        DistSubscription x = s == null ? defaults(a.getCode()) : s;
        return new DistDtos.SubscriptionView(a.getCode(), a.getName(), !Boolean.FALSE.equals(x.getTokenClaim()),
                !Boolean.FALSE.equals(x.getSnapshot()), Boolean.TRUE.equals(x.getWebhook()), Boolean.TRUE.equals(x.getScim()),
                x.getWebhookUrl(), StringUtils.hasText(x.getSecret()), x.getEvents(), x.getUpdatedAt());
    }

    private AppInfo requireApp(String app) {
        AppInfo a = appMapper.selectById(app);
        if (a == null) {
            throw BizException.notFound("应用【" + app + "】");
        }
        return a;
    }

    private DistDelivery requireDelivery(String id) {
        DistDelivery d = deliveryMapper.selectById(id);
        if (d == null) {
            throw BizException.notFound("投递记录");
        }
        return d;
    }
}
