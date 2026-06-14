<template>
  <div :style="{ height: '100vh', minWidth: '1180px', display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'var(--bg)', color: 'var(--ink)', transition: 'background-color 0.35s, color 0.35s' }">

    <TopBar teacher compact subtitle="软件工程 · 图谱审核工作台">
      <NavLink to="/teacher/questions">题目审核池</NavLink>
      <NavLink to="/teacher/dashboard">学情看板</NavLink>
    </TopBar>

    <!-- 状态条 -->
    <div :style="{ height: '46px', flex: 'none', display: 'flex', alignItems: 'center', gap: '16px', padding: '0 20px', borderBottom: '1px solid var(--line)', fontSize: '12.5px', color: 'var(--mut)', transition: 'border-color 0.35s' }">
      <span>AI 初稿 <b :style="bk">v0.4</b> · 基于课程大纲与教材第 1–13 章生成 · 新增 12 条候选边</span>
      <div :style="{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '12px' }">
        <span>已处理 <b :style="bk">{{ doneCount }}</b> / 12</span>
        <div :style="{ width: '120px', height: '4px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden' }">
          <div :style="{ height: '100%', width: donePct, background: 'var(--ok)', borderRadius: '99px', transition: 'width 0.3s' }"></div>
        </div>
        <button @click="publish" :style="{ height: '32px', padding: '0 18px', border: 'none', borderRadius: '8px', fontSize: '12.5px', fontWeight: 500, background: published ? 'var(--okSoft)' : (allDone ? 'var(--acc)' : 'var(--card2)'), color: published ? 'var(--ok)' : (allDone ? '#FFFDF8' : 'var(--mut)'), cursor: allDone && !published ? 'pointer' : 'default', transition: 'background-color 0.25s, color 0.25s' }">{{ published ? '已发布 v0.4 ✓' : '发布图谱' }}</button>
      </div>
    </div>

    <!-- 主体 -->
    <div :style="{ flex: 1, minHeight: 0, display: 'flex' }">

      <!-- 图谱画布 -->
      <div :style="{ flex: 1, minWidth: 0, position: 'relative', overflow: 'hidden' }">
        <svg v-if="layout" viewBox="0 0 1480 740" preserveAspectRatio="xMidYMid meet" @click="onSvgClick" :style="{ width: '100%', height: '100%', display: 'block', transform: mapIn ? 'scale(1)' : 'scale(0.96)', transformOrigin: '50% 50%', transition: 'transform 1.2s cubic-bezier(0.22,1,0.36,1)' }">
          <defs>
            <marker v-for="mk in markers" :key="mk.id" :id="mk.id" viewBox="0 0 10 10" :refX="8" :refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 1 L 8 5 L 0 9 z" :fill="mk.color" />
            </marker>
          </defs>

          <template v-if="theme === 'ink'">
            <circle v-for="(s, i) in layout.stars" :key="'st' + i" :cx="s.x" :cy="s.y" :r="s.r" :fill="pal.star" :opacity="s.o * 0.5">
              <animate v-if="i % 6 === 0" attributeName="opacity" :values="(s.o * 0.5) + ';' + Math.min(0.55, s.o * 1.6) + ';' + (s.o * 0.5)" :dur="(3.5 + (i % 9) * 0.7) + 's'" repeatCount="indefinite" />
            </circle>
          </template>

          <text v-for="ch in chapterLabels" :key="'ch' + ch.name" :x="ch.x" :y="ch.y" text-anchor="middle" :fill="pal.chap" :opacity="ch.op" font-size="21" letter-spacing="7" :font-family="serif" font-weight="500" :style="{ transition: 'opacity 0.3s', pointerEvents: 'none' }">{{ ch.name }}</text>

          <line v-for="(e, i) in baseEdges" :key="'ge' + i" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="pal.edge" :stroke-width="e.w" :stroke-dasharray="e.dash" :opacity="e.op" :style="{ transition: 'opacity 0.3s' }" />

          <template v-for="e in candEdges" :key="'ce' + e.k">
            <line :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="e.color" :stroke-width="e.width" :stroke-dasharray="e.dash" :marker-end="e.marker" :opacity="e.op" :style="{ transition: 'opacity 0.3s' }" />
            <line v-if="e.pending" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" stroke="transparent" stroke-width="16" :style="{ cursor: 'pointer' }" @click.stop="toggleSel(e.k)" />
          </template>

          <template v-for="n in nodeList" :key="n.id">
            <circle :cx="n.x" :cy="n.y" :r="n.r" :fill="n.fill" :opacity="n.op" :style="{ transition: 'opacity 0.3s, fill 0.3s' }" />
            <text :x="n.lx" :y="n.ly" :text-anchor="n.la" :fill="n.hi ? pal.labelHi : pal.label" font-size="11.5" :opacity="n.labelOp" :style="{ transition: 'opacity 0.3s', pointerEvents: 'none' }">{{ n.short }}</text>
          </template>
        </svg>
        <div v-else :style="{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)', fontSize: '13px' }">正在绘制图谱……</div>

        <!-- 图例 -->
        <div :style="{ position: 'absolute', left: '16px', bottom: '14px', display: 'flex', flexDirection: 'column', gap: '8px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '12px 15px', fontSize: '11.5px', color: 'var(--mut)', transition: 'background-color 0.35s, border-color 0.35s' }">
          <span :style="lg"><svg width="26" height="6" :style="{ flex: 'none' }"><line x1="0" y1="3" x2="26" y2="3" stroke="var(--warn)" stroke-width="2" stroke-dasharray="6 4" /></svg>待复核候选边</span>
          <span :style="lg"><svg width="26" height="6" :style="{ flex: 'none' }"><line x1="0" y1="3" x2="26" y2="3" stroke="var(--ok)" stroke-width="2" /></svg>本次已采纳</span>
          <span :style="lg"><svg width="26" height="6" :style="{ flex: 'none' }"><line x1="0" y1="3" x2="26" y2="3" stroke="var(--mut)" stroke-width="1" opacity="0.5" /></svg>已确认图谱</span>
        </div>
        <div :style="{ position: 'absolute', right: '16px', top: '12px', fontSize: '11.5px', color: 'var(--mut)', opacity: 0.8 }">点击列表或虚线边，可在图上定位</div>
      </div>

      <!-- 审核列表 -->
      <div :style="{ width: '400px', flex: 'none', boxSizing: 'border-box', borderLeft: '1px solid var(--line)', display: 'flex', flexDirection: 'column', transition: 'border-color 0.35s' }">
        <div :style="{ flex: 'none', padding: '18px 20px 12px', display: 'flex', alignItems: 'baseline', gap: '10px' }">
          <span :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)' }">待复核边</span>
          <span :style="{ fontSize: '12px', color: 'var(--mut)', marginLeft: 'auto' }">按置信度排序 · 剩 {{ pendingCount }} 条</span>
        </div>

        <div :style="{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 16px 16px', display: 'flex', flexDirection: 'column', gap: '10px' }">
          <div v-for="(c, i) in cards" :key="c.k" @click="toggleSel(c.k)" class="wj-hover-mut" :style="{ background: 'var(--card)', border: '1.5px solid ' + (sel === c.k ? 'var(--acc)' : 'var(--line)'), borderRadius: '12px', padding: '14px 16px', cursor: 'pointer', transition: 'border-color 0.2s, background-color 0.35s', animation: 'wjFadeUp 0.45s cubic-bezier(0.22,1,0.36,1) both', animationDelay: (Math.min(i, 8) * 0.05) + 's' }">
            <div :style="{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }">
              <span :style="{ fontSize: '11px', letterSpacing: '1px', color: c.type === '前置' ? 'var(--ink)' : 'var(--mut)', border: '1px solid var(--line)', borderRadius: '5px', padding: '2px 7px' }">{{ c.type }}</span>
              <span :style="{ fontSize: '11.5px', color: c.low ? 'var(--warn)' : 'var(--mut)', background: c.low ? 'var(--warnSoft)' : 'var(--card2)', borderRadius: '999px', padding: '2px 9px' }">置信度 {{ c.conf }}%</span>
              <span :style="{ marginLeft: 'auto', fontSize: '11px', color: 'var(--mut)', opacity: 0.7 }">AI-{{ c.k.toUpperCase() }}</span>
            </div>
            <div :style="{ display: 'flex', alignItems: 'center', gap: '9px', fontSize: '13.5px', fontWeight: 500, marginBottom: '9px', flexWrap: 'wrap' }">
              <span>{{ c.from }}</span>
              <span :style="{ color: 'var(--mut)', fontSize: '12px' }">→</span>
              <span>{{ c.to }}</span>
            </div>
            <div :style="{ fontSize: '12px', color: 'var(--mut)', lineHeight: 1.7, marginBottom: '12px' }">{{ c.why }}</div>
            <div :style="{ display: 'flex', gap: '8px' }">
              <button @click.stop="setEdge(c.k, 'accepted')" class="wj-btn-acc" :style="{ flex: 1, height: '34px', background: 'var(--acc)', border: 'none', borderRadius: '8px', color: '#FFFDF8', fontSize: '12.5px', fontWeight: 500, cursor: 'pointer' }">采纳</button>
              <button @click.stop="setEdge(c.k, 'rejected')" class="wj-hover-card2" :style="{ height: '34px', padding: '0 18px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '8px', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer' }">驳回</button>
            </div>
          </div>

          <div v-if="allDone" :style="{ textAlign: 'center', padding: '36px 12px 22px', animation: 'wjFadeUp 0.4s ease both' }">
            <div :style="{ fontFamily: serif, fontSize: '17px', letterSpacing: '3px', marginBottom: '8px' }">边边俱到</div>
            <div :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.7 }">12 条候选边已全部复核，可以发布图谱了。</div>
          </div>

          <!-- 已处理 -->
          <div :style="{ flex: 'none', paddingTop: '8px' }">
            <div :style="{ fontSize: '11.5px', letterSpacing: '3px', color: 'var(--mut)', padding: '8px 4px 10px' }">已处理 · {{ doneCount }}</div>
            <div :style="{ display: 'flex', flexDirection: 'column', gap: '6px' }">
              <div v-for="d in doneRows" :key="d.k" :style="{ display: 'flex', alignItems: 'center', gap: '9px', padding: '9px 12px', background: 'var(--card2)', borderRadius: '9px', fontSize: '12px', transition: 'background-color 0.35s' }">
                <span :style="{ color: d.accepted ? 'var(--ok)' : 'var(--mut)', flex: 'none' }">{{ d.accepted ? '已采纳' : '已驳回' }}</span>
                <span :style="{ color: 'var(--mut)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }">{{ d.from }} → {{ d.to }}</span>
                <button @click="setEdge(d.k, 'pending')" class="wj-underline" :style="{ marginLeft: 'auto', flex: 'none', background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '11.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">撤销</button>
              </div>
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

