<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IconExit, IconSetting, IconBell } from '@kousum/semi-icons-vue'
import { menus } from './menu'
import { useSession } from '@/stores/session'
import { logout } from '@/auth/oidc'
import { config } from '@/config'

const route = useRoute()
const router = useRouter()
const session = useSession()

const visibleMenus = computed(() => menus.filter((m) => session.has(...m.perms)))
const isActive = (path: string) => (path === '/' ? route.path === '/' : route.path.startsWith(path))
// 头像文字：人名（≤3 字）取后两字，如"张三""小明"；其他名称取首字
const initial = computed(() => {
  const n = session.me?.name || '?'
  return n.length <= 3 ? n.slice(-2) : n.slice(0, 1)
})
const base = import.meta.env.BASE_URL
</script>

<template>
  <div class="flex h-full gap-5 p-5">
    <!-- 深色图标导航栏 -->
    <aside class="flex w-[84px] shrink-0 flex-col items-center rounded-[26px] bg-ink py-5 text-white shadow-lg">
      <img :src="`${base}favicon.svg`" alt="PDS" class="mb-6 size-11 cursor-pointer rounded-xl" @click="router.push('/')" />
      <nav class="flex w-full flex-1 flex-col items-center gap-1.5 overflow-y-auto px-2">
        <Tooltip v-for="m in visibleMenus" :key="m.path" :content="m.title" placement="right" transfer>
          <router-link
            :to="m.path"
            class="flex w-[68px] flex-col items-center gap-1 rounded-2xl py-2 text-[11px] no-underline transition-colors"
            :class="isActive(m.path) ? 'bg-brand text-white shadow-[0_6px_16px_rgba(30,80,230,.45)]' : 'text-white/65 hover:bg-white/10 hover:text-white'"
          >
            <component :is="m.icon" size="large" />
            <span>{{ m.short }}</span>
          </router-link>
        </Tooltip>
      </nav>
      <div class="flex flex-col items-center gap-2 pt-3">
        <Tooltip content="个人中心" placement="right" transfer>
          <router-link to="/me" class="flex size-11 items-center justify-center rounded-2xl text-white/65 hover:bg-white/10 hover:text-white"
                       :class="{ 'bg-white/15 text-white': route.path === '/me' }">
            <IconSetting size="large" />
          </router-link>
        </Tooltip>
        <Tooltip content="退出登录" placement="right" transfer>
          <button class="flex size-11 cursor-pointer items-center justify-center rounded-2xl border-0 bg-transparent text-white/65 hover:bg-white/10 hover:text-white" @click="logout">
            <IconExit size="large" />
          </button>
        </Tooltip>
        <div class="mt-2 flex size-11 items-center justify-center rounded-full bg-accent text-sm font-semibold text-ink" :title="session.me?.name">
          {{ initial }}
        </div>
      </div>
    </aside>

    <!-- 主区域 -->
    <main class="flex min-w-0 flex-1 flex-col">
      <header class="mb-5 flex items-center justify-between">
        <div>
          <div class="text-xs text-muted">{{ config.title }}</div>
          <h1 class="m-0 text-[22px] font-semibold text-ink">{{ route.meta.title }}</h1>
        </div>
        <div class="flex items-center gap-3">
          <Tooltip content="通知（二期）" placement="bottom">
            <span class="flex size-10 items-center justify-center rounded-full bg-white text-ink shadow-sm"><IconBell /></span>
          </Tooltip>
          <div class="flex items-center gap-2 rounded-full bg-white py-1.5 pl-1.5 pr-4 shadow-sm">
            <span class="flex size-8 items-center justify-center rounded-full bg-brand-soft text-xs font-semibold text-brand">{{ initial }}</span>
            <span class="text-sm text-ink">{{ session.me?.name }}</span>
          </div>
        </div>
      </header>
      <section class="min-h-0 flex-1 overflow-y-auto pb-6">
        <router-view />
      </section>
    </main>
  </div>
</template>
