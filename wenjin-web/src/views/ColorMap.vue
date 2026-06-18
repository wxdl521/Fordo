<template>
  <div :style="{ flex: 1, display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)', overflow: 'hidden', transition: 'background-color 0.35s, color 0.35s' }">

    <!-- 工具栏：搜索 + 章节筛选 + 根因回溯 -->
    <div :style="{ height: '48px', flex: 'none', display: 'flex', alignItems: 'center', gap: '10px', padding: '0 20px', borderBottom: '1px solid var(--line)', transition: 'border-color 0.35s' }">
      <span v-show="width >= 560" :style="{ fontSize: '13px', color: 'var(--mut)', whiteSpace: 'nowrap', flex: 'none' }">染色地图</span>

      <!-- 搜索 -->
      <div v-show="width >= 720" :style="{ position: 'relative', marginLeft: 'auto' }">
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
    </div>

    <!-- 画布 -->
    <div :style="{ flex: 1, position: 'relative', overflow: 'hidden' }">

      <!-- 未登录提示 -->
      <div v-if="!currentUser" :style="{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '16px', color: 'var(--mut)', zIndex: 5 }">
        <div :style="{ fontFamily: serif, fontSize: '20px', fontWeight: 600, letterSpacing: '2px', color: 'var(--ink)' }">请先登录</div>
        <div :style="{ fontSize: '13px' }">登录后即可查看你的知识图谱与学情染色</div>
        <button @click="router.push('/')" class="wj-btn-acc" :style="{ height: '40px', padding: '0 28px', background: 'var(--acc)', border: 'none', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, cursor: 'pointer' }">去登录</button>
      </div>

      <svg v-else-if="layout" width="100%" height="100%" :style="{ position: 'absolute', inset: 0, display: 'block', cursor: spaceHeld ? 'grab' : (isPanning ? 'grabbing' : 'default'), transform: mapIn ? 'scale(1)' : 'scale(0.96)', transformOrigin: '50% 50%', transition: 'transform 1.2s cubic-bezier(0.22,1,0.36,1)', userSelect: 'none' }" @wheel.prevent="onWheel" @pointerdown="onSvgPointerDown" @pointermove="onSvgPointerMove" @pointerup="onSvgPointerUp" @pointercancel="onSvgPointerUp">

        <!-- 可变换的内容层 -->
        <g :transform="`translate(${panX}, ${panY}) scale(${zoom})`">

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
            <line v-if="e.glow" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="pal.cur" stroke-width="12" opacity="0.2" stroke-linecap="round" pointer-events="none">
              <animate attributeName="opacity" values="0.1;0.32;0.1" dur="2.2s" repeatCount="indefinite" />
            </line>
            <line :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="e.stroke" :stroke-width="e.width" :opacity="e.op" :stroke-dasharray="e.dash" pointer-events="none" :style="{ transition: 'opacity 0.25s, stroke 0.25s', animation: e.anim }" />
          </template>

          <!-- 节点 -->
          <g v-for="n in nodeList" :key="n.id" :opacity="n.op" :style="{ cursor: draggingNode === n.id ? 'grabbing' : 'pointer', transition: 'opacity 0.25s' }" @mouseenter="hoverId = n.id" @mouseleave="hoverId = null" @click="!wasDragged && (selectedId = n.id)" @pointerdown.stop="onNodePointerDown($event, n.id)">
            <circle :cx="n.x" :cy="n.y" :r="Math.max(n.r * 2, 18)" fill="transparent" />
            <template v-if="n.status !== 'dim'">
              <circle :cx="n.x" :cy="n.y" :r="n.r * 1.95" :fill="n.c" opacity="0.08" />
              <circle :cx="n.x" :cy="n.y" :r="n.r * 1.35" :fill="n.c" opacity="0.18" />
            </template>
            <circle v-if="n.rootGlow" :cx="n.x" :cy="n.y" :r="n.r * 2.8" :fill="n.c" opacity="0.1">
              <animate attributeName="opacity" values="0.06;0.18;0.06" dur="2.2s" repeatCount="indefinite" />
            </circle>
            <circle :cx="n.x" :cy="n.y" :r="n.r" :fill="n.status === 'dim' ? 'none' : n.c" :stroke="n.status === 'dim' ? n.c : 'none'" :stroke-width="n.status === 'dim' ? 2 : 0" />
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
        </g>
      </svg>

      <!-- 加载态 -->
      <div v-else :style="{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)', fontSize: '13px' }">正在绘制知识图谱……</div>

      <!-- 缩放工具栏 -->
      <div v-if="layout && currentUser" :style="{ position: 'absolute', top: '14px', right: '14px', display: 'flex', alignItems: 'center', gap: '2px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '4px', zIndex: 15, transition: 'background-color 0.35s, border-color 0.35s' }">
        <button @click="zoomIn" class="wj-zoom-btn" :style="{ width: '32px', height: '32px', border: 'none', background: 'transparent', color: 'var(--ink)', fontSize: '17px', cursor: 'pointer', borderRadius: '7px', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }">+</button>
        <span :style="{ minWidth: '44px', textAlign: 'center', fontSize: '12px', color: 'var(--mut)', fontWeight: 500, userSelect: 'none' }">{{ zoomPct }}%</span>
        <button @click="zoomOut" class="wj-zoom-btn" :style="{ width: '32px', height: '32px', border: 'none', background: 'transparent', color: 'var(--ink)', fontSize: '17px', cursor: 'pointer', borderRadius: '7px', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }">-</button>
        <div :style="{ width: '1px', height: '20px', background: 'var(--line)', margin: '0 2px' }"></div>
        <button @click="fitAll" class="wj-zoom-btn" :style="{ width: '32px', height: '32px', border: 'none', background: 'transparent', color: 'var(--mut)', fontSize: '11px', cursor: 'pointer', borderRadius: '7px', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1, fontWeight: 600 }" title="适合全部">Fit</button>
      </div>

      <!-- 缩略图 -->
      <svg v-if="layout && currentUser" width="150" height="100" :style="{ position: 'absolute', right: '14px', bottom: '14px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '8px', zIndex: 15, cursor: 'pointer', transition: 'background-color 0.35s, border-color 0.35s' }" @click="onMiniMapClick">
        <!-- 缩略图内容（静态缩小） -->
        <g :transform="`scale(${150 / 1480}, ${100 / 740})`">
          <template v-if="theme === 'ink'">
            <circle v-for="(s, i) in layout.stars" :key="'mst' + i" :cx="s.x" :cy="s.y" :r="s.r" :fill="pal.star" :opacity="s.o * 0.3" />
          </template>
          <line v-for="(e, i) in edgeList" :key="'me' + i" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" :stroke="e.stroke" :stroke-width="e.width * 2" :opacity="Math.min(e.op, 0.4)" />
          <circle v-for="n in nodeList" :key="'mn' + n.id" :cx="n.x" :cy="n.y" :r="n.r * 1.5" :fill="n.c" :opacity="n.op * 0.7" />
        </g>
        <!-- 视口矩形 -->
        <rect :x="miniView.x" :y="miniView.y" :width="miniView.w" :height="miniView.h" fill="none" stroke="var(--acc)" stroke-width="2" rx="2" />
      </svg>

      <!-- 诊断结论卡 -->
      <div v-if="rootCause && rootCauseData" :style="{ position: 'absolute', left: '20px', top: '20px', width: '352px', boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '12px', padding: '18px 20px', zIndex: 20, animation: 'wjFadeUp 0.35s cubic-bezier(0.22,1,0.36,1) both', transition: 'background-color 0.35s, border-color 0.35s' }">
        <div :style="{ fontSize: '11px', letterSpacing: '2px', color: 'var(--mut)', marginBottom: '10px' }">诊断结论</div>
        <div :style="{ fontFamily: serif, fontSize: '18px', fontWeight: 600, lineHeight: 1.5, marginBottom: '10px' }">你卡在「{{ rootCauseData.currentName }}」</div>
        <div :style="{ fontSize: '13px', color: 'var(--mut)', lineHeight: 1.75, marginBottom: '14px' }">{{ rootCauseData.reason }}</div>
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
        <div :style="{ fontSize: '11.5px', color: 'var(--mut)' }">已掌握 {{ stats.ok }} · 薄弱 {{ stats.warn }} · 未学 {{ stats.dim }}</div>
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
            <span v-if="sel.bloom">认知层级 · {{ sel.bloom }}</span>
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
          <router-link :to="{ path: '/knowledge', query: { nodeCode: sel.id } }" class="wj-btn-acc" :style="{ flex: 1, height: '40px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, textDecoration: 'none' }">{{ selCta }}</router-link>
          <router-link :to="{ path: '/companion', query: { nodeCode: sel.id } }" class="wj-hover-card2" :style="{ height: '40px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 16px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '13px', textDecoration: 'none' }">问 AI 伴侣</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTheme } from '../composables/useTheme.js'
