<template>
  <div :style="pageStyle">
    <!-- 状态条 -->
    <div :style="statusBarStyle">
      <span>待复核边 <b :style="{ color: 'var(--text)' }">{{ pending.length }}</b> 条 · 已处理 <b :style="{ color: 'var(--text)' }">{{ processed }}</b> 条</span>
      <div :style="{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '12px' }">
        <div :style="progressWrapStyle">
          <div :style="progressBarStyle"></div>
        </div>
        <span>{{ progressText }}</span>
        <button @click="toggleLinkMode" :style="linkMode ? linkBtnActiveStyle : linkBtnStyle">连线模式</button>
        <button @click="showImportModal = true" :style="importBtnHeaderStyle">导入图谱</button>
        <button @click="openPreview" :style="importBtnHeaderStyle">生成预览图</button>
        <button @click="openHistory" :style="importBtnHeaderStyle">抽取历史</button>
        <label :style="{ ...importBtnHeaderStyle, cursor: 'pointer' }">
          从课程标准生成
          <input type="file" accept="image/png,image/jpeg,image/webp,image/bmp,.pdf,.docx"
                 style="display:none" @change="handleSyllabusImage" />
        </label>
        <span v-if="syllabusBusy" :style="{ marginLeft: '8px', fontSize: '13px', color: 'var(--text-mut)' }">识别中…</span>
        <span v-if="syllabusError" :style="{ marginLeft: '8px', fontSize: '13px', color: 'var(--accent)' }">{{ syllabusError }}</span>
      </div>
    </div>

    <!-- 主体 -->
    <div :style="mainStyle">
      <!-- 图谱画布 -->
      <div :style="{ ...canvasWrapStyle, cursor: linkMode ? 'crosshair' : 'default' }">
        <div ref="chartEl" :style="chartStyle"></div>
        <div v-if="loading" :style="overlayStyle">加载中…</div>
        <div v-else-if="error" :style="{ ...overlayStyle, color: 'var(--accent)' }">
          加载失败：{{ error }}
          <button @click="reload" :style="retryBtnStyle">重试</button>
        </div>

        <!-- 图例 -->
        <div :style="legendStyle">
          <span :style="lgStyle"><svg width="26" height="6"><line x1="0" y1="3" x2="26" y2="3" stroke="var(--accent)" stroke-width="2" stroke-dasharray="6 4" /></svg>待复核边</span>
          <span :style="lgStyle"><svg width="26" height="6"><line x1="0" y1="3" x2="26" y2="3" stroke="var(--mastered)" stroke-width="2" /></svg>已采纳边</span>
          <span :style="lgStyle"><svg width="26" height="6"><line x1="0" y1="3" x2="26" y2="3" stroke="var(--text-mut)" stroke-width="1" opacity="0.5" /></svg>已确认图谱</span>
        </div>

        <!-- 操作提示 -->
        <div :style="hintStyle">
          <button @click="showNodeForm = true" :style="addNodeBtnStyle">+ 新增节点</button>
          <span v-if="linkMode" :style="{ fontSize: '11.5px', color: 'var(--accent)', fontWeight: 500 }">
            {{ linkSource ? `已选起点：${linkSource.name}，请点击终点节点` : '请点击起点节点' }} · Esc 取消
          </span>
          <span v-else :style="{ fontSize: '11.5px', color: 'var(--text-mut)', opacity: 0.8 }">点击节点可编辑/删除 · 双击空白添加节点</span>
        </div>
      </div>

      <!-- 审核列表 -->
      <div :style="sidebarStyle">
        <div :style="sidebarHeadStyle">
          <span :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--text-mut)' }">待复核边</span>
          <span :style="{ fontSize: '12px', color: 'var(--text-mut)', marginLeft: 'auto' }">按置信度排序 · 剩 {{ pending.length }} 条</span>
        </div>

        <div :style="sidebarBodyStyle">
          <!-- 待复核列表 -->
          <div v-for="(edge, i) in pending" :key="edge.id" :style="cardStyle(i)">
            <div :style="{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }">
              <span :style="typeTagStyle">{{ edge.type }}</span>
              <span :style="confTagStyle(edge)">置信度 {{ edge.confidence }}%</span>
              <span :style="{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-mut)', opacity: 0.7 }">ID-{{ edge.id }}</span>
            </div>
            <div :style="{ display: 'flex', alignItems: 'center', gap: '9px', fontSize: '13.5px', fontWeight: 500, marginBottom: '9px' }">
              <span>{{ edge.fromName }}</span>
              <span :style="{ color: 'var(--text-mut)', fontSize: '12px' }">→</span>
              <span>{{ edge.toName }}</span>
            </div>
            <div :style="{ fontSize: '12px', color: 'var(--text-mut)', lineHeight: 1.7, marginBottom: '12px' }">{{ edge.reason }}</div>
            <div :style="{ display: 'flex', gap: '8px' }">
              <button @click="accept(edge.id)" :style="acceptBtnStyle">采纳</button>
              <button @click="reject(edge.id)" :style="rejectBtnStyle">驳回</button>
            </div>
          </div>

          <div v-if="pending.length === 0 && !loading" :style="emptyStyle">
            <div :style="{ fontSize: '16px', letterSpacing: '2px', marginBottom: '8px' }">全部复核完成</div>
            <div :style="{ fontSize: '12.5px', color: 'var(--text-mut)', lineHeight: 1.7 }">所有待复核边已处理完毕。</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增节点表单 -->
    <div v-if="showNodeForm" :style="modalOverlayStyle" @click.self="showNodeForm = false">
      <div :style="modalStyle">
        <div :style="modalHeadStyle">
          <h3 :style="{ margin: 0, fontSize: '15px' }">新增节点</h3>
          <button @click="showNodeForm = false" :style="closeBtnStyle">×</button>
        </div>
        <div :style="modalBodyStyle">
          <div :style="formRowStyle">
            <label :style="labelStyle">节点编码</label>
            <input v-model="nodeForm.nodeCode" :style="inputStyle" placeholder="例：KT99" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">名称</label>
            <input v-model="nodeForm.name" :style="inputStyle" placeholder="例：敏捷开发" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">章节</label>
            <input v-model="nodeForm.chapter" :style="inputStyle" placeholder="例：软件工程概述" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">难度 (1-5)</label>
            <input v-model.number="nodeForm.difficulty" type="number" min="1" max="5" :style="inputStyle" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">是否重点</label>
            <input v-model="nodeForm.isKey" type="checkbox" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">描述</label>
            <textarea v-model="nodeForm.description" :style="textareaStyle" rows="3"></textarea>
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">备注</label>
            <input v-model="nodeForm.note" :style="inputStyle" />
          </div>
        </div>
        <div :style="modalFooterStyle">
          <button @click="submitCreate" :style="submitBtnStyle">创建</button>
          <button @click="showNodeForm = false" :style="cancelBtnStyle">取消</button>
        </div>
      </div>
    </div>

    <!-- 编辑/删除节点表单 -->
    <div v-if="showEditForm" :style="modalOverlayStyle" @click.self="showEditForm = false">
      <div :style="modalStyle">
        <div :style="modalHeadStyle">
          <h3 :style="{ margin: 0, fontSize: '15px' }">编辑节点</h3>
          <button @click="showEditForm = false" :style="closeBtnStyle">×</button>
        </div>
        <div :style="modalBodyStyle">
          <div :style="formRowStyle">
            <label :style="labelStyle">节点编码</label>
            <input v-model="editForm.nodeCode" :style="inputStyle" disabled />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">名称</label>
            <input v-model="editForm.name" :style="inputStyle" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">章节</label>
            <input v-model="editForm.chapter" :style="inputStyle" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">难度 (1-5)</label>
            <input v-model.number="editForm.difficulty" type="number" min="1" max="5" :style="inputStyle" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">是否重点</label>
            <input v-model="editForm.isKey" type="checkbox" />
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">描述</label>
            <textarea v-model="editForm.description" :style="textareaStyle" rows="3"></textarea>
          </div>
          <div :style="formRowStyle">
            <label :style="labelStyle">备注</label>
            <input v-model="editForm.note" :style="inputStyle" />
          </div>
        </div>
        <div :style="modalFooterStyle">
          <button @click="submitUpdate" :style="submitBtnStyle">保存</button>
          <button @click="confirmDelete" :style="deleteBtnStyle">删除</button>
          <button @click="showEditForm = false" :style="cancelBtnStyle">取消</button>
        </div>
      </div>
    </div>

    <!-- 导入图谱弹窗 -->
    <div v-if="showImportModal" :style="modalOverlayStyle" @click.self="closeImportModal">
      <div :style="importModalStyle">
        <div :style="modalHeadStyle">
          <h3 :style="{ margin: 0, fontSize: '15px' }">导入知识图谱</h3>
          <button @click="closeImportModal" :style="closeBtnStyle">×</button>
        </div>
        <div :style="modalBodyStyle">
          <!-- 上传区域 -->
          <div
            v-if="!importResult && !importError"
            :style="dropZoneStyle(isDragging)"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="onFileDrop"
            @click="fileInput && fileInput.click()"
          >
            <input ref="fileInput" type="file" accept=".json,.xlsx,.xls" :style="{ display: 'none' }" @change="onFileSelect" />
            <div :style="{ fontSize: '28px', marginBottom: '10px', opacity: 0.6 }">+</div>
            <div :style="{ fontSize: '14px', color: 'var(--text)', marginBottom: '8px' }">
              {{ importFile ? importFile.name : '拖拽文件到此处，或点击选择' }}
            </div>
            <div :style="{ fontSize: '12px', color: 'var(--text-mut)' }">支持 .json / .xlsx / .xls 格式</div>
          </div>

          <!-- 上传进度 -->
          <div v-if="importUploading" :style="{ marginTop: '16px' }">
            <div :style="{ fontSize: '13px', color: 'var(--text-mut)', marginBottom: '8px' }">
              {{ importFile?.name?.endsWith('.json') ? '正在导入…' : '正在上传并 AI 清洗，请稍候…' }}
            </div>
            <div :style="importProgressWrapStyle">
              <div :style="importProgressBarStyle(importProgress)"></div>
            </div>
            <div :style="{ fontSize: '12px', color: 'var(--text-mut)', marginTop: '6px', textAlign: 'right' }">{{ importProgress }}%</div>
          </div>

          <!-- 导入结果 -->
          <div v-if="importResult" :style="importResultStyle">
            <div :style="{ fontSize: '15px', fontWeight: 600, color: 'var(--mastered)', marginBottom: '14px' }">导入成功</div>
            <div :style="resultRowStyle">
              <span :style="resultLabelStyle">新增节点</span>
              <span :style="resultValueStyle">{{ importResult.nodeCount ?? '-' }}</span>
            </div>
            <div :style="resultRowStyle">
              <span :style="resultLabelStyle">新增边</span>
              <span :style="resultValueStyle">{{ importResult.edgeCount ?? '-' }}</span>
            </div>
            <div v-if="importResult.message" :style="{ fontSize: '12.5px', color: 'var(--text-mut)', marginTop: '10px', lineHeight: 1.6 }">
              {{ importResult.message }}
            </div>
          </div>

          <!-- 错误信息 -->
          <div v-if="importError" :style="importErrorBoxStyle">
            <div :style="{ fontSize: '14px', fontWeight: 500, color: 'var(--accent)', marginBottom: '8px' }">导入失败</div>
            <div :style="{ fontSize: '13px', color: 'var(--text-mut)', lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }">{{ importError }}</div>
          </div>
        </div>
        <div :style="modalFooterStyle">
          <button v-if="!importUploading" @click="closeImportModal" :style="cancelBtnStyle">
            {{ importResult || importError ? '关闭' : '取消' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 预览图弹窗 -->
    <div v-if="showPreview" :style="modalOverlayStyle" @click.self="closePreview">
      <div :style="previewModalStyle">
        <div :style="modalHeadStyle">
          <h3 :style="{ margin: 0, fontSize: '15px' }">知识图谱预览图</h3>
          <button @click="closePreview" :style="closeBtnStyle">×</button>
        </div>
        <div :style="previewBodyStyle">
          <div v-if="previewLoading" :style="{ padding: '60px', textAlign: 'center', color: 'var(--text-mut)' }">
            正在生成预览图，AI 出图较慢请稍候…
          </div>
          <template v-else>
            <div :style="previewMetaStyle">
              <span v-if="previewSource === 'ai'" :style="previewBadgeOk">AI 生成</span>
              <span v-else-if="previewSource === 'ai-repaired'" :style="previewBadgeOk">AI 生成（修复后）</span>
              <span v-else-if="previewSource === 'fallback'" :style="previewBadgeFallback">兜底生成</span>
              <span v-if="previewIssues.length" :style="{ fontSize: '11.5px', color: 'var(--text-mut)' }">
                （AI 未达标：{{ previewIssues.join('；') }}）
              </span>
            </div>
            <div v-if="previewSvg" :style="previewCanvasStyle" v-html="previewSvg"></div>
            <div v-else :style="{ padding: '40px', textAlign: 'center', color: 'var(--accent)' }">
              生成失败，请重试。
              <button @click="openPreview" :style="retryBtnStyle">重试</button>
            </div>
          </template>
        </div>
        <div :style="modalFooterStyle">
          <button v-if="previewSvg && !previewLoading" @click="downloadPreview" :style="submitBtnStyle">下载 .svg</button>
          <button @click="closePreview" :style="cancelBtnStyle">关闭</button>
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <div
      v-if="contextMenu.visible"
      :style="contextMenuStyle(contextMenu.x, contextMenu.y)"
      @click.stop
    >
      <div :style="ctxMenuItemStyle" @click="ctxEdit">编辑</div>
      <div :style="{ ...ctxMenuItemStyle, color: 'var(--weak)' }" @click="ctxDelete">删除</div>
      <div :style="ctxMenuItemStyle" @click="contextMenu.visible = false">取消</div>
    </div>

    <!-- 边类型选择弹窗 -->
    <div
      v-if="edgeTypePopup.visible"
      :style="contextMenuStyle(edgeTypePopup.x, edgeTypePopup.y)"
      @click.stop
    >
      <div :style="{ ...ctxMenuItemStyle, fontSize: '11px', color: 'var(--text-mut)', cursor: 'default' }">选择边类型</div>
      <div :style="ctxMenuItemStyle" @click="confirmEdgeType('前置')">前置</div>
      <div :style="ctxMenuItemStyle" @click="confirmEdgeType('包含')">包含</div>
      <div :style="ctxMenuItemStyle" @click="confirmEdgeType('相关')">相关</div>
    </div>

    <!-- 抽取历史弹层 -->
    <div v-if="showHistory" :style="histOverlayStyle" @click.self="showHistory = false">
      <div :style="histCardStyle">
        <h3 :style="{ margin: '0 0 12px' }">抽取审核历史</h3>
        <div v-if="reviews.length === 0" :style="{ color: 'var(--text-mut)', fontSize: '13px' }">暂无记录</div>
        <table v-else :style="{ width: '100%', borderCollapse: 'collapse', fontSize: '12.5px' }">
          <thead>
            <tr>
              <th :style="histTh">时间</th><th :style="histTh">节点召回</th><th :style="histTh">节点精确</th>
              <th :style="histTh">保留/删/增</th><th :style="histTh">边召回</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in reviews" :key="i">
              <td :style="histTd">{{ (r.createdAt || '').replace('T', ' ').slice(0, 16) }}</td>
              <td :style="histTd">{{ histPct(r.nodeRecall) }}</td>
              <td :style="histTd">{{ histPct(r.nodePrecision) }}</td>
              <td :style="histTd">{{ r.nodeKeptCount }}/{{ r.nodeDeletedCount }}/{{ r.nodeAddedCount }}</td>
              <td :style="histTd">{{ histPct(r.edgeRecall) }}</td>
            </tr>
          </tbody>
        </table>
        <button @click="showHistory = false" :style="{ ...importBtnHeaderStyle, marginTop: '14px' }">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import {
  fetchTeacherGraph,
  fetchPendingEdges,
  acceptEdge,
  rejectEdge,
  createNode,
  updateNode,
  deleteNode,
  generateGraphPreviewSvg
} from '../api/teacher.js'
import { renderGraphSvg } from '../utils/graphSvgRenderer.js'
import { importGraphJson, importGraphExcel, extractGraphFromFile, fetchExtractionReviews } from '../api/admin.js'
import { useRouter } from 'vue-router'

const router = useRouter()
const COURSE_ID = 1

const chartEl = ref(null)
const graph = ref(null)
const pending = ref([])
const loading = ref(true)
const error = ref(null)
const showNodeForm = ref(false)
const showEditForm = ref(false)
const processed = ref(0)

// ── 连线模式 ──
const linkMode = ref(false)
const linkSource = ref(null) // { nodeCode, name }
const edgeTypePopup = ref({ visible: false, x: 0, y: 0 })

// ── 右键菜单 ──
const contextMenu = ref({ visible: false, x: 0, y: 0, node: null })

// ── 导入状态 ──
const showImportModal = ref(false)
const importFile = ref(null)
const isDragging = ref(false)
const importUploading = ref(false)
const importProgress = ref(0)
const importResult = ref(null)
const importError = ref(null)
const fileInput = ref(null)

// ── 课程标准图片抽取状态 ──
const syllabusBusy = ref(false)
const syllabusError = ref('')

// ── 抽取历史 ──
const showHistory = ref(false)
const reviews = ref([])
async function openHistory() {
  showHistory.value = true
  try { reviews.value = await fetchExtractionReviews('52015CC4B4') }
  catch { reviews.value = [] }
}
function histPct(v) { return v == null ? '—' : (Number(v) * 100).toFixed(1) + '%' }

const histOverlayStyle = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.45)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 60 }
const histCardStyle = { width: '560px', maxWidth: '92vw', maxHeight: '80vh', overflow: 'auto', padding: '20px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: '12px' }
const histTh = { textAlign: 'left', padding: '6px 8px', borderBottom: '1px solid var(--line)', color: 'var(--text-mut)', whiteSpace: 'nowrap' }
const histTd = { padding: '6px 8px', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' }

// ── 预览图状态 ──
const showPreview = ref(false)
const previewLoading = ref(false)
const previewSvg = ref('')
const previewSource = ref('')   // 'ai' | 'ai-repaired' | 'fallback'
const previewIssues = ref([])

let chart = null
let C = {}

const nodeForm = ref({
  nodeCode: '',
  name: '',
  chapter: '',
  difficulty: 3,
  isKey: false,
  description: '',
  note: '',
  x: null,
  y: null
})

const editForm = ref({
  id: null,
  nodeCode: '',
  name: '',
  chapter: '',
  difficulty: 3,
  isKey: false,
  description: '',
  note: ''
})

const progressText = computed(() => {
  const total = pending.value.length + processed.value
  return total > 0 ? `${Math.round(processed.value / total * 100)}%` : '0%'
})

function readColors() {
  const s = getComputedStyle(document.documentElement)
  const g = (k) => s.getPropertyValue(k).trim()
  C = {
    mastered: g('--mastered'),
    weak: g('--weak'),
    unlearned: g('--unlearned'),
    accent: g('--accent'),
    line: g('--line'),
    text: g('--text'),
    textMut: g('--text-mut'),
    panel: g('--panel')
  }
}

async function reload() {
  loading.value = true
  error.value = null
  try {
    const [g, p] = await Promise.all([
      fetchTeacherGraph(COURSE_ID),
      fetchPendingEdges(COURSE_ID)
    ])

    // 提取 .data（接口返回 Result 信封）
    graph.value = g.data || g
    pending.value = p.data || p

    // 计算已处理数（根据初始待复核边总数）
    const initialTotal = (graph.value.edges || []).filter(e => e.pending !== false).length
    processed.value = Math.max(0, initialTotal - pending.value.length)

    renderChart()
  } catch (e) {
    console.error('加载失败', e)
    error.value = e.message || '网络错误'
  } finally {
    loading.value = false
  }
}

function renderChart() {
  if (!graph.value || !chartEl.value) return

  readColors()
  if (!chart) chart = echarts.init(chartEl.value)

  const nodes = (graph.value.nodes || []).map(n => ({
    id: n.nodeCode,
    name: n.name,
    symbolSize: n.isKey ? 40 : 24,
    itemStyle: {
      color: C.unlearned,
      borderColor: C.accent,
      borderWidth: 0
    },
    label: { show: true, position: 'right', color: C.text, fontSize: 11 },
    _raw: n
  }))

  const edges = (graph.value.edges || []).map(e => {
    const isPending = e.pending === true
    const base = {
      source: e.source,
      target: e.target,
      _type: e.type,
      _id: e.id
    }

    if (isPending) {
      base.symbol = ['none', 'arrow']
      base.symbolSize = 7
      base.lineStyle = { type: 'dashed', width: 2, color: C.accent, opacity: 0.85, curveness: 0.06 }
    } else if (e.confidence && e.confidence > 0) {
      // 已采纳边
      base.symbol = ['none', 'arrow']
      base.symbolSize = 7
      base.lineStyle = { type: 'solid', width: 1.6, color: C.mastered, opacity: 0.7, curveness: 0.06 }
    } else {
      // 原有图谱边
      if (e.type === '前置') {
        base.symbol = ['none', 'arrow']
        base.symbolSize = 7
        base.lineStyle = { type: 'solid', width: 1.4, color: C.textMut, opacity: 0.5, curveness: 0.06 }
      } else if (e.type === '包含') {
        base.symbol = ['none', 'none']
        base.lineStyle = { type: 'dashed', width: 1.2, color: C.textMut, opacity: 0.4, curveness: 0.06 }
      } else {
        base.symbol = ['none', 'none']
        base.lineStyle = { type: 'solid', width: 0.8, color: C.textMut, opacity: 0.22, curveness: 0.12 }
      }
    }

    return base
  })

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      formatter: (p) => {
        if (p.dataType === 'node') {
          return `${p.data.name}<br/>编码: ${p.data.id}`
        }
        return ''
      }
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        zoom: 0.85,
        force: {
          repulsion: 280,
          edgeLength: [60, 160],
          gravity: 0.05,
          friction: 0.25
        },
        emphasis: {
          focus: 'adjacency',
          label: { fontSize: 12 },
          lineStyle: { width: 2.6 }
        },
        data: nodes,
        links: edges
      }
    ]
  }

  chart.setOption(option, { notMerge: true })

  chart.off('click')
  chart.off('dblclick')
  chart.off('contextmenu')
  chart.on('click', (params) => {
    if (params.dataType !== 'node') return
    if (linkMode.value) {
      selectLinkNode(params.data._raw)
    } else {
      openEditForm(params.data._raw)
    }
  })
  chart.on('dblclick', (params) => {
    if (params.dataType === 'node') return
    // 双击空白区域：新增节点，预填坐标
    const pixel = params.event?.offsetX != null
      ? { x: params.event.offsetX, y: params.event.offsetY }
      : null
    if (pixel && chart) {
      try {
        const coord = chart.convertFromPixel({ seriesIndex: 0 }, [pixel.x, pixel.y])
        if (coord && !isNaN(coord[0])) {
          nodeForm.value.x = Math.round(coord[0])
          nodeForm.value.y = Math.round(coord[1])
        }
      } catch (_) { /* force layout 不支持 convertFromPixel 时忽略 */ }
    }
    showNodeForm.value = true
  })
  chart.on('contextmenu', (params) => {
    if (params.dataType !== 'node') return
    const e = params.event?.event || params.event
    const x = e?.clientX || e?.offsetX || 200
    const y = e?.clientY || e?.offsetY || 200
    contextMenu.value = { visible: true, x, y, node: params.data._raw }
  })
}

