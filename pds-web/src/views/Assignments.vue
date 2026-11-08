<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconSearch, IconPlus, IconDownload, IconCopy, IconUserGroup, IconUserCardPhone } from '@kousum/semi-icons-vue'
import {
  api, ACTIONS, DATA_SCOPES, SOURCES, SUBJECT_TYPES, type AppView, type AssignmentRow, type AssignmentStats, type PermNode,
} from '@/api'
import { useSession } from '@/stores/session'
import { usePage } from '@/stores/page'
import { downloadCsv } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import PillSelect from '@/components/pds/PillSelect.vue'
import SubjectPicker from '@/components/pds/SubjectPicker.vue'
import UserSelect from '@/components/pds/UserSelect.vue'
import BatchGrantModal from '@/components/pds/BatchGrantModal.vue'
import OrgIcon from '@/components/pds/OrgIcon.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const page = usePage()
const canGrant = computed(() => session.has('pds:grant:manage'))

// ---------------- 视角与筛选（同步到地址栏）
const view = ref<'app' | 'subject' | 'perm'>((route.query.view as any) || 'app')
const apps = ref<AppView[]>([])
const nodes = ref<PermNode[]>([])
const f = reactive({
  app: (route.query.app as string) || '',
  subjectType: (route.query.subjectType as string) || 'USER',
  subjectId: (route.query.subjectId as string) || '',
  includeChildren: true,
  related: route.query.related !== '0',
  permCode: (route.query.permCode as string) || '',
  filterSubjectType: (route.query.st as string) || '',
  objectType: (route.query.objectType as string) || '',
  source: (route.query.source as string) || '',
  effect: (route.query.effect as string) || '',
  expiring: (route.query.expiring as string) || '',
  keyword: (route.query.q as string) || '',
  page: Number(route.query.page) || 1,
})
const PAGE_SIZE = 10
const rows = ref<AssignmentRow[]>([])
const total = ref(0)
const stats = ref<AssignmentStats | null>(null)
const loading = ref(false)
const selected = ref<Set<string>>(new Set())

function params(extra: object = {}) {
  return {
    app: view.value === 'app' ? f.app || undefined : undefined,
    subjectType: view.value === 'subject' ? f.subjectType : f.filterSubjectType || undefined,
    subjectId: view.value === 'subject' ? f.subjectId || undefined : undefined,
    related: view.value === 'subject' && f.subjectType === 'USER' ? f.related : undefined,
    permCode: view.value === 'perm' ? f.permCode || undefined : undefined,
    objectType: f.objectType || undefined, source: f.source || undefined, effect: f.effect || undefined,
    expiringDays: f.expiring ? Number(f.expiring) : undefined, keyword: f.keyword || undefined,
    page: f.page, pageSize: PAGE_SIZE, ...extra,
  }
}
async function load() {
  if (view.value === 'subject' && !f.subjectId) { rows.value = []; total.value = 0; return }
  if (view.value === 'perm' && !f.permCode) { rows.value = []; total.value = 0; return }
  loading.value = true
  try {
    const r = await api.assignments(params())
    rows.value = r.items
    total.value = r.total
    selected.value = new Set()
    page.set('分配库', { subtitle: `全部授权记录 · 共 ${r.total.toLocaleString('en-US')} 条` })
  } finally {
    loading.value = false
  }
  stats.value = await api.assignmentStats(view.value === 'app' ? f.app || undefined : undefined)
}
function syncUrl() {
  router.replace({ query: {
    view: view.value,
    ...(view.value === 'app' && f.app ? { app: f.app } : {}),
    ...(view.value === 'subject' && f.subjectId ? { subjectType: f.subjectType, subjectId: f.subjectId, ...(f.related ? {} : { related: '0' }) } : {}),
    ...(view.value === 'perm' && f.permCode ? { permCode: f.permCode } : {}),
    ...(f.filterSubjectType ? { st: f.filterSubjectType } : {}), ...(f.objectType ? { objectType: f.objectType } : {}),
    ...(f.source ? { source: f.source } : {}), ...(f.effect ? { effect: f.effect } : {}), ...(f.expiring ? { expiring: f.expiring } : {}),
    ...(f.keyword ? { q: f.keyword } : {}), ...(f.page > 1 ? { page: String(f.page) } : {}),
  } })
}
watch([view, () => f.app, () => f.subjectId, () => f.related, () => f.permCode, () => f.filterSubjectType, () => f.objectType, () => f.source,
  () => f.effect, () => f.expiring], () => { f.page = 1; syncUrl(); load() })
