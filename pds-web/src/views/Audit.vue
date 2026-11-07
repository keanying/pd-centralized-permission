<script setup lang="ts">
import { onMounted, reactive, ref, resolveComponent } from 'vue'
import { IconSafe } from '@kousum/semi-icons-vue'
import { api, EVENT_TYPES, type AuditEvent } from '@/api'
import { formatTime } from '@/lib/utils'

const query = reactive({ eventType: '', app: '', result: '', userId: '', range: [] as any[], page: 1, pageSize: 20 })
const rows = ref<AuditEvent[]>([])
const total = ref(0)
const loading = ref(false)
const verify = ref<{ intact: boolean; message: string } | null>(null)

async function load() {
  loading.value = true
  try {
    const [from, to] = query.range ?? []
    const r = await api.audit({
      eventType: query.eventType || undefined, app: query.app || undefined, result: query.result || undefined,
      userId: query.userId || undefined, from: from ? new Date(from).toISOString() : undefined,
      to: to ? new Date(to).toISOString() : undefined, page: query.page, pageSize: query.pageSize,
    })
    rows.value = r.items
    total.value = r.total
  } finally {
    loading.value = false
  }
}
onMounted(load)
async function runVerify() {
  verify.value = await api.auditVerify()
}

const RESULT: Record<string, { label: string; color: string }> = {
  SUCCESS: { label: '成功', color: 'success' }, ALLOW: { label: '允许', color: 'success' },
  FAILURE: { label: '失败', color: 'warning' }, DENY: { label: '拦截', color: 'error' },
}
const pretty = (s?: string) => { try { return s ? JSON.stringify(JSON.parse(s), null, 2) : '' } catch { return s } }

const columns = [
  {
    type: 'expand', width: 44,
    render: (h: any, p: any) => h('div', { class: 'grid grid-cols-2 gap-4 text-xs' }, [
      h('div', [h('div', { class: 'pds-muted mb-1' }, '详情'), h('pre', { class: 'pds-mono m-0 whitespace-pre-wrap' }, pretty(p.row.detail) || '—')]),
      h('div', { class: 'pds-mono break-all' }, [
        h('div', `事件 ID：${p.row.id}（序号 ${p.row.seq}）`), h('div', `对象：${p.row.targetType || '—'} ${p.row.targetIds || ''}`),
        h('div', `代理方：${p.row.via || '—'}`), h('div', `哈希：${p.row.hash}`), h('div', `前序：${p.row.prevHash}`),
      ]),
    ]),
  },
  { title: '时间', key: 'ts', width: 150, render: (h: any, p: any) => h('span', formatTime(p.row.ts)) },
  { title: '应用', key: 'app', width: 70 },
  { title: '类型', key: 'eventType', width: 110, render: (h: any, p: any) => h('span', EVENT_TYPES[p.row.eventType] || p.row.eventType) },
  {
    title: '人员', key: 'userName', width: 150,
    render: (h: any, p: any) => {
      const id: string = p.row.userId || ''
      if (id.startsWith('client:')) return h('span', { class: 'pds-muted' }, `服务·${id.slice(7)}`)
      return h('span', p.row.userName || id || '—')
    },
  },
  { title: '内容', key: 'action', minWidth: 200 },
  { title: '结果', key: 'result', width: 80, render: (h: any, p: any) => h(resolveComponent('Tag'), { color: RESULT[p.row.result]?.color }, () => RESULT[p.row.result]?.label || p.row.result) },
  { title: 'IP', key: 'ip', width: 120 },
]
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="pds-card flex flex-wrap items-center gap-4">
      <span class="flex size-11 items-center justify-center rounded-xl bg-brand-soft text-brand"><IconSafe size="large" /></span>
      <div class="flex-1">
        <div class="font-semibold text-ink">审计日志只追加、不可删除</div>
        <div class="pds-muted">每条记录与前一条哈希相连，任何篡改都会在完整性校验中暴露。</div>
      </div>
      <Alert v-if="verify" :type="verify.intact ? 'success' : 'error'" show-icon class="mb-0">{{ verify.message }}</Alert>
      <Button type="primary" @click="runVerify">完整性校验</Button>
    </div>

    <div class="pds-card">
      <div class="pds-toolbar">
        <Select v-model="query.eventType" clearable placeholder="事件类型" style="width: 150px">
          <Option v-for="(l, k) in EVENT_TYPES" :key="k" :value="k">{{ l }}</Option>
        </Select>
        <Input v-model="query.app" clearable placeholder="应用编码，如 pdk" style="width: 150px" />
        <Select v-model="query.result" clearable placeholder="结果" style="width: 110px">
          <Option v-for="(r, k) in RESULT" :key="k" :value="k">{{ r.label }}</Option>
        </Select>
        <Input v-model="query.userId" clearable placeholder="人员 ID" style="width: 190px" />
        <DatePicker v-model="query.range" type="datetimerange" placeholder="时间范围" style="width: 320px" transfer />
        <Button type="primary" ghost @click="() => { query.page = 1; load() }">查询</Button>
      </div>
      <Table :columns="columns" :data="rows" :loading="loading" size="small" no-data-text="暂无审计记录" />
      <div class="mt-4 flex justify-end"><Page :total="total" v-model="query.page" :page-size="query.pageSize" show-total @on-change="load" /></div>
    </div>
  </div>
</template>