import { useViewport } from '../composables/useViewport.js'
import { useGraphData, resetGraphData } from '../composables/useGraphData.js'
import { computeLayout, shortName, radiusOf } from '../utils/graphLayout.js'

const serif = "'Noto Serif SC', serif"
const { theme } = useTheme()
const { width } = useViewport()
const route = useRoute()
const router = useRouter()

// ── 从 localStorage 读取当前登录用户 ──
function readUser() {
  try { return JSON.parse(localStorage.getItem('wj_user')) } catch { return null }
}
const currentUser = ref(readUser())
const courseId = computed(() => {
  const q = Number(route.query.courseId)
  return q > 0 ? q : 1
})

// 每次进入页面都重新加载（诊断后 mastery 会更新）
const { data } = useGraphData(courseId.value, currentUser.value?.id, true)

// 监听 storage 事件（其他标签页登录/退出时同步）
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === 'wj_user') {
      const newUser = readUser()
      if (newUser?.id !== currentUser.value?.id) {
        currentUser.value = newUser
        resetGraphData()
        // 重新加载页面数据
        window.location.reload()
      }
    }
  })
}

const CHAPTERS = ['全部章节', '软件工程概述', '需求确定', '软件项目管理', '系统分析', '系统设计', '对象设计', '软件测试', '部署与维护']