const bk = { color: 'var(--ink)', fontWeight: 500 }
const lg = { display: 'inline-flex', alignItems: 'center', gap: '8px' }

const SHORT_EXTRA = {
  KT01: '软工概念与生命周期', KT02: '传统过程模型', KT03: '现代过程模型', KT04: '需求概念与目标',
  KT05: '业务流程分析', KT06: '团队组织管理', KT07: '用例模型', KT10: '领域模型', KT13: '软件设计概念',
  KT14: '通用设计原则', KT15: '架构设计', KT16: '交互设计', KT17: '数据设计', KT18: 'OO设计原则',
  KT19: '设计类构建', KT20: '类设计转代码', KT25: '软件质量管理', KT27: 'UML建模工具',
  KT28: '数据库建模工具', KT29: '原型设计工具', 'KT15-1': '分层架构', 'KT18-2': '设计模式'
}
const PAL = {
  ink: { node: '#4A4D55', nodeKey: '#6B6F7A', edge: 'rgba(232,227,216,0.10)', label: '#9A948A', labelHi: '#E8E3D8', star: '#E8E3D8', chap: '#9A948A', warn: '#E0A33E', acc: '#D85E45', ok: '#57A87E' },
  paper: { node: '#C9C2B4', nodeKey: '#A89F8D', edge: 'rgba(42,37,32,0.12)', label: '#6F6759', labelHi: '#2A2520', star: '#6F6759', chap: '#6F6759', warn: '#C8862A', acc: '#B4422E', ok: '#3D7A5E' }
}
const ANCH = {
  软件工程概述: [180, 320], 软件项目管理: [430, 110], 需求确定: [450, 480], 系统分析: [730, 300],
  系统设计: [950, 510], 对象设计: [1010, 190], 软件测试: [1220, 360], 部署与维护: [1350, 150]
}

