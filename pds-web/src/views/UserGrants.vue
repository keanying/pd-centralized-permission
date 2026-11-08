<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message, Modal } from 'view-ui-plus'
import { IconPlus, IconClock, IconHelpCircle, IconChevronRight, IconMore, IconTick, IconClose } from '@kousum/semi-icons-vue'
import {
  api, ACTIONS, SECURITY_LEVELS, type AppView, type OrgNode, type PermissionState, type ResourceState, type ResourceType,
  type UserEffective, type UserPermView, type UserView,
} from '@/api'
import { useSession } from '@/stores/session'
import { usePage } from '@/stores/page'
import { formatTime } from '@/lib/utils'
import UserSelect from '@/components/pds/UserSelect.vue'
import SourceChip from '@/components/pds/SourceChip.vue'
import PdsModal from '@/components/pds/PdsModal.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const page = usePage()
const canGrant = computed(() => session.has('pds:grant:manage'))

const userId = ref<string>((route.query.userId as string) || '')
const user = ref<UserView | null>(null)
const apps = ref<AppView[]>([])
const effs = ref<Record<string, UserEffective>>({})
const records = ref<UserPermView[]>([])
const types = ref<ResourceType[]>([])
const scenics = ref<OrgNode[]>([])
const collapsed = reactive(new Set<string>())
const selected = ref<string>('')   // 选中的权限点编码
const loading = ref(false)

onMounted(async () => {
  const [a, t, tree] = await Promise.all([api.apps(), api.resourceTypes(), api.orgTree()])
  apps.value = a
  types.value = t
  const walk = (ns: OrgNode[]) => ns.forEach((n) => { if (n.type === 'SCENIC') scenics.value.push(n); walk(n.children) })
  walk(tree)
  if (userId.value) load()
})
watch(userId, (v) => {
  router.replace({ query: v ? { userId: v } : {} })
  selected.value = ''
  explainResult.value = null
  if (v) load()
})

async function load() {
  loading.value = true
  try {
    const id = userId.value
    const [u, recs, ...list] = await Promise.all([api.user(id), api.userPerms(id), ...apps.value.map((a) => api.userEffective(id, a.code))])
    user.value = u
    records.value = recs
    effs.value = Object.fromEntries(list.map((e) => [e.app, e]))
    // 默认折叠"既无已拥有、也无已禁止"的应用，便于聚焦
    collapsed.clear()
    list.forEach((e) => { if (!e.permissions.some((p) => p.state !== 'NONE')) collapsed.add(e.app) })
    page.set(`人员明细权限 · ${u.name}`, { inline: `（工号 ${u.empNo || '—'} · ${scenicNames.value} ${u.primaryOrgName || ''} · ${u.positions.join('、') || '无岗位'}）` })
  } finally {
    loading.value = false
  }
}

const anyEff = computed(() => Object.values(effs.value)[0])
const scenicName = (id: string) => (id === 'GROUP' ? '集团共享' : scenics.value.find((s) => s.id === id)?.name || id)
const scenicNames = computed(() => (anyEff.value?.scenicScope ?? []).map(scenicName).join('、') || '仅集团共享')
const allPerms = computed(() => apps.value.flatMap((a) => (effs.value[a.code]?.permissions ?? []).map((p) => ({ ...p, app: a.code }))))
const selectedPerm = computed(() => allPerms.value.find((p) => p.code === selected.value))

// 资源授权（按类型分组，默认第一个有数据的类型）
const resources = computed(() => Object.values(effs.value).flatMap((e) => e.resources))
const resType = ref('')
const resTypes = computed(() => [...new Set(resources.value.map((r) => r.type))])
watch(resTypes, (ts) => { if (!ts.includes(resType.value)) resType.value = ts[0] || '' })
const typeName = (code: string) => types.value.find((t) => t.typeCode === code)?.name || code
const shownResources = computed(() => resources.value.filter((r) => r.type === resType.value))
const recordFor = (r: ResourceState) => records.value.find((x) => x.resourceType === r.type && x.resourceId === r.refId)
const recordForPerm = (code: string) => records.value.find((x) => x.permCode === code)

const STATE = { GRANTED: { label: '已拥有', cls: 'ok' }, DENIED: { label: '已禁止', cls: 'bad' }, NONE: { label: '未拥有', cls: 'none' } }

