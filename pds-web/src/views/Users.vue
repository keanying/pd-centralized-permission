<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconSearch, IconImport, IconPlus, IconUserGroup } from '@kousum/semi-icons-vue'
import { api, flattenOrgs, type Credential, type GroupView, type OrgNode, type Position, type UserView } from '@/api'
import { useSession } from '@/stores/session'
import { formatRelative } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import PillSelect from '@/components/pds/PillSelect.vue'
import UserDrawer from '@/components/pds/UserDrawer.vue'
import ImportUsers from '@/components/pds/ImportUsers.vue'
import OneTimeSecret from '@/components/pds/OneTimeSecret.vue'
import UserSelect from '@/components/pds/UserSelect.vue'

/**
 * 人员管理：PDS 作为统一登录入口，集中管理全部账号（新建、导入、编辑、停用/启用、重置密码、解锁），
 * 以及用户组与岗位。组织内的人员调整也可在架构树中进行。
 */
const route = useRoute()
const router = useRouter()
const session = useSession()
const canUser = computed(() => session.has('pds:user:manage'))
const canGroup = computed(() => session.has('pds:group:manage'))
const canOrg = computed(() => session.has('pds:org:manage'))

const tab = ref<'users' | 'groups' | 'positions'>((route.query.tab as any) || 'users')
watch(tab, (t) => router.replace({ query: t === 'users' ? {} : { tab: t } }))

const orgs = ref<(OrgNode & { depth: number })[]>([])
const positions = ref<Position[]>([])
const scenics = computed(() => orgs.value.filter((o) => o.type === 'SCENIC'))
async function loadBase() {
  const [t, p] = await Promise.all([api.orgTree(), api.positions()])
  orgs.value = flattenOrgs(t)
  positions.value = p
}

// ================= 人员 =================
const USER_TYPES: Record<string, string> = { FULL_TIME: '正式员工', OUTSOURCE: '劳务派遣', INTERN: '实习生', TEMP: '临时工' }
const STATUS: Record<string, { label: string; cls: string }> = {
  ACTIVE: { label: '在职', cls: 'ok' }, DISABLED: { label: '停用', cls: 'off' }, LEFT: { label: '离职', cls: 'bad' },
}
const q = reactive({ keyword: '', orgId: '', status: '', userType: '', page: 1 })
const PAGE_SIZE = 12
const rows = ref<UserView[]>([])
const total = ref(0)
const loading = ref(false)
async function loadUsers() {
  loading.value = true
  try {
    const r = await api.users({ keyword: q.keyword || undefined, orgId: q.orgId || undefined, includeChildren: true,
      status: q.status || undefined, page: q.page, pageSize: PAGE_SIZE })
    rows.value = q.userType ? r.items.filter((u) => u.userType === q.userType) : r.items
    total.value = r.total
  } finally {
    loading.value = false
  }
}
watch([() => q.orgId, () => q.status, () => q.userType], () => { q.page = 1; loadUsers() })
watch(() => q.page, loadUsers)
let kw: number | undefined
watch(() => q.keyword, () => { window.clearTimeout(kw); kw = window.setTimeout(() => { q.page = 1; loadUsers() }, 300) })
const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const pageList = computed(() => {
  const start = Math.max(1, Math.min(q.page - 2, pages.value - 4))
  return Array.from({ length: Math.min(5, pages.value) }, (_, i) => start + i)
})

