<script setup lang="ts">
import { Message } from 'view-ui-plus'

/** 一次性展示的密码/密钥（关闭后无法再次查看）。 */
const open = defineModel<boolean>({ default: false })
defineProps<{ title: string; label: string; value?: string; tip?: string }>()

async function copy(v?: string) {
  if (!v) return
  await navigator.clipboard.writeText(v)
  Message.success('已复制')
}
</script>

<template>
  <Modal v-model="open" :title="title" :mask-closable="false" :closable="false">
    <div class="rounded-xl bg-warn-soft-2 px-4 py-3 text-[13px] text-[#92400E]">{{ tip || '请立即复制并安全地交给使用者，关闭后将无法再次查看。' }}</div>
    <div class="mt-4 text-[13px] text-muted">{{ label }}</div>
    <div class="mt-1 flex items-center gap-2">
      <Input :model-value="value" readonly class="pds-mono" />
      <button class="pds-btn dark sm" @click="copy(value)">复制</button>
    </div>
    <template #footer><div class="flex justify-end"><button class="pds-btn accent sm" @click="open = false">我已妥善保存</button></div></template>
  </Modal>
</template>
