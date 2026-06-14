<template>
  <div :style="{ height: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)', overflow: 'hidden', transition: 'background-color 0.35s, color 0.35s' }">

    <!-- 顶栏 -->
    <div :style="{ height: '60px', flex: 'none', display: 'flex', alignItems: 'center', gap: '14px', padding: '0 20px', borderBottom: '1px solid var(--line)', transition: 'border-color 0.35s' }">
      <span :style="{ fontFamily: serif, fontSize: '22px', fontWeight: 600, letterSpacing: '3px', whiteSpace: 'nowrap', flex: 'none' }">问津</span>
      <div :style="{ width: '1px', height: '18px', background: 'var(--line)', flex: 'none' }"></div>
      <span v-show="width >= 560" :style="{ fontSize: '14px', fontWeight: 500, whiteSpace: 'nowrap', flex: 'none' }">软件工程 · 染色地图</span>

      <div :style="{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '10px' }">
        <NavLink v-show="width >= 1230" to="/path">学习路径</NavLink>
        <NavLink v-show="width >= 1230" to="/growth">成长档案</NavLink>

        <!-- 搜索 -->
        <div v-show="width >= 720" :style="{ position: 'relative' }">
          <input v-model="query" placeholder="搜索知识点…" class="wj-search" :style="{ width: '190px', height: '34px', boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '8px', padding: '0 12px', color: 'var(--ink)', fontSize: '13px', outline: 'none', transition: 'background-color 0.35s, border-color 0.35s' }" />
          <div v-if="results.length" :style="{ position: 'absolute', top: '40px', left: 0, width: '280px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '6px', zIndex: 40, boxShadow: '0 8px 24px rgba(0,0,0,0.18)' }">
            <div v-for="r in results" :key="r.id" @click="pickNode(r.id)" class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 10px', borderRadius: '6px', cursor: 'pointer' }">
              <span :style="{ width: '7px', height: '7px', borderRadius: '50%', background: r.color, flex: 'none' }"></span>
              <span :style="{ fontSize: '13px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }">{{ r.name }}</span>
              <span :style="{ fontSize: '11px', color: 'var(--mut)', marginLeft: 'auto', flex: 'none' }">{{ r.chapter }}</span>
            </div>
          </div>
        </div>

        <!-- 章节筛选 -->
        <select v-show="width >= 900" v-model="chapterFilter" :style="{ height: '34px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '8px', padding: '0 8px', color: 'var(--ink)', fontSize: '12.5px', outline: 'none', cursor: 'pointer', transition: 'background-color 0.35s, border-color 0.35s' }">
          <option v-for="c in CHAPTERS" :key="c" :value="c">{{ c }}</option>
        </select>

        <!-- 薄弱根因回溯 -->
        <button @click="toggleRoot" class="wj-hover-acc" :style="rootCause
          ? { height: '34px', padding: '0 14px', background: 'var(--accSoft)', border: '1px solid var(--acc)', borderRadius: '8px', color: 'var(--acc)', fontSize: '12.5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '7px', fontWeight: 500, whiteSpace: 'nowrap', flex: 'none' }
          : { height: '34px', padding: '0 14px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '8px', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '7px', whiteSpace: 'nowrap', flex: 'none' }">
          <span :style="{ width: '6px', height: '6px', borderRadius: '50%', background: rootCause ? 'var(--acc)' : 'var(--mut)', opacity: rootCause ? 1 : 0.5 }"></span>{{ width < 1080 ? '回溯' : '薄弱根因回溯' }}
        </button>

        <ThemeToggle />
      </div>
    </div>

    <!-- 画布 -->
    <div :style="{ flex: 1, position: 'relative', overflow: 'hidden' }">
      <svg v-if="layout" viewBox="0 0 1480 740" preserveAspectRatio="xMidYMid meet" :style="{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block', transform: mapIn ? 'scale(1)' : 'scale(0.96)', transformOrigin: '50% 50%', transition: 'transform 1.2s cubic-bezier(0.22,1,0.36,1)' }">

        <!-- 星空 -->
        <template v-if="theme === 'ink'">
          <circle v-for="(s, i) in layout.stars" :key="'st' + i" :cx="s.x" :cy="s.y" :r="s.r" :fill="pal.star" :opacity="s.o * 0.5">
            <animate v-if="i % 6 === 0" attributeName="opacity" :values="(s.o * 0.5) + ';' + Math.min(0.55, s.o * 1.6) + ';' + (s.o * 0.5)" :dur="(3.5 + (i % 9) * 0.7) + 's'" repeatCount="indefinite" />
          </circle>
        </template>

        <!-- 章节名 -->
        <text v-for="ch in chapterLabels" :key="'ch' + ch.name" :x="ch.x" :y="ch.y" text-anchor="middle" :fill="pal.chap" :opacity="ch.op" font-size="21" letter-spacing="7" :font-family="serif" font-weight="500" :style="{ transition: 'opacity 0.3s', pointerEvents: 'none' }">{{ ch.name }}</text>

        <!-- 边 -->
        <template v-for="(e, i) in edgeList" :key="'e' + i">
          <line v-if="e.glow" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="pal.cur" stroke-width="12" opacity="0.2" stroke-linecap="round">
            <animate attributeName="opacity" values="0.1;0.32;0.1" dur="2.2s" repeatCount="indefinite" />
          </line>
          <line :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="e.stroke" :stroke-width="e.width" :opacity="e.op" :stroke-dasharray="e.dash" :style="{ transition: 'opacity 0.25s, stroke 0.25s', animation: e.anim }" />
        </template>

        <!-- 节点 -->
        <g v-for="n in nodeList" :key="n.id" :opacity="n.op" :style="{ cursor: 'pointer', transition: 'opacity 0.25s' }" @mouseenter="hoverId = n.id" @mouseleave="hoverId = null" @click="selectedId = n.id">
          <template v-if="n.status !== 'dim'">
            <circle :cx="n.x" :cy="n.y" :r="n.r * 1.95" :fill="n.c" opacity="0.08" />
            <circle :cx="n.x" :cy="n.y" :r="n.r * 1.35" :fill="n.c" opacity="0.18" />
          </template>
          <circle v-if="n.rootGlow" :cx="n.x" :cy="n.y" :r="n.r * 2.8" :fill="n.c" opacity="0.1">
            <animate attributeName="opacity" values="0.06;0.18;0.06" dur="2.2s" repeatCount="indefinite" />
          </circle>
          <circle :cx="n.x" :cy="n.y" :r="n.r" :fill="n.status === 'dim' ? 'none' : n.c" :stroke="n.status === 'dim' ? n.c : 'none'" :stroke-width="n.status === 'dim' ? 1.4 : 0" />
          <template v-if="n.isCurrent">
            <rect :x="n.x - (n.r * 2 + 13) / 2" :y="n.y - (n.r * 2 + 13) / 2" :width="n.r * 2 + 13" :height="n.r * 2 + 13" rx="5" fill="none" :stroke="pal.cur" stroke-width="1.5" />
            <circle :cx="n.x" :cy="n.y" :r="n.r + 5" fill="none" :stroke="pal.cur" stroke-width="1.5">
              <animate attributeName="r" :values="(n.r + 5) + ';' + (n.r + 22)" dur="2.4s" repeatCount="indefinite" />
              <animate attributeName="opacity" values="0.6;0" dur="2.4s" repeatCount="indefinite" />
            </circle>
          </template>
          <circle v-if="n.selRing" :cx="n.x" :cy="n.y" :r="n.r + 5.5" fill="none" :stroke="n.c" stroke-width="1.2" opacity="0.85" />
          <text v-if="n.showLabel" :x="n.lx" :y="n.ly" :text-anchor="n.la" :font-size="n.isMain ? 12 : 11" :fill="n.hi ? pal.labelHi : pal.label" :opacity="n.isMain ? 0.9 : 0.85" :style="{ pointerEvents: 'none' }">{{ n.short }}</text>
        </g>
      </svg>

      <!-- 加载态 -->
      <div v-else :style="{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)', fontSize: '13px' }">正在绘制知识图谱……</div>

      <!-- 诊断结论卡 -->
      <div v-if="rootCause" :style="{ position: 'absolute', left: '20px', top: '20px', width: '352px', boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '12px', padding: '18px 20px', zIndex: 20, animation: 'wjFadeUp 0.35s cubic-bezier(0.22,1,0.36,1) both', transition: 'background-color 0.35s, border-color 0.35s' }">
        <div :style="{ fontSize: '11px', letterSpacing: '2px', color: 'var(--mut)', marginBottom: '10px' }">诊断结论 · 6月10日</div>
        <div :style="{ fontFamily: serif, fontSize: '18px', fontWeight: 600, lineHeight: 1.5, marginBottom: '10px' }">你卡在「领域类图绘制」</div>
        <div :style="{ fontSize: '13px', color: 'var(--mut)', lineHeight: 1.75, marginBottom: '14px' }">根本原因更可能在前置点「用例图绘制」——参与者与系统边界识别不清，导致业务实体提取困难，领域类图无从下手。最近 3 次练习中，实体识别相关错误率 67%。</div>
        <div :style="{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '6px', marginBottom: '16px' }">
          <template v-for="(c, i) in rootChips" :key="i">
            <span :style="{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11.5px', color: 'var(--ink)', border: '1px solid var(--line)', borderRadius: '999px', padding: '4px 9px' }">
              <span :style="{ width: '6px', height: '6px', borderRadius: '50%', background: c.color }"></span>{{ c.name }}
            </span>
            <span v-if="c.arrow" :style="{ fontSize: '11px', color: 'var(--mut)' }">{{ c.arrow }}</span>
          </template>
        </div>
        <div :style="{ display: 'flex', gap: '10px' }">
          <router-link to="/path" class="wj-btn-acc" :style="{ flex: 1, height: '38px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, textDecoration: 'none' }">生成学习路径</router-link>
          <button @click="toggleRoot" class="wj-hover-acc" :style="{ height: '38px', padding: '0 14px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--mut)', fontSize: '13px', cursor: 'pointer' }">退出演示</button>
        </div>
      </div>

      <!-- 图例 -->
      <div v-if="!rootCause" :style="{ position: 'absolute', left: '20px', bottom: '20px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '12px', padding: '13px 16px', zIndex: 10, transition: 'background-color 0.35s, border-color 0.35s' }">
        <div :style="{ display: 'flex', flexDirection: 'column', gap: '8px', fontSize: '12px' }">
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--ok)' }"></span><span>已掌握</span></div>
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--warn)' }"></span><span>薄弱 · 待修</span></div>
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--dim)' }"></span><span>未学</span></div>
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '3px', border: '1.5px solid var(--acc)', boxSizing: 'border-box' }"></span><span>当前位置 / 根因</span></div>
        </div>
        <div :style="{ height: '1px', background: 'var(--line)', margin: '11px 0 9px' }"></div>
        <div :style="{ fontSize: '11.5px', color: 'var(--mut)' }">已掌握 22 · 薄弱 5 · 未学 15</div>
      </div>

      <div v-show="width >= 640" :style="{ position: 'absolute', right: '20px', bottom: '20px', fontSize: '12px', color: 'var(--mut)', opacity: 0.75, zIndex: 10 }">悬停节点查看前置链 · 点击查看详情</div>

      <!-- 抽屉 -->
      <div v-if="sel" :style="{ position: 'absolute', right: '16px', top: '16px', bottom: '16px', width: drawerW, boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', display: 'flex', flexDirection: 'column', zIndex: 30, boxShadow: '0 12px 36px rgba(0,0,0,0.16)', animation: 'wjDrawerIn 0.34s cubic-bezier(0.22,1,0.36,1) both', transition: 'background-color 0.35s, border-color 0.35s' }">
        <div :style="{ flex: 1, overflowY: 'auto', padding: '20px 22px 10px' }">
          <div :style="{ display: 'flex', alignItems: 'flex-start', gap: '10px', marginBottom: '12px' }">
            <span :style="{ fontSize: '11.5px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '3px 10px', flex: 'none' }">{{ sel.chapter }}</span>
            <button @click="selectedId = null" class="wj-hover-card2" :style="{ marginLeft: 'auto', width: '28px', height: '28px', border: 'none', background: 'transparent', color: 'var(--mut)', fontSize: '17px', cursor: 'pointer', borderRadius: '6px', lineHeight: 1 }">×</button>
          </div>
          <div :style="{ fontSize: '17px', fontWeight: 600, lineHeight: 1.45, marginBottom: '14px' }">{{ sel.name }}</div>

          <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '8px' }">
            <span :style="{ fontSize: '12px', fontWeight: 500, color: selStatusColor, background: selStatusBg, borderRadius: '999px', padding: '4px 11px' }">{{ selStatusLabel }}</span>
            <span :style="{ fontSize: '20px', fontWeight: 600, color: selStatusColor, marginLeft: 'auto' }">{{ selMasteryText }}</span>
          </div>
          <div :style="{ height: '5px', background: 'var(--line)', borderRadius: '99px', overflow: 'hidden', marginBottom: '14px' }">
            <div :style="{ height: '100%', borderRadius: '99px', background: selStatusColor, width: selMasteryPct, transition: 'width 0.4s ease' }"></div>
          </div>

          <div :style="{ display: 'flex', gap: '16px', fontSize: '12px', color: 'var(--mut)', marginBottom: '14px', flexWrap: 'wrap' }">
            <span>难度 <span :style="{ letterSpacing: '1px' }">{{ selDiffDots }}</span></span>
            <span>认知层级 · {{ sel.bloom }}</span>
            <span v-if="sel.is_key" :style="{ color: 'var(--ink)' }">考核重点</span>
          </div>

          <div :style="{ fontSize: '13px', color: 'var(--mut)', lineHeight: 1.75, paddingBottom: '16px', borderBottom: '1px solid var(--line)' }">{{ sel.description }}</div>

          <div :style="{ fontSize: '11.5px', letterSpacing: '2px', color: 'var(--mut)', margin: '16px 0 10px' }">前置知识点</div>
          <div v-if="selPrereqs.length" :style="{ display: 'flex', flexDirection: 'column', gap: '6px' }">
            <div v-for="p in selPrereqs" :key="p.id" @click="selectedId = p.id" class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', gap: '9px', padding: '9px 11px', border: '1px solid var(--line)', borderRadius: '9px', cursor: 'pointer' }">
              <span :style="{ width: '8px', height: '8px', borderRadius: '50%', background: p.color, flex: 'none' }"></span>
              <span :style="{ fontSize: '12.5px', lineHeight: 1.4 }">{{ p.name }}</span>
              <span :style="{ fontSize: '11px', color: 'var(--mut)', marginLeft: 'auto', flex: 'none' }">{{ p.statusLabel }}</span>
            </div>
          </div>
          <div v-else :style="{ fontSize: '12.5px', color: 'var(--mut)' }">无前置依赖，可直接学习</div>

          <div :style="{ fontSize: '11.5px', letterSpacing: '2px', color: 'var(--mut)', margin: '18px 0 10px' }">学习资源</div>
          <div :style="{ display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '12px' }">
            <div v-for="(res, i) in selResources" :key="i" class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', gap: '9px', padding: '9px 11px', border: '1px solid var(--line)', borderRadius: '9px', cursor: 'pointer' }">
              <span :style="{ fontSize: '10.5px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '5px', padding: '2px 6px', flex: 'none' }">{{ res.t }}</span>
              <span :style="{ fontSize: '12.5px' }">{{ res.n }}</span>
            </div>
          </div>
        </div>
        <div :style="{ flex: 'none', display: 'flex', gap: '10px', padding: '14px 22px 16px', borderTop: '1px solid var(--line)' }">
          <router-link to="/knowledge" class="wj-btn-acc" :style="{ flex: 1, height: '40px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, textDecoration: 'none' }">{{ selCta }}</router-link>
          <router-link to="/companion" class="wj-hover-card2" :style="{ height: '40px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 16px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '13px', textDecoration: 'none' }">问 AI 伴侣</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import ThemeToggle from '../components/ThemeToggle.vue'
