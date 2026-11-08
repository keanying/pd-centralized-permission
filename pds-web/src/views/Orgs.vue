<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconSearch, IconFilter, IconImport, IconPlus, IconMore, IconUserGroup } from '@kousum/semi-icons-vue'
import { api, flattenOrgs, ORG_TYPES, type GroupView, type OrgNode, type Position, type UserView, type Credential } from '@/api'
import { useSession } from '@/stores/session'
import { formatRelative } from '@/lib/utils'
import OrgTree from '@/components/pds/OrgTree.vue'
import PdsModal from '@/components/pds/PdsModal.vue'
import UserDrawer from '@/components/pds/UserDrawer.vue'
import ImportUsers from '@/components/pds/ImportUsers.vue'
import OneTimeSecret from '@/components/pds/OneTimeSecret.vue'
import UserSelect from '@/components/pds/UserSelect.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canOrg = computed(() => session.has('pds:org:manage'))
const canUser = computed(() => session.has('pds:user:manage'))
const canGroup = computed(() => session.has('pds:group:manage'))

// ---------------- 左侧：组织 / 用户组
const mode = ref<'org' | 'group'>((route.query.mode as any) === 'group' ? 'group' : 'org')
const tree = ref<OrgNode[]>([])
const flat = computed(() => flattenOrgs(tree.value))
const positions = ref<Position[]>([])
const groups = ref<GroupView[]>([])
const orgId = ref<string>((route.query.org as string) || '')
const groupId = ref<string>((route.query.group as string) || '')
const selectedOrg = computed(() => flat.value.find((o) => o.id === orgId.value) || null)
const selectedGroup = computed(() => groups.value.find((g) => g.id === groupId.value) || null)

