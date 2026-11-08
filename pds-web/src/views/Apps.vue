<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from 'view-ui-plus'
import { IconGridView, IconPlus, IconClose } from '@kousum/semi-icons-vue'
import { api, type AppView, type ClientView, type PermPoint, type ResourceType } from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import OneTimeSecret from '@/components/pds/OneTimeSecret.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canManage = computed(() => session.has('pds:app:manage'))
const apps = ref<AppView[]>([])
const groups = computed(() => {
  const map = new Map<string, AppView[]>()
  apps.value.forEach((a) => map.set(a.categoryName, [...(map.get(a.categoryName) ?? []), a]))
  return [...map.entries()]
})

const cur = ref<AppView | null>(null)
const tab = ref<'perms' | 'types' | 'clients'>('perms')
const perms = ref<PermPoint[]>([])
const types = ref<ResourceType[]>([])
const clients = ref<ClientView[]>([])
async function open(a: AppView) {
  cur.value = a
  tab.value = 'perms'
  router.replace({ query: { app: a.code } })
  const [p, t, c] = await Promise.all([api.appPermissions(a.code), api.resourceTypes(), canManage.value ? api.appClients(a.code) : Promise.resolve([])])
  perms.value = p
  types.value = t.filter((x) => x.appCode === a.code)
  clients.value = c
}
function close() { cur.value = null; router.replace({ query: {} }) }
onMounted(async () => {
  apps.value = await api.apps()
  const q = route.query.app as string
  if (q) { const a = apps.value.find((x) => x.code === q); if (a) open(a) }
})
watch(() => route.query.app, (q) => { const a = apps.value.find((x) => x.code === q); if (a && a.code !== cur.value?.code) open(a) })

const GRANTS: Record<string, string> = {
  authorization_code: '授权码（单点登录）', refresh_token: '刷新令牌', client_credentials: '客户端凭证（服务调用）',
  'urn:ietf:params:oauth:grant-type:token-exchange': '令牌交换（代表用户调用其他系统）',
}
const form = reactive({ open: false, clientId: '', name: '', grantTypes: ['authorization_code'] as string[], redirectUris: '', scopes: '', publicClient: false })
const secret = reactive({ open: false, value: '' })
async function submitClient() {
  if (!/^[a-z0-9-]{3,64}$/.test(form.clientId)) { Message.warning('客户端 ID 只能包含小写字母、数字和 -，长度 3-64'); return false }
  if (!form.name.trim()) { Message.warning('请填写名称'); return false }
  const split = (s: string) => s.split(/[\s,，]+/).map((x) => x.trim()).filter(Boolean)
  const grants = form.publicClient ? ['authorization_code'] : form.grantTypes
  if (grants.includes('authorization_code') && !split(form.redirectUris).length) { Message.warning('授权码方式必须填写回调地址'); return false }
  const r = await api.createClient(cur.value!.code, { clientId: form.clientId, name: form.name.trim(), grantTypes: grants,
    redirectUris: split(form.redirectUris), scopes: split(form.scopes), publicClient: form.publicClient })
  clients.value = await api.appClients(cur.value!.code)
  if (r.clientSecret) Object.assign(secret, { open: true, value: r.clientSecret })
  else Message.success('已创建公共客户端（无密钥，强制 PKCE）')
}
</script>