import NavLink from '../components/NavLink.vue'
import { useTheme } from '../composables/useTheme.js'
import { useViewport } from '../composables/useViewport.js'
import { useGraphData } from '../composables/useGraphData.js'
import { computeLayout, shortName, radiusOf } from '../utils/graphLayout.js'

const serif = "'Noto Serif SC', serif"
const { theme } = useTheme()
const { width } = useViewport()
const { data } = useGraphData()
const route = useRoute()

const CURRENT = 'KT10-2'
const ROOT_NODES = ['KT07-2', 'KT07', 'KT10', 'KT10-1', 'KT10-2']
const ROOT_EDGES = ['KT07>KT07-2', 'KT07>KT10', 'KT10>KT10-1', 'KT10-1>KT10-2']
const CHAPTERS = ['全部章节', '软件工程概述', '需求确定', '软件项目管理', '系统分析', '系统设计', '对象设计', '软件测试', '部署与维护']

const MASTERY = {
  KT01: [92, 'ok'], KT02: [88, 'ok'], 'KT02-1': [90, 'ok'], 'KT02-2': [81, 'ok'],
  KT03: [84, 'ok'], 'KT03-1': [86, 'ok'], 'KT03-2': [78, 'ok'],
  KT04: [90, 'ok'], KT05: [83, 'ok'], 'KT05-1': [85, 'ok'], 'KT05-2': [80, 'ok'],
  KT06: [82, 'ok'], KT27: [88, 'ok'], KT29: [86, 'ok'],
  'KT07-1': [79, 'ok'], 'KT07-4': [77, 'ok'], 'KT07-2': [81, 'ok'],
  KT13: [75, 'ok'], KT14: [72, 'ok'], KT15: [73, 'ok'], KT16: [76, 'ok'], KT17: [74, 'ok'],
  KT07: [62, 'warn'], 'KT07-3': [55, 'warn'],
  KT10: [58, 'warn'], 'KT10-1': [52, 'warn'],
  'KT10-2': [34, 'cur']
}

