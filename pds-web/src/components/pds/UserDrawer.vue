<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Message, Modal } from 'view-ui-plus'
import { IconPlus, IconDelete } from '@kousum/semi-icons-vue'
import { api, type OrgNode, type Position, type UserView } from '@/api'
import { formatRelative } from '@/lib/utils'
import OneTimeSecret from './OneTimeSecret.vue'

/**
 * 编辑人员抽屉：基本信息、任职（多部门/多岗位）、账号安全（重置密码、停用/启用、办理离职）。
 * 有未保存修改时关闭会提示。
 */
const open = defineModel<boolean>({ default: false })
const props = defineProps<{ user: UserView | null; orgs: (OrgNode & { depth: number })[]; positions: Position[]; canManage: boolean }>()
const emit = defineEmits<{ changed: [] }>()

const USER_TYPES: Record<string, string> = { FULL_TIME: '正式员工', OUTSOURCE: '劳务派遣', INTERN: '实习生', TEMP: '临时工' }
const base = reactive({ name: '', empNo: '', mobile: '', email: '', userType: 'FULL_TIME' })
const assignments = ref<{ orgId: string; positionId: string; primary: boolean }[]>([])
const snapshot = ref('')
const saving = ref(false)
const secret = reactive({ open: false, value: '' })

watch(() => [open.value, props.user], () => {
  if (!open.value || !props.user) return
  const u = props.user
  Object.assign(base, { name: u.name, empNo: u.empNo || '', mobile: '', email: u.email || '', userType: u.userType })
  assignments.value = [{ orgId: u.primaryOrgId || '', positionId: '', primary: true }]
  snapshot.value = JSON.stringify({ base, a: assignments.value })
}, { immediate: true })

const dirty = computed(() => JSON.stringify({ base, a: assignments.value }) !== snapshot.value)

function close() {
  if (dirty.value) {
    Modal.confirm({ title: '放弃未保存的修改？', content: '关闭后本次修改将丢失。', okText: '放弃修改', onOk: () => (open.value = false) })
  } else {
    open.value = false
  }
}

/** 抽屉的关闭拦截：交给 close() 处理（有未保存修改时确认），阻止组件自行关闭。 */
function beforeClose() {
  close()
  return Promise.reject()
}

async function save() {
  const u = props.user!
  if (!base.name.trim()) return Message.warning('姓名不能为空')
  if (assignments.value.some((a) => !a.orgId)) return Message.warning('请为每条任职选择组织')
  saving.value = true
  try {
    await api.updateUser(u.id, { name: base.name, empNo: base.empNo || undefined, mobile: base.mobile || undefined, email: base.email || undefined, userType: base.userType })
    const origin = JSON.parse(snapshot.value).a
    if (JSON.stringify(origin) !== JSON.stringify(assignments.value)) {
      await api.assignOrgs(u.id, assignments.value.map((a) => ({ orgId: a.orgId, positionId: a.positionId || undefined, primary: a.primary })))
    }
    Message.success('已保存')
    snapshot.value = JSON.stringify({ base, a: assignments.value })
    emit('changed')
  } finally {
    saving.value = false
  }
}

function setPrimary(i: number) {
  assignments.value.forEach((a, j) => (a.primary = i === j))
}

function act(kind: 'reset' | 'disable' | 'enable' | 'leave') {
  const u = props.user!
  const cfg = {
    reset: { title: '重置密码', content: `将为【${u.name}】生成新的初始密码，已登录的会话立即失效，下次登录须修改密码。`, ok: '重置',
      run: async () => { const c = await api.resetPassword(u.id); Object.assign(secret, { open: true, value: c.initialPassword }) } },
    disable: { title: '停用账号', content: `停用后【${u.name}】已登录的会话与令牌立即失效，无法再登录任何应用。`, ok: '停用', run: () => api.disableUser(u.id, '管理员停用') },
    enable: { title: '启用账号', content: `恢复【${u.name}】的登录，并解除锁定。`, ok: '启用', run: () => api.enableUser(u.id) },
    leave: { title: '办理离职', content: `【${u.name}】将标记为离职并立即吊销全部登录，此操作会记入审计。`, ok: '办理离职', run: () => api.leaveUser(u.id, '离职') },
  }[kind]
  Modal.confirm({
    title: cfg.title, content: cfg.content, okText: cfg.ok,
    onOk: async () => { await cfg.run(); if (kind !== 'reset') Message.success('操作成功'); emit('changed') },
  })
}
</script>

