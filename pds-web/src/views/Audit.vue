<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconCalendar, IconClose, IconUser, IconComment, IconWrench, IconFile, IconEyeClosed, IconTick, IconTickCircle, IconAlertCircle, IconShield } from '@kousum/semi-icons-vue'
import { api, EVENT_TYPES, type AppView, type AuditEvent, type OrgNode } from '@/api'
import { usePage } from '@/stores/page'
import { formatTime } from '@/lib/utils'
import PillSelect from '@/components/pds/PillSelect.vue'
import UserSelect from '@/components/pds/UserSelect.vue'

const route = useRoute()
const router = useRouter()
const page = usePage()

/** AI 问答审计：PDA 上报的问答、工具调用与越权拦截；全部日志：所有审计事件。 */
const view = ref<'ai' | 'all'>(route.query.view === 'all' ? 'all' : 'ai')
const AI_TYPES = 'AI_CHAT,AI_TOOL_CALL,ACCESS_DENIED'

const startOfDay = (d = new Date()) => new Date(d.getFullYear(), d.getMonth(), d.getDate())
const RANGES = { today: '今天', d7: '近 7 天', d30: '近 30 天' } as const
const f = reactive({
  range: 'today' as keyof typeof RANGES | 'custom', custom: [] as any[],
  scenicId: '', app: view.value === 'ai' ? 'pda' : '', userId: '', eventType: (route.query.eventType as string) || '', result: '',
})
const pageNo = ref(1)
const PAGE_SIZE = 12
const rows = ref<AuditEvent[]>([])
const total = ref(0)
const loading = ref(false)
const apps = ref<AppView[]>([])
const scenics = ref<OrgNode[]>([])
const userPicker = ref(false)

onMounted(async () => {
  const [a, tree] = await Promise.all([api.apps(), api.orgTree()])
  apps.value = a
  const walk = (ns: OrgNode[]) => ns.forEach((n) => { if (n.type === 'SCENIC') scenics.value.push(n); walk(n.children) })
  walk(tree)
  query()
})
watch(view, (v) => {
  router.replace({ query: v === 'all' ? { view: 'all' } : {} })
  f.app = v === 'ai' ? 'pda' : ''
  f.eventType = ''
  selected.value = null
  query()
}, { flush: 'sync' })
watch(view, (v) => page.set(`审计中心 · ${v === 'ai' ? 'AI 问答审计' : '全部日志'}`), { immediate: true })

const timeRange = computed<[Date, Date]>(() => {
  const now = new Date()
  if (f.range === 'custom' && f.custom?.[0]) return [new Date(f.custom[0]), new Date(new Date(f.custom[1]).getTime() + 86_399_999)]
  const days = f.range === 'd30' ? 29 : f.range === 'd7' ? 6 : 0
  return [new Date(startOfDay(now).getTime() - days * 86_400_000), new Date(startOfDay(now).getTime() + 86_399_999)]
})
const rangeLabel = computed(() => {
  if (f.range === 'today') return '今天 00:00 – 23:59'
  if (f.range === 'custom') return `${formatTime(timeRange.value[0].toISOString()).slice(0, 10)} – ${formatTime(timeRange.value[1].toISOString()).slice(0, 10)}`
  return RANGES[f.range]
})

async function query(resetPage = true) {
  if (resetPage) pageNo.value = 1
  loading.value = true
  try {
    const [from, to] = timeRange.value
    const r = await api.audit({
      eventType: f.eventType || (view.value === 'ai' ? AI_TYPES : undefined), app: f.app || undefined, result: f.result || undefined,
      userId: f.userId || undefined, scenicId: f.scenicId || undefined, from: from.toISOString(), to: to.toISOString(),
      page: pageNo.value, pageSize: PAGE_SIZE,
    })
    rows.value = r.items
    total.value = r.total
  } finally {
    loading.value = false
  }
}
const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
function go(p: number) { pageNo.value = p; query(false) }

