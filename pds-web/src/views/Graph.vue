<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from 'view-ui-plus'
import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, type SimulationNodeDatum } from 'd3-force'
import { IconUser, IconTick, IconAlertTriangle, IconMinus, IconPlus, IconExpand, IconClose, IconTriangleDown } from '@kousum/semi-icons-vue'
import {
  api, flattenOrgs, type AppView, type Graph, type GraphEdge, type GraphExplain, type GraphNode, type OrgNode, type PermNode,
  type Position, type RoleView,
} from '@/api'
import { useSession } from '@/stores/session'
import { downloadCsv } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import UserSelect from '@/components/pds/UserSelect.vue'
import PillSelect from '@/components/pds/PillSelect.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()

type View = 'person' | 'app' | 'perm' | 'org' | 'overview'
const view = ref<View>((route.query.view as View) || 'person')
const sel = reactive({
  userId: (route.query.userId as string) || '',
  app: (route.query.app as string) || '',
  code: (route.query.code as string) || '',
  orgId: (route.query.orgId as string) || '',
  filterApp: (route.query.fapp as string) || '',
  risk: (route.query.risk as string) || '',
  source: (route.query.source as string) || '',
  scenic: (route.query.scenic as string) || '',
})
const apps = ref<AppView[]>([])
const perms = ref<PermNode[]>([])
const orgs = ref<(OrgNode & { depth: number })[]>([])
const userLabel = ref('')

// ---------------- 数据
const graph = ref<Graph | null>(null)
const loading = ref(false)
interface VNode extends SimulationNodeDatum { id: string; d: GraphNode; w: number; h: number; shape: 'circle' | 'pill' | 'square' }
const vnodes = ref<VNode[]>([])
const vedges = ref<GraphEdge[]>([])
const selectedId = ref('')
const highlight = ref<Set<string>>(new Set())
const explain = ref<GraphExplain | null>(null)

const W = 1100
const H = 660
const ctx = document.createElement('canvas').getContext('2d')!
function textW(t: string, size = 12) { ctx.font = `${size}px "PingFang SC","Microsoft YaHei",sans-serif`; return ctx.measureText(t).width }

function layout(g: Graph) {
  const ns: VNode[] = g.nodes.map((d) => {
    if (d.type === 'USER') return { id: d.id, d, w: d.center ? 96 : 44, h: d.center ? 96 : 44, shape: 'circle' as const }
    if (d.type === 'APP') return { id: d.id, d, w: 64, h: 64, shape: 'square' as const }
    const label = d.type === 'PERM' ? d.label : d.label
    const w = textW(label, d.type === 'PERM' ? 12 : 12) + (d.type === 'PERM' ? 28 : 40)
    return { id: d.id, d, w, h: 32, shape: 'pill' as const }
  })
  const center = ns.find((n) => n.d.center)
  if (center) { center.fx = W / 2; center.fy = H / 2 }
  const links = g.edges.map((e) => ({ source: e.source, target: e.target }))
  const sim = forceSimulation(ns)
    .force('link', forceLink<VNode, any>(links).id((n) => n.id).distance((l: any) => (l.target.d?.type === 'APP' || l.source.d?.type === 'APP' ? 110 : 140)).strength(0.6))
    .force('charge', forceManyBody().strength(-620))
    .force('center', forceCenter(W / 2, H / 2))
    .force('collide', forceCollide<VNode>().radius((n) => Math.max(n.w, n.h) / 2 + 14))
    .stop()
  for (let i = 0; i < 320; i++) sim.tick()
  vnodes.value = ns
  vedges.value = g.edges
}

async function load() {
  loading.value = true
  selectedId.value = ''
  explain.value = null
  highlight.value = new Set()
  try {
    let g: Graph | null = null
    if (view.value === 'person') { if (!sel.userId) { graph.value = null; vnodes.value = []; return } g = await api.graphUser(sel.userId, { app: sel.filterApp || undefined, risk: sel.risk || undefined, source: sel.source || undefined }) }
    else if (view.value === 'app') { if (!sel.app) return; g = await api.graphApp(sel.app, sel.scenic || undefined) }
    else if (view.value === 'perm') { if (!sel.code) { graph.value = null; vnodes.value = []; return } g = await api.graphPerm(sel.code, sel.scenic || undefined) }
    else if (view.value === 'org') { if (!sel.orgId) return; g = await api.graphOrg(sel.orgId) }
    else g = await api.graphOverview(sel.scenic || undefined)
    graph.value = g
    layout(g)
    fit()
    if (view.value === 'perm') select(vnodes.value.find((n) => n.d.center)?.id || '')
  } finally {
    loading.value = false
  }
}
function syncUrl() {
  router.replace({ query: {
    view: view.value,
    ...(view.value === 'person' && sel.userId ? { userId: sel.userId } : {}),
    ...(view.value === 'app' && sel.app ? { app: sel.app } : {}),
    ...(view.value === 'perm' && sel.code ? { code: sel.code } : {}),
    ...(view.value === 'org' && sel.orgId ? { orgId: sel.orgId } : {}),
    ...(sel.filterApp ? { fapp: sel.filterApp } : {}), ...(sel.risk ? { risk: sel.risk } : {}),
    ...(sel.source ? { source: sel.source } : {}), ...(sel.scenic ? { scenic: sel.scenic } : {}),
  } })
}
watch([view, () => sel.userId, () => sel.app, () => sel.code, () => sel.orgId, () => sel.filterApp, () => sel.risk, () => sel.source, () => sel.scenic], () => { syncUrl(); load() })
watch(() => sel.userId, async (id) => { if (id) { const u = await api.user(id); userLabel.value = `${u.name}${u.empNo ? `（${u.empNo}）` : ''}` } }, { immediate: true })

