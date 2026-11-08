<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import {
  IconPlus, IconLock, IconSearch, IconMore, IconArrowRight, IconKey, IconServer, IconBolt, IconUserGroup, IconRefresh, IconTick,
  IconClose, IconMinusCircle,
} from '@kousum/semi-icons-vue'
import {
  api, DATA_SCOPES, ROLE_TYPES, SUBJECT_TYPES, flattenOrgs, type AppView, type BindingView, type DistributionRow, type OrgNode,
  type PermNode, type RoleMappingView, type RoleMember, type RoleVersionView, type RoleView, type Subscription,
} from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import SubjectPicker from '@/components/pds/SubjectPicker.vue'
import UserSelect from '@/components/pds/UserSelect.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canRole = computed(() => session.has('pds:role:manage'))
const canApp = computed(() => session.has('pds:app:manage'))

// ---------------- 角色列表
const roles = ref<RoleView[]>([])
const typeFilter = ref('')
const keyword = ref('')
const current = ref<RoleView | null>(null)
const filtered = computed(() => roles.value.filter((r) => (!typeFilter.value || r.type === typeFilter.value)
  && (!keyword.value || r.name.includes(keyword.value) || r.code.toLowerCase().includes(keyword.value.toLowerCase()))))

async function loadRoles(keepId?: string) {
  roles.value = await api.roles()
  const id = keepId || current.value?.id || (route.query.roleId as string)
  const pick = roles.value.find((r) => r.id === id) || roles.value.find((r) => r.type !== 'BUILTIN') || roles.value[0]
  if (pick) await applyRole(pick)
}
const tab = ref<'perms' | 'members' | 'mappings' | 'dist' | 'versions'>((route.query.tab as any) || 'perms')

async function applyRole(r: RoleView) {
  current.value = r
  resetDraft()
  router.replace({ query: { roleId: r.id, ...(tab.value !== 'perms' ? { tab: tab.value } : {}) } })
  loadTab()
}
function selectRole(r: RoleView) {
  if (r.id === current.value?.id) return
  if (dirty.value) {
    Modal.confirm({ title: '放弃未保存的修改？', content: `角色【${current.value?.name}】有 ${changes.value} 项权限修改尚未保存。`, okText: '放弃修改', onOk: () => applyRole(r) })
  } else applyRole(r)
}
watch(tab, (t) => { router.replace({ query: { roleId: current.value?.id, ...(t !== 'perms' ? { tab: t } : {}) } }); loadTab() })
onBeforeRouteLeave(() => (dirty.value ? window.confirm('角色权限有未保存的修改，确定离开？') : true))

// ---------------- 权限（按应用，树形）
const apps = ref<AppView[]>([])
const app = ref('')
const nodes = ref<PermNode[]>([])
const draft = reactive<Record<string, { checked: boolean; dataScope: string }>>({})
const original = ref('')
const draftKey = () => JSON.stringify(Object.entries(draft).filter(([, v]) => v.checked).map(([k, v]) => [k, v.dataScope]).sort())
const dirty = computed(() => current.value != null && draftKey() !== original.value)
const changes = computed(() => {
  if (!current.value) return 0
  const before = new Map(current.value.permissions.map((p) => [p.permCode, p.dataScope || '']))
  const after = new Map(Object.entries(draft).filter(([, v]) => v.checked).map(([k, v]) => [k, v.dataScope]))
  let n = 0
  new Set([...before.keys(), ...after.keys()]).forEach((k) => { if (before.get(k) !== after.get(k)) n++ })
  return n
})
function resetDraft() {
  Object.keys(draft).forEach((k) => delete draft[k])
  current.value?.permissions.forEach((p) => (draft[p.permCode] = { checked: true, dataScope: p.dataScope || '' }))
  original.value = draftKey()
}
async function loadNodes() { nodes.value = app.value ? (await api.permTree(app.value)).filter((n) => n.status === 1 || draft[n.code]?.checked) : [] }
watch(app, loadNodes)
const permRows = computed(() => {
  const ids = new Set(nodes.value.map((n) => n.id))
  const children = new Map<string, PermNode[]>()
  nodes.value.forEach((n) => { const p = n.parentId && ids.has(n.parentId) ? n.parentId : '__root'; children.set(p, [...(children.get(p) || []), n]) })
  const out: { node: PermNode; depth: number }[] = []
  const walk = (pid: string, d: number) => (children.get(pid) || []).sort((a, b) => a.sort - b.sort || a.code.localeCompare(b.code))
    .forEach((n) => { out.push({ node: n, depth: d }); walk(n.id, d + 1) })
  walk('__root', 0)
  return out
})
const inherited = computed(() => new Set(current.value?.inheritedPerms || []))
const countByApp = (code: string) => Object.entries(draft).filter(([k, v]) => v.checked && k.startsWith(code + ':')).length
function toggle(code: string, checked: boolean) { draft[code] = { checked, dataScope: draft[code]?.dataScope || '' } }
const selectable = computed(() => permRows.value.filter((r) => r.node.grantable && !inherited.value.has(r.node.code)))
const allChecked = computed(() => selectable.value.length > 0 && selectable.value.every((r) => draft[r.node.code]?.checked))
function toggleAll(v: boolean) { selectable.value.forEach((r) => toggle(r.node.code, v)) }
async function savePerms() {
  const permissions = Object.entries(draft).filter(([, v]) => v.checked).map(([permCode, v]) => ({ permCode, dataScope: v.dataScope || undefined }))
  await api.setRolePermissions(current.value!.id, permissions)
  Message.success('已保存，生成新版本并通知目标应用')
  await loadRoles(current.value!.id)
}

