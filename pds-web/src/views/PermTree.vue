<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import {
  IconSearch, IconPlus, IconMore, IconChevronRight, IconCopy, IconLock, IconInfoCircle, IconUndo,
} from '@kousum/semi-icons-vue'
import {
  api, SECURITY_LEVELS, type Holder, type HistoryItem, type NodeType, type PermNode, type PermNodeDetail, type TreeApp,
  type TreeChange, type TreeVersion,
} from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import NodeTypeIcon from '@/components/pds/NodeTypeIcon.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canManage = computed(() => session.has('pds:app:manage'))

// ---------------- 应用、节点类型、树
const apps = ref<TreeApp[]>([])
const app = ref<string>((route.query.app as string) || '')
const types = ref<NodeType[]>([])
const typeMap = computed(() => Object.fromEntries(types.value.map((t) => [t.code, t])))
const nodes = ref<PermNode[]>([])
const keyword = ref('')
const typeFilter = ref('')
const expanded = reactive(new Set<string>())
const selectedId = ref<string>((route.query.node as string) || '')

async function loadApps() {
  apps.value = await api.treeApps()
  if (!app.value || !apps.value.some((a) => a.code === app.value)) app.value = apps.value.find((a) => a.code !== 'pds')?.code || apps.value[0]?.code || ''
}
async function loadTree(keepSelection = true) {
  if (!app.value) return
  nodes.value = await api.permTree(app.value)
  if (!keepSelection || !nodes.value.some((n) => n.id === selectedId.value)) {
    selectedId.value = roots.value[0]?.id || ''
  }
  nodes.value.forEach((n) => expanded.add(n.id))
}
const pendingCount = computed(() => nodes.value.filter((n) => n.draft).length)

/** 父子索引（按排序）。 */
const childrenOf = computed(() => {
  const ids = new Set(nodes.value.map((n) => n.id))
  const m = new Map<string, PermNode[]>()
  nodes.value.forEach((n) => {
    const p = n.parentId && ids.has(n.parentId) ? n.parentId : '__root'
    m.set(p, [...(m.get(p) || []), n])
  })
  m.forEach((list) => list.sort((a, b) => a.sort - b.sort || a.code.localeCompare(b.code)))
  return m
})
const roots = computed(() => childrenOf.value.get('__root') || [])
const byId = computed(() => new Map(nodes.value.map((n) => [n.id, n])))

/** 筛选：命中节点及其上级都显示。 */
const visible = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw && !typeFilter.value) return null
  const keep = new Set<string>()
  nodes.value.forEach((n) => {
    const hit = (!kw || n.name.toLowerCase().includes(kw) || n.code.toLowerCase().includes(kw)) && (!typeFilter.value || n.type === typeFilter.value)
    if (!hit) return
    let cur: PermNode | undefined = n
    while (cur) { keep.add(cur.id); cur = cur.parentId ? byId.value.get(cur.parentId) : undefined }
  })
  return keep
})
const rows = computed(() => {
  const out: { node: PermNode; depth: number; hasChildren: boolean }[] = []
  const walk = (pid: string, depth: number) => (childrenOf.value.get(pid) || []).forEach((n) => {
    if (visible.value && !visible.value.has(n.id)) return
    const kids = childrenOf.value.get(n.id) || []
    out.push({ node: n, depth, hasChildren: kids.length > 0 })
    if (expanded.has(n.id) || visible.value) walk(n.id, depth + 1)
  })
  walk('__root', 0)
  return out
})
/** 全部节点按树顺序（带深度），用于父节点选择。 */
const treeOrder = computed(() => {
  const out: { node: PermNode; depth: number }[] = []
  const walk = (pid: string, d: number) => (childrenOf.value.get(pid) || []).forEach((n) => {
    if (n.draft !== 'DELETING') { out.push({ node: n, depth: d }); walk(n.id, d + 1) }
  })
  walk('__root', 0)
  return out
})
const typesInApp = computed(() => {
  const used = new Set(nodes.value.map((n) => n.type))
  return types.value.filter((t) => used.has(t.code))
})
const toggle = (id: string) => (expanded.has(id) ? expanded.delete(id) : expanded.add(id))

// ---------------- 选中节点与编辑
const detail = ref<PermNodeDetail | null>(null)
const tab = ref<'info' | 'children' | 'holders' | 'history'>('info')
const form = reactive({ code: '', name: '', type: '', risk: 'LOW', securityLevel: -1, grantable: true, needMfa: false, dataScopeEnabled: true, description: '' })
const snapshot = ref('')
const dirty = computed(() => detail.value != null && JSON.stringify(form) !== snapshot.value)
const node = computed(() => detail.value?.node || null)

function fillForm(n: PermNode) {
  Object.assign(form, { code: n.code, name: n.name, type: n.type, risk: n.risk || 'LOW', securityLevel: n.securityLevel ?? -1,
    grantable: n.grantable, needMfa: n.needMfa, dataScopeEnabled: n.dataScopeEnabled, description: n.description || '' })
  snapshot.value = JSON.stringify(form)
}
async function loadDetail() {
  if (!selectedId.value) { detail.value = null; return }
  detail.value = await api.permNode(selectedId.value)
  fillForm(detail.value.node)
  if (tab.value === 'holders') loadHolders()
  if (tab.value === 'history') loadHistory()
}
function guard(next: () => void) {
  if (!dirty.value) return next()
  Modal.confirm({ title: '放弃未保存的修改？', content: `节点【${node.value?.name}】有未保存的修改。`, okText: '放弃修改', onOk: next })
}
function select(n: PermNode) {
  if (n.id === selectedId.value) return
  guard(() => { selectedId.value = n.id })
}
function switchApp(code: string) {
  if (code === app.value) return
  guard(() => { app.value = code })
}
watch(selectedId, () => { router.replace({ query: { app: app.value, node: selectedId.value || undefined } }); loadDetail() })
watch(app, async () => { selectedId.value = ''; keyword.value = ''; typeFilter.value = ''; await loadTree(false); loadVersionLabel() })
onBeforeRouteLeave(() => (dirty.value ? window.confirm('节点有未保存的修改，确定离开？') : true))

