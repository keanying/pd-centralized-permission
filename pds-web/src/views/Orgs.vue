<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconSearch, IconFilter, IconImport, IconPlus, IconMore, IconArrowUp, IconBolt } from '@kousum/semi-icons-vue'
import {
  api, flattenOrgs, ORG_TYPES, DATA_SCOPES, type OrgNode, type Position, type UserView, type Credential,
  type OrgInfo, type NodeGrant, type MembersPreview, type RoleView,
} from '@/api'
import { useSession } from '@/stores/session'
import { formatRelative } from '@/lib/utils'
import OrgTree from '@/components/pds/OrgTree.vue'
import PdsModal from '@/components/pds/PdsModal.vue'
import UserDrawer from '@/components/pds/UserDrawer.vue'
import ImportUsers from '@/components/pds/ImportUsers.vue'
import OneTimeSecret from '@/components/pds/OneTimeSecret.vue'
import UserSelect from '@/components/pds/UserSelect.vue'
import PermNodePicker from '@/components/pds/PermNodePicker.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canOrg = computed(() => session.has('pds:org:manage'))
const canUser = computed(() => session.has('pds:user:manage'))
const canGrant = computed(() => session.has('pds:grant:manage'))

// ---------------- 左侧：组织树（用户组、岗位在"人员管理"中维护）
const tree = ref<OrgNode[]>([])
const flat = computed(() => flattenOrgs(tree.value))
const positions = ref<Position[]>([])
const orgId = ref<string>((route.query.org as string) || '')
const selectedOrg = computed(() => flat.value.find((o) => o.id === orgId.value) || null)

async function loadTree() {
  const [t, p] = await Promise.all([api.orgTree(), api.positions()])
  tree.value = t
  positions.value = p
  if (!orgId.value && t.length) orgId.value = t[0].id
}

// ---------------- 右侧：人员表（筛选同步到地址栏，刷新/分享可还原）
const q = reactive({
  keyword: (route.query.q as string) || '', status: (route.query.status as string) || '',
  userType: (route.query.type as string) || '', children: route.query.children !== '0', page: Number(route.query.page) || 1,
})
const PAGE_SIZE = 10
const rows = ref<UserView[]>([])
const total = ref(0)
const loading = ref(false)
async function loadUsers() {
  loading.value = true
  try {
    const r = await api.users({
      keyword: q.keyword || undefined, orgId: orgId.value || undefined, includeChildren: q.children,
      status: q.status || undefined, page: q.page, pageSize: PAGE_SIZE,
    })
    rows.value = q.userType ? r.items.filter((u) => u.userType === q.userType) : r.items
    total.value = r.total
  } finally {
    loading.value = false
  }
}
watch([orgId, () => q.status, () => q.userType, () => q.children, () => q.page], () => {
  router.replace({ query: {
    org: orgId.value, ...(tab.value === 'grants' ? { tab: 'grants' } : {}),
    ...(q.keyword ? { q: q.keyword } : {}), ...(q.status ? { status: q.status } : {}), ...(q.userType ? { type: q.userType } : {}),
    ...(q.children ? {} : { children: '0' }), ...(q.page > 1 ? { page: String(q.page) } : {}),
  } })
  loadUsers()
})
let kwTimer: number | undefined
watch(() => q.keyword, () => {
  window.clearTimeout(kwTimer)
  kwTimer = window.setTimeout(() => { q.page = 1; loadUsers() }, 300)
})
function selectOrg(n: OrgNode) { orgId.value = n.id; q.page = 1 }
onMounted(async () => { await loadTree(); loadUsers(); loadNode() })

// ---------------- 节点详情（V2：组织即权限）
const tab = ref<'members' | 'grants'>((route.query.tab as any) === 'grants' ? 'grants' : 'members')
const info = ref<OrgInfo | null>(null)
const nodeGrants = ref<NodeGrant[]>([])
async function loadNode() {
  if (!orgId.value) return
  info.value = await api.orgInfo(orgId.value)
  loadTab()
}
async function loadTab() {
  if (!orgId.value) return
  if (tab.value === 'grants') nodeGrants.value = await api.orgGrants(orgId.value)
}
// 双击树节点改名
async function onRename(n: OrgNode, name: string) {
  if (!name.trim() || name.trim() === n.name) return
  await api.updateOrg(n.id, { name: name.trim() })
  Message.success('已改名')
  await loadTree()
  loadNode()
}
watch(orgId, () => loadNode())
watch(tab, () => { router.replace({ query: { ...route.query, tab: tab.value === 'members' ? undefined : tab.value } }); loadTab() })

