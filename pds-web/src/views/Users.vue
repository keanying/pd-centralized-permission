<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, resolveComponent } from 'vue'
import { useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconPlus, IconSearch } from '@kousum/semi-icons-vue'
import { api, flattenOrgs, USER_STATUS, type OrgNode, type Position, type UserView, type Credential } from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import OneTimeSecret from '@/components/pds/OneTimeSecret.vue'

const router = useRouter()
const session = useSession()
const canManage = computed(() => session.has('pds:user:manage'))

const orgs = ref<(OrgNode & { depth: number })[]>([])
const positions = ref<Position[]>([])
const query = reactive({ keyword: '', orgId: '', status: '', page: 1, pageSize: 20 })
const rows = ref<UserView[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await api.users({ ...query, orgId: query.orgId || undefined, status: query.status || undefined, includeChildren: true })
    rows.value = r.items
    total.value = r.total
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  const [t, p] = await Promise.all([api.orgTree(), api.positions()])
  orgs.value = flattenOrgs(t)
  positions.value = p
  load()
})
const search = () => { query.page = 1; load() }

// 一次性密码展示
const secret = reactive({ open: false, title: '', value: '' })
function showCredential(c: Credential, title: string) {
  if (c.initialPassword) Object.assign(secret, { open: true, title, value: c.initialPassword })
}

// 新增 / 编辑
const USER_TYPES: Record<string, string> = { FULL_TIME: '正式', OUTSOURCE: '外包', INTERN: '实习', TEMP: '临时' }
const editor = reactive({ open: false, id: '', username: '', empNo: '', name: '', mobile: '', email: '', userType: 'FULL_TIME', orgId: '', positionId: '' })
function openCreate() {
  Object.assign(editor, { open: true, id: '', username: '', empNo: '', name: '', mobile: '', email: '', userType: 'FULL_TIME', orgId: query.orgId, positionId: '' })
}
function openEdit(u: UserView) {
  Object.assign(editor, { open: true, id: u.id, username: u.username, empNo: u.empNo || '', name: u.name, mobile: '', email: u.email || '', userType: u.userType })
}
async function save() {
  if (!editor.name) return Message.warning('请填写姓名')
  if (editor.id) {
    await api.updateUser(editor.id, { name: editor.name, email: editor.email || undefined, mobile: editor.mobile || undefined, userType: editor.userType, empNo: editor.empNo || undefined })
    Message.success('已保存')
  } else {
    if (!editor.username || !editor.orgId) return Message.warning('请填写账号并选择所属组织')
    const c = await api.createUser({
      username: editor.username, empNo: editor.empNo || undefined, name: editor.name, mobile: editor.mobile || undefined,
      email: editor.email || undefined, userType: editor.userType, orgId: editor.orgId, positionId: editor.positionId || undefined,
    })
    showCredential(c, `已创建人员：${editor.name}`)
  }
  editor.open = false
  load()
}

// 调整任职
const assign = reactive({ open: false, user: null as UserView | null, rows: [] as { orgId: string; positionId: string; primary: boolean }[] })
function openAssign(u: UserView) {
  Object.assign(assign, { open: true, user: u, rows: [{ orgId: u.primaryOrgId || '', positionId: '', primary: true }] })
}
async function saveAssign() {
  if (assign.rows.some((r) => !r.orgId)) return Message.warning('请选择组织')
  await api.assignOrgs(assign.user!.id, assign.rows.map((r) => ({ orgId: r.orgId, positionId: r.positionId || undefined, primary: r.primary })))
  Message.success('已调整任职')
  assign.open = false
  load()
}
function setPrimary(i: number) {
  assign.rows.forEach((r, j) => (r.primary = i === j))
}

function confirmAction(u: UserView, action: string) {
  const map: Record<string, { title: string; content: string; run: () => Promise<any> }> = {
    disable: { title: '停用账号', content: `停用后【${u.name}】已登录的会话与令牌立即失效。`, run: () => api.disableUser(u.id, '管理员停用') },
    leave: { title: '办理离职', content: `【${u.name}】将被标记为离职，并立即吊销全部登录。`, run: () => api.leaveUser(u.id, '离职') },
    enable: { title: '启用账号', content: `恢复【${u.name}】的登录（同时解除锁定）。`, run: () => api.enableUser(u.id) },
    reset: {
      title: '重置密码', content: `将为【${u.name}】生成新的初始密码，下次登录须修改。`,
      run: async () => showCredential(await api.resetPassword(u.id), `${u.name} 的新密码`),
    },
  }
  const a = map[action]
  Modal.confirm({ title: a.title, content: a.content, onOk: async () => { await a.run(); Message.success('操作成功'); load() } })
}