const PAL = {
  ink: { ok: '#57A87E', warn: '#E0A33E', dim: '#4A4D55', cur: '#D85E45', edge: 'rgba(232,227,216,0.10)', label: '#9A948A', labelHi: '#E8E3D8', star: '#E8E3D8', chap: '#9A948A' },
  paper: { ok: '#3D7A5E', warn: '#C8862A', dim: '#C9C2B4', cur: '#B4422E', edge: 'rgba(42,37,32,0.12)', label: '#6F6759', labelHi: '#2A2520', star: '#6F6759', chap: '#6F6759' }
}
const STATUS_LABEL = { ok: '已掌握', warn: '薄弱 · 待修', dim: '未学', cur: '当前位置' }
const STATUS_BG = {
  ink: { ok: 'rgba(87,168,126,0.14)', warn: 'rgba(224,163,62,0.14)', dim: 'rgba(74,77,85,0.3)', cur: 'rgba(216,94,69,0.14)' },
  paper: { ok: 'rgba(61,122,94,0.10)', warn: 'rgba(200,134,42,0.12)', dim: 'rgba(201,194,180,0.25)', cur: 'rgba(180,66,46,0.10)' }
}
const ANCH = {
  软件工程概述: [180, 320], 软件项目管理: [430, 110], 需求确定: [450, 480], 系统分析: [730, 300],
  系统设计: [950, 510], 对象设计: [1010, 190], 软件测试: [1220, 360], 部署与维护: [1350, 150]
}