const drawer = reactive({ open: false, user: null as UserView | null })
function toggleStatus(u: UserView) {
  const enable = u.status !== 'ACTIVE'
  Modal.confirm({
    title: enable ? '启用账号' : '停用账号',
    content: enable ? `恢复【${u.name}】的登录。` : `停用后【${u.name}】已登录的会话与令牌立即失效，无法再登录任何应用。`,
    okText: enable ? '启用' : '停用',
    onOk: async () => { await (enable ? api.enableUser(u.id) : api.disableUser(u.id, '管理员停用')); Message.success(enable ? '已启用' : '已停用'); loadUsers() },
  })
}
const secret = reactive({ open: false, title: '', value: '' })
function resetPassword(u: UserView) {
  Modal.confirm({ title: '重置密码', content: `为【${u.name}】生成新的初始密码？其现有登录会话立即失效，下次登录须修改密码。`, okText: '重置',
    onOk: async () => { const c = await api.resetPassword(u.id); if (c.initialPassword) Object.assign(secret, { open: true, title: `${u.name} 的新密码`, value: c.initialPassword }) } })
}
const create = reactive({ open: false, username: '', empNo: '', name: '', mobile: '', email: '', userType: 'FULL_TIME', orgId: '', positionId: '' })
function openCreate() { Object.assign(create, { open: true, username: '', empNo: '', name: '', mobile: '', email: '', userType: 'FULL_TIME', orgId: q.orgId, positionId: '' }) }
async function submitCreate() {
  if (!/^[A-Za-z0-9_.@-]{3,64}$/.test(create.username)) { Message.warning('账号只能包含字母、数字和 _.@-，长度 3-64'); return false }
  if (!create.name.trim()) { Message.warning('请填写姓名'); return false }
  if (!create.orgId) { Message.warning('请选择所属组织'); return false }
  const c: Credential = await api.createUser({ username: create.username, empNo: create.empNo || undefined, name: create.name, mobile: create.mobile || undefined,
    email: create.email || undefined, userType: create.userType, orgId: create.orgId, positionId: create.positionId || undefined })
  if (c.initialPassword) Object.assign(secret, { open: true, title: `已创建人员：${create.name}`, value: c.initialPassword })
  loadUsers()
}
const importOpen = ref(false)

// ================= 用户组 =================
const groups = ref<GroupView[]>([])
const groupId = ref('')
const selectedGroup = computed(() => groups.value.find((g) => g.id === groupId.value) || null)
const members = ref<{ id: string; name: string; username: string }[]>([])
async function loadGroups() {
  groups.value = await api.groups()
  if (!groups.value.some((g) => g.id === groupId.value)) groupId.value = groups.value[0]?.id || ''
}
watch(groupId, async (id) => { members.value = id ? await api.groupMembers(id) : [] })
const groupForm = reactive({ open: false, mode: 'create' as 'create' | 'edit', code: '', name: '', description: '' })
function openGroup(g?: GroupView) {
  Object.assign(groupForm, g ? { open: true, mode: 'edit', code: g.code, name: g.name, description: g.description || '' }
    : { open: true, mode: 'create', code: '', name: '', description: '' })
}
async function submitGroup() {
  if (!groupForm.code.trim() || !groupForm.name.trim()) { Message.warning('请填写编码与名称'); return false }
  if (groupForm.mode === 'create') {
    const g: any = await api.createGroup({ code: groupForm.code.trim(), name: groupForm.name.trim(), description: groupForm.description || undefined })
    await loadGroups()
    groupId.value = g.id
  } else {
    await api.updateGroup(groupId.value, { code: groupForm.code.trim(), name: groupForm.name.trim(), description: groupForm.description })
    await loadGroups()
  }
  Message.success('已保存')
}
function deleteGroup(g: GroupView) {
  Modal.confirm({ title: '删除用户组', content: `删除【${g.name}】？成员关系一并删除；有授权或角色绑定时不能删除。`, okText: '删除',
    onOk: async () => { await api.deleteGroup(g.id); Message.success('已删除'); loadGroups() } })
}
const addMember = reactive({ open: false, ids: [] as string[] })
async function submitMembers() {
  await api.setGroupMembers(groupId.value, [...new Set([...members.value.map((m) => m.id), ...addMember.ids])])
  Message.success('已添加成员')
  addMember.ids = []
  await loadGroups()
  members.value = await api.groupMembers(groupId.value)
}
function removeMember(m: { id: string; name: string }) {
  Modal.confirm({ title: '移出用户组', content: `将【${m.name}】移出【${selectedGroup.value?.name}】？其经由该用户组获得的权限将立即失效。`, okText: '移出',
    onOk: async () => {
      await api.setGroupMembers(groupId.value, members.value.filter((x) => x.id !== m.id).map((x) => x.id))
      Message.success('已移出'); await loadGroups(); members.value = await api.groupMembers(groupId.value)
    } })
}

