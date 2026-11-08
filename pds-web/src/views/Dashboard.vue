<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { IconShield, IconEdit, IconHourglass } from '@kousum/semi-icons-vue'
import NumberTicker from '@/components/inspira/NumberTicker.vue'
import HeroIllustration from '@/components/pds/HeroIllustration.vue'
import { api, type Overview } from '@/api'
import { useSession } from '@/stores/session'
import { usePage } from '@/stores/page'

const router = useRouter()
const session = useSession()
const page = usePage()
const d = ref<Overview | null>(null)
const week = ref<'this' | 'last'>('this')
const hoverBar = ref<number | null>(null)
const hoverAi = ref<number | null>(null)

onMounted(async () => {
  page.set('工作台', { subtitle: `欢迎回来，${session.me?.name ?? ''}` })
  d.value = await api.overview()
})

const fmt = (n: number) => n.toLocaleString('en-US')
const DAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

// 登录趋势：本周默认高亮今天，上周高亮最大值；悬停任一柱显示数值
const bars = computed(() => (week.value === 'this' ? d.value?.loginWeek.thisWeek : d.value?.loginWeek.lastWeek) ?? [])
const barMax = computed(() => Math.max(1, ...bars.value))
const highlight = computed(() => {
  if (hoverBar.value !== null) return hoverBar.value
  if (week.value === 'this') return d.value?.loginWeek.todayIndex ?? 0
  const m = Math.max(...bars.value)
  return m > 0 ? bars.value.indexOf(m) : -1
})

