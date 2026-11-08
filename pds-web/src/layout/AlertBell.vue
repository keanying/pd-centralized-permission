<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { IconBell } from '@kousum/semi-icons-vue'
import { api, EVENT_TYPES, type AuditEvent } from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'

/** 顶栏通知（设计稿右上角铃铛，红点表示今日有越权拦截或登录失败）。仅审计管理员可见明细。 */
const router = useRouter()
const session = useSession()
const alerts = ref<AuditEvent[]>([])
const open = ref(false)
let timer: number | undefined

async function load() {
  if (!session.has('pds:audit:view')) return
  alerts.value = (await api.overview()).recentAlerts
}
onMounted(() => {
  load()
  timer = window.setInterval(load, 120_000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="relative" @mouseleave="open = false">
    <button class="relative flex size-11 cursor-pointer items-center justify-center rounded-full border-0 bg-white text-ink" @click="open = !open" aria-label="通知">
      <IconBell />
      <span v-if="alerts.length" class="absolute right-[11px] top-[10px] size-2 rounded-full bg-danger" />
    </button>
    <div v-if="open" class="absolute right-0 top-12 z-50 w-[340px] rounded-2xl bg-white p-4 shadow-[0_12px_32px_rgba(31,36,33,.14)]">
      <div class="mb-2 flex items-center justify-between">
        <span class="text-sm font-semibold text-ink">今日安全事件</span>
        <button v-if="session.has('pds:audit:view')" class="pds-link" @click="router.push('/audit?view=all&eventType=ACCESS_DENIED,LOGIN_FAIL'); open = false">查看全部</button>
      </div>
      <div v-for="a in alerts" :key="a.id" class="flex items-start justify-between gap-3 border-0 border-b border-solid border-[#F0F2EE] py-2 last:border-b-0">
        <div class="min-w-0">
          <div class="truncate text-[13px] text-ink">{{ a.userName || a.userId || '未知' }} · {{ a.action }}</div>
          <div class="pds-muted">{{ formatTime(a.ts) }}</div>
        </div>
        <span class="pds-badge" :class="a.eventType === 'ACCESS_DENIED' ? 'bad' : 'warn'">{{ EVENT_TYPES[a.eventType] }}</span>
      </div>
      <div v-if="!alerts.length" class="py-6 text-center text-sm text-muted">{{ session.has('pds:audit:view') ? '今日暂无安全事件' : '暂无通知' }}</div>
    </div>
  </div>
</template>
