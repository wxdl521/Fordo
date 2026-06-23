<template>
  <div :style="pageStyle">
    <!-- 工具条 -->
    <div :style="toolbarStyle">
      <div :style="tabGroupStyle">
        <button v-for="t in tabDefs" :key="t.key" @click="setTab(t.key)" :style="tabBtnStyle(tab === t.key)">
          {{ t.label }}
        </button>
      </div>
      <div :style="filterGroupStyle">
        <span :style="filterLabelStyle">置信度</span>
        <div :style="confGroupStyle">
          <button v-for="c in confOptions" :key="c.value" @click="setConf(c.value)" :style="confBtnStyle(conf === c.value)">
            {{ c.label }}
          </button>
        </div>
      </div>
      <div :style="filterGroupStyle">
        <span :style="filterLabelStyle">知识点</span>
        <select v-model="nodeCode" :style="selectStyle">
          <option value="">全部</option>
          <option v-for="node in graphNodes" :key="node.nodeCode" :value="node.nodeCode">
            {{ node.name }}
          </option>
        </select>
      </div>
      <span :style="infoStyle" v-if="data">共 {{ data.total }} 题</span>
      <button @click="showTools = !showTools" :style="toolsToggleStyle">
        {{ showTools ? '收起工具' : '批量工具' }}
      </button>
    </div>

    <!-- 批量工具面板 -->
    <div v-if="showTools" :style="toolsPanelStyle">
      <!-- AI 出题 -->
      <div :style="toolBlockStyle">
        <div :style="toolTitleStyle">AI 出题</div>
        <div :style="toolRowStyle">
          <select v-model="genNodeCode" :style="selectStyle">
            <option v-for="node in graphNodes" :key="node.nodeCode" :value="node.nodeCode">
              {{ node.nodeCode }} · {{ node.name }}
            </option>
          </select>
          <input v-model.number="genCount" type="number" min="1" max="20" :style="numInputStyle" />
          <button @click="handleGenerate" :disabled="genLoading" :style="toolBtnStyle">
            {{ genLoading ? '生成中…' : '生成' }}
          </button>
        </div>
        <div v-if="genResult" :style="toolResultStyle">
          生成 <strong>{{ genResult.generated }}</strong> 题 · 去重 <strong>{{ genResult.duplicated }}</strong> · 丢弃 <strong>{{ genResult.dropped }}</strong>
          <span v-if="genResult.message" :style="{ marginLeft: '8px', color: 'var(--text-mut)' }">{{ genResult.message }}</span>
        </div>
        <div v-if="genError" :style="toolErrorStyle">{{ genError }}</div>
      </div>

      <!-- 导入题库 -->
      <div :style="toolBlockStyle">
        <div :style="toolTitleStyle">导入题库</div>
        <div :style="toolRowStyle">
          <div
            @dragover.prevent="importDrag = true"
            @dragleave="importDrag = false"
            @drop.prevent="handleImportDrop"
            @click="$refs.importInput.click()"
            :style="dropZoneStyle(importDrag)"
          >
            <span v-if="!importFile">拖拽或点击选择文件（.json / .xlsx）</span>
            <span v-else>{{ importFile.name }}</span>
          </div>
          <input ref="importInput" type="file" accept=".json,.xlsx,.xls" @change="handleImportSelect" :style="{ display: 'none' }" />
          <button @click="handleImportFile" :disabled="importLoading || !importFile" :style="toolBtnStyle">
            {{ importLoading ? '导入中…' : '导入' }}
          </button>
        </div>
        <div v-if="importResult" :style="toolResultStyle">
          导入 <strong>{{ importResult.imported }}</strong> 题 · 跳过 <strong>{{ importResult.skipped }}</strong> 题
          <span v-if="importResult.aiCleaned" :style="{ marginLeft: '8px', color: 'var(--accent)' }">（AI 清洗）</span>
        </div>
        <div v-if="importError" :style="toolErrorStyle">{{ importError }}</div>
      </div>

      <!-- 标注 -->
      <div :style="toolBlockStyle">
        <div :style="toolTitleStyle">存量题标注</div>
        <textarea v-model="annotateJson" :style="textareaStyle" rows="6" spellcheck="false" placeholder="粘贴 AnnotateRequest JSON: { items: [{ stem, options }] }"></textarea>
        <div :style="toolRowStyle">
          <button @click="handleAnnotate" :disabled="annotateLoading" :style="toolBtnStyle">
            {{ annotateLoading ? '标注中…' : '标注' }}
          </button>
          <span v-if="annotateResult" :style="toolResultStyle">
            标注 <strong>{{ annotateResult.length }}</strong> 题，落库 <strong>{{ annotateResult.filter(r => r.persisted).length }}</strong> 题
          </span>
          <span v-if="annotateError" :style="toolErrorStyle">{{ annotateError }}</span>
        </div>
      </div>
    </div>

    <!-- 批量操作条 -->
    <div v-if="checkedCount > 0" :style="batchBarStyle">
      <span :style="{ fontSize: '13px', fontWeight: 500 }">已选 {{ checkedCount }} 题</span>
      <button @click="batch('pass')" :style="batchPassBtnStyle">批量通过</button>
      <button @click="batch('reject')" :style="batchRejectBtnStyle">批量驳回</button>
      <button @click="clearChecked" :style="cancelBtnStyle">取消选择</button>
    </div>

    <!-- 全选行 -->
    <div v-if="tab === 0 && list.length > 0" :style="selectAllRowStyle">
      <div @click="toggleAll" :style="checkboxStyle(allChecked)">
        {{ allChecked ? '✓' : '' }}
      </div>
      <span @click="toggleAll" :style="selectAllTextStyle">全选当前页</span>
    </div>

    <!-- 题目列表 -->
    <div :style="listStyle">
      <div v-for="(q, idx) in list" :key="q.id" :style="cardStyle(idx, q)">
        <div @click="toggleOne(q)" :style="checkboxStyle(checked[q.id], q.status !== 0)">
          {{ checked[q.id] ? '✓' : '' }}
        </div>
        <div :style="contentStyle">
          <!-- 元信息 -->
          <div :style="metaRowStyle">
            <span :style="nodeTagStyle">{{ q.mainNodeName }}</span>
            <span :style="typeTagStyle">{{ typeText(q.type) }} · 难度 {{ diffText(q.difficulty) }}</span>
            <span :style="confTagStyle(q.confidence)">置信度 {{ q.confidence }}%</span>
            <span :style="idStyle">{{ q.source }}</span>
          </div>

          <!-- 题干 -->
          <div :style="stemStyle">{{ q.stem }}</div>

          <!-- 选项 -->
          <div :style="optionsGridStyle">
            <div v-for="(opt, i) in q.options" :key="i" :style="optionStyle(opt.correct)">
              <span :style="optionKeyStyle(opt.correct)">{{ optionKeys[i] }}.</span>
              <span :style="optionTextStyle">{{ opt.text }}</span>
              <span v-if="opt.correct" :style="checkMarkStyle">✓</span>
            </div>
          </div>

          <!-- 底栏：来源 + 操作 -->
          <div :style="footerStyle">
            <span :style="sourceStyle">创建于 {{ formatDate(q.createdAt) }}</span>
            <div v-if="q.status === 0" :style="actionGroupStyle">
              <button @click="review([q.id], 'pass')" :style="passBtnStyle">通过</button>
              <button @click="review([q.id], 'reject')" :style="rejectBtnStyle">驳回</button>
            </div>
            <span v-else :style="statusTextStyle(q.status)">
              {{ q.status === 1 ? '已通过' : '已驳回' }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 空态 -->
    <div v-if="!loading && list.length === 0" :style="emptyStyle">
      <div :style="emptyTitleStyle">此处无题</div>
      <div :style="emptyDescStyle">当前筛选条件下没有题目。</div>
    </div>

    <!-- 加载态 -->
    <div v-if="loading" :style="loadingStyle">加载中…</div>

    <!-- 分页 -->
    <div v-if="!loading && data && data.total > 0" :style="paginationStyle">
      <button @click="prevPage" :disabled="page === 1" :style="pageBtnStyle(page === 1)">上一页</button>
      <span :style="pageInfoStyle">第 {{ page }} / {{ totalPages }} 页</span>
      <button @click="nextPage" :disabled="page >= totalPages" :style="pageBtnStyle(page >= totalPages)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { fetchQuestions, reviewQuestions, fetchTeacherGraph } from '../api/teacher.js'
import { generateQuestions, annotateQuestions, importQuestionJson, importQuestionExcel } from '../api/admin.js'

const COURSE_ID = 1
const optionKeys = ['A', 'B', 'C', 'D']

// ── 状态 ──
const tab = ref(0) // 0=待审 1=已通过 2=已驳回
const conf = ref('')
const nodeCode = ref('')
const page = ref(1)
const size = ref(20)
const checked = ref({})
const data = ref(null)
const loading = ref(false)

const confOptions = [
  { value: '', label: '全部' },
  { value: 'ge85', label: '≥85%' },
  { value: 'mid', label: '70–84%' },
  { value: 'lt70', label: '<70%' }
]

// ── 批量工具状态 ──
const showTools = ref(false)
const graphNodes = ref([]) // [{nodeCode, name, chapter}]
const genNodeCode = ref('')
const genCount = ref(5)
const genLoading = ref(false)
const genError = ref('')
const genResult = ref(null)
const importLoading = ref(false)
const importError = ref('')
const importResult = ref(null)
const importFile = ref(null)
const importDrag = ref(false)
const annotateJson = ref('{\n  "items": [\n    {\n      "stem": "题目内容",\n      "options": [\n        { "key": "A", "text": "选项A", "correct": true },\n        { "key": "B", "text": "选项B", "correct": false }\n      ]\n    }\n  ]\n}')
const annotateLoading = ref(false)
const annotateError = ref('')
const annotateResult = ref(null)

// ── 计算属性 ──
const tabDefs = computed(() => {
  const counts = data.value?.counts || { pending: 0, passed: 0, rejected: 0 }
  return [
    { key: 0, label: `待审核 ${counts.pending}` },
    { key: 1, label: `已通过 ${counts.passed}` },
    { key: 2, label: `已驳回 ${counts.rejected}` }
  ]
})

const list = computed(() => data.value?.items || [])

const nodeOptions = computed(() => {
  const nodes = []
  const seen = new Set()
  list.value.forEach(q => {
    if (q.mainNodeCode && !seen.has(q.mainNodeCode)) {
      seen.add(q.mainNodeCode)
      nodes.push({ code: q.mainNodeCode, name: q.mainNodeName })
    }
  })
  return nodes.sort((a, b) => a.name.localeCompare(b.name))
})

const checkedCount = computed(() => Object.values(checked.value).filter(Boolean).length)

const allChecked = computed(() => {
  const pending = list.value.filter(q => q.status === 0)
  return pending.length > 0 && pending.every(q => checked.value[q.id])
})

const totalPages = computed(() => {
  if (!data.value || data.value.total === 0) return 1
  return Math.ceil(data.value.total / size.value)
})

// ── 数据加载 ──
async function load() {
  loading.value = true
  try {
    const res = await fetchQuestions({
      courseId: COURSE_ID,
      status: tab.value,
      conf: conf.value || undefined,
      nodeCode: nodeCode.value || undefined,
      page: page.value,
      size: size.value
    })
    data.value = res
  } catch (err) {
    console.error('加载题目失败', err)
  } finally {
    loading.value = false
  }
}

// ── 加载图谱节点 ──
async function loadGraphNodes() {
  try {
    const graph = await fetchTeacherGraph(COURSE_ID)
    graphNodes.value = (graph.nodes || []).sort((a, b) => a.nodeCode.localeCompare(b.nodeCode))
    if (graphNodes.value.length > 0 && !genNodeCode.value) {
      genNodeCode.value = graphNodes.value[0].nodeCode
    }
  } catch (err) {
    console.error('加载图谱节点失败', err)
  }
}

// ── AI 出题 ──
async function handleGenerate() {
  if (!genNodeCode.value) return
  genLoading.value = true
  genError.value = ''
  genResult.value = null
  try {
    genResult.value = await generateQuestions(COURSE_ID, genNodeCode.value, genCount.value)
    await load() // 刷新题目列表
  } catch (e) {
    genError.value = e.message || '出题失败'
  } finally {
    genLoading.value = false
  }
}

// ── 导入题库 ──
function handleImportSelect(e) {
  const f = e.target.files[0]
  if (f) importFile.value = f
}

function handleImportDrop(e) {
  importDrag.value = false
  const f = e.dataTransfer.files[0]
  if (f) importFile.value = f
}

async function handleImportFile() {
  if (!importFile.value) return
  importLoading.value = true
  importError.value = ''
  importResult.value = null
  try {
    const isExcel = /\.(xlsx?|xls)$/i.test(importFile.value.name)
    if (isExcel) {
      importResult.value = await importQuestionExcel(COURSE_ID, importFile.value)
    } else {
      importResult.value = await importQuestionJson(COURSE_ID, importFile.value)
    }
    await load()
  } catch (e) {
    importError.value = e.message || '导入失败'
  } finally {
    importLoading.value = false
  }
}

// ── 标注 ──
async function handleAnnotate() {
  annotateLoading.value = true
  annotateError.value = ''
  annotateResult.value = null
  let parsed
  try {
    parsed = JSON.parse(annotateJson.value)
  } catch {
    annotateError.value = 'JSON 格式有误'
    annotateLoading.value = false
    return
  }
  try {
    annotateResult.value = await annotateQuestions(COURSE_ID, parsed)
    await load()
  } catch (e) {
    annotateError.value = e.message || '标注失败'
  } finally {
    annotateLoading.value = false
  }
}

// ── 操作 ──
function setTab(t) {
  tab.value = t
  checked.value = {}
  page.value = 1
}

function setConf(c) {
  conf.value = c
  checked.value = {}
  page.value = 1
}

function toggleOne(q) {
  if (q.status !== 0) return
  checked.value[q.id] = !checked.value[q.id]
}

function toggleAll() {
  if (allChecked.value) {
    checked.value = {}
  } else {
    const c = { ...checked.value }
    list.value.filter(q => q.status === 0).forEach(q => { c[q.id] = true })
    checked.value = c
  }
}

function clearChecked() {
  checked.value = {}
}

async function review(ids, action) {
  try {
    await reviewQuestions(COURSE_ID, ids, action)
    checked.value = {}
    await load()
  } catch (err) {
    console.error('审核失败', err)
  }
}

async function batch(action) {
  const ids = Object.keys(checked.value).filter(k => checked.value[k]).map(Number)
  if (ids.length === 0) return
  await review(ids, action)
}

function prevPage() {
  if (page.value > 1) {
    page.value--
  }
}

function nextPage() {
  if (page.value < totalPages.value) {
    page.value++
  }
}

// ── 工具 ──
function typeText(type) {
  return type === 'SINGLE_CHOICE' ? '单选' : '多选'
}

function diffText(diff) {
  if (diff === 1) return '●○○'
  if (diff === 2) return '●●○'
  return '●●●'
}

function formatDate(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// ── 监听 ──
watch([tab, conf, nodeCode, page], () => {
  load()
})

onMounted(() => {
  load()
  loadGraphNodes()
})

// ── DEV 钩子 ──
if (import.meta.env.DEV) {
  window.__wjTeacherQuestions = {
    load,
    review: reviewQuestions,
    state: () => data.value,
    setTab: (t) => { tab.value = t },
    check: (id) => { checked.value[id] = true }
  }
}

// ── 样式 ──
const toolsToggleStyle = {
  marginLeft: 'auto',
  height: '32px',
  padding: '0 14px',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  background: 'transparent',
  color: 'var(--text-mut)',
  fontSize: '12.5px',
  cursor: 'pointer',
  transition: 'all 0.2s'
}

const toolsPanelStyle = {
  display: 'flex',
  gap: '16px',
  padding: '16px 32px',
  borderBottom: '1px solid var(--line)',
  background: 'var(--panel)',
  flexWrap: 'wrap'
}

const toolBlockStyle = {
  flex: '1',
  minWidth: '280px',
  background: 'var(--panel-2)',
  border: '1px solid var(--line)',
  borderRadius: '10px',
  padding: '14px 16px'
}

const toolTitleStyle = {
  fontSize: '13px',
  fontWeight: 600,
  marginBottom: '10px',
  color: 'var(--text)'
}

const toolRowStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  flexWrap: 'wrap'
}

const numInputStyle = {
  width: '60px',
  height: '28px',
  padding: '0 8px',
  border: '1px solid var(--line)',
  borderRadius: '6px',
  background: 'var(--panel)',
  color: 'var(--text)',
  fontSize: '12px',
  textAlign: 'center'
}

const toolBtnStyle = {
  height: '32px',
  padding: '0 16px',
  background: 'var(--accent)',
  border: 'none',
  borderRadius: '8px',
  color: '#fff',
  fontSize: '12.5px',
  fontWeight: 500,
  cursor: 'pointer',
  transition: 'opacity 0.2s'
}

function dropZoneStyle(dragging) {
  return {
    flex: 1,
    height: '36px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    border: `1.5px dashed ${dragging ? 'var(--accent)' : 'var(--line)'}`,
    borderRadius: '8px',
    background: dragging ? 'rgba(107,91,71,0.06)' : 'transparent',
    color: 'var(--text-mut)',
    fontSize: '12px',
    cursor: 'pointer',
    transition: 'all 0.2s'
  }
}

const toolResultStyle = {
  fontSize: '12.5px',
  color: 'var(--text)',
  marginTop: '8px'
}

const toolErrorStyle = {
  fontSize: '12.5px',
  color: '#e0a33e',
  marginTop: '6px'
}

const textareaStyle = {
  width: '100%',
  boxSizing: 'border-box',
  background: 'var(--panel)',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  padding: '10px 12px',
  fontSize: '12px',
  fontFamily: "'Consolas', 'Fira Mono', monospace",
  color: 'var(--text)',
  outline: 'none',
  resize: 'vertical',
  marginBottom: '8px'
}

const pageStyle = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  minWidth: '1100px',
  background: 'var(--bg)',
  color: 'var(--text)',
  padding: '0 0 48px'
}

const headerStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '16px 32px',
  borderBottom: '1px solid var(--line)',
  background: 'var(--panel)'
}

const titleStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '12px',
  fontSize: '15px'
}

const courseStyle = {
  fontSize: '13px',
  color: 'var(--text-mut)',
  padding: '2px 10px',
  border: '1px solid var(--line)',
  borderRadius: '6px'
}

const navStyle = {
  display: 'flex',
  gap: '16px'
}

const linkStyle = {
  color: 'var(--text-mut)',
  textDecoration: 'none',
  fontSize: '13px',
  padding: '6px 12px',
  borderRadius: '6px',
  transition: 'color 0.2s, background-color 0.2s'
}

const toolbarStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '16px',
  padding: '16px 32px',
  borderBottom: '1px solid var(--line)',
  flexWrap: 'wrap'
}

const tabGroupStyle = {
  display: 'flex',
  gap: '4px',
  padding: '3px',
  border: '1px solid var(--line)',
  borderRadius: '8px'
}

function tabBtnStyle(active) {
  return {
    height: '32px',
    padding: '0 16px',
    border: 'none',
    borderRadius: '6px',
    fontSize: '13px',
    cursor: 'pointer',
    background: active ? 'var(--panel-2)' : 'transparent',
    color: active ? 'var(--text)' : 'var(--text-mut)',
    transition: 'all 0.2s',
    fontWeight: active ? 500 : 400
  }
}

const filterGroupStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '8px'
}

const filterLabelStyle = {
  fontSize: '12px',
  color: 'var(--text-mut)'
}

const confGroupStyle = {
  display: 'flex',
  gap: '4px',
  padding: '3px',
  border: '1px solid var(--line)',
  borderRadius: '8px'
}

function confBtnStyle(active) {
  return {
    height: '28px',
    padding: '0 12px',
    border: 'none',
    borderRadius: '5px',
    fontSize: '12px',
    cursor: 'pointer',
    background: active ? 'var(--panel-2)' : 'transparent',
    color: active ? 'var(--text)' : 'var(--text-mut)',
    transition: 'all 0.2s'
  }
}

const selectStyle = {
  height: '28px',
  padding: '0 10px',
  border: '1px solid var(--line)',
  borderRadius: '6px',
  background: 'var(--panel)',
  color: 'var(--text)',
  fontSize: '12px',
  cursor: 'pointer'
}

const infoStyle = {
  marginLeft: 'auto',
  fontSize: '12px',
  color: 'var(--text-mut)'
}

const batchBarStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '12px',
  background: 'var(--panel)',
  border: '1.5px solid var(--accent)',
  borderRadius: '10px',
  padding: '12px 20px',
  margin: '16px 32px 0'
}