const hoverId = ref(null)
const selectedId = ref(null)
const rootCause = ref(false)
const chapterFilter = ref('全部章节')
const query = ref('')
const mapIn = ref(false)
const layout = ref(null)

const pal = computed(() => PAL[theme.value])
const drawerW = computed(() => (width.value < 460 ? 'calc(100% - 32px)' : '360px'))

onMounted(() => {
  if (route.query.root === '1') rootCause.value = true
  if (data.value) buildLayout()
  watch(data, (d) => { if (d) buildLayout() })
})

function buildLayout() {
  layout.value = computeLayout(data.value)
  setTimeout(() => { mapIn.value = true }, 60)
}

function statusOf(id) {
  if (id === CURRENT) return 'cur'
  const m = MASTERY[id]
  return m ? m[1] : 'dim'
}
function masteryOf(id) {
  const m = MASTERY[id]
  return m ? m[0] : null
}

// 悬停前置链
function chainFor(id) {
  const L = layout.value
  const nodes = { [id]: true }
  const edges = {}
  const q = [id]
  while (q.length) {
    const cur = q.shift()
    const ins = L.inEdges[cur] || []
    for (const e of ins) {
      edges[e.source + '>' + e.target] = true
      if (!nodes[e.source]) { nodes[e.source] = true; q.push(e.source) }
    }
  }
  return { nodes, edges }
}