function openEditForm(node) {
  editForm.value = {
    id: node.id,
    nodeCode: node.nodeCode,
    name: node.name,
    chapter: node.chapter || '',
    difficulty: node.difficulty || 3,
    isKey: node.isKey || false,
    description: node.description || '',
    note: node.note || ''
  }
  showEditForm.value = true
}

async function accept(id) {
  try {
    await acceptEdge(id)
    await reload()
  } catch (e) {
    console.error('采纳失败', e)
    alert('操作失败：' + (e.message || '网络错误'))
  }
}

async function reject(id) {
  try {
    await rejectEdge(id)
    await reload()
  } catch (e) {
    console.error('驳回失败', e)
    alert('操作失败：' + (e.message || '网络错误'))
  }
}

async function submitCreate() {
  if (!nodeForm.value.nodeCode || !nodeForm.value.name) {
    alert('节点编码和名称为必填项')
    return
  }
  try {
    await createNode(COURSE_ID, nodeForm.value)
    showNodeForm.value = false
    nodeForm.value = { nodeCode: '', name: '', chapter: '', difficulty: 3, isKey: false, description: '', note: '', x: null, y: null }
    await reload()
  } catch (e) {
    console.error('创建失败', e)
    alert('创建失败：' + (e.message || '网络错误'))
  }
}