// 在线人数进度 = 在线 / 在职
const onlinePct = computed(() => (d.value && d.value.activeUsers ? Math.min(100, (d.value.onlineUsers / d.value.activeUsers) * 100) : 0))
// 今日登录走势（近 7 日折线）
const sparkPath = computed(() => {
  const s = d.value?.loginSparkline ?? []
  if (!s.length) return ''
  const max = Math.max(1, ...s)
  return s.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i / (s.length - 1)) * 100},${22 - (v / max) * 20}`).join(' ')
})

const scenicMax = computed(() => Math.max(1, ...(d.value?.scenics ?? []).map((s) => s.users)))
// 景区分布示意点（景区尚无坐标，按顺序均匀分布于示意区域内）
const DOTS = [[30, 48], [63, 42], [70, 35], [48, 62], [80, 58], [40, 30]]

const sec = computed(() => d.value?.security)
const activationPct = computed(() => (sec.value && sec.value.activeUsers ? Math.round((sec.value.activatedUsers / sec.value.activeUsers) * 100) : 0))
const ring = computed(() => {
  const r = 46
  const c = 2 * Math.PI * r
  return { r, c, dash: (activationPct.value / 100) * c }
})

const AI_SERIES = [
  { key: 'chats', label: '问答量', color: '#A3D65C' },
  { key: 'retrievals', label: '知识检索量', color: '#1F2421' },
  { key: 'sensitive', label: '敏感访问', color: '#F87171' },
] as const
const aiMax = computed(() => Math.max(1, ...(d.value?.aiUsage ?? []).flatMap((a) => [a.chats, a.retrievals, a.sensitive])))
const aiEmpty = computed(() => (d.value?.aiUsage ?? []).every((a) => !a.chats && !a.retrievals && !a.sensitive))
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-[1fr_1fr_1fr_1.45fr]">
    <!-- 主视觉 -->
    <div class="relative overflow-hidden rounded-[24px] bg-ink px-8 py-8 text-white xl:col-span-4">
      <div class="relative z-10 grid grid-cols-1 items-center gap-6 lg:grid-cols-[1.1fr_1fr_0.75fr]">
        <div>
          <h2 class="m-0 text-[34px] font-bold leading-tight">PDS 统一权限</h2>
          <p class="mb-6 mt-2 text-[14px] text-white/60">统一登录 · 角色授权 · 景区隔离 · 字段脱敏 · 访问审计</p>
          <div class="grid grid-cols-2 gap-4">
            <div class="rounded-2xl bg-ink-2 px-4 py-4">
              <div class="text-[12px] text-white/60">在线人数</div>
              <NumberTicker :value="d?.onlineUsers ?? 0" :decimal-places="0" class="mt-1 text-[24px] font-bold text-white dark:text-white" />
              <div class="mt-3 h-1.5 rounded-full bg-white/15"><div class="h-1.5 rounded-full bg-accent" :style="{ width: onlinePct + '%' }" /></div>
              <div class="mt-1.5 text-[11px] text-white/45">在职 {{ fmt(d?.activeUsers ?? 0) }} 人</div>
            </div>
            <div class="rounded-2xl bg-ink-2 px-4 py-4">
              <div class="text-[12px] text-white/60">今日登录</div>
              <NumberTicker :value="d?.todayLogins ?? 0" :decimal-places="0" class="mt-1 text-[24px] font-bold text-white dark:text-white" />
              <svg viewBox="0 0 100 24" class="mt-2 h-6 w-full" preserveAspectRatio="none"><path :d="sparkPath" fill="none" stroke="#A3D65C" stroke-width="2" vector-effect="non-scaling-stroke" /></svg>
              <div class="text-[11px] text-white/45">近 7 日走势</div>
            </div>
          </div>
        </div>
        <div class="hidden h-[220px] lg:block"><HeroIllustration /></div>
        <div class="flex flex-col gap-4">
          <div class="cursor-pointer rounded-2xl bg-ink-2 px-5 py-5" @click="router.push('/apps')">
            <div class="text-[12px] text-white/60">接入应用</div>
            <NumberTicker :value="d?.apps ?? 0" :decimal-places="0" class="mt-1 text-[28px] font-bold text-white dark:text-white" />
          </div>
          <div class="rounded-2xl bg-ink-2 px-5 py-5">
            <div class="text-[12px] text-white/60">系统健康</div>
            <div class="mt-1 text-[28px] font-bold" :class="d?.healthy === false ? 'text-danger-2' : 'text-accent'">{{ d ? (d.healthy ? '运行正常' : '异常') : '—' }}</div>
            <div class="text-[11px] text-white/45">{{ d?.healthy === false ? d.healthText : '数据库与认证服务连接正常' }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 指标卡 -->
    <div class="pds-card cursor-pointer" @click="session.has('pds:audit:view') && router.push('/audit?view=all&eventType=ACCESS_DENIED')">
      <span class="flex size-10 items-center justify-center rounded-xl bg-danger-soft text-danger"><IconShield /></span>
      <div class="mt-5 text-[14px] text-[#374151]">今日越权拦截</div>
      <div class="mt-1 flex items-center gap-2">
        <span class="text-[28px] font-bold text-ink">{{ fmt(d?.todayDenied ?? 0) }}</span>
        <span v-if="d?.deniedChangePercent != null" class="pds-badge" :class="d.deniedChangePercent > 0 ? 'bad' : 'ok'">
          {{ d.deniedChangePercent > 0 ? '+' : '' }}{{ d.deniedChangePercent }}%
        </span>
        <span v-else class="pds-muted">较昨日</span>
      </div>
    </div>
    <div class="pds-card">
      <span class="flex size-10 items-center justify-center rounded-xl bg-accent-soft text-accent-text"><IconEdit /></span>
      <div class="mt-5 text-[14px] text-[#374151]">待审批权限申请</div>
      <div class="mt-1 flex items-center gap-2">
        <span class="text-[28px] font-bold text-[#C0C4CC]">—</span>
        <span class="pds-badge gray">二期上线</span>
      </div>
    </div>
    <div class="pds-card">
      <span class="flex size-10 items-center justify-center rounded-xl bg-warn-soft-2 text-warn"><IconHourglass /></span>
      <div class="mt-5 text-[14px] text-[#374151]">即将到期授权</div>
      <div class="mt-1 flex items-center gap-2">
        <span class="text-[28px] font-bold text-ink">{{ fmt(d?.expiringGrants ?? 0) }}</span>
        <span class="pds-muted">30 天内</span>
      </div>
    </div>

    <!-- 景区分布（跨两行） -->
    <div class="pds-card xl:row-span-2">
      <div class="pds-card-title">景区分布</div>
      <div class="relative mx-auto my-4 h-[210px] max-w-[320px]">
        <svg viewBox="0 0 100 80" class="h-full w-full" aria-hidden="true">
          <polygon points="6,30 40,14 64,12 90,26 82,44 86,68 60,76 38,70 18,72 6,52" fill="#E3EBDD" stroke="#CFDCC4" stroke-width=".6" />
        </svg>
        <span v-for="(s, i) in (d?.scenics ?? []).slice(0, DOTS.length)" :key="s.id"
              class="absolute size-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-solid border-ink bg-accent"
              :style="{ left: DOTS[i][0] + '%', top: DOTS[i][1] + '%' }" :title="`${s.name}：${s.users} 人`" />
      </div>
      <div class="flex flex-col gap-2">
        <div v-for="s in d?.scenics" :key="s.id" class="relative overflow-hidden rounded-xl bg-row px-4 py-2.5">
          <div class="absolute inset-y-0 left-0 bg-accent-soft" :style="{ width: (s.users / scenicMax) * 100 + '%' }" />
          <div class="relative flex items-center justify-between text-[14px]">
            <span class="text-ink">{{ s.name }}</span><b class="text-ink">{{ fmt(s.users) }} 人</b>
          </div>
        </div>
        <div v-if="d && !d.scenics.length" class="py-6 text-center text-sm text-muted">尚未建立景区，可在"组织架构与人员"中新增</div>
      </div>
    </div>

    <!-- 登录趋势 -->
    <div class="pds-card xl:col-span-3">
      <div class="pds-card-title">
        登录趋势
        <Dropdown trigger="click" @on-click="(v: any) => (week = v)">
          <button class="pds-pill" style="height: 28px; padding: 0 12px; font-size: 12px; background: #F3F4F6">{{ week === 'this' ? '本周' : '上周' }}</button>
          <template #list><DropdownMenu><DropdownItem name="this">本周</DropdownItem><DropdownItem name="last">上周</DropdownItem></DropdownMenu></template>
        </Dropdown>
      </div>
      <div class="flex h-[220px] items-end justify-around px-2 pt-8">
        <div v-for="(v, i) in bars" :key="i" class="flex h-full w-[48px] cursor-default flex-col items-center justify-end"
             @mouseenter="hoverBar = i" @mouseleave="hoverBar = null">
          <span v-if="highlight === i" class="mb-2 rounded-md bg-ink px-2 py-0.5 text-[12px] text-white">{{ fmt(v) }}</span>
          <div class="w-9 rounded-full transition-all" :class="highlight === i ? 'bg-accent' : 'bg-line'"
               :style="{ height: Math.max(18, (v / barMax) * 150) + 'px' }" />
        </div>
      </div>
      <div class="mt-3 flex justify-around px-2">
        <span v-for="(n, i) in DAYS" :key="n" class="w-[48px] text-center text-[12px]" :class="highlight === i ? 'font-bold text-ink' : 'text-muted'">{{ n }}</span>
      </div>
    </div>

    <!-- 安全态势 -->
    <div class="pds-card xl:col-span-2">
      <div class="pds-card-title">安全态势</div>
      <div class="flex items-center gap-6">
        <div class="relative size-[120px] shrink-0">
          <svg viewBox="0 0 120 120" class="size-full -rotate-90">
            <circle cx="60" cy="60" :r="ring.r" fill="none" stroke="#E3EBDD" stroke-width="14" />
            <circle cx="60" cy="60" :r="ring.r" fill="none" stroke="#A3D65C" stroke-width="14" stroke-linecap="round"
                    :stroke-dasharray="`${ring.dash} ${ring.c}`" />
          </svg>
          <div class="absolute inset-0 flex flex-col items-center justify-center">
            <b class="text-[22px] text-ink">{{ activationPct }}%</b>
            <span class="text-[10px] text-muted">账号激活率</span>
          </div>
        </div>
        <div class="flex flex-1 flex-col gap-3">
          <div class="flex items-center justify-between rounded-xl bg-row px-4 py-3 text-[14px]">
            <span>锁定账号</span><b class="text-danger-2">{{ fmt(sec?.locked ?? 0) }}</b>
          </div>
          <div class="flex items-center justify-between rounded-xl bg-row px-4 py-3 text-[14px]">
            <span>长期未登录（90 天）</span><b class="text-warn">{{ fmt(sec?.longInactive ?? 0) }}</b>
          </div>
          <div class="flex items-center justify-between rounded-xl bg-row px-4 py-3 text-[14px]">
            <span>待修改初始密码</span><b class="text-ink">{{ fmt(sec?.mustChangePassword ?? 0) }}</b>
          </div>
        </div>
      </div>
      <p class="pds-muted mb-0 mt-3">账号激活率 = 已登录过的在职人员 / 在职人员；MFA 覆盖率将在二期接入二次认证后提供。</p>
    </div>

    <!-- AI 使用概况 -->
    <div class="pds-card xl:col-span-2">
      <div class="pds-card-title">
        AI 使用概况 <span class="pds-muted font-normal">近 7 日</span>
        <span class="flex gap-3 text-[12px] font-normal text-[#374151]">
          <span v-for="s in AI_SERIES" :key="s.key" class="flex items-center gap-1"><i class="inline-block size-2.5 rounded-full" :style="{ background: s.color }" />{{ s.label }}</span>
        </span>
      </div>
      <div v-if="aiEmpty" class="flex h-[170px] items-center justify-center text-center text-sm text-muted">
        近 7 日暂无 AI 使用记录<br />（由 PDA、PDK 上报审计事件后自动统计）
      </div>
      <div v-else class="flex h-[170px] items-end justify-around">
        <div v-for="(a, i) in d?.aiUsage" :key="a.scenicId" class="relative flex h-full flex-col items-center justify-end"
             @mouseenter="hoverAi = i" @mouseleave="hoverAi = null">
          <div v-if="hoverAi === i" class="absolute -top-2 z-10 whitespace-nowrap rounded-lg bg-ink px-2 py-1 text-[11px] text-white">
            问答 {{ a.chats }} · 检索 {{ a.retrievals }} · 敏感 {{ a.sensitive }}
          </div>
          <div class="flex items-end gap-1.5">
            <div v-for="s in AI_SERIES" :key="s.key" class="w-5 rounded-t-md" :style="{ background: s.color, height: Math.max(4, ((a as any)[s.key] / aiMax) * 130) + 'px' }" />
          </div>
          <span class="mt-2 text-[12px] text-muted">{{ a.name.replace('景区', '') }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