// ================= 岗位 =================
const posRows = ref<{ id: string; code: string; name: string; scenicId?: string; members: number; grants: number }[]>([])
async function loadPositions() { posRows.value = await api.positionStats(); positions.value = await api.positions() }
const posForm = reactive({ open: false, id: '', code: '', name: '', scenicId: '' })
function openPosition(p?: { id: string; code: string; name: string; scenicId?: string }) {
  Object.assign(posForm, p ? { open: true, id: p.id, code: p.code, name: p.name, scenicId: p.scenicId || '' } : { open: true, id: '', code: '', name: '', scenicId: '' })
}
async function submitPosition() {
  if (!posForm.code.trim() || !posForm.name.trim()) { Message.warning('请填写岗位编码与名称'); return false }
  if (posForm.id) await api.updatePosition(posForm.id, { code: posForm.code.trim(), name: posForm.name.trim(), scenicId: posForm.scenicId || '' })
  else await api.createPosition({ code: posForm.code.trim(), name: posForm.name.trim(), scenicId: posForm.scenicId || undefined })
  Message.success('已保存岗位')
  loadPositions()
}
function deletePosition(p: { id: string; name: string }) {
  Modal.confirm({ title: '删除岗位', content: `删除岗位【${p.name}】？有人担任或有授权时不能删除。`, okText: '删除',
    onOk: async () => { await api.deletePosition(p.id); Message.success('已删除'); loadPositions() } })
}
const scenicName = (id?: string) => (id ? orgs.value.find((o) => o.id === id)?.name || id : '集团通用')

