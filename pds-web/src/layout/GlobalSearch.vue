<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { IconSearch } from '@kousum/semi-icons-vue'
import { api, type AppView, type RoleView, type UserView } from '@/api'
import { useSession } from '@/stores/session'

/** 顶栏全局搜索（设计稿："搜索人员、应用、角色"）：输入即搜，↑↓ 选择，回车打开，Esc 关闭。 */
const router = useRouter()
const session = useSession()
const q = ref('')
const open = ref(false)
const users = ref<UserView[]>([])
const apps = ref<AppView[]>([])
const roles = ref<RoleView[]>([])
const active = ref(0)
let timer: number | undefined
let cache: { apps?: AppView[]; roles?: RoleView[] } = {}

watch(q, (v) => {
  window.clearTimeout(timer)
  if (!v.trim()) {
    open.value = false
    return
  }
  timer = window.setTimeout(() => search(v.trim()), 250)
})

async function search(k: string) {
  const lower = k.toLowerCase()
  const tasks: Promise<void>[] = []
  if (session.has('pds:user:view')) tasks.push(api.users({ keyword: k, pageSize: 5 }).then((r) => { users.value = r.items }))
  if (session.has('pds:role:view')) {
    tasks.push((async () => {
      cache.apps ??= await api.apps()
      cache.roles ??= await api.roles()
      apps.value = cache.apps.filter((a) => a.name.toLowerCase().includes(lower) || a.code.includes(lower)).slice(0, 4)
      roles.value = cache.roles.filter((r) => r.name.toLowerCase().includes(lower) || r.code.toLowerCase().includes(lower)).slice(0, 4)
    })())
  }
  await Promise.all(tasks)
  active.value = 0
  open.value = true
}

const items = computed(() => [
  ...users.value.map((u) => ({ group: '人员', label: u.name, sub: `${u.username} · ${u.primaryOrgName || '—'}`, to: { path: '/user-grants', query: { userId: u.id } } })),
  ...apps.value.map((a) => ({ group: '应用', label: a.name, sub: a.code, to: { path: '/apps', query: { app: a.code } } })),
  ...roles.value.map((r) => ({ group: '角色', label: r.name, sub: r.code, to: { path: '/roles', query: { roleId: r.id } } })),
])

function go(i: number) {
  const it = items.value[i]
  if (!it) return
  router.push(it.to)
  q.value = ''
  open.value = false
  cache = {}
}
function closeLater() {
  window.setTimeout(() => (open.value = false), 150)
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') { active.value = Math.min(active.value + 1, items.value.length - 1); e.preventDefault() }
  else if (e.key === 'ArrowUp') { active.value = Math.max(active.value - 1, 0); e.preventDefault() }
  else if (e.key === 'Enter') go(active.value)
  else if (e.key === 'Escape') open.value = false
}
</script>

<template>
  <div class="relative w-[288px]">
    <div class="pds-search white w-full">
      <IconSearch />
      <input v-model="q" placeholder="搜索人员、应用、角色" @keydown="onKey" @focus="q && (open = true)" @blur="closeLater" />
    </div>
    <div v-if="open" class="absolute right-0 top-12 z-50 w-[360px] overflow-hidden rounded-2xl bg-white py-2 shadow-[0_12px_32px_rgba(31,36,33,.14)]">
      <template v-if="items.length">
        <template v-for="(it, i) in items" :key="i">
          <div v-if="i === 0 || items[i - 1].group !== it.group" class="px-4 pb-1 pt-2 text-[11px] text-muted">{{ it.group }}</div>
          <div class="mx-2 flex cursor-pointer items-center justify-between rounded-xl px-3 py-2"
               :class="i === active ? 'bg-row' : ''" @mouseenter="active = i" @mousedown.prevent="go(i)">
            <span class="text-sm text-ink">{{ it.label }}</span>
            <span class="pds-muted">{{ it.sub }}</span>
          </div>
        </template>
      </template>
      <div v-else class="px-4 py-6 text-center text-sm text-muted">没有找到"{{ q }}"相关的人员、应用或角色</div>
    </div>
  </div>
</template>
