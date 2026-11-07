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
  <Modal v-model="open" :title="title" :mask-closable="false" footer-hide>
    <Alert type="warning" show-icon>{{ tip || '请立即复制并安全地交给使用者，关闭后将无法再次查看。' }}</Alert>
    <div class="mt-3 text-sm text-muted">{{ label }}</div>
    <div class="mt-1 flex items-center gap-2">
      <Input :model-value="value" readonly class="pds-mono" />
      <Button type="primary" @click="copy(value)">复制</Button>
    </div>
    <div class="mt-5 text-right"><Button @click="open = false">我已妥善保存</Button></div>
  </Modal>
</template>