const edges = ref([
  { k: 'e1', s: 'KT05', t: 'KT10', type: '前置', conf: 93, why: '领域类的候选实体多来自业务流程中的名词与数据对象，教材 6.2 节有直接对应。', status: 'pending' },
  { k: 'e2', s: 'KT04', t: 'KT07', type: '前置', conf: 89, why: '功能需求的表述方式直接决定用例的粒度与系统边界划定。', status: 'pending' },
  { k: 'e3', s: 'KT13', t: 'KT18', type: '前置', conf: 86, why: '面向对象设计原则建立在抽象、封装等通用设计概念之上。', status: 'pending' },
  { k: 'e4', s: 'KT18-2', t: 'KT19', type: '前置', conf: 81, why: '设计类的协作结构大量复用典型模式，先识模式再构建类。', status: 'pending' },
  { k: 'e5', s: 'KT17', t: 'KT20', type: '前置', conf: 74, why: '持久化字段与映射约束影响类到代码的转换细节。', status: 'pending' },
  { k: 'e6', s: 'KT22', t: 'KT24', type: '前置', conf: 69, why: '上线前的回归与验收测试是部署的准入条件，证据来自大纲第 12 章。', status: 'pending' },
  { k: 'e7', s: 'KT15-1', t: 'KT17', type: '前置', conf: 66, why: '数据访问层的职责划分依赖分层架构的依赖规则。', status: 'pending' },
  { k: 'e8', s: 'KT03', t: 'KT06', type: '相关', conf: 58, why: '敏捷过程与团队组织方式互相影响，证据强度一般。', status: 'pending' },
  { k: 'e9', s: 'KT29', t: 'KT05', type: '相关', conf: 51, why: '原型工具可辅助业务流程梳理，仅在案例脚注中出现，证据较弱。', status: 'pending' },
  { k: 'p1', s: 'KT06', t: 'KT25', type: '前置', conf: 84, why: '质量管理改挂团队组织管理。', status: 'accepted' },
  { k: 'p2', s: 'KT10', t: 'KT13', type: '前置', conf: 88, why: '分析到设计的衔接边。', status: 'accepted' },
  { k: 'p3', s: 'KT01', t: 'KT13', type: '前置', conf: 55, why: '跨度过大，经设计概念链已覆盖。', status: 'rejected' }
])