<template>
  <Drawer :model-value="open" :title="user ? `编辑人员 · ${user.name}` : ''" width="560"
          :before-close="beforeClose">
    <template v-if="user">
      <div class="mb-5 flex items-center gap-4 rounded-2xl bg-row px-4 py-3">
        <span class="flex size-11 items-center justify-center rounded-full bg-accent text-sm font-semibold text-ink">{{ user.name.slice(-2) }}</span>
        <div class="flex-1">
          <div class="font-semibold text-ink">{{ user.name }} <span class="pds-muted font-normal">{{ user.username }}</span></div>
          <div class="pds-muted">最近登录：{{ formatRelative(user.lastLoginAt) }}</div>
        </div>
        <span class="pds-dot" :class="user.status === 'ACTIVE' ? 'ok' : 'off'">{{ { ACTIVE: '在职', DISABLED: '停用', LEFT: '离职' }[user.status] }}</span>
      </div>

      <div class="pds-card-title">基本信息</div>
      <Form :label-width="72" :disabled="!canManage">
        <div class="grid grid-cols-2 gap-x-4">
          <FormItem label="姓名" required><Input v-model="base.name" /></FormItem>
          <FormItem label="工号"><Input v-model="base.empNo" /></FormItem>
          <FormItem label="手机"><Input v-model="base.mobile" placeholder="不修改请留空" /></FormItem>
          <FormItem label="邮箱"><Input v-model="base.email" /></FormItem>
          <FormItem label="人员类型">
            <Select v-model="base.userType" transfer><Option v-for="(l, k) in USER_TYPES" :key="k" :value="k">{{ l }}</Option></Select>
          </FormItem>
        </div>
      </Form>

      <div class="pds-card-title mt-2">任职 <span class="pds-muted font-normal">调岗后，经岗位、组织获得的权限自动重新计算</span></div>
      <div v-for="(a, i) in assignments" :key="i" class="mb-2 flex items-center gap-2">
        <Select v-model="a.orgId" filterable placeholder="组织" class="flex-1" transfer :disabled="!canManage">
          <Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name"><span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span></Option>
        </Select>
        <Select v-model="a.positionId" clearable placeholder="岗位" style="width: 130px" transfer :disabled="!canManage">
          <Option v-for="p in positions" :key="p.id" :value="p.id">{{ p.name }}</Option>
        </Select>
        <Radio :model-value="a.primary" :disabled="!canManage" @on-change="setPrimary(i)">主部门</Radio>
        <button v-if="canManage && assignments.length > 1" class="pds-link gray" title="移除" @click="assignments.splice(i, 1)"><IconDelete size="small" /></button>
      </div>
      <button v-if="canManage" class="pds-link mt-1" @click="assignments.push({ orgId: '', positionId: '', primary: false })"><IconPlus size="small" /> 增加兼职</button>

      <template v-if="canManage">
        <div class="pds-card-title mt-6">账号安全</div>
        <div class="flex flex-wrap gap-2">
          <button class="pds-btn ghost sm" @click="act('reset')">重置密码</button>
          <button v-if="user.status === 'ACTIVE'" class="pds-btn danger sm" @click="act('disable')">停用账号</button>
          <button v-else class="pds-btn ghost sm" @click="act('enable')">启用账号</button>
          <button v-if="user.status !== 'LEFT'" class="pds-btn danger sm" @click="act('leave')">办理离职</button>
        </div>
      </template>
    </template>
    <div v-if="canManage" class="absolute inset-x-0 bottom-0 flex justify-end gap-2 border-0 border-t border-solid border-[#F0F2EE] bg-white px-6 py-4">
      <button class="pds-btn ghost sm" @click="close">取消</button>
      <button class="pds-btn dark sm" :disabled="!dirty || saving" @click="save">{{ saving ? '保存中…' : '保存修改' }}</button>
    </div>
    <OneTimeSecret v-model="secret.open" :title="`${user?.name} 的新密码`" label="初始密码" :value="secret.value" />
  </Drawer>
</template>