onMounted(async () => {
  await loadBase()
  loadUsers()
  loadGroups()
  loadPositions()
})
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="pds-segment self-start">
      <button :class="{ on: tab === 'users' }" @click="tab = 'users'">人员</button>
      <button :class="{ on: tab === 'groups' }" @click="tab = 'groups'">用户组</button>
      <button :class="{ on: tab === 'positions' }" @click="tab = 'positions'">岗位</button>
    </div>

    <!-- 人员 -->
    <div v-if="tab === 'users'" class="pds-card min-w-0">
      <div class="mb-5 flex flex-wrap items-center gap-3">
        <div class="pds-search w-[240px]"><IconSearch /><input v-model="q.keyword" placeholder="搜索工号 / 姓名 / 账号" /></div>
        <Select v-model="q.orgId" clearable filterable transfer placeholder="全部组织" style="width: 200px">
          <Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
        </Select>
        <PillSelect v-model="q.status" label="状态" compact :options="Object.entries(STATUS).map(([value, s]) => ({ value, label: s.label }))" />
        <PillSelect v-model="q.userType" label="人员类型" compact :options="Object.entries(USER_TYPES).map(([value, label]) => ({ value, label }))" />
        <span class="flex-1" />
        <button v-if="canUser" class="pds-btn ghost" @click="importOpen = true"><IconImport size="small" /> 批量导入</button>
        <button v-if="canUser" class="pds-btn dark" @click="openCreate"><IconPlus size="small" /> 新建人员</button>
      </div>
      <table class="pds-table">
        <thead><tr><th>工号</th><th>姓名</th><th>账号</th><th>主部门</th><th>岗位</th><th>人员类型</th><th>状态</th><th>最近登录</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="u in rows" :key="u.id">
            <td class="text-[#6B7280]">{{ u.empNo || '—' }}</td>
            <td class="text-ink">{{ u.name }}</td>
            <td class="pds-mono text-[#6B7280]">{{ u.username }}</td>
            <td>{{ u.primaryOrgName || '—' }}</td>
            <td><span class="flex flex-wrap gap-1"><span v-for="(p, i) in u.positions" :key="p" class="pds-badge" :class="i === 0 ? 'ok' : 'gray'">{{ p }}</span>
              <span v-if="!u.positions.length" class="text-[#9CA3AF]">—</span></span></td>
            <td>{{ USER_TYPES[u.userType] || u.userType }}</td>
            <td><span class="pds-dot" :class="STATUS[u.status]?.cls">{{ STATUS[u.status]?.label }}</span><span v-if="u.locked" class="pds-badge warn ml-1">锁定</span></td>
            <td class="text-[#6B7280]">{{ formatRelative(u.lastLoginAt) }}</td>
            <td class="whitespace-nowrap text-[13px] text-[#6B7280]">
              <button class="pds-link gray" @click="Object.assign(drawer, { open: true, user: u })">{{ canUser ? '编辑' : '查看' }}</button> ·
              <button class="pds-link" @click="router.push({ path: '/user-grants', query: { userId: u.id } })">权限</button>
              <template v-if="canUser"> · <button class="pds-link gray" @click="resetPassword(u)">重置密码</button>
                · <button class="pds-link" :class="u.status === 'ACTIVE' ? 'red' : ''" @click="toggleStatus(u)">{{ u.status === 'ACTIVE' ? '停用' : '启用' }}</button></template>
            </td>
          </tr>
          <tr v-if="!loading && !rows.length"><td colspan="9" class="empty">{{ q.keyword ? `没有找到"${q.keyword}"` : '暂无人员' }}</td></tr>
        </tbody>
      </table>
      <div class="pds-pager">
        <span class="pds-muted">共 {{ total.toLocaleString('en-US') }} 人</span>
        <div class="pages">
          <button :disabled="q.page <= 1" @click="q.page--">‹</button>
          <button v-for="p in pageList" :key="p" :class="{ active: p === q.page }" @click="q.page = p">{{ p }}</button>
          <button :disabled="q.page >= pages" @click="q.page++">›</button>
        </div>
      </div>
    </div>

    <!-- 用户组 -->
    <div v-else-if="tab === 'groups'" class="grid grid-cols-1 gap-5 xl:grid-cols-[340px_1fr]">
      <div class="pds-card self-start">
        <div class="pds-card-title">用户组 <button v-if="canGroup" class="pds-btn dark sm" @click="openGroup()"><IconPlus size="small" /> 新建用户组</button></div>
        <div class="flex flex-col gap-1">
          <div v-for="g in groups" :key="g.id" class="flex h-10 cursor-pointer items-center gap-2 rounded-xl px-3 text-[14px] transition-colors"
               :class="g.id === groupId ? 'bg-accent-soft font-semibold text-ink' : 'hover:bg-row'" @click="groupId = g.id">
            <IconUserGroup size="small" class="text-[#A855F7]" />
            <span class="flex-1 truncate">{{ g.name }}</span>
            <span class="text-[12px] text-[#9CA3AF]">{{ g.memberCount }}</span>
          </div>
          <p v-if="!groups.length" class="pds-muted py-6 text-center">暂无用户组</p>
        </div>
        <p class="pds-muted mb-0 mt-3">用户组把跨组织的人员归为一类（如"应急小组"），可整体授予角色、权限或资源。</p>
      </div>
      <div class="pds-card min-w-0">
        <template v-if="selectedGroup">
          <div class="mb-5 flex flex-wrap items-center gap-3">
            <span class="text-[20px] font-bold text-ink">{{ selectedGroup.name }}</span>
            <span class="pds-mono text-[#6B7280]">{{ selectedGroup.code }}</span>
            <span class="pds-muted">{{ selectedGroup.description }}</span>
            <span class="flex-1" />
            <template v-if="canGroup">
              <button class="pds-btn ghost sm" @click="openGroup(selectedGroup)">编辑</button>
              <button class="pds-btn danger sm" @click="deleteGroup(selectedGroup)">删除</button>
              <button class="pds-btn dark sm" @click="addMember.open = true"><IconPlus size="small" /> 添加成员</button>
            </template>
          </div>
          <table class="pds-table">
            <thead><tr><th>姓名</th><th>账号</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="m in members" :key="m.id">
                <td class="text-ink">{{ m.name }}</td><td class="pds-mono text-[#6B7280]">{{ m.username }}</td>
                <td class="text-[13px]"><button class="pds-link" @click="router.push({ path: '/user-grants', query: { userId: m.id } })">权限</button>
                  <template v-if="canGroup"> · <button class="pds-link red" @click="removeMember(m)">移出</button></template></td>
              </tr>
              <tr v-if="!members.length"><td colspan="3" class="empty">该用户组暂无成员</td></tr>
            </tbody>
          </table>
        </template>
        <div v-else class="pds-muted py-16 text-center">新建一个用户组开始</div>
      </div>
    </div>

    <!-- 岗位 -->
    <div v-else class="pds-card min-w-0">
      <div class="mb-5 flex items-center">
        <span class="pds-muted flex-1">岗位可绑定角色或直接授权，人员担任岗位即获得相应权限。</span>
        <button v-if="canOrg" class="pds-btn dark" @click="openPosition()"><IconPlus size="small" /> 新增岗位</button>
      </div>
      <table class="pds-table">
        <thead><tr><th>岗位</th><th>编码</th><th>所属景区</th><th>担任人数</th><th>授权/角色绑定</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="p in posRows" :key="p.id">
            <td class="text-ink">{{ p.name }}</td><td class="pds-mono">{{ p.code }}</td><td>{{ scenicName(p.scenicId) }}</td>
            <td>{{ p.members }}</td><td>{{ p.grants }}</td>
            <td class="text-[13px]">
              <button class="pds-link" @click="router.push({ path: '/assignments', query: { view: 'subject', subjectType: 'POSITION', subjectId: p.id } })">授权</button>
              <template v-if="canOrg"> · <button class="pds-link gray" @click="openPosition(p)">编辑</button>
                · <button class="pds-link red" :disabled="p.members > 0 || p.grants > 0" :title="p.members > 0 || p.grants > 0 ? '有人担任或有授权，不能删除' : ''" @click="deletePosition(p)">删除</button></template>
            </td>
          </tr>
          <tr v-if="!posRows.length"><td colspan="6" class="empty">暂无岗位</td></tr>
        </tbody>
      </table>
    </div>

    <UserDrawer v-model="drawer.open" :user="drawer.user" :orgs="orgs" :positions="positions" :can-manage="canUser" @changed="loadUsers" />
    <ImportUsers v-model="importOpen" @done="loadUsers" />
    <OneTimeSecret v-model="secret.open" :title="secret.title" label="初始密码" :value="secret.value" />

    <PdsModal v-model="create.open" title="新建人员" width="600" ok-text="创建" :on-ok="submitCreate">
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
          <Select v-model="create.orgId" filterable transfer><Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option></Select>
        </FormItem>
        <FormItem label="岗位"><Select v-model="create.positionId" filterable clearable transfer><Option v-for="p in positions" :key="p.id" :value="p.id">{{ p.name }}</Option></Select></FormItem>
      </Form>
      <p class="pds-muted m-0">系统生成符合密码策略的初始密码，创建后只显示一次；首次登录须修改。</p>
    </PdsModal>

    <PdsModal v-model="groupForm.open" :title="groupForm.mode === 'create' ? '新建用户组' : '编辑用户组'" :on-ok="submitGroup">
      <Form :label-width="70">
        <FormItem label="编码" required><Input v-model="groupForm.code" placeholder="如 EMERGENCY_TEAM" /></FormItem>
        <FormItem label="名称" required><Input v-model="groupForm.name" placeholder="如 应急小组" /></FormItem>
        <FormItem label="说明"><Input v-model="groupForm.description" type="textarea" :rows="2" /></FormItem>
      </Form>
    </PdsModal>
    <PdsModal v-model="addMember.open" :title="`添加成员 · ${selectedGroup?.name}`" ok-text="添加" :on-ok="submitMembers">
      <UserSelect v-model="addMember.ids" multiple placeholder="搜索并选择人员（可多选）" />
    </PdsModal>
    <PdsModal v-model="posForm.open" :title="posForm.id ? '编辑岗位' : '新增岗位'" :on-ok="submitPosition">
      <Form :label-width="80">
        <FormItem label="编码" required><Input v-model="posForm.code" placeholder="如 SELLER" /></FormItem>
        <FormItem label="名称" required><Input v-model="posForm.name" placeholder="如 售票员" /></FormItem>
        <FormItem label="所属景区"><Select v-model="posForm.scenicId" clearable placeholder="不选为集团通用岗位" transfer><Option v-for="s in scenics" :key="s.id" :value="s.id">{{ s.name }}</Option></Select></FormItem>
      </Form>
    </PdsModal>
  </div>
</template>
