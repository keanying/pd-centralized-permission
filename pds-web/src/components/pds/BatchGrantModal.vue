<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Message } from 'view-ui-plus'
import { IconAlertTriangle, IconSync, IconClose, IconPlus } from '@kousum/semi-icons-vue'
import {
  api, ACTIONS, DATA_SCOPES, SUBJECT_TYPES, type Impact, type ObjectRefDto, type ResourceType, type ResourceView, type RoleView,
  type SubjectRefDto,
} from '@/api'
import PdsModal from './PdsModal.vue'
import SubjectPicker from './SubjectPicker.vue'
import PermNodePicker from './PermNodePicker.vue'

/**
 * 批量授权 · 影响分析（设计稿）：左侧选择主体、授权对象、有效期与原因；右侧实时显示影响人数、新增高风险权限、
 * 职责分离冲突与超出可授权范围的情况。确认后全部成功或全部回滚。
 */
const open = defineModel<boolean>({ default: false })
const props = defineProps<{ presetSubjects?: (SubjectRefDto & { name: string })[]; presetObjects?: (ObjectRefDto & { name: string })[]; app?: string }>()
const emit = defineEmits<{ done: [] }>()

const subjects = ref<(SubjectRefDto & { name: string })[]>([])
const objects = ref<(ObjectRefDto & { name: string })[]>([])
const form = reactive({ effect: 'ALLOW', validTo: '' as any, reason: '', dataScope: '' })
const picker = reactive({ type: 'USER', id: '', includeChildren: true })
const objTab = ref<'PERM' | 'ROLE' | 'RESOURCE'>('PERM')
const permCodes = ref<string[]>([])
const roleIds = ref<string[]>([])
const res = reactive({ type: '', refId: '', action: 'READ' })
const roles = ref<RoleView[]>([])
const resTypes = ref<ResourceType[]>([])
const resources = ref<ResourceView[]>([])
const impact = ref<Impact | null>(null)
const analysing = ref(false)

watch(open, (v) => {
  if (!v) return
  subjects.value = [...(props.presetSubjects || [])]
  objects.value = [...(props.presetObjects || [])]
  Object.assign(form, { effect: 'ALLOW', validTo: '', reason: '', dataScope: '' })
  permCodes.value = []
  roleIds.value = []
  impact.value = null
  analyse()
})
onMounted(async () => {
  const [r, t] = await Promise.all([api.roles(), api.resourceTypes()])
  roles.value = r.filter((x) => x.status !== 0)
  resTypes.value = t
})
watch(() => res.type, async (t) => { res.refId = ''; resources.value = t ? (await api.resources({ type: t, pageSize: 100 })).items : [] })

async function nameOf(type: string, id: string): Promise<string> {
  if (type === 'USER') { const u = await api.user(id); return `${u.name}${u.empNo ? ' ' + u.empNo : ''}` }
  if (type === 'POSITION') return (await api.positions()).find((p) => p.id === id)?.name || id
  if (type === 'GROUP') return (await api.groups()).find((g) => g.id === id)?.name || id
  const o = await api.orgInfo(id)
  return o.name
}
async function addSubject() {
  if (!picker.id) { Message.warning('请选择主体'); return }
  if (subjects.value.some((s) => s.type === picker.type && s.id === picker.id)) { picker.id = ''; return }
  const name = await nameOf(picker.type, picker.id)
  subjects.value.push({ type: picker.type, id: picker.id, includeChildren: picker.type === 'ORG' ? picker.includeChildren : undefined, name })
  picker.id = ''
}
function addObjects() {
  if (objTab.value === 'PERM') {
    permCodes.value.forEach((c) => { if (!objects.value.some((o) => o.type === 'PERM' && o.code === c)) objects.value.push({ type: 'PERM', code: c, name: c }) })
    permCodes.value = []
  } else if (objTab.value === 'ROLE') {
    roleIds.value.forEach((id) => {
      const r = roles.value.find((x) => x.id === id)
      if (!objects.value.some((o) => o.type === 'ROLE' && o.roleId === id)) objects.value.push({ type: 'ROLE', roleId: id, name: `角色：${r?.name || id}` })
    })
    roleIds.value = []
  } else {
    if (!res.type || !res.refId) { Message.warning('请选择资源'); return }
    const r = resources.value.find((x) => x.refId === res.refId)
    objects.value.push({ type: 'RESOURCE', resourceType: res.type, refId: res.refId, action: res.action, name: `${r?.name || res.refId} · ${ACTIONS[res.action]}` })
    res.refId = ''
  }
}