const layout = ref(null)
const sel = ref(null)
const published = ref(false)
const mapIn = ref(false)
const pal = computed(() => PAL[theme.value])

const markers = computed(() => [
  { id: 'arrW', color: pal.value.warn },
  { id: 'arrA', color: pal.value.acc },
  { id: 'arrK', color: pal.value.ok }
])

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
function nameShort(n) {
  if (SHORT_EXTRA[n.id]) return SHORT_EXTRA[n.id]
  return shortName(n)
}

const pending = computed(() => edges.value.filter((e) => e.status === 'pending').sort((a, b) => b.conf - a.conf))
const done = computed(() => edges.value.filter((e) => e.status !== 'pending'))
const doneCount = computed(() => done.value.length)
const pendingCount = computed(() => pending.value.length)
const allDone = computed(() => pending.value.length === 0)
const donePct = computed(() => Math.round(doneCount.value / 12 * 100) + '%')

const selEdge = computed(() => {
  if (!sel.value) return null
  const e = edges.value.find((x) => x.k === sel.value)
  return e && e.status === 'pending' ? e : null
})
const dimAll = computed(() => !!selEdge.value)
const hiNodes = computed(() => {
  const h = {}
  if (selEdge.value) { h[selEdge.value.s] = true; h[selEdge.value.t] = true }
  return h
})

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
    out.push({ x1: a[0], y1: a[1], x2: b2[0], y2: b2[1], w: e.type === '包含' ? 0.8 : 1.2, dash: e.type === '相关' ? '2 5' : 'none', op: dimAll.value ? 0.35 : 1 })
  })
  return out
})