async function loadTree() {
  const [t, p, g] = await Promise.all([api.orgTree(), api.positions(), api.groups()])
  tree.value = t
  positions.value = p
  groups.value = g
  if (!orgId.value && t.length) orgId.value = t[0].id
  if (!groupId.value && g.length) groupId.value = g[0].id
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
const members = ref<{ id: string; name: string; username: string }[]>([])

async function loadUsers() {
  loading.value = true
  try {
    if (mode.value === 'group') {
      members.value = groupId.value ? await api.groupMembers(groupId.value) : []
      return
    }
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
watch([mode, orgId, groupId, () => q.status, () => q.userType, () => q.children, () => q.page], () => {
  router.replace({ query: {
    ...(mode.value === 'group' ? { mode: 'group', group: groupId.value } : { org: orgId.value }),
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
onMounted(async () => { await loadTree(); loadUsers() })

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
  Object.assign(create, { open: true, username: '', empNo: '', name: '', mobile: '', email: '', userType: 'FULL_TIME', orgId: mode.value === 'org' ? orgId.value : '', positionId: '' })
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
const orgForm = reactive({ open: false, mode: 'create' as 'create' | 'edit', node: null as OrgNode | null, type: 'DEPT', code: '', name: '', sort: 0, parentId: '' })
function onOrgAction(name: string, n: OrgNode) {
  if (name === 'create') Object.assign(orgForm, { open: true, mode: 'create', node: n, type: n.type === 'GROUP' ? 'SCENIC' : 'DEPT', code: '', name: '', sort: 0 })
  else if (name === 'edit') Object.assign(orgForm, { open: true, mode: 'edit', node: n, type: n.type, code: n.code, name: n.name, sort: n.sort, parentId: n.parentId || '' })
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
    await api.updateOrg(orgForm.node!.id, { name: orgForm.name.trim(), sort: orgForm.sort,
      parentId: orgForm.parentId && orgForm.parentId !== orgForm.node!.parentId ? orgForm.parentId : undefined })
  }
  Message.success('已保存')
  await loadTree()
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

// 岗位库
const posForm = reactive({ open: false, code: '', name: '', scenicId: '' })
const scenics = computed(() => flat.value.filter((o) => o.type === 'SCENIC'))
async function submitPosition() {
  if (!posForm.code.trim() || !posForm.name.trim()) { Message.warning('请填写岗位编码与名称'); return false }
  await api.createPosition({ code: posForm.code.trim(), name: posForm.name.trim(), scenicId: posForm.scenicId || undefined })
  Message.success('已新增岗位')
  posForm.code = posForm.name = ''
  positions.value = await api.positions()
  return false
}

// 用户组
const groupForm = reactive({ open: false, code: '', name: '', description: '' })
async function submitGroup() {
  if (!groupForm.code.trim() || !groupForm.name.trim()) { Message.warning('请填写编码与名称'); return false }
  const g: any = await api.createGroup({ code: groupForm.code.trim(), name: groupForm.name.trim(), description: groupForm.description || undefined })
  groups.value = await api.groups()
  groupId.value = g.id
}
const addMember = reactive({ open: false, ids: [] as string[] })
async function submitMembers() {
  const ids = [...new Set([...members.value.map((m) => m.id), ...addMember.ids])]
  await api.setGroupMembers(groupId.value, ids)
  Message.success('已添加成员')
  addMember.ids = []
  groups.value = await api.groups()
  loadUsers()
}
function removeMember(m: { id: string; name: string }) {
  Modal.confirm({
    title: '移出用户组', content: `将【${m.name}】移出【${selectedGroup.value?.name}】？其经由该用户组获得的权限将立即失效。`, okText: '移出',
    onOk: async () => {
      await api.setGroupMembers(groupId.value, members.value.filter((x) => x.id !== m.id).map((x) => x.id))
      Message.success('已移出')
      groups.value = await api.groups()
      loadUsers()
    },
  })
}
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-[312px_1fr]">
    <!-- 左：组织树 / 用户组 -->
    <div class="pds-card self-start">
      <div class="pds-card-title">
        <span>{{ mode === 'org' ? '组织树' : '用户组' }}</span>
        <span class="flex items-center gap-1">
          <button class="pds-link gray" @click="mode = mode === 'org' ? 'group' : 'org'">{{ mode === 'org' ? '用户组' : '组织树' }}</button>
          <Dropdown v-if="mode === 'org' && (canOrg || canGroup)" trigger="click" transfer placement="bottom-end"
                    @on-click="(n: string) => (n === 'pos' ? (posForm.open = true) : null)">
            <span class="flex size-7 cursor-pointer items-center justify-center rounded-full hover:bg-row"><IconMore size="small" /></span>
            <template #list><DropdownMenu><DropdownItem name="pos">岗位库</DropdownItem></DropdownMenu></template>
          </Dropdown>
          <button v-if="mode === 'group' && canGroup" class="pds-link" @click="Object.assign(groupForm, { open: true, code: '', name: '', description: '' })">新建</button>
        </span>
      </div>
      <OrgTree v-if="mode === 'org'" :nodes="tree" :selected-id="orgId" :can-manage="canOrg" @select="selectOrg" @action="onOrgAction" />
      <div v-else class="flex flex-col gap-1">
        <div v-for="g in groups" :key="g.id" class="flex h-10 cursor-pointer items-center gap-2 rounded-xl px-3 text-[14px] transition-colors"
             :class="g.id === groupId ? 'bg-accent font-medium text-ink' : 'hover:bg-row'" @click="groupId = g.id">
          <IconUserGroup size="small" :class="g.id === groupId ? 'text-ink' : 'text-[#9CA3AF]'" />
          <span class="flex-1 truncate">{{ g.name }}</span>
          <span class="text-[12px]" :class="g.id === groupId ? 'text-ink' : 'text-[#9CA3AF]'">{{ g.memberCount }}</span>
        </div>
        <p v-if="!groups.length" class="pds-muted py-6 text-center">暂无用户组</p>
        <p class="pds-muted mb-0 mt-3">用户组把跨组织的人员归为一类（如"应急小组"），可整体授予角色或资源。</p>
      </div>
    </div>

    <!-- 右：人员 -->
    <div class="pds-card min-w-0">
      <div class="mb-5 flex flex-wrap items-center gap-3">
        <template v-if="mode === 'org'">
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
          <button v-if="canUser" class="pds-btn dark" @click="openCreate"><IconPlus size="small" /> 新增人员</button>
        </template>
        <template v-else>
          <div class="text-[15px] font-semibold text-ink">{{ selectedGroup?.name || '—' }}</div>
          <span class="pds-muted">{{ selectedGroup?.description }}</span>
          <span class="flex-1" />
          <button v-if="canGroup && selectedGroup" class="pds-btn dark" @click="addMember.open = true"><IconPlus size="small" /> 添加成员</button>
        </template>
      </div>

      <table v-if="mode === 'org'" class="pds-table">
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

      <table v-else class="pds-table">
        <thead><tr><th>姓名</th><th>账号</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="m in members" :key="m.id">
            <td class="text-ink">{{ m.name }}</td><td class="text-[#6B7280]">{{ m.username }}</td>
            <td><span class="flex gap-3">
              <button class="pds-link" @click="router.push({ path: '/user-grants', query: { userId: m.id } })">权限</button>
              <button v-if="canGroup" class="pds-link red" @click="removeMember(m)">移出</button>
            </span></td>
          </tr>
          <tr v-if="!loading && !members.length"><td colspan="3" class="empty">该用户组暂无成员</td></tr>
        </tbody>
      </table>

      <div v-if="mode === 'org'" class="pds-pager">
        <span class="pds-muted">共 {{ total.toLocaleString('en-US') }} 人<template v-if="selectedOrg"> · {{ selectedOrg.name }}{{ q.children ? '（含下级）' : '' }}</template></span>
        <div class="pages">
          <button :disabled="q.page <= 1" @click="q.page--">‹</button>
          <button v-for="p in pageList" :key="p" :class="{ active: p === q.page }" @click="q.page = p">{{ p }}</button>
          <button :disabled="q.page >= pages" @click="q.page++">›</button>
        </div>
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
      <Form :label-width="80">
        <FormItem v-if="orgForm.mode === 'create'" label="类型">
          <RadioGroup v-model="orgForm.type" type="button"><Radio v-for="t in ['COMPANY', 'SCENIC', 'DEPT', 'TEAM']" :key="t" :label="t">{{ ORG_TYPES[t] }}</Radio></RadioGroup>
        </FormItem>
        <FormItem label="编码" required><Input v-model="orgForm.code" :disabled="orgForm.mode === 'edit'" placeholder="全局唯一，批量导入时使用，如 XH_TICKET" /></FormItem>
        <FormItem label="名称" required><Input v-model="orgForm.name" /></FormItem>
        <FormItem label="排序"><InputNumber v-model="orgForm.sort" :min="0" /></FormItem>
        <FormItem v-if="orgForm.mode === 'edit' && orgForm.node?.type !== 'GROUP'" label="上级组织">
          <Select v-model="orgForm.parentId" filterable transfer>
            <Option v-for="o in moveTargets" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
          </Select>
          <div class="pds-muted mt-1">移动后，整棵子树的所属景区与继承的权限会随之变化。</div>
        </FormItem>
      </Form>
    </PdsModal>

    <PdsModal v-model="posForm.open" title="岗位库" width="560" ok-text="新增岗位" :on-ok="submitPosition">
      <div class="mb-4 flex flex-wrap gap-2">
        <span v-for="p in positions" :key="p.id" class="pds-badge gray">{{ p.name }} · {{ p.code }}</span>
        <span v-if="!positions.length" class="pds-muted">暂无岗位</span>
      </div>
      <Form v-if="canOrg" :label-width="70">
        <div class="grid grid-cols-2 gap-x-4">
          <FormItem label="编码"><Input v-model="posForm.code" placeholder="如 SELLER" /></FormItem>
          <FormItem label="名称"><Input v-model="posForm.name" placeholder="如 售票员" /></FormItem>
        </div>
        <FormItem label="所属景区">
          <Select v-model="posForm.scenicId" clearable placeholder="不选为集团通用岗位" transfer><Option v-for="s in scenics" :key="s.id" :value="s.id">{{ s.name }}</Option></Select>
        </FormItem>
      </Form>
    </PdsModal>

    <PdsModal v-model="groupForm.open" title="新建用户组" :on-ok="submitGroup">
      <Form :label-width="70">
        <FormItem label="编码" required><Input v-model="groupForm.code" placeholder="如 EMERGENCY_TEAM" /></FormItem>
        <FormItem label="名称" required><Input v-model="groupForm.name" placeholder="如 应急小组" /></FormItem>
        <FormItem label="说明"><Input v-model="groupForm.description" type="textarea" :rows="2" /></FormItem>
      </Form>
    </PdsModal>

    <PdsModal v-model="addMember.open" :title="`添加成员 · ${selectedGroup?.name}`" ok-text="添加" :on-ok="submitMembers">
      <UserSelect v-model="addMember.ids" multiple placeholder="搜索并选择人员（可多选）" />
      <p class="pds-muted mb-0 mt-3">添加后，成员经由该用户组获得的角色与资源权限立即生效。</p>
    </PdsModal>
  </div>
</template>
