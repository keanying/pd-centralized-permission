<script setup lang="ts">
import { computed, onMounted, reactive, ref, resolveComponent } from 'vue'
import { Message } from 'view-ui-plus'
import { IconAppCenter, IconPlus } from '@kousum/semi-icons-vue'
import { api, type AppView, type ClientView, type PermPoint } from '@/api'
import { useSession } from '@/stores/session'
import { formatTime } from '@/lib/utils'
import OneTimeSecret from '@/components/pds/OneTimeSecret.vue'

const session = useSession()
const canManage = computed(() => session.has('pds:app:manage'))
const apps = ref<AppView[]>([])
onMounted(async () => (apps.value = await api.apps()))

const groups = computed(() => {
  const map = new Map<string, AppView[]>()
  apps.value.forEach((a) => map.set(a.categoryName, [...(map.get(a.categoryName) ?? []), a]))
  return [...map.entries()]
})

const drawer = reactive({ open: false, app: null as AppView | null, tab: 'perms', perms: [] as PermPoint[], clients: [] as ClientView[] })
async function open(a: AppView) {
  Object.assign(drawer, { open: true, app: a, tab: 'perms' })
  drawer.perms = await api.appPermissions(a.code)
  drawer.clients = canManage.value ? await api.appClients(a.code) : []
}

