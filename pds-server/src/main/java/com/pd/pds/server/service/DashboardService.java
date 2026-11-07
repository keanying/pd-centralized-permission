package com.pd.pds.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pd.pds.server.entity.AppInfo;
import com.pd.pds.server.entity.AuditEvent;
import com.pd.pds.server.entity.OrgUnit;
import com.pd.pds.server.entity.Role;
import com.pd.pds.server.entity.SysUser;
import com.pd.pds.server.mapper.AppInfoMapper;
import com.pd.pds.server.mapper.AuditEventMapper;
import com.pd.pds.server.mapper.OrgUnitMapper;
import com.pd.pds.server.mapper.RoleMapper;
import com.pd.pds.server.mapper.SysUserMapper;
import com.pd.pds.server.security.Guard;
import com.pd.pds.server.security.PdsPermissions;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台工作台统计（一期直接从业务表与审计表汇总；数据量大后改为定时汇总）。
 */
@Service
public class DashboardService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SysUserMapper userMapper;
    private final OrgUnitMapper orgMapper;
    private final AppInfoMapper appMapper;
    private final RoleMapper roleMapper;
    private final AuditEventMapper auditMapper;
    private final Guard guard;

    public DashboardService(SysUserMapper userMapper, OrgUnitMapper orgMapper, AppInfoMapper appMapper, RoleMapper roleMapper,
                            AuditEventMapper auditMapper, Guard guard) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.appMapper = appMapper;
        this.roleMapper = roleMapper;
        this.auditMapper = auditMapper;
        this.guard = guard;
    }

    public record DayCount(String date, long success, long failure) {
    }

    public record AppCategoryCount(String category, String name, long count) {
    }

    public record Overview(long users, long activeUsers, long lockedUsers, long mustChangePassword, long scenics, long orgs,
                           long apps, long roles, long todayLogins, long todayLoginFailures, long todayDenied,
                           long todayTokenExchanges, List<DayCount> loginTrend, List<AppCategoryCount> appsByCategory,
                           List<AuditEvent> recentAlerts) {
    }

    public Overview overview() {
        guard.require(PdsPermissions.CONSOLE);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE);
        Instant todayStart = today.atStartOfDay(ZONE).toInstant();
        Instant weekStart = today.minusDays(6).atStartOfDay(ZONE).toInstant();

        long users = userMapper.selectCount(null);
        long active = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE));
        long locked = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().gt(SysUser::getLockedUntil, now));
        long mustChange = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMustChangePassword, true)
                .eq(SysUser::getStatus, AuthzService.STATUS_ACTIVE));
        long scenics = orgMapper.selectCount(new LambdaQueryWrapper<OrgUnit>().eq(OrgUnit::getType, "SCENIC"));
        long orgs = orgMapper.selectCount(null);
        long roles = roleMapper.selectCount(new LambdaQueryWrapper<Role>());

        List<AuditEvent> week = auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .select(AuditEvent::getTs, AuditEvent::getEventType)
                .ge(AuditEvent::getTs, weekStart)
                .in(AuditEvent::getEventType, List.of("LOGIN", "LOGIN_FAIL", "ACCESS_DENIED", "TOKEN_EXCHANGE")));
        Map<String, long[]> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            byDay.put(today.minusDays(i).toString(), new long[2]);
        }
        long todayLogins = 0, todayFail = 0, todayDenied = 0, todayExchange = 0;
        for (AuditEvent e : week) {
            String day = LocalDate.ofInstant(e.getTs(), ZONE).toString();
            long[] c = byDay.get(day);
            boolean isToday = !e.getTs().isBefore(todayStart);
            switch (e.getEventType()) {
                case "LOGIN" -> {
                    if (c != null) c[0]++;
                    if (isToday) todayLogins++;
                }
                case "LOGIN_FAIL" -> {
                    if (c != null) c[1]++;
                    if (isToday) todayFail++;
                }
                case "ACCESS_DENIED" -> {
                    if (isToday) todayDenied++;
                }
                case "TOKEN_EXCHANGE" -> {
                    if (isToday) todayExchange++;
                }
                default -> {
                }
            }
        }
        List<DayCount> trend = new ArrayList<>();
        byDay.forEach((d, c) -> trend.add(new DayCount(d, c[0], c[1])));

        List<AppInfo> apps = appMapper.selectList(null);
        Map<String, Long> byCat = apps.stream().collect(Collectors.groupingBy(
                a -> a.getCategory() == null ? "UNCATEGORIZED" : a.getCategory(), LinkedHashMap::new, Collectors.counting()));
        List<AppCategoryCount> cats = byCat.entrySet().stream()
                .map(e -> new AppCategoryCount(e.getKey(), "UNCATEGORIZED".equals(e.getKey()) ? "未分类"
                        : AppCategories.ALL.getOrDefault(e.getKey(), e.getKey()).replaceAll("（.*", ""), e.getValue()))
                .toList();

        List<AuditEvent> alerts = guard.has(PdsPermissions.AUDIT_VIEW)
                ? auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .in(AuditEvent::getEventType, List.of("ACCESS_DENIED", "LOGIN_FAIL"))
                .orderByDesc(AuditEvent::getSeq).last("LIMIT 6"))
                : List.of();
        return new Overview(users, active, locked, mustChange, scenics, orgs, apps.size(), roles, todayLogins, todayFail,
                todayDenied, todayExchange, trend, cats, alerts);
    }
}
