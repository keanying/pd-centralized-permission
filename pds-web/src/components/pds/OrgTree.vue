<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { IconChevronRight, IconMore, IconLock, IconHandle } from '@kousum/semi-icons-vue'
import type { OrgNode } from '@/api'
import OrgIcon from './OrgIcon.vue'

/** 设计稿组织树：展开箭头、类型图标、人数（含下级），选中行黄绿底；可管理时悬停显示"⋯"操作。 */
const props = defineProps<{ nodes: OrgNode[]; selectedId?: string; canManage?: boolean }>()
const emit = defineEmits<{ select: [node: OrgNode]; action: [name: string, node: OrgNode]; move: [node: OrgNode, target: OrgNode]; rename: [node: OrgNode, name: string] }>()

// 双击名称就地改名：回车或失焦保存，Esc 取消
const editing = ref('')
const editName = ref('')
function startEdit(n: OrgNode) {
  if (!props.canManage) return
  editing.value = n.id
  editName.value = n.name
  nextTick(() => (document.querySelector('.org-rename-input') as HTMLInputElement | null)?.select())
}
function commitEdit(n: OrgNode) {
  if (editing.value !== n.id) return
  editing.value = ''
  if (editName.value.trim() && editName.value.trim() !== n.name) emit('rename', n, editName.value.trim())
}

// 拖拽移动：拖到目标节点上即移到其下（不能拖到自身或下级）
const dragging = ref<OrgNode | null>(null)
const over = ref('')
function descendants(n: OrgNode, out = new Set<string>()) { out.add(n.id); n.children.forEach((c) => descendants(c, out)); return out }
function onDragStart(e: DragEvent, n: OrgNode) { dragging.value = n; e.dataTransfer?.setData('text/plain', n.id) }
function canDrop(t: OrgNode) { return !!dragging.value && !descendants(dragging.value).has(t.id) && dragging.value.parentId !== t.id }
function onDrop(t: OrgNode) {
  if (dragging.value && canDrop(t)) emit('move', dragging.value, t)
  dragging.value = null
  over.value = ''
}
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
         :class="[r.node.id === selectedId ? 'bg-accent-soft font-semibold text-ink' : 'text-[#1F2421] hover:bg-row', over === r.node.id ? 'ring-2 ring-accent' : '']"
         :style="{ paddingLeft: 6 + r.depth * 18 + 'px' }" :draggable="canManage && r.node.type !== 'GROUP'"
         @dragstart="onDragStart($event, r.node)" @dragend="dragging = null; over = ''"
         @dragover.prevent="canDrop(r.node) && (over = r.node.id)" @dragleave="over === r.node.id && (over = '')" @drop.prevent="onDrop(r.node)"
         @click="emit('select', r.node)">
      <span class="flex size-5 items-center justify-center text-[#6B7280]" @click.stop="r.hasChildren && toggle(r.node.id)">
        <IconChevronRight v-if="r.hasChildren" size="small" class="transition-transform" :class="{ 'rotate-90': expanded.has(r.node.id) }" />
      </span>
      <IconHandle v-if="canManage && r.node.type !== 'GROUP' && r.node.type !== 'SCENIC' && r.depth > 0" size="extra-small" class="hidden cursor-grab text-[#C0C4CC] group-hover:inline" />
      <OrgIcon :type="r.node.type" :name="r.node.name" />
      <input v-if="editing === r.node.id" v-model="editName" class="org-rename-input min-w-0 flex-1 rounded-md border border-solid border-accent bg-white px-1.5 py-0.5 text-[14px] outline-none"
             @click.stop @keydown.enter="commitEdit(r.node)" @keydown.esc="editing = ''" @blur="commitEdit(r.node)" />
      <span v-else class="truncate" :class="{ 'font-semibold': r.depth === 0 || r.node.type === 'SCENIC' }" :title="canManage ? '双击改名' : ''"
            @dblclick.stop="startEdit(r.node)">{{ r.node.name }}</span>
      <span v-if="r.node.inheritFromParent === false && editing !== r.node.id" class="pds-badge warn shrink-0" title="阻断继承：不继承上级组织的节点权限" style="height: 18px; font-size: 11px; padding: 0 5px"><IconLock size="extra-small" class="mr-0.5" />阻断</span>
      <span v-if="editing !== r.node.id" class="flex-1" />
      <Dropdown v-if="canManage" trigger="click" transfer placement="bottom-end" @on-click="(name: string) => (name === 'rename' ? startEdit(r.node) : emit('action', name, r.node))">
        <span class="hidden size-6 items-center justify-center rounded-md text-[#6B7280] hover:bg-white/60 group-hover:flex" @click.stop>
          <IconMore size="small" />
        </span>
        <template #list>
          <DropdownMenu>
            <DropdownItem name="create">新增下级组织</DropdownItem>
            <DropdownItem name="rename">改名</DropdownItem>
            <DropdownItem name="edit">编辑编码 / 负责人 / 上级</DropdownItem>
            <DropdownItem v-if="r.node.type !== 'GROUP'" name="inherit">{{ r.node.inheritFromParent === false ? '恢复继承上级权限' : '阻断继承上级权限' }}</DropdownItem>
            <DropdownItem v-if="r.node.type !== 'GROUP'" name="delete" divided>删除</DropdownItem>
          </DropdownMenu>
        </template>
      </Dropdown>
      <span class="min-w-[36px] text-right text-[12px]" :class="r.node.id === selectedId ? 'text-accent-text' : 'text-[#9CA3AF]'">{{ r.total.toLocaleString('en-US') }}</span>
    </div>
  </div>
</template>
