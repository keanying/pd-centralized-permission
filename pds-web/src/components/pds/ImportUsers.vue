<script setup lang="ts">
import { computed, ref } from 'vue'
import { Message } from 'view-ui-plus'
import { IconUpload, IconDownload } from '@kousum/semi-icons-vue'
import { api } from '@/api'
import { parseCsv, downloadCsv } from '@/lib/utils'

/** 批量导入人员：① 下载模板 ② 上传 CSV 预览校验 ③ 导入并查看逐行结果（可下载含初始密码的结果文件）。 */
const open = defineModel<boolean>({ default: false })
const emit = defineEmits<{ done: [] }>()
const HEAD = ['账号', '工号', '姓名', '手机', '邮箱', '人员类型', '组织编码', '岗位编码']
const TYPE_MAP: Record<string, string> = { 正式员工: 'FULL_TIME', 正式: 'FULL_TIME', 劳务派遣: 'OUTSOURCE', 外包: 'OUTSOURCE', 实习生: 'INTERN', 实习: 'INTERN', 临时工: 'TEMP', 临时: 'TEMP' }

const step = ref<1 | 2 | 3>(1)
const rows = ref<string[][]>([])
const results = ref<{ row: number; ok: boolean; message: string; username: string; initialPassword?: string }[]>([])
const busy = ref(false)
const fileInput = ref<HTMLInputElement>()

const rowErrors = computed(() => rows.value.map((r) => {
  if (!r[0]?.trim()) return '缺少账号'
  if (!r[2]?.trim()) return '缺少姓名'
  if (!r[6]?.trim()) return '缺少组织编码'
  return ''
}))
const validCount = computed(() => rowErrors.value.filter((e) => !e).length)

function reset() {
  step.value = 1
  rows.value = []
  results.value = []
}
function template() {
  downloadCsv('人员导入模板.csv', [HEAD, ['zhangsan', 'S001023', '张三', '13812341234', '', '正式员工', 'XH_TICKET', 'SELLER']])
}
async function pick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  const all = parseCsv(await f.text())
  if (!all.length || all[0][0]?.trim() !== '账号') return Message.error('文件格式不正确，请使用模板（首行为表头"账号,工号,姓名,…"）')
  rows.value = all.slice(1)
  if (!rows.value.length) return Message.warning('文件中没有数据行')
  step.value = 2
  ;(e.target as HTMLInputElement).value = ''
}
async function submit() {
  busy.value = true
  try {
    const payload = rows.value.filter((_, i) => !rowErrors.value[i]).map((r) => ({
      username: r[0]?.trim(), empNo: r[1]?.trim() || undefined, name: r[2]?.trim(), mobile: r[3]?.trim() || undefined,
      email: r[4]?.trim() || undefined, userType: TYPE_MAP[r[5]?.trim()] || undefined, orgCode: r[6]?.trim(), positionCode: r[7]?.trim() || undefined,
    }))
    results.value = await api.importUsers(payload)
    step.value = 3
    emit('done')
  } finally {
    busy.value = false
  }
}
function exportResults() {
  downloadCsv('人员导入结果（含初始密码，请妥善保管）.csv', [['行号', '账号', '结果', '说明', '初始密码'],
    ...results.value.map((r) => [r.row, r.username, r.ok ? '成功' : '失败', r.message, r.initialPassword || ''])])
}
const okCount = computed(() => results.value.filter((r) => r.ok).length)
</script>

<template>
  <Modal v-model="open" title="批量导入人员" width="760" :mask-closable="false" @on-visible-change="(v: boolean) => !v && reset()">
    <Steps :current="step - 1" size="small" class="mb-5">
      <Step title="下载模板" /><Step title="上传并预览" /><Step title="导入结果" />
    </Steps>

    <div v-if="step === 1" class="flex flex-col items-center gap-4 rounded-2xl bg-row py-10">
      <p class="m-0 text-sm text-[#374151]">按模板填写后另存为 <b>CSV（UTF-8）</b> 上传；组织、岗位填写编码（可在组织树中查看）。</p>
      <div class="flex gap-3">
        <button class="pds-btn ghost" @click="template"><IconDownload size="small" /> 下载模板</button>
        <button class="pds-btn dark" @click="fileInput?.click()"><IconUpload size="small" /> 选择文件</button>
      </div>
      <input ref="fileInput" type="file" accept=".csv,text/csv" class="hidden" @change="pick" />
      <p class="pds-muted m-0">单次最多 1000 人；初始密码由系统生成，首次登录须修改。</p>
    </div>

    <template v-else-if="step === 2">
      <p class="mb-3 mt-0 text-sm">共 {{ rows.length }} 行，可导入 <b class="text-accent-text">{{ validCount }}</b> 行<span v-if="rows.length - validCount">，<b class="text-danger">{{ rows.length - validCount }}</b> 行缺少必填项将跳过</span>。</p>
      <div class="max-h-[340px] overflow-auto">
        <table class="pds-table">
          <thead><tr><th>#</th><th v-for="h in HEAD" :key="h">{{ h }}</th><th>校验</th></tr></thead>
          <tbody>
            <tr v-for="(r, i) in rows" :key="i">
              <td>{{ i + 1 }}</td><td v-for="(_, j) in HEAD" :key="j">{{ r[j] || '—' }}</td>
              <td><span class="pds-badge" :class="rowErrors[i] ? 'bad' : 'ok'">{{ rowErrors[i] || '通过' }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else>
      <Alert :type="okCount === results.length ? 'success' : 'warning'" show-icon>
        成功 {{ okCount }} 人，失败 {{ results.length - okCount }} 人。初始密码仅在此显示一次，请下载结果文件并安全分发。
      </Alert>
      <div class="max-h-[320px] overflow-auto">
        <table class="pds-table">
          <thead><tr><th>行号</th><th>账号</th><th>结果</th><th>说明</th><th>初始密码</th></tr></thead>
          <tbody>
            <tr v-for="r in results" :key="r.row">
              <td>{{ r.row }}</td><td>{{ r.username }}</td>
              <td><span class="pds-badge" :class="r.ok ? 'ok' : 'bad'">{{ r.ok ? '成功' : '失败' }}</span></td>
              <td class="text-[13px]">{{ r.message }}</td><td class="pds-mono">{{ r.initialPassword || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template #footer>
      <div class="flex justify-between">
        <button v-if="step === 2" class="pds-btn ghost sm" @click="reset">重新选择</button><span v-else />
        <div class="flex gap-2">
          <button v-if="step === 3" class="pds-btn ghost sm" @click="exportResults"><IconDownload size="small" /> 下载结果</button>
          <button v-if="step === 2" class="pds-btn dark sm" :disabled="!validCount || busy" @click="submit">{{ busy ? '导入中…' : `导入 ${validCount} 人` }}</button>
          <button v-else class="pds-btn ghost sm" @click="open = false">{{ step === 3 ? '完成' : '取消' }}</button>
        </div>
      </div>
    </template>
  </Modal>
</template>
