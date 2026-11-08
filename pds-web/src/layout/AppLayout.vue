<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconExit, IconSetting } from '@kousum/semi-icons-vue'
import { Modal } from 'view-ui-plus'
import { menus } from './menu'
import GlobalSearch from './GlobalSearch.vue'
import AlertBell from './AlertBell.vue'
import { useSession } from '@/stores/session'
import { usePage } from '@/stores/page'
import { logout } from '@/auth/oidc'

const route = useRoute()
const router = useRouter()
const session = useSession()
const page = usePage()

const visibleMenus = computed(() => menus.filter((m) => session.has(...m.perms)))
const isActive = (m: { path: string; match?: string[] }) =>
  m.path === '/' ? route.path === '/' : [m.path, ...(m.match ?? [])].some((p) => route.path.startsWith(p))

// 路由切换时重置为路由默认标题；页面可通过 usePage().set() 覆盖
watch(() => route.fullPath, () => page.set((route.meta.title as string) || ''), { immediate: true })

// 头像文字：人名（≤3 字）取后两字，其他名称取首字
const initial = computed(() => {
  const n = session.me?.name || '?'
  return n.length <= 3 ? n.slice(-2) : n.slice(0, 1)
})

function confirmLogout() {
  Modal.confirm({ title: '退出登录', content: '将同时退出统一认证中心，访问其他应用需重新登录。', okText: '退出', onOk: logout })
}
</script>

<template>
  <div class="flex min-h-full gap-5 p-5">
    <!-- 侧栏：深炭灰胶囊，纯图标，选中为黄绿实心圆；页面随浏览器滚动，侧栏固定 -->
    <aside class="sticky top-5 flex h-[calc(100vh-40px)] w-[76px] shrink-0 flex-col items-center self-start rounded-[38px] bg-ink py-6">
      <button class="mb-8 flex size-11 cursor-pointer items-center justify-center rounded-full border-0 bg-accent text-base font-bold text-ink" title="PDS 统一权限" @click="router.push('/')">P</button>
      <nav class="flex flex-1 flex-col items-center gap-3">
        <Tooltip v-for="m in visibleMenus" :key="m.path" :content="m.title" placement="right" transfer>
          <router-link :to="m.path" class="flex size-11 items-center justify-center rounded-full transition-colors"
                       :class="isActive(m) ? 'bg-accent text-ink' : 'text-[#C9CCCB] hover:bg-white/10 hover:text-white'">
            <component :is="m.icon" />
          </router-link>
        </Tooltip>
        <Tooltip content="个人设置" placement="right" transfer>
          <router-link to="/me" class="flex size-11 items-center justify-center rounded-full transition-colors"
                       :class="route.path === '/me' ? 'bg-accent text-ink' : 'text-[#C9CCCB] hover:bg-white/10 hover:text-white'">
            <IconSetting />
          </router-link>
        </Tooltip>
      </nav>
      <div class="flex flex-col items-center gap-4">
        <Tooltip content="退出登录" placement="right" transfer>
          <button class="flex size-11 cursor-pointer items-center justify-center rounded-full border-0 bg-transparent text-[#C9CCCB] hover:bg-white/10 hover:text-white" @click="confirmLogout">
            <IconExit />
          </button>
        </Tooltip>
        <Tooltip :content="session.me?.name" placement="right" transfer>
          <div class="flex size-11 items-center justify-center rounded-full bg-accent-soft text-sm font-semibold text-ink ring-2 ring-white/20">{{ initial }}</div>
        </Tooltip>
      </div>
    </aside>

    <main class="flex min-w-0 flex-1 flex-col">
      <header class="mb-5 flex min-h-[48px] items-start justify-between gap-6">
        <div class="min-w-0">
          <h1 class="m-0 truncate text-[24px] font-bold leading-tight text-ink">
            {{ page.title }}<span v-if="page.inline" class="ml-2 text-[14px] font-normal text-muted">{{ page.inline }}</span>
          </h1>
          <div v-if="page.subtitle" class="mt-1 text-[14px] text-muted">{{ page.subtitle }}</div>
        </div>
        <div class="flex shrink-0 items-center gap-3">
          <GlobalSearch />
          <AlertBell />
        </div>
      </header>
      <section class="flex-1 pb-2">
        <router-view />
      </section>
    </main>
  </div>
</template>
