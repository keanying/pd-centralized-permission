<script setup lang="ts">
import { computed } from 'vue'
import type { SourceRef } from '@/api'
import { formatTime } from '@/lib/utils'

/** 权限来源标签（设计稿：岗位：售票员 / 角色：PDA普通用户 / 部门：票务部 / 个人直授 / 个人禁止）。 */
const props = defineProps<{ refs: SourceRef[] }>()
const SUBJECT: Record<string, string> = { POSITION: '岗位', ORG: '组织', GROUP: '用户组' }

function label(r: SourceRef) {
  switch (r.kind) {
    case 'DIRECT_ALLOW': return '个人直授'
    case 'DIRECT_DENY': return '个人禁止'
    case 'DISABLED': return '权限点已停用'
    case 'ROLE': return r.subjectType && r.subjectType !== 'USER' ? `${SUBJECT[r.subjectType]}：${r.subjectName}` : `角色：${r.roleName}`
    default: return r.subjectName ? `${SUBJECT[r.subjectType || ''] || '授权'}：${r.subjectName}` : r.text
  }
}
const cls = (r: SourceRef) => (r.kind === 'DIRECT_ALLOW' ? 'dark' : r.kind === 'DIRECT_DENY' || r.kind === 'RESOURCE_DENY' ? 'bad' : 'gray')
const first = computed(() => props.refs[0])
const tip = computed(() => props.refs.map((r) => r.text + (r.validTo ? `，有效期至 ${formatTime(r.validTo)}` : '')).join('\n'))
</script>

<template>
  <Tooltip v-if="first" :content="tip" placement="top" transfer max-width="320" theme="light">
    <span class="flex items-center gap-1">
      <span class="pds-badge min-w-[100px] justify-center" :class="cls(first)">{{ label(first) }}</span>
      <span v-if="refs.length > 1" class="pds-badge gray">+{{ refs.length - 1 }}</span>
    </span>
  </Tooltip>
</template>