// ---------------- 成员
const bindings = ref<BindingView[]>([])
const members = ref<RoleMember[]>([])
const bind = reactive({ open: false, type: 'POSITION', id: '', includeChildren: true, validTo: '' as any })
async function submitBinding() {
  if (!bind.id) { Message.warning('请选择授权对象'); return false }
  await api.bindRole(current.value!.id, { subjectType: bind.type, subjectId: bind.id, includeChildren: bind.includeChildren,
    validTo: bind.validTo ? new Date(bind.validTo).toISOString() : undefined })
  Message.success('已绑定，成员变更已下发')
  await loadRoles(current.value!.id)
}
function unbind(b: BindingView) {
  Modal.confirm({ title: '解除绑定', content: `解除【${b.subjectName}】与角色【${current.value?.name}】的绑定？被覆盖人员将立即失去该角色的权限。`, okText: '解除',
    onOk: async () => { await api.unbindRole(b.id); Message.success('已解除'); await loadRoles(current.value!.id) } })
}

// ---------------- 外部编码映射
const mappings = ref<RoleMappingView[]>([])
const mapDraft = ref<{ appCode: string; externalCode: string }[]>([])
const mapDirty = computed(() => JSON.stringify(mapDraft.value) !== JSON.stringify(mappings.value.map(({ appCode, externalCode }) => ({ appCode, externalCode }))))
async function saveMappings() {
  const rows = mapDraft.value.filter((m) => m.appCode && m.externalCode.trim())
  mappings.value = await api.setRoleMappings(current.value!.id, rows)
  mapDraft.value = mappings.value.map(({ appCode, externalCode }) => ({ appCode, externalCode }))
  Message.success('已保存外部编码映射')
}

// ---------------- 下发
const dist = ref<DistributionRow[]>([])
const subs = ref<Subscription[]>([])
const targetSubs = computed(() => subs.value.filter((s) => dist.value.some((d) => d.appCode === s.appCode)))
const modeCount = (k: 'tokenClaim' | 'snapshot' | 'webhook' | 'scim') => targetSubs.value.filter((s) => s[k]).length
const MODES = [
  { k: 'tokenClaim', title: '令牌声明 roles', icon: IconKey },
  { k: 'snapshot', title: '快照拉取', icon: IconServer },
  { k: 'webhook', title: 'Webhook 推送', icon: IconBolt },
  { k: 'scim', title: 'SCIM', icon: IconUserGroup },
] as const
async function reconcile() {
  const r = await api.reconcileRole(current.value!.id)
  Message.success(`已向 ${r.apps} 个应用推送角色定义与完整成员清单`)
  loadTab()
}
async function retry(d: DistributionRow) { await api.retryDelivery(d.deliveryId!); Message.success('已重新加入投递队列'); setTimeout(loadTab, 1500) }
const payload = reactive({ open: false, text: '' })
async function showPayload(d: DistributionRow) {
  const p = await api.deliveryPayload(d.deliveryId!)
  Object.assign(payload, { open: true, text: JSON.stringify(JSON.parse(p.payload), null, 2) })
}
const subOpen = ref(false)
const subItems = ref<(Subscription & { secret?: string })[]>([])
function openSubs() { subOpen.value = true; subItems.value = targetSubs.value.map((s) => ({ ...s, secret: '' })) }
async function saveSubs() {
  for (const s of subItems.value) {
    await api.saveSubscription(s.appCode, { tokenClaim: s.tokenClaim, snapshot: s.snapshot, webhook: s.webhook, webhookUrl: s.webhookUrl || '', secret: s.secret || undefined })
  }
  Message.success('已保存下发方式')
  loadTab()
}

// ---------------- 版本
const versions = ref<RoleVersionView[]>([])
function restore(v: RoleVersionView) {
  Modal.confirm({ title: `回滚到 v${v.version}`, content: `角色权限恢复为 v${v.version}（${v.permCount} 项），生成新版本并下发。`, okText: '回滚',
    onOk: async () => { await api.restoreRole(current.value!.id, v.id); Message.success('已回滚'); await loadRoles(current.value!.id) } })
}

