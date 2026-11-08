<script setup lang="ts">
import { computed } from 'vue'
import { IconTicketCodeStroked, IconComment, IconShield, IconWrench, IconCreditCard, IconGridView, IconFolderOpen, IconUserGroup } from '@kousum/semi-icons-vue'

/** 组织类型图标（设计稿：集团=楼宇，景区=山峰，部门按名称：票务/服务/安保/运营…）。 */
const props = defineProps<{ type: string; name: string }>()
const deptIcon = computed(() => {
  const n = props.name
  if (/票|售/.test(n)) return IconTicketCodeStroked
  if (/服务|客服|游客/.test(n)) return IconComment
  if (/安保|安全|保卫/.test(n)) return IconShield
  if (/运营|索道|设备|工程|维修/.test(n)) return IconWrench
  if (/财务|结算/.test(n)) return IconCreditCard
  if (/信息|技术|数据/.test(n)) return IconGridView
  return IconFolderOpen
})
</script>

<template>
  <svg v-if="type === 'GROUP' || type === 'COMPANY'" width="16" height="16" viewBox="0 0 24 24" class="text-accent-text" fill="currentColor" aria-hidden="true">
    <path d="M4 21V4.5A1.5 1.5 0 0 1 5.5 3h9A1.5 1.5 0 0 1 16 4.5V9h2.5A1.5 1.5 0 0 1 20 10.5V21h1v1.5H3V21zm3-14v2h2V7zm4 0v2h2V7zm-4 4v2h2v-2zm4 0v2h2v-2zm-4 4v2h2v-2zm4 0v2h2v-2zm5-3v2h2v-2zm0 4v2h2v-2z" />
  </svg>
  <svg v-else-if="type === 'SCENIC'" width="18" height="16" viewBox="0 0 26 22" class="text-accent-text" fill="currentColor" aria-hidden="true">
    <path d="M9 3 1 20h24l-6-9-4 5z" /><circle cx="20" cy="4" r="2.5" />
  </svg>
  <IconUserGroup v-else-if="type === 'TEAM'" size="small" class="text-[#9CA3AF]" />
  <component :is="deptIcon" v-else size="small" class="text-[#9CA3AF]" />
</template>