<template>
  <div class="grid grid-cols-1 gap-5" :class="cur ? 'xl:grid-cols-[1fr_520px]' : ''">
    <div class="flex min-w-0 flex-col gap-5">
      <div v-for="[cat, list] in groups" :key="cat" class="pds-card">
        <div class="pds-card-title">{{ cat }} <span class="pds-muted font-normal">{{ list.length }} 个应用</span></div>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2" :class="cur ? '' : '2xl:grid-cols-3'">
          <div v-for="a in list" :key="a.code" class="cursor-pointer rounded-2xl p-5 transition-colors"
               :class="cur?.code === a.code ? 'bg-accent-soft ring-2 ring-accent' : 'bg-row hover:bg-[#ECF1E8]'" @click="open(a)">
            <div class="flex items-start gap-3">
              <span class="flex size-11 shrink-0 items-center justify-center rounded-xl bg-ink text-accent"><IconGridView /></span>
              <div class="min-w-0 flex-1">
                <div class="truncate font-semibold text-ink">{{ a.name }}</div>
                <div class="pds-mono pds-muted">{{ a.code }}</div>
              </div>
              <span class="pds-badge" :class="a.accessControl ? 'ok' : 'none'">{{ a.accessControl ? '准入控制' : '未开启准入' }}</span>
            </div>
            <p class="pds-muted my-3 min-h-[18px]">{{ a.description || '—' }}</p>
            <div class="flex gap-5 text-[13px]">
              <span><b class="text-ink">{{ a.permissionCount }}</b> <span class="text-muted">权限点</span></span>
              <span><b class="text-ink">{{ a.resourceTypeCount }}</b> <span class="text-muted">资源类型</span></span>
              <span><b class="text-ink">{{ a.clientCount }}</b> <span class="text-muted">客户端</span></span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="cur" class="pds-card self-start border-0 border-l-4 border-solid border-accent">
      <div class="flex items-start justify-between">
        <div>
          <div class="text-[17px] font-semibold text-ink">{{ cur.name }}</div>
          <div class="pds-muted mt-1">清单版本 {{ cur.manifestVersion || '—' }} · 应用准入 <span class="pds-mono">{{ cur.code === 'pds' ? 'pds:console:access' : cur.code + ':access' }}</span></div>
        </div>
        <button class="pds-link gray" @click="close"><IconClose /></button>
      </div>
      <RadioGroup v-model="tab" type="button" size="small" class="mt-4">
        <Radio label="perms">权限点 {{ perms.length }}</Radio><Radio label="types">资源类型 {{ types.length }}</Radio>
        <Radio v-if="canManage" label="clients">客户端 {{ clients.length }}</Radio>
      </RadioGroup>
      <div class="mt-4 max-h-[560px] overflow-auto">
        <template v-if="tab === 'perms'">
          <div v-for="p in perms" :key="p.code" class="flex items-center gap-3 border-0 border-b border-solid border-[#F0F2EE] py-2.5">
            <div class="min-w-0 flex-1"><div class="text-[14px] text-ink">{{ p.name }}</div><div class="pds-mono pds-muted">{{ p.code }}</div></div>
            <span class="pds-badge gray">{{ p.type }}</span>
            <span v-if="p.risk === 'HIGH'" class="pds-badge bad">高风险</span>
            <span v-if="p.status === 0" class="pds-badge none">已停用</span>
          </div>
          <p v-if="!perms.length" class="pds-muted py-6 text-center">尚未注册权限清单</p>
        </template>
        <template v-else-if="tab === 'types'">
          <div v-for="t in types" :key="t.typeCode" class="flex items-center justify-between rounded-xl bg-row px-4 py-3 mb-2">
            <span class="text-ink">{{ t.name }} <span class="pds-mono pds-muted">{{ t.typeCode }}</span></span>
            <button class="pds-link" @click="router.push({ path: '/assignments', query: { view: 'app', objectType: 'RESOURCE' } })">去授权</button>
          </div>
          <p v-if="!types.length" class="pds-muted py-6 text-center">该应用未声明可授权的资源类型</p>
        </template>
        <template v-else>
          <button class="pds-btn dark sm mb-3" @click="Object.assign(form, { open: true, clientId: '', name: '', redirectUris: '', scopes: '', publicClient: false, grantTypes: ['authorization_code'] })"><IconPlus size="small" /> 新建客户端</button>
          <div v-for="c in clients" :key="c.clientId" class="mb-2 rounded-xl bg-row px-4 py-3">
            <div class="flex items-center justify-between"><b class="text-ink">{{ c.name }}</b><span class="pds-badge" :class="c.publicClient ? 'warn' : 'gray'">{{ c.publicClient ? '公共客户端' : '机密客户端' }}</span></div>
            <div class="pds-mono pds-muted mt-1">{{ c.clientId }} · {{ formatTime(c.issuedAt) }}</div>
            <div class="mt-2 text-[12px] text-[#374151]">{{ c.grantTypes.map((g) => GRANTS[g] || g).join('、') }}</div>
            <div class="pds-mono pds-muted mt-1">范围：{{ c.scopes.join(' ') }}</div>
          </div>
          <p v-if="!clients.length" class="pds-muted py-6 text-center">暂无客户端</p>
        </template>
      </div>
    </div>

    <PdsModal v-model="form.open" :title="`为 ${cur?.name} 新建客户端`" width="580" ok-text="创建" :on-ok="submitClient">
      <Form :label-width="96">
        <FormItem label="客户端 ID" required><Input v-model="form.clientId" placeholder="如 pbi-portal" /></FormItem>
        <FormItem label="名称" required><Input v-model="form.name" /></FormItem>
        <FormItem label="浏览器应用"><Switch v-model="form.publicClient" /> <span class="pds-muted ml-2">单页应用用公共客户端（无密钥，强制 PKCE）</span></FormItem>
        <FormItem v-if="!form.publicClient" label="授权方式">
          <CheckboxGroup v-model="form.grantTypes" class="flex flex-col"><Checkbox v-for="(l, k) in GRANTS" :key="k" :label="k">{{ l }}</Checkbox></CheckboxGroup>
        </FormItem>
        <FormItem label="回调地址"><Input v-model="form.redirectUris" type="textarea" :rows="2" placeholder="授权码方式必填，多个换行；本地开发用 127.0.0.1" /></FormItem>
        <FormItem label="范围"><Input v-model="form.scopes" placeholder="如 openid profile，或 pds.authz pds.audit" /></FormItem>
      </Form>
    </PdsModal>
    <OneTimeSecret v-model="secret.open" title="客户端已创建" label="客户端密钥" :value="secret.value" />
  </div>
</template>