async function loadTab() {
  const r = current.value
  if (!r) return
  if (tab.value === 'perms') loadNodes()
  if (tab.value === 'members') [bindings.value, members.value] = await Promise.all([api.roleBindings(r.id), api.roleMembers(r.id)])
  if (tab.value === 'mappings') {
    mappings.value = await api.roleMappings(r.id)
    mapDraft.value = mappings.value.map(({ appCode, externalCode }) => ({ appCode, externalCode }))
  }
  if (tab.value === 'dist') [dist.value, subs.value] = await Promise.all([api.roleDistribution(r.id), api.subscriptions()])
  if (tab.value === 'versions') versions.value = await api.roleVersions(r.id)
}

// ---------------- 新建 / 编辑 / 复制 / 删除 / 启停
const orgs = ref<(OrgNode & { depth: number })[]>([])
const scenics = computed(() => orgs.value.filter((o) => o.type === 'SCENIC'))
const form = reactive({ open: false, mode: 'create' as 'create' | 'edit', name: '', code: '', type: 'GLOBAL', appCode: '', scenicId: '',
  parentRoleId: '', ownerId: '', description: '', mutex: [] as string[] })
function openCreate() {
  Object.assign(form, { open: true, mode: 'create', name: '', code: '', type: 'GLOBAL', appCode: '', scenicId: '', parentRoleId: '', ownerId: '', description: '', mutex: [] })
}
function openEdit() {
  const r = current.value!
  Object.assign(form, { open: true, mode: 'edit', name: r.name, code: r.code, type: r.type, appCode: r.appCode || '', scenicId: r.scenicId || '',
    parentRoleId: r.parentRoleId || '', ownerId: r.ownerId || '', description: r.description || '', mutex: [...r.mutexRoleIds] })
}
/** 可选父角色：排除自身及以自身为祖先的角色。 */
const parentOptions = computed(() => {
  const self = form.mode === 'edit' ? current.value?.id : undefined
  const isDesc = (r: RoleView): boolean => { let p = r.parentRoleId; const seen = new Set<string>(); while (p && !seen.has(p)) { if (p === self) return true; seen.add(p); p = roles.value.find((x) => x.id === p)?.parentRoleId } return false }
  return roles.value.filter((r) => r.id !== self && !isDesc(r) && r.type !== 'BUILTIN')
})
async function submitForm() {
  if (!form.name.trim()) { Message.warning('请填写角色名称'); return false }
  if (form.mode === 'create') {
    if (form.type === 'APP' && !form.appCode) { Message.warning('请选择所属应用'); return false }
    if (form.type === 'SCENIC' && !form.scenicId) { Message.warning('请选择景区'); return false }
    const r: any = await api.createRole({ name: form.name.trim(), code: form.code.trim() || undefined, type: form.type,
      appCode: form.type === 'APP' ? form.appCode : undefined, scenicId: form.type === 'SCENIC' ? form.scenicId : undefined,
      parentRoleId: form.parentRoleId || undefined, ownerId: form.ownerId || undefined, description: form.description || undefined })
    if (form.mutex.length) await api.setRoleMutex(r.id, form.mutex)
    Message.success('已创建，请为角色勾选权限')
    tab.value = 'perms'
    await loadRoles(r.id)
  } else {
    const r = current.value!
    await api.updateRole(r.id, { name: form.name.trim(), description: form.description, ownerId: form.ownerId || '',
      ...(form.parentRoleId ? { parentRoleId: form.parentRoleId } : { clearParent: true }) })
    if (JSON.stringify([...form.mutex].sort()) !== JSON.stringify([...r.mutexRoleIds].sort())) await api.setRoleMutex(r.id, form.mutex)
    Message.success('已保存')
    await loadRoles(r.id)
  }
}
function toggleStatus(v: boolean) {
  const r = current.value!
  Modal.confirm({ title: v ? '启用角色' : '停用角色', content: v ? `启用【${r.name}】，绑定的人员重新获得其权限。` : `停用后【${r.name}】的全部绑定暂停生效（保留绑定，可再启用），并通知目标应用。`,
    okText: v ? '启用' : '停用', onOk: async () => { await api.updateRole(r.id, { status: v ? 1 : 0 }); Message.success(v ? '已启用' : '已停用'); await loadRoles(r.id) },
    onCancel: () => loadRoles(r.id) })
}
const copyForm = reactive({ open: false, name: '', code: '' })
async function submitCopy() {
  if (!copyForm.name.trim()) { Message.warning('请填写新角色名称'); return false }
  const r = await api.copyRole(current.value!.id, { name: copyForm.name.trim(), code: copyForm.code.trim() || undefined })
  Message.success('已复制（含权限，不含绑定）')
  await loadRoles(r.id)
}
function remove() {
  const r = current.value!
  const bound = r.bindingCount
  Modal.confirm({
    title: '删除角色',
    content: bound ? `【${r.name}】还有 ${bound} 条绑定，删除将一并解除这些绑定，被覆盖人员立即失去该角色的权限，并通知目标应用。此操作不可恢复。`
      : `删除【${r.name}】？此操作不可恢复，并通知目标应用删除该角色。`,
    okText: bound ? '解除绑定并删除' : '删除',
    onOk: async () => { await api.deleteRole(r.id, bound > 0); Message.success('已删除'); current.value = null; await loadRoles() },
  })
}
function onMore(name: string) {
  if (name === 'edit') openEdit()
  if (name === 'copy') Object.assign(copyForm, { open: true, name: `${current.value!.name}（副本）`, code: '' })
  if (name === 'delete') remove()
}