async function submitUpdate() {
  if (!editForm.value.name) {
    alert('名称为必填项')
    return
  }
  try {
    const { id, nodeCode, ...req } = editForm.value
    await updateNode(id, req)
    showEditForm.value = false
    await reload()
  } catch (e) {
    console.error('更新失败', e)
    alert('更新失败：' + (e.message || '网络错误'))
  }
}

async function confirmDelete() {
  if (!confirm(`确定删除节点「${editForm.value.name}」吗？此操作不可撤销。`)) return
  try {
    await deleteNode(editForm.value.id)
    showEditForm.value = false
    await reload()
  } catch (e) {
    console.error('删除失败', e)
    alert('删除失败：' + (e.message || '网络错误'))
  }
}

// ── 连线模式 ──
function toggleLinkMode() {
  if (linkMode.value) {
    exitLinkMode()
  } else {
    linkMode.value = true
    linkSource.value = null
  }
}

function exitLinkMode() {
  linkMode.value = false
  linkSource.value = null
  edgeTypePopup.value = { visible: false, x: 0, y: 0 }
  highlightLinkSource() // 清除高亮
}

function highlightLinkSource() {
  if (!chart || !graph.value) return
  const srcCode = linkSource.value?.nodeCode
  const nodes = (graph.value.nodes || []).map(n => ({
    id: n.nodeCode,
    name: n.name,
    symbolSize: n.isKey ? 40 : 24,
    itemStyle: {
      color: C.unlearned,
      borderColor: n.nodeCode === srcCode ? '#ff4444' : C.accent,
      borderWidth: n.nodeCode === srcCode ? 3 : 0
    },
    label: { show: true, position: 'right', color: C.text, fontSize: 11 },
    _raw: n
  }))
  chart.setOption({ series: [{ data: nodes }] }, { replaceMerge: ['series'] })
}