async function save() {
  const n = node.value!
  const body: Record<string, unknown> = {}
  if (form.name !== n.name) body.name = form.name.trim()
  if (form.type !== n.type) body.type = form.type
  if (form.risk !== n.risk) body.risk = form.risk
  if (form.securityLevel !== (n.securityLevel ?? -1)) {
    if (form.securityLevel < 0) body.clearSecurityLevel = true
    else body.securityLevel = form.securityLevel
  }
  if (form.grantable !== n.grantable) body.grantable = form.grantable
  if (form.needMfa !== n.needMfa) body.needMfa = form.needMfa
  if (form.dataScopeEnabled !== n.dataScopeEnabled) body.dataScopeEnabled = form.dataScopeEnabled
  if (form.description !== (n.description || '')) body.description = form.description
  if (!n.codeLocked && form.code !== n.code) body.code = form.code.trim()
  if (!form.name.trim()) { Message.warning('名称不能为空'); return }
  await api.updateNode(n.id, body)
  Message.success(n.codeLocked ? '已保存为草稿，发布后生效' : '已保存')
  await refresh()
}
function discardForm() { if (node.value) fillForm(node.value) }
async function refresh() {
  await Promise.all([loadTree(), loadApps()])
  await loadDetail()
}

function copyId() {
  navigator.clipboard?.writeText(node.value!.id)
  Message.success('已复制节点 ID')
}
function toggleEnabled() {
  const n = node.value!
  const disabling = n.status === 1
  Modal.confirm({
    title: disabling ? '停用节点' : '启用节点',
    content: disabling ? `停用【${n.name}】及其全部下级。发布后，已有授权保留但不再生效。` : `启用【${n.name}】，发布后生效。`,
    okText: disabling ? '停用' : '启用',
    onOk: async () => { await (disabling ? api.disableNode(n.id) : api.enableNode(n.id)); Message.success('已加入待发布'); refresh() },
  })
}
function remove() {
  const n = node.value!
  Modal.confirm({
    title: '删除节点',
    content: n.draft === 'NEW' ? `删除草稿节点【${n.name}】及其草稿下级？` : `删除【${n.name}】？发布后删除；被授权或角色引用的节点不能删除，可改为停用。`,
    okText: '删除',
    onOk: async () => {
      await api.deleteNode(n.id)
      Message.success(n.draft === 'NEW' ? '已删除' : '已标记删除，发布后生效')
      if (n.draft === 'NEW') selectedId.value = n.parentId || ''
      refresh()
    },
  })
}
function undo() {
  const n = node.value!
  Modal.confirm({ title: '撤销修改', content: `撤销【${n.name}】全部未发布的修改？`, okText: '撤销',
    onOk: async () => { await api.discardNode(n.id); Message.success('已撤销'); refresh() } })
}

// 父节点（移动）
const move = reactive({ open: false, parentId: '' })
const moveTargets = computed(() => {
  const n = node.value
  if (!n) return []
  const banned = new Set<string>([n.id])
  const walk = (id: string) => (childrenOf.value.get(id) || []).forEach((c) => { banned.add(c.id); walk(c.id) })
  walk(n.id)
  const out: { node: PermNode; depth: number }[] = []
  const w = (pid: string, d: number) => (childrenOf.value.get(pid) || []).forEach((c) => {
    if (banned.has(c.id)) return
    out.push({ node: c, depth: d }); w(c.id, d + 1)
  })
  w('__root', 0)
  return out
})
async function submitMove() {
  await api.updateNode(node.value!.id, move.parentId ? { parentId: move.parentId } : { moveToRoot: true })
  Message.success(node.value!.codeLocked ? '已加入待发布' : '已移动')
  refresh()
}

// ---------------- 下级 / 谁拥有 / 变更记录
const holders = ref<{ total: number; items: Holder[] }>({ total: 0, items: [] })
const history = ref<HistoryItem[]>([])
async function loadHolders() { if (node.value?.status === 1) holders.value = await api.nodeHolders(node.value.id); else holders.value = { total: 0, items: [] } }
async function loadHistory() { if (node.value) history.value = await api.nodeHistory(node.value.id) }
watch(tab, (t) => { if (t === 'holders') loadHolders(); if (t === 'history') loadHistory() })

