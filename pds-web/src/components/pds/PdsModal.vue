<script setup lang="ts">
import { ref } from 'vue'

/**
 * 统一表单弹窗：点击"确定"执行 onOk；返回 false 或抛出异常时弹窗保持打开（校验失败、接口报错不丢失已填内容），
 * 执行期间按钮显示"处理中"并防止重复提交；点击遮罩不关闭。
 */
const open = defineModel<boolean>({ default: false })
const props = withDefaults(defineProps<{ title: string; width?: number | string; okText?: string; danger?: boolean; hideOk?: boolean;
  onOk?: () => unknown | Promise<unknown> }>(), { width: 520, okText: '确定' })
const busy = ref(false)

async function submit() {
  if (busy.value) return
  busy.value = true
  try {
    const r = await props.onOk?.()
    if (r !== false) open.value = false
  } catch {
    /* 错误已由接口层提示，保持弹窗打开 */
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Modal v-model="open" :title="title" :width="width" :mask-closable="false">
    <slot />
    <template #footer>
      <div class="flex justify-end gap-2">
        <button class="pds-btn ghost sm" @click="open = false">{{ hideOk ? '关闭' : '取消' }}</button>
        <button v-if="!hideOk" class="pds-btn sm" :class="danger ? 'danger' : 'dark'" :disabled="busy" @click="submit">{{ busy ? '处理中…' : okText }}</button>
      </div>
    </template>
  </Modal>
</template>