onMounted(async () => {
  const [a, p, t] = await Promise.all([api.apps(), api.grantableNodes(), api.orgTree()])
  apps.value = a
  perms.value = p
  orgs.value = flattenOrgs(t)
  if (!sel.app) sel.app = a.find((x) => x.code !== 'pds')?.code || ''
  if (!sel.orgId) sel.orgId = orgs.value[0]?.id || ''
  if (!sel.userId && session.me) sel.userId = (session.me as any).id
  else load()
})

// ---------------- 视口：缩放与平移
const tf = reactive({ x: 0, y: 0, k: 1 })
const svgEl = ref<SVGSVGElement | null>(null)
function fit() {
  if (!vnodes.value.length) return
  const xs = vnodes.value.map((n) => n.x || 0), ys = vnodes.value.map((n) => n.y || 0)
  const minX = Math.min(...xs) - 80, maxX = Math.max(...xs) + 80, minY = Math.min(...ys) - 60, maxY = Math.max(...ys) + 60
  const box = svgEl.value?.getBoundingClientRect()
  const vw = box?.width || W, vh = box?.height || H
  const k = Math.min(1.2, Math.min((vw - 340) / (maxX - minX), vh / (maxY - minY)))
  tf.k = Math.max(0.3, k)
  tf.x = (vw - 340) / 2 - ((minX + maxX) / 2) * tf.k
  tf.y = vh / 2 - ((minY + maxY) / 2) * tf.k
}
function zoom(f: number, cx?: number, cy?: number) {
  const box = svgEl.value!.getBoundingClientRect()
  const px = cx ?? box.width / 2, py = cy ?? box.height / 2
  const nk = Math.min(3, Math.max(0.25, tf.k * f))
  tf.x = px - ((px - tf.x) / tf.k) * nk
  tf.y = py - ((py - tf.y) / tf.k) * nk
  tf.k = nk
}
function onWheel(e: WheelEvent) {
  const box = svgEl.value!.getBoundingClientRect()
  zoom(e.deltaY < 0 ? 1.1 : 1 / 1.1, e.clientX - box.left, e.clientY - box.top)
}
let drag: { mode: 'pan' | 'node'; id?: string; sx: number; sy: number; ox: number; oy: number; moved: boolean } | null = null
function onDown(e: PointerEvent, id?: string) {
  const n = id ? vnodes.value.find((x) => x.id === id) : null
  drag = { mode: id ? 'node' : 'pan', id, sx: e.clientX, sy: e.clientY, ox: n ? n.x! : tf.x, oy: n ? n.y! : tf.y, moved: false }
  ;(e.currentTarget as Element).setPointerCapture?.(e.pointerId)
}
function onMoveEvt(e: PointerEvent) {
  if (!drag) return
  const dx = e.clientX - drag.sx, dy = e.clientY - drag.sy
  if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true
  if (drag.mode === 'pan') { tf.x = drag.ox + dx; tf.y = drag.oy + dy }
  else {
    const n = vnodes.value.find((x) => x.id === drag!.id)!
    n.x = drag.ox + dx / tf.k
    n.y = drag.oy + dy / tf.k
    vnodes.value = [...vnodes.value]
  }
}
function onUp() {
  if (drag && !drag.moved) {
    if (drag.mode === 'node' && drag.id) select(drag.id)
    else if (drag.mode === 'pan') { selectedId.value = ''; explain.value = null; highlight.value = new Set() }
  }
  drag = null
}

// ---------------- 选择、路径高亮、解释
const byId = computed(() => new Map(vnodes.value.map((n) => [n.id, n])))
const selected = computed(() => byId.value.get(selectedId.value) || null)
const centerNode = computed(() => vnodes.value.find((n) => n.d.center) || null)

