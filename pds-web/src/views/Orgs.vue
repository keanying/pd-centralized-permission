<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { Message, Modal } from 'view-ui-plus'
import { IconPlus, IconEdit, IconDelete } from '@kousum/semi-icons-vue'
import { api, ORG_TYPES, USER_STATUS, type OrgNode, type Position, type UserView } from '@/api'
import { useSession } from '@/stores/session'

const session = useSession()
const canManage = computed(() => session.has('pds:org:manage'))
const tree = ref<OrgNode[]>([])
const selected = ref<OrgNode | null>(null)
const users = ref<UserView[]>([])
const positions = ref<Position[]>([])
const expanded = new Set<string>()

const TYPE_COLOR: Record<string, string> = { GROUP: 'primary', COMPANY: 'cyan', SCENIC: 'success', DEPT: 'default', TEAM: 'default' }

function toTreeData(nodes: OrgNode[], depth = 0): any[] {
  return nodes.map((n) => ({
    title: n.name, org: n, expand: depth < 2 || expanded.has(n.id), selected: selected.value?.id === n.id,
    children: toTreeData(n.children, depth + 1),
  }))
}
const treeData = computed(() => toTreeData(tree.value))

function renderNode(hh: typeof h, { data }: any) {
  const n: OrgNode = data.org
  return hh('span', { class: 'inline-flex items-center gap-2' }, [
    hh('span', n.name),
    hh('span', { class: 'rounded px-1.5 text-[11px]', style: n.type === 'SCENIC' ? 'background:#E9FBF3;color:#0F7A52' : 'background:#F1F4FA;color:#6B7280' }, ORG_TYPES[n.type]),
    hh('span', { class: 'pds-muted' }, `${n.userCount} 人`),
  ])
}

async function load() {
  tree.value = await api.orgTree()
  if (selected.value) {
    const find = (ns: OrgNode[]): OrgNode | null => ns.reduce<OrgNode | null>((f, n) => f || (n.id === selected.value!.id ? n : find(n.children)), null)
    selected.value = find(tree.value)
  } else if (tree.value.length) {
    select(tree.value[0])
  }
  positions.value = await api.positions()
}

async function select(n: OrgNode) {
  selected.value = n
  expanded.add(n.id)
  users.value = (await api.users({ orgId: n.id, includeChildren: false, pageSize: 100 })).items
}

onMounted(load)

// 新增/编辑组织
const form = reactive({ open: false, mode: 'create' as 'create' | 'edit', type: 'DEPT', code: '', name: '', sort: 0 })
function openCreate() {
  Object.assign(form, { open: true, mode: 'create', type: selected.value?.type === 'GROUP' ? 'SCENIC' : 'DEPT', code: '', name: '', sort: 0 })
}
function openEdit() {
  const n = selected.value!
  Object.assign(form, { open: true, mode: 'edit', type: n.type, code: n.code, name: n.name, sort: n.sort })
}
async function submit() {
  if (!form.name || (form.mode === 'create' && !form.code)) return Message.warning('请填写编码与名称')
  if (form.mode === 'create') {
    await api.createOrg({ parentId: selected.value!.id, type: form.type, code: form.code, name: form.name, sort: form.sort })
    Message.success('已创建')
  } else {
    await api.updateOrg(selected.value!.id, { name: form.name, sort: form.sort })
    Message.success('已保存')
  }
  form.open = false
  await load()
}
function remove() {
  const n = selected.value!
  Modal.confirm({
    title: '删除组织', content: `确定删除【${n.name}】？组织下不能有下级组织和人员。`,
    onOk: async () => {
      await api.deleteOrg(n.id)
      Message.success('已删除')
      selected.value = null
      await load()
    },
  })
}