const PAL = {
  ink: { ok: '#57A87E', warn: '#E0A33E', dim: '#4A4D55', cur: '#D85E45', edge: 'rgba(232,227,216,0.10)', label: '#9A948A', labelHi: '#E8E3D8', star: '#E8E3D8', chap: '#9A948A' },
  paper: { ok: '#3D7A5E', warn: '#C8862A', dim: '#C9C2B4', cur: '#B4422E', edge: 'rgba(42,37,32,0.12)', label: '#6F6759', labelHi: '#2A2520', star: '#6F6759', chap: '#6F6759' }
}
const STATUS_LABEL = { ok: '已掌握', warn: '薄弱 · 待修', dim: '未学', cur: '当前位置' }
const STATUS_BG = {
  ink: { ok: 'rgba(87,168,126,0.14)', warn: 'rgba(224,163,62,0.14)', dim: 'rgba(74,77,85,0.3)', cur: 'rgba(216,94,69,0.14)' },
  paper: { ok: 'rgba(61,122,94,0.10)', warn: 'rgba(200,134,42,0.12)', dim: 'rgba(201,194,180,0.25)', cur: 'rgba(180,66,46,0.10)' }
}

const hoverId = ref(null)
const selectedId = ref(null)
const rootCause = ref(false)
const chapterFilter = ref('全部章节')
const query = ref('')
const mapIn = ref(false)
const layout = ref(null)

// ── 视口状态（Canva 风格交互画布）──
const zoom = ref(1)
const panX = ref(0)
const panY = ref(0)
const isPanning = ref(false)
const panStart = ref({ x: 0, y: 0 })
const panStartPan = ref({ x: 0, y: 0 })
const draggingNode = ref(null)
const dragOffset = ref({ x: 0, y: 0 })
const dragStartPos = ref({ x: 0, y: 0 })
const wasDragged = ref(false)
const spaceHeld = ref(false)
const dragTick = ref(0) // 拖拽时递增，强制 nodeList 重新计算
const svgRef = ref(null)