/** 中心节点到目标节点的全部最短路径上的边（忽略"属于应用"关系，无向）。 */
function pathEdges(from: string, to: string) {
  const adj = new Map<string, { n: string; e: string }[]>()
  vedges.value.filter((e) => e.kind !== 'IN_APP').forEach((e) => {
    adj.set(e.source, [...(adj.get(e.source) || []), { n: e.target, e: e.id }])
    adj.set(e.target, [...(adj.get(e.target) || []), { n: e.source, e: e.id }])
  })
  const dist = new Map<string, number>([[from, 0]])
  const queue = [from]
  while (queue.length) {
    const c = queue.shift()!
    for (const { n } of adj.get(c) || []) if (!dist.has(n)) { dist.set(n, dist.get(c)! + 1); queue.push(n) }
  }
  const out = new Set<string>()
  if (!dist.has(to)) return out
  const back = (c: string) => {
    for (const { n, e } of adj.get(c) || []) if (dist.get(n) === dist.get(c)! - 1) { out.add(e); back(n) }
  }
  back(to)
  return out
}
async function select(id: string) {
  selectedId.value = id
  explain.value = null
  const n = byId.value.get(id)
  if (!n) return
  if (centerNode.value && n.id !== centerNode.value.id) highlight.value = pathEdges(centerNode.value.id, n.id)
  else highlight.value = new Set()
  if (view.value === 'person' && n.d.type === 'PERM' && sel.userId) {
    explain.value = await api.graphExplain(sel.userId, n.d.label)
  }
}
function explainSelected() {
  if (!selected.value || selected.value.d.type !== 'PERM') { Message.info('先在图中点选一个权限节点'); return }
  select(selected.value.id)
}
function focusOn(n: GraphNode) {
  const raw = n.id.slice(n.id.indexOf(':') + 1)
  if (n.type === 'USER') { view.value = 'person'; sel.userId = raw }
  else if (n.type === 'ORG') { view.value = 'org'; sel.orgId = raw }
  else if (n.type === 'APP') { view.value = 'app'; sel.app = raw }
  else if (n.type === 'PERM') { view.value = 'perm'; sel.code = raw }
}

// ---------------- 绘制辅助
const STYLE: Record<string, { bg: string; fg: string; dot: string; label: string }> = {
  USER: { bg: '#1F2421', fg: '#fff', dot: '#1F2421', label: '人员' },
  ORG: { bg: '#E0F2FE', fg: '#0369A1', dot: '#0EA5E9', label: '组织' },
  POSITION: { bg: '#EFF6FF', fg: '#2563EB', dot: '#3B82F6', label: '岗位' },
  GROUP: { bg: '#F3E8FF', fg: '#9333EA', dot: '#A855F7', label: '用户组' },
  ROLE: { bg: '#EEF7DC', fg: '#4A7A22', dot: '#A3D65C', label: '角色' },
  APP: { bg: '#FFF7ED', fg: '#EA580C', dot: '#F97316', label: '应用' },
  RESOURCE: { bg: '#CCFBF1', fg: '#0F766E', dot: '#14B8A6', label: '资源' },
  PERM: { bg: '#fff', fg: '#1F2421', dot: '#EF4444', label: '权限' },
}
const ICON: Record<string, string> = { ORG: '▦', POSITION: '▣', GROUP: '◉', ROLE: '◆', RESOURCE: '▤' }
function permStroke(n: VNode) {
  if (n.d.state === 'DENY') return '#EF4444'
  if (n.d.risk === 'HIGH') return '#F87171'
  if (n.d.state === 'ALLOW' || n.d.center) return '#A3D65C'
  return '#D1D5DB'
}
/** 边端点截到节点边界附近。 */
function ends(e: GraphEdge) {
  const a = byId.value.get(e.source), b = byId.value.get(e.target)
  if (!a || !b) return null
  const dx = b.x! - a.x!, dy = b.y! - a.y!, len = Math.hypot(dx, dy) || 1
  const ra = radius(a, dx, dy), rb = radius(b, -dx, -dy)
  return { x1: a.x! + (dx / len) * ra, y1: a.y! + (dy / len) * ra, x2: b.x! - (dx / len) * (rb + 4), y2: b.y! - (dy / len) * (rb + 4),
    mx: (a.x! + b.x!) / 2, my: (a.y! + b.y!) / 2 }
}
function radius(n: VNode, dx: number, dy: number) {
  if (n.shape === 'circle') return n.w / 2 + (n.d.center ? 10 : 2)
  const hw = n.w / 2 + 2, hh = n.h / 2 + 2
  const tx = Math.abs(dx) < 1e-6 ? Infinity : hw / Math.abs(dx), ty = Math.abs(dy) < 1e-6 ? Infinity : hh / Math.abs(dy)
  return Math.min(tx, ty) * Math.hypot(dx, dy)
}
const hlNodes = computed(() => {
  const out = new Set<string>()
  vedges.value.forEach((e) => { if (highlight.value.has(e.id)) { out.add(e.source); out.add(e.target) } })
  return out
})
const edgeColor = (e: GraphEdge) => (highlight.value.has(e.id) ? '#A3D65C' : e.kind === 'DENY' ? '#EF4444' : e.kind === 'IN_APP' ? '#E1E5DE' : '#C4C9C2')