// ---------------- 行数据解析（PDA 上报的 detail 约定见《应用接入指南》）
const detailOf = (e: AuditEvent): Record<string, any> => { try { return e.detail ? JSON.parse(e.detail) : {} } catch { return {} } }
const scenicName = (id?: string) => (id ? scenics.value.find((s) => s.id === id)?.name.replace('景区', '') || id : '—')
const question = (e: AuditEvent) => detailOf(e).question || e.action || '—'
const tool = (e: AuditEvent) => { const d = detailOf(e); return d.tool || d.tools?.[0]?.name || (e.eventType === 'AI_TOOL_CALL' ? e.action : '—') }
const hitDocs = (e: AuditEvent) => { const d = detailOf(e); return Array.isArray(d.hitDocs) ? d.hitDocs.length : d.hitDocCount ?? 0 }
const TYPE_BADGE: Record<string, { label: string; cls: string }> = {
  AI_CHAT: { label: 'AI问答', cls: 'ok' }, AI_TOOL_CALL: { label: '工具调用', cls: 'gray' }, ACCESS_DENIED: { label: '越权拦截', cls: 'bad' },
}
const RESULT: Record<string, { label: string; cls: string }> = {
  ALLOW: { label: '允许', cls: 'ok' }, SUCCESS: { label: '成功', cls: 'ok' }, DENY: { label: '拦截', cls: 'bad-solid' }, FAILURE: { label: '失败', cls: 'warn' },
}
const fmtTs = (ts: string) => {
  const d = new Date(ts)
  return d >= startOfDay() ? formatTime(ts).slice(11) + ':' + String(d.getSeconds()).padStart(2, '0') : formatTime(ts).slice(5)
}

// ---------------- 详情面板
const selected = ref<AuditEvent | null>(null)
const verify = ref<{ intact: boolean; message: string } | null>(null)
async function open(e: AuditEvent) {
  selected.value = e
  verify.value = null
  verify.value = await api.auditVerifyOne(e.id)
}
const sd = computed(() => (selected.value ? detailOf(selected.value) : {}))
const timeline = computed(() => {
  const e = selected.value
  if (!e) return []
  const d = sd.value
  const items: { icon: any; tone: string; title: string; sub?: string; badge?: { label: string; cls: string } }[] = []
  items.push({ icon: IconUser, tone: 'dark', title: '登录用户', sub: `${e.userName || e.userId || '未知'}${d.empNo ? `（${d.empNo}）` : ''}${e.via ? ` · 经 ${e.via}` : ''}${d.mfa ? ' · MFA 通过' : ''}` })
  if (view.value === 'ai' || d.question) items.push({ icon: IconComment, tone: 'dark', title: '提出问题', sub: question(e) })
  const tools: { name: string; result?: string }[] = d.tools ?? (tool(e) !== '—' ? [{ name: tool(e), result: e.result }] : [])
  tools.forEach((t) => items.push({ icon: IconWrench, tone: t.result === 'DENY' ? 'red' : 'green', title: `工具 ${t.name}`,
    badge: t.result === 'DENY' ? { label: '拦截', cls: 'bad' } : { label: '允许', cls: 'ok' } }))
  if (Array.isArray(d.hitDocs) && d.hitDocs.length) items.push({ icon: IconFile, tone: 'green', title: `命中文档 ${d.hitDocs.length} 份`, sub: d.hitDocs.join(' · ') })
  if (d.masked) items.push({ icon: IconEyeClosed, tone: 'yellow', title: `输出脱敏 ${d.masked} 处`, sub: d.maskedSample })
  if (e.result === 'DENY') items.push({ icon: IconShield, tone: 'red', title: '已拦截', sub: d.reason || '无此权限' })
  else items.push({ icon: IconTick, tone: 'green', title: e.eventType === 'AI_CHAT' ? '回答完成' : '完成', sub: d.latencyMs ? `耗时 ${(d.latencyMs / 1000).toFixed(1)} 秒` : undefined })
  return items
})
const TONE: Record<string, string> = { dark: 'bg-ink text-white', green: 'bg-accent text-ink', red: 'bg-danger-2 text-white', yellow: 'bg-[#FACC15] text-ink' }