const GRAPH_W = 1480
const GRAPH_H = 740

const zoomPct = computed(() => Math.round(zoom.value * 100))

// 缩放工具栏
function zoomIn() { zoomTo(zoom.value * 1.25) }
function zoomOut() { zoomTo(zoom.value / 1.25) }
function fitAll() { zoom.value = 1; panX.value = 0; panY.value = 0 }

function zoomTo(newZoom, cx, cy) {
  newZoom = Math.max(0.3, Math.min(3, newZoom))
  if (cx !== undefined && cy !== undefined) {
    panX.value = cx - (cx - panX.value) * newZoom / zoom.value
    panY.value = cy - (cy - panY.value) * newZoom / zoom.value
  }
  zoom.value = newZoom
}

// 鼠标滚轮缩放
function onWheel(e) {
  const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1
  const svg = e.currentTarget
  const rect = svg.getBoundingClientRect()
  const mx = e.clientX - rect.left
  const my = e.clientY - rect.top
  const newZoom = Math.max(0.3, Math.min(3, zoom.value * factor))
  panX.value = mx - (mx - panX.value) * newZoom / zoom.value
  panY.value = my - (my - panY.value) * newZoom / zoom.value
  zoom.value = newZoom
}

// SVG pointer down — 平移开始
function onSvgPointerDown(e) {
  if (draggingNode.value) return
  // 中键或 Space+左键
  if (e.button === 1 || (e.button === 0 && spaceHeld.value)) {
    isPanning.value = true
    panStart.value = { x: e.clientX, y: e.clientY }
    panStartPan.value = { x: panX.value, y: panY.value }
    e.currentTarget.setPointerCapture(e.pointerId)
  }
}

// SVG pointer move
function onSvgPointerMove(e) {
  if (isPanning.value) {
    panX.value = panStartPan.value.x + (e.clientX - panStart.value.x)
    panY.value = panStartPan.value.y + (e.clientY - panStart.value.y)
  }
  if (draggingNode.value) {
    const svg = e.currentTarget.closest('svg')
    const rect = svg.getBoundingClientRect()
    const svgX = (e.clientX - rect.left - panX.value) / zoom.value
    const svgY = (e.clientY - rect.top - panY.value) / zoom.value
    const L = layout.value
    if (L) {
      const oldPos = L.pos[draggingNode.value]
      const dx = svgX - dragOffset.value.x
      const dy = svgY - dragOffset.value.y
      if (Math.abs(dx) > 2 || Math.abs(dy) > 2) wasDragged.value = true
      // 更新节点位置
      L.pos[draggingNode.value] = [oldPos[0] + dx, oldPos[1] + dy]
      // 更新标签位置（保持相对偏移）
      const lp = L.labelPos[draggingNode.value]
      if (lp) {
        lp.x += dx
        lp.y += dy
      }
      dragOffset.value = { x: svgX, y: svgY }
      dragTick.value++ // 强制响应式更新
    }
  }
}

// SVG pointer up
function onSvgPointerUp(e) {
  if (isPanning.value) {
    isPanning.value = false
  }
  if (draggingNode.value) {
    draggingNode.value = null
    // wasDragged 在 click handler 中消费后重置
    setTimeout(() => { wasDragged.value = false }, 0)
  }
}

// 节点拖拽开始
function onNodePointerDown(e, nodeId) {
  if (e.button !== 0 || spaceHeld.value) return
  draggingNode.value = nodeId
  wasDragged.value = false
  const svg = e.currentTarget.closest('svg')
  const rect = svg.getBoundingClientRect()
  const svgX = (e.clientX - rect.left - panX.value) / zoom.value
  const svgY = (e.clientY - rect.top - panY.value) / zoom.value
  dragOffset.value = { x: svgX, y: svgY }
}