const batchPassBtnStyle = {
  height: '34px',
  padding: '0 20px',
  background: 'var(--accent)',
  border: 'none',
  borderRadius: '8px',
  color: '#fff',
  fontSize: '13px',
  fontWeight: 500,
  cursor: 'pointer',
  transition: 'opacity 0.2s'
}

const batchRejectBtnStyle = {
  height: '34px',
  padding: '0 18px',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  color: 'var(--text-mut)',
  fontSize: '13px',
  cursor: 'pointer',
  transition: 'all 0.2s'
}

const cancelBtnStyle = {
  marginLeft: 'auto',
  background: 'transparent',
  border: 'none',
  color: 'var(--text-mut)',
  fontSize: '12px',
  cursor: 'pointer',
  textDecoration: 'underline',
  textUnderlineOffset: '3px'
}

const selectAllRowStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  padding: '12px 52px 8px',
  cursor: 'pointer'
}

function checkboxStyle(checked, hidden = false) {
  return {
    width: '17px',
    height: '17px',
    flex: 'none',
    marginTop: '3px',
    boxSizing: 'border-box',
    border: `1.5px solid ${checked ? 'var(--accent)' : 'var(--line)'}`,
    background: checked ? 'var(--accent)' : 'transparent',
    borderRadius: '5px',
    cursor: hidden ? 'default' : 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    color: '#fff',
    fontSize: '11px',
    transition: 'all 0.18s',
    visibility: hidden ? 'hidden' : 'visible'
  }
}