// 全链校验（全部日志视图）
const chain = ref<{ intact: boolean; message: string } | null>(null)
const chainBusy = ref(false)
async function verifyChain() {
  chainBusy.value = true
  try { chain.value = await api.auditVerify() } finally { chainBusy.value = false }
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- 筛选条 -->
    <div class="pds-card flex flex-wrap items-center gap-3 py-4">
      <Poptip placement="bottom-start" transfer width="300">
        <button class="pds-pill"><IconCalendar size="small" /> {{ rangeLabel }}</button>
        <template #content>
          <div class="flex flex-col gap-3 py-1">
            <RadioGroup v-model="f.range" type="button" size="small">
              <Radio v-for="(l, k) in RANGES" :key="k" :label="k">{{ l }}</Radio><Radio label="custom">自定义</Radio>
            </RadioGroup>
            <DatePicker v-if="f.range === 'custom'" v-model="f.custom" type="daterange" placeholder="选择日期范围" transfer />
          </div>
        </template>
      </Poptip>
      <PillSelect v-model="f.scenicId" label="景区" :options="scenics.map((s) => ({ value: s.id, label: s.name }))" />
      <PillSelect v-model="f.app" label="应用" :options="apps.map((a) => ({ value: a.code, label: a.code.toUpperCase() }))" />
      <Poptip v-model="userPicker" placement="bottom-start" transfer width="280">
        <button class="pds-pill">人员：{{ f.userId ? '已选 1 人' : '全部' }}</button>
        <template #content>
          <UserSelect v-model="f.userId" placeholder="搜索人员" />
          <button v-if="f.userId" class="pds-link mt-2" @click="f.userId = ''">清除</button>
        </template>
      </Poptip>
      <PillSelect v-model="f.eventType" label="事件类型"
                  :options="view === 'ai' ? Object.entries(TYPE_BADGE).map(([k, v]) => ({ value: k, label: v.label })) : Object.entries(EVENT_TYPES).map(([k, v]) => ({ value: k, label: v }))" />
      <PillSelect v-model="f.result" label="结果" :options="Object.entries(RESULT).map(([k, v]) => ({ value: k, label: v.label }))" />
      <span class="flex-1" />
      <button class="pds-btn dark" @click="query()">查询</button>
    </div>

    <div class="grid grid-cols-1 gap-5" :class="selected ? 'xl:grid-cols-[1fr_420px]' : ''">
      <div class="pds-card min-w-0">
        <div class="mb-4 flex items-center gap-3">
          <RadioGroup v-model="view" type="button" size="small"><Radio label="ai">AI 问答审计</Radio><Radio label="all">全部日志</Radio></RadioGroup>
          <span class="pds-muted">共 {{ total }} 条</span>
          <span class="flex-1" />
          <template v-if="view === 'all'">
            <span v-if="chain" class="pds-badge" :class="chain.intact ? 'ok' : 'bad'">{{ chain.message }}</span>
            <button class="pds-btn ghost sm" :disabled="chainBusy" @click="verifyChain">{{ chainBusy ? '校验中…' : '全链完整性校验' }}</button>
          </template>
        </div>

        <table class="pds-table">
          <thead v-if="view === 'ai'"><tr><th>时间</th><th>人员</th><th>景区</th><th>事件类型</th><th>问题摘要</th><th>调用工具</th><th>命中文档</th><th>结果</th><th></th></tr></thead>
          <thead v-else><tr><th>时间</th><th>应用</th><th>类型</th><th>人员</th><th>内容</th><th>结果</th><th>IP</th><th></th></tr></thead>
          <tbody>
            <tr v-for="e in rows" :key="e.id" class="clickable" :class="{ active: selected?.id === e.id }" @click="open(e)">
              <td class="whitespace-nowrap text-[#6B7280]">{{ fmtTs(e.ts) }}</td>
              <template v-if="view === 'ai'">
                <td class="whitespace-nowrap text-ink">{{ e.userName || e.userId || '—' }}</td>
                <td class="whitespace-nowrap">{{ scenicName(e.scenicId) }}</td>
                <td><span class="pds-badge" :class="TYPE_BADGE[e.eventType]?.cls || 'gray'">{{ TYPE_BADGE[e.eventType]?.label || EVENT_TYPES[e.eventType] || e.eventType }}</span></td>
                <td class="max-w-[260px] truncate text-ink">{{ question(e) }}</td>
                <td class="pds-mono">{{ tool(e) }}</td>
                <td>{{ hitDocs(e) }}</td>
              </template>
              <template v-else>
                <td class="uppercase">{{ e.app }}</td>
                <td class="whitespace-nowrap">{{ EVENT_TYPES[e.eventType] || e.eventType }}</td>
                <td class="whitespace-nowrap text-ink">{{ e.userId?.startsWith('client:') ? `服务·${e.userId.slice(7)}` : e.userName || e.userId || '—' }}</td>
                <td class="max-w-[280px] truncate">{{ e.action }}</td>
              </template>
              <td><span class="pds-badge" :class="RESULT[e.result]?.cls || 'gray'">{{ RESULT[e.result]?.label || e.result }}</span></td>
              <td v-if="view === 'all'" class="text-[#6B7280]">{{ e.ip || '—' }}</td>
              <td><button class="pds-link" @click.stop="open(e)">详情</button></td>
            </tr>
            <tr v-if="!loading && !rows.length"><td :colspan="9" class="empty">{{ view === 'ai' ? '该时间范围内暂无 AI 问答审计记录（由 PDA 上报）' : '该时间范围内暂无审计记录' }}</td></tr>
          </tbody>
        </table>
        <div class="pds-pager">
          <span class="pds-muted">第 {{ pageNo }} / {{ pages }} 页</span>
          <div class="pages">
            <button :disabled="pageNo <= 1" @click="go(pageNo - 1)">‹</button>
            <button v-for="p in Math.min(pages, 5)" :key="p" :class="{ active: p === pageNo }" @click="go(p)">{{ p }}</button>
            <button :disabled="pageNo >= pages" @click="go(pageNo + 1)">›</button>
          </div>
        </div>
      </div>

      <!-- 详情面板（左侧黄绿竖条） -->
      <div v-if="selected" class="pds-card relative self-start overflow-hidden border-0 border-l-4 border-solid border-accent">
        <div class="flex items-start justify-between">
          <div>
            <div class="text-[17px] font-semibold text-ink">{{ view === 'ai' ? '问答详情' : '事件详情' }}</div>
            <div class="pds-muted mt-1">{{ selected.userName || selected.userId || '—' }} · {{ scenicName(selected.scenicId) }} · {{ formatTime(selected.ts) }}</div>
          </div>
          <button class="pds-link gray" @click="selected = null" aria-label="关闭"><IconClose /></button>
        </div>

        <div class="relative mt-6 flex flex-col gap-5">
          <div class="absolute bottom-3 left-[13px] top-3 w-px bg-[#E5E7EB]" />
          <div v-for="(t, i) in timeline" :key="i" class="relative flex gap-3">
            <span class="z-10 flex size-7 shrink-0 items-center justify-center rounded-full" :class="TONE[t.tone]"><component :is="t.icon" size="small" /></span>
            <div class="min-w-0">
              <div class="flex items-center gap-2 text-[14px] font-semibold text-ink">{{ t.title }}<span v-if="t.badge" class="pds-badge" :class="t.badge.cls">{{ t.badge.label }}</span></div>
              <div v-if="t.sub" class="pds-muted mt-0.5 break-all">{{ t.sub }}</div>
            </div>
          </div>
        </div>

        <div v-if="view === 'all'" class="mt-5 rounded-xl bg-row p-3 text-[12px]">
          <div class="pds-muted mb-1">事件 #{{ selected.seq }} · {{ selected.eventType }} · {{ selected.app }}</div>
          <div v-if="selected.targetType">对象：{{ selected.targetType }} {{ selected.targetIds }}</div>
          <pre v-if="selected.detail" class="pds-mono m-0 mt-2 max-h-[180px] overflow-auto whitespace-pre-wrap">{{ JSON.stringify(sd, null, 2) }}</pre>
        </div>

        <div class="mt-6 flex items-center gap-3 rounded-2xl border border-solid px-4 py-3"
             :class="!verify ? 'border-[#E5E7EB] bg-row' : verify.intact ? 'border-accent bg-accent-soft' : 'border-[#FCA5A5] bg-danger-soft'">
          <span class="flex size-7 items-center justify-center rounded-full" :class="!verify ? 'bg-[#E5E7EB]' : verify.intact ? 'bg-accent-text text-white' : 'bg-danger text-white'">
            <IconTickCircle v-if="!verify || verify.intact" size="small" /><IconAlertCircle v-else size="small" />
          </span>
          <div>
            <div class="text-[15px] font-semibold" :class="!verify ? 'text-muted' : verify.intact ? 'text-accent-text' : 'text-danger'">
              完整性校验：{{ !verify ? '校验中…' : verify.intact ? '通过' : '未通过' }}
            </div>
            <div class="pds-muted">{{ verify?.message || '正在比对哈希链' }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