// 缩略图点击导航
function onMiniMapClick(e) {
  const svg = e.currentTarget
  const rect = svg.getBoundingClientRect()
  const mx = e.clientX - rect.left
  const my = e.clientY - rect.top
  // 缩略图坐标 -> 图谱坐标
  const graphX = mx / (150 / GRAPH_W)
  const graphY = my / (100 / GRAPH_H)
  // 计算需要的 pan 使该点居中于视口
  const container = svg.closest('.wj-canvas-container') || svg.parentElement
  const cw = container.clientWidth
  const ch2 = container.clientHeight
  panX.value = cw / 2 - graphX * zoom.value
  panY.value = ch2 / 2 - graphY * zoom.value
}

// 缩略图视口矩形
const miniView = computed(() => {
  if (!layout.value) return { x: 0, y: 0, w: 150, h: 100 }
  // 获取容器尺寸（近似用 window）
  const cw = typeof window !== 'undefined' ? window.innerWidth : 1480
  const ch2 = typeof window !== 'undefined' ? window.innerHeight - 48 : 740
  // 视口在图谱坐标中的范围
  const left = -panX.value / zoom.value
  const top = -panY.value / zoom.value
  const vw = cw / zoom.value
  const vh = ch2 / zoom.value
  // 映射到缩略图坐标
  const sx = 150 / GRAPH_W
  const sy = 100 / GRAPH_H
  return {
    x: Math.max(0, left * sx),
    y: Math.max(0, top * sy),
    w: Math.min(150, vw * sx),
    h: Math.min(100, vh * sy)
  }
})

// 键盘快捷键
function onKeyDown(e) {
  if (e.code === 'Space' && !e.repeat) {
    spaceHeld.value = true
  }
  if (e.key === '0') { fitAll() }
  if (e.key === '+' || e.key === '=') { zoomIn() }
  if (e.key === '-') { zoomOut() }
}
function onKeyUp(e) {
  if (e.code === 'Space') {
    spaceHeld.value = false
  }
}

const pal = computed(() => PAL[theme.value])
const drawerW = computed(() => (width.value < 460 ? 'calc(100% - 32px)' : '360px'))
const legendRow = computed(() => ({ display: 'flex', alignItems: 'center', gap: '8px' }))

// ── 动态掌握度映射 ──
// 后端 mastery: mastered / weak / unlearned；masteryScore: 0–100 或 null
// 映射到设计稿三态：ok / warn / dim
function statusOf(id) {
  const node = layout.value && layout.value.byId[id]
  if (!node) return 'dim'
  if (id === currentId.value) return 'cur'
  const m = node.mastery
  if (m === 'mastered') return 'ok'
  if (m === 'weak') return 'warn'
  return 'dim'
}
function masteryOf(id) {
  const node = layout.value && layout.value.byId[id]
  return node ? node.masteryScore : null
}

// ── 当前位置节点（掌握度最低的节点）──
const currentId = computed(() => {
  if (!data.value || !data.value.nodes.length) return null
  let lowest = null
  let lowestScore = Infinity
  for (const n of data.value.nodes) {
    if (n.masteryScore != null && n.masteryScore < lowestScore) {
      lowestScore = n.masteryScore
      lowest = n.id
    }
  }
  return lowest
})

// ── 掌握度统计 ──
const stats = computed(() => {
  const s = { ok: 0, warn: 0, dim: 0 }
  if (!data.value) return s
  for (const n of data.value.nodes) {
    const st = statusOf(n.id)
    if (st === 'ok' || st === 'cur') s.ok++
    else if (st === 'warn') s.warn++
    else s.dim++
  }
  return s
})

// ── 薄弱根因回溯 ──
// 根因节点：weak 且没有 weak 前置节点的节点（链的起点）
const ROOT_NODES = computed(() => {
  if (!data.value || !layout.value) return []
  const weakNodes = new Set()
  data.value.nodes.forEach((n) => {
    if (n.mastery === 'weak' || n.masteryScore != null && n.masteryScore < 70) weakNodes.add(n.id)
  })
  // 找 weak 链的根：weak 且其所有 weak 前置都不是 weak
  const roots = []
  const L = layout.value
  for (const id of weakNodes) {
    const ins = L.inEdges[id] || []
    const hasWeakParent = ins.some((e) => weakNodes.has(e.source))
    if (!hasWeakParent) roots.push(id)
  }
  return roots.length ? roots : Array.from(weakNodes).slice(0, 3)
})