watch(() => f.page, () => { syncUrl(); load() })
let kw: number | undefined
watch(() => f.keyword, () => { window.clearTimeout(kw); kw = window.setTimeout(() => { f.page = 1; syncUrl(); load() }, 300) })
watch(() => f.subjectType, () => { if (view.value === 'subject') f.subjectId = '' })

onMounted(async () => {
  const [a, n] = await Promise.all([api.apps(), api.grantableNodes()])
  apps.value = a
  nodes.value = n
  if (!f.app) f.app = a.find((x) => x.code !== 'pds')?.code || a[0]?.code || ''
  else load()
})

const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const pageList = computed(() => {
  const p = f.page, n = pages.value
  const start = Math.max(1, Math.min(p - 2, n - 4))
  return Array.from({ length: Math.min(5, n) }, (_, i) => start + i)
})
const appName = computed(() => apps.value.find((a) => a.code === f.app)?.name || '选择应用')

// ---------------- 展示
function remaining(validTo?: string) {
  if (!validTo) return null
  return Math.ceil((new Date(validTo).getTime() - Date.now()) / 86400000)
}
const dateOnly = (v?: string) => (v ? new Date(v).toLocaleDateString('sv-SE') : '')
function objectLabel(r: AssignmentRow) {
  if (r.objectType === 'RESOURCE') return `${r.objectName} · ${ACTIONS[r.action || 'READ']}`
  return r.objectName
}
const initial = (n: string) => (n.length <= 3 ? n.slice(-2) : n.slice(0, 1))

// ---------------- 选择与批量
const allChecked = computed(() => rows.value.length > 0 && rows.value.every((r) => selected.value.has(r.id)))
function toggleAll(v: boolean) { selected.value = new Set(v ? rows.value.map((r) => r.id) : []) }
function toggleRow(id: string, v: boolean) { const s = new Set(selected.value); v ? s.add(id) : s.delete(id); selected.value = s }
const selectedRows = computed(() => rows.value.filter((r) => selected.value.has(r.id)))

function revoke(ids: string[], label: string) {
  Modal.confirm({ title: '撤销授权', content: `撤销${label}？相关人员的权限立即重新计算。`, okText: '撤销',
    onOk: async () => { await api.revokeAssignments(ids); Message.success('已撤销'); load() } })
}
/** 续期：在原到期日（已过期或长期则从今天）基础上延长 90 天。 */
function renew(list: AssignmentRow[]) {
  const target = list.filter((r) => r.validTo)
  if (!target.length) { Message.info('所选记录均为长期有效，无需续期'); return }
  Modal.confirm({ title: '续期 90 天', content: `将 ${target.length} 条有期限的授权各延长 90 天。`, okText: '续期',
    onOk: async () => {
      for (const r of target) {
        const base = Math.max(Date.now(), new Date(r.validTo!).getTime())
        await api.updateAssignment(r.id, { validTo: new Date(base + 90 * 86400000).toISOString() })
      }
      Message.success('已续期'); load()
    } })
}
const validity = reactive({ open: false, ids: [] as string[], forever: false, date: '' as any, reason: '' })
function openValidity(list: AssignmentRow[]) {
  const one = list.length === 1 ? list[0] : null
  Object.assign(validity, { open: true, ids: list.map((r) => r.id), forever: one ? !one.validTo : false,
    date: one?.validTo ? new Date(one.validTo) : '', reason: '' })
}
async function submitValidity() {
  if (!validity.forever && !validity.date) { Message.warning('请选择到期日期或设为长期'); return false }
  const iso = validity.forever ? undefined : new Date(validity.date).toISOString()
  if (validity.ids.length === 1) await api.updateAssignment(validity.ids[0], { validTo: iso, clearValidTo: validity.forever, reason: validity.reason || undefined })
  else await api.renewAssignments(validity.ids, iso, validity.forever)
  Message.success('已修改有效期')
  load()
}

// 批量授权 / 复制给
const grant = reactive({ open: false, objects: [] as any[], subjects: [] as any[] })
function openGrant() { Object.assign(grant, { open: true, objects: [], subjects: [] }) }
function copyTo(list: AssignmentRow[]) {
  const objects = list.map((r) => r.objectType === 'ROLE' ? { type: 'ROLE', roleId: r.objectCode, name: r.objectName }
    : r.objectType === 'RESOURCE' ? { type: 'RESOURCE', resourceType: r.objectCode.split('/')[0], refId: r.objectCode.split('/').slice(1).join('/'), action: r.action, name: objectLabel(r) }
      : { type: 'PERM', code: r.objectCode, name: r.objectCode })
  const uniq = objects.filter((o, i) => objects.findIndex((x) => JSON.stringify(x) === JSON.stringify(o)) === i)
  Object.assign(grant, { open: true, objects: uniq, subjects: [] })
}

