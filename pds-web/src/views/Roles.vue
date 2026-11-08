<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconPlus, IconLock, IconShield, IconSearch, IconClose } from '@kousum/semi-icons-vue'
import {
  api, ACTIONS, DATA_SCOPES, SUBJECT_TYPES, type AppView, type BindingView, type GrantView, type PermPoint, type ResourceType,
  type ResourceView, type RoleView,
} from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import SubjectPicker from '@/components/pds/SubjectPicker.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canRole = computed(() => session.has('pds:role:manage'))
const tab = ref<'roles' | 'resources'>(route.query.tab === 'resources' ? 'resources' : 'roles')

// =============== 角色 ===============
const roles = ref<RoleView[]>([])
const apps = ref<AppView[]>([])
const current = ref<RoleView | null>(null)
const sub = ref<'perms' | 'bindings'>('perms')
const app = ref('')
const points = ref<PermPoint[]>([])
const draft = reactive<Record<string, { checked: boolean; dataScope: string }>>({})
const original = ref('')
const bindings = ref<BindingView[]>([])

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

async function loadRoles(keepId?: string) {
  roles.value = await api.roles()
  const id = keepId || (route.query.roleId as string) || current.value?.id
  const pick = roles.value.find((r) => r.id === id) || roles.value[0]
  if (pick) await applyRole(pick)
}
async function applyRole(r: RoleView) {
  current.value = r
  Object.keys(draft).forEach((k) => delete draft[k])
  r.permissions.forEach((p) => (draft[p.permCode] = { checked: true, dataScope: p.dataScope || '' }))
  original.value = draftKey()
  router.replace({ query: { ...route.query, roleId: r.id } })
  bindings.value = await api.roleBindings(r.id)
}
function selectRole(r: RoleView) {
  if (r.id === current.value?.id) return
  if (dirty.value) {
    Modal.confirm({ title: '放弃未保存的修改？', content: `角色【${current.value?.name}】有 ${changes.value} 项权限修改尚未保存。`, okText: '放弃修改', onOk: () => applyRole(r) })
  } else applyRole(r)
}
onBeforeRouteLeave(() => (dirty.value ? window.confirm('角色权限有未保存的修改，确定离开？') : true))

async function loadPoints() { points.value = app.value ? await api.appPermissions(app.value) : [] }
watch(app, loadPoints)
onMounted(async () => {
  apps.value = await api.apps()
  app.value = apps.value[0]?.code || ''
  await loadRoles()
})
const countByApp = (code: string) => Object.entries(draft).filter(([k, v]) => v.checked && k.startsWith(code + ':')).length
function toggle(code: string, checked: boolean) { draft[code] = { checked, dataScope: draft[code]?.dataScope || '' } }
function toggleAll(checked: boolean) { points.value.filter((p) => p.status === 1).forEach((p) => toggle(p.code, checked)) }
const allChecked = computed(() => points.value.length > 0 && points.value.filter((p) => p.status === 1).every((p) => draft[p.code]?.checked))

async function savePerms() {
  const permissions = Object.entries(draft).filter(([, v]) => v.checked).map(([permCode, v]) => ({ permCode, dataScope: v.dataScope || undefined }))
  await api.setRolePermissions(current.value!.id, permissions)
  Message.success('已保存角色权限，相关人员的权限已重新计算')
  await loadRoles(current.value!.id)
}
function discard() { if (current.value) applyRole(current.value) }