// 小地图
const mini = computed(() => {
  if (!vnodes.value.length) return null
  const xs = vnodes.value.map((n) => n.x!), ys = vnodes.value.map((n) => n.y!)
  const minX = Math.min(...xs) - 40, maxX = Math.max(...xs) + 40, minY = Math.min(...ys) - 40, maxY = Math.max(...ys) + 40
  const s = Math.min(150 / (maxX - minX), 100 / (maxY - minY))
  const box = svgEl.value?.getBoundingClientRect()
  const vw = box?.width || W, vh = box?.height || H
  return { s, minX, minY, view: { x: (-tf.x / tf.k - minX) * s, y: (-tf.y / tf.k - minY) * s, w: (vw / tf.k) * s, h: (vh / tf.k) * s } }
})

// ---------------- 权限模拟
const simu = reactive({ open: false, orgId: '', primary: false, positionId: '', roleIds: [] as string[], result: null as null | { added: string[]; highRisk: string[]; lost: string[] } })
const positions = ref<Position[]>([])
const roles = ref<RoleView[]>([])
async function openSimulate() {
  if (view.value !== 'person' || !sel.userId) { Message.info('权限模拟在"以人为中心"视图中使用，请先选择人员'); return }
  if (!positions.value.length) [positions.value, roles.value] = await Promise.all([api.positions(), api.roles()])
  Object.assign(simu, { open: true, orgId: '', primary: false, positionId: '', roleIds: [], result: null })
}
async function runSimulate() {
  if (!simu.orgId && !simu.positionId && !simu.roleIds.length) { Message.warning('请至少设置一项假设变化'); return false }
  simu.result = await api.graphSimulate({ userId: sel.userId, orgId: simu.orgId || undefined, primary: simu.primary,
    positionId: simu.positionId || undefined, roleIds: simu.roleIds })
  return false
}