// 岗位
const pos = reactive({ open: false, code: '', name: '', scenicId: '' })
async function createPosition() {
  if (!pos.code || !pos.name) return Message.warning('请填写岗位编码与名称')
  await api.createPosition({ code: pos.code, name: pos.name, scenicId: pos.scenicId || undefined })
  Message.success('已创建岗位')
  pos.open = false
  positions.value = await api.positions()
}
const scenics = computed(() => {
  const out: OrgNode[] = []
  const walk = (ns: OrgNode[]) => ns.forEach((n) => { if (n.type === 'SCENIC') out.push(n); walk(n.children) })
  walk(tree.value)
  return out
})
const scenicName = (id?: string) => scenics.value.find((s) => s.id === id)?.name || '集团通用'
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-[360px_1fr]">
    <div class="pds-card">
      <div class="pds-card-title">组织树</div>
      <Tree :data="treeData" :render="renderNode" @on-select-change="(_: any, node: any) => node && select(node.org)" />
    </div>

    <div class="flex min-w-0 flex-col gap-5">
      <div v-if="selected" class="pds-card">
        <div class="pds-card-title">
          <span class="flex items-center gap-2">{{ selected.name }} <Tag :color="TYPE_COLOR[selected.type]">{{ ORG_TYPES[selected.type] }}</Tag></span>
          <Space v-if="canManage">
            <Button type="primary" @click="openCreate"><IconPlus size="small" /> 新增下级</Button>
            <Button @click="openEdit"><IconEdit size="small" /> 编辑</Button>
            <Button v-if="selected.type !== 'GROUP'" @click="remove"><IconDelete size="small" /> 删除</Button>
          </Space>
        </div>
        <div class="mb-4 grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
          <div><div class="pds-muted">编码</div>{{ selected.code }}</div>
          <div><div class="pds-muted">所属景区</div>{{ scenicName(selected.scenicId) }}</div>
          <div><div class="pds-muted">直属人员</div>{{ selected.userCount }} 人</div>
          <div><div class="pds-muted">下级组织</div>{{ selected.children.length }} 个</div>
        </div>
        <Table size="small" :data="users" no-data-text="该组织下暂无直属人员" :columns="[
          { title: '姓名', key: 'name', width: 120 },
          { title: '账号', key: 'username', width: 140 },
          { title: '工号', key: 'empNo', width: 120 },
          { title: '岗位', key: 'positions', render: (hh: any, p: any) => hh('span', p.row.positions.join('、') || '—') },
          { title: '状态', key: 'status', width: 90, render: (hh: any, p: any) => hh('span', USER_STATUS[p.row.status]?.label || p.row.status) },
        ]" />
      </div>

      <div class="pds-card">
        <div class="pds-card-title">
          岗位库
          <Button v-if="canManage" size="small" @click="pos.open = true"><IconPlus size="small" /> 新增岗位</Button>
        </div>
        <div class="flex flex-wrap gap-2">
          <Tag v-for="p in positions" :key="p.id" size="large">{{ p.name }} <span class="pds-muted">· {{ scenicName(p.scenicId) }}</span></Tag>
          <span v-if="!positions.length" class="pds-muted">暂无岗位</span>
        </div>
      </div>
    </div>

    <Modal v-model="form.open" :title="form.mode === 'create' ? `在【${selected?.name}】下新增组织` : '编辑组织'" @on-ok="submit" :loading="false">
      <Form :label-width="80">
        <FormItem v-if="form.mode === 'create'" label="类型">
          <RadioGroup v-model="form.type">
            <Radio v-for="t in ['COMPANY', 'SCENIC', 'DEPT', 'TEAM']" :key="t" :label="t">{{ ORG_TYPES[t] }}</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="编码"><Input v-model="form.code" :disabled="form.mode === 'edit'" placeholder="全局唯一，如 S001_TICKET" /></FormItem>
        <FormItem label="名称"><Input v-model="form.name" /></FormItem>
        <FormItem label="排序"><InputNumber v-model="form.sort" :min="0" /></FormItem>
      </Form>
    </Modal>

    <Modal v-model="pos.open" title="新增岗位" @on-ok="createPosition">
      <Form :label-width="80">
        <FormItem label="编码"><Input v-model="pos.code" placeholder="如 SELLER" /></FormItem>
        <FormItem label="名称"><Input v-model="pos.name" placeholder="如 售票员" /></FormItem>
        <FormItem label="所属景区">
          <Select v-model="pos.scenicId" clearable placeholder="不选为集团通用岗位" transfer>
            <Option v-for="s in scenics" :key="s.id" :value="s.id">{{ s.name }}</Option>
          </Select>
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>