const bind = reactive({ open: false, type: 'POSITION', id: '', includeChildren: true, validTo: '' as any })
async function submitBinding() {
  if (!bind.id) { Message.warning('请选择授权对象'); return false }
  await api.bindRole(current.value!.id, { subjectType: bind.type, subjectId: bind.id, includeChildren: bind.includeChildren,
    validTo: bind.validTo ? new Date(bind.validTo).toISOString() : undefined })
  Message.success('已绑定')
  bindings.value = await api.roleBindings(current.value!.id)
  roles.value = await api.roles()
}
function unbind(b: BindingView) {
  Modal.confirm({ title: '解除绑定', content: `解除【${b.subjectName}】与角色【${current.value?.name}】的绑定？被覆盖人员将立即失去该角色的权限。`, okText: '解除',
    onOk: async () => { await api.unbindRole(b.id); Message.success('已解除'); bindings.value = await api.roleBindings(current.value!.id); roles.value = await api.roles() } })
}

const create = reactive({ open: false, code: '', name: '', description: '' })
async function submitRole() {
  if (!create.code.trim() || !create.name.trim()) { Message.warning('请填写编码与名称'); return false }
  const r: any = await api.createRole({ code: create.code.trim(), name: create.name.trim(), description: create.description || undefined })
  Message.success('已创建，请为角色勾选权限')
  sub.value = 'perms'
  await loadRoles(r.id)
}