// ---------------- 直接授予 / 禁止
const grant = reactive({ open: false, effect: 'ALLOW' as 'ALLOW' | 'DENY', target: 'perm' as 'perm' | 'resource', permCode: '', resKey: '', action: 'READ', reason: '', validTo: '' as any })
function openGrant(effect: 'ALLOW' | 'DENY', preset?: { perm?: string; res?: ResourceState }) {
  Object.assign(grant, {
    open: true, effect, reason: '', validTo: '', action: 'READ',
    target: preset?.res ? 'resource' : 'perm',
    permCode: preset?.perm ?? (selected.value || ''),
    resKey: preset?.res ? `${preset.res.type}|${preset.res.refId}` : '',
  })
}
async function submitGrant() {
  if (grant.target === 'perm' && !grant.permCode) { Message.warning('请选择权限点'); return false }
  if (grant.target === 'resource' && !grant.resKey) { Message.warning('请选择资源'); return false }
  if (!grant.reason.trim()) { Message.warning('请填写原因（将记入审计）'); return false }
  const [resourceType, resourceId] = grant.resKey.split('|')
  await api.addUserPerm(userId.value, {
    ...(grant.target === 'perm' ? { permCode: grant.permCode } : { resourceType, resourceId, action: grant.action }),
    effect: grant.effect, reason: grant.reason.trim(), validTo: grant.validTo ? new Date(grant.validTo).toISOString() : undefined,
  })
  Message.success(grant.effect === 'ALLOW' ? '已直接授予' : '已禁止')
  await load()
}
const grantablePerms = computed(() => allPerms.value.filter((p) => (grant.effect === 'ALLOW' ? p.state !== 'GRANTED' : p.state !== 'DENIED')))
const allResourceOptions = ref<{ key: string; label: string }[]>([])
watch(() => grant.open, async (o) => {
  if (!o || allResourceOptions.value.length) return
  const r = await api.resources({ pageSize: 100 })
  allResourceOptions.value = r.items.map((x) => ({ key: `${x.type}|${x.refId}`, label: `${x.name}（${typeName(x.type)} · ${x.scenicName}）` }))
})

// ---------------- 设置有效期（人员明细记录）
const validity = reactive({ open: false, rows: [] as { rec: UserPermView; value: any; forever: boolean }[] })
function openValidity() {
  if (!records.value.length) return Message.info('此人暂无个人直授或禁止记录，有效期只能设置在人员明细记录上')
  const focus = selected.value ? recordForPerm(selected.value) : undefined
  validity.rows = records.value
    .slice().sort((a, b) => (a.id === focus?.id ? -1 : b.id === focus?.id ? 1 : 0))
    .map((rec) => ({ rec, value: rec.validTo ? new Date(rec.validTo) : '', forever: !rec.validTo }))
  validity.open = true
}
async function submitValidity() {
  const changed = validity.rows.filter((r) => {
    const before = r.rec.validTo ? new Date(r.rec.validTo).getTime() : null
    const after = r.forever || !r.value ? null : new Date(r.value).getTime()
    return before !== after
  })
  if (!changed.length) { Message.info('没有修改'); return false }
  for (const r of changed) {
    await api.updateUserPerm(userId.value, r.rec.id, r.forever || !r.value ? { clearValidTo: true } : { validTo: new Date(r.value).toISOString() })
  }
  Message.success(`已更新 ${changed.length} 条有效期`)
  await load()
}

// ---------------- 撤销个人设置
function revoke(rec: UserPermView) {
  Modal.confirm({
    title: '撤销个人设置', content: `撤销对「${rec.permName || rec.resourceName}」的个人${rec.effect === 'ALLOW' ? '直授' : '禁止'}？撤销后按角色、岗位等其他来源重新计算。`,
    okText: '撤销', onOk: async () => { await api.deleteUserPerm(userId.value, rec.id); Message.success('已撤销'); await load() },
  })
}
function onResourceMenu(name: string, r: ResourceState) {
  if (name === 'allow') openGrant('ALLOW', { res: r })
  else if (name === 'deny') openGrant('DENY', { res: r })
  else if (name === 'revoke') { const rec = recordFor(r); if (rec) revoke(rec) }
}

// ---------------- 权限解释
const explainCode = ref('')
const explainResult = ref<{ allowed: boolean; reason: string } | null>(null)
const explainCard = ref<HTMLElement>()
async function explain() {
  if (!explainCode.value.trim()) return Message.warning('请输入或选择权限点')
  explainResult.value = await api.explain({ subject: userId.value, permission: explainCode.value.trim() })
}
async function explainSelected() {
  if (selected.value) explainCode.value = selected.value
  await nextTick()
  explainCard.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  if (explainCode.value) explain()
}
</script>