function selectLinkNode(rawNode) {
  if (!linkSource.value) {
    linkSource.value = { nodeCode: rawNode.nodeCode, name: rawNode.name }
    highlightLinkSource()
  } else if (rawNode.nodeCode !== linkSource.value.nodeCode) {
    // 第二个节点：显示边类型选择弹窗
    edgeTypePopup.value = {
      visible: true,
      x: 200,
      y: 200,
      source: linkSource.value.nodeCode,
      target: rawNode.nodeCode
    }
  }
}

function confirmEdgeType(type) {
  const src = edgeTypePopup.value.source
  const tgt = edgeTypePopup.value.target
  edgeTypePopup.value = { visible: false, x: 0, y: 0 }
  // TODO: POST /api/teacher/graph/edges
  alert(`连线功能待后端接口支持\n${src} → [${type}] → ${tgt}`)
  exitLinkMode()
}

// ── 右键菜单 ──
function ctxEdit() {
  if (contextMenu.value.node) openEditForm(contextMenu.value.node)
  contextMenu.value = { visible: false, x: 0, y: 0, node: null }
}

function ctxDelete() {
  const node = contextMenu.value.node
  contextMenu.value = { visible: false, x: 0, y: 0, node: null }
  if (!node) return
  editForm.value = {
    id: node.id,
    nodeCode: node.nodeCode,
    name: node.name,
    chapter: node.chapter || '',
    difficulty: node.difficulty || 3,
    isKey: node.isKey || false,
    description: node.description || '',
    note: node.note || ''
  }
  confirmDelete()
}

