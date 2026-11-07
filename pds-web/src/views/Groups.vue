<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from 'view-ui-plus'
import { IconPlus, IconUserGroup } from '@kousum/semi-icons-vue'
import { api, type GroupView } from '@/api'
import { useSession } from '@/stores/session'
import UserSelect from '@/components/pds/UserSelect.vue'

const session = useSession()
const canManage = computed(() => session.has('pds:group:manage'))
const groups = ref<GroupView[]>([])
const load = async () => (groups.value = await api.groups())
onMounted(load)

const form = reactive({ open: false, code: '', name: '', description: '' })
async function create() {
  if (!form.code || !form.name) return Message.warning('请填写编码与名称')
  await api.createGroup({ code: form.code, name: form.name, description: form.description || undefined })
  Message.success('已创建')
  form.open = false
  load()
}

const members = reactive({ open: false, group: null as GroupView | null, ids: [] as string[], current: [] as { id: string; name: string; username: string }[] })
const picker = ref<InstanceType<typeof UserSelect>>()
async function openMembers(g: GroupView) {
  members.group = g
  members.current = await api.groupMembers(g.id)
  members.ids = members.current.map((m) => m.id)
  members.open = true
  // 让已有成员出现在下拉选项中
  if (picker.value) picker.value.options = [...members.current.map((m) => ({ ...m, primaryOrgName: '' })) as any, ...picker.value.options]
}
async function saveMembers() {
  await api.setGroupMembers(members.group!.id, members.ids)
  Message.success('已保存成员')
  members.open = false
  load()
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="pds-card">
      <div class="pds-toolbar">
        <span class="pds-muted">用户组用于把跨组织的人员归为一类（如"应急小组""知识管理员"），整体授予角色或资源。</span>
        <span class="grow" />
        <Button v-if="canManage" type="primary" @click="form.open = true"><IconPlus size="small" /> 新增用户组</Button>
      </div>
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <div v-for="g in groups" :key="g.id" class="rounded-2xl border border-line p-5 transition-shadow hover:shadow-md">
          <div class="flex items-start justify-between">
            <div class="flex items-center gap-3">
              <span class="flex size-10 items-center justify-center rounded-xl bg-brand-soft text-brand"><IconUserGroup /></span>
              <div>
                <div class="font-semibold text-ink">{{ g.name }}</div>
                <div class="pds-muted">{{ g.code }}</div>
              </div>
            </div>
            <Tag>{{ g.memberCount }} 人</Tag>
          </div>
          <p class="pds-muted my-3 min-h-[18px]">{{ g.description || '—' }}</p>
          <Button v-if="canManage" size="small" @click="openMembers(g)">管理成员</Button>
        </div>
        <div v-if="!groups.length" class="pds-muted py-8">暂无用户组</div>
      </div>
    </div>

    <Modal v-model="form.open" title="新增用户组" @on-ok="create">
      <Form :label-width="70">
        <FormItem label="编码"><Input v-model="form.code" placeholder="如 EMERGENCY_TEAM" /></FormItem>
        <FormItem label="名称"><Input v-model="form.name" placeholder="如 应急小组" /></FormItem>
        <FormItem label="说明"><Input v-model="form.description" type="textarea" :rows="2" /></FormItem>
      </Form>
    </Modal>

    <Modal v-model="members.open" :title="`成员：${members.group?.name}`" width="560" @on-ok="saveMembers">
      <UserSelect ref="picker" v-model="members.ids" multiple placeholder="搜索并添加成员" />
      <p class="pds-muted mt-3">保存后，成员通过该用户组获得的角色与资源权限立即生效。</p>
    </Modal>
  </div>
</template>