const payload = computed(() => ({
  subjects: subjects.value.map(({ type, id, includeChildren }) => ({ type, id, includeChildren })),
  objects: objects.value.map(({ type, code, roleId, resourceType, refId, action }) => ({ type, code, roleId, resourceType, refId, action })),
  effect: form.effect,
  dataScope: form.dataScope || undefined,
  validTo: form.validTo ? new Date(form.validTo).toISOString() : undefined,
  reason: form.reason.trim() || undefined,
}))
const ready = computed(() => subjects.value.length > 0 && objects.value.length > 0)

let timer: number | undefined
function analyse() {
  window.clearTimeout(timer)
  if (!ready.value) { impact.value = null; return }
  timer = window.setTimeout(async () => {
    analysing.value = true
    try { impact.value = await api.impact(payload.value) } catch { impact.value = null } finally { analysing.value = false }
  }, 350)
}
watch([subjects, objects, () => form.effect, () => form.validTo], analyse, { deep: true })

const blocking = computed(() => !!impact.value && (impact.value.exceeding.length > 0 || impact.value.outOfScope > 0 || impact.value.conflicts.length > 0))
async function submit() {
  if (!ready.value) { Message.warning('请至少选择一个主体和一个授权对象'); return false }
  if (!form.reason.trim()) { Message.warning('请填写授权原因'); return false }
  if (form.effect === 'DENY' && objects.value.some((o) => o.type === 'ROLE')) { Message.warning('角色只能授予，不能设为禁止'); return false }
  const r = await api.batchGrant(payload.value)
  Message.success(`已生成 ${r.created} 条授权记录`)
  emit('done')
}
</script>

