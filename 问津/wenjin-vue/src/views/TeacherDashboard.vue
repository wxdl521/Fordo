<template>
  <div :style="{ height: '100vh', minWidth: '1180px', display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'var(--bg)', color: 'var(--ink)', transition: 'background-color 0.35s, color 0.35s' }">

    <TopBar teacher compact subtitle="软件工程 · 学情看板">
      <NavLink to="/teacher/graph">图谱审核工作台</NavLink>
      <NavLink to="/teacher/questions">题目审核池</NavLink>
    </TopBar>

    <!-- 状态条 -->
    <div :style="{ height: '46px', flex: 'none', display: 'flex', alignItems: 'center', gap: '16px', padding: '0 20px', borderBottom: '1px solid var(--line)', fontSize: '12.5px', color: 'var(--mut)', transition: 'border-color 0.35s' }">
      <span>2024 级 1 班 · <b :style="b">86</b> 人 · 入口诊断 <b :style="b">79</b>/86 已完成</span>
      <div :style="{ width: '1px', height: '14px', background: 'var(--line)' }"></div>
      <span>已开课至「对象设计」 · 班级平均掌握率 <b :style="b">68%</b></span>
      <span :style="{ marginLeft: 'auto' }">图谱 v0.3 · {{ nodeCount }} 个知识点 · 数据更新于今天 08:00</span>
    </div>

    <!-- 主体 -->
    <div :style="{ flex: 1, minHeight: 0, display: 'flex' }">

      <!-- 班级染色地图 -->
      <div :style="{ flex: 1, minWidth: 0, position: 'relative', overflow: 'hidden' }">
        <svg v-if="layout" viewBox="0 0 1480 740" preserveAspectRatio="xMidYMid meet" @click="onSvgClick" :style="{ width: '100%', height: '100%', display: 'block', transform: mapIn ? 'scale(1)' : 'scale(0.96)', transformOrigin: '50% 50%', transition: 'transform 1.2s cubic-bezier(0.22,1,0.36,1)' }">
          <defs>
            <marker id="arrA" viewBox="0 0 10 10" :refX="8" :refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 1 L 8 5 L 0 9 z" :fill="pal.acc" />
            </marker>
          </defs>

          <template v-if="theme === 'ink'">
            <circle v-for="(s, i) in layout.stars" :key="'st' + i" :cx="s.x" :cy="s.y" :r="s.r" :fill="pal.star" :opacity="s.o * 0.5">
              <animate v-if="i % 6 === 0" attributeName="opacity" :values="(s.o * 0.5) + ';' + Math.min(0.55, s.o * 1.6) + ';' + (s.o * 0.5)" :dur="(3.5 + (i % 9) * 0.7) + 's'" repeatCount="indefinite" />
            </circle>
          </template>

          <text v-for="ch in chapterLabels" :key="'ch' + ch.name" :x="ch.x" :y="ch.y" text-anchor="middle" :fill="pal.chap" :opacity="ch.op" font-size="21" letter-spacing="7" :font-family="serif" font-weight="500" :style="{ transition: 'opacity 0.3s', pointerEvents: 'none' }">{{ ch.name }}</text>

          <line v-for="(e, i) in baseEdges" :key="'ge' + i" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="pal.edge" :stroke-width="e.w" :stroke-dasharray="e.dash" :opacity="e.op" :style="{ transition: 'opacity 0.3s' }" />

          <line v-for="(e, i) in chainEdges" :key="'tr' + i" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="pal.acc" stroke-width="2" opacity="0.85" marker-end="url(#arrA)" />

          <template v-for="n in nodeList" :key="n.id">
            <circle v-if="n.isSel" :cx="n.x" :cy="n.y" :r="n.r + 5" fill="none" :stroke="pal.acc" stroke-width="2" opacity="0.9" />
            <circle v-if="n.isSel" :cx="n.x" :cy="n.y" :r="n.r + 5" fill="none" :stroke="pal.acc" stroke-width="1.5" opacity="0">
              <animate attributeName="r" :values="(n.r + 5) + ';' + (n.r + 20)" dur="2.2s" repeatCount="indefinite" />
              <animate attributeName="opacity" values="0.6;0" dur="2.2s" repeatCount="indefinite" />
            </circle>
            <circle :cx="n.x" :cy="n.y" :r="n.r" :fill="n.fill" :opacity="n.op" :style="{ transition: 'opacity 0.3s, fill 0.3s', cursor: 'pointer' }" @click.stop="toggleSel(n.id)" />
            <text :x="n.lx" :y="n.ly" :text-anchor="n.la" :fill="n.labelHi ? pal.labelHi : pal.label" font-size="11.5" :opacity="n.labelOp" :style="{ transition: 'opacity 0.3s', pointerEvents: 'none' }">{{ n.short }}</text>
          </template>
        </svg>
        <div v-else :style="{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)', fontSize: '13px' }">正在绘制班级图谱……</div>

        <!-- 图例 -->
        <div :style="{ position: 'absolute', left: '16px', bottom: '14px', display: 'flex', flexDirection: 'column', gap: '8px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '12px 15px', fontSize: '11.5px', color: 'var(--mut)', transition: 'background-color 0.35s, border-color 0.35s' }">
          <span :style="lg"><span :style="{ width: '10px', height: '10px', flex: 'none', borderRadius: '50%', background: 'var(--ok)' }"></span>掌握良好（≥72%）</span>
          <span :style="lg"><span :style="{ width: '10px', height: '10px', flex: 'none', borderRadius: '50%', background: 'var(--warn)' }"></span>整体薄弱（&lt;72%）</span>
          <span :style="lg"><span :style="{ width: '10px', height: '10px', flex: 'none', borderRadius: '50%', background: 'var(--dim)' }"></span>尚未开课</span>
          <span :style="lg"><span :style="{ width: '10px', height: '10px', flex: 'none', boxSizing: 'border-box', borderRadius: '50%', border: '2px solid var(--acc)' }"></span>选中 · 根因回溯链</span>
        </div>
        <div :style="{ position: 'absolute', right: '16px', top: '12px', fontSize: '11.5px', color: 'var(--mut)', opacity: 0.8 }">点击排行或图上节点，回溯班级共同薄弱的根因链</div>
      </div>

      <!-- 薄弱排行 -->
      <div :style="{ width: '400px', flex: 'none', boxSizing: 'border-box', borderLeft: '1px solid var(--line)', display: 'flex', flexDirection: 'column', transition: 'border-color 0.35s' }">
        <div :style="{ flex: 'none', padding: '18px 20px 12px', display: 'flex', alignItems: 'baseline', gap: '10px' }">
          <span :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)' }">共同薄弱排行</span>
          <span :style="{ fontSize: '12px', color: 'var(--mut)', marginLeft: 'auto' }">按薄弱人数 · 前 8</span>
        </div>

        <div :style="{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 16px 16px', display: 'flex', flexDirection: 'column', gap: '10px' }">
          <!-- 选中详情 -->
          <div v-if="sel" :style="{ background: 'var(--card)', border: '1.5px solid var(--acc)', borderRadius: '12px', padding: '15px 16px', animation: 'wjFadeUp 0.4s cubic-bezier(0.22,1,0.36,1) both', transition: 'background-color 0.35s' }">
            <div :style="{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }">
              <span :style="{ fontSize: '14px', fontWeight: 600 }">{{ nameOf(sel) }}</span>
              <span :style="{ fontSize: '11px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '2px 9px' }">{{ layout && layout.byId[sel] ? layout.byId[sel].chapter : '' }}</span>
              <button @click="sel = null" class="wj-underline" :style="{ marginLeft: 'auto', flex: 'none', background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">收起</button>
            </div>
            <template v-if="selM">
              <div :style="{ display: 'flex', alignItems: 'center', height: '6px', borderRadius: '99px', overflow: 'hidden', background: 'var(--card2)', marginBottom: '9px' }">
                <div :style="{ height: '100%', width: selG, background: 'var(--ok)' }"></div>
                <div :style="{ height: '100%', width: selW, background: 'var(--warn)' }"></div>
              </div>
              <div :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '10px' }">掌握 {{ selM[0] }} 人 · 薄弱 {{ selM[1] }} 人 · 未诊断 {{ selM[2] }} 人 · 掌握率 <b :style="b">{{ selRate }}</b></div>
              <div v-if="causeText" :style="{ fontSize: '12px', color: 'var(--warn)', background: 'var(--warnSoft)', borderRadius: '8px', padding: '9px 12px', lineHeight: 1.7 }">{{ causeText }}</div>
            </template>
            <div v-else :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.7 }">该知识点尚未开课，暂无学情数据。</div>
          </div>

          <div v-for="(r, i) in rows" :key="r.id" @click="toggleSel(r.id)" class="wj-hover-mut" :style="{ background: 'var(--card)', border: '1.5px solid ' + (sel === r.id ? 'var(--acc)' : 'var(--line)'), borderRadius: '12px', padding: '13px 16px', cursor: 'pointer', animation: 'wjFadeUp 0.45s cubic-bezier(0.22,1,0.36,1) both', animationDelay: (i * 0.05) + 's', transition: 'border-color 0.2s, background-color 0.35s' }">
            <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '9px' }">
              <span :style="{ fontFamily: serif, fontSize: '15px', fontWeight: 600, color: i < 3 ? 'var(--acc)' : 'var(--mut)', flex: 'none', width: '18px' }">{{ r.rank }}</span>
              <span :style="{ fontSize: '13.5px', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }">{{ r.name }}</span>
              <span :style="{ marginLeft: 'auto', flex: 'none', fontSize: '12px', color: 'var(--warn)' }">薄弱 {{ r.weak }} 人</span>
            </div>
            <div :style="{ display: 'flex', alignItems: 'center', height: '5px', borderRadius: '99px', overflow: 'hidden', background: 'var(--card2)', marginBottom: '8px' }">
              <div :style="{ height: '100%', width: r.gW, background: 'var(--ok)' }"></div>
              <div :style="{ height: '100%', width: r.wW, background: 'var(--warn)' }"></div>
            </div>
            <div :style="{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '11.5px', color: 'var(--mut)' }">
              <span>{{ r.chapter }}</span>
              <span :style="{ marginLeft: 'auto' }">掌握率 {{ r.rate }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import TopBar from '../components/TopBar.vue'
import NavLink from '../components/NavLink.vue'
import { useTheme } from '../composables/useTheme.js'
import { useGraphData } from '../composables/useGraphData.js'
import { computeLayout, shortName } from '../utils/graphLayout.js'

const serif = "'Noto Serif SC', serif"
const { theme } = useTheme()
const { data } = useGraphData()

const b = { color: 'var(--ink)', fontWeight: 500 }
const lg = { display: 'inline-flex', alignItems: 'center', gap: '8px' }

const MAST = {
  KT01: [80, 4, 2], KT02: [76, 8, 2], 'KT02-1': [78, 6, 2], 'KT02-2': [70, 12, 4],
  KT03: [66, 16, 4], 'KT03-1': [71, 11, 4], 'KT03-2': [58, 22, 6],
  KT04: [74, 9, 3], KT05: [68, 14, 4], 'KT05-1': [72, 10, 4], 'KT05-2': [60, 21, 5],
  KT06: [70, 12, 4], KT25: [64, 16, 6],
  KT07: [56, 26, 4], 'KT07-1': [69, 13, 4], 'KT07-2': [52, 30, 4], 'KT07-3': [44, 38, 4], 'KT07-4': [58, 22, 6],
  KT27: [73, 9, 4], KT29: [75, 7, 4],
  KT10: [54, 27, 5], 'KT10-1': [62, 19, 5], 'KT10-2': [41, 40, 5], 'KT12-1': [49, 31, 6],
  KT13: [63, 17, 6], KT14: [60, 20, 6], KT15: [47, 32, 7], 'KT15-1': [45, 34, 7],
  'KT15-2': [38, 40, 8], KT16: [57, 21, 8], KT17: [52, 26, 8], KT28: [66, 12, 8],
  KT18: [49, 29, 8], 'KT18-1': [43, 36, 7], 'KT18-2': [36, 40, 10], KT19: [40, 34, 12], KT20: [30, 36, 20]
}
const CAUSE = {
  'KT18-2': { pre: 'KT18-1', n: 29 }, 'KT15-2': { pre: 'KT15-1', n: 25 }, 'KT10-2': { pre: 'KT10-1', n: 24 },
  'KT07-3': { pre: 'KT07-2', n: 26 }, KT20: { pre: 'KT19', n: 24 }, 'KT18-1': { pre: 'KT14', n: 21 },
  KT19: { pre: 'KT18-1', n: 23 }, 'KT15-1': { pre: 'KT14', n: 19 }
}
const SHORT_EXTRA = {
  KT01: '软工概念与生命周期', KT02: '传统过程模型', KT03: '现代过程模型', KT04: '需求概念与目标',
  KT05: '业务流程分析', KT06: '团队组织管理', KT07: '用例模型', KT10: '领域模型', KT13: '软件设计概念',
  KT14: '通用设计原则', KT15: '架构设计', KT16: '交互设计', KT17: '数据设计', KT18: 'OO设计原则',
  KT19: '设计类构建', KT20: '类设计转代码', KT25: '软件质量管理', KT27: 'UML建模工具',
  KT28: '数据库建模工具', KT29: '原型设计工具', 'KT15-1': '分层架构', 'KT15-2': '架构风格与选型',
  'KT18-1': 'SOLID原则', 'KT18-2': '设计模式'
}
const PAL = {
  ink: { node: '#4A4D55', edge: 'rgba(232,227,216,0.10)', label: '#9A948A', labelHi: '#E8E3D8', star: '#E8E3D8', chap: '#9A948A', warn: '#E0A33E', acc: '#D85E45', ok: '#57A87E' },
  paper: { node: '#C9C2B4', edge: 'rgba(42,37,32,0.12)', label: '#6F6759', labelHi: '#2A2520', star: '#6F6759', chap: '#6F6759', warn: '#C8862A', acc: '#B4422E', ok: '#3D7A5E' }
}
const ANCH = {
  软件工程概述: [180, 320], 软件项目管理: [430, 110], 需求确定: [450, 480], 系统分析: [730, 300],
  系统设计: [950, 510], 对象设计: [1010, 190], 软件测试: [1220, 360], 部署与维护: [1350, 150]
}

const layout = ref(null)
const sel = ref(null)
const mapIn = ref(false)
const pal = computed(() => PAL[theme.value])
const nodeCount = computed(() => (data.value ? data.value.nodes.length : '—'))

onMounted(() => {
  if (data.value) build()
  watch(data, (d) => { if (d) build() })
})
function build() {
  layout.value = computeLayout(data.value)
  setTimeout(() => { mapIn.value = true }, 60)
}

function nameOf(id) {
  if (SHORT_EXTRA[id]) return SHORT_EXTRA[id]
  if (layout.value && layout.value.byId[id]) return shortName(layout.value.byId[id])
  return id
}
function rateOf(id) {
  const m = MAST[id]
  if (!m || m[0] + m[1] === 0) return null
  return m[0] / (m[0] + m[1])
}
function nameShort(n) {
  if (SHORT_EXTRA[n.id]) return SHORT_EXTRA[n.id]
  return shortName(n)
}

function backTrace(id) {
  const L = layout.value
  const nodes = { [id]: true }
  const edges = []
  const queue = [id]
  let guard = 0
  while (queue.length && guard < 60) {
    guard++
    const x = queue.shift()
    let preds = data.value.edges.filter((e) => e.type === '前置' && e.target === x).map((e) => e.source)
    if (preds.length === 0 && L.parentOf[x]) preds = [L.parentOf[x]]
    preds.forEach((p) => {
      edges.push({ s: p, t: x })
      if (!nodes[p]) { nodes[p] = true; queue.push(p) }
    })
  }
  return { nodes, edges }
}

const chain = computed(() => (sel.value && layout.value ? backTrace(sel.value) : null))
const dimAll = computed(() => !!chain.value)

const chapterLabels = computed(() => {
  if (!layout.value) return []
  return Object.keys(ANCH).map((ch) => {
    const a = ANCH[ch]
    return { name: ch, x: a[0], y: Math.max(42, a[1] - 95), op: dimAll.value ? 0.06 : (theme.value === 'ink' ? 0.16 : 0.28) }
  })
})

const baseEdges = computed(() => {
  if (!layout.value) return []
  const L = layout.value
  const out = []
  data.value.edges.forEach((e) => {
    const a = L.pos[e.source]; const b2 = L.pos[e.target]
    if (!a || !b2) return
    out.push({ x1: a[0], y1: a[1], x2: b2[0], y2: b2[1], w: e.type === '包含' ? 0.8 : 1.2, dash: e.type === '相关' ? '2 5' : 'none', op: dimAll.value ? 0.3 : 1 })
  })
  return out
})

const chainEdges = computed(() => {
  if (!chain.value || !layout.value) return []
  const L = layout.value
  const trim = (s, t) => {
    const a = L.pos[s]; const bb = L.pos[t]
    const dx = bb[0] - a[0]; const dy = bb[1] - a[1]
    const d = Math.sqrt(dx * dx + dy * dy) + 0.01
    const ux = dx / d; const uy = dy / d
    const ra = L.radius[s] + 4; const rb = L.radius[t] + 9
    return { x1: a[0] + ux * ra, y1: a[1] + uy * ra, x2: bb[0] - ux * rb, y2: bb[1] - uy * rb }
  }
  return chain.value.edges.filter((e) => L.pos[e.s] && L.pos[e.t]).map((e) => trim(e.s, e.t))
})

const nodeList = computed(() => {
  if (!layout.value) return []
  const L = layout.value
  const ch = chain.value
  return data.value.nodes.map((n) => {
    const p = L.pos[n.id]; const r = L.radius[n.id]
    const rate = rateOf(n.id)
    const fill = rate === null ? pal.value.node : (rate >= 0.72 ? pal.value.ok : pal.value.warn)
    const inChain = ch && ch.nodes[n.id]
    const isSel = sel.value === n.id
    const op = dimAll.value && !inChain ? 0.15 : (rate === null ? 0.6 : 1)
    const lp = L.labelPos[n.id] || { x: p[0], y: p[1] + r + 16, a: 'middle' }
    return {
      id: n.id, x: p[0], y: p[1], r,
      fill: isSel ? pal.value.acc : fill, op, isSel,
      lx: lp.x, ly: lp.y, la: lp.a, short: nameShort(n),
      labelHi: inChain, labelOp: dimAll.value && !inChain ? 0.1 : (rate === null ? 0.55 : 0.92)
    }
  })
})

const rows = computed(() => {
  if (!layout.value) return []
  return Object.keys(MAST)
    .map((id) => { const m = MAST[id]; return { id, m: m[0], w: m[1], rate: m[0] / (m[0] + m[1]) } })
    .sort((a, c) => c.w - a.w || a.rate - c.rate)
    .slice(0, 8)
    .map((x, i) => ({
      id: x.id, rank: String(i + 1).padStart(2, '0'), name: nameOf(x.id),
      chapter: layout.value.byId[x.id] ? layout.value.byId[x.id].chapter : '',
      weak: x.w, rate: Math.round(x.rate * 100) + '%',
      gW: Math.round(x.m / 86 * 100) + '%', wW: Math.round(x.w / 86 * 100) + '%'
    }))
})

const selM = computed(() => (sel.value ? MAST[sel.value] : null))
const selRate = computed(() => (selM.value ? Math.round(selM.value[0] / (selM.value[0] + selM.value[1]) * 100) + '%' : ''))
const selG = computed(() => (selM.value ? Math.round(selM.value[0] / 86 * 100) + '%' : '0%'))
const selW = computed(() => (selM.value ? Math.round(selM.value[1] / 86 * 100) + '%' : '0%'))
const causeText = computed(() => {
  const c = sel.value ? CAUSE[sel.value] : null
  if (c && selM.value) return selM.value[1] + ' 名薄弱学生中，' + c.n + ' 人在前置点「' + nameOf(c.pre) + '」同样薄弱 — 建议下次课优先回补该前置点。'
  return ''
})

function toggleSel(id) { sel.value = sel.value === id ? null : id }
function onSvgClick(ev) { if (ev.target.tagName === 'svg') sel.value = null }
</script>

<style scoped>
.wj-hover-mut:hover {
  border-color: var(--mut) !important;
}
</style>
