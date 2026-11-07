<script setup lang="ts">
import { computed, onMounted, reactive, ref, resolveComponent } from 'vue'
import { Message, Modal } from 'view-ui-plus'
import { IconPlus, IconShield, IconLock } from '@kousum/semi-icons-vue'
import { api, DATA_SCOPES, SUBJECT_TYPES, type AppView, type BindingView, type PermPoint, type RoleView } from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import SubjectPicker from '@/components/pds/SubjectPicker.vue'

const session = useSession()
const canManage = computed(() => session.has('pds:role:manage'))
const roles = ref<RoleView[]>([])
const apps = ref<AppView[]>([])
const current = ref<RoleView | null>(null)
const tab = ref('perms')

async function load(keepId?: string) {
  roles.value = await api.roles()
  const pick = roles.value.find((r) => r.id === (keepId || current.value?.id)) || roles.value[0]
  if (pick) select(pick)
}
onMounted(async () => {
  apps.value = await api.apps()
  if (apps.value.length) app.value = apps.value[0].code
  await load()
})

// ---------- 权限编辑
const app = ref('pds')
const points = ref<PermPoint[]>([])
const draft = reactive<Record<string, { checked: boolean; dataScope: string }>>({})
const dirty = ref(false)

async function select(r: RoleView) {
  current.value = r
  Object.keys(draft).forEach((k) => delete draft[k])
  r.permissions.forEach((p) => (draft[p.permCode] = { checked: true, dataScope: p.dataScope || '' }))
  dirty.value = false
  bindings.value = await api.roleBindings(r.id)
  await loadPoints()
}
async function loadPoints() {
  points.value = await api.appPermissions(app.value)
}
const countByApp = (code: string) => Object.entries(draft).filter(([k, v]) => v.checked && k.startsWith(code + ':')).length

function toggle(code: string, checked: boolean) {
  draft[code] = { checked, dataScope: draft[code]?.dataScope || '' }
  dirty.value = true
}
async function savePerms() {
  const permissions = Object.entries(draft).filter(([, v]) => v.checked)
    .map(([permCode, v]) => ({ permCode, dataScope: v.dataScope || undefined }))
  await api.setRolePermissions(current.value!.id, permissions)
  Message.success('已保存角色权限')
  await load(current.value!.id)
}

const permColumns = computed(() => [
  {
    title: '授予', key: 'checked', width: 70, align: 'center',
    render: (h: any, p: any) => h(resolveComponent('Checkbox'), {
      modelValue: !!draft[p.row.code]?.checked, disabled: !canManage.value || p.row.status === 0,
      'onUpdate:modelValue': (v: boolean) => toggle(p.row.code, v),
    }),
  },
  { title: '权限点', key: 'name', minWidth: 180, render: (h: any, p: any) => h('div', [h('div', p.row.name), h('div', { class: 'pds-mono pds-muted' }, p.row.code)]) },
  { title: '类型', key: 'type', width: 100 },
  {
    title: '风险', key: 'risk', width: 80,
    render: (h: any, p: any) => p.row.risk === 'HIGH' ? h(resolveComponent('Tag'), { color: 'error' }, () => '高') : h('span', { class: 'pds-muted' }, '低'),
  },
  {
    title: '数据范围', key: 'scope', width: 170,
    render: (h: any, p: any) => {
      if (!draft[p.row.code]?.checked) return h('span', { class: 'pds-muted' }, '—')
      const Select = resolveComponent('Select')
      const Option = resolveComponent('Option')
      return h(Select, {
        modelValue: draft[p.row.code].dataScope, size: 'small', clearable: true, transfer: true, placeholder: '默认：本人景区',
        disabled: !canManage.value,
        'onUpdate:modelValue': (v: string) => { draft[p.row.code].dataScope = v || ''; dirty.value = true },
      }, () => Object.entries(DATA_SCOPES).filter(([k]) => !['SCENIC_LIST', 'CUSTOM', 'DOMAIN'].includes(k))
        .map(([k, l]) => h(Option, { value: k, key: k }, () => l)))
    },
  },
])

// ---------- 绑定
const bindings = ref<BindingView[]>([])
const bind = reactive({ open: false, type: 'POSITION', id: '', includeChildren: true, validTo: '' as any })
async function addBinding() {
  if (!bind.id) return Message.warning('请选择授权对象')
  await api.bindRole(current.value!.id, {
    subjectType: bind.type, subjectId: bind.id, includeChildren: bind.includeChildren,
    validTo: bind.validTo ? new Date(bind.validTo).toISOString() : undefined,
  })
  Message.success('已绑定')
  bind.open = false
  bindings.value = await api.roleBindings(current.value!.id)
  load(current.value!.id)
}
function removeBinding(b: BindingView) {
  Modal.confirm({
    title: '解除绑定', content: `解除【${b.subjectName}】与角色【${current.value?.name}】的绑定？`,
    onOk: async () => {
      await api.unbindRole(b.id)
      Message.success('已解除')
      bindings.value = await api.roleBindings(current.value!.id)
    },
  })
}