<template>
  <PdsModal v-model="open" title="批量授权 · 影响分析" width="1000" ok-text="确认授权" :on-ok="submit">
    <div class="grid grid-cols-[1fr_380px] gap-6">
      <!-- 左：选择 -->
      <div class="pds-form flex min-w-0 flex-col gap-4">
        <div>
          <div class="pds-label">已选主体（{{ subjects.length }}）</div>
          <div class="mb-2 flex min-h-[28px] flex-wrap gap-2">
            <span v-for="(s, i) in subjects" :key="s.type + s.id" class="pds-badge gray" style="height: 28px; padding: 0 10px">
              <span class="mr-1 text-[#9CA3AF]">{{ SUBJECT_TYPES[s.type] }}：</span>{{ s.name }}<template v-if="s.type === 'ORG' && s.includeChildren">（含下级）</template>
              <IconClose size="extra-small" class="ml-1 cursor-pointer text-[#9CA3AF]" @click="subjects.splice(i, 1)" />
            </span>
            <span v-if="!subjects.length" class="pds-muted leading-7">从下方选择人员、岗位、组织或用户组</span>
          </div>
          <div class="flex items-start gap-2 rounded-2xl bg-row p-3">
            <div class="min-w-0 flex-1"><SubjectPicker v-model:type="picker.type" v-model:id="picker.id" v-model:include-children="picker.includeChildren" /></div>
            <button class="pds-btn dark sm mt-8" @click="addSubject"><IconPlus size="small" /> 添加</button>
          </div>
        </div>

        <div>
          <div class="pds-label">授权对象（{{ objects.length }}）</div>
          <div class="mb-2 flex min-h-[28px] flex-wrap gap-2">
            <span v-for="(o, i) in objects" :key="i" class="pds-badge bad" style="height: 28px; padding: 0 10px">
              <span :class="o.type === 'PERM' ? 'pds-mono' : ''">{{ o.name }}</span>
              <IconClose size="extra-small" class="ml-1 cursor-pointer opacity-60" @click="objects.splice(i, 1)" />
            </span>
            <span v-if="!objects.length" class="pds-muted leading-7">选择权限节点、角色或资源实例</span>
          </div>
          <div class="rounded-2xl bg-row p-3">
            <div class="pds-segment mb-2" style="background: #fff">
              <button :class="{ on: objTab === 'PERM' }" @click="objTab = 'PERM'">权限节点</button>
              <button :class="{ on: objTab === 'ROLE' }" @click="objTab = 'ROLE'">角色</button>
              <button :class="{ on: objTab === 'RESOURCE' }" @click="objTab = 'RESOURCE'">资源实例</button>
            </div>
            <div class="flex items-start gap-2">
              <div class="min-w-0 flex-1">
                <PermNodePicker v-if="objTab === 'PERM'" v-model="permCodes" :app="app" />
                <Select v-else-if="objTab === 'ROLE'" v-model="roleIds" multiple filterable transfer placeholder="选择角色">
                  <Option v-for="r in roles" :key="r.id" :value="r.id" :label="r.name">{{ r.name }} <span class="pds-mono text-[#9CA3AF]">{{ r.code }}</span></Option>
                </Select>
                <div v-else class="grid grid-cols-[1fr_1fr_110px] gap-2">
                  <Select v-model="res.type" transfer placeholder="资源类型"><Option v-for="t in resTypes" :key="t.typeCode" :value="t.typeCode">{{ t.name }}</Option></Select>
                  <Select v-model="res.refId" filterable transfer placeholder="资源实例"><Option v-for="r in resources" :key="r.id" :value="r.refId">{{ r.name }}</Option></Select>
                  <Select v-model="res.action" transfer><Option v-for="(l, k) in ACTIONS" :key="k" :value="k">{{ l }}</Option></Select>
                </div>
              </div>
              <button class="pds-btn dark sm" @click="addObjects"><IconPlus size="small" /> 添加</button>
            </div>
          </div>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <div class="pds-label">效果</div>
            <div class="pds-seg"><button :class="['low', { on: form.effect === 'ALLOW' }]" @click="form.effect = 'ALLOW'">允许</button>
              <button :class="['high', { on: form.effect === 'DENY' }]" @click="form.effect = 'DENY'">禁止</button></div>
          </div>
          <div>
            <div class="pds-label">有效期</div>
            <DatePicker v-model="form.validTo" type="date" placeholder="不填为长期" transfer size="large" style="width: 100%" :options="{ disabledDate: (d: Date) => d.getTime() < Date.now() - 86400000 }" />
          </div>
          <div v-if="objects.some((o) => o.type === 'PERM')" class="col-span-2">
            <div class="pds-label">数据范围（可选，仅对权限节点生效）</div>
            <Select v-model="form.dataScope" clearable transfer placeholder="默认：本人景区">
              <Option v-for="(l, k) in DATA_SCOPES" :key="k" :value="k" v-show="!['SCENIC_LIST', 'CUSTOM', 'DOMAIN'].includes(k)">{{ l }}</Option>
            </Select>
          </div>
        </div>
        <div>
          <div class="pds-label">原因</div>
          <Input v-model="form.reason" type="textarea" :rows="2" placeholder="如：旺季票务保障需要临时扩展权限" />
        </div>
      </div>

      <!-- 右：分析结果 -->
      <div class="self-start rounded-[20px] bg-row p-5">
        <div class="mb-4 flex items-center justify-between"><span class="text-[15px] font-semibold text-ink">分析结果</span>
          <span v-if="analysing" class="pds-muted">计算中…</span></div>
        <template v-if="impact">
          <div class="mb-3 flex items-center justify-between rounded-2xl bg-white px-4 py-3.5">
            <span class="text-[14px] text-ink">影响人数</span>
            <span class="text-[20px] font-bold text-ink">{{ impact.affectedUsers }} <span class="text-[14px] font-normal">人</span></span>
          </div>
          <div class="mb-3 rounded-2xl px-4 py-3.5" :class="impact.highRiskCount ? 'bg-danger-soft' : 'bg-white'">
            <div class="flex items-center justify-between">
              <span class="flex items-center gap-1.5 text-[14px]" :class="impact.highRiskCount ? 'text-danger' : 'text-ink'"><IconAlertTriangle size="small" /> 新增高风险权限</span>
              <span class="font-semibold" :class="impact.highRiskCount ? 'text-danger' : 'text-ink'">{{ impact.highRiskCount }} 项</span>
            </div>
            <div v-if="impact.highRiskAdded.length" class="mt-1 text-[12px] text-danger">{{ impact.highRiskAdded.join('、') }}</div>
          </div>
          <div class="mb-3 rounded-2xl px-4 py-3.5" :class="impact.conflicts.length ? 'bg-warn-soft-2' : 'bg-white'">
            <div class="flex items-center justify-between">
              <span class="flex items-center gap-1.5 text-[14px]" :class="impact.conflicts.length ? 'text-warn' : 'text-ink'"><IconSync size="small" /> 职责分离冲突</span>
              <span class="font-semibold" :class="impact.conflicts.length ? 'text-warn' : 'text-ink'">{{ impact.conflicts.length }} 处</span>
            </div>
            <div v-for="c in impact.conflicts.slice(0, 3)" :key="c.userName + c.detail" class="mt-1 text-[12px] text-warn">{{ c.userName }}：{{ c.detail }}</div>
          </div>
          <div class="mb-3 rounded-2xl bg-white px-4 py-3.5">
            <div class="flex items-center justify-between">
              <span class="text-[14px] text-[#6B7280]">超出你的可授权范围</span>
              <span class="font-semibold text-[#6B7280]">{{ impact.outOfScope + impact.exceeding.length }} {{ impact.exceeding.length ? '项' : '人' }}</span>
            </div>
            <div v-if="impact.exceeding.length" class="mt-1 text-[12px] text-[#6B7280]">你未拥有：{{ impact.exceeding.join('、') }}</div>
            <div v-if="impact.outOfScopeNames.length" class="mt-1 text-[12px] text-[#6B7280]">不在管理范围：{{ impact.outOfScopeNames.join('、') }}</div>
          </div>
          <div v-if="impact.lostPerms" class="mb-3 rounded-2xl bg-white px-4 py-3 text-[13px] text-[#4B5563]">将失去 {{ impact.lostPerms }} 项权限（禁止生效）</div>
          <div v-if="impact.users.length" class="max-h-[160px] overflow-auto rounded-2xl bg-white px-4 py-2">
            <div v-for="u in impact.users" :key="u.userId" class="flex items-center justify-between py-1.5 text-[12px]">
              <span class="text-ink">{{ u.name }} <span class="text-[#9CA3AF]">{{ u.empNo }}</span></span>
              <span class="text-[#6B7280]">+{{ u.added }}<template v-if="u.lost"> / −{{ u.lost }}</template><span v-if="u.highRisk.length" class="ml-1 text-danger">高风险 {{ u.highRisk.length }}</span></span>
            </div>
          </div>
          <p v-if="impact.sampled < impact.affectedUsers" class="pds-muted mb-0 mt-2">人数较多，已按前 {{ impact.sampled }} 人逐一计算。</p>
          <p v-if="blocking" class="mb-0 mt-3 text-[12px] text-danger">存在冲突或越权项，确认授权时将被拒绝，请返回修改。</p>
        </template>
        <div v-else class="pds-muted py-12 text-center">选择主体与授权对象后自动分析</div>
      </div>
    </div>
  </PdsModal>
</template>