// ── 键盘事件 ──
function closeMenus() {
  if (contextMenu.value.visible) contextMenu.value = { visible: false, x: 0, y: 0, node: null }
  if (edgeTypePopup.value.visible) edgeTypePopup.value = { visible: false, x: 0, y: 0 }
}

function handleKeydown(e) {
  if (e.key === 'Escape') {
    if (edgeTypePopup.value.visible) { edgeTypePopup.value = { visible: false, x: 0, y: 0 }; return }
    if (contextMenu.value.visible) { contextMenu.value = { visible: false, x: 0, y: 0, node: null }; return }
    if (linkMode.value) { exitLinkMode(); return }
  }
  if ((e.key === 'Delete' || e.key === 'Backspace') && showEditForm.value) {
    const tag = e.target.tagName
    if (tag === 'INPUT' || tag === 'TEXTAREA') return
    e.preventDefault()
    confirmDelete()
  }
}


// ── 预览图：AI 生成，失败兜底 ──
function graphToLayoutData() {
  const g = graph.value || {}
  return {
    nodes: (g.nodes || []).map((n) => ({
      id: n.nodeCode, name: n.name, chapter: n.chapter || '',
      difficulty: n.difficulty ?? 3, is_key: !!n.isKey
    })),
    edges: (g.edges || []).map((e) => ({ source: e.source, target: e.target, type: e.type }))
  }
}