function toggleInherit(v: boolean) {
  const n = info.value!
  Modal.confirm({
    title: v ? '恢复继承上级权限' : '阻断继承上级权限',
    content: v ? `【${n.name}】及其下级将重新继承上级组织"本节点及下级"的权限。` : `【${n.name}】及其下级将不再继承上级组织的节点权限与角色（本节点自身的授权不受影响），常用于外包、实习等需要隔离的部门。`,
    okText: v ? '恢复继承' : '阻断继承',
    onOk: async () => { info.value = await api.setOrgInherit(n.id, v); Message.success('已更新，相关人员权限已重新计算'); await loadTree(); loadTab() },
    onCancel: () => { info.value = { ...n } },
  })
}

// 节点授权：从权限树添加权限 / 挂载角色
const ng = reactive({ open: false, kind: 'PERM' as 'PERM' | 'ROLE', perms: [] as string[], roles: [] as string[], children: true,
  effect: 'ALLOW', dataScope: '', validTo: '' as any, reason: '' })
const allRoles = ref<RoleView[]>([])
async function openNodeGrant(kind: 'PERM' | 'ROLE') {
  Object.assign(ng, { open: true, kind, perms: [], roles: [], children: true, effect: 'ALLOW', dataScope: '', validTo: '', reason: '' })
  if (kind === 'ROLE' && !allRoles.value.length) allRoles.value = (await api.roles()).filter((r) => r.status !== 0)
}
async function submitNodeGrant() {
  if (ng.kind === 'PERM' && !ng.perms.length) { Message.warning('请选择权限节点'); return false }
  if (ng.kind === 'ROLE' && !ng.roles.length) { Message.warning('请选择角色'); return false }
  await api.addOrgGrants(orgId.value, {
    permCodes: ng.kind === 'PERM' ? ng.perms : [], roleIds: ng.kind === 'ROLE' ? ng.roles : [], includeChildren: ng.children,
    effect: ng.kind === 'ROLE' ? 'ALLOW' : ng.effect, dataScope: ng.dataScope || undefined,
    validTo: ng.validTo ? new Date(ng.validTo).toISOString() : undefined, reason: ng.reason || undefined,
  })
  Message.success('已添加，相关人员权限已重新计算')
  tab.value = 'grants'
  loadTab()
}
function revokeNodeGrant(g: NodeGrant) {
  Modal.confirm({ title: g.objectType === 'ROLE' ? '解除角色' : '撤销节点权限', content: `撤销【${info.value?.name}】上的【${g.name}】？本节点${g.includeChildren ? '及下级' : ''}人员立即失去经此获得的权限。`,
    okText: '撤销', onOk: async () => { await api.revokeAssignments([g.id]); Message.success('已撤销'); loadTab() } })
}

// 添加人员（主属 / 兼职 + 岗位），保存前预览权限变化
const add = reactive({ open: false, users: [] as string[], primary: true, positionId: '' })
const addPreview = ref<MembersPreview | null>(null)
const previewing = ref(false)
let pvTimer: number | undefined
watch(() => [add.open, add.users.join(','), add.primary, add.positionId], () => {
  window.clearTimeout(pvTimer)
  if (!add.open || !add.users.length) { addPreview.value = null; return }
  pvTimer = window.setTimeout(async () => {
    previewing.value = true
    try { addPreview.value = await api.previewMembers(orgId.value, { userIds: add.users, primary: add.primary, positionId: add.positionId || undefined }) }
    finally { previewing.value = false }
  }, 300)
})
const adding = ref(false)
async function submitAdd() {
  if (!add.users.length) { Message.warning('请选择人员'); return }
  adding.value = true
  try {
    await api.addMembers(orgId.value, { userIds: add.users, primary: add.primary, positionId: add.positionId || undefined })
    Message.success(`已添加 ${add.users.length} 人`)
    add.open = false
    await loadTree()
    loadUsers()
    loadNode()
  } finally {
    adding.value = false
  }
}