const ROOT_EDGES = computed(() => {
  if (!data.value || !layout.value) return []
  const nodeSet = new Set(ROOT_NODES.value)
  const edges = new Set()
  // BFS 从根节点出发，沿 inEdges 找到所有到最低分节点的路径
  const L = layout.value
  const target = currentId.value
  if (!target) return []
  // 从 target 反向 BFS
  const visited = new Set()
  const queue = [target]
  while (queue.length) {
    const cur = queue.shift()
    if (visited.has(cur)) continue
    visited.add(cur)
    const ins = L.inEdges[cur] || []
    for (const e of ins) {
      edges.add(e.source + '>' + e.target)
      if (!visited.has(e.source)) queue.push(e.source)
    }
  }
  return Array.from(edges)
})

const rootNodeSet = computed(() => {
  const s = {}
  ROOT_NODES.value.forEach((id) => { s[id] = true })
  return s
})
const rootEdgeSet = computed(() => {
  const s = {}
  ROOT_EDGES.value.forEach((k) => { s[k] = true })
  return s
})

// ── 根因诊断数据 ──
const rootCauseData = computed(() => {
  if (!rootCause.value || !currentId.value || !layout.value) return null
  const L = layout.value
  const curNode = L.byId[currentId.value]
  if (!curNode) return null
  // 找根因链：从当前节点沿 inEdges 回溯，找第一个 weak 祖先
  const visited = new Set()
  const queue = [currentId.value]
  let rootAncestor = null
  while (queue.length) {
    const cur = queue.shift()
    if (visited.has(cur)) continue
    visited.add(cur)
    const ins = L.inEdges[cur] || []
    for (const e of ins) {
      const srcNode = L.byId[e.source]
      if (srcNode && (srcNode.mastery === 'weak' || (srcNode.masteryScore != null && srcNode.masteryScore < 70))) {
        if (!rootAncestor || (srcNode.masteryScore != null && (rootAncestor.masteryScore == null || srcNode.masteryScore < rootAncestor.masteryScore))) {
          rootAncestor = srcNode
        }
      }
      if (!visited.has(e.source)) queue.push(e.source)
    }
  }
  const ancestorName = rootAncestor ? shortName(rootAncestor) : '前置知识点'
  return {
    currentName: shortName(curNode),
    reason: `根本原因更可能在前置点「${ancestorName}」——相关基础薄弱，导致后续知识点难以理解。建议先巩固前置知识。`
  }
})

// ── 根因链 chips ──
const rootChips = computed(() => {
  if (!rootCause.value || !layout.value || !currentId.value) return []
  const L = layout.value
  // 构建从根因到当前节点的链
  const chain = []
  const visited = new Set()
  const queue = [[currentId.value]]
  while (queue.length) {
    const path = queue.shift()
    const last = path[path.length - 1]
    if (visited.has(last)) continue
    visited.add(last)
    if (rootNodeSet.value[last] || ROOT_NODES.value.includes(last)) {
      chain.push(...path.reverse())
      break
    }
    const ins = L.inEdges[last] || []
    for (const e of ins) {
      if (!visited.has(e.source)) queue.push([...path, e.source])
    }
  }
  if (!chain.length) {
    // 回退：用 ROOT_NODES + currentId
    chain.push(...ROOT_NODES.value.slice(0, 2), currentId.value)
  }
  const unique = [...new Set(chain)].slice(0, 4)
  return unique.map((id, i) => ({
    name: shortName(L.byId[id] || { id, name: id }),
    color: pal.value[statusOf(id)],
    arrow: i < unique.length - 1 ? '→' : ''
  }))
})

// ── 悬停前置链 ──
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

// ── 布局 ──
onMounted(() => {
  if (route.query.root === '1') rootCause.value = true
  if (data.value) buildLayout()
  watch(data, (d) => { if (d) buildLayout() })
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
})
onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
})

