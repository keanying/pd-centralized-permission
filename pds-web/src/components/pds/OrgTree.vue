<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { IconChevronRight, IconMore } from '@kousum/semi-icons-vue'
import type { OrgNode } from '@/api'
import OrgIcon from './OrgIcon.vue'

/** 设计稿组织树：展开箭头、类型图标、人数（含下级），选中行黄绿底；可管理时悬停显示"⋯"操作。 */
const props = defineProps<{ nodes: OrgNode[]; selectedId?: string; canManage?: boolean }>()
const emit = defineEmits<{ select: [node: OrgNode]; action: [name: string, node: OrgNode] }>()
const expanded = reactive(new Set<string>())

interface Row { node: OrgNode; depth: number; total: number; hasChildren: boolean }
function total(n: OrgNode): number {
  return n.userCount + n.children.reduce((s, c) => s + total(c), 0)
}
// 默认展开前两层，以及选中节点的上级链
watch(() => [props.nodes, props.selectedId], () => {
  const walk = (ns: OrgNode[], depth: number, path: string[]): boolean => {
    let found = false
    for (const n of ns) {
      if (depth < 2) expanded.add(n.id)
      const hit = n.id === props.selectedId || walk(n.children, depth + 1, [...path, n.id])
      if (hit) { path.forEach((p) => expanded.add(p)); expanded.add(n.id); found = true }
    }
    return found
  }
  walk(props.nodes, 0, [])
}, { immediate: true })

const rows = computed(() => {
  const out: Row[] = []
  const walk = (ns: OrgNode[], depth: number) => ns.forEach((n) => {
    out.push({ node: n, depth, total: total(n), hasChildren: n.children.length > 0 })
    if (expanded.has(n.id)) walk(n.children, depth + 1)
  })
  walk(props.nodes, 0)
  return out
})
const toggle = (id: string) => (expanded.has(id) ? expanded.delete(id) : expanded.add(id))
</script>

<template>
  <div class="flex flex-col gap-1">
    <div v-for="r in rows" :key="r.node.id" class="group flex h-10 cursor-pointer items-center gap-1.5 rounded-xl pr-2 text-[14px] transition-colors"
         :class="r.node.id === selectedId ? 'bg-accent font-medium text-ink' : 'text-[#1F2421] hover:bg-row'"
         :style="{ paddingLeft: 6 + r.depth * 18 + 'px' }" @click="emit('select', r.node)">
      <span class="flex size-5 items-center justify-center text-[#6B7280]" @click.stop="r.hasChildren && toggle(r.node.id)">
        <IconChevronRight v-if="r.hasChildren" size="small" class="transition-transform" :class="{ 'rotate-90': expanded.has(r.node.id) }" />
      </span>
      <OrgIcon :type="r.node.type" :name="r.node.name" />
      <span class="flex-1 truncate" :class="{ 'font-semibold': r.depth === 0 || r.node.type === 'SCENIC' }">{{ r.node.name }}</span>
      <Dropdown v-if="canManage" trigger="click" transfer placement="bottom-end" @on-click="(name: string) => emit('action', name, r.node)">
        <span class="hidden size-6 items-center justify-center rounded-md text-[#6B7280] hover:bg-white/60 group-hover:flex" @click.stop>
          <IconMore size="small" />
        </span>
        <template #list>
          <DropdownMenu>
            <DropdownItem name="create">新增下级组织</DropdownItem>
            <DropdownItem name="edit">编辑 / 移动</DropdownItem>
            <DropdownItem v-if="r.node.type !== 'GROUP'" name="delete" divided>删除</DropdownItem>
          </DropdownMenu>
        </template>
      </Dropdown>
      <span class="min-w-[36px] text-right text-[12px]" :class="r.node.id === selectedId ? 'text-ink' : 'text-[#9CA3AF]'">{{ r.total.toLocaleString('en-US') }}</span>
    </div>
  </div>
</template>
