<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Message, Modal } from 'view-ui-plus'
import { api } from '@/api'
import { useSession } from '@/stores/session'
import { claims, logout } from '@/auth/oidc'
import { formatTime } from '@/lib/utils'

const session = useSession()
const c = computed(() => claims())
const pw = reactive({ old: '', next: '', confirm: '', busy: false })
const rules = computed(() => {
  const v = pw.next
  const classes = [/[A-Z]/, /[a-z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(v)).length
  return [
    { ok: v.length >= 8, text: '至少 8 位' },
    { ok: classes >= 3, text: '大写、小写、数字、特殊字符至少 3 类' },
    { ok: !!v && !v.toLowerCase().includes((session.me?.username || '').toLowerCase()), text: '不包含账号' },
    { ok: !!v && v === pw.confirm, text: '两次输入一致' },
  ]
})
async function change() {
  if (!pw.old) return Message.warning('请输入原密码')
  if (rules.value.some((r) => !r.ok)) return Message.warning('新密码不符合要求')
  pw.busy = true
  try {
    await api.changePassword(pw.old, pw.next)
    Message.success('密码已修改')
    Object.assign(pw, { old: '', next: '', confirm: '' })
  } finally {
    pw.busy = false
  }
}
function confirmLogout() {
  Modal.confirm({ title: '退出登录', content: '将同时退出统一认证中心，访问其他应用需重新登录。', okText: '退出', onOk: logout })
}
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-2">
    <div class="pds-card">
      <div class="mb-6 flex items-center gap-4">
        <span class="flex size-14 items-center justify-center rounded-full bg-accent text-[18px] font-bold text-ink">{{ session.me?.name.slice(-2) }}</span>
        <div><div class="text-[18px] font-semibold text-ink">{{ session.me?.name }}</div><div class="pds-muted">{{ session.me?.username }}</div></div>
      </div>
      <div class="grid grid-cols-2 gap-3 text-[14px]">
        <div class="rounded-xl bg-row px-4 py-3"><div class="pds-muted">工号</div>{{ c.emp_no || '—' }}</div>
        <div class="rounded-xl bg-row px-4 py-3"><div class="pds-muted">人员 ID</div><span class="pds-mono">{{ session.me?.id }}</span></div>
        <div class="rounded-xl bg-row px-4 py-3"><div class="pds-muted">景区范围</div>{{ (c.scenic_scope || []).length ? `${c.scenic_scope.length} 个景区` : '仅集团共享' }}</div>
        <div class="rounded-xl bg-row px-4 py-3"><div class="pds-muted">登录有效期至</div>{{ c.exp ? formatTime(new Date(c.exp * 1000).toISOString()) : '—' }}</div>
      </div>
      <div class="pds-card-title mt-6">我在 PDS 中的权限</div>
      <div class="flex flex-wrap gap-2">
        <span v-for="p in session.me?.pdsPermissions" :key="p" class="pds-badge gray pds-mono">{{ p }}</span>
        <span v-if="!session.me?.pdsPermissions.length" class="pds-muted">无</span>
      </div>
      <button class="pds-btn danger mt-6" @click="confirmLogout">退出登录（单点登出）</button>
    </div>
    <div class="pds-card">
      <div class="pds-card-title">修改密码</div>
      <Form :label-width="90" style="max-width: 440px" @submit.prevent>
        <FormItem label="原密码"><Input v-model="pw.old" type="password" password /></FormItem>
        <FormItem label="新密码"><Input v-model="pw.next" type="password" password /></FormItem>
        <FormItem label="确认新密码"><Input v-model="pw.confirm" type="password" password @on-enter="change" /></FormItem>
      </Form>
      <div class="mb-5 ml-[90px] flex flex-col gap-1.5">
        <span v-for="r in rules" :key="r.text" class="pds-dot" :class="r.ok ? 'ok' : 'off'">{{ r.text }}</span>
      </div>
      <button class="pds-btn dark ml-[90px]" :disabled="pw.busy" @click="change">{{ pw.busy ? '提交中…' : '确认修改' }}</button>
    </div>
  </div>
</template>