// ---------------- 新增节点
const create = reactive({ open: false, parentId: '', type: '', name: '', segment: '', risk: 'LOW', description: '' })
const parentNode = computed(() => (create.parentId ? byId.value.get(create.parentId) : undefined))
const allowedTypes = computed(() => types.value.filter((t) => !t.parentTypes.length || (parentNode.value && t.parentTypes.includes(parentNode.value.type))))
function openCreate(parentId?: string) {
  const p = parentId ?? ''
  Object.assign(create, { open: true, parentId: p, type: '', name: '', segment: '', risk: 'LOW', description: '' })
  const first = allowedTypes.value[0]
  create.type = first?.code || ''
  create.risk = first?.defaultRisk || 'LOW'
}
watch(() => create.type, (t) => { if (typeMap.value[t]) create.risk = typeMap.value[t].defaultRisk })
const codePreview = computed(() => `${parentNode.value?.code || app.value}:${create.segment || '自动生成'}`)
async function submitCreate() {
  if (!create.name.trim()) { Message.warning('请填写名称'); return false }
  if (!create.type) { Message.warning('请选择节点类型'); return false }
  if (create.segment && !/^[a-z0-9_]{1,32}$/.test(create.segment)) { Message.warning('编码标识只能为小写字母、数字和下划线'); return false }
  const n = await api.createNode({ appCode: app.value, parentId: create.parentId || undefined, type: create.type, name: create.name.trim(),
    segment: create.segment || undefined, risk: create.risk, description: create.description || undefined })
  Message.success('已新增草稿节点，发布后生效')
  if (create.parentId) expanded.add(create.parentId)
  await Promise.all([loadTree(), loadApps()])
  selectedId.value = n.id
  tab.value = 'info'
}

// ---------------- 差异与发布、版本
const nextVersion = ref('')
async function loadVersionLabel() { if (app.value) nextVersion.value = (await api.nextVersion(app.value)).version }
const diff = reactive({ open: false, items: [] as TreeChange[], summary: '' })
async function openDiff() { Object.assign(diff, { open: true, items: await api.treeDiff(app.value), summary: '' }) }
async function publish() {
  const v = await api.publishTree(app.value, diff.summary || undefined)
  Message.success(`已发布 ${v.version}，相关人员权限已重新计算`)
  diff.open = false
  await refresh()
  loadVersionLabel()
}
function confirmPublish() {
  Modal.confirm({ title: `发布 ${nextVersion.value}`, content: `发布 ${pendingCount.value} 处修改，发布后立即生效并通知订阅的应用。`, okText: '发布', onOk: publish })
}
const versions = reactive({ open: false, items: [] as TreeVersion[] })
async function openVersions() { Object.assign(versions, { open: true, items: await api.treeVersions(app.value) }) }

// ---------------- 节点类型管理
const typeMgr = reactive({ open: false, editing: '' as string, form: { code: '', name: '', icon: 'button', color: '#4A7A22', parentTypes: [] as string[],
  grantable: true, dataScope: false, defaultRisk: 'LOW', description: '', sort: 100 } })
const ICON_OPTIONS = [['folder', '文件夹'], ['database', '数据库'], ['module', '模块'], ['menu', '菜单'], ['page', '页面'], ['button', '按钮'], ['api', '接口'],
  ['table', '数据表'], ['field', '字段'], ['ai', 'AI'], ['tool', '工具'], ['model', '模型'], ['key', '钥匙']]
function editType(t?: NodeType) {
  typeMgr.editing = t ? t.code : '__new'
  Object.assign(typeMgr.form, t ? { code: t.code, name: t.name, icon: t.icon || 'button', color: t.color || '#4A7A22', parentTypes: [...t.parentTypes],
    grantable: t.grantable, dataScope: t.dataScope, defaultRisk: t.defaultRisk, description: t.description || '', sort: t.sort }
    : { code: '', name: '', icon: 'button', color: '#4A7A22', parentTypes: [], grantable: true, dataScope: false, defaultRisk: 'LOW', description: '', sort: 100 })
}
async function saveType() {
  const f = typeMgr.form
  if (!/^[A-Z][A-Z0-9_]{1,31}$/.test(f.code)) { Message.warning('类型编码只能为大写字母、数字和下划线'); return }
  if (!f.name.trim()) { Message.warning('请填写类型名称'); return }
  if (typeMgr.editing === '__new') await api.createNodeType(f)
  else await api.updateNodeType(typeMgr.editing, f)
  Message.success('已保存节点类型')
  typeMgr.editing = ''
  types.value = await api.nodeTypes()
  loadTree()
}
function deleteType(t: NodeType) {
  Modal.confirm({ title: '删除节点类型', content: `删除【${t.name}】？`, okText: '删除',
    onOk: async () => { await api.deleteNodeType(t.code); Message.success('已删除'); types.value = await api.nodeTypes() } })
}