// 拖拽移动组织
function onMove(n: OrgNode, target: OrgNode) {
  Modal.confirm({ title: '移动组织', content: `把【${n.name}】移到【${target.name}】下？整棵子树的所属景区与继承的权限会随之变化。`, okText: '移动',
    onOk: async () => { await api.updateOrg(n.id, { parentId: target.id }); Message.success('已移动'); await loadTree(); loadNode() } })
}

const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const pageList = computed(() => {
  const p = q.page, n = pages.value
  const start = Math.max(1, Math.min(p - 2, n - 4))
  return Array.from({ length: Math.min(5, n) }, (_, i) => start + i)
})

const USER_TYPES: Record<string, string> = { FULL_TIME: '正式员工', OUTSOURCE: '劳务派遣', INTERN: '实习生', TEMP: '临时工' }
const STATUS: Record<string, { label: string; cls: string }> = {
  ACTIVE: { label: '在职', cls: 'ok' }, DISABLED: { label: '停用', cls: 'off' }, LEFT: { label: '离职', cls: 'bad' },
}
const filterCount = computed(() => [q.status, q.userType, q.children ? '' : '1'].filter(Boolean).length)

// ---------------- 人员操作
const drawer = reactive({ open: false, user: null as UserView | null })
function toggleStatus(u: UserView) {
  const enable = u.status !== 'ACTIVE'
  Modal.confirm({
    title: enable ? '启用账号' : '停用账号',
    content: enable ? `恢复【${u.name}】的登录。` : `停用后【${u.name}】已登录的会话与令牌立即失效，无法再登录任何应用。`,
    okText: enable ? '启用' : '停用',
    onOk: async () => {
      await (enable ? api.enableUser(u.id) : api.disableUser(u.id, '管理员停用'))
      Message.success(enable ? '已启用' : '已停用')
      loadUsers()
    },
  })
}

const create = reactive({ open: false, username: '', empNo: '', name: '', mobile: '', email: '', userType: 'FULL_TIME', orgId: '', positionId: '' })
function openCreate() {
  Object.assign(create, { open: true, username: '', empNo: '', name: '', mobile: '', email: '', userType: 'FULL_TIME', orgId: orgId.value, positionId: '' })
}
const secret = reactive({ open: false, title: '', value: '' })
async function submitCreate() {
  if (!/^[A-Za-z0-9_.@-]{3,64}$/.test(create.username)) { Message.warning('账号只能包含字母、数字和 _.@-，长度 3-64'); return false }
  if (!create.name.trim()) { Message.warning('请填写姓名'); return false }
  if (!create.orgId) { Message.warning('请选择所属组织'); return false }
  const c: Credential = await api.createUser({
    username: create.username, empNo: create.empNo || undefined, name: create.name, mobile: create.mobile || undefined,
    email: create.email || undefined, userType: create.userType, orgId: create.orgId, positionId: create.positionId || undefined,
  })
  if (c.initialPassword) Object.assign(secret, { open: true, title: `已创建人员：${create.name}`, value: c.initialPassword })
  await loadTree()
  loadUsers()
}
const importOpen = ref(false)