<template>
  <div v-if="!userId" class="pds-card flex flex-col items-center gap-4 py-16">
    <div class="text-[16px] font-semibold text-ink">选择要查看或调整权限的人员</div>
    <div class="w-[360px]"><UserSelect v-model="userId" placeholder="输入姓名、账号或工号" /></div>
    <p class="pds-muted m-0">也可以在"架构树"中点击人员的"权限"进入。</p>
  </div>

  <div v-else class="flex flex-col gap-5">
    <!-- 人员信息与操作 -->
    <div class="pds-card flex flex-wrap items-center gap-6 py-5">
      <span class="flex size-12 items-center justify-center rounded-full bg-accent-soft text-[15px] font-semibold text-ink">{{ user?.name.slice(-2) }}</span>
      <div class="min-w-[160px]">
        <div class="text-[18px] font-bold text-ink">{{ user?.name }}</div>
        <div class="mt-0.5 text-[12px] text-muted">工号 {{ user?.empNo || '—' }} · {{ user?.primaryOrgName || '—' }} · {{ user?.positions.join('、') || '无岗位' }}</div>
      </div>
      <div class="flex gap-8 text-[12px]">
        <div><div class="mb-1 text-muted">景区范围</div><span class="pds-badge ok">{{ scenicNames }}</span></div>
        <div><div class="mb-1 text-muted">最高密级</div><span class="pds-badge warn">{{ SECURITY_LEVELS[anyEff?.maxSecurityLevel ?? 0] }}</span></div>
        <div><div class="mb-1 text-muted">账号状态</div>
          <span class="pds-dot" :class="anyEff?.status === 'ACTIVE' ? 'ok' : 'off'">{{ anyEff?.status === 'ACTIVE' ? '正常' : anyEff?.status === 'LEFT' ? '离职' : '停用' }}</span></div>
      </div>
      <span class="flex-1" />
      <div class="flex flex-wrap gap-2">
        <template v-if="canGrant">
          <button class="pds-btn dark" @click="openGrant('ALLOW')"><IconPlus size="small" /> 直接授予</button>
          <button class="pds-btn danger" @click="openGrant('DENY')"><IconClose size="small" /> 禁止</button>
          <button class="pds-btn ghost" @click="openValidity"><IconClock size="small" /> 设置有效期</button>
        </template>
        <button class="pds-btn ghost" @click="explainSelected"><IconHelpCircle size="small" /> 权限解释</button>
      </div>
    </div>
    <div v-if="anyEff && anyEff.status !== 'ACTIVE'" class="rounded-2xl bg-danger-soft px-5 py-3 text-[13px] text-danger">该账号已{{ anyEff.status === 'LEFT' ? '离职' : '停用' }}，所有权限均不生效。</div>

    <div class="grid grid-cols-1 gap-5 xl:grid-cols-[1.6fr_1fr]">
      <!-- 权限树 -->
      <div class="pds-card min-w-0">
        <div class="pds-card-title">权限树 <span class="pds-muted font-normal">点击一行选中，再使用上方操作</span></div>
        <Spin v-if="loading && !allPerms.length" fix />
        <div v-for="a in apps" :key="a.code" class="mb-3">
          <div class="flex h-10 cursor-pointer items-center gap-2 rounded-xl bg-row px-3 text-[14px] font-semibold text-ink" @click="collapsed.has(a.code) ? collapsed.delete(a.code) : collapsed.add(a.code)">
            <IconChevronRight size="small" class="transition-transform" :class="{ 'rotate-90': !collapsed.has(a.code) }" />
            {{ a.name }}
            <span class="pds-muted ml-auto font-normal">已拥有 {{ (effs[a.code]?.permissions ?? []).filter((p) => p.state === 'GRANTED').length }} / {{ (effs[a.code]?.permissions ?? []).length }}</span>
          </div>
          <template v-if="!collapsed.has(a.code)">
            <div v-for="p in effs[a.code]?.permissions ?? []" :key="p.code"
                 class="flex min-h-[44px] cursor-pointer items-center gap-3 border-0 border-b border-solid border-[#F0F2EE] py-2 pr-6 text-[14px] transition-colors last:border-b-0"
                 :style="{ paddingLeft: 24 + (p.depth || 0) * 20 + 'px' }"
                 :class="selected === p.code ? 'bg-[#F6FAEF]' : 'hover:bg-[#FAFBF8]'" @click="selected = selected === p.code ? '' : p.code">
              <span class="min-w-0 flex-1">
                <span class="text-[#374151]">{{ p.code }}</span> <span class="text-ink">{{ p.name }}</span>
                <span v-if="p.risk === 'HIGH'" class="pds-badge bad ml-1" style="height: 18px">高风险</span>
              </span>
              <span v-if="p.state === 'NONE' && p.visible" class="pds-badge gray" title="下级节点已授予，此节点对其可见（如菜单），但未拥有本节点">路径可见</span>
              <span class="pds-badge" :class="STATE[p.state].cls">{{ STATE[p.state].label }}</span>
              <span class="w-[132px] shrink-0"><SourceChip :refs="p.state === 'DENIED' ? p.denyRefs : p.allowRefs" /></span>
            </div>
            <div v-if="!(effs[a.code]?.permissions ?? []).length" class="pds-muted px-6 py-3">该应用尚未注册权限清单</div>
          </template>
        </div>
      </div>

      <!-- 资源授权 -->
      <div class="pds-card min-w-0 self-start">
        <div class="pds-card-title">
          资源授权 · {{ resType ? typeName(resType) : '—' }}
          <Select v-if="resTypes.length > 1" v-model="resType" size="small" style="width: 120px" transfer>
            <Option v-for="t in resTypes" :key="t" :value="t">{{ typeName(t) }}</Option>
          </Select>
        </div>
        <div class="flex flex-col gap-3">
          <div v-for="r in shownResources" :key="r.refId" class="flex items-center gap-3 rounded-2xl px-4 py-4" :class="r.denied ? 'bg-danger-soft' : 'bg-row'"
               :title="[...r.denyRefs, ...r.allowRefs].map((x) => x.text).join('\n')">
            <div class="min-w-0 flex-1">
              <div class="truncate text-[14px] text-ink">{{ r.name }}</div>
              <div class="pds-muted mt-0.5">有效期：{{ r.denied ? '长期' : r.validTo ? `至 ${formatTime(r.validTo).slice(0, 10)}` : '长期' }}</div>
            </div>
            <span v-if="r.denied" class="pds-badge bad-solid">禁止</span>
            <span v-else class="pds-badge white">{{ r.actions.includes('ADMIN') ? 'admin' : r.actions.includes('WRITE') ? 'write' : 'read' }}</span>
            <Dropdown v-if="canGrant" trigger="click" transfer placement="bottom-end" @on-click="(n: string) => onResourceMenu(n, r)">
              <span class="flex size-7 cursor-pointer items-center justify-center rounded-full text-muted hover:bg-white"><IconMore size="small" /></span>
              <template #list>
                <DropdownMenu>
                  <DropdownItem v-if="r.denied" name="allow">直接授予</DropdownItem>
                  <DropdownItem v-else name="deny">禁止访问</DropdownItem>
                  <DropdownItem v-if="!r.denied" name="allow">提升动作（编辑/管理）</DropdownItem>
                  <DropdownItem v-if="recordFor(r)" name="revoke" divided>撤销个人设置</DropdownItem>
                </DropdownMenu>
              </template>
            </Dropdown>
          </div>
          <div v-if="!shownResources.length" class="pds-muted py-8 text-center">此人暂无可访问的资源</div>
        </div>
        <p class="pds-muted mb-0 mt-3">来源说明：鼠标悬停卡片查看授权来源。</p>
      </div>
    </div>

    <!-- 权限解释 -->
    <div ref="explainCard" class="pds-card">
      <div class="pds-card-title">权限解释</div>
      <div class="flex gap-3">
        <AutoComplete v-model="explainCode" :data="allPerms.map((p) => p.code)" placeholder="输入或选择权限点编码，如 pda:tool:pdk_search" class="pds-mono flex-1" transfer
                      :filter-method="(v: string, o: string) => o.includes(v)" @on-select="explain" @keydown.enter="explain" />
        <button class="pds-btn accent px-8" @click="explain">解释</button>
      </div>
      <div v-if="explainResult" class="mt-4 flex items-center gap-4 rounded-2xl border border-solid px-5 py-4 text-[15px]"
           :class="explainResult.allowed ? 'border-accent bg-accent-soft text-ink' : 'border-[#FCA5A5] bg-danger-soft text-ink'">
        <span class="flex size-9 shrink-0 items-center justify-center rounded-full" :class="explainResult.allowed ? 'bg-accent' : 'bg-danger-2 text-white'">
          <IconTick v-if="explainResult.allowed" /><IconClose v-else />
        </span>
        <span><b>{{ explainResult.allowed ? '允许' : explainResult.reason.startsWith('禁止') ? '禁止' : '未授予' }}</b> —— {{ explainResult.reason.replace(/^(允许|禁止|未授予) —— /, '') }}</span>
      </div>
    </div>

    <!-- 直接授予 / 禁止 -->
    <PdsModal v-model="grant.open" :title="grant.effect === 'ALLOW' ? `直接授予 · ${user?.name}` : `禁止 · ${user?.name}`" width="560"
              :ok-text="grant.effect === 'ALLOW' ? '授予' : '禁止'" :danger="grant.effect === 'DENY'" :on-ok="submitGrant">
      <Alert v-if="grant.effect === 'DENY'" type="warning" show-icon>禁止优先于任何授予：即使此人通过角色、岗位或组织获得该权限，也会被拒绝。</Alert>
      <Form :label-width="80">
        <FormItem label="对象">
          <RadioGroup v-model="grant.target" type="button" size="small"><Radio label="perm">权限点</Radio><Radio label="resource">资源</Radio></RadioGroup>
        </FormItem>
        <FormItem v-if="grant.target === 'perm'" label="权限点" required>
          <Select v-model="grant.permCode" filterable transfer placeholder="搜索权限点">
            <Option v-for="p in grantablePerms" :key="p.code" :value="p.code" :label="`${p.name}（${p.code}）`">
              {{ p.name }} <span class="pds-muted pds-mono">{{ p.code }}</span>
            </Option>
          </Select>
        </FormItem>
        <template v-else>
          <FormItem label="资源" required>
            <Select v-model="grant.resKey" filterable transfer placeholder="搜索资源">
              <Option v-for="o in allResourceOptions" :key="o.key" :value="o.key">{{ o.label }}</Option>
            </Select>
          </FormItem>
          <FormItem label="动作">
            <RadioGroup v-model="grant.action" type="button" size="small"><Radio v-for="(l, k) in ACTIONS" :key="k" :label="k">{{ l }}</Radio></RadioGroup>
            <div class="pds-muted mt-1">{{ grant.effect === 'ALLOW' ? '授予高级动作自动包含低级动作（管理 ⊇ 编辑 ⊇ 查看）' : '禁止低级动作会同时禁止更高级动作' }}</div>
          </FormItem>
        </template>
        <FormItem label="原因" required><Input v-model="grant.reason" type="textarea" :rows="2" placeholder="将记入审计，如：票务知识联络人" /></FormItem>
        <FormItem label="有效期至"><DatePicker v-model="grant.validTo" type="datetime" placeholder="不填为长期" transfer style="width: 100%" :options="{ disabledDate: (d: Date) => d.getTime() < Date.now() - 86400000 }" /></FormItem>
      </Form>
    </PdsModal>

    <!-- 设置有效期 -->
    <PdsModal v-model="validity.open" :title="`设置有效期 · ${user?.name}`" width="640" ok-text="保存" :on-ok="submitValidity">
      <p class="pds-muted mt-0">有效期设置在人员明细记录（个人直授 / 个人禁止）上；角色绑定、组织与岗位上的授权有效期请在"分配库"中调整。</p>
      <div v-for="r in validity.rows" :key="r.rec.id" class="mb-2 flex items-center gap-3 rounded-xl bg-row px-4 py-3">
        <span class="pds-badge" :class="r.rec.effect === 'ALLOW' ? 'dark' : 'bad'">{{ r.rec.effect === 'ALLOW' ? '个人直授' : '个人禁止' }}</span>
        <span class="min-w-0 flex-1 truncate text-[13px]">{{ r.rec.permName || r.rec.resourceName }}<span v-if="r.rec.action" class="pds-muted">（{{ ACTIONS[r.rec.action] }}）</span></span>
        <Checkbox v-model="r.forever">长期</Checkbox>
        <DatePicker v-model="r.value" type="date" :disabled="r.forever" placeholder="失效日期" transfer style="width: 150px"
                    :options="{ disabledDate: (d: Date) => d.getTime() < Date.now() }" />
        <button v-if="canGrant" class="pds-link red" @click="revoke(r.rec); validity.open = false">撤销</button>
      </div>
    </PdsModal>
  </div>
</template>