function buildLayout() {
  layout.value = computeLayout(data.value)
  setTimeout(() => { mapIn.value = true }, 60)
}

// ── 章节标签 ──
const chapterLabels = computed(() => {
  if (!layout.value) return []
  const ANCH = {
    软件工程概述: [180, 320], 软件项目管理: [430, 110], 需求确定: [450, 480], 系统分析: [730, 300],
    系统设计: [950, 510], 对象设计: [1010, 190], 软件测试: [1220, 360], 部署与维护: [1350, 150]
  }
  return Object.keys(ANCH).map((ch) => {
    const a = ANCH[ch]
    let op = filterOn.value ? (ch === chapterFilter.value ? 0.5 : 0.07) : (theme.value === 'ink' ? 0.16 : 0.28)
    if (rootCause.value) op = 0.06
    return { name: ch, x: a[0], y: Math.max(42, a[1] - 95), op }
  })
})

// ── 边列表 ──
const edgeList = computed(() => {
  if (!layout.value) return []
  void dragTick.value // 拖拽时强制重算
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
      if (rootEdgeSet.value[key]) { glow = true; stroke = pal.value.cur; width2 = 2.2; op = 1; dash = '7 9'; anim = 'wjDash 1.1s linear infinite' }
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

// ── 节点列表 ──
const nodeList = computed(() => {
  if (!layout.value) return []
  void dragTick.value // 拖拽时强制重算
  const L = layout.value
  const ch = chain.value
  return data.value.nodes.map((n) => {
    const p = L.pos[n.id]
    const s = statusOf(n.id)
    const c = pal.value[s]
    const r = L.radius[n.id]
    const isMain = n.id.indexOf('-') === -1
    const hi = (ch && ch.nodes[n.id]) || (rootCause.value && rootNodeSet.value[n.id]) || selectedId.value === n.id || hoverId.value === n.id
    let op = 1
    if (rootCause.value) op = rootNodeSet.value[n.id] ? 1 : 0.12
    else if (ch) op = ch.nodes[n.id] ? 1 : 0.2
    if (filterOn.value && n.chapter !== chapterFilter.value) op = Math.min(op, 0.12)
    if (selectedId.value === n.id) op = Math.max(op, 1)
    const lp = L.labelPos[n.id] || { x: p[0], y: p[1] + r + 16, a: 'middle' }
    const showLabel = isMain || hi || n.id === currentId.value || (filterOn.value && n.chapter === chapterFilter.value)
    return {
      id: n.id, x: p[0], y: p[1], r, c, status: s, op, isMain,
      isCurrent: n.id === currentId.value,
      rootGlow: rootCause.value && rootNodeSet.value[n.id],
      selRing: selectedId.value === n.id && n.id !== currentId.value,
      hi, showLabel, short: shortName(n), lx: lp.x, ly: lp.y, la: lp.a
    }
  })
})

// ── 搜索 ──
const results = computed(() => {
  if (!query.value.trim() || !data.value) return []
  const q = query.value.trim().toLowerCase()
  return data.value.nodes
    .filter((n) => n.name.toLowerCase().indexOf(q) !== -1 || n.id.toLowerCase().indexOf(q) !== -1)
    .slice(0, 6)
    .map((n) => ({ id: n.id, name: n.name, chapter: n.chapter, color: pal.value[statusOf(n.id)] }))
})
function pickNode(id) { selectedId.value = id; query.value = '' }

function toggleRoot() {
  rootCause.value = !rootCause.value
  selectedId.value = null
  hoverId.value = null
  chapterFilter.value = '全部章节'
}

// ── 抽屉 ──
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

// 仅开发期暴露给端到端（Playwright）测试用
if (import.meta.env.DEV) {
  window.__wjColorMap = {
    data,
    layout,
    currentId,
    rootCause,
    toggleRoot,
    statusOf,
    masteryOf,
    stats,
    ROOT_NODES
  }
}

// 引用以消除告警
radiusOf
</script>

<style scoped>
.wj-search:focus {
  border-color: var(--mut) !important;
}
.wj-zoom-btn:hover {
  background: var(--line) !important;
}
</style>