function fallbackPreview(issues) {
  try {
    previewSvg.value = renderGraphSvg(graphToLayoutData())
    previewSource.value = 'fallback'
    previewIssues.value = issues || []
  } catch (e) {
    previewSvg.value = ''
    previewSource.value = ''
    previewIssues.value = ['兜底渲染失败：' + (e.message || e)]
  }
}

async function openPreview() {
  showPreview.value = true
  previewLoading.value = true
  previewSvg.value = ''
  previewSource.value = ''
  previewIssues.value = []
  try {
    const res = await generateGraphPreviewSvg(COURSE_ID) // http 拦截器已拆 Result 信封
    if (res && res.valid && res.svg) {
      previewSvg.value = res.svg
      previewSource.value = res.source || 'ai'
      previewIssues.value = []
    } else {
      fallbackPreview(res ? res.issues : [])
    }
  } catch (e) {
    fallbackPreview(['AI 调用失败：' + (e.message || '网络错误')])
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  showPreview.value = false
  previewSvg.value = ''
}

function downloadPreview() {
  if (!previewSvg.value) return
  const blob = new Blob([previewSvg.value], { type: 'image/svg+xml;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `知识图谱预览-${COURSE_ID}.svg`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ── 课程标准图片抽取 ──
async function handleSyllabusImage(event) {
  const file = event.target.files && event.target.files[0]
  event.target.value = ''
  if (!file) return
  if (syllabusBusy.value) return
  syllabusError.value = ''
  syllabusBusy.value = true
  try {
    const courseCode = '52015CC4B4'
    const { draftId } = await extractGraphFromFile(courseCode, file)
    router.push({ path: '/teacher/graph-extract-review', query: { draftId, courseCode } })
  } catch (e) {
    syllabusError.value = e.message || '识别失败'
  } finally {
    syllabusBusy.value = false
  }
}

// ── 导入 ──
function closeImportModal() {
  if (importUploading.value) return
  showImportModal.value = false
  importFile.value = null
  importResult.value = null
  importError.value = null
  importProgress.value = 0
  isDragging.value = false
}

function onFileDrop(e) {
  isDragging.value = false
  const file = e.dataTransfer.files[0]
  if (file) doImport(file)
}

function onFileSelect(e) {
  const file = e.target.files[0]
  if (file) doImport(file)
  e.target.value = ''
}

async function doImport(file) {
  const name = file.name.toLowerCase()
  const isJson = name.endsWith('.json')
  const isExcel = name.endsWith('.xlsx') || name.endsWith('.xls')
  if (!isJson && !isExcel) {
    importError.value = '不支持的文件格式，请上传 .json / .xlsx / .xls 文件'
    return
  }

  importFile.value = file
  importUploading.value = true
  importProgress.value = 0
  importResult.value = null
  importError.value = null

  const courseCode = '52015CC4B4'

  try {
    let res
    if (isJson) {
      const text = await file.text()
      const data = JSON.parse(text)
      importProgress.value = 50
      res = await importGraphJson(courseCode, data)
    } else {
      res = await importGraphExcel(courseCode, file, (e) => {
        if (e.total) importProgress.value = Math.round((e.loaded / e.total) * 100)
      })
    }
    importProgress.value = 100
    importResult.value = res || {}
    await reload()
  } catch (e) {
    console.error('导入失败', e)
    importError.value = e.message || '导入失败，请检查文件格式和内容'
  } finally {
    importUploading.value = false
  }
}

function onResize() {
  if (chart) chart.resize()
}

onMounted(async () => {
  readColors()
  await reload()
  window.addEventListener('resize', onResize)
  window.addEventListener('keydown', handleKeydown)
  document.addEventListener('click', closeMenus, true)

  if (import.meta.env.DEV) {
    window.__wjTeacherGraph = {
      reload,
      accept: acceptEdge,
      reject: rejectEdge,
      openImport: () => { showImportModal.value = true },
      state: () => ({ pending: pending.value, edges: graph.value?.edges }),
      openPreview: () => { openPreview() },
    }
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('keydown', handleKeydown)
  document.removeEventListener('click', closeMenus, true)
  if (chart) chart.dispose()
})

// ── 样式 ──
const pageStyle = {
  minWidth: '1180px',
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg)',
  color: 'var(--text)'
}

const headerStyle = {
  height: '56px',
  flexShrink: 0,
  display: 'flex',
  alignItems: 'center',
  gap: '24px',
  padding: '0 20px',
  background: 'var(--panel)',
  borderBottom: '1px solid var(--line)'
}

const titleStyle = {
  display: 'flex',
  alignItems: 'baseline',
  gap: '12px'
}

const courseStyle = {
  fontSize: '13px',
  color: 'var(--text-mut)'
}

const navStyle = {
  display: 'flex',
  gap: '16px',
  marginLeft: 'auto'
}

const linkStyle = {
  fontSize: '13px',
  color: 'var(--text-mut)',
  textDecoration: 'none',
  padding: '6px 12px',
  borderRadius: '8px',
  transition: 'color 0.2s, background 0.2s'
}

const statusBarStyle = {
  height: '44px',
  flexShrink: 0,
  display: 'flex',
  alignItems: 'center',
  gap: '16px',
  padding: '0 20px',
  borderBottom: '1px solid var(--line)',
  fontSize: '12.5px',
  color: 'var(--text-mut)'
}

const progressWrapStyle = {
  width: '120px',
  height: '4px',
  background: 'var(--panel-2)',
  borderRadius: '99px',
  overflow: 'hidden'
}

const progressBarStyle = computed(() => ({
  height: '100%',
  width: progressText.value,
  background: 'var(--mastered)',
  borderRadius: '99px',
  transition: 'width 0.3s'
}))

const mainStyle = {
  flex: 1,
  minHeight: 0,
  display: 'flex'
}

const canvasWrapStyle = {
  flex: 1,
  minWidth: 0,
  position: 'relative',
  overflow: 'hidden'
}

const chartStyle = {
  width: '100%',
  height: '100%'
}

const overlayStyle = {
  position: 'absolute',
  inset: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: '12px',
  color: 'var(--text-mut)',
  fontSize: '14px'
}

const retryBtnStyle = {
  background: 'var(--accent)',
  border: 'none',
  color: '#fff',
  padding: '6px 14px',
  borderRadius: '8px',
  cursor: 'pointer'
}

const legendStyle = {
  position: 'absolute',
  left: '16px',
  bottom: '14px',
  display: 'flex',
  flexDirection: 'column',
  gap: '8px',
  background: 'var(--panel)',
  border: '1px solid var(--line)',
  borderRadius: '10px',
  padding: '12px 15px',
  fontSize: '11.5px',
  color: 'var(--text-mut)'
}

const lgStyle = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: '8px'
}

const hintStyle = {
  position: 'absolute',
  right: '16px',
  top: '12px',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'flex-end',
  gap: '8px'
}

const addNodeBtnStyle = {
  background: 'var(--accent)',
  border: 'none',
  color: '#fff',
  padding: '8px 16px',
  borderRadius: '8px',
  fontSize: '12.5px',
  fontWeight: 500,
  cursor: 'pointer',
  transition: 'opacity 0.2s'
}

const sidebarStyle = {
  width: '400px',
  flexShrink: 0,
  boxSizing: 'border-box',
  borderLeft: '1px solid var(--line)',
  display: 'flex',
  flexDirection: 'column'
}

const sidebarHeadStyle = {
  flexShrink: 0,
  padding: '18px 20px 12px',
  display: 'flex',
  alignItems: 'baseline',
  gap: '10px'
}

const sidebarBodyStyle = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '0 16px 16px',
  display: 'flex',
  flexDirection: 'column',
  gap: '10px'
}

function cardStyle(i) {
  return {
    background: 'var(--panel)',
    border: '1.5px solid var(--line)',
    borderRadius: '12px',
    padding: '14px 16px',
    transition: 'border-color 0.2s'
  }
}

const typeTagStyle = {
  fontSize: '11px',
  letterSpacing: '1px',
  color: 'var(--text)',
  border: '1px solid var(--line)',
  borderRadius: '5px',
  padding: '2px 7px'
}

function confTagStyle(edge) {
  const low = edge.low || edge.confidence < 70
  return {
    fontSize: '11.5px',
    color: low ? 'var(--weak)' : 'var(--text-mut)',
    background: low ? 'rgba(224, 163, 62, 0.16)' : 'var(--panel-2)',
    borderRadius: '999px',
    padding: '2px 9px'
  }
}

const acceptBtnStyle = {
  flex: 1,
  height: '34px',
  background: 'var(--accent)',
  border: 'none',
  borderRadius: '8px',
  color: '#fff',
  fontSize: '12.5px',
  fontWeight: 500,
  cursor: 'pointer',
  transition: 'opacity 0.2s'
}

const rejectBtnStyle = {
  height: '34px',
  padding: '0 18px',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  color: 'var(--text-mut)',
  fontSize: '12.5px',
  cursor: 'pointer',
  transition: 'border-color 0.2s'
}

const emptyStyle = {
  textAlign: 'center',
  padding: '36px 12px 22px'
}

const modalOverlayStyle = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0, 0, 0, 0.6)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1000
}