const selectAllTextStyle = {
  fontSize: '12.5px',
  color: 'var(--text-mut)',
  cursor: 'pointer'
}

const listStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: '12px',
  padding: '16px 32px'
}

function cardStyle(idx, q) {
  return {
    background: 'var(--panel)',
    border: `1.5px solid ${checked.value[q.id] ? 'var(--accent)' : 'var(--line)'}`,
    borderRadius: '12px',
    padding: '18px 20px',
    display: 'flex',
    gap: '14px',
    transition: 'all 0.2s',
    animation: `fadeUp 0.4s cubic-bezier(0.22,1,0.36,1) both ${Math.min(idx, 8) * 0.04}s`
  }
}

const contentStyle = {
  flex: 1,
  minWidth: 0
}

const metaRowStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  marginBottom: '10px',
  flexWrap: 'wrap'
}

const nodeTagStyle = {
  fontSize: '11.5px',
  color: 'var(--text-mut)',
  border: '1px solid var(--line)',
  borderRadius: '999px',
  padding: '2px 10px'
}

const typeTagStyle = {
  fontSize: '11.5px',
  color: 'var(--text-mut)'
}

function confTagStyle(conf) {
  return {
    fontSize: '11.5px',
    color: conf < 70 ? '#e0a33e' : 'var(--text-mut)',
    background: conf < 70 ? 'rgba(224, 163, 62, 0.15)' : 'var(--panel-2)',
    borderRadius: '999px',
    padding: '2px 9px'
  }
}