const GRANTS: Record<string, string> = {
  authorization_code: '授权码（单点登录）', refresh_token: '刷新令牌', client_credentials: '客户端凭证（服务调用）',
  'urn:ietf:params:oauth:grant-type:token-exchange': '令牌交换（代表用户调用）',
}
const form = reactive({ open: false, clientId: '', name: '', grantTypes: ['authorization_code'] as string[], redirectUris: '', scopes: '', publicClient: false })
const secret = reactive({ open: false, value: '' })
async function createClient() {
  if (!form.clientId || !form.name) return Message.warning('请填写客户端 ID 与名称')
  const split = (s: string) => s.split(/[\s,，]+/).map((x) => x.trim()).filter(Boolean)
  const r = await api.createClient(drawer.app!.code, {
    clientId: form.clientId, name: form.name, grantTypes: form.publicClient ? ['authorization_code'] : form.grantTypes,
    redirectUris: split(form.redirectUris), scopes: split(form.scopes), publicClient: form.publicClient,
  })
  form.open = false
  drawer.clients = await api.appClients(drawer.app!.code)
  if (r.clientSecret) Object.assign(secret, { open: true, value: r.clientSecret })
  else Message.success('已创建公共客户端（无密钥）')
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <div v-for="[cat, list] in groups" :key="cat" class="pds-card">
      <div class="pds-card-title">{{ cat }} <span class="pds-muted">{{ list.length }} 个应用</span></div>
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <div v-for="a in list" :key="a.code" class="cursor-pointer rounded-2xl border border-line p-5 transition-shadow hover:shadow-md" @click="open(a)">
          <div class="flex items-start justify-between">
            <div class="flex items-center gap-3">
              <span class="flex size-11 items-center justify-center rounded-xl bg-brand text-white"><IconAppCenter /></span>
              <div>
                <div class="font-semibold text-ink">{{ a.name }}</div>
                <div class="pds-mono pds-muted">{{ a.code }}</div>
              </div>
            </div>
            <Tag :color="a.accessControl ? 'success' : 'default'">{{ a.accessControl ? '准入控制' : '未开启准入' }}</Tag>
          </div>
          <p class="pds-muted my-3 min-h-[18px]">{{ a.description || '—' }}</p>
          <div class="flex gap-5 text-sm">
            <span><b class="text-ink">{{ a.permissionCount }}</b> <span class="pds-muted">权限点</span></span>
            <span><b class="text-ink">{{ a.resourceTypeCount }}</b> <span class="pds-muted">资源类型</span></span>
            <span><b class="text-ink">{{ a.clientCount }}</b> <span class="pds-muted">客户端</span></span>
          </div>
        </div>
      </div>
    </div>

    <Drawer v-model="drawer.open" :title="drawer.app?.name" width="820">
      <p class="pds-muted mb-3">
        清单版本 {{ drawer.app?.manifestVersion || '—' }} · 应用准入权限 <span class="pds-mono">{{ drawer.app?.code === 'pds' ? 'pds:console:access' : drawer.app?.code + ':access' }}</span>
        · 接入方式见《应用接入指南》
      </p>
      <Tabs v-model="drawer.tab">
        <TabPane :label="`权限点（${drawer.perms.length}）`" name="perms">
          <Table size="small" :data="drawer.perms" :columns="[
            { title: '权限点', key: 'name', minWidth: 180, render: (h: any, p: any) => h('div', [h('div', p.row.name), h('div', { class: 'pds-mono pds-muted' }, p.row.code)]) },
            { title: '类型', key: 'type', width: 100 },
            { title: '风险', key: 'risk', width: 70, render: (h: any, p: any) => h(resolveComponent('Tag'), { color: p.row.risk === 'HIGH' ? 'error' : 'default' }, () => (p.row.risk === 'HIGH' ? '高' : '低')) },
            { title: '状态', key: 'status', width: 80, render: (h: any, p: any) => h('span', p.row.status === 1 ? '启用' : '已停用') },
          ]" />
        </TabPane>
        <TabPane v-if="canManage" :label="`客户端（${drawer.clients.length}）`" name="clients">
          <div class="pds-toolbar"><span class="grow" /><Button type="primary" @click="Object.assign(form, { open: true, clientId: '', name: '', redirectUris: '', scopes: '' })"><IconPlus size="small" /> 新建客户端</Button></div>
          <Table size="small" :data="drawer.clients" no-data-text="暂无客户端" :columns="[
            { title: '客户端', key: 'clientId', minWidth: 150, render: (h: any, p: any) => h('div', [h('div', p.row.name), h('div', { class: 'pds-mono pds-muted' }, p.row.clientId)]) },
            { title: '授权方式', key: 'grantTypes', minWidth: 180, render: (h: any, p: any) => h('div', { class: 'text-xs' }, p.row.grantTypes.map((g: string) => h('div', GRANTS[g] || g))) },
            { title: '范围', key: 'scopes', minWidth: 140, render: (h: any, p: any) => h('span', { class: 'pds-mono' }, p.row.scopes.join(' ')) },
            { title: '类型', key: 'publicClient', width: 90, render: (h: any, p: any) => h('span', p.row.publicClient ? '公共' : '机密') },
            { title: '创建', key: 'issuedAt', width: 140, render: (h: any, p: any) => h('span', formatTime(p.row.issuedAt)) },
          ]" />
        </TabPane>
      </Tabs>
    </Drawer>

    <Modal v-model="form.open" :title="`为 ${drawer.app?.name} 新建客户端`" width="560" @on-ok="createClient">
      <Form :label-width="90">
        <FormItem label="客户端 ID"><Input v-model="form.clientId" placeholder="小写字母、数字和 -，如 pbi-portal" /></FormItem>
        <FormItem label="名称"><Input v-model="form.name" /></FormItem>
        <FormItem label="浏览器应用"><Switch v-model="form.publicClient" /> <span class="pds-muted">单页应用使用公共客户端（无密钥，强制 PKCE）</span></FormItem>
        <FormItem v-if="!form.publicClient" label="授权方式">
          <CheckboxGroup v-model="form.grantTypes"><Checkbox v-for="(l, k) in GRANTS" :key="k" :label="k">{{ l }}</Checkbox></CheckboxGroup>
        </FormItem>
        <FormItem label="回调地址"><Input v-model="form.redirectUris" type="textarea" :rows="2" placeholder="授权码方式必填，多个用换行分隔；本地开发用 127.0.0.1" /></FormItem>
        <FormItem label="范围"><Input v-model="form.scopes" placeholder="如 openid profile，或 pds.authz pds.audit" /></FormItem>
      </Form>
    </Modal>
    <OneTimeSecret v-model="secret.open" title="客户端已创建" label="客户端密钥" :value="secret.value" />
  </div>
</template>