// ---------- 新建角色
const create = reactive({ open: false, code: '', name: '', description: '' })
async function createRole() {
  if (!create.code || !create.name) return Message.warning('请填写编码与名称')
  const r: any = await api.createRole({ code: create.code, name: create.name, description: create.description || undefined })
  Message.success('已创建，请为角色勾选权限')
  create.open = false
  await load(r.id)
}
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-[300px_1fr]">
    <div class="pds-card">
      <div class="pds-card-title">
        角色
        <Button v-if="canManage" size="small" type="primary" @click="create.open = true"><IconPlus size="small" /> 新建</Button>
      </div>
      <div class="flex flex-col gap-2">
        <div v-for="r in roles" :key="r.id" class="cursor-pointer rounded-2xl border px-4 py-3 transition-colors"
             :class="current?.id === r.id ? 'border-brand bg-brand-soft' : 'border-line hover:bg-page'" @click="select(r)">
          <div class="flex items-center justify-between">
            <span class="flex items-center gap-2 font-medium text-ink">
              <component :is="r.builtin ? IconLock : IconShield" size="small" class="text-brand" />{{ r.name }}
            </span>
            <span class="pds-muted">{{ r.bindingCount }} 个绑定</span>
          </div>
          <div class="pds-muted mt-1">{{ r.permissions.length }} 项权限 · {{ r.code }}</div>
        </div>
      </div>
    </div>

    <div v-if="current" class="pds-card min-w-0">
      <div class="pds-card-title">
        <span class="flex items-center gap-2">
          {{ current.name }}
          <Tag v-if="current.builtin" color="primary">内置</Tag>
          <span class="pds-muted font-normal">{{ current.description }}</span>
        </span>
      </div>
      <Tabs v-model="tab">
        <TabPane label="权限" name="perms">
          <div class="pds-toolbar">
            <RadioGroup v-model="app" type="button" @on-change="loadPoints">
              <Radio v-for="a in apps" :key="a.code" :label="a.code">{{ a.name }}<span class="pds-muted"> {{ countByApp(a.code) }}</span></Radio>
            </RadioGroup>
            <span class="grow" />
            <Button v-if="canManage" type="primary" :disabled="!dirty" @click="savePerms">保存权限</Button>
          </div>
          <Alert v-if="current.builtin" show-icon>内置角色的权限由系统维护，只有安全管理员可以修改。</Alert>
          <Table :columns="permColumns" :data="points" size="small" no-data-text="该应用尚未注册权限清单" />
          <p class="pds-muted mt-3">未设置数据范围时，按"本人所属景区"处理；授予的权限不能超出您自己拥有的权限（安全管理员除外）。</p>
        </TabPane>
        <TabPane :label="`绑定（${bindings.length}）`" name="bindings">
          <div class="pds-toolbar">
            <span class="pds-muted">角色可绑定到人员、岗位、组织（可含下级）或用户组，被覆盖的人员自动获得角色中的权限。</span>
            <span class="grow" />
            <Button v-if="canManage" type="primary" @click="bind.open = true"><IconPlus size="small" /> 新增绑定</Button>
          </div>
          <Table size="small" :data="bindings" no-data-text="暂未绑定" :columns="[
            { title: '对象类型', key: 'subjectType', width: 100, render: (h: any, p: any) => h('span', SUBJECT_TYPES[p.row.subjectType]) },
            { title: '对象', key: 'subjectName', minWidth: 160, render: (h: any, p: any) => h('span', p.row.subjectName + (p.row.includeChildren ? '（含下级）' : '')) },
            { title: '有效期至', key: 'validTo', width: 150, render: (h: any, p: any) => h('span', p.row.validTo ? formatTime(p.row.validTo) : '长期') },
            { title: '授权人', key: 'createdBy', width: 120 },
            { title: '时间', key: 'createdAt', width: 150, render: (h: any, p: any) => h('span', formatTime(p.row.createdAt)) },
            { title: '操作', key: 'op', width: 90, render: (h: any, p: any) => canManage ? h(resolveComponent('Button'), { type: 'text', size: 'small', onClick: () => removeBinding(p.row) }, () => '解除') : null },
          ]" />
        </TabPane>
      </Tabs>
    </div>

    <Modal v-model="bind.open" :title="`绑定角色：${current?.name}`" width="520" @on-ok="addBinding">
      <Form :label-width="80">
        <FormItem label="授权对象"><SubjectPicker v-model:type="bind.type" v-model:id="bind.id" v-model:include-children="bind.includeChildren" /></FormItem>
        <FormItem label="有效期至"><DatePicker v-model="bind.validTo" type="datetime" placeholder="不填为长期" transfer style="width: 100%" /></FormItem>
      </Form>
    </Modal>

    <Modal v-model="create.open" title="新建角色" @on-ok="createRole">
      <Form :label-width="70">
        <FormItem label="编码"><Input v-model="create.code" placeholder="如 PDA_USER" /></FormItem>
        <FormItem label="名称"><Input v-model="create.name" placeholder="如 PDA 普通用户" /></FormItem>
        <FormItem label="说明"><Input v-model="create.description" type="textarea" :rows="2" /></FormItem>
      </Form>
    </Modal>
  </div>
</template>
