<script setup lang="ts">
import { computed, onMounted, reactive, ref, resolveComponent, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconTickCircle, IconClose, IconMinus } from '@kousum/semi-icons-vue'
import {
  api, ACTIONS, DATA_SCOPES, SECURITY_LEVELS, type AppView, type BindingView, type OrgNode, type PermissionState,
  type UserEffective, type UserPermView, type UserView,
} from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import UserSelect from '@/components/pds/UserSelect.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canGrant = computed(() => session.has('pds:grant:manage'))

const userId = ref<string>((route.query.userId as string) || '')
// 选择器只用于"切换人员"，选中后清空；当前人员信息在右侧展示
const picker = ref<string>('')
watch(picker, (v) => {
  if (v) {
    userId.value = v
    setTimeout(() => (picker.value = ''))
  }
})
const user = ref<UserView | null>(null)
const apps = ref<AppView[]>([])
const app = ref('pds')
const eff = ref<UserEffective | null>(null)
const records = ref<UserPermView[]>([])
const roles = ref<BindingView[]>([])
const scenics = ref<OrgNode[]>([])
const scenicDraft = ref<string[]>([])
const levelDraft = ref(0)

onMounted(async () => {
  apps.value = await api.apps()
  if (apps.value.length) app.value = apps.value[0].code
  const walk = (ns: OrgNode[]) => ns.forEach((n) => { if (n.type === 'SCENIC') scenics.value.push(n); walk(n.children) })
  walk(await api.orgTree())
  if (userId.value) loadUser()
})
watch(userId, (v) => {
  router.replace({ query: v ? { userId: v } : {} })
  if (v) loadUser()
})

async function loadUser() {
  const id = userId.value
  user.value = await api.user(id)
  await Promise.all([loadEffective(), (async () => (records.value = await api.userPerms(id)))(), (async () => (roles.value = await api.bindingsOfSubject('USER', id)))()])
}
async function loadEffective() {
  eff.value = await api.userEffective(userId.value, app.value)
  scenicDraft.value = [...eff.value.scenicScope]
  levelDraft.value = eff.value.maxSecurityLevel
}
const scenicName = (id: string) => scenics.value.find((s) => s.id === id)?.name || id

const stats = computed(() => {
  const ps = eff.value?.permissions ?? []
  return { granted: ps.filter((p) => p.state === 'GRANTED').length, denied: ps.filter((p) => p.state === 'DENIED').length, total: ps.length }
})

function scopeText(s: any) {
  if (!s) return '—'
  if (s.all) return '全部'
  const parts: string[] = []
  if (s.self) parts.push('本人')
  if (s.orgIds?.length) parts.push(`${s.orgIds.length} 个组织`)
  if (s.orgTreeIds?.length) parts.push(`${s.orgTreeIds.length} 个组织及下级`)
  if (s.scenicIds?.length) parts.push(s.scenicIds.map(scenicName).join('、'))
  if (s.domainIds?.length) parts.push(`${s.domainIds.length} 个领域`)
  return parts.join('，') || '—'
}

// ---------- 授予 / 禁止
const dlg = reactive({ open: false, effect: 'ALLOW', perm: null as PermissionState | null, reason: '', validTo: '' as any, dataScope: '' })
function openGrant(p: PermissionState, effect: 'ALLOW' | 'DENY') {
  Object.assign(dlg, { open: true, effect, perm: p, reason: '', validTo: '', dataScope: '' })
}
async function submitGrant() {
  if (!dlg.reason.trim()) return Message.warning('请填写原因（将记入审计）')
  await api.addUserPerm(userId.value, {
    permCode: dlg.perm!.code, effect: dlg.effect, reason: dlg.reason, dataScope: dlg.dataScope || undefined,
    validTo: dlg.validTo ? new Date(dlg.validTo).toISOString() : undefined,
  })
  Message.success(dlg.effect === 'ALLOW' ? '已授予' : '已禁止')
  dlg.open = false
  loadUser()
}
function removeRecord(r: UserPermView) {
  Modal.confirm({
    title: '撤销人员明细权限', content: `撤销「${r.permName || r.resourceName}」的${r.effect === 'ALLOW' ? '授予' : '禁止'}？`,
    onOk: async () => { await api.deleteUserPerm(userId.value, r.id); Message.success('已撤销'); loadUser() },
  })
}
async function saveScenic() {
  await api.setScenicScope(userId.value, scenicDraft.value)
  Message.success('已保存景区范围')
  loadEffective()
}
async function saveLevel() {
  await api.setSecurityLevel({ subjectType: 'USER', subjectId: userId.value, maxLevel: levelDraft.value })
  Message.success('已保存密级')
  loadEffective()
}