// ---------------- 组织操作
const orgForm = reactive({ open: false, mode: 'create' as 'create' | 'edit', node: null as OrgNode | null, type: 'DEPT', code: '', name: '', sort: 0, parentId: '', leaderId: '' })
function onOrgAction(name: string, n: OrgNode) {
  if (name === 'inherit') {
    if (orgId.value !== n.id) { orgId.value = n.id }
    api.orgInfo(n.id).then((i) => { info.value = i; toggleInherit(!i.inheritFromParent) })
    return
  }
  if (name === 'create') Object.assign(orgForm, { open: true, mode: 'create', node: n, type: n.type === 'GROUP' ? 'SCENIC' : 'DEPT', code: '', name: '', sort: 0 })
  else if (name === 'edit') Object.assign(orgForm, { open: true, mode: 'edit', node: n, type: n.type, code: n.code, name: n.name, sort: n.sort, parentId: n.parentId || '',
    leaderId: info.value?.id === n.id ? info.value.leaderId || '' : '' })
  else if (name === 'delete') {
    Modal.confirm({
      title: '删除组织', content: `确定删除【${n.name}】？组织下不能有下级组织和人员。`, okText: '删除',
      onOk: async () => { await api.deleteOrg(n.id); Message.success('已删除'); if (orgId.value === n.id) orgId.value = ''; await loadTree(); loadUsers() },
    })
  }
}
async function submitOrg() {
  if (!orgForm.name.trim() || (orgForm.mode === 'create' && !orgForm.code.trim())) { Message.warning('请填写编码与名称'); return false }
  if (orgForm.mode === 'create') {
    await api.createOrg({ parentId: orgForm.node!.id, type: orgForm.type, code: orgForm.code.trim(), name: orgForm.name.trim(), sort: orgForm.sort })
  } else {
    await api.updateOrg(orgForm.node!.id, { name: orgForm.name.trim(), code: orgForm.code.trim(), sort: orgForm.sort,
      leaderId: orgForm.leaderId || undefined,
      parentId: orgForm.parentId && orgForm.parentId !== orgForm.node!.parentId ? orgForm.parentId : undefined })
  }
  Message.success('已保存')
  await loadTree()
  loadNode()
}
// 可移动到的上级：排除自身及其所有下级
const moveTargets = computed(() => {
  const n = orgForm.node
  if (!n) return []
  const banned = new Set<string>()
  const walk = (x: OrgNode) => { banned.add(x.id); x.children.forEach(walk) }
  walk(n)
  return flat.value.filter((o) => !banned.has(o.id))
})

