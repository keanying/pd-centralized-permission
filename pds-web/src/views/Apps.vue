<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from 'view-ui-plus'
import { IconGridView, IconPlus, IconClose, IconSearch } from '@kousum/semi-icons-vue'
import { api, type AppView, type ClientView, type PermPoint, type ResourceType } from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import PdsModal from '@/components/pds/PdsModal.vue'
import OneTimeSecret from '@/components/pds/OneTimeSecret.vue'
import PillSelect from '@/components/pds/PillSelect.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const canManage = computed(() => session.has('pds:app:manage'))
const apps = ref<AppView[]>([])
const q = reactive({ keyword: '', category: '' })
const rows = computed(() => apps.value.filter((a) => (!q.category || (a.category || '') === q.category)
  && (!q.keyword || a.name.includes(q.keyword) || a.code.includes(q.keyword.toLowerCase()))))
async function loadApps() { apps.value = await api.apps() }

const CATEGORIES: Record<string, string> = {
  APPLICATION: '应用层', QUERY: '查数层', SECURITY: '统一权限', DATA_OUTLET: '数据出口', OFFLINE_GOVERNANCE: '离线治理',
  REALTIME_GOVERNANCE: '实时治理', DATA_CLEANSING: '数据清洗', DATA_INTEGRATION: '数据集成', FOUNDATION: '公共底座', FEEDBACK: '回流平台',
}
// 新增 / 编辑应用
const appForm = reactive({ open: false, mode: 'create' as 'create' | 'edit', code: '', name: '', category: 'APPLICATION', description: '', accessControl: true, homeUrl: '' })
function openCreate() { Object.assign(appForm, { open: true, mode: 'create', code: '', name: '', category: 'APPLICATION', description: '', accessControl: true, homeUrl: '' }) }
function openEdit(a: AppView) {
  Object.assign(appForm, { open: true, mode: 'edit', code: a.code, name: a.name, category: a.category || '', description: a.description || '', accessControl: a.accessControl, homeUrl: a.homeUrl || '' })
}
async function submitApp() {
  if (appForm.mode === 'create' && !/^[a-z][a-z0-9_]{1,31}$/.test(appForm.code)) { Message.warning('应用编码只能为小写字母开头的字母数字组合'); return false }
  if (!appForm.name.trim()) { Message.warning('请填写应用名称'); return false }
  if (appForm.mode === 'create') {
    await api.createApp({ code: appForm.code, name: appForm.name.trim(), category: appForm.category || undefined, description: appForm.description || undefined, accessControl: appForm.accessControl, homeUrl: appForm.homeUrl.trim() || undefined })
    Message.success(`已新增应用，自动生成准入节点 ${appForm.code}:access，可到权限树维护节点`)
  } else {
    await api.updateApp(appForm.code, { name: appForm.name.trim(), category: appForm.category, description: appForm.description, accessControl: appForm.accessControl, homeUrl: appForm.homeUrl.trim() })
    Message.success('已保存')
  }
  await loadApps()
  const a = apps.value.find((x) => x.code === appForm.code)
  if (a) open(a)
}
// 删除应用：先看影响范围，输入应用编码确认
const del = reactive({ open: false, app: null as AppView | null, impact: {} as Record<string, number>, confirm: '' })
async function openDelete(a: AppView) { Object.assign(del, { open: true, app: a, impact: await api.appDeleteImpact(a.code), confirm: '' }) }
async function submitDelete() {
  if (del.confirm !== del.app!.code) { Message.warning('请输入应用编码确认删除'); return false }
  await api.deleteApp(del.app!.code)
  Message.success('已删除应用')
  if (cur.value?.code === del.app!.code) close()
  await loadApps()
}
const IMPACT: Record<string, string> = { permNodes: '权限节点', grants: '权限授权记录', rolePerms: '角色中的权限项', resources: '资源实例',
  resourceGrants: '资源授权记录', appRoles: '应用角色', roleBindings: '应用角色的绑定', clients: 'OAuth 客户端' }

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
  await loadApps()
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
  <div class="grid grid-cols-1 gap-5" :class="cur ? 'xl:grid-cols-[1fr_480px]' : ''">
    <div class="pds-card min-w-0 self-start">
      <div class="mb-5 flex flex-wrap items-center gap-3">
        <div class="pds-search w-[260px]"><IconSearch /><input v-model="q.keyword" placeholder="搜索应用名称或编码" /></div>
        <PillSelect v-model="q.category" label="分层" compact :options="Object.entries(CATEGORIES).map(([value, label]) => ({ value, label }))" />
        <span class="flex-1" />
        <button v-if="canManage" class="pds-btn dark" @click="openCreate"><IconPlus size="small" /> 新增应用</button>
      </div>
      <table class="pds-table">
        <thead><tr><th>应用</th><th>所属分层</th><th>准入</th><th>权限节点</th><th>资源类型</th><th>客户端</th><th>清单版本</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="a in rows" :key="a.code" class="clickable" :class="{ active: cur?.code === a.code }" @click="open(a)">
            <td>
              <span class="flex items-center gap-3">
                <span class="flex size-9 shrink-0 items-center justify-center rounded-xl bg-ink text-accent"><IconGridView size="small" /></span>
                <span class="min-w-0"><span class="block font-semibold text-ink">{{ a.name }}</span><span class="pds-mono pds-muted block">{{ a.code }}</span></span>
              </span>
            </td>
            <td>{{ a.category ? a.categoryName : '未分类' }}</td>
            <td><span class="pds-badge" :class="a.accessControl ? 'ok' : 'none'">{{ a.accessControl ? '准入控制' : '未开启' }}</span></td>
            <td>{{ a.permissionCount }}</td><td>{{ a.resourceTypeCount }}</td><td>{{ a.clientCount }}</td>
            <td class="pds-mono text-[#6B7280]">{{ a.manifestVersion || '—' }}</td>
            <td class="whitespace-nowrap" @click.stop>
              <span class="text-[13px] text-[#6B7280]">
                <button class="pds-link" @click="router.push({ path: '/perm-tree', query: { app: a.code } })">权限树</button>
                <template v-if="canManage"> · <button class="pds-link gray" @click="openEdit(a)">编辑</button>
                  · <button class="pds-link red" :disabled="a.code === 'pds'" :title="a.code === 'pds' ? 'PDS 自身不能删除' : ''" @click="openDelete(a)">删除</button></template>
              </span>
            </td>
          </tr>
          <tr v-if="!rows.length"><td colspan="8" class="empty">{{ q.keyword || q.category ? '没有匹配的应用' : '还没有接入应用' }}</td></tr>
        </tbody>
      </table>
      <p class="pds-muted mb-0 mt-4">应用也可以由各系统通过权限清单接口自行注册；在这里新增的应用到"权限树"中维护权限节点。</p>
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
    <PdsModal v-model="appForm.open" :title="appForm.mode === 'create' ? '新增应用' : `编辑应用 · ${appForm.name}`" :ok-text="appForm.mode === 'create' ? '新增' : '保存'" :on-ok="submitApp">
      <div class="pds-form flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-4">
          <div><div class="pds-label">应用编码</div><Input v-model="appForm.code" :disabled="appForm.mode === 'edit'" placeholder="小写字母，如 pdq" /></div>
          <div><div class="pds-label">应用名称</div><Input v-model="appForm.name" placeholder="如 PDQ 即席自助" /></div>
        </div>
        <div><div class="pds-label">所属分层</div>
          <Select v-model="appForm.category" clearable transfer placeholder="未分类"><Option v-for="(l, k) in CATEGORIES" :key="k" :value="k">{{ l }}</Option></Select></div>
        <div><div class="pds-label">说明</div><Input v-model="appForm.description" /></div>
        <div><div class="pds-label">访问地址（门户中的应用入口跳转到这里）</div><Input v-model="appForm.homeUrl" placeholder="如 https 开头的系统首页地址" /></div>
        <label class="flex items-center gap-2 text-[13px]"><Switch v-model="appForm.accessControl" size="small" :disabled="appForm.code === 'pds'" /> 应用准入：人员须获得"登录进入"权限才能单点登录该应用</label>
      </div>
    </PdsModal>
    <PdsModal v-model="del.open" :title="`删除应用 · ${del.app?.name}`" ok-text="确认删除" danger :on-ok="submitDelete">
      <div class="pds-banner bad mb-4 text-[13px]">删除后以下数据将一并删除且不可恢复，相关人员立即失去对应权限：</div>
      <div class="mb-4 grid grid-cols-2 gap-2">
        <div v-for="(l, k) in IMPACT" :key="k" class="flex justify-between rounded-xl bg-row px-4 py-2 text-[13px]"><span>{{ l }}</span><b :class="del.impact[k] ? 'text-danger' : 'text-ink'">{{ del.impact[k] ?? 0 }}</b></div>
      </div>
      <p class="pds-muted mt-0">配置文件中定义的开发客户端会在 PDS 重启时重新登记。请输入应用编码 <b class="pds-mono text-ink">{{ del.app?.code }}</b> 确认：</p>
      <Input v-model="del.confirm" class="pds-mono" :placeholder="del.app?.code" />
    </PdsModal>
    <OneTimeSecret v-model="secret.open" title="客户端已创建" label="客户端密钥" :value="secret.value" />
  </div>
</template>
