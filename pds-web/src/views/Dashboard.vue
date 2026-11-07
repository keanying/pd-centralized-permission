<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { IconUser, IconShield, IconAlertTriangle, IconKey, IconAppCenter, IconBranch } from '@kousum/semi-icons-vue'
import NumberTicker from '@/components/inspira/NumberTicker.vue'
import BorderBeam from '@/components/inspira/BorderBeam.vue'
import FlickeringGrid from '@/components/inspira/FlickeringGrid.vue'
import CardSpotlight from '@/components/inspira/CardSpotlight.vue'
import { api, EVENT_TYPES, type Overview } from '@/api'
import { formatTime } from '@/lib/utils'
import { useSession } from '@/stores/session'

use([CanvasRenderer, BarChart, GridComponent, LegendComponent, TooltipComponent])

const session = useSession()
const data = ref<Overview | null>(null)
onMounted(async () => (data.value = await api.overview()))

// 已用 dataviz 校验：#1E50E6 / #E8590C 两色在色盲模拟下仍可区分，对白底对比度 ≥ 3:1
const SERIES = { success: '#1E50E6', failure: '#E8590C' }
const AXIS = { axisLine: { lineStyle: { color: '#E4E9F4' } }, axisTick: { show: false }, axisLabel: { color: '#6B7280' } }

