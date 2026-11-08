<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, type PermNode, type TreeApp } from '@/api'

/**
 * 权限节点选择（只列出已发布、可授权的节点），按应用分组并按树层级缩进；授予上级节点即包含其非高风险下级。
 */
const model = defineModel<string[]>({ default: () => [] })
const props = defineProps<{ app?: string; placeholder?: string }>()
const nodes = ref<PermNode[]>([])
const apps = ref<TreeApp[]>([])

async function load() {
  const [n, a] = await Promise.all([api.grantableNodes(props.app), api.treeApps()])
  nodes.value = n.filter((x) => x.grantable)
  apps.value = a
}
onMounted(load)
watch(() => props.app, load)

/** 按应用分组，组内按父子顺序排列并计算深度。 */
const groups = computed(() => {
  const byApp = new Map<string, PermNode[]>()
  nodes.value.forEach((n) => byApp.set(n.appCode, [...(byApp.get(n.appCode) || []), n]))
  return [...byApp.entries()].map(([app, list]) => {
    const ids = new Set(list.map((n) => n.id))
    const children = new Map<string, PermNode[]>()
    list.forEach((n) => {
      const p = n.parentId && ids.has(n.parentId) ? n.parentId : '__root'
      children.set(p, [...(children.get(p) || []), n])
    })
    const out: { node: PermNode; depth: number }[] = []
    const walk = (pid: string, depth: number) => (children.get(pid) || [])
      .sort((a, b) => a.sort - b.sort || a.code.localeCompare(b.code))
      .forEach((n) => { out.push({ node: n, depth }); walk(n.id, depth + 1) })
    walk('__root', 0)
    return { app, name: apps.value.find((a) => a.code === app)?.name || app, items: out }
  })
})
</script>

<template>
  <Select v-model="model" multiple filterable :placeholder="placeholder || '搜索名称或编码，可多选'" transfer :max-tag-count="4">
    <OptionGroup v-for="g in groups" :key="g.app" :label="g.name">
      <Option v-for="it in g.items" :key="it.node.code" :value="it.node.code" :label="`${it.node.name}（${it.node.code}）`">
        <span :style="{ paddingLeft: it.depth * 14 + 'px' }">{{ it.node.name }}</span>
        <span class="pds-mono ml-2 text-[#9CA3AF]">{{ it.node.code }}</span>
        <span v-if="it.node.risk === 'HIGH'" class="pds-badge bad ml-2" style="height: 18px">高风险</span>
      </Option>
    </OptionGroup>
  </Select>
</template>