onMounted(async () => {
  const [a, t] = await Promise.all([api.apps(), api.orgTree()])
  apps.value = a
  orgs.value = flattenOrgs(t)
  app.value = a.find((x) => x.code !== 'pds')?.code || a[0]?.code || ''
  await loadRoles()
})

const typeLabel = (r: RoleView) => (r.type === 'APP' ? `应用角色 · ${r.appName || r.appCode}` : r.type === 'SCENIC' ? `景区角色 · ${r.scenicName || ''}` : `${ROLE_TYPES[r.type]}角色`)
const DIST_STATUS: Record<string, { label: string; cls: string }> = {
  SUCCESS: { label: '成功', cls: 'ok' }, PENDING: { label: '待投递', cls: 'gray' }, RETRYING: { label: '重试中', cls: 'warn' }, FAILED: { label: '失败', cls: 'bad' },
}
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-[380px_1fr]">
    <!-- 左：角色列表 -->
    <div class="pds-card self-start">
      <div class="pds-segment row mb-4 w-full justify-between">
        <button :class="{ on: !typeFilter }" @click="typeFilter = ''">全部</button>
        <button v-for="(l, k) in ROLE_TYPES" :key="k" :class="{ on: typeFilter === k }" @click="typeFilter = k">{{ l }}</button>
      </div>
      <div class="mb-4 flex items-center gap-2">
        <div class="pds-search flex-1"><IconSearch /><input v-model="keyword" placeholder="搜索角色" /></div>
        <button v-if="canRole" class="pds-btn dark" @click="openCreate"><IconPlus size="small" /> 新建角色</button>
      </div>
      <div class="flex flex-col gap-1">
        <div v-for="r in filtered" :key="r.id" class="cursor-pointer rounded-2xl px-3 py-3 transition-colors"
             :class="current?.id === r.id ? 'bg-accent-soft' : 'hover:bg-row'" @click="selectRole(r)">
          <div class="flex items-center gap-1.5">
            <IconLock v-if="r.builtin" size="small" class="text-[#6B7280]" />
            <span class="flex-1 truncate text-[15px] font-semibold text-ink" :class="{ 'opacity-50': r.status === 0 }">{{ r.name }}</span>
            <span class="text-[12px]" :class="current?.id === r.id ? 'text-accent-text' : 'text-[#9CA3AF]'">{{ r.memberCount.toLocaleString('en-US') }} 人</span>
          </div>
          <div class="mt-1 flex items-center gap-2">
            <span class="pds-mono text-[#6B7280]">{{ r.code }}</span>
            <span class="pds-badge gray" style="height: 18px; font-size: 11px">{{ ROLE_TYPES[r.type] }}</span>
            <span v-if="r.status === 0" class="pds-badge gray" style="height: 18px; font-size: 11px">已停用</span>
          </div>
        </div>
        <div v-if="!filtered.length" class="pds-muted py-8 text-center">没有匹配的角色</div>
      </div>
    </div>

    <!-- 右 -->
    <div v-if="current" class="flex min-w-0 flex-col gap-5">
      <div class="pds-card" style="padding: 24px 28px">
        <div class="flex items-start gap-3">
          <h2 class="m-0 text-[24px] font-bold text-ink">{{ current.name }}</h2>
          <span class="pds-badge gray mt-1.5">{{ typeLabel(current) }}</span>
          <span class="pds-badge gray mt-1.5">v{{ current.version }}</span>
          <span class="flex-1" />
          <label class="mt-1 flex items-center gap-2 text-[14px] text-ink">
            <Switch :model-value="current.status !== 0" :disabled="!canRole || current.builtin" @on-change="toggleStatus" /> {{ current.status === 0 ? '已停用' : '已启用' }}
          </label>
          <Dropdown v-if="canRole" trigger="click" transfer placement="bottom-end" @on-click="onMore">
            <button class="pds-icon-btn" style="width: 34px; height: 34px"><IconMore size="small" /></button>
            <template #list><DropdownMenu>
              <DropdownItem name="edit">编辑（名称、父角色、互斥、负责人）</DropdownItem>
              <DropdownItem name="copy">复制角色</DropdownItem>
              <DropdownItem v-if="!current.builtin" name="delete" divided>删除角色</DropdownItem>
            </DropdownMenu></template>
          </Dropdown>
        </div>
        <div class="mt-2 flex items-center gap-5 text-[14px] text-[#6B7280]">
          <span class="pds-mono text-[14px]">{{ current.code }}</span>
          <span>负责人 {{ current.ownerName || '未指定' }}</span>
          <span v-if="current.description" class="truncate">{{ current.description }}</span>
        </div>
        <div class="mt-4 flex flex-wrap items-center gap-2">
          <template v-for="p in current.parentChain" :key="p">
            <span class="rounded-full bg-row px-4 py-2 text-[14px] text-ink">{{ p }}</span>
            <IconArrowRight size="small" class="text-[#9CA3AF]" />
          </template>
          <span class="rounded-full bg-accent px-4 py-2 text-[14px] text-ink">{{ current.name }}（当前）</span>
          <span v-if="!current.parentChain.length" class="pds-muted ml-1">未继承其他角色</span>
          <span class="flex-1" />
          <span v-for="m in current.mutexNames" :key="m" class="pds-badge bad" style="height: 26px; padding: 0 10px"><IconMinusCircle size="extra-small" class="mr-1" />互斥：{{ m }}</span>
        </div>
      </div>

      <div class="pds-card relative min-w-0 pb-20">
        <div class="pds-tabs items-end">
          <button :class="{ on: tab === 'perms' }" @click="tab = 'perms'">权限</button>
          <button :class="{ on: tab === 'members' }" @click="tab = 'members'">成员</button>
          <button :class="{ on: tab === 'mappings' }" @click="tab = 'mappings'">外部编码映射</button>
          <button :class="{ on: tab === 'dist' }" @click="tab = 'dist'">下发状态</button>
          <button :class="{ on: tab === 'versions' }" @click="tab = 'versions'">版本</button>
          <span class="flex-1" />
          <button v-if="tab === 'dist' && canRole" class="pds-btn accent sm mb-2" style="padding-bottom: 0" @click="reconcile"><IconRefresh size="small" /> 立即对账</button>
        </div>

        <!-- 权限 -->
        <template v-if="tab === 'perms'">
          <div class="mb-4 flex flex-wrap gap-2">
            <button v-for="a in apps" :key="a.code" class="pds-chip" style="height: 34px; padding: 0 14px; font-size: 13px" :class="app === a.code ? 'dark' : ''" @click="app = a.code">
              {{ a.name }} <span class="opacity-60">{{ countByApp(a.code) }}</span>
            </button>
          </div>
          <div v-if="current.builtin" class="pds-banner warn mb-3 text-[13px]">内置角色的权限由系统维护，只有拥有"授权不受自身权限限制"的安全管理员可以修改。</div>
          <div v-if="current.parentName" class="pds-banner gray mb-3 text-[13px]">继承自父角色【{{ current.parentName }}】的权限自动拥有（灰色勾选），无需重复勾选。</div>
          <table class="pds-table">
            <thead><tr>
              <th style="width: 56px"><Checkbox :model-value="allChecked" :disabled="!canRole" @on-change="toggleAll" /></th>
              <th>权限节点</th><th style="width: 110px">类型</th><th style="width: 80px">风险</th><th style="width: 190px">数据范围</th>
            </tr></thead>
            <tbody>
              <tr v-for="{ node: p, depth } in permRows" :key="p.code" class="clickable"
                  @click="canRole && p.grantable && !inherited.has(p.code) && toggle(p.code, !draft[p.code]?.checked)">
                <td @click.stop>
                  <Checkbox :model-value="!!draft[p.code]?.checked || inherited.has(p.code)" :disabled="!canRole || !p.grantable || inherited.has(p.code) || p.status !== 1"
                            @on-change="(v: boolean) => toggle(p.code, v)" />
                </td>
                <td>
                  <div :style="{ paddingLeft: depth * 18 + 'px' }">
                    <div class="text-ink">{{ p.name }}
                      <span v-if="inherited.has(p.code)" class="pds-badge gray" style="height: 18px">继承</span>
                      <span v-if="!p.grantable" class="pds-badge gray" style="height: 18px">分组</span>
                      <span v-if="p.status !== 1" class="pds-badge gray" style="height: 18px">已停用</span>
                    </div>
                    <div class="pds-mono pds-muted">{{ p.code }}</div>
                  </div>
                </td>
                <td class="text-[13px] text-muted">{{ p.typeName }}</td>
                <td><span class="pds-badge" :class="p.risk === 'HIGH' ? 'bad' : p.risk === 'MEDIUM' ? 'warn' : 'none'">{{ { HIGH: '高', MEDIUM: '中' }[p.risk] || '低' }}</span></td>
                <td @click.stop>
                  <Select v-if="draft[p.code]?.checked && p.dataScopeEnabled" v-model="draft[p.code].dataScope" size="small" clearable transfer placeholder="默认：本人景区" :disabled="!canRole">
                    <Option v-for="(l, k) in DATA_SCOPES" v-show="!['SCENIC_LIST', 'CUSTOM', 'DOMAIN'].includes(k as string)" :key="k" :value="k">{{ l }}</Option>
                  </Select>
                  <span v-else class="text-[#C0C4CC]">—</span>
                </td>
              </tr>
              <tr v-if="!permRows.length"><td colspan="5" class="empty">该应用还没有已发布的权限节点</td></tr>
            </tbody>
          </table>
          <p class="pds-muted mb-0 mt-3">勾选上级节点即包含其下级（高风险节点除外）。未设置数据范围时按"本人所属景区"处理；授予的权限不能超出您自己拥有的权限（安全管理员除外）。</p>
          <div v-if="dirty" class="absolute inset-x-6 bottom-5 flex items-center gap-3 rounded-2xl bg-ink px-5 py-3 text-white shadow-lg">
            <span class="flex-1 text-[14px]">有 <b class="text-accent">{{ changes }}</b> 项权限修改尚未保存，保存后生成 v{{ current.version + 1 }} 并下发</span>
            <button class="pds-btn sm ghost !border-0 !bg-white/10 !text-white" @click="resetDraft">放弃</button>
            <button class="pds-btn sm accent" @click="savePerms">保存权限</button>
          </div>
        </template>

        <!-- 成员 -->
        <template v-else-if="tab === 'members'">
          <div class="mb-3 flex items-center">
            <span class="text-[14px] font-semibold text-ink">绑定（{{ bindings.length }}）</span>
            <span class="pds-muted ml-3 flex-1">被绑定对象覆盖的人员自动获得该角色；组织可选择包含下级。</span>
            <button v-if="canRole" class="pds-btn dark sm" @click="Object.assign(bind, { open: true, id: '', validTo: '' })"><IconPlus size="small" /> 添加绑定</button>
          </div>
          <table class="pds-table mb-6">
            <thead><tr><th>对象类型</th><th>对象</th><th>有效期至</th><th>授权人</th><th>时间</th><th></th></tr></thead>
            <tbody>
              <tr v-for="b in bindings" :key="b.id">
                <td><span class="pds-badge gray">{{ SUBJECT_TYPES[b.subjectType] }}</span></td>
                <td class="text-ink">{{ b.subjectName }}<span v-if="b.includeChildren" class="pds-muted">（含下级）</span></td>
                <td>{{ b.validTo ? formatTime(b.validTo) : '长期' }}</td>
                <td>{{ b.createdBy }}</td>
                <td class="text-muted">{{ formatTime(b.createdAt) }}</td>
                <td><button v-if="canRole" class="pds-link red" @click="unbind(b)">解除</button></td>
              </tr>
              <tr v-if="!bindings.length"><td colspan="6" class="empty">尚未绑定，角色不会对任何人生效</td></tr>
            </tbody>
          </table>
          <div class="mb-3 text-[14px] font-semibold text-ink">拥有该角色的人员（{{ members.length }}）</div>
          <table class="pds-table">
            <thead><tr><th>姓名</th><th>工号</th><th>主部门</th><th>经由</th></tr></thead>
            <tbody>
              <tr v-for="m in members" :key="m.userId" class="clickable" @click="router.push({ path: '/user-grants', query: { userId: m.userId } })">
                <td class="text-ink">{{ m.name }}</td><td class="text-muted">{{ m.empNo || '—' }}</td><td>{{ m.deptName || '—' }}</td>
                <td><span class="flex flex-wrap gap-1"><span v-for="v in m.via" :key="v" class="pds-badge gray">{{ v }}</span></span></td>
              </tr>
              <tr v-if="!members.length"><td colspan="4" class="empty">暂无人员</td></tr>
            </tbody>
          </table>
        </template>

        <!-- 外部编码映射 -->
        <template v-else-if="tab === 'mappings'">
          <p class="pds-muted mt-0">已有自己角色体系的系统（如票付通、PBI），配置该角色在对方系统中的编码；下发时令牌 roles 声明、快照与推送事件使用外部编码。</p>
          <table class="pds-table">
            <thead><tr><th style="width: 280px">目标应用</th><th>外部角色编码</th><th style="width: 80px"></th></tr></thead>
            <tbody>
              <tr v-for="(m, i) in mapDraft" :key="i">
                <td><Select v-model="m.appCode" transfer :disabled="!canRole"><Option v-for="a in apps" :key="a.code" :value="a.code">{{ a.name }}</Option></Select></td>
                <td><Input v-model="m.externalCode" class="pds-mono" placeholder="如 pbi_role_17" :disabled="!canRole" /></td>
                <td><button v-if="canRole" class="pds-link red" @click="mapDraft.splice(i, 1)">移除</button></td>
              </tr>
              <tr v-if="!mapDraft.length"><td colspan="3" class="empty">未配置映射，下发时使用 PDS 角色编码 {{ current.code }}</td></tr>
            </tbody>
          </table>
          <div v-if="canRole" class="mt-4 flex gap-2">
            <button class="pds-btn ghost sm" @click="mapDraft.push({ appCode: '', externalCode: '' })"><IconPlus size="small" /> 添加映射</button>
            <span class="flex-1" />
            <button v-if="mapDirty" class="pds-btn ghost sm" @click="mapDraft = mappings.map(({ appCode, externalCode }) => ({ appCode, externalCode }))">放弃</button>
            <button class="pds-btn dark sm" :disabled="!mapDirty" @click="saveMappings">保存映射</button>
          </div>
        </template>

        <!-- 下发状态 -->
        <template v-else-if="tab === 'dist'">
          <div class="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-4">
            <div v-for="m in MODES" :key="m.k" class="cursor-pointer rounded-[18px] p-5 transition-colors" :class="m.k === 'scim' ? 'bg-row opacity-60' : 'bg-row hover:bg-[#E8EEE3]'"
                 @click="m.k !== 'scim' && canApp && openSubs()">
              <component :is="m.icon" :class="m.k === 'scim' ? 'text-[#9CA3AF]' : 'text-accent-text'" />
              <div class="mt-3 text-[15px] font-semibold" :class="m.k === 'scim' ? 'text-[#9CA3AF]' : 'text-ink'">{{ m.title }}</div>
              <div class="mt-1 text-[12px]">
                <template v-if="m.k === 'scim'"><span class="text-[#9CA3AF]">未启用（二期）</span></template>
                <template v-else-if="modeCount(m.k)"><span class="text-accent-text"><IconTick size="extra-small" /> 已启用</span>
                  <span v-if="modeCount(m.k) < targetSubs.length" class="text-[#9CA3AF]"> · {{ modeCount(m.k) }}/{{ targetSubs.length }} 个应用</span></template>
                <template v-else><span class="text-[#9CA3AF]">未启用</span></template>
              </div>
            </div>
          </div>
          <table class="pds-table">
            <thead><tr><th>应用</th><th>外部角色编码</th><th>最近事件</th><th>投递状态</th><th>耗时</th><th>时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="d in dist" :key="d.appCode">
                <td class="font-semibold text-ink">{{ d.appName }}</td>
                <td class="pds-mono">{{ d.externalCode || current.code }}</td>
                <td>{{ d.lastEventLabel || '—' }}</td>
                <td>
                  <span v-if="d.status" class="pds-badge" :class="DIST_STATUS[d.status].cls">{{ DIST_STATUS[d.status].label }}<template v-if="d.status === 'RETRYING'"> · 第 {{ d.attempts }} 次</template><template v-if="d.status === 'FAILED' && d.lastError"> · {{ d.lastError }}</template></span>
                  <span v-else class="pds-badge gray">{{ d.webhook ? '暂无事件' : '未开启推送' }}</span>
                </td>
                <td>{{ d.latencyMs != null ? `${d.latencyMs.toLocaleString('en-US')} ms` : '—' }}</td>
                <td class="text-muted">{{ d.time ? formatTime(d.time) : '—' }}</td>
                <td class="whitespace-nowrap text-[13px] text-[#6B7280]">
                  <button class="pds-link gray" :disabled="!d.deliveryId || !canApp" @click="retry(d)">重推</button> ·
                  <button class="pds-link gray" :disabled="!d.deliveryId" @click="showPayload(d)">查看载荷</button> ·
                  <button class="pds-link gray" :disabled="!canRole" @click="reconcile">对账</button>
                </td>
              </tr>
              <tr v-if="!dist.length"><td colspan="7" class="empty">该角色还没有目标应用：为角色勾选权限、指定所属应用或配置外部编码后自动成为下发目标</td></tr>
            </tbody>
          </table>
          <p class="pds-muted mb-0 mt-3">推送请求带 HMAC-SHA256 签名（X-PDS-Signature）、时间戳与随机串；失败按 10 秒、30 秒、2 分钟、10 分钟重试，第 5 次仍失败标记为失败。</p>
        </template>

        <!-- 版本 -->
        <template v-else>
          <table class="pds-table">
            <thead><tr><th>版本</th><th>说明</th><th>权限数</th><th>操作人</th><th>时间</th><th></th></tr></thead>
            <tbody>
              <tr v-for="v in versions" :key="v.id">
                <td class="pds-mono text-ink">v{{ v.version }}<span v-if="v.version === current.version" class="pds-badge ok ml-2">当前</span></td>
                <td>{{ v.summary }}</td><td>{{ v.permCount }}</td><td>{{ v.createdBy || '系统' }}</td><td class="text-muted">{{ formatTime(v.createdAt) }}</td>
                <td><button v-if="canRole && v.version !== current.version" class="pds-link" @click="restore(v)">回滚到此版本</button></td>
              </tr>
              <tr v-if="!versions.length"><td colspan="6" class="empty">还没有版本记录（设置权限后生成）</td></tr>
            </tbody>
          </table>
        </template>
      </div>
    </div>

    <PdsModal v-model="bind.open" :title="`添加绑定 · ${current?.name}`" width="560" ok-text="绑定" :on-ok="submitBinding">
      <Form :label-width="80">
        <FormItem label="授权对象" required><SubjectPicker v-model:type="bind.type" v-model:id="bind.id" v-model:include-children="bind.includeChildren" /></FormItem>
        <FormItem label="有效期至"><DatePicker v-model="bind.validTo" type="datetime" placeholder="不填为长期" transfer style="width: 100%" /></FormItem>
      </Form>
      <p v-if="current?.mutexNames.length" class="pds-muted mb-0">与【{{ current.mutexNames.join('、') }}】互斥：覆盖的人员已拥有互斥角色时绑定会被拒绝。</p>
    </PdsModal>

    <PdsModal v-model="form.open" :title="form.mode === 'create' ? '新建角色' : `编辑角色 · ${current?.name}`" width="620" :ok-text="form.mode === 'create' ? '创建' : '保存'" :on-ok="submitForm">
      <div class="pds-form flex flex-col gap-4">
        <div v-if="form.mode === 'create'">
          <div class="pds-label">角色类型</div>
          <div class="pds-segment row"><button v-for="t in ['GLOBAL', 'APP', 'SCENIC']" :key="t" :class="{ on: form.type === t }" @click="form.type = t">{{ ROLE_TYPES[t] }}角色</button></div>
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div><div class="pds-label">名称</div><Input v-model="form.name" placeholder="如 景区知识管理员" /></div>
          <div><div class="pds-label">编码{{ form.mode === 'create' ? '（留空自动生成）' : '' }}</div><Input v-model="form.code" class="pds-mono" :disabled="form.mode === 'edit'" placeholder="如 PDK_ADMIN_SCENIC" /></div>
          <div v-if="form.mode === 'create' && form.type === 'APP'"><div class="pds-label">所属应用</div>
            <Select v-model="form.appCode" transfer><Option v-for="a in apps" :key="a.code" :value="a.code">{{ a.name }}</Option></Select></div>
          <div v-if="form.mode === 'create' && form.type === 'SCENIC'"><div class="pds-label">景区</div>
            <Select v-model="form.scenicId" transfer><Option v-for="s in scenics" :key="s.id" :value="s.id">{{ s.name }}</Option></Select></div>
          <div><div class="pds-label">父角色（继承其权限）</div>
            <Select v-model="form.parentRoleId" clearable filterable transfer placeholder="不继承"><Option v-for="r in parentOptions" :key="r.id" :value="r.id">{{ r.name }}</Option></Select></div>
          <div><div class="pds-label">负责人</div><UserSelect v-model="form.ownerId" placeholder="可选" /></div>
          <div class="col-span-2"><div class="pds-label">互斥角色（不能同时授予同一人）</div>
            <Select v-model="form.mutex" multiple filterable transfer placeholder="可选"><Option v-for="r in roles.filter((x) => x.id !== current?.id || form.mode === 'create')" :key="r.id" :value="r.id">{{ r.name }}</Option></Select></div>
          <div class="col-span-2"><div class="pds-label">说明</div><Input v-model="form.description" type="textarea" :rows="2" /></div>
        </div>
      </div>
    </PdsModal>

    <PdsModal v-model="copyForm.open" title="复制角色" ok-text="复制" :on-ok="submitCopy">
      <div class="pds-form flex flex-col gap-4">
        <div><div class="pds-label">新角色名称</div><Input v-model="copyForm.name" /></div>
        <div><div class="pds-label">编码（留空自动生成）</div><Input v-model="copyForm.code" class="pds-mono" /></div>
        <p class="pds-muted m-0">复制类型、父角色与全部权限；不复制绑定、互斥与外部编码。</p>
      </div>
    </PdsModal>

    <PdsModal v-model="payload.open" title="投递载荷" width="680" hide-ok>
      <pre class="pds-mono m-0 max-h-[460px] overflow-auto rounded-2xl bg-row p-4 text-[12px] leading-5">{{ payload.text }}</pre>
    </PdsModal>

    <PdsModal v-model="subOpen" title="下发方式（按应用）" width="760" ok-text="保存" :on-ok="saveSubs">
      <div class="flex flex-col gap-3">
        <div v-for="s in subItems" :key="s.appCode" class="rounded-2xl bg-row p-4">
          <div class="mb-3 text-[14px] font-semibold text-ink">{{ s.appName }} <span class="pds-mono text-[#9CA3AF]">{{ s.appCode }}</span></div>
          <div class="flex flex-wrap gap-6 text-[13px]">
            <label class="flex items-center gap-2"><Switch v-model="s.tokenClaim" size="small" /> 令牌声明 roles</label>
            <label class="flex items-center gap-2"><Switch v-model="s.snapshot" size="small" /> 快照拉取</label>
            <label class="flex items-center gap-2"><Switch v-model="s.webhook" size="small" /> Webhook 推送</label>
          </div>
          <div v-if="s.webhook" class="mt-3 grid grid-cols-[1fr_240px] gap-3">
            <Input v-model="s.webhookUrl" placeholder="推送地址 https://…" />
            <Input v-model="s.secret" type="password" :placeholder="s.secretSet ? '签名密钥已设置，留空不修改' : '签名密钥（留空自动生成）'" />
          </div>
        </div>
        <div v-if="!subItems.length" class="pds-muted py-6 text-center">该角色还没有目标应用</div>
      </div>
    </PdsModal>
  </div>
</template>