const filterOn = computed(() => chapterFilter.value !== '全部章节')
const chain = computed(() => (!rootCause.value && hoverId.value && layout.value ? chainFor(hoverId.value) : null))
const rootNodeSet = ROOT_NODES.reduce((a, id) => ((a[id] = true), a), {})
const rootEdgeSet = ROOT_EDGES.reduce((a, k) => ((a[k] = true), a), {})

const chapterLabels = computed(() => {
  if (!layout.value) return []
  return Object.keys(ANCH).map((ch) => {
    const a = ANCH[ch]
    let op = filterOn.value ? (ch === chapterFilter.value ? 0.5 : 0.07) : (theme.value === 'ink' ? 0.16 : 0.28)
    if (rootCause.value) op = 0.06
    return { name: ch, x: a[0], y: Math.max(42, a[1] - 95), op }
  })
})

const edgeList = computed(() => {
  if (!layout.value) return []
  const L = layout.value
  const ch = chain.value
  const out = []
  data.value.edges.forEach((e) => {
    const a = L.pos[e.source]; const b = L.pos[e.target]
    if (!a || !b) return
    const key = e.source + '>' + e.target
    const na = L.byId[e.source]; const nb = L.byId[e.target]
    let stroke = pal.value.edge
    let width2 = e.type === '包含' ? 0.8 : 1
    let op = e.type === '相关' ? 0.55 : 1
    let dash = e.type === '相关' ? '3 5' : 'none'
    let anim = 'none'
    let glow = false
    if (rootCause.value) {
      if (rootEdgeSet[key]) { glow = true; stroke = pal.value.cur; width2 = 2.2; op = 1; dash = '7 9'; anim = 'wjDash 1.1s linear infinite' }
      else op = 0.05
    } else if (ch) {
      if (ch.edges[key]) { stroke = pal.value[statusOf(e.source)]; width2 = 1.7; op = 0.85; if (e.type === '相关') dash = '3 5' }
      else op = 0.18
    }
    if (filterOn.value && !(na.chapter === chapterFilter.value && nb.chapter === chapterFilter.value)) op = Math.min(op, 0.1)
    out.push({ x1: a[0], y1: a[1], x2: b[0], y2: b[1], stroke, width: width2, op, dash, anim, glow })
  })
  return out
})