function trim(s, t) {
  const L = layout.value
  const a = L.pos[s]; const b2 = L.pos[t]
  const dx = b2[0] - a[0]; const dy = b2[1] - a[1]
  const d = Math.sqrt(dx * dx + dy * dy) + 0.01
  const ux = dx / d; const uy = dy / d
  const ra = L.radius[s] + 4; const rb = L.radius[t] + 9
  return { x1: a[0] + ux * ra, y1: a[1] + uy * ra, x2: b2[0] - ux * rb, y2: b2[1] - uy * rb }
}

const candEdges = computed(() => {
  if (!layout.value) return []
  const L = layout.value
  const out = []
  edges.value.forEach((e) => {
    if (e.status === 'rejected') return
    if (!L.pos[e.s] || !L.pos[e.t]) return
    const p = trim(e.s, e.t)
    const isSel = selEdge.value && selEdge.value.k === e.k
    const pendingE = e.status === 'pending'
    const color = isSel ? pal.value.acc : (pendingE ? pal.value.warn : pal.value.ok)
    out.push({
      k: e.k, ...p, color,
      width: isSel ? 2.6 : (pendingE ? 2 : 1.6),
      dash: pendingE && !isSel ? '7 6' : 'none',
      marker: 'url(#' + (isSel ? 'arrA' : (pendingE ? 'arrW' : 'arrK')) + ')',
      op: dimAll.value && !isSel ? 0.18 : (pendingE ? 0.9 : 0.7),
      pending: pendingE
    })
  })
  return out
})

const nodeList = computed(() => {
  if (!layout.value) return []
  const L = layout.value
  return data.value.nodes.map((n) => {
    const p = L.pos[n.id]; const r = L.radius[n.id]
    const hi = hiNodes.value[n.id]
    const lp = L.labelPos[n.id] || { x: p[0], y: p[1] + r + 16, a: 'middle' }
    return {
      id: n.id, x: p[0], y: p[1], r,
      fill: hi ? pal.value.acc : (n.is_key ? pal.value.nodeKey : pal.value.node),
      op: dimAll.value && !hi ? 0.18 : 1,
      lx: lp.x, ly: lp.y, la: lp.a, short: nameShort(n), hi,
      labelOp: dimAll.value && !hi ? 0.12 : 0.92
    }
  })
})

const cards = computed(() => pending.value.map((e) => ({ k: e.k, type: e.type, conf: e.conf, low: e.conf < 70, from: nameOf(e.s), to: nameOf(e.t), why: e.why })))
const doneRows = computed(() => done.value.map((e) => ({ k: e.k, accepted: e.status === 'accepted', from: nameOf(e.s), to: nameOf(e.t) })))

function setEdge(k, status) {
  edges.value = edges.value.map((e) => (e.k === k ? { ...e, status } : e))
  if (sel.value === k && status !== 'pending') sel.value = null
  published.value = false
}
function toggleSel(k) { sel.value = sel.value === k ? null : k }
function onSvgClick(ev) { if (ev.target.tagName === 'svg') sel.value = null }
function publish() { if (allDone.value) published.value = true }
</script>

<style scoped>
.wj-hover-mut:hover {
  border-color: var(--mut) !important;
}
</style>