// ---------------- 新增应用
const CATEGORIES: Record<string, string> = {
  APPLICATION: '应用层', QUERY: '查数层', SECURITY: '统一权限', DATA_OUTLET: '数据出口', OFFLINE_GOVERNANCE: '离线治理',
  REALTIME_GOVERNANCE: '实时治理', DATA_CLEANSING: '数据清洗', DATA_INTEGRATION: '数据集成', FOUNDATION: '公共底座', FEEDBACK: '回流平台',
}
const appForm = reactive({ open: false, code: '', name: '', category: 'APPLICATION', description: '', accessControl: true })
async function submitApp() {
  if (!/^[a-z][a-z0-9_]{1,31}$/.test(appForm.code)) { Message.warning('应用编码只能为小写字母开头的字母数字组合'); return false }
  if (!appForm.name.trim()) { Message.warning('请填写应用名称'); return false }
  await api.createApp({ code: appForm.code, name: appForm.name.trim(), category: appForm.category, description: appForm.description || undefined,
    accessControl: appForm.accessControl })
  Message.success(`已新增应用，已自动生成准入节点 ${appForm.code}:access`)
  await loadApps()
  app.value = appForm.code
}
function onMore(name: string) {
  if (name === 'types') { typeMgr.open = true; typeMgr.editing = '' }
  if (name === 'app') Object.assign(appForm, { open: true, code: '', name: '', category: 'APPLICATION', description: '', accessControl: true })
  if (name === 'versions') openVersions()
  if (name === 'export') exportManifest()
}
/** 把当前应用的节点导出为权限清单 JSON（交给应用开发对接）。 */
function exportManifest() {
  const a = apps.value.find((x) => x.code === app.value)
  const manifest = {
    name: a?.name, version: nextVersion.value, category: a?.category,
    permissions: nodes.value.filter((n) => n.status === 1).map((n) => ({
      code: n.code, name: n.name, type: n.type, risk: n.risk, description: n.description || undefined,
      parent: n.parentId ? byId.value.get(n.parentId)?.code : undefined,
    })),
  }
  const blob = new Blob([JSON.stringify(manifest, null, 2)], { type: 'application/json' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `${app.value}-manifest.json`
  link.click()
  URL.revokeObjectURL(link.href)
}

onMounted(async () => {
  types.value = await api.nodeTypes()
  await loadApps()
  await loadTree()
  loadVersionLabel()
  loadDetail()
})

const draftText: Record<string, { label: string; cls: string }> = {
  NEW: { label: '新增', cls: 'ok' }, MODIFIED: { label: '已修改', cls: 'warn' }, DELETING: { label: '待删除', cls: 'bad' },
}
const riskSeg = [{ v: 'LOW', l: '低', c: 'low' }, { v: 'MEDIUM', l: '中', c: 'mid' }, { v: 'HIGH', l: '高', c: 'high' }]
const levelTag = (l?: number) => (l == null ? null : l >= 3 ? { t: '机密', c: 'bad' } : l === 2 ? { t: '秘密', c: 'warn' } : null)
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-[420px_1fr]">
    <!-- 左：应用 + 权限树 -->
    <div class="pds-card self-start">
      <div class="mb-4 flex flex-wrap gap-2">
        <button v-for="a in apps" :key="a.code" class="pds-chip" style="height: 30px; padding: 0 12px; font-size: 13px"
                :class="a.code === app ? 'dark' : ''" @click="switchApp(a.code)">
          {{ a.name }}<span v-if="a.pendingCount" class="ml-1 inline-block size-1.5 rounded-full bg-[#F59E0B] align-middle" />
        </button>
      </div>
      <div class="mb-3 flex items-center gap-2">
        <div class="pds-search flex-1"><IconSearch /><input v-model="keyword" placeholder="搜索节点名称 / 编码" /></div>
        <button v-if="canManage" class="pds-btn dark" @click="openCreate(selectedId && node?.draft !== 'DELETING' ? selectedId : '')"><IconPlus size="small" /> 新增节点</button>
        <Dropdown trigger="click" transfer placement="bottom-end" @on-click="onMore">
          <button class="pds-icon-btn"><IconMore /></button>
          <template #list>
            <DropdownMenu>
              <DropdownItem name="types">节点类型管理</DropdownItem>
              <DropdownItem v-if="canManage" name="app">新增应用</DropdownItem>
              <DropdownItem name="versions">发布记录</DropdownItem>
              <DropdownItem name="export">导出为权限清单</DropdownItem>
            </DropdownMenu>
          </template>
        </Dropdown>
      </div>
      <div class="mb-4 flex flex-wrap gap-1.5">
        <button class="pds-chip" :class="{ on: !typeFilter }" @click="typeFilter = ''">全部</button>
        <button v-for="t in typesInApp" :key="t.code" class="pds-chip" :class="{ on: typeFilter === t.code }" @click="typeFilter = typeFilter === t.code ? '' : t.code">{{ t.name }}</button>
      </div>

      <div class="flex flex-col gap-0.5">
        <div v-for="r in rows" :key="r.node.id"
             class="group flex h-10 cursor-pointer items-center gap-1.5 rounded-xl pr-3 text-[14px] transition-colors"
             :class="[r.node.id === selectedId ? 'bg-accent-soft font-semibold' : 'hover:bg-row', r.node.status === 0 || r.node.draft === 'DELETING' ? 'opacity-55' : '']"
             :style="{ paddingLeft: 6 + r.depth * 22 + 'px' }" @click="select(r.node)">
          <span class="flex size-5 shrink-0 items-center justify-center text-[#6B7280]" @click.stop="r.hasChildren && toggle(r.node.id)">
            <IconChevronRight v-if="r.hasChildren" size="small" class="transition-transform" :class="{ 'rotate-90': expanded.has(r.node.id) || visible }" />
          </span>
          <NodeTypeIcon :icon="typeMap[r.node.type]?.icon" :color="typeMap[r.node.type]?.color" />
          <span class="truncate text-ink" :class="{ 'line-through': r.node.draft === 'DELETING' }">{{ r.node.name }}</span>
          <span v-if="r.node.risk === 'HIGH'" class="pds-badge bad" style="height: 18px; font-size: 11px; padding: 0 6px">高风险</span>
          <span v-if="levelTag(r.node.securityLevel)" class="pds-badge" :class="levelTag(r.node.securityLevel)!.c" style="height: 18px; font-size: 11px; padding: 0 6px">{{ levelTag(r.node.securityLevel)!.t }}</span>
          <span v-if="r.node.type.startsWith('AI_') && r.node.type !== 'AI_GROUP'" class="text-[11px] text-[#9CA3AF]">{{ r.node.typeName }}</span>
          <span class="flex-1" />
          <span v-if="r.node.draft" class="pds-badge" :class="draftText[r.node.draft].cls" style="height: 18px; font-size: 11px; padding: 0 6px">{{ draftText[r.node.draft].label }}</span>
          <span v-else-if="r.node.status === 0" class="pds-badge gray" style="height: 18px; font-size: 11px; padding: 0 6px">停用</span>
          <span class="w-7 text-right text-[11px] text-[#9CA3AF]">{{ r.node.source === 'MANIFEST' ? '清单' : '手工' }}</span>
        </div>
        <div v-if="!rows.length" class="pds-muted py-10 text-center">
          {{ keyword || typeFilter ? '没有匹配的节点' : '该应用还没有权限节点' }}
          <div v-if="canManage && !keyword && !typeFilter" class="mt-3"><button class="pds-btn dark sm" @click="openCreate('')"><IconPlus size="small" /> 新增第一个节点</button></div>
        </div>
      </div>
    </div>

    <!-- 右：节点详情 -->
    <div class="pds-card min-w-0 self-start" style="padding: 28px">
      <div v-if="pendingCount" class="pds-banner ok mb-6">
        <IconInfoCircle />
        <span class="flex-1">草稿中有 {{ pendingCount }} 处修改未发布</span>
        <button class="pds-btn ghost sm" @click="openDiff">查看差异</button>
        <button v-if="canManage" class="pds-btn dark sm" @click="confirmPublish">发布 {{ nextVersion }}</button>
      </div>

      <template v-if="node">
        <div class="mb-6 flex items-start gap-3">
          <h2 class="m-0 text-[24px] font-bold text-ink" :class="{ 'line-through opacity-60': node.draft === 'DELETING' }">{{ node.name }}</h2>
          <span class="pds-badge gray mt-1.5">{{ node.typeName }}</span>
          <span v-if="node.risk === 'HIGH'" class="pds-badge bad mt-1.5">高风险</span>
          <span v-else-if="node.risk === 'MEDIUM'" class="pds-badge warn mt-1.5">中风险</span>
          <span v-if="node.draft" class="pds-badge mt-1.5" :class="draftText[node.draft].cls">{{ draftText[node.draft].label }}</span>
          <span v-else-if="node.status === 0" class="pds-badge gray mt-1.5">已停用</span>
          <span class="flex-1" />
          <span v-if="canManage" class="mt-2 flex items-center gap-4">
            <button v-if="node.draft === 'MODIFIED' || node.draft === 'DELETING'" class="pds-link gray flex items-center gap-1" @click="undo"><IconUndo size="small" /> 撤销修改</button>
            <button v-if="node.draft !== 'NEW' && node.draft !== 'DELETING'" class="pds-link gray text-[14px]" @click="toggleEnabled">{{ node.status === 0 ? '启用' : '停用' }}</button>
            <button v-if="node.draft !== 'DELETING'" class="pds-link red text-[14px]" @click="remove">删除</button>
          </span>
        </div>

        <div class="pds-tabs">
          <button :class="{ on: tab === 'info' }" @click="tab = 'info'">基本信息</button>
          <button :class="{ on: tab === 'children' }" @click="tab = 'children'">下级节点<template v-if="detail?.children.length">（{{ detail.children.length }}）</template></button>
          <button :class="{ on: tab === 'holders' }" @click="tab = 'holders'">谁拥有（{{ detail?.holderCount ?? 0 }} 人）</button>
          <button :class="{ on: tab === 'history' }" @click="tab = 'history'">变更记录</button>
        </div>

        <!-- 基本信息 -->
        <div v-if="tab === 'info'" class="pds-form">
          <div class="grid grid-cols-2 gap-x-6 gap-y-5">
            <div>
              <div class="pds-label">节点 ID（只读）</div>
              <div class="pds-readonly"><span class="pds-mono text-[14px]">{{ node.id }}</span>
                <button class="cursor-pointer border-0 bg-transparent text-[#6B7280]" title="复制" @click="copyId"><IconCopy size="small" /></button></div>
            </div>
            <div>
              <div class="pds-label">权限编码 <span class="text-warn">{{ node.codeLocked ? '发布后锁定' : '草稿可修改，发布后锁定' }}</span></div>
              <div v-if="node.codeLocked" class="pds-readonly"><span class="pds-mono text-[14px]">{{ node.code }}</span><IconLock size="small" /></div>
              <Input v-else v-model="form.code" size="large" class="pds-mono" :disabled="!canManage" />
            </div>
            <div>
              <div class="pds-label">名称</div>
              <Input v-model="form.name" size="large" :disabled="!canManage" />
            </div>
            <div>
              <div class="pds-label">所属父节点 <button v-if="canManage" class="pds-link" @click="Object.assign(move, { open: true, parentId: node.parentId || '' })">移动</button></div>
              <div class="flex h-[44px] items-center rounded-xl border border-[#E5E7EB] px-4 text-[14px]">
                {{ apps.find((a) => a.code === app)?.name }}<template v-for="p in detail?.path" :key="p.id"><span class="mx-1 text-[#9CA3AF]">›</span>{{ p.name }}</template>
              </div>
            </div>
            <div>
              <div class="pds-label">节点类型</div>
              <Select v-model="form.type" size="large" :disabled="!canManage" transfer>
                <Option v-for="t in types" :key="t.code" :value="t.code" :label="t.name">
                  <span class="flex items-center gap-2"><NodeTypeIcon :icon="t.icon" :color="t.color" />{{ t.name }}<span class="pds-muted">{{ t.code }}</span></span>
                </Option>
              </Select>
            </div>
            <div>
              <div class="pds-label">风险等级</div>
              <div class="pds-seg">
                <button v-for="r in riskSeg" :key="r.v" :class="[r.c, { on: form.risk === r.v }]" :disabled="!canManage" @click="form.risk = r.v">{{ r.l }}</button>
              </div>
            </div>
            <div>
              <div class="pds-label">密级</div>
              <Select v-model="form.securityLevel" size="large" :disabled="!canManage" transfer>
                <Option :value="-1">未设置</Option>
                <Option v-for="(l, i) in SECURITY_LEVELS" :key="l" :value="i">{{ l }}</Option>
              </Select>
            </div>
            <div class="flex items-end gap-6 pb-3">
              <label class="flex items-center gap-2 text-[13px]"><Switch v-model="form.grantable" size="small" :disabled="!canManage" /> 可授权</label>
              <label class="flex items-center gap-2 text-[13px]"><Switch v-model="form.needMfa" size="small" :disabled="!canManage" /> 需二次验证</label>
              <label class="flex items-center gap-2 text-[13px]"><Switch v-model="form.dataScopeEnabled" size="small" :disabled="!canManage" /> 支持数据范围</label>
            </div>
            <div class="col-span-2">
              <div class="pds-label">描述</div>
              <Input v-model="form.description" type="textarea" :rows="3" :disabled="!canManage" placeholder="说明该权限允许做什么、使用场景与注意事项" />
            </div>
          </div>
          <div v-if="dirty" class="mt-6 flex items-center justify-between rounded-2xl bg-row px-5 py-3">
            <span class="text-[13px] text-[#4B5563]">有未保存的修改{{ node.codeLocked ? '，保存后进入草稿，发布后生效' : '' }}</span>
            <span class="flex gap-2"><button class="pds-btn ghost sm" @click="discardForm">放弃</button><button class="pds-btn dark sm" @click="save">保存</button></span>
          </div>
          <p class="pds-muted mb-0 mt-5">授予上级节点即包含其全部下级（高风险节点除外，需单独授予）；禁止上级节点即禁止全部下级。来源：{{ node.source === 'MANIFEST' ? '应用清单同步' : '后台维护' }}。</p>
        </div>

        <!-- 下级节点 -->
        <div v-else-if="tab === 'children'">
          <div class="mb-3 flex justify-end"><button v-if="canManage && node.draft !== 'DELETING'" class="pds-btn dark sm" @click="openCreate(node.id)"><IconPlus size="small" /> 新增下级</button></div>
          <table class="pds-table">
            <thead><tr><th>名称</th><th>编码</th><th>类型</th><th>风险</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="c in detail?.children" :key="c.id" class="clickable" @click="select(c)">
                <td><span class="flex items-center gap-2"><NodeTypeIcon :icon="typeMap[c.type]?.icon" :color="typeMap[c.type]?.color" />{{ c.name }}</span></td>
                <td class="pds-mono">{{ c.code }}</td>
                <td>{{ c.typeName }}</td>
                <td><span class="pds-badge" :class="c.risk === 'HIGH' ? 'bad' : c.risk === 'MEDIUM' ? 'warn' : 'gray'">{{ { LOW: '低', MEDIUM: '中', HIGH: '高' }[c.risk] || c.risk }}</span></td>
                <td><span v-if="c.draft" class="pds-badge" :class="draftText[c.draft].cls">{{ draftText[c.draft].label }}</span><span v-else class="pds-dot" :class="c.status === 1 ? 'ok' : 'off'">{{ c.status === 1 ? '生效' : '停用' }}</span></td>
              </tr>
              <tr v-if="!detail?.children.length"><td colspan="5" class="empty">没有下级节点</td></tr>
            </tbody>
          </table>
        </div>

        <!-- 谁拥有 -->
        <div v-else-if="tab === 'holders'">
          <div v-if="node.status !== 1" class="pds-banner gray">节点尚未发布或已停用，暂无人拥有。</div>
          <template v-else>
            <div class="mb-3 flex items-center justify-between">
              <span class="pds-muted">共 {{ holders.total }} 人（含经角色、组织、岗位、用户组与上级节点获得）</span>
              <button class="pds-link" @click="router.push({ path: '/graph', query: { view: 'perm', code: node.code } })">在权限图谱中查看</button>
            </div>
            <table class="pds-table">
              <thead><tr><th>姓名</th><th>工号</th><th>主部门</th><th>获得来源</th></tr></thead>
              <tbody>
                <tr v-for="h in holders.items" :key="h.userId" class="clickable" @click="router.push({ path: '/user-grants', query: { userId: h.userId, app: node.appCode } })">
                  <td class="text-ink">{{ h.name }}</td><td class="text-[#6B7280]">{{ h.empNo || '—' }}</td><td>{{ h.deptName || '—' }}</td>
                  <td><span class="flex flex-wrap gap-1"><span v-for="s in h.sources" :key="s" class="pds-badge gray">{{ s }}</span></span></td>
                </tr>
                <tr v-if="!holders.items.length"><td colspan="4" class="empty">暂无人拥有该权限</td></tr>
              </tbody>
            </table>
          </template>
        </div>

        <!-- 变更记录 -->
        <div v-else>
          <div v-for="h in history" :key="h.ts + h.action" class="flex gap-3 border-b border-[#F0F2EE] py-3 last:border-0">
            <span class="mt-1.5 size-2 shrink-0 rounded-full bg-accent" />
            <div class="min-w-0 flex-1">
              <div class="text-[14px] text-ink">{{ h.action }}</div>
              <div class="pds-muted mt-0.5">{{ h.userName || '系统' }} · {{ formatTime(h.ts) }}</div>
            </div>
          </div>
          <div v-if="!history.length" class="pds-muted py-10 text-center">暂无变更记录</div>
        </div>
      </template>
      <div v-else class="pds-muted py-24 text-center">在左侧选择一个节点查看详情</div>
    </div>

    <!-- 新增节点 -->
    <PdsModal v-model="create.open" title="新增权限节点" width="600" ok-text="新增（草稿）" :on-ok="submitCreate">
      <div class="pds-form flex flex-col gap-4">
        <div>
          <div class="pds-label">父节点</div>
          <Select v-model="create.parentId" filterable transfer size="large">
            <Option value="">（应用根）{{ apps.find((a) => a.code === app)?.name }}</Option>
            <Option v-for="r in treeOrder" :key="r.node.id" :value="r.node.id" :label="r.node.name">
              <span :style="{ paddingLeft: r.depth * 14 + 'px' }">{{ r.node.name }}</span> <span class="pds-mono text-[#9CA3AF]">{{ r.node.code }}</span>
            </Option>
          </Select>
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <div class="pds-label">节点类型</div>
            <Select v-model="create.type" transfer size="large" placeholder="选择类型">
              <Option v-for="t in allowedTypes" :key="t.code" :value="t.code" :label="t.name">
                <span class="flex items-center gap-2"><NodeTypeIcon :icon="t.icon" :color="t.color" />{{ t.name }}</span>
              </Option>
            </Select>
          </div>
          <div>
            <div class="pds-label">名称</div>
            <Input v-model="create.name" size="large" placeholder="如 下载原文" />
          </div>
        </div>
        <div>
          <div class="pds-label">编码标识（可选，留空自动生成）</div>
          <Input v-model="create.segment" size="large" placeholder="小写字母、数字、下划线，如 download" />
          <div class="pds-muted mt-1">权限编码：<span class="pds-mono text-ink">{{ codePreview }}</span>，节点 ID 由系统生成。发布前可修改编码。</div>
        </div>
        <div>
          <div class="pds-label">风险等级</div>
          <div class="pds-seg"><button v-for="r in riskSeg" :key="r.v" :class="[r.c, { on: create.risk === r.v }]" @click="create.risk = r.v">{{ r.l }}</button></div>
        </div>
        <div><div class="pds-label">描述</div><Input v-model="create.description" type="textarea" :rows="2" /></div>
      </div>
    </PdsModal>

    <!-- 移动 -->
    <PdsModal v-model="move.open" :title="`移动 · ${node?.name}`" ok-text="移动" :on-ok="submitMove">
      <Select v-model="move.parentId" filterable transfer>
        <Option value="">（应用根）</Option>
        <Option v-for="t in moveTargets" :key="t.node.id" :value="t.node.id" :label="t.node.name">
          <span :style="{ paddingLeft: t.depth * 14 + 'px' }">{{ t.node.name }}</span>
        </Option>
      </Select>
      <p class="pds-muted mb-0 mt-3">移动后，授予新上级节点的人员将包含此节点（高风险除外）。已发布节点的移动在发布后生效。</p>
    </PdsModal>

    <!-- 差异 -->
    <PdsModal v-model="diff.open" title="待发布的修改" width="720" :ok-text="`发布 ${nextVersion}`" :hide-ok="!canManage || !diff.items.length" :on-ok="publish">
      <div class="max-h-[420px] overflow-auto">
        <div v-for="c in diff.items" :key="c.nodeId" class="mb-2 rounded-2xl bg-row px-4 py-3">
          <div class="flex items-center gap-2">
            <span class="pds-badge" :class="c.kind === 'NEW' ? 'ok' : c.kind === 'DELETE' ? 'bad' : 'warn'">{{ { NEW: '新增', MODIFY: '修改', DELETE: '删除' }[c.kind] }}</span>
            <span class="text-[14px] font-medium text-ink">{{ c.name }}</span><span class="pds-mono text-[#9CA3AF]">{{ c.code }}</span>
          </div>
          <div v-for="f in c.fields" :key="f.field" class="mt-1.5 pl-1 text-[13px] text-[#4B5563]">
            {{ f.label }}：<span class="text-[#9CA3AF] line-through">{{ f.before }}</span> → <span class="text-ink">{{ f.after }}</span>
          </div>
        </div>
        <div v-if="!diff.items.length" class="pds-muted py-8 text-center">没有待发布的修改</div>
      </div>
      <Input v-if="canManage && diff.items.length" v-model="diff.summary" class="mt-3" placeholder="发布说明（可选，默认按修改自动生成）" />
    </PdsModal>

    <!-- 发布记录 -->
    <PdsModal v-model="versions.open" title="发布记录" width="640" hide-ok>
      <table class="pds-table">
        <thead><tr><th>版本</th><th>说明</th><th>修改数</th><th>发布人</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="v in versions.items" :key="v.id"><td class="pds-mono text-ink">{{ v.version }}</td><td>{{ v.summary }}</td><td>{{ v.changeCount }}</td><td>{{ v.publishedBy || '—' }}</td><td class="text-[#6B7280]">{{ formatTime(v.publishedAt) }}</td></tr>
          <tr v-if="!versions.items.length"><td colspan="5" class="empty">还没有发布过</td></tr>
        </tbody>
      </table>
    </PdsModal>

    <!-- 节点类型管理 -->
    <PdsModal v-model="typeMgr.open" title="节点类型管理" width="860" hide-ok>
      <template v-if="!typeMgr.editing">
        <div class="mb-3 flex items-center justify-between">
          <span class="pds-muted">节点类型全部可维护：新增、修改、删除（在用的类型不能删除）。</span>
          <button v-if="canManage" class="pds-btn dark sm" @click="editType()"><IconPlus size="small" /> 新增类型</button>
        </div>
        <div class="max-h-[460px] overflow-auto">
          <table class="pds-table">
            <thead><tr><th>类型</th><th>编码</th><th>可放在</th><th>可授权</th><th>数据范围</th><th>默认风险</th><th>使用</th><th></th></tr></thead>
            <tbody>
              <tr v-for="t in types" :key="t.code">
                <td><span class="flex items-center gap-2"><NodeTypeIcon :icon="t.icon" :color="t.color" />{{ t.name }}</span></td>
                <td class="pds-mono">{{ t.code }}</td>
                <td class="text-[12px]">{{ t.parentTypes.length ? t.parentTypes.map((c) => typeMap[c]?.name || c).join('、') : '任意位置' }}</td>
                <td>{{ t.grantable ? '是' : '否' }}</td><td>{{ t.dataScope ? '支持' : '—' }}</td>
                <td>{{ { LOW: '低', MEDIUM: '中', HIGH: '高' }[t.defaultRisk] }}</td>
                <td>{{ t.usage }}</td>
                <td class="whitespace-nowrap"><span v-if="canManage" class="flex gap-3"><button class="pds-link" @click="editType(t)">编辑</button>
                  <button class="pds-link red" :disabled="t.usage > 0" :title="t.usage > 0 ? '有节点在用，不能删除' : ''" @click="deleteType(t)">删除</button></span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <div v-else class="pds-form flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-4">
          <div><div class="pds-label">类型编码</div><Input v-model="typeMgr.form.code" :disabled="typeMgr.editing !== '__new'" placeholder="大写字母，如 REPORT" /></div>
          <div><div class="pds-label">类型名称</div><Input v-model="typeMgr.form.name" placeholder="如 报表" /></div>
          <div><div class="pds-label">图标</div>
            <Select v-model="typeMgr.form.icon" transfer><Option v-for="[k, l] in ICON_OPTIONS" :key="k" :value="k" :label="l"><span class="flex items-center gap-2"><NodeTypeIcon :icon="k" :color="typeMgr.form.color" />{{ l }}</span></Option></Select></div>
          <div><div class="pds-label">颜色</div><ColorPicker v-model="typeMgr.form.color" transfer /></div>
          <div class="col-span-2"><div class="pds-label">只能放在以下类型的节点下（不选为任意位置）</div>
            <Select v-model="typeMgr.form.parentTypes" multiple transfer><Option v-for="t in types" :key="t.code" :value="t.code">{{ t.name }}</Option></Select></div>
          <div><div class="pds-label">默认风险</div>
            <div class="pds-seg"><button v-for="r in riskSeg" :key="r.v" :class="[r.c, { on: typeMgr.form.defaultRisk === r.v }]" @click="typeMgr.form.defaultRisk = r.v">{{ r.l }}</button></div></div>
          <div class="flex items-end gap-6 pb-2">
            <label class="flex items-center gap-2 text-[13px]"><Switch v-model="typeMgr.form.grantable" size="small" /> 可授权</label>
            <label class="flex items-center gap-2 text-[13px]"><Switch v-model="typeMgr.form.dataScope" size="small" /> 支持数据范围</label>
          </div>
          <div class="col-span-2"><div class="pds-label">说明</div><Input v-model="typeMgr.form.description" /></div>
        </div>
        <div class="flex justify-end gap-2"><button class="pds-btn ghost sm" @click="typeMgr.editing = ''">返回</button><button class="pds-btn dark sm" @click="saveType">保存类型</button></div>
      </div>
    </PdsModal>

    <!-- 新增应用 -->
    <PdsModal v-model="appForm.open" title="新增应用" ok-text="新增" :on-ok="submitApp">
      <div class="pds-form flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-4">
          <div><div class="pds-label">应用编码</div><Input v-model="appForm.code" placeholder="小写字母，如 pdq" /></div>
          <div><div class="pds-label">应用名称</div><Input v-model="appForm.name" placeholder="如 PDQ 即席自助" /></div>
        </div>
        <div><div class="pds-label">所属分层</div>
          <Select v-model="appForm.category" transfer><Option v-for="(l, k) in CATEGORIES" :key="k" :value="k">{{ l }}</Option></Select></div>
        <div><div class="pds-label">说明</div><Input v-model="appForm.description" /></div>
        <label class="flex items-center gap-2 text-[13px]"><Switch v-model="appForm.accessControl" size="small" /> 开启应用准入（人员须获得"登录进入"权限才能单点登录该应用）</label>
      </div>
    </PdsModal>
  </div>
</template>
