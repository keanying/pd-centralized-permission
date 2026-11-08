<script setup lang="ts">
import { computed } from 'vue'
import { IconTriangleDown } from '@kousum/semi-icons-vue'

/** 设计稿筛选条的胶囊下拉："景区：西湖景区 ▾"。 */
const model = defineModel<string>({ default: '' })
const props = defineProps<{ label: string; options: { value: string; label: string }[]; allLabel?: string }>()
const current = computed(() => props.options.find((o) => o.value === model.value)?.label ?? props.allLabel ?? '全部')
</script>

<template>
  <Dropdown trigger="click" transfer @on-click="(v: string) => (model = v === '__all' ? '' : v)">
    <button class="pds-pill">{{ label }}：{{ current }} <IconTriangleDown size="extra-small" class="text-[#6B7280]" /></button>
    <template #list>
      <DropdownMenu style="max-height: 320px; overflow: auto">
        <DropdownItem name="__all" :selected="!model">{{ allLabel ?? '全部' }}</DropdownItem>
        <DropdownItem v-for="o in options" :key="o.value" :name="o.value" :selected="model === o.value">{{ o.label }}</DropdownItem>
      </DropdownMenu>
    </template>
  </Dropdown>
</template>