const nodeList = computed(() => {
  if (!layout.value) return []
  const L = layout.value
  const ch = chain.value
  return data.value.nodes.map((n) => {
    const p = L.pos[n.id]
    const s = statusOf(n.id)
    const c = pal.value[s]
    const r = L.radius[n.id]
    const isMain = n.id.indexOf('-') === -1
    const hi = (ch && ch.nodes[n.id]) || (rootCause.value && rootNodeSet[n.id]) || selectedId.value === n.id || hoverId.value === n.id
    let op = 1
    if (rootCause.value) op = rootNodeSet[n.id] ? 1 : 0.12
    else if (ch) op = ch.nodes[n.id] ? 1 : 0.2
    if (filterOn.value && n.chapter !== chapterFilter.value) op = Math.min(op, 0.12)
    if (selectedId.value === n.id) op = Math.max(op, 1)
    const lp = L.labelPos[n.id] || { x: p[0], y: p[1] + r + 16, a: 'middle' }
    const showLabel = isMain || hi || n.id === CURRENT || (filterOn.value && n.chapter === chapterFilter.value)
    return {
      id: n.id, x: p[0], y: p[1], r, c, status: s, op, isMain,
      isCurrent: n.id === CURRENT,
      rootGlow: rootCause.value && rootNodeSet[n.id],
      selRing: selectedId.value === n.id && n.id !== CURRENT,
      hi, showLabel, short: shortName(n), lx: lp.x, ly: lp.y, la: lp.a
    }
  })
})