const modalStyle = {
  width: '480px',
  maxHeight: '80vh',
  background: 'var(--panel)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  display: 'flex',
  flexDirection: 'column'
}

const modalHeadStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '16px 20px',
  borderBottom: '1px solid var(--line)'
}

const closeBtnStyle = {
  background: 'transparent',
  border: 'none',
  color: 'var(--text-mut)',
  fontSize: '24px',
  lineHeight: 1,
  cursor: 'pointer'
}

const modalBodyStyle = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '20px'
}

const formRowStyle = {
  marginBottom: '16px'
}

const labelStyle = {
  display: 'block',
  fontSize: '13px',
  color: 'var(--text-mut)',
  marginBottom: '6px'
}

const inputStyle = {
  width: '100%',
  padding: '8px 12px',
  background: 'var(--panel-2)',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  fontSize: '13px',
  color: 'var(--text)'
}

const textareaStyle = {
  ...inputStyle,
  fontFamily: 'inherit',
  resize: 'vertical'
}

const modalFooterStyle = {
  display: 'flex',
  gap: '8px',
  padding: '16px 20px',
  borderTop: '1px solid var(--line)'
}

const submitBtnStyle = {
  flex: 1,
  height: '38px',
  background: 'var(--accent)',
  border: 'none',
  borderRadius: '8px',
  color: '#fff',
  fontSize: '13px',
  fontWeight: 500,
  cursor: 'pointer'
}