const columns = computed(() => [
  { title: '姓名', key: 'name', width: 110 },
  { title: '账号', key: 'username', width: 130 },
  { title: '工号', key: 'empNo', width: 110 },
  { title: '主部门', key: 'primaryOrgName', minWidth: 120 },
  { title: '岗位', key: 'positions', minWidth: 130, render: (hh: any, p: any) => hh('span', p.row.positions.join('、') || '—') },
  { title: '手机', key: 'mobile', width: 130 },
  { title: '类型', key: 'userType', width: 80, render: (hh: any, p: any) => hh('span', USER_TYPES[p.row.userType] || p.row.userType) },
  {
    title: '状态', key: 'status', width: 110,
    render: (hh: any, p: any) => hh('span', { class: 'flex gap-1' }, [
      hh(resolveComponent('Tag'), { color: USER_STATUS[p.row.status]?.color }, () => USER_STATUS[p.row.status]?.label || p.row.status),
      p.row.locked ? hh(resolveComponent('Tag'), { color: 'warning' }, () => '锁定') : null,
    ]),
  },
  { title: '最近登录', key: 'lastLoginAt', width: 150, render: (hh: any, p: any) => hh('span', formatTime(p.row.lastLoginAt)) },
  {
    title: '操作', key: 'op', width: 270,
    render: (hh: any, p: any) => {
      const u: UserView = p.row
      const Button = resolveComponent('Button')
      const btn = (label: string, fn: () => void) => hh(Button, { type: 'text', size: 'small', onClick: fn }, () => label)
      const items = [btn('明细权限', () => router.push({ path: '/user-grants', query: { userId: u.id } }))]
      if (canManage.value) {
        items.push(btn('编辑', () => openEdit(u)), btn('任职', () => openAssign(u)))
        const Dropdown = resolveComponent('Dropdown')
        const DropdownMenu = resolveComponent('DropdownMenu')
        const DropdownItem = resolveComponent('DropdownItem')
        items.push(hh(Dropdown, { transfer: true, trigger: 'click', onOnClick: (name: string) => confirmAction(u, name) }, {
          default: () => btn('更多', () => {}),
          list: () => hh(DropdownMenu, null, () => [
            hh(DropdownItem, { name: 'reset' }, () => '重置密码'),
            u.status === 'ACTIVE' ? hh(DropdownItem, { name: 'disable' }, () => '停用') : hh(DropdownItem, { name: 'enable' }, () => '启用'),
            u.status !== 'LEFT' ? hh(DropdownItem, { name: 'leave', divided: true }, () => '办理离职') : null,
          ]),
        }))
      }
      return hh('span', items)
    },
  },
])
</script>

<template>
  <div class="pds-card">
    <div class="pds-toolbar">
      <Input v-model="query.keyword" placeholder="姓名 / 账号 / 工号" style="width: 220px" clearable @on-enter="search">
        <template #prefix><IconSearch /></template>
      </Input>
      <Select v-model="query.orgId" filterable clearable placeholder="所属组织（含下级）" style="width: 220px" @on-change="search">
        <Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
      </Select>
      <Select v-model="query.status" clearable placeholder="状态" style="width: 120px" @on-change="search">
        <Option v-for="(s, k) in USER_STATUS" :key="k" :value="k">{{ s.label }}</Option>
      </Select>
      <Button type="primary" ghost @click="search">查询</Button>
      <span class="grow" />
      <Button v-if="canManage" type="primary" @click="openCreate"><IconPlus size="small" /> 新增人员</Button>
    </div>
    <Table :columns="columns" :data="rows" :loading="loading" no-data-text="暂无人员" />
    <div class="mt-4 flex justify-end">
      <Page :total="total" v-model="query.page" :page-size="query.pageSize" show-total @on-change="load" />
    </div>

    <Modal v-model="editor.open" :title="editor.id ? '编辑人员' : '新增人员'" width="560" @on-ok="save">
      <Form :label-width="80">
        <div class="grid grid-cols-2 gap-x-4">
          <FormItem label="账号" required><Input v-model="editor.username" :disabled="!!editor.id" placeholder="登录账号" /></FormItem>
          <FormItem label="工号"><Input v-model="editor.empNo" /></FormItem>
          <FormItem label="姓名" required><Input v-model="editor.name" /></FormItem>
          <FormItem label="人员类型">
            <Select v-model="editor.userType" transfer><Option v-for="(l, k) in USER_TYPES" :key="k" :value="k">{{ l }}</Option></Select>
          </FormItem>
          <FormItem label="手机"><Input v-model="editor.mobile" :placeholder="editor.id ? '不修改请留空' : ''" /></FormItem>
          <FormItem label="邮箱"><Input v-model="editor.email" /></FormItem>
        </div>
        <template v-if="!editor.id">
          <FormItem label="所属组织" required>
            <Select v-model="editor.orgId" filterable transfer>
              <Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
            </Select>
          </FormItem>
          <FormItem label="岗位">
            <Select v-model="editor.positionId" filterable clearable transfer>
              <Option v-for="p in positions" :key="p.id" :value="p.id">{{ p.name }}</Option>
            </Select>
          </FormItem>
          <Alert show-icon>系统将生成符合密码策略的初始密码，创建后仅显示一次；人员首次登录须修改密码。</Alert>
        </template>
      </Form>
    </Modal>

    <Modal v-model="assign.open" :title="`调整任职：${assign.user?.name}`" width="640" @on-ok="saveAssign">
      <div v-for="(r, i) in assign.rows" :key="i" class="mb-3 flex items-center gap-2">
        <Select v-model="r.orgId" filterable placeholder="组织" style="width: 230px" transfer>
          <Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
        </Select>
        <Select v-model="r.positionId" clearable placeholder="岗位" style="width: 160px" transfer>
          <Option v-for="p in positions" :key="p.id" :value="p.id">{{ p.name }}</Option>
        </Select>
        <Radio :model-value="r.primary" @on-change="setPrimary(i)">主部门</Radio>
        <Button v-if="assign.rows.length > 1" type="text" @click="assign.rows.splice(i, 1)">移除</Button>
      </div>
      <Button type="dashed" long @click="assign.rows.push({ orgId: '', positionId: '', primary: false })"><IconPlus size="small" /> 增加兼职</Button>
      <p class="pds-muted mt-3">调岗后，此人通过岗位、组织获得的权限将自动重新计算。</p>
    </Modal>

    <OneTimeSecret v-model="secret.open" :title="secret.title" label="初始密码" :value="secret.value" />
  </div>
</template>
