package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.AuditEvent;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.entity.UserOrg;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.AuditEventMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.mapper.UserOrgMapper;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理后台工作台统计（与 UX Pilot 设计稿"工作台"一一对应）。
 * 一期直接从业务表、授权记录表与审计表汇总；数据量大后改为定时汇总。
 * 尚无数据来源的指标返回 null，前端显示"二期"，不编造数字。
 */
@Service
public class DashboardService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration LONG_INACTIVE = Duration.ofDays(90);
    private static final Duration EXPIRING_WINDOW = Duration.ofDays(30);

    private final SysUserMapper userMapper;
    private final OrgUnitMapper orgMapper;
    private final UserOrgMapper userOrgMapper;
    private final AppInfoMapper appMapper;
    private final AuditEventMapper auditMapper;
    private final JdbcTemplate jdbc;
    private final Guard guard;

    public DashboardService(SysUserMapper userMapper, OrgUnitMapper orgMapper, UserOrgMapper userOrgMapper,
                            AppInfoMapper appMapper, AuditEventMapper auditMapper, JdbcTemplate jdbc, Guard guard) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.userOrgMapper = userOrgMapper;
        this.appMapper = appMapper;
        this.auditMapper = auditMapper;
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public record ScenicStat(String id, String name, long users) {
    }

    /** 周一到周日的登录次数（东八区）。 */
    public record WeekTrend(List<Long> thisWeek, List<Long> lastWeek, int todayIndex) {
    }

    public record Security(long activeUsers, long activatedUsers, long longInactive, long locked, long mustChangePassword) {
    }

    public record AiUsage(String scenicId, String name, long chats, long retrievals, long sensitive) {
    }

    public record Overview(
            long onlineUsers, long activeUsers, long todayLogins, List<Long> loginSparkline,
            long apps, boolean healthy, String healthText,
            long todayDenied, Integer deniedChangePercent,
            Long pendingApprovals, long expiringGrants,
            List<ScenicStat> scenics, WeekTrend loginWeek, Security security, List<AiUsage> aiUsage,
            List<AuditEvent> recentAlerts) {
    }

    public Overview overview() {
        guard.require(PdsPermissions.CONSOLE);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE);
        Instant todayStart = today.atStartOfDay(ZONE).toInstant();
        Instant yesterdayStart = today.minusDays(1).atStartOfDay(ZONE).toInstant();
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant lastWeekStart = thisMonday.minusWeeks(1).atStartOfDay(ZONE).toInstant();
        Instant sevenDaysAgo = today.minusDays(6).atStartOfDay(ZONE).toInstant();
        Instant from = lastWeekStart.isBefore(sevenDaysAgo) ? lastWeekStart : sevenDaysAgo;

        // 人员与在线（在线 = 持有未过期访问令牌的人员）
        long active = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE));
        Long online = jdbc.queryForObject("SELECT COUNT(DISTINCT principal_name) FROM oauth2_authorization "
                + "WHERE access_token_expires_at > ? AND authorization_grant_type <> 'client_credentials'", Long.class,
                java.sql.Timestamp.from(now));

        // 登录 / 拦截（审计）
        List<AuditEvent> events = auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .select(AuditEvent::getTs, AuditEvent::getEventType)
                .ge(AuditEvent::getTs, from)
                .in(AuditEvent::getEventType, List.of("LOGIN", "ACCESS_DENIED")));
        long[] thisWeek = new long[7];
        long[] lastWeek = new long[7];
        long[] spark = new long[7];
        long todayLogins = 0, todayDenied = 0, yesterdayDenied = 0;
        for (AuditEvent e : events) {
            LocalDate day = LocalDate.ofInstant(e.getTs(), ZONE);
            if ("LOGIN".equals(e.getEventType())) {
                long weeks = java.time.temporal.ChronoUnit.WEEKS.between(thisMonday, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                int idx = day.getDayOfWeek().getValue() - 1;
                if (weeks == 0) thisWeek[idx]++;
                else if (weeks == -1) lastWeek[idx]++;
                long ago = java.time.temporal.ChronoUnit.DAYS.between(day, today);
                if (ago >= 0 && ago < 7) spark[(int) (6 - ago)]++;
                if (!e.getTs().isBefore(todayStart)) todayLogins++;
            } else {
                if (!e.getTs().isBefore(todayStart)) todayDenied++;
                else if (!e.getTs().isBefore(yesterdayStart)) yesterdayDenied++;
            }
        }
        Integer deniedChange = yesterdayDenied == 0 ? null
                : (int) Math.round((todayDenied - yesterdayDenied) * 100.0 / yesterdayDenied);

        // 即将到期授权（30 天内）：角色绑定 + 人员明细 + 资源授权
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        java.sql.Timestamp until = java.sql.Timestamp.from(now.plus(EXPIRING_WINDOW));
        long expiring = 0;
        for (String table : List.of("auth_role_binding", "auth_user_perm", "auth_resource_grant")) {
            Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE valid_to > ? AND valid_to <= ?", Long.class, nowTs, until);
            expiring += c == null ? 0 : c;
        }

        // 景区分布：各景区（含下级组织）的在职人员数
        List<OrgUnit> orgs = orgMapper.selectList(null);
        Map<String, String> orgScenic = orgs.stream().filter(o -> o.getScenicId() != null)
                .collect(Collectors.toMap(OrgUnit::getId, OrgUnit::getScenicId));
        Set<String> activeIds = userMapper.selectList(new LambdaQueryWrapper<SysUser>().select(SysUser::getId)
                .eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE)).stream().map(SysUser::getId).collect(Collectors.toSet());
        Map<String, Set<String>> usersByScenic = new HashMap<>();
        for (UserOrg uo : userOrgMapper.selectList(null)) {
            String scenic = orgScenic.get(uo.getOrgId());
            if (scenic != null && activeIds.contains(uo.getUserId())) {
                usersByScenic.computeIfAbsent(scenic, k -> new HashSet<>()).add(uo.getUserId());
            }
        }
        List<ScenicStat> scenics = orgs.stream().filter(o -> "SCENIC".equals(o.getType()))
                .sorted(java.util.Comparator.comparing((OrgUnit o) -> o.getSort() == null ? 0 : o.getSort()).thenComparing(OrgUnit::getCode))
                .map(o -> new ScenicStat(o.getId(), o.getName(), usersByScenic.getOrDefault(o.getId(), Set.of()).size()))
                .toList();

        // 安全态势
        long activated = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE)
                .isNotNull(SysUser::getLastLoginAt));
        Instant inactiveBefore = now.minus(LONG_INACTIVE);
        long longInactive = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE)
                .and(q -> q.lt(SysUser::getLastLoginAt, inactiveBefore)
                        .or(x -> x.isNull(SysUser::getLastLoginAt).lt(SysUser::getCreatedAt, inactiveBefore))));
        long locked = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().gt(SysUser::getLockedUntil, now));
        long mustChange = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMustChangePassword, true)
                .eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE));

        // AI 使用概况（近 7 日，按景区）：问答 AI_CHAT、知识检索 DATA_ACCESS、敏感访问（查看明文/下载/导出）
        List<AuditEvent> ai = auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .select(AuditEvent::getScenicId, AuditEvent::getEventType)
                .ge(AuditEvent::getTs, sevenDaysAgo)
                .in(AuditEvent::getEventType, List.of("AI_CHAT", "DATA_ACCESS", "MASK_REVEAL", "DATA_DOWNLOAD", "DATA_EXPORT")));
        Map<String, long[]> aiByScenic = new LinkedHashMap<>();
        scenics.forEach(s -> aiByScenic.put(s.id(), new long[3]));
        for (AuditEvent e : ai) {
            String key = e.getScenicId() != null && aiByScenic.containsKey(e.getScenicId()) ? e.getScenicId() : "GROUP";
            long[] c = aiByScenic.computeIfAbsent(key, k -> new long[3]);
            switch (e.getEventType()) {
                case "AI_CHAT" -> c[0]++;
                case "DATA_ACCESS" -> c[1]++;
                default -> c[2]++;
            }
        }
        Map<String, String> scenicNames = scenics.stream().collect(Collectors.toMap(ScenicStat::id, ScenicStat::name));
        List<AiUsage> aiUsage = new ArrayList<>();
        aiByScenic.forEach((id, c) -> aiUsage.add(new AiUsage(id, "GROUP".equals(id) ? "集团" : scenicNames.getOrDefault(id, id), c[0], c[1], c[2])));

        // 健康：数据库可用即视为正常（详细健康见 /actuator/health）
        boolean healthy;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            healthy = true;
        } catch (RuntimeException ex) {
            healthy = false;
        }

        List<AuditEvent> alerts = guard.has(PdsPermissions.AUDIT_VIEW)
                ? auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .in(AuditEvent::getEventType, List.of("ACCESS_DENIED", "LOGIN_FAIL"))
                .ge(AuditEvent::getTs, todayStart)
                .orderByDesc(AuditEvent::getSeq).last("LIMIT 8"))
                : List.of();

        return new Overview(online == null ? 0 : online, active, todayLogins, toList(spark),
                appMapper.selectCount(new LambdaQueryWrapper<AppInfo>()), healthy, healthy ? "运行正常" : "数据库连接异常",
                todayDenied, deniedChange, null, expiring, scenics,
                new WeekTrend(toList(thisWeek), toList(lastWeek), today.getDayOfWeek().getValue() - 1),
                new Security(active, activated, longInactive, locked, mustChange), aiUsage, alerts);
    }

    private static List<Long> toList(long[] a) {
        List<Long> l = new ArrayList<>(a.length);
        for (long v : a) {
            l.add(v);
        }
        return l;
    }
}