// 搜索
const results = computed(() => {
  if (!query.value.trim() || !data.value) return []
  const q = query.value.trim().toLowerCase()
  return data.value.nodes
    .filter((n) => n.name.toLowerCase().indexOf(q) !== -1 || n.id.toLowerCase().indexOf(q) !== -1)
    .slice(0, 6)
    .map((n) => ({ id: n.id, name: n.name, chapter: n.chapter, color: pal.value[statusOf(n.id)] }))
})
function pickNode(id) { selectedId.value = id; query.value = '' }

const rootChips = computed(() => {
  if (!rootCause.value || !layout.value) return []
  const arr = ['KT07-2', 'KT10-1', 'KT10-2']
  return arr.map((id, i) => ({ name: shortName(layout.value.byId[id]), color: pal.value[statusOf(id)], arrow: i < arr.length - 1 ? '→' : '' }))
})

function toggleRoot() {
  rootCause.value = !rootCause.value
  selectedId.value = null
  hoverId.value = null
  chapterFilter.value = '全部章节'
}

// 抽屉
const sel = computed(() => (selectedId.value && layout.value ? layout.value.byId[selectedId.value] : null))
const selStatus = computed(() => (sel.value ? statusOf(sel.value.id) : 'dim'))
const selStatusColor = computed(() => pal.value[selStatus.value])
const selStatusBg = computed(() => STATUS_BG[theme.value][selStatus.value])
const selStatusLabel = computed(() => STATUS_LABEL[selStatus.value])
const selMastery = computed(() => (sel.value ? masteryOf(sel.value.id) : null))
const selMasteryText = computed(() => (selMastery.value === null ? '未开始' : selMastery.value + '%'))
const selMasteryPct = computed(() => (selMastery.value === null ? 0 : selMastery.value) + '%')
const selDiffDots = computed(() => (sel.value ? '●'.repeat(sel.value.difficulty) + '○'.repeat(5 - sel.value.difficulty) : ''))
const selCta = computed(() => (selStatus.value === 'dim' ? '开始学习' : '开始针对练习'))

const selPrereqs = computed(() => {
  if (!sel.value || !layout.value) return []
  const L = layout.value
  const list = []
  const parent = L.parentOf[sel.value.id]
  if (parent) list.push({ id: parent })
  data.value.edges.forEach((e) => { if (e.type === '前置' && e.target === sel.value.id) list.push({ id: e.source }) })
  return list.map((p) => {
    const pn = L.byId[p.id]
    const ps = statusOf(p.id)
    return { id: p.id, name: pn.name.length > 14 ? pn.name.slice(0, 14) + '…' : pn.name, color: pal.value[ps], statusLabel: STATUS_LABEL[ps] }
  })
})
const selResources = computed(() => {
  if (!sel.value || !layout.value) return []
  return [
    { t: '课件', n: '《软件工程》讲义 · ' + sel.value.chapter },
    { t: '视频', n: shortName(sel.value) + ' · 精讲视频' }
  ]
})
// 引用以消除告警
radiusOf
</script>

<style scoped>
.wj-search:focus {
  border-color: var(--mut) !important;
}
</style>
