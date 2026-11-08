<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, type UserView } from '@/api'

/** 人员远程搜索选择（按姓名/账号/工号）。 */
const model = defineModel<string | string[]>()
defineProps<{ multiple?: boolean; placeholder?: string }>()
const options = ref<UserView[]>([])
const loading = ref(false)

async function search(q: string) {
  loading.value = true
  try {
    options.value = (await api.users({ keyword: q, pageSize: 20 })).items
  } finally {
    loading.value = false
  }
}
search('')

// 预置的人员 ID 不在当前选项中时补充加载，保证显示姓名；只有补充加载后才重建下拉（避免选择时重建导致弹层报错、关闭）
const ver = ref(0)
watch(model, async (v) => {
  const ids = Array.isArray(v) ? v : v ? [v] : []
  let loaded = false
  for (const id of ids) {
    if (!options.value.some((o) => o.id === id)) {
      try {
        options.value = [await api.user(id), ...options.value]
        loaded = true
      } catch {
        /* 无权查看时忽略 */
      }
    }
  }
  if (loaded) ver.value++
}, { immediate: true })
const defaultLabel = computed(() => {
  const ids = Array.isArray(model.value) ? model.value : model.value ? [model.value] : []
  const labels = ids.map((id) => options.value.find((o) => o.id === id)).filter(Boolean).map((u) => `${u!.name}（${u!.username}）`)
  return Array.isArray(model.value) ? labels : labels[0] || ''
})
defineExpose({ options })
</script>

<template>
  <Select :key="ver" v-model="model" filterable remote :remote-method="search" :loading="loading" :multiple="multiple"
          :default-label="defaultLabel"
          :placeholder="placeholder || '输入姓名、账号或工号搜索'" clearable transfer>
    <Option v-for="u in options" :key="u.id" :value="u.id" :label="`${u.name}（${u.username}）`">
      {{ u.name }} <span class="pds-muted">{{ u.username }} · {{ u.primaryOrgName || '—' }}</span>
    </Option>
  </Select>
</template>
