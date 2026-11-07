<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, flattenOrgs, SUBJECT_TYPES, type GroupView, type Position, type OrgNode } from '@/api'
import UserSelect from './UserSelect.vue'

/** 授权对象选择：人员 / 岗位 / 组织（可含下级）/ 用户组。 */
const subjectType = defineModel<string>('type', { default: 'USER' })
const subjectId = defineModel<string>('id', { default: '' })
const includeChildren = defineModel<boolean>('includeChildren', { default: true })

const orgs = ref<(OrgNode & { depth: number })[]>([])
const positions = ref<Position[]>([])
const groups = ref<GroupView[]>([])

onMounted(async () => {
  const [t, p, g] = await Promise.all([api.orgTree(), api.positions(), api.groups()])
  orgs.value = flattenOrgs(t)
  positions.value = p
  groups.value = g
})
watch(subjectType, () => (subjectId.value = ''))
</script>

<template>
  <div class="flex flex-col gap-2">
    <RadioGroup v-model="subjectType" type="button" button-style="solid" size="small">
      <Radio v-for="(label, key) in SUBJECT_TYPES" :key="key" :label="key">{{ label }}</Radio>
    </RadioGroup>
    <UserSelect v-if="subjectType === 'USER'" v-model="subjectId" />
    <Select v-else-if="subjectType === 'POSITION'" v-model="subjectId" filterable placeholder="选择岗位" transfer>
      <Option v-for="p in positions" :key="p.id" :value="p.id" :label="p.name">{{ p.name }} <span class="pds-muted">{{ p.code }}</span></Option>
    </Select>
    <template v-else-if="subjectType === 'ORG'">
      <Select v-model="subjectId" filterable placeholder="选择组织" transfer>
        <Option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name">
          <span :style="{ paddingLeft: o.depth * 14 + 'px' }">{{ o.name }}</span>
        </Option>
      </Select>
      <Checkbox v-model="includeChildren">包含下级组织</Checkbox>
    </template>
    <Select v-else v-model="subjectId" filterable placeholder="选择用户组" transfer>
      <Option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name">{{ g.name }} <span class="pds-muted">{{ g.memberCount }} 人</span></Option>
    </Select>
  </div>
</template>