const deleteBtnStyle = {
  height: '38px',
  padding: '0 18px',
  background: 'rgba(224, 163, 62, 0.16)',
  border: '1px solid var(--weak)',
  borderRadius: '8px',
  color: 'var(--weak)',
  fontSize: '13px',
  cursor: 'pointer'
}

const cancelBtnStyle = {
  height: '38px',
  padding: '0 18px',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  color: 'var(--text-mut)',
  fontSize: '13px',
  cursor: 'pointer'
}

const importBtnHeaderStyle = {
  fontSize: '13px',
  color: 'var(--accent)',
  background: 'transparent',
  border: '1px solid var(--accent)',
  borderRadius: '8px',
  padding: '6px 14px',
  cursor: 'pointer',
  transition: 'opacity 0.2s',
  fontWeight: 500
}

const importModalStyle = {
  width: '520px',
  maxHeight: '80vh',
  background: 'var(--panel)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  display: 'flex',
  flexDirection: 'column'
}

const previewModalStyle = {
  width: '90vw',
  maxWidth: '1200px',
  maxHeight: '88vh',
  background: 'var(--panel)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  display: 'flex',
  flexDirection: 'column'
}

const previewBodyStyle = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '16px 20px'
}

const previewMetaStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  marginBottom: '12px'
}

const previewCanvasStyle = {
  width: '100%',
  background: '#121317',
  borderRadius: '10px',
  overflow: 'hidden'
}

const previewBadgeOk = {
  fontSize: '12px',
  color: '#fff',
  background: 'var(--mastered)',
  borderRadius: '999px',
  padding: '2px 10px'
}

const previewBadgeFallback = {
  fontSize: '12px',
  color: 'var(--text)',
  background: 'var(--panel-2)',
  border: '1px solid var(--line)',
  borderRadius: '999px',
  padding: '2px 10px'
}

function dropZoneStyle(dragging) {
  return {
    border: `2px dashed ${dragging ? 'var(--accent)' : 'var(--line)'}`,
    borderRadius: '12px',
    padding: '40px 20px',
    textAlign: 'center',
    cursor: 'pointer',
    background: dragging ? 'rgba(100, 160, 255, 0.06)' : 'var(--panel-2)',
    transition: 'border-color 0.2s, background 0.2s'
  }
}

const importProgressWrapStyle = {
  width: '100%',
  height: '6px',
  background: 'var(--panel-2)',
  borderRadius: '99px',
  overflow: 'hidden'
}

function importProgressBarStyle(pct) {
  return {
    height: '100%',
    width: pct + '%',
    background: 'var(--accent)',
    borderRadius: '99px',
    transition: 'width 0.3s'
  }
}

const importResultStyle = {
  marginTop: '16px',
  padding: '18px 20px',
  background: 'rgba(76, 175, 80, 0.08)',
  border: '1px solid rgba(76, 175, 80, 0.3)',
  borderRadius: '10px'
}

const resultRowStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '6px 0'
}

const resultLabelStyle = {
  fontSize: '13px',
  color: 'var(--text-mut)'
}

const resultValueStyle = {
  fontSize: '15px',
  fontWeight: 600,
  color: 'var(--text)'
}

const importErrorBoxStyle = {
  marginTop: '16px',
  padding: '18px 20px',
  background: 'rgba(224, 100, 100, 0.08)',
  border: '1px solid rgba(224, 100, 100, 0.3)',
  borderRadius: '10px'
}

// ── 连线模式按钮 ──
const linkBtnStyle = {
  fontSize: '13px',
  color: 'var(--text-mut)',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  padding: '6px 14px',
  cursor: 'pointer',
  transition: 'all 0.2s',
  fontWeight: 500
}

const linkBtnActiveStyle = {
  fontSize: '13px',
  color: '#fff',
  background: 'var(--accent)',
  border: '1px solid var(--accent)',
  borderRadius: '8px',
  padding: '6px 14px',
  cursor: 'pointer',
  transition: 'all 0.2s',
  fontWeight: 500
}

// ── 右键菜单 / 边类型弹窗 ──
function contextMenuStyle(x, y) {
  return {
    position: 'fixed',
    left: x + 'px',
    top: y + 'px',
    background: 'var(--panel)',
    border: '1px solid var(--line)',
    borderRadius: '10px',
    padding: '6px 0',
    minWidth: '120px',
    boxShadow: '0 6px 20px rgba(0,0,0,0.25)',
    zIndex: 2000
  }
}

const ctxMenuItemStyle = {
  padding: '8px 18px',
  fontSize: '13px',
  color: 'var(--text)',
  cursor: 'pointer',
  transition: 'background 0.15s'
}
</script>