// 复制权限（人 → 人）
const copy = reactive({ open: false, from: '', to: [] as string[], perms: true, roles: true, resources: true })
async function submitCopy() {
  if (!copy.from || !copy.to.length) { Message.warning('请选择复制来源与目标人员'); return false }
  const r = await api.copyAssignments({ fromUserId: copy.from, toUserIds: copy.to, includePerms: copy.perms, includeRoles: copy.roles, includeResources: copy.resources })
  Message.success(`已复制 ${r.count} 条授权`)
  load()
}

// 导出
async function exportCsv() {
  const r = await api.assignments(params({ page: 1, pageSize: 200 }))
  let items = r.items
  for (let p = 2; items.length < Math.min(r.total, 2000); p++) items = items.concat((await api.assignments(params({ page: p, pageSize: 200 }))).items)
  downloadCsv(`分配库-${new Date().toLocaleDateString('sv-SE')}.csv`, [
    ['主体类型', '主体', '工号', '含下级', '授权对象', '对象编码', '应用', '效果', '数据范围', '有效期至', '来源', '原因', '授权人'],
    ...items.map((x) => [SUBJECT_TYPES[x.subjectType], x.subjectName, x.subjectSub, x.includeChildren ? '是' : '', objectLabel(x), x.objectCode,
      x.appName, x.effect === 'ALLOW' ? '允许' : '禁止', x.dataScope ? DATA_SCOPES[x.dataScope] : '', dateOnly(x.validTo) || '长期',
      SOURCES[x.source] || x.source, x.reason, x.grantedBy]),
  ])
}

const OBJECT_TYPES = [{ value: 'PERM', label: '权限节点' }, { value: 'ACCESS', label: '应用准入' }, { value: 'ROLE', label: '角色' }, { value: 'RESOURCE', label: '资源实例' }]
const permOptions = computed(() => nodes.value.map((n) => ({ value: n.code, label: `${n.name}（${n.code}）` })))
</script>