// ---------------- 导出
function exportSvg() {
  if (!svgEl.value) return
  const clone = svgEl.value.cloneNode(true) as SVGSVGElement
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  const blob = new Blob([new XMLSerializer().serializeToString(clone)], { type: 'image/svg+xml' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `权限图谱-${new Date().toLocaleDateString('sv-SE')}.svg`
  a.click()
  URL.revokeObjectURL(a.href)
}
function exportCsv() {
  if (!graph.value) return
  const name = (id: string) => byId.value.get(id)?.d.label || id
  downloadCsv(`权限图谱关系-${new Date().toLocaleDateString('sv-SE')}.csv`, [
    ['起点类型', '起点', '关系', '终点类型', '终点'],
    ...graph.value.edges.map((e) => [STYLE[byId.value.get(e.source)?.d.type || '']?.label, name(e.source), e.label,
      STYLE[byId.value.get(e.target)?.d.type || '']?.label, name(e.target)]),
  ])
}
function onAction(n: string) {
  if (n === 'explain') explainSelected()
  else if (n === 'simulate') openSimulate()
  else if (n === 'svg') exportSvg()
  else exportCsv()
}
const scenics = computed(() => orgs.value.filter((o) => o.type === 'SCENIC'))
const SOURCES: Record<string, string> = { ROLE: '角色', ORG: '组织', POSITION: '岗位', GROUP: '用户组', USER: '个人直授' }
const RISKS: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const filtersOn = computed(() => (view.value === 'person' ? [sel.filterApp, sel.risk, sel.source] : [sel.scenic]).some(Boolean))
function resetFilters() { Object.assign(sel, { filterApp: '', risk: '', source: '', scenic: '' }) }

const VIEWS: { k: View; l: string }[] = [
  { k: 'person', l: '以人为中心' }, { k: 'app', l: '以应用为中心' }, { k: 'perm', l: '以权限为中心' }, { k: 'org', l: '以组织为中心' }, { k: 'overview', l: '全局总览' },
]
const permLabel = computed(() => { const p = perms.value.find((x) => x.code === sel.code); return p ? `${p.name}` : '选择权限' })
const orgLabel = computed(() => orgs.value.find((o) => o.id === sel.orgId)?.name || '选择组织')
const appLabel = computed(() => apps.value.find((a) => a.code === sel.app)?.name || '选择应用')
const pickerOpen = ref(false)
watch(() => sel.userId, () => nextTick(() => (pickerOpen.value = false)))
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- 筛选栏 + 操作菜单 -->
    <div class="pds-card flex flex-wrap items-center gap-2.5" style="padding: 14px 20px">
      <Dropdown trigger="click" transfer @on-click="(v: string) => (view = v as View)">
        <button class="pds-chip dark" style="height: 36px; padding: 0 14px; font-size: 13px">视图：{{ VIEWS.find((v) => v.k === view)?.l }} <IconTriangleDown size="extra-small" /></button>
        <template #list><DropdownMenu><DropdownItem v-for="v in VIEWS" :key="v.k" :name="v.k" :selected="v.k === view">{{ v.l }}</DropdownItem></DropdownMenu></template>
      </Dropdown>

      <Poptip v-if="view === 'person'" v-model="pickerOpen" placement="bottom-start" transfer width="320">
        <button class="pds-chip on" style="height: 36px; padding: 0 14px; font-size: 13px"><IconUser size="small" /> {{ userLabel || '选择人员' }} <IconTriangleDown size="extra-small" /></button>
        <template #content><UserSelect v-model="sel.userId" /></template>
      </Poptip>
      <Dropdown v-else-if="view === 'app'" trigger="click" transfer @on-click="(v: string) => (sel.app = v)">
        <button class="pds-chip on" style="height: 36px; padding: 0 14px; font-size: 13px">应用：{{ appLabel }} <IconTriangleDown size="extra-small" /></button>
        <template #list><DropdownMenu><DropdownItem v-for="a in apps" :key="a.code" :name="a.code" :selected="a.code === sel.app">{{ a.name }}</DropdownItem></DropdownMenu></template>
      </Dropdown>
      <Select v-else-if="view === 'perm'" v-model="sel.code" filterable transfer style="width: 280px" :placeholder="permLabel">
        <Option v-for="p in perms" :key="p.code" :value="p.code" :label="`${p.name}（${p.code}）`">{{ p.name }} <span class="pds-mono text-[#9CA3AF]">{{ p.code }}</span></Option>
      </Select>
      <Select v-else-if="view === 'org'" v-model="sel.orgId" filterable transfer style="width: 220px" :placeholder="orgLabel">
        <Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
      </Select>

      <span class="mx-1 h-5 w-px bg-[#E5E7EB]" />
      <template v-if="view === 'person'">
        <PillSelect v-model="sel.filterApp" label="应用" compact :options="apps.map((a) => ({ value: a.code, label: a.name }))" />
        <PillSelect v-model="sel.risk" label="风险等级" compact :options="Object.entries(RISKS).map(([value, label]) => ({ value, label }))" />
        <PillSelect v-model="sel.source" label="来源" compact :options="Object.entries(SOURCES).map(([value, label]) => ({ value, label }))" />
      </template>
      <PillSelect v-else-if="view !== 'org'" v-model="sel.scenic" label="景区" compact :options="scenics.map((o) => ({ value: o.id, label: o.name }))" />
      <span v-else class="pds-muted">以组织为中心时按所选组织展示</span>
      <button v-if="filtersOn" class="pds-link gray ml-1" @click="resetFilters">重置筛选</button>

      <span class="flex-1" />
      <Dropdown trigger="click" transfer placement="bottom-end" @on-click="onAction">
        <button class="pds-btn accent">操作 <IconTriangleDown size="extra-small" /></button>
        <template #list>
          <DropdownMenu>
            <DropdownItem name="explain" :disabled="view !== 'person'">路径解释（先点选权限节点）</DropdownItem>
            <DropdownItem name="simulate" :disabled="view !== 'person'">权限模拟</DropdownItem>
            <DropdownItem name="svg" divided>导出图片（SVG）</DropdownItem>
            <DropdownItem name="csv">导出关系清单（CSV）</DropdownItem>
          </DropdownMenu>
        </template>
      </Dropdown>
    </div>

    <!-- 画布 -->
    <div class="pds-card relative overflow-hidden" style="padding: 0; height: calc(100vh - 220px); min-height: 620px">
      <svg ref="svgEl" class="size-full cursor-grab touch-none select-none" style="background-image: radial-gradient(#D7DDD3 1px, transparent 1px); background-size: 18px 18px"
           @wheel.prevent="onWheel" @pointerdown="onDown($event)" @pointermove="onMoveEvt" @pointerup="onUp">
        <defs>
          <marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#B8BEB5" /></marker>
          <marker id="arr-red" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#EF4444" /></marker>
          <marker id="arr-hl" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#A3D65C" /></marker>
        </defs>
        <g :transform="`translate(${tf.x},${tf.y}) scale(${tf.k})`">
          <!-- 边 -->
          <g v-for="e in vedges" :key="e.id">
            <template v-if="ends(e)">
              <line :x1="ends(e)!.x1" :y1="ends(e)!.y1" :x2="ends(e)!.x2" :y2="ends(e)!.y2" :stroke="edgeColor(e)"
                    :stroke-width="highlight.has(e.id) ? 5 : 1.4" :stroke-dasharray="e.kind === 'DENY' ? '7 5' : undefined" stroke-linecap="round"
                    :marker-end="highlight.has(e.id) ? 'url(#arr-hl)' : e.kind === 'DENY' ? 'url(#arr-red)' : 'url(#arr)'"
                    :opacity="highlight.size && !highlight.has(e.id) ? 0.35 : 1" />
              <text v-if="e.kind !== 'IN_APP' || highlight.has(e.id)" :x="ends(e)!.mx" :y="ends(e)!.my - 5" text-anchor="middle" font-size="10" :fill="e.kind === 'DENY' ? '#EF4444' : '#6B7280'"
                    :opacity="highlight.size && !highlight.has(e.id) ? 0.4 : 1">{{ e.label }}</text>
            </template>
          </g>
          <!-- 节点 -->
          <g v-for="n in vnodes" :key="n.id" class="cursor-pointer" :transform="`translate(${n.x},${n.y})`"
             :opacity="highlight.size && !n.d.center && n.id !== selectedId && !hlNodes.has(n.id) ? 0.45 : 1"
             @pointerdown.stop="onDown($event, n.id)">
            <template v-if="n.shape === 'circle'">
              <circle v-if="n.d.center" r="58" fill="#EEF7DC" />
              <circle :r="n.w / 2" fill="#1F2421" :stroke="n.id === selectedId ? '#A3D65C' : 'none'" stroke-width="3" />
              <template v-if="n.d.center">
                <circle cy="-16" r="7" fill="#fff" />
                <path d="M-11,2 a11,9 0 0 1 22,0 v1.5 h-22z" transform="translate(0,-6)" fill="#fff" />
                <text y="20" text-anchor="middle" fill="#fff" font-size="15" font-weight="600">{{ n.d.label }}</text>
              </template>
              <text v-else y="4" text-anchor="middle" fill="#fff" font-size="11">{{ n.d.label.length <= 3 ? n.d.label.slice(-2) : n.d.label.slice(0, 1) }}</text>
              <text v-if="!n.d.center" :y="n.h / 2 + 14" text-anchor="middle" fill="#374151" font-size="11">{{ n.d.label }}</text>
            </template>
            <template v-else-if="n.shape === 'square'">
              <rect :x="-n.w / 2" :y="-n.h / 2" :width="n.w" :height="n.h" rx="16" :fill="n.d.center ? '#1F2421' : '#FFF7ED'"
                    :stroke="n.id === selectedId ? '#A3D65C' : 'none'" stroke-width="3" />
              <text y="5" text-anchor="middle" :fill="n.d.center ? '#fff' : '#EA580C'" font-size="13" font-weight="700">{{ n.d.label }}</text>
            </template>
            <template v-else>
              <rect :x="-n.w / 2" :y="-n.h / 2" :width="n.w" :height="n.h" rx="16"
                    :fill="n.d.type === 'PERM' ? '#fff' : n.d.type === 'RESOURCE' && n.d.state === 'DENY' ? '#FEF2F2' : STYLE[n.d.type]?.bg"
                    :stroke="n.d.type === 'PERM' ? permStroke(n) : n.id === selectedId ? '#A3D65C' : 'none'" :stroke-width="n.d.type === 'PERM' ? 2 : 2.5" />
              <text v-if="n.d.type === 'PERM'" y="4" text-anchor="middle" font-size="12" font-family="ui-monospace,Menlo,monospace"
                    :fill="n.d.state === 'DENY' || n.d.risk === 'HIGH' ? '#EF4444' : '#1F2421'">{{ n.d.label }}</text>
              <text v-else y="4" text-anchor="middle" font-size="12" :fill="n.d.type === 'RESOURCE' && n.d.state === 'DENY' ? '#EF4444' : STYLE[n.d.type]?.fg">
                {{ ICON[n.d.type] }} {{ n.d.label }}
              </text>
            </template>
          </g>
        </g>
      </svg>

      <div v-if="loading" class="pds-muted absolute left-6 top-5">加载中…</div>
      <div v-else-if="!vnodes.length" class="pds-muted absolute inset-0 flex items-center justify-center">
        {{ view === 'person' ? '选择一个人员查看其权限关系' : view === 'perm' ? '选择一个权限节点' : '暂无数据' }}
      </div>
      <div v-if="graph && view === 'person'" class="absolute left-6 top-5 text-[12px] text-[#6B7280]">
        拥有 {{ graph.summary.permissions }} 项权限 · 高风险 {{ graph.summary.highRisk }} · 被禁止 {{ graph.summary.denied }}<template v-if="graph.truncated"> · 节点过多已截断</template>
      </div>
      <div v-else-if="graph?.summary.holders != null" class="absolute left-6 top-5 text-[12px] text-[#6B7280]">当前 {{ graph.summary.holders }} 人拥有</div>
      <div v-else-if="graph?.summary.accessUsers != null" class="absolute left-6 top-5 text-[12px] text-[#6B7280]">可进入人数 {{ graph.summary.accessUsers }}</div>

      <!-- 图例与缩放 -->
      <div class="absolute bottom-5 left-5 flex items-end gap-3">
        <div class="grid grid-cols-2 gap-x-5 gap-y-1.5 rounded-2xl bg-white px-4 py-3 text-[12px] shadow-[0_4px_16px_rgba(0,0,0,0.06)]">
          <span v-for="k in ['USER', 'ORG', 'POSITION', 'GROUP', 'ROLE', 'APP', 'RESOURCE']" :key="k" class="flex items-center gap-1.5">
            <span class="size-2.5 rounded-full" :style="{ background: STYLE[k].dot }" />{{ STYLE[k].label }}</span>
          <span class="flex items-center gap-1.5"><span class="size-2.5 rounded-full bg-danger" />高风险</span>
        </div>
        <div class="flex items-center gap-3 rounded-full bg-white px-4 py-2 text-[13px] shadow-[0_4px_16px_rgba(0,0,0,0.06)]">
          <IconMinus class="cursor-pointer" @click="zoom(1 / 1.2)" />
          <span class="w-10 text-center">{{ Math.round(tf.k * 100) }}%</span>
          <IconPlus class="cursor-pointer" @click="zoom(1.2)" />
          <IconExpand class="cursor-pointer" title="适应画布" @click="fit" />
        </div>
      </div>

      <!-- 小地图 -->
      <div v-if="mini" class="absolute bottom-5 right-[360px] rounded-2xl bg-white p-2 shadow-[0_4px_16px_rgba(0,0,0,0.06)]">
        <svg width="150" height="100" class="rounded-lg bg-row">
          <circle v-for="n in vnodes" :key="n.id" :cx="(n.x! - mini.minX) * mini.s" :cy="(n.y! - mini.minY) * mini.s" :r="n.d.center ? 4 : 2.5"
                  :fill="n.d.type === 'PERM' && (n.d.risk === 'HIGH' || n.d.state === 'DENY') ? '#EF4444' : STYLE[n.d.type]?.dot" />
          <rect :x="mini.view.x" :y="mini.view.y" :width="mini.view.w" :height="mini.view.h" fill="none" stroke="#A3D65C" stroke-width="1.5" />
        </svg>
      </div>

      <!-- 详情面板 -->
      <div class="absolute bottom-5 right-5 top-5 w-[320px] overflow-auto rounded-[20px] bg-white p-5 shadow-[0_8px_28px_rgba(31,36,33,0.10)]">
        <template v-if="selected">
          <div class="flex items-start justify-between">
            <div class="pds-muted">{{ selected.d.type === 'PERM' ? '权限节点' : STYLE[selected.d.type]?.label }}</div>
            <IconClose size="small" class="cursor-pointer text-[#9CA3AF]" @click="selectedId = ''; explain = null; highlight = new Set()" />
          </div>
          <div class="mt-1 break-all text-[17px] font-semibold text-ink" :class="{ 'pds-mono': selected.d.type === 'PERM' }" :style="selected.d.type === 'PERM' ? 'font-size:16px' : ''">{{ selected.d.label }}</div>
          <div v-if="selected.d.sub" class="mt-1 text-[14px] text-[#6B7280]">{{ selected.d.sub }}</div>

          <template v-if="explain">
            <div class="mt-5 flex items-center gap-2 text-[13px] text-[#6B7280]">结论
              <span class="pds-badge" :class="explain.verdict === 'ALLOW' ? 'ok' : explain.verdict === 'DENY' ? 'bad' : 'gray'">
                <IconTick v-if="explain.verdict === 'ALLOW'" size="extra-small" class="mr-1" />{{ { ALLOW: '允许', DENY: '禁止', NONE: '未授予' }[explain.verdict] }}</span>
            </div>
            <div v-if="explain.allow.length" class="mt-5 text-[15px] font-semibold text-ink">获得路径 · {{ explain.allow.length }} 条</div>
            <div v-for="(p, i) in explain.allow" :key="'a' + i" class="mt-3 rounded-2xl bg-row px-4 py-3">
              <div class="pds-muted">路径 {{ i + 1 }}</div>
              <div class="mt-1 text-[13px] text-ink">{{ p.steps.join(' → ') }}<template v-if="p.validTo"> · 有效期至 {{ p.validTo }}</template></div>
            </div>
            <div v-if="explain.deny.length" class="mt-5 text-[15px] font-semibold text-danger">禁止来源 · {{ explain.deny.length }} 条</div>
            <div v-for="(p, i) in explain.deny" :key="'d' + i" class="mt-3 rounded-2xl bg-danger-soft px-4 py-3 text-[13px] text-danger">{{ p.steps.join(' → ') }}</div>
            <div v-if="explain.verdict === 'ALLOW' && explain.unusedDays != null && explain.unusedDays >= 90" class="pds-banner warn mt-4 text-[12px]">
              <IconAlertTriangle size="small" /> 该权限 {{ explain.unusedDays }} 天未使用，建议复核是否回收。</div>
            <div v-else-if="explain.verdict === 'ALLOW' && !explain.lastUsed" class="pds-banner gray mt-4 text-[12px]">审计中暂无该权限的使用记录。</div>
            <div v-else-if="explain.lastUsed" class="pds-muted mt-4">最近使用：{{ explain.lastUsed }}</div>
            <button class="pds-btn dark mt-5 w-full justify-center" @click="router.push({ path: '/user-grants', query: { userId: sel.userId, app: explain.app } })">查看完整权限明细</button>
          </template>
          <template v-else>
            <div v-if="selected.d.type === 'PERM' && selected.d.meta?.name" class="mt-2 text-[14px] text-[#4B5563]">{{ selected.d.meta.name }}</div>
            <div v-if="selected.d.meta?.members != null" class="mt-3 text-[13px] text-[#4B5563]">成员 {{ selected.d.meta.members }} 人</div>
            <div v-if="highlight.size" class="pds-banner ok mt-4 text-[12px]">已高亮从中心到该节点的路径</div>
            <button v-if="['USER', 'ORG', 'APP', 'PERM'].includes(selected.d.type) && !selected.d.center" class="pds-btn dark mt-5 w-full justify-center" @click="focusOn(selected.d)">以此为中心查看</button>
          </template>
        </template>
        <div v-else class="pds-muted pt-10 text-center leading-6">点选图中节点查看详情<br />{{ view === 'person' ? '点选权限节点可查看"为什么有"的路径' : '拖动画布平移，滚轮缩放，可拖动节点' }}</div>
      </div>
    </div>

    <PdsModal v-model="simu.open" :title="`权限模拟 · ${userLabel}`" width="680" ok-text="模拟计算" :on-ok="runSimulate">
      <div class="pds-form flex flex-col gap-4">
        <div class="grid grid-cols-[1fr_200px] gap-3">
          <div><div class="pds-label">假设调入/兼任组织</div>
            <Select v-model="simu.orgId" clearable filterable transfer placeholder="不变"><Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option></Select></div>
          <div><div class="pds-label">方式</div><div class="pds-segment row"><button :class="{ on: simu.primary }" @click="simu.primary = true">调岗（主属）</button><button :class="{ on: !simu.primary }" @click="simu.primary = false">兼职</button></div></div>
        </div>
        <div><div class="pds-label">假设担任岗位</div><Select v-model="simu.positionId" clearable filterable transfer placeholder="不变"><Option v-for="p in positions" :key="p.id" :value="p.id">{{ p.name }}</Option></Select></div>
        <div><div class="pds-label">假设授予角色</div><Select v-model="simu.roleIds" multiple filterable transfer placeholder="不变"><Option v-for="r in roles" :key="r.id" :value="r.id">{{ r.name }}</Option></Select></div>
        <div v-if="simu.result" class="grid grid-cols-2 gap-3">
          <div class="rounded-2xl bg-accent-soft p-4">
            <div class="text-[14px] font-semibold text-accent-text">新增 {{ simu.result.added.length }} 项<span v-if="simu.result.highRisk.length" class="ml-2 text-danger">（高风险 {{ simu.result.highRisk.length }}）</span></div>
            <div class="mt-2 max-h-[180px] overflow-auto text-[12px] leading-6 text-ink">
              <div v-for="a in simu.result.added" :key="a" :class="{ 'text-danger': simu.result.highRisk.some((h) => a.startsWith(h + '（')) }">{{ a }}</div>
              <div v-if="!simu.result.added.length" class="pds-muted">无</div>
            </div>
          </div>
          <div class="rounded-2xl bg-row p-4">
            <div class="text-[14px] font-semibold text-[#6B7280]">失去 {{ simu.result.lost.length }} 项</div>
            <div class="mt-2 max-h-[180px] overflow-auto text-[12px] leading-6 text-ink">
              <div v-for="a in simu.result.lost" :key="a">{{ a }}</div>
              <div v-if="!simu.result.lost.length" class="pds-muted">无</div>
            </div>
          </div>
        </div>
        <p class="pds-muted m-0">模拟只计算结果，不会修改任何数据。</p>
      </div>
    </PdsModal>
  </div>
</template>