</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-[340px_1fr]">
    <!-- 左：组织树 / 用户组 -->
    <div class="pds-card self-start">
      <div class="pds-card-title">
        <span>组织树</span>
        <button v-if="canOrg && tree.length" class="pds-btn dark sm" @click="onOrgAction('create', selectedOrg || tree[0])"><IconPlus size="small" /> 新增组织</button>
      </div>
      <OrgTree :nodes="tree" :selected-id="orgId" :can-manage="canOrg" @select="selectOrg" @action="onOrgAction" @move="onMove" @rename="onRename" />
      <p v-if="canOrg" class="pds-muted mb-0 mt-3">双击组织名称可直接改名；拖动组织可调整上级。</p>
    </div>

    <!-- 右：节点详情 / 用户组成员 -->
    <div class="flex min-w-0 flex-col gap-5">
      <div class="pds-card min-w-0">
        <template v-if="true">
          <!-- 节点标题：名称、编码可直接修改（含根组织） -->
          <div v-if="info" class="mb-5 flex flex-wrap items-center gap-x-5 gap-y-2">
            <span class="text-[22px] font-bold text-ink">{{ info.name }}</span>
            <span class="pds-badge gray">{{ ORG_TYPES[info.type] || info.type }}</span>
            <span class="text-[13px] text-[#6B7280]">编码 {{ info.code }}</span>
            <span class="text-[13px] text-[#6B7280]">负责人 {{ info.leaderName || '未指定' }}</span>
            <span class="text-[13px] text-[#6B7280]">成员 {{ info.memberCount.toLocaleString('en-US') }} 人</span>
            <label v-if="!info.root" class="flex items-center gap-2 text-[13px]" :class="info.inheritFromParent ? 'text-accent-text' : 'text-warn'">
              <Switch :model-value="info.inheritFromParent" size="small" :disabled="!canGrant" @on-change="toggleInherit" />
              继承上级权限：{{ info.inheritFromParent ? '开' : '关（已阻断）' }}
            </label>
            <span class="flex-1" />
            <button v-if="canUser" class="pds-btn dark sm" @click="Object.assign(add, { open: true, users: [], primary: true, positionId: '' })"><IconPlus size="small" /> 添加人员</button>
            <Dropdown v-if="canOrg && selectedOrg" trigger="click" transfer placement="bottom-end" @on-click="(n: string) => onOrgAction(n, selectedOrg!)">
              <button class="pds-icon-btn" style="width: 32px; height: 32px"><IconMore size="small" /></button>
              <template #list><DropdownMenu>
                <DropdownItem name="edit">编辑编码 / 负责人 / 上级</DropdownItem>
                <DropdownItem name="create">新增下级组织</DropdownItem>
                <DropdownItem v-if="!info.root" name="delete" divided>删除</DropdownItem>
              </DropdownMenu></template>
            </Dropdown>
          </div>
          <div class="pds-tabs">
            <button :class="{ on: tab === 'members' }" @click="tab = 'members'">成员</button>
            <button :class="{ on: tab === 'grants' }" @click="tab = 'grants'">节点权限</button>
          </div>
        </template>

        <!-- 成员 -->
        <template v-if="tab === 'members'">
          <div class="mb-5 flex flex-wrap items-center gap-3">
            <template v-if="true">
              <div class="pds-search w-[256px]"><IconSearch /><input v-model="q.keyword" placeholder="搜索工号 / 姓名" /></div>
              <Poptip placement="bottom-start" transfer width="260">
                <button class="pds-pill"><IconFilter size="small" /> 筛选<span v-if="filterCount" class="pds-badge dark ml-1" style="height: 18px; padding: 0 6px">{{ filterCount }}</span></button>
                <template #content>
                  <div class="flex flex-col gap-3 py-1">
                    <div><div class="pds-muted mb-1">状态</div>
                      <RadioGroup v-model="q.status" type="button" size="small"><Radio label="">全部</Radio><Radio v-for="(s, k) in STATUS" :key="k" :label="k">{{ s.label }}</Radio></RadioGroup></div>
                    <div><div class="pds-muted mb-1">人员类型</div>
                      <Select v-model="q.userType" clearable size="small" placeholder="全部" transfer><Option v-for="(l, k) in USER_TYPES" :key="k" :value="k">{{ l }}</Option></Select></div>
                    <div class="flex items-center justify-between"><span class="text-[13px]">包含下级组织</span><Switch v-model="q.children" size="small" /></div>
                  </div>
                </template>
              </Poptip>
              <span class="flex-1" />
              <button v-if="canUser" class="pds-btn ghost" @click="importOpen = true"><IconImport size="small" /> 批量导入</button>
              <button v-if="canUser" class="pds-btn ghost" @click="openCreate"><IconPlus size="small" /> 新建人员</button>
            </template>
          </div>
          <table class="pds-table">
          <thead><tr><th>工号</th><th>姓名</th><th>主部门</th><th>岗位</th><th>人员类型</th><th>状态</th><th>最近登录</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="u in rows" :key="u.id">
              <td class="text-[#6B7280]">{{ u.empNo || '—' }}</td>
              <td class="text-ink">{{ u.name }}</td>
              <td>{{ u.primaryOrgName || '—' }}</td>
              <td><span class="flex flex-wrap gap-1">
                <span v-for="(p, i) in u.positions" :key="p" class="pds-badge" :class="i === 0 ? 'ok' : 'gray'">{{ p }}</span>
                <span v-if="!u.positions.length" class="text-[#9CA3AF]">—</span>
              </span></td>
              <td>{{ USER_TYPES[u.userType] || u.userType }}</td>
              <td><span class="pds-dot" :class="STATUS[u.status]?.cls">{{ STATUS[u.status]?.label }}</span><span v-if="u.locked" class="pds-badge warn ml-1">锁定</span></td>
              <td class="text-[#6B7280]">{{ formatRelative(u.lastLoginAt) }}</td>
              <td class="whitespace-nowrap">
                <span class="flex gap-3">
                  <button class="pds-link gray" @click="Object.assign(drawer, { open: true, user: u })">{{ canUser ? '编辑' : '查看' }}</button>
                  <button class="pds-link" @click="router.push({ path: '/user-grants', query: { userId: u.id } })">权限</button>
                  <button v-if="canUser" class="pds-link" :class="u.status === 'ACTIVE' ? 'red' : ''" @click="toggleStatus(u)">{{ u.status === 'ACTIVE' ? '停用' : '启用' }}</button>
                </span>
              </td>
            </tr>
            <tr v-if="!loading && !rows.length"><td colspan="8" class="empty">{{ q.keyword ? `没有找到"${q.keyword}"` : '该组织下暂无人员' }}</td></tr>
          </tbody>
        </table>


        <div class="pds-pager">
          <span class="pds-muted">共 {{ total.toLocaleString('en-US') }} 人<template v-if="selectedOrg"> · {{ selectedOrg.name }}{{ q.children ? '（含下级）' : '' }}</template></span>
          <div class="pages">
            <button :disabled="q.page <= 1" @click="q.page--">‹</button>
            <button v-for="p in pageList" :key="p" :class="{ active: p === q.page }" @click="q.page = p">{{ p }}</button>
            <button :disabled="q.page >= pages" @click="q.page++">›</button>
          </div>
        </div>
        </template>

        <!-- 节点权限 -->
        <template v-else>
          <div v-if="canGrant" class="mb-4 flex gap-2">
            <button class="pds-btn accent" @click="openNodeGrant('PERM')"><IconPlus size="small" /> 从权限树添加权限</button>
            <button class="pds-btn ghost" @click="openNodeGrant('ROLE')">挂载角色</button>
          </div>
          <table class="pds-table">
            <thead><tr><th>权限/角色</th><th>所属应用</th><th>来源</th><th>继承方式</th><th>效果</th><th>数据范围</th><th>有效期</th><th></th></tr></thead>
            <tbody>
              <tr v-for="g in nodeGrants" :key="g.id" :class="{ 'opacity-60': g.inherited }">
                <td :class="g.inherited ? 'italic text-[#6B7280]' : 'font-semibold text-ink'">
                  <span class="flex items-center gap-1.5"><IconArrowUp v-if="g.inherited" size="small" />{{ g.objectType === 'ROLE' ? '角色：' : '' }}{{ g.name }}
                    <span v-if="g.risk === 'HIGH'" class="pds-badge bad not-italic" style="height: 18px">高风险</span></span>
                  <span v-if="g.objectType !== 'ROLE'" class="pds-mono block font-normal not-italic text-[#9CA3AF]">{{ g.code }}</span>
                </td>
                <td :class="{ italic: g.inherited }">{{ g.appName || '—' }}</td>
                <td :class="{ italic: g.inherited }">{{ g.inherited ? `继承自"${g.fromOrgName}"` : '本节点' }}</td>
                <td><span class="pds-badge gray">{{ g.includeChildren ? '本节点及下级' : '仅本节点' }}</span></td>
                <td><span class="pds-badge" :class="g.effect === 'ALLOW' ? 'ok' : 'bad'">{{ g.effect === 'ALLOW' ? '允许' : '禁止' }}</span></td>
                <td>{{ g.dataScope ? DATA_SCOPES[g.dataScope] : '—' }}</td>
                <td class="whitespace-nowrap">{{ g.validTo ? new Date(g.validTo).toLocaleDateString('sv-SE') : '长期' }}</td>
                <td class="whitespace-nowrap">
                  <button v-if="g.inherited" class="pds-link gray" @click="orgId = g.fromOrgId">查看来源</button>
                  <button v-else-if="canGrant" class="pds-link red" @click="revokeNodeGrant(g)">撤销</button>
                </td>
              </tr>
              <tr v-if="!nodeGrants.length"><td colspan="8" class="empty">本节点没有直接授权，也没有从上级继承的权限</td></tr>
            </tbody>
          </table>
          <p class="pds-muted mb-0 mt-3">"本节点及下级"的授权向下继承；阻断继承的节点不再继承上级。人员的最终权限 = 组织（含继承）∪ 岗位 ∪ 用户组 ∪ 角色 ∪ 个人授权，禁止优先。</p>
        </template>

      </div>
    </div>

    <UserDrawer v-model="drawer.open" :user="drawer.user" :orgs="flat" :positions="positions" :can-manage="canUser" @changed="loadUsers" />
    <ImportUsers v-model="importOpen" @done="async () => { await loadTree(); loadUsers() }" />
    <OneTimeSecret v-model="secret.open" :title="secret.title" label="初始密码" :value="secret.value" />

    <PdsModal v-model="create.open" title="新增人员" width="600" ok-text="创建" :on-ok="submitCreate">
      <Form :label-width="80">
        <div class="grid grid-cols-2 gap-x-4">
          <FormItem label="账号" required><Input v-model="create.username" placeholder="登录账号" /></FormItem>
          <FormItem label="工号"><Input v-model="create.empNo" /></FormItem>
          <FormItem label="姓名" required><Input v-model="create.name" /></FormItem>
          <FormItem label="人员类型"><Select v-model="create.userType" transfer><Option v-for="(l, k) in USER_TYPES" :key="k" :value="k">{{ l }}</Option></Select></FormItem>
          <FormItem label="手机"><Input v-model="create.mobile" /></FormItem>
          <FormItem label="邮箱"><Input v-model="create.email" /></FormItem>
        </div>
        <FormItem label="所属组织" required>
          <Select v-model="create.orgId" filterable transfer>
            <Option v-for="o in flat" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
          </Select>
        </FormItem>
        <FormItem label="岗位"><Select v-model="create.positionId" filterable clearable transfer><Option v-for="p in positions" :key="p.id" :value="p.id">{{ p.name }}</Option></Select></FormItem>
      </Form>
      <p class="pds-muted m-0">系统生成符合密码策略的初始密码，创建后只显示一次；首次登录须修改。</p>
    </PdsModal>

    <PdsModal v-model="orgForm.open" :title="orgForm.mode === 'create' ? `在【${orgForm.node?.name}】下新增组织` : `编辑组织 · ${orgForm.node?.name}`" :on-ok="submitOrg">
      <p v-if="orgForm.mode === 'edit' && orgForm.node?.type === 'GROUP'" class="pds-muted mt-0">根组织的名称、编码可按企业实际修改（如"浙江景旅集团"）。</p>
      <Form :label-width="80">
        <FormItem v-if="orgForm.mode === 'create'" label="类型">
          <RadioGroup v-model="orgForm.type" type="button"><Radio v-for="t in ['COMPANY', 'SCENIC', 'DEPT', 'TEAM']" :key="t" :label="t">{{ ORG_TYPES[t] }}</Radio></RadioGroup>
        </FormItem>
        <FormItem label="编码" required><Input v-model="orgForm.code" placeholder="全局唯一，批量导入时使用，如 XH_TICKET" /></FormItem>
        <FormItem label="名称" required><Input v-model="orgForm.name" /></FormItem>
        <FormItem v-if="orgForm.mode === 'edit'" label="负责人"><UserSelect v-model="orgForm.leaderId" placeholder="可选" /></FormItem>
        <FormItem label="排序"><InputNumber v-model="orgForm.sort" :min="0" /></FormItem>
        <FormItem v-if="orgForm.mode === 'edit' && orgForm.node?.type !== 'GROUP'" label="上级组织">
          <Select v-model="orgForm.parentId" filterable transfer>
            <Option v-for="o in moveTargets" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
          </Select>
          <div class="pds-muted mt-1">移动后，整棵子树的所属景区与继承的权限会随之变化。</div>
        </FormItem>
      </Form>
    </PdsModal>




    <!-- 节点授权 -->
    <PdsModal v-model="ng.open" :title="ng.kind === 'PERM' ? `从权限树添加权限 · ${info?.name}` : `挂载角色 · ${info?.name}`" width="640" ok-text="添加" :on-ok="submitNodeGrant">
      <div class="pds-form flex flex-col gap-4">
        <div v-if="ng.kind === 'PERM'"><div class="pds-label">权限节点（授予上级节点即包含其非高风险下级）</div><PermNodePicker v-model="ng.perms" /></div>
        <div v-else><div class="pds-label">角色</div>
          <Select v-model="ng.roles" multiple filterable transfer><Option v-for="r in allRoles" :key="r.id" :value="r.id" :label="r.name">{{ r.name }} <span class="pds-mono text-[#9CA3AF]">{{ r.code }}</span></Option></Select></div>
        <div class="grid grid-cols-2 gap-4">
          <div><div class="pds-label">继承方式</div>
            <div class="pds-segment row"><button :class="{ on: ng.children }" @click="ng.children = true">本节点及下级</button><button :class="{ on: !ng.children }" @click="ng.children = false">仅本节点</button></div></div>
          <div v-if="ng.kind === 'PERM'"><div class="pds-label">效果</div>
            <div class="pds-segment row"><button :class="{ on: ng.effect === 'ALLOW' }" @click="ng.effect = 'ALLOW'">允许</button><button :class="{ on: ng.effect === 'DENY' }" @click="ng.effect = 'DENY'">禁止</button></div></div>
          <div v-if="ng.kind === 'PERM'"><div class="pds-label">数据范围</div>
            <Select v-model="ng.dataScope" clearable transfer placeholder="默认：本人景区"><Option v-for="(l, k) in DATA_SCOPES" v-show="!['SCENIC_LIST', 'CUSTOM', 'DOMAIN'].includes(k as string)" :key="k" :value="k">{{ l }}</Option></Select></div>
          <div><div class="pds-label">有效期</div><DatePicker v-model="ng.validTo" type="date" placeholder="不填为长期" transfer style="width: 100%" /></div>
        </div>
        <div><div class="pds-label">原因</div><Input v-model="ng.reason" placeholder="记入审计，如：票务部日常工作需要" /></div>
      </div>
    </PdsModal>

    <!-- 添加人员（右侧抽屉，保存前预览权限变化） -->
    <Drawer v-model="add.open" :title="`添加人员到 ${info?.name || ''}`" width="480" :mask-closable="false" class-name="pds-drawer">
      <div class="pds-form flex flex-col gap-5">
        <div><div class="pds-label">人员（多选）</div><UserSelect v-model="add.users" multiple placeholder="输入姓名、账号或工号搜索" /></div>
        <div><div class="pds-label">归属类型</div>
          <div class="pds-seg dark-off"><button :class="{ on: add.primary }" @click="add.primary = true">主属</button><button :class="{ on: !add.primary }" @click="add.primary = false">兼职</button></div>
          <div class="pds-muted mt-1">{{ add.primary ? '主属：人员的主部门调整为本节点（原主部门的任职被替换，可能失去原部门的权限）。' : '兼职：保留原任职，新增本节点任职，默认继承本节点权限。' }}</div></div>
        <div><div class="pds-label">岗位</div>
          <Select v-model="add.positionId" clearable filterable transfer size="large" placeholder="可选"><Option v-for="p in positions" :key="p.id" :value="p.id">{{ p.name }}</Option></Select></div>

        <div class="rounded-[20px] border-2 border-solid border-accent bg-[#F8FBF2] p-5">
          <div class="mb-4 flex items-center gap-2 text-[15px] font-semibold text-ink"><IconBolt class="text-accent-text" /> 权限变化预览 <span v-if="previewing" class="pds-muted font-normal">计算中…</span></div>
          <template v-if="addPreview">
            <div class="flex gap-8">
              <div><div class="text-[30px] font-bold text-accent-text">+{{ addPreview.added }}</div><div class="pds-muted">项新增权限</div></div>
              <div><div class="text-[30px] font-bold text-danger">{{ addPreview.highRiskCount }}</div><div class="pds-muted">项高风险</div></div>
              <div><div class="text-[30px] font-bold text-[#9CA3AF]">{{ addPreview.lost }}</div><div class="pds-muted">项将失去</div></div>
            </div>
            <div v-if="addPreview.highRisk.length" class="mt-4 flex flex-col gap-1.5">
              <div v-for="h in addPreview.highRisk" :key="h" class="flex items-center gap-2 text-[14px] text-ink"><span class="size-1.5 rounded-full bg-danger" />{{ h }}</div>
            </div>
            <div v-if="addPreview.lostNames.length" class="mt-3 text-[12px] text-[#6B7280]">将失去：{{ addPreview.lostNames.slice(0, 8).join('、') }}<template v-if="addPreview.lostNames.length > 8"> 等</template></div>
            <div v-if="addPreview.users.length > 1" class="mt-4 border-0 border-t border-solid border-[#E3EBDD] pt-3">
              <div v-for="u in addPreview.users" :key="u.userId" class="flex justify-between py-1 text-[12px]">
                <span class="text-ink">{{ u.name }} <span class="text-[#9CA3AF]">{{ u.empNo }}</span><span v-if="u.alreadyMember" class="pds-badge gray ml-1" style="height: 16px; font-size: 10px">已在本节点</span></span>
                <span class="text-[#6B7280]">+{{ u.added }} / −{{ u.lost }}<span v-if="u.highRisk.length" class="ml-1 text-danger">高风险 {{ u.highRisk.length }}</span></span>
              </div>
            </div>
          </template>
          <div v-else class="pds-muted">选择人员后自动计算</div>
        </div>
      </div>
      <div class="absolute inset-x-6 bottom-6 flex gap-3">
        <button class="pds-btn ghost flex-1 justify-center" style="height: 46px; border-radius: 23px" @click="add.open = false">取消</button>
        <button class="pds-btn dark flex-1 justify-center" style="height: 46px; border-radius: 23px" :disabled="!add.users.length || adding" @click="submitAdd">{{ adding ? '处理中…' : '确认添加' }}</button>
      </div>
    </Drawer>
  </div>
</template>
