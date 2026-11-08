package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pd.pds.common.Ids;
import com.pd.pds.common.PageResult;
import com.pd.pds.server.entity.AuditEvent;
import com.pd.pds.server.mapper.AuditEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 审计服务：只追加、哈希链防篡改。
 *
 * <p>一期为单实例内存维护链尾；多实例部署时按技术方案改为"MQ + 单消费者"写入。
 * 审计写入使用独立事务（REQUIRES_NEW），不随业务事务回滚，链尾只在写入成功后推进。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    static final String GENESIS = "0".repeat(64);

    private final AuditEventMapper mapper;
    private final JsonMapper json;

    private boolean loaded;
    private long lastSeq;
    private String lastHash;

    public AuditService(AuditEventMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** 一条待写入的审计记录。 */
    public record Entry(String app, String eventType, String userId, String userName, String via,
                        String scenicId, String ip, String targetType, String targetIds, String action,
                        String result, Map<String, ?> detail, String traceId) {

        public static Entry of(String eventType, String action, String result) {
            return new Entry("pds", eventType, null, null, null, null, null, null, null, action, result, null, null);
        }

        public Entry actor(String userId, String userName, String ip) {
            return new Entry(app, eventType, userId, userName, via, scenicId, ip, targetType, targetIds, action, result, detail, traceId);
        }

        public Entry target(String type, String ids) {
            return new Entry(app, eventType, userId, userName, via, scenicId, ip, type, ids, action, result, detail, traceId);
        }

        public Entry detail(Map<String, ?> d) {
            return new Entry(app, eventType, userId, userName, via, scenicId, ip, targetType, targetIds, action, result, d, traceId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized void record(Entry e) {
        ensureLoaded();
        AuditEvent ev = new AuditEvent();
        ev.setSeq(lastSeq + 1);
        ev.setId(Ids.next("ae"));
        ev.setTs(Instant.now());
        ev.setApp(e.app() == null ? "pds" : e.app());
        ev.setEventType(e.eventType());
        ev.setUserId(e.userId());
        ev.setUserName(e.userName());
        ev.setVia(e.via());
        ev.setScenicId(e.scenicId());
        ev.setIp(e.ip());
        ev.setTargetType(e.targetType());
        ev.setTargetIds(truncate(e.targetIds(), 2000));
        ev.setAction(truncate(e.action(), 128));
        ev.setResult(e.result());
        ev.setDetail(e.detail() == null ? null : json.writeValueAsString(e.detail()));
        ev.setTraceId(e.traceId());
        ev.setPrevHash(lastHash);
        ev.setHash(hashOf(ev));
        mapper.insert(ev);
        lastSeq = ev.getSeq();
        lastHash = ev.getHash();
    }

    /** 记录失败不影响业务（例如登录事件），只打日志。 */
    public void recordQuietly(Entry e) {
        try {
            record(e);
        } catch (RuntimeException ex) {
            log.error("审计写入失败：{} {}", e.eventType(), e.action(), ex);
        }
    }

    /** eventType 支持逗号分隔的多个类型。 */
    public PageResult<AuditEvent> query(String userId, String eventType, String app, String result, String scenicId,
                                        Instant from, Instant to, long page, long size) {
        List<String> types = StringUtils.hasText(eventType)
                ? java.util.Arrays.stream(eventType.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList() : List.of();
        LambdaQueryWrapper<AuditEvent> w = new LambdaQueryWrapper<AuditEvent>()
                .eq(StringUtils.hasText(userId), AuditEvent::getUserId, userId)
                .in(!types.isEmpty(), AuditEvent::getEventType, types)
                .eq(StringUtils.hasText(scenicId), AuditEvent::getScenicId, scenicId)
                .eq(StringUtils.hasText(app), AuditEvent::getApp, app)
                .eq(StringUtils.hasText(result), AuditEvent::getResult, result)
                .ge(from != null, AuditEvent::getTs, from)
                .lt(to != null, AuditEvent::getTs, to)
                .orderByDesc(AuditEvent::getSeq);
        Page<AuditEvent> p = mapper.selectPage(new Page<>(page, size), w);
        return new PageResult<>(p.getTotal(), page, size, p.getRecords());
    }

    public record VerifyResult(boolean intact, long checked, Long firstBrokenSeq, String message) {
    }

    /** 单条校验：本条哈希可重算，且与前一条哈希相连。 */
    public VerifyResult verifyOne(String id) {
        AuditEvent ev = mapper.selectOne(new LambdaQueryWrapper<AuditEvent>().eq(AuditEvent::getId, id));
        if (ev == null) {
            throw com.pd.pds.common.BizException.notFound("审计记录");
        }
        if (!hashOf(ev).equals(ev.getHash())) {
            return new VerifyResult(false, 1, ev.getSeq(), "本条记录内容与哈希不符，可能被篡改");
        }
        String expectedPrev = ev.getSeq() == 1 ? GENESIS : java.util.Optional.ofNullable(mapper.selectById(ev.getSeq() - 1))
                .map(AuditEvent::getHash).orElse(null);
        if (!ev.getPrevHash().equals(expectedPrev)) {
            return new VerifyResult(false, 1, ev.getSeq(), "与前一条记录的哈希链断开");
        }
        return new VerifyResult(true, 1, null, "哈希链未被篡改");
    }

    /** 逐条重算哈希链，发现第一处不一致即返回。 */
    public VerifyResult verify() {
        String prev = GENESIS;
        long checked = 0;
        long cursor = 0;
        while (true) {
            List<AuditEvent> batch = mapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                    .gt(AuditEvent::getSeq, cursor).orderByAsc(AuditEvent::getSeq).last("LIMIT 1000"));
            if (batch.isEmpty()) {
                return new VerifyResult(true, checked, null, "完整性校验通过，共 " + checked + " 条");
            }
            for (AuditEvent ev : batch) {
                if (!prev.equals(ev.getPrevHash()) || !hashOf(ev).equals(ev.getHash())) {
                    return new VerifyResult(false, checked, ev.getSeq(), "第 " + ev.getSeq() + " 条审计记录被篡改或链路断开");
                }
                prev = ev.getHash();
                checked++;
                cursor = ev.getSeq();
            }
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        AuditEvent last = mapper.selectOne(new LambdaQueryWrapper<AuditEvent>()
                .orderByDesc(AuditEvent::getSeq).last("LIMIT 1"));
        lastSeq = last == null ? 0 : last.getSeq();
        lastHash = last == null ? GENESIS : last.getHash();
        loaded = true;
    }

    static String hashOf(AuditEvent ev) {
        String canonical = String.join("|",
                ev.getPrevHash(), String.valueOf(ev.getSeq()), ev.getId(), String.valueOf(ev.getTs().toEpochMilli()),
                nz(ev.getApp()), nz(ev.getEventType()), nz(ev.getUserId()), nz(ev.getUserName()), nz(ev.getVia()),
                nz(ev.getScenicId()), nz(ev.getIp()), nz(ev.getTargetType()), nz(ev.getTargetIds()), nz(ev.getAction()),
                nz(ev.getResult()), nz(ev.getDetail()), nz(ev.getTraceId()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