// =============== 资源授权 ===============
const types = ref<ResourceType[]>([])
const rq = reactive({ type: '', keyword: '' })
const resources = ref<ResourceView[]>([])
const res = ref<ResourceView | null>(null)
const grants = ref<GrantView[]>([])
const typeName = (c: string) => types.value.find((t) => t.typeCode === c)?.name || c
async function loadResources() {
  if (!types.value.length) types.value = await api.resourceTypes()
  resources.value = (await api.resources({ type: rq.type || undefined, keyword: rq.keyword || undefined, pageSize: 100 })).items
}
watch(tab, (t) => {
  router.replace({ query: t === 'resources' ? { tab: 'resources' } : { roleId: current.value?.id } })
  if (t === 'resources' && !resources.value.length) loadResources()
}, { immediate: true })
let kw: number | undefined
watch(() => rq.keyword, () => { window.clearTimeout(kw); kw = window.setTimeout(loadResources, 300) })
watch(() => rq.type, loadResources)
async function openResource(r: ResourceView) { res.value = r; grants.value = await api.resourceGrants(r.type, r.refId) }
const grantForm = reactive({ open: false, type: 'ORG', id: '', includeChildren: true, action: 'READ', effect: 'ALLOW', validTo: '' as any, reason: '' })
async function submitGrant() {
  if (!grantForm.id) { Message.warning('请选择授权对象'); return false }
  const r = res.value!
  await api.addResourceGrant(r.type, r.refId, { subjectType: grantForm.type, subjectId: grantForm.id, includeChildren: grantForm.includeChildren,
    action: grantForm.action, effect: grantForm.effect, validTo: grantForm.validTo ? new Date(grantForm.validTo).toISOString() : undefined,
    reason: grantForm.reason || undefined })
  Message.success('已授权')
  grants.value = await api.resourceGrants(r.type, r.refId)
  loadResources()
}
function revokeGrant(g: GrantView) {
  const r = res.value!
  Modal.confirm({ title: '撤销授权', content: `撤销【${g.subjectName}】对【${r.name}】的${ACTIONS[g.action]}${g.effect === 'DENY' ? '禁止' : '授权'}？`, okText: '撤销',
    onOk: async () => { await api.removeResourceGrant(r.type, r.refId, g.id); Message.success('已撤销'); grants.value = await api.resourceGrants(r.type, r.refId); loadResources() } })
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="flex items-center gap-3">
      <RadioGroup v-model="tab" type="button"><Radio label="roles">角色</Radio><Radio label="resources">资源授权</Radio></RadioGroup>
      <span class="pds-muted">{{ tab === 'roles' ? '角色是权限点的集合，绑定到人员、岗位、组织或用户组后生效' : '对具体资源实例（如某个知识库）授权到人员、岗位、组织或用户组' }}</span>
    </div>

    <!-- ========== 角色 ========== -->
    <div v-if="tab === 'roles'" class="grid grid-cols-1 gap-5 xl:grid-cols-[300px_1fr]">
      <div class="pds-card self-start">
        <div class="pds-card-title">角色 <button v-if="canRole" class="pds-link" @click="Object.assign(create, { open: true, code: '', name: '', description: '' })">新建</button></div>
        <div class="flex flex-col gap-1">
          <div v-for="r in roles" :key="r.id" class="cursor-pointer rounded-xl px-3 py-2.5 transition-colors"
               :class="current?.id === r.id ? 'bg-accent text-ink' : 'hover:bg-row'" @click="selectRole(r)">
            <div class="flex items-center gap-2 text-[14px]">
              <component :is="r.builtin ? IconLock : IconShield" size="small" :class="current?.id === r.id ? 'text-ink' : 'text-[#9CA3AF]'" />
              <span class="flex-1 truncate font-medium">{{ r.name }}</span>
              <span class="text-[12px]" :class="current?.id === r.id ? 'text-ink' : 'text-[#9CA3AF]'">{{ r.bindingCount }} 绑定</span>
            </div>
            <div class="mt-0.5 pl-6 text-[12px]" :class="current?.id === r.id ? 'text-ink/70' : 'text-muted'">{{ r.permissions.length }} 项权限 · {{ r.code }}</div>
          </div>
        </div>
      </div>

      <div v-if="current" class="pds-card relative min-w-0 pb-20">
        <div class="mb-4 flex flex-wrap items-center gap-3">
          <span class="text-[18px] font-semibold text-ink">{{ current.name }}</span>
          <span v-if="current.builtin" class="pds-badge dark">内置</span>
          <span class="pds-muted">{{ current.description }}</span>
          <span class="flex-1" />
          <RadioGroup v-model="sub" type="button" size="small"><Radio label="perms">权限</Radio><Radio label="bindings">绑定（{{ bindings.length }}）</Radio></RadioGroup>
        </div>

        <template v-if="sub === 'perms'">
          <div class="mb-4 flex flex-wrap gap-2">
            <button v-for="a in apps" :key="a.code" class="pds-pill" :class="{ '!bg-ink !text-white': app === a.code }" style="height: 34px" @click="app = a.code">
              {{ a.name }} <span class="opacity-60">{{ countByApp(a.code) }}</span>
            </button>
          </div>
          <div v-if="current.builtin" class="mb-3 rounded-xl bg-warn-soft-2 px-4 py-2 text-[13px] text-[#92400E]">内置角色的权限由系统维护，只有拥有"授权不受自身权限限制"的安全管理员可以修改。</div>
          <table class="pds-table">
            <thead><tr>
              <th style="width: 56px"><Checkbox :model-value="allChecked" :disabled="!canRole" @on-change="toggleAll" /></th>
              <th>权限点</th><th style="width: 110px">类型</th><th style="width: 80px">风险</th><th style="width: 190px">数据范围</th>
            </tr></thead>
            <tbody>
              <tr v-for="p in points" :key="p.code" class="clickable" @click="canRole && p.status === 1 && toggle(p.code, !draft[p.code]?.checked)">
                <td @click.stop><Checkbox :model-value="!!draft[p.code]?.checked" :disabled="!canRole || p.status === 0" @on-change="(v: boolean) => toggle(p.code, v)" /></td>
                <td><div class="text-ink">{{ p.name }} <span v-if="p.status === 0" class="pds-badge gray">已停用</span></div><div class="pds-mono pds-muted">{{ p.code }}</div></td>
                <td class="text-[13px] text-muted">{{ p.type }}</td>
                <td><span class="pds-badge" :class="p.risk === 'HIGH' ? 'bad' : 'none'">{{ p.risk === 'HIGH' ? '高' : '低' }}</span></td>
                <td @click.stop>
                  <Select v-if="draft[p.code]?.checked" v-model="draft[p.code].dataScope" size="small" clearable transfer placeholder="默认：本人景区" :disabled="!canRole">
                    <Option v-for="(l, k) in DATA_SCOPES" v-show="!['SCENIC_LIST', 'CUSTOM', 'DOMAIN'].includes(k as string)" :key="k" :value="k">{{ l }}</Option>
                  </Select>
                  <span v-else class="text-[#C0C4CC]">—</span>
                </td>
              </tr>
              <tr v-if="!points.length"><td colspan="5" class="empty">该应用尚未注册权限清单</td></tr>
            </tbody>
          </table>
          <p class="pds-muted mb-0 mt-3">未设置数据范围时按"本人所属景区"处理；授予的权限不能超出您自己拥有的权限（安全管理员除外）。</p>
          <!-- 未保存修改提示条 -->
          <div v-if="dirty" class="absolute inset-x-6 bottom-5 flex items-center gap-3 rounded-2xl bg-ink px-5 py-3 text-white shadow-lg">
            <span class="flex-1 text-[14px]">有 <b class="text-accent">{{ changes }}</b> 项权限修改尚未保存</span>
            <button class="pds-btn sm ghost !border-0 !bg-white/10 !text-white" @click="discard">放弃</button>
            <button class="pds-btn sm accent" @click="savePerms">保存权限</button>
          </div>
        </template>

        <template v-else>
          <div class="mb-4 flex items-center">
            <span class="pds-muted flex-1">被绑定对象覆盖的人员自动获得该角色的全部权限；组织可选择包含下级。</span>
            <button v-if="canRole" class="pds-btn dark sm" @click="Object.assign(bind, { open: true, id: '', validTo: '' })"><IconPlus size="small" /> 新增绑定</button>
          </div>
          <table class="pds-table">
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
        </template>
      </div>
    </div>

    <!-- ========== 资源授权 ========== -->
    <div v-else class="grid grid-cols-1 gap-5" :class="res ? 'xl:grid-cols-[1fr_440px]' : ''">
      <div class="pds-card min-w-0">
        <div class="mb-5 flex flex-wrap items-center gap-3">
          <div class="pds-search w-[240px]"><IconSearch /><input v-model="rq.keyword" placeholder="资源名称或 ID" /></div>
          <button class="pds-pill" :class="{ '!bg-ink !text-white': !rq.type }" @click="rq.type = ''">全部类型</button>
          <button v-for="t in types" :key="t.typeCode" class="pds-pill" :class="{ '!bg-ink !text-white': rq.type === t.typeCode }" @click="rq.type = t.typeCode">{{ t.name }}</button>
        </div>
        <table class="pds-table">
          <thead><tr><th>资源</th><th>类型</th><th>所属</th><th>授权条数</th><th>更新时间</th><th></th></tr></thead>
          <tbody>
            <tr v-for="r in resources" :key="r.id" class="clickable" :class="{ active: res?.id === r.id }" @click="openResource(r)">
              <td><div class="text-ink">{{ r.name }}</div><div class="pds-mono pds-muted">{{ r.refId }}</div></td>
              <td>{{ typeName(r.type) }}</td>
              <td><span class="pds-badge" :class="r.scenicId === 'GROUP' ? 'ok' : 'gray'">{{ r.scenicName }}</span></td>
              <td>{{ r.grantCount }}</td>
              <td class="text-muted">{{ formatTime(r.updatedAt) }}</td>
              <td><button class="pds-link" @click.stop="openResource(r)">授权</button></td>
            </tr>
            <tr v-if="!resources.length"><td colspan="6" class="empty">暂无资源（由各接入系统注册，如 PDK 知识库、PDD 数据集）</td></tr>
          </tbody>
        </table>
      </div>

      <div v-if="res" class="pds-card self-start border-0 border-l-4 border-solid border-accent">
        <div class="flex items-start justify-between">
          <div>
            <div class="text-[17px] font-semibold text-ink">{{ res.name }}</div>
            <div class="pds-muted mt-1">{{ typeName(res.type) }} · {{ res.scenicName }} · 查看 ⊂ 编辑 ⊂ 管理，禁止优先</div>
          </div>
          <button class="pds-link gray" @click="res = null"><IconClose /></button>
        </div>
        <div class="mt-5 flex flex-col gap-2">
          <div v-for="g in grants" :key="g.id" class="flex items-center gap-3 rounded-xl px-4 py-3" :class="g.effect === 'DENY' ? 'bg-danger-soft' : 'bg-row'">
            <div class="min-w-0 flex-1">
              <div class="truncate text-[14px] text-ink">{{ g.subjectName }}<span v-if="g.includeChildren" class="pds-muted">（含下级）</span></div>
              <div class="pds-muted">{{ SUBJECT_TYPES[g.subjectType] }} · {{ g.validTo ? `至 ${formatTime(g.validTo).slice(0, 10)}` : '长期' }} · {{ g.grantedByName }}</div>
            </div>
            <span class="pds-badge" :class="g.effect === 'DENY' ? 'bad-solid' : 'white'">{{ g.effect === 'DENY' ? '禁止' : ACTIONS[g.action] }}</span>
            <button class="pds-link red" @click="revokeGrant(g)">撤销</button>
          </div>
          <div v-if="!grants.length" class="pds-muted py-6 text-center">尚未授权，除资源管理者外无人可访问</div>
        </div>
        <button class="pds-btn dark mt-5 w-full justify-center" @click="Object.assign(grantForm, { open: true, id: '', reason: '', validTo: '' })"><IconPlus size="small" /> 新增授权</button>
      </div>
    </div>

    <PdsModal v-model="bind.open" :title="`绑定角色 · ${current?.name}`" width="560" ok-text="绑定" :on-ok="submitBinding">
      <Form :label-width="80">
        <FormItem label="授权对象" required><SubjectPicker v-model:type="bind.type" v-model:id="bind.id" v-model:include-children="bind.includeChildren" /></FormItem>
        <FormItem label="有效期至"><DatePicker v-model="bind.validTo" type="datetime" placeholder="不填为长期" transfer style="width: 100%" /></FormItem>
      </Form>
    </PdsModal>
    <PdsModal v-model="create.open" title="新建角色" ok-text="创建" :on-ok="submitRole">
      <Form :label-width="70">
        <FormItem label="编码" required><Input v-model="create.code" placeholder="如 PDA_USER" /></FormItem>
        <FormItem label="名称" required><Input v-model="create.name" placeholder="如 PDA 普通用户" /></FormItem>
        <FormItem label="说明"><Input v-model="create.description" type="textarea" :rows="2" /></FormItem>
      </Form>
    </PdsModal>
    <PdsModal v-model="grantForm.open" :title="`新增授权 · ${res?.name}`" width="560" ok-text="授权" :on-ok="submitGrant">
      <Form :label-width="80">
        <FormItem label="授权对象" required><SubjectPicker v-model:type="grantForm.type" v-model:id="grantForm.id" v-model:include-children="grantForm.includeChildren" /></FormItem>
        <FormItem label="动作"><RadioGroup v-model="grantForm.action" type="button" size="small"><Radio v-for="(l, k) in ACTIONS" :key="k" :label="k">{{ l }}</Radio></RadioGroup></FormItem>
        <FormItem label="效果"><RadioGroup v-model="grantForm.effect"><Radio label="ALLOW">允许</Radio><Radio label="DENY">禁止</Radio></RadioGroup></FormItem>
        <FormItem label="有效期至"><DatePicker v-model="grantForm.validTo" type="datetime" placeholder="不填为长期" transfer style="width: 100%" /></FormItem>
        <FormItem label="原因"><Input v-model="grantForm.reason" placeholder="选填，记入审计" /></FormItem>
      </Form>
    </PdsModal>
  </div>
</template>