// ---------- 权限解释
const explainCode = ref('')
const explainResult = ref<{ allowed: boolean; reason: string } | null>(null)
async function explain() {
  if (!explainCode.value) return
  explainResult.value = await api.explain({ subject: userId.value, permission: explainCode.value })
}

const STATE = {
  GRANTED: { label: '已拥有', color: 'success', icon: IconTickCircle },
  DENIED: { label: '已禁止', color: 'error', icon: IconClose },
  NONE: { label: '未拥有', color: 'default', icon: IconMinus },
}
const columns = computed(() => [
  { title: '权限点', key: 'name', minWidth: 190, render: (h: any, p: any) => h('div', [h('div', p.row.name), h('div', { class: 'pds-mono pds-muted' }, p.row.code)]) },
  {
    title: '状态', key: 'state', width: 100,
    render: (h: any, p: any) => h(resolveComponent('Tag'), { color: STATE[p.row.state as keyof typeof STATE].color }, () => STATE[p.row.state as keyof typeof STATE].label),
  },
  {
    title: '来源 / 原因', key: 'sources', minWidth: 300,
    render: (h: any, p: any) => {
      const lines = [...p.row.denySources.map((s: string) => h('div', { class: 'text-[#E5484D]' }, s)), ...p.row.allowSources.map((s: string) => h('div', s))]
      return h('div', { class: 'py-1 text-xs leading-5' }, lines.length ? lines : [h('span', { class: 'pds-muted' }, '—')])
    },
  },
  { title: '数据范围', key: 'dataScope', width: 140, render: (h: any, p: any) => h('span', { class: 'text-xs' }, p.row.state === 'GRANTED' ? scopeText(p.row.dataScope) : '—') },
  {
    title: '操作', key: 'op', width: 130,
    render: (h: any, p: any) => {
      if (!canGrant.value) return null
      const B = resolveComponent('Button')
      return h('span', [
        p.row.state !== 'GRANTED' ? h(B, { type: 'text', size: 'small', onClick: () => openGrant(p.row, 'ALLOW') }, () => '授予') : null,
        p.row.state !== 'DENIED' ? h(B, { type: 'text', size: 'small', class: 'text-[#E5484D]', onClick: () => openGrant(p.row, 'DENY') }, () => '禁止') : null,
      ])
    },
  },
])
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="pds-card">
      <div class="flex flex-wrap items-center gap-6">
        <div style="width: 300px"><UserSelect v-model="picker" :placeholder="userId ? '切换人员：输入姓名 / 账号 / 工号' : '选择人员（姓名 / 账号 / 工号）'" /></div>
        <template v-if="user && eff">
          <div>
            <div class="text-lg font-semibold text-ink">{{ user.name }} <span class="pds-muted font-normal">{{ user.username }} · {{ user.empNo || '无工号' }}</span></div>
            <div class="pds-muted">{{ user.primaryOrgName }} · {{ user.positions.join('、') || '无岗位' }}</div>
          </div>
          <div class="flex gap-6 text-sm">
            <div><div class="pds-muted">景区范围</div>{{ eff.scenicScope.map(scenicName).join('、') || '无（仅集团共享）' }}</div>
            <div><div class="pds-muted">最高密级</div>{{ SECURITY_LEVELS[eff.maxSecurityLevel] }}</div>
            <div><div class="pds-muted">直接绑定角色</div>{{ roles.map((r) => r.subjectName).join('、') || '—' }}</div>
          </div>
        </template>
      </div>
    </div>

    <div v-if="!userId" class="pds-card pds-muted py-12 text-center">请选择要查看或调整权限的人员</div>

    <div v-else-if="eff" class="grid grid-cols-1 gap-5 2xl:grid-cols-[1fr_380px]">
      <div class="pds-card min-w-0">
        <div class="pds-toolbar">
          <RadioGroup v-model="app" type="button" @on-change="loadEffective">
            <Radio v-for="a in apps" :key="a.code" :label="a.code">{{ a.name }}</Radio>
          </RadioGroup>
          <span class="grow" />
          <span class="text-sm">已拥有 <b class="text-[#0F9F69]">{{ stats.granted }}</b> · 已禁止 <b class="text-[#E5484D]">{{ stats.denied }}</b> · 共 {{ stats.total }}</span>
        </div>
        <Table :columns="columns" :data="eff.permissions" size="small" no-data-text="该应用尚未注册权限清单" />

        <div class="pds-card-title mt-6">资源授权</div>
        <Table size="small" :data="eff.resources" no-data-text="该应用下暂无此人可访问的资源" :columns="[
          { title: '资源', key: 'name', minWidth: 160, render: (h: any, p: any) => h('div', [h('div', p.row.name), h('div', { class: 'pds-mono pds-muted' }, p.row.type + ':' + p.row.refId)]) },
          { title: '所属', key: 'scenicId', width: 120, render: (h: any, p: any) => h('span', p.row.scenicId === 'GROUP' ? '集团共享' : scenicName(p.row.scenicId)) },
          { title: '可执行', key: 'actions', width: 160, render: (h: any, p: any) => h('span', p.row.actions.length ? p.row.actions.map((a: string) => ACTIONS[a]).join(' / ') : '无') },
          { title: '说明', key: 'reason', minWidth: 220, render: (h: any, p: any) => h('span', { class: 'text-xs' }, p.row.reason) },
        ]" />
      </div>

      <div class="flex flex-col gap-5">
        <div class="pds-card">
          <div class="pds-card-title">权限解释</div>
          <div class="flex gap-2">
            <Select v-model="explainCode" filterable placeholder="选择权限点" transfer>
              <Option v-for="p in eff.permissions" :key="p.code" :value="p.code" :label="p.name">{{ p.name }} <span class="pds-muted">{{ p.code }}</span></Option>
            </Select>
            <Button type="primary" @click="explain">解释</Button>
          </div>
          <Alert v-if="explainResult" class="mt-3" :type="explainResult.allowed ? 'success' : 'error'" show-icon>{{ explainResult.reason }}</Alert>
        </div>

        <div class="pds-card">
          <div class="pds-card-title">人员明细记录 <span class="pds-muted">{{ records.length }} 条</span></div>
          <div v-for="r in records" :key="r.id" class="mb-2 rounded-xl border border-line p-3 text-sm">
            <div class="flex items-center justify-between">
              <span><Tag :color="r.effect === 'ALLOW' ? 'success' : 'error'">{{ r.effect === 'ALLOW' ? '授予' : '禁止' }}</Tag>{{ r.permName || r.resourceName }}<span v-if="r.action" class="pds-muted">（{{ ACTIONS[r.action] }}）</span></span>
              <Button v-if="canGrant" type="text" size="small" @click="removeRecord(r)">撤销</Button>
            </div>
            <div class="pds-muted mt-1">原因：{{ r.reason }} · {{ r.grantedBy }} · {{ formatTime(r.createdAt) }}{{ r.validTo ? ` · 至 ${formatTime(r.validTo)}` : '' }}</div>
          </div>
          <div v-if="!records.length" class="pds-muted">暂无个人直授或禁止记录</div>
        </div>

        <div v-if="canGrant" class="pds-card">
          <div class="pds-card-title">景区范围与密级</div>
          <div class="pds-muted mb-1">可访问景区（为空时取所属景区）</div>
          <Select v-model="scenicDraft" multiple placeholder="选择景区" transfer>
            <Option v-for="s in scenics" :key="s.id" :value="s.id">{{ s.name }}</Option>
          </Select>
          <Button class="mt-2" size="small" @click="saveScenic">保存景区范围</Button>
          <div class="pds-muted mb-1 mt-4">最高可访问密级（本人设置优先于岗位/组织）</div>
          <RadioGroup v-model="levelDraft" type="button" size="small">
            <Radio v-for="(l, i) in SECURITY_LEVELS" :key="i" :label="i">{{ l }}</Radio>
          </RadioGroup>
          <div><Button class="mt-2" size="small" @click="saveLevel">保存密级</Button></div>
        </div>
      </div>
    </div>

    <Modal v-model="dlg.open" :title="`${dlg.effect === 'ALLOW' ? '直接授予' : '禁止'}：${dlg.perm?.name}`" @on-ok="submitGrant">
      <Alert v-if="dlg.effect === 'DENY'" type="warning" show-icon>禁止优先于任何授予：即使此人通过角色、岗位或组织获得该权限，也将被拒绝。</Alert>
      <Form :label-width="80">
        <FormItem label="原因" required><Input v-model="dlg.reason" type="textarea" :rows="2" placeholder="将记入审计，如：票务知识联络人" /></FormItem>
        <FormItem v-if="dlg.effect === 'ALLOW'" label="数据范围">
          <Select v-model="dlg.dataScope" clearable placeholder="默认：本人景区" transfer>
            <Option v-for="(l, k) in DATA_SCOPES" :key="k" :value="k">{{ l }}</Option>
          </Select>
        </FormItem>
        <FormItem label="有效期至"><DatePicker v-model="dlg.validTo" type="datetime" placeholder="不填为长期" transfer style="width: 100%" /></FormItem>
      </Form>
    </Modal>
  </div>
</template>