const trendOption = computed(() => {
  const t = data.value?.loginTrend ?? []
  return {
    grid: { left: 36, right: 12, top: 36, bottom: 28 },
    legend: { top: 0, right: 0, itemWidth: 10, itemHeight: 10, icon: 'roundRect', textStyle: { color: '#374151' } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(30,80,230,.06)' } } },
    xAxis: { type: 'category', data: t.map((d) => d.date.slice(5)), ...AXIS },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#EEF1F7' } }, axisLabel: { color: '#6B7280' } },
    series: [
      { name: '登录成功', type: 'bar', data: t.map((d) => d.success), barMaxWidth: 18, barGap: '12%', itemStyle: { color: SERIES.success, borderRadius: [4, 4, 0, 0] } },
      { name: '登录失败', type: 'bar', data: t.map((d) => d.failure), barMaxWidth: 18, itemStyle: { color: SERIES.failure, borderRadius: [4, 4, 0, 0] } },
    ],
  }
})

const categoryOption = computed(() => {
  const c = [...(data.value?.appsByCategory ?? [])].sort((a, b) => a.count - b.count)
  return {
    grid: { left: 96, right: 28, top: 4, bottom: 4 },
    tooltip: { trigger: 'item', formatter: (p: any) => `${p.name}：${p.value} 个应用` },
    xAxis: { type: 'value', show: false, minInterval: 1 },
    yAxis: { type: 'category', data: c.map((x) => x.name), ...AXIS, axisLine: { show: false } },
    series: [{
      type: 'bar', data: c.map((x) => x.count), barMaxWidth: 14,
      itemStyle: { color: SERIES.success, borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', color: '#374151' },
    }],
  }
})

const kpis = computed(() => {
  const d = data.value
  return [
    { label: '人员总数', value: d?.users ?? 0, sub: `在职 ${d?.activeUsers ?? 0}`, icon: IconUser },
    { label: '今日登录', value: d?.todayLogins ?? 0, sub: `失败 ${d?.todayLoginFailures ?? 0} 次`, icon: IconKey },
    { label: '今日越权拦截', value: d?.todayDenied ?? 0, sub: '含应用准入拒绝', icon: IconAlertTriangle },
    { label: '锁定账号', value: d?.lockedUsers ?? 0, sub: `待改密 ${d?.mustChangePassword ?? 0} 人`, icon: IconShield },
  ]
})
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- 主视觉 -->
    <div class="relative overflow-hidden rounded-[24px] bg-gradient-to-br from-brand to-brand-dark px-8 py-7 text-white shadow-[0_10px_24px_rgba(30,80,230,.18)]">
      <FlickeringGrid class="absolute inset-0 z-0 opacity-60" :square-size="4" :grid-gap="6" color="rgb(34,229,154)" :max-opacity="0.18" :flicker-chance="0.08" />
      <BorderBeam :size="260" :duration="9000" color-from="#22E59A" color-to="#ffffff" />
      <div class="relative z-10 flex flex-wrap items-end justify-between gap-6">
        <div>
          <div class="mb-2 flex items-center gap-3">
            <span class="rounded-full bg-white/15 px-3 py-1 text-xs">PD 统一权限中心</span>
          </div>
          <h2 class="m-0 text-[28px] font-bold leading-tight">您好，{{ session.me?.name }}</h2>
          <p class="mb-0 mt-2 text-sm text-white/80">统一登录 · 角色授权 · 景区隔离 · 字段脱敏 · 访问审计</p>
        </div>
        <div class="flex gap-3">
          <div v-for="s in [
            { label: '接入应用', value: data?.apps ?? 0, icon: IconAppCenter },
            { label: '景区', value: data?.scenics ?? 0, icon: IconBranch },
            { label: '角色', value: data?.roles ?? 0, icon: IconShield },
          ]" :key="s.label" class="min-w-[120px] rounded-2xl bg-white/12 px-5 py-3 backdrop-blur-sm" style="background: rgba(255,255,255,.12)">
            <div class="flex items-center gap-2 text-xs text-white/75"><component :is="s.icon" size="small" />{{ s.label }}</div>
            <NumberTicker :value="s.value" :decimal-places="0" class="mt-1 text-[26px] font-bold text-white dark:text-white" />
          </div>
        </div>
      </div>
    </div>

    <!-- 指标卡 -->
    <div class="grid grid-cols-2 gap-5 xl:grid-cols-4">
      <CardSpotlight v-for="k in kpis" :key="k.label" class="border-0 bg-white shadow-[0_6px_24px_rgba(11,27,77,.06)] dark:bg-white"
                     gradient-color="#E8EEFD" :gradient-opacity="0.9" slot-class="w-full">
        <div class="flex w-full items-start justify-between p-5">
          <div>
            <div class="text-[13px] text-muted">{{ k.label }}</div>
            <NumberTicker :value="k.value" :decimal-places="0" class="mt-1 text-[30px] font-bold text-ink dark:text-ink" />
            <div class="pds-muted">{{ k.sub }}</div>
          </div>
          <span class="flex size-10 items-center justify-center rounded-xl bg-brand-soft text-brand"><component :is="k.icon" /></span>
        </div>
      </CardSpotlight>
    </div>

    <div class="grid grid-cols-1 gap-5 xl:grid-cols-3">
      <div class="pds-card xl:col-span-2">
        <div class="pds-card-title">近 7 日登录 <span class="pds-muted">按天统计（东八区）</span></div>
        <v-chart :option="trendOption" autoresize style="height: 280px" />
      </div>
      <div class="pds-card">
        <div class="pds-card-title">接入应用分布 <span class="pds-muted">按架构分层</span></div>
        <v-chart :option="categoryOption" autoresize style="height: 280px" />
      </div>
    </div>

    <div v-if="session.has('pds:audit:view')" class="pds-card">
      <div class="pds-card-title">
        最新安全事件
        <router-link to="/audit"><Button type="text" size="small">查看审计中心</Button></router-link>
      </div>
      <Table :columns="[
        { title: '时间', key: 'ts', width: 160, render: (h: any, p: any) => h('span', formatTime(p.row.ts)) },
        { title: '类型', key: 'eventType', width: 120, render: (h: any, p: any) => h('span', EVENT_TYPES[p.row.eventType] || p.row.eventType) },
        { title: '人员', key: 'userName', width: 140 },
        { title: '内容', key: 'action', minWidth: 200 },
        { title: '来源 IP', key: 'ip', width: 140 },
      ]" :data="data?.recentAlerts ?? []" size="small" no-data-text="暂无安全事件" />
    </div>
  </div>
</template>