<template>
  <div class="flex flex-col gap-5 pb-16">
    <div class="pds-segment self-start">
      <button :class="{ on: view === 'app' }" @click="view = 'app'">按应用</button>
      <button :class="{ on: view === 'subject' }" @click="view = 'subject'">按主体</button>
      <button :class="{ on: view === 'perm' }" @click="view = 'perm'">按权限</button>
    </div>

    <!-- 筛选 -->
    <div class="pds-card flex flex-wrap items-center gap-3" style="padding: 20px 24px">
      <Dropdown v-if="view === 'app'" trigger="click" transfer @on-click="(v: string) => (f.app = v)">
        <button class="pds-chip dark" style="height: 40px; padding: 0 16px; font-size: 13px">{{ appName }} ▾</button>
        <template #list><DropdownMenu>
          <DropdownItem v-for="a in apps" :key="a.code" :name="a.code" :selected="a.code === f.app">{{ a.name }}</DropdownItem>
        </DropdownMenu></template>
      </Dropdown>
      <div v-else-if="view === 'subject'" class="flex items-center gap-2">
        <div class="w-[420px]"><SubjectPicker v-model:type="f.subjectType" v-model:id="f.subjectId" v-model:include-children="f.includeChildren" /></div>
        <label v-if="f.subjectType === 'USER'" class="flex items-center gap-1.5 text-[13px]"><Switch v-model="f.related" size="small" /> 含经组织/岗位/用户组的记录</label>
      </div>
      <div v-else class="w-[380px]">
        <Select v-model="f.permCode" filterable transfer placeholder="选择权限节点（含经上级节点与角色获得的记录）">
          <Option v-for="o in permOptions" :key="o.value" :value="o.value">{{ o.label }}</Option>
        </Select>
      </div>
      <PillSelect v-if="view !== 'subject'" v-model="f.filterSubjectType" label="主体" compact :options="Object.entries(SUBJECT_TYPES).map(([value, label]) => ({ value, label }))" />
      <PillSelect v-model="f.objectType" label="对象" compact :options="OBJECT_TYPES" />
      <PillSelect v-model="f.source" label="来源" compact :options="Object.entries(SOURCES).map(([value, label]) => ({ value, label }))" />
      <PillSelect v-model="f.effect" label="效果" compact :options="[{ value: 'ALLOW', label: '允许' }, { value: 'DENY', label: '禁止' }]" />
      <PillSelect v-model="f.expiring" label="有效期" compact all-label="全部" :options="[{ value: '30', label: '30 天内到期' }, { value: '90', label: '90 天内到期' }]" />
      <div class="pds-search w-[180px]"><IconSearch /><input v-model="f.keyword" placeholder="搜索主体或对象" /></div>
      <span class="flex-1" />
      <button class="pds-btn ghost" @click="exportCsv"><IconDownload size="small" /> 导出</button>
      <button v-if="canGrant" class="pds-btn ghost" @click="Object.assign(copy, { open: true, from: '', to: [], perms: true, roles: true, resources: true })"><IconCopy size="small" /> 复制权限</button>
      <button v-if="canGrant" class="pds-btn dark" @click="openGrant"><IconPlus size="small" /> 批量授权</button>
    </div>

    <!-- 概览 -->
    <div class="grid grid-cols-2 gap-5 lg:grid-cols-4">
      <div class="pds-card"><div class="pds-muted">准入人数</div><div class="mt-2 text-[30px] font-bold text-ink">{{ view === 'app' ? (stats?.accessUsers ?? '—') : '—' }}</div></div>
      <div class="pds-card"><div class="pds-muted">授权记录</div><div class="mt-2 text-[30px] font-bold text-ink">{{ (stats?.records ?? 0).toLocaleString('en-US') }}</div></div>
      <div class="pds-card"><div class="pds-muted">高风险授权</div><div class="mt-2 text-[30px] font-bold text-danger">{{ stats?.highRisk ?? 0 }}</div></div>
      <div class="pds-card cursor-pointer" @click="f.expiring = '30'"><div class="pds-muted">30 天内到期</div><div class="mt-2 text-[30px] font-bold text-warn">{{ stats?.expiring ?? 0 }}</div></div>
    </div>

    <!-- 列表 -->
    <div class="pds-card min-w-0">
      <table class="pds-table">
        <thead><tr>
          <th style="width: 44px"><Checkbox :model-value="allChecked" @on-change="toggleAll" /></th>
          <th>主体</th><th>授权对象</th><th>效果</th><th>数据范围</th><th>有效期</th><th>来源</th><th>授权人</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id" :class="{ active: selected.has(r.id) }">
            <td><Checkbox :model-value="selected.has(r.id)" @on-change="(v: boolean) => toggleRow(r.id, v)" /></td>
            <td>
              <span class="flex items-center gap-2.5">
                <span v-if="r.subjectType === 'USER'" class="flex size-8 shrink-0 items-center justify-center rounded-full bg-[#E5E7EB] text-[12px] font-semibold text-ink">{{ initial(r.subjectName) }}</span>
                <OrgIcon v-else-if="r.subjectType === 'ORG'" type="SCENIC" :name="r.subjectName" />
                <IconUserCardPhone v-else-if="r.subjectType === 'POSITION'" class="text-[#6366F1]" />
                <IconUserGroup v-else class="text-[#A855F7]" />
                <span class="min-w-0">
                  <span class="block font-semibold text-ink">{{ r.subjectType === 'USER' ? '' : SUBJECT_TYPES[r.subjectType] + '：' }}{{ r.subjectName }}{{ r.includeChildren ? '（含下级）' : '' }}</span>
                  <span v-if="r.subjectSub" class="block text-[12px] text-[#9CA3AF]">{{ r.subjectSub }}</span>
                </span>
              </span>
            </td>
            <td>
              <span class="flex flex-wrap items-center gap-1.5">
                <span :class="r.objectType === 'PERM' ? 'pds-mono text-[13px] text-ink' : 'text-ink'">{{ r.objectType === 'PERM' ? r.objectCode : objectLabel(r) }}</span>
                <span v-if="r.risk === 'HIGH'" class="pds-badge bad" style="height: 18px; font-size: 11px">高风险</span>
              </span>
              <span v-if="r.objectType === 'PERM'" class="block text-[12px] text-[#9CA3AF]">{{ r.objectName }}</span>
            </td>
            <td><span class="pds-badge" :class="r.effect === 'ALLOW' ? 'ok' : 'bad'">{{ r.effect === 'ALLOW' ? '允许' : '禁止' }}</span></td>
            <td>{{ r.dataScope ? DATA_SCOPES[r.dataScope] : '—' }}</td>
            <td class="whitespace-nowrap">
              <template v-if="r.validTo">
                <span :class="(remaining(r.validTo) ?? 999) <= 0 ? 'text-[#9CA3AF] line-through' : (remaining(r.validTo) ?? 999) <= 60 ? 'text-warn' : ''">
                  {{ dateOnly(r.validTo) }}<template v-if="(remaining(r.validTo) ?? 0) > 0"> · 剩 {{ remaining(r.validTo) }} 天</template><template v-else> · 已过期</template>
                </span>
              </template>
              <span v-else>长期</span>
            </td>
            <td><span class="pds-badge gray">{{ SOURCES[r.source] || r.source }}</span></td>
            <td>{{ r.grantedBy || '—' }}</td>
            <td class="whitespace-nowrap">
              <span v-if="canGrant" class="text-[13px] text-[#6B7280]">
                <button class="pds-link gray" @click="openValidity([r])">编辑</button> ·
                <button class="pds-link gray" :disabled="!r.validTo" @click="renew([r])">续期</button> ·
                <button class="pds-link red" @click="revoke([r.id], `【${r.subjectName}】的【${objectLabel(r)}】`)">撤销</button>
              </span>
            </td>
          </tr>
          <tr v-if="!loading && !rows.length"><td colspan="9" class="empty">
            {{ view === 'subject' && !f.subjectId ? '请先选择一个主体' : view === 'perm' && !f.permCode ? '请先选择一个权限节点' : '没有符合条件的授权记录' }}
          </td></tr>
        </tbody>
      </table>
      <div class="pds-pager">
        <span class="pds-muted">共 {{ total.toLocaleString('en-US') }} 条</span>
        <div class="pages">
          <button :disabled="f.page <= 1" @click="f.page--">‹</button>
          <button v-for="p in pageList" :key="p" :class="{ active: p === f.page }" @click="f.page = p">{{ p }}</button>
          <button :disabled="f.page >= pages" @click="f.page++">›</button>
        </div>
      </div>
    </div>

    <!-- 批量操作条 -->
    <transition name="fade">
      <div v-if="selected.size && canGrant" class="fixed bottom-6 left-1/2 z-[800] flex -translate-x-1/2 items-center gap-6 rounded-[24px] bg-ink px-7 py-3.5 text-[14px] text-white shadow-[0_12px_32px_rgba(0,0,0,0.25)]">
        <span>已选 {{ selected.size }} 项</span>
        <button class="cursor-pointer border-0 bg-transparent text-white hover:text-accent" @click="renew(selectedRows)">续期</button>
        <button class="cursor-pointer border-0 bg-transparent text-white hover:text-accent" @click="openValidity(selectedRows)">修改有效期</button>
        <button class="cursor-pointer border-0 bg-transparent text-white hover:text-accent" @click="copyTo(selectedRows)">复制给…</button>
        <button class="cursor-pointer border-0 bg-transparent text-danger-2 hover:opacity-80" @click="revoke([...selected], `所选 ${selected.size} 条授权`)">撤销</button>
      </div>
    </transition>

    <BatchGrantModal v-model="grant.open" :preset-objects="grant.objects" :preset-subjects="grant.subjects" :app="view === 'app' ? f.app : undefined" @done="load" />

    <PdsModal v-model="validity.open" :title="validity.ids.length > 1 ? `修改有效期（${validity.ids.length} 条）` : '编辑授权'" :on-ok="submitValidity">
      <div class="pds-form flex flex-col gap-4">
        <RadioGroup v-model="validity.forever" type="button">
          <Radio :label="false">指定到期日</Radio><Radio :label="true">长期有效</Radio>
        </RadioGroup>
        <DatePicker v-if="!validity.forever" v-model="validity.date" type="date" placeholder="到期日期" transfer style="width: 100%"
                    :options="{ disabledDate: (d: Date) => d.getTime() < Date.now() }" />
        <Input v-if="validity.ids.length === 1" v-model="validity.reason" placeholder="修改原因（可选）" />
      </div>
    </PdsModal>

    <PdsModal v-model="copy.open" title="复制权限" ok-text="复制" :on-ok="submitCopy">
      <div class="pds-form flex flex-col gap-4">
        <div><div class="pds-label">从（来源人员）</div><UserSelect v-model="copy.from" /></div>
        <div><div class="pds-label">复制给（可多选）</div><UserSelect v-model="copy.to" multiple /></div>
        <div class="flex gap-5">
          <Checkbox v-model="copy.perms">权限节点授权</Checkbox><Checkbox v-model="copy.roles">个人绑定的角色</Checkbox><Checkbox v-model="copy.resources">资源授权</Checkbox>
        </div>
        <p class="pds-muted m-0">只复制直接授予来源人员个人的记录；经组织、岗位、用户组获得的权限不复制（目标人员按自己的任职获得）。复制的记录保留原有效期。</p>
      </div>
    </PdsModal>
  </div>
</template>

<style scoped>
.fade-enter-active, .fade-leave-active { transition: opacity .15s, transform .15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; transform: translate(-50%, 8px); }
</style>