const idStyle = {
  marginLeft: 'auto',
  fontSize: '11px',
  color: 'var(--text-mut)',
  opacity: 0.7
}

const stemStyle = {
  fontSize: '14.5px',
  fontWeight: 500,
  lineHeight: 1.7,
  marginBottom: '12px'
}

const optionsGridStyle = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: '8px 18px',
  marginBottom: '12px'
}

function optionStyle(correct) {
  return {
    display: 'flex',
    alignItems: 'baseline',
    gap: '8px',
    fontSize: '13px',
    color: correct ? 'var(--text)' : 'var(--text-mut)'
  }
}

function optionKeyStyle(correct) {
  return {
    flex: 'none',
    fontWeight: correct ? 600 : 400
  }
}

const optionTextStyle = {
  lineHeight: 1.6
}

const checkMarkStyle = {
  flex: 'none',
  color: 'var(--mastered)',
  fontWeight: 600
}

const footerStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px'
}

const sourceStyle = {
  fontSize: '11.5px',
  color: 'var(--text-mut)',
  opacity: 0.85
}

const actionGroupStyle = {
  marginLeft: 'auto',
  display: 'flex',
  gap: '8px'
}

const passBtnStyle = {
  height: '32px',
  padding: '0 18px',
  background: 'var(--accent)',
  border: 'none',
  borderRadius: '8px',
  color: '#fff',
  fontSize: '12px',
  fontWeight: 500,
  cursor: 'pointer',
  transition: 'opacity 0.2s'
}

