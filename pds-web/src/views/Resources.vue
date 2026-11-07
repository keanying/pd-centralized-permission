<script setup lang="ts">
import { computed, onMounted, reactive, ref, resolveComponent } from 'vue'
import { Message, Modal } from 'view-ui-plus'
import { IconPlus, IconSearch } from '@kousum/semi-icons-vue'
import { api, ACTIONS, SUBJECT_TYPES, type GrantView, type ResourceType, type ResourceView } from '@/api'
import { formatTime } from '@/lib/utils'
import SubjectPicker from '@/components/pds/SubjectPicker.vue'

const types = ref<ResourceType[]>([])
const query = reactive({ type: '', keyword: '', page: 1, pageSize: 20 })
const rows = ref<ResourceView[]>([])
const total = ref(0)
const typeName = (code: string) => types.value.find((t) => t.typeCode === code)?.name || code

async function load() {
  const r = await api.resources({ ...query, type: query.type || undefined })
  rows.value = r.items
  total.value = r.total
}
onMounted(async () => {
  types.value = await api.resourceTypes()
  load()
})

// 授权抽屉
const drawer = reactive({ open: false, res: null as ResourceView | null, grants: [] as GrantView[] })
async function openGrants(r: ResourceView) {
  drawer.res = r
  drawer.grants = await api.resourceGrants(r.type, r.refId)
  drawer.open = true
}
const form = reactive({ open: false, type: 'ORG', id: '', includeChildren: true, action: 'READ', effect: 'ALLOW', validTo: '' as any, reason: '' })
async function addGrant() {
  if (!form.id) return Message.warning('请选择授权对象')
  const r = drawer.res!
  await api.addResourceGrant(r.type, r.refId, {
    subjectType: form.type, subjectId: form.id, includeChildren: form.includeChildren, action: form.action, effect: form.effect,
    validTo: form.validTo ? new Date(form.validTo).toISOString() : undefined, reason: form.reason || undefined,
  })
  Message.success('已授权')
  form.open = false
  drawer.grants = await api.resourceGrants(r.type, r.refId)
  load()
}
function removeGrant(g: GrantView) {
  const r = drawer.res!
  Modal.confirm({
    title: '撤销授权', content: `撤销【${g.subjectName}】对【${r.name}】的${ACTIONS[g.action]}${g.effect === 'DENY' ? '禁止' : '授权'}？`,
    onOk: async () => {
      await api.removeResourceGrant(r.type, r.refId, g.id)
      Message.success('已撤销')
      drawer.grants = await api.resourceGrants(r.type, r.refId)
      load()
    },
  })
}

const columns = computed(() => [
  { title: '资源', key: 'name', minWidth: 200, render: (h: any, p: any) => h('div', [h('div', p.row.name), h('div', { class: 'pds-mono pds-muted' }, p.row.refId)]) },
  { title: '类型', key: 'type', width: 160, render: (h: any, p: any) => h('span', typeName(p.row.type)) },
  { title: '所属', key: 'scenicName', width: 140 },
  { title: '授权条数', key: 'grantCount', width: 100 },
  { title: '更新时间', key: 'updatedAt', width: 160, render: (h: any, p: any) => h('span', formatTime(p.row.updatedAt)) },
  { title: '操作', key: 'op', width: 110, render: (h: any, p: any) => h(resolveComponent('Button'), { type: 'text', size: 'small', onClick: () => openGrants(p.row) }, () => '授权管理') },
])
</script>

<template>
  <div class="pds-card">
    <div class="pds-toolbar">
      <Select v-model="query.type" clearable placeholder="资源类型" style="width: 220px" @on-change="() => { query.page = 1; load() }">
        <Option v-for="t in types" :key="t.typeCode" :value="t.typeCode">{{ t.name }} <span class="pds-muted">{{ t.typeCode }}</span></Option>
      </Select>
      <Input v-model="query.keyword" placeholder="资源名称或 ID" style="width: 220px" clearable @on-enter="load"><template #prefix><IconSearch /></template></Input>
      <Button type="primary" ghost @click="load">查询</Button>
      <span class="grow" />
      <span class="pds-muted">资源实例由各接入系统注册（如 PDK 知识库、PDD 数据集、PBI 看板）</span>
    </div>
    <Table :columns="columns" :data="rows" no-data-text="暂无资源" />
    <div class="mt-4 flex justify-end"><Page :total="total" v-model="query.page" :page-size="query.pageSize" show-total @on-change="load" /></div>

    <Drawer v-model="drawer.open" :title="`授权管理：${drawer.res?.name}`" width="760">
      <div class="pds-toolbar">
        <span class="pds-muted">{{ drawer.res && typeName(drawer.res.type) }} · {{ drawer.res?.scenicName }} · 查看 ⊂ 编辑 ⊂ 管理；禁止优先</span>
        <span class="grow" />
        <Button type="primary" @click="Object.assign(form, { open: true, id: '', reason: '', validTo: '' })"><IconPlus size="small" /> 新增授权</Button>
      </div>
      <Table size="small" :data="drawer.grants" no-data-text="尚未授权" :columns="[
        { title: '对象', key: 'subjectName', minWidth: 160, render: (h: any, p: any) => h('div', [h('div', p.row.subjectName + (p.row.includeChildren ? '（含下级）' : '')), h('div', { class: 'pds-muted' }, SUBJECT_TYPES[p.row.subjectType])]) },
        { title: '动作', key: 'action', width: 80, render: (h: any, p: any) => h('span', ACTIONS[p.row.action]) },
        { title: '效果', key: 'effect', width: 80, render: (h: any, p: any) => h(resolveComponent('Tag'), { color: p.row.effect === 'ALLOW' ? 'success' : 'error' }, () => (p.row.effect === 'ALLOW' ? '允许' : '禁止')) },
        { title: '有效期至', key: 'validTo', width: 130, render: (h: any, p: any) => h('span', p.row.validTo ? formatTime(p.row.validTo) : '长期') },
        { title: '授权人', key: 'grantedByName', width: 100 },
        { title: '操作', key: 'op', width: 80, render: (h: any, p: any) => h(resolveComponent('Button'), { type: 'text', size: 'small', onClick: () => removeGrant(p.row) }, () => '撤销') },
      ]" />
    </Drawer>

    <Modal v-model="form.open" :title="`新增授权：${drawer.res?.name}`" width="520" @on-ok="addGrant">
      <Form :label-width="80">
        <FormItem label="授权对象"><SubjectPicker v-model:type="form.type" v-model:id="form.id" v-model:include-children="form.includeChildren" /></FormItem>
        <FormItem label="动作">
          <RadioGroup v-model="form.action" type="button"><Radio v-for="(l, k) in ACTIONS" :key="k" :label="k">{{ l }}</Radio></RadioGroup>
        </FormItem>
        <FormItem label="效果">
          <RadioGroup v-model="form.effect"><Radio label="ALLOW">允许</Radio><Radio label="DENY">禁止</Radio></RadioGroup>
        </FormItem>
        <FormItem label="有效期至"><DatePicker v-model="form.validTo" type="datetime" placeholder="不填为长期" transfer style="width: 100%" /></FormItem>
        <FormItem label="原因"><Input v-model="form.reason" placeholder="选填，记入审计" /></FormItem>
      </Form>
    </Modal>
  </div>
</template>
