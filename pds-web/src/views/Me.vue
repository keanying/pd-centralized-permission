<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Message } from 'view-ui-plus'
import { api } from '@/api'
import { useSession } from '@/stores/session'
import { claims, logout } from '@/auth/oidc'

const session = useSession()
const c = computed(() => claims())
const pw = reactive({ old: '', next: '', confirm: '' })

async function change() {
  if (!pw.old || !pw.next) return Message.warning('请填写原密码与新密码')
  if (pw.next !== pw.confirm) return Message.warning('两次输入的新密码不一致')
  await api.changePassword(pw.old, pw.next)
  Message.success('密码已修改')
  Object.assign(pw, { old: '', next: '', confirm: '' })
}
</script>

<template>
  <div class="grid grid-cols-1 gap-5 xl:grid-cols-2">
    <div class="pds-card">
      <div class="pds-card-title">我的信息</div>
      <div class="grid grid-cols-2 gap-4 text-sm">
        <div><div class="pds-muted">姓名</div>{{ session.me?.name }}</div>
        <div><div class="pds-muted">账号</div>{{ session.me?.username }}</div>
        <div><div class="pds-muted">工号</div>{{ c.emp_no || '—' }}</div>
        <div><div class="pds-muted">人员 ID</div><span class="pds-mono">{{ session.me?.id }}</span></div>
        <div><div class="pds-muted">景区范围</div>{{ (c.scenic_scope || []).join('、') || '—' }}</div>
        <div><div class="pds-muted">令牌有效期至</div>{{ c.exp ? new Date(c.exp * 1000).toLocaleString('zh-CN') : '—' }}</div>
      </div>
      <div class="pds-card-title mt-6">我在 PDS 中的权限</div>
      <div class="flex flex-wrap gap-2">
        <Tag v-for="p in session.me?.pdsPermissions" :key="p" class="pds-mono">{{ p }}</Tag>
        <span v-if="!session.me?.pdsPermissions.length" class="pds-muted">无</span>
      </div>
      <Button class="mt-6" @click="logout">退出登录（单点登出）</Button>
    </div>
    <div class="pds-card">
      <div class="pds-card-title">修改密码</div>
      <Form :label-width="90" style="max-width: 420px">
        <FormItem label="原密码"><Input v-model="pw.old" type="password" password /></FormItem>
        <FormItem label="新密码"><Input v-model="pw.next" type="password" password /></FormItem>
        <FormItem label="确认新密码"><Input v-model="pw.confirm" type="password" password /></FormItem>
        <FormItem><Button type="primary" @click="change">确认修改</Button></FormItem>
      </Form>
      <p class="pds-muted">至少 8 位，包含大写字母、小写字母、数字、特殊字符中的至少 3 类，且不能包含账号。</p>
    </div>
  </div>
</template>