const rejectBtnStyle = {
  height: '32px',
  padding: '0 16px',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  color: 'var(--text-mut)',
  fontSize: '12px',
  cursor: 'pointer',
  transition: 'all 0.2s'
}

function statusTextStyle(status) {
  return {
    marginLeft: 'auto',
    fontSize: '12px',
    color: status === 1 ? 'var(--mastered)' : 'var(--text-mut)'
  }
}

const emptyStyle = {
  textAlign: 'center',
  padding: '64px 20px'
}

const emptyTitleStyle = {
  fontSize: '17px',
  letterSpacing: '3px',
  marginBottom: '8px',
  fontFamily: "'Noto Serif SC', serif"
}

const emptyDescStyle = {
  fontSize: '12.5px',
  color: 'var(--text-mut)'
}

const loadingStyle = {
  textAlign: 'center',
  padding: '48px 20px',
  fontSize: '14px',
  color: 'var(--text-mut)'
}

const paginationStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: '16px',
  padding: '24px 32px 0'
}

function pageBtnStyle(disabled) {
  return {
    height: '34px',
    padding: '0 20px',
    border: '1px solid var(--line)',
    borderRadius: '8px',
    background: disabled ? 'transparent' : 'var(--panel)',
    color: disabled ? 'var(--text-mut)' : 'var(--text)',
    fontSize: '13px',
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.5 : 1,
    transition: 'all 0.2s'
  }
}

const pageInfoStyle = {
  fontSize: '13px',
  color: 'var(--text-mut)'
}
</script>

<style scoped>
@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

a:hover {
  background: var(--panel-2);
  color: var(--text);
}

button:not(:disabled):hover {
  opacity: 0.85;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
</style>
