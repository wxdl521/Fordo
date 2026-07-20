<template>
  <div :style="{ flex: 1, display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)', overflow: 'hidden', transition: 'background-color 0.35s, color 0.35s' }">

    <!-- 工具栏：搜索 + 章节筛选 + 根因回溯 -->
    <div :style="{ height: '48px', flex: 'none', display: 'flex', alignItems: 'center', gap: '10px', padding: '0 20px', borderBottom: '1px solid var(--line)', transition: 'border-color 0.35s' }">
      <span v-show="width >= 560" :style="{ fontSize: '13px', color: 'var(--mut)', whiteSpace: 'nowrap', flex: 'none' }">染色地图</span>

      <!-- 搜索 -->
      <div v-show="width >= 720" :style="{ position: 'relative', marginLeft: 'auto' }">
        <input v-model="query" aria-label="搜索知识点" placeholder="搜索知识点…" class="wj-search" :style="{ width: '190px', height: '34px', boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '8px', padding: '0 12px', color: 'var(--ink)', fontSize: '13px', outline: 'none', transition: 'background-color 0.35s, border-color 0.35s' }" />
        <div v-if="results.length" :style="{ position: 'absolute', top: '40px', left: 0, width: '280px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '6px', zIndex: 40, boxShadow: '0 8px 24px rgba(0,0,0,0.18)' }">
          <div v-for="r in results" :key="r.id" @click="pickNode(r.id)" class="wj-hover-card2" :style="{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 10px', borderRadius: '6px', cursor: 'pointer' }">
            <span :style="{ width: '7px', height: '7px', borderRadius: '50%', background: r.color, flex: 'none' }"></span>
            <span :style="{ fontSize: '13px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }">{{ r.name }}</span>
            <span :style="{ fontSize: '11px', color: 'var(--mut)', marginLeft: 'auto', flex: 'none' }">{{ r.chapter }}</span>
          </div>
        </div>
      </div>

      <!-- 章节筛选 -->
      <select v-show="width >= 900" v-model="chapterFilter" aria-label="筛选章节" :style="{ height: '34px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '8px', padding: '0 8px', color: 'var(--ink)', fontSize: '12.5px', outline: 'none', cursor: 'pointer', transition: 'background-color 0.35s, border-color 0.35s' }">
        <option v-for="c in CHAPTERS" :key="c" :value="c">{{ c }}</option>
      </select>

      <!-- 今日复习 -->
      <button
        v-if="currentUser"
        @click="toggleReview"
        class="wj-hover-acc"
        :style="reviewOpen
          ? { height: '34px', padding: '0 14px', background: 'var(--accSoft)', border: '1px solid var(--acc)', borderRadius: '8px', color: 'var(--acc)', fontSize: '12.5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '7px', fontWeight: 500, whiteSpace: 'nowrap', flex: 'none' }
          : { height: '34px', padding: '0 14px', background: reviewCount > 0 ? 'var(--accSoft)' : 'transparent', border: reviewCount > 0 ? '1px solid var(--acc)' : '1px solid var(--line)', borderRadius: '8px', color: reviewCount > 0 ? 'var(--acc)' : 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '7px', whiteSpace: 'nowrap', flex: 'none' }"
      >
        <span :style="{ width: '6px', height: '6px', borderRadius: '50%', background: reviewCount > 0 ? 'var(--acc)' : 'var(--mut)', opacity: reviewCount > 0 ? 1 : 0.5 }"></span>{{ width < 900 ? `复习 ${reviewCount}` : `今日复习 ${reviewCount} 项` }}
      </button>

      <!-- 薄弱根因回溯 -->
      <button @click="toggleRoot" class="wj-hover-acc" :style="rootCause
        ? { height: '34px', padding: '0 14px', background: 'var(--accSoft)', border: '1px solid var(--acc)', borderRadius: '8px', color: 'var(--acc)', fontSize: '12.5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '7px', fontWeight: 500, whiteSpace: 'nowrap', flex: 'none' }
        : { height: '34px', padding: '0 14px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '8px', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '7px', whiteSpace: 'nowrap', flex: 'none' }">
        <span :style="{ width: '6px', height: '6px', borderRadius: '50%', background: rootCause ? 'var(--acc)' : 'var(--mut)', opacity: rootCause ? 1 : 0.5 }"></span>{{ width < 1080 ? '回溯' : '薄弱根因回溯' }}
      </button>
    </div>

    <!-- 画布 -->
    <div ref="canvasRef" class="wj-canvas-container" :style="{ flex: 1, position: 'relative', overflow: 'hidden', touchAction: 'none' }">

      <!-- 每日简报（学生已登录且有课程） -->
      <DailyBriefCard
        v-if="currentUser && courseId"
        :course-id="courseId"
        :enabled="!!currentUser && !!courseId"
      />

      <!-- 未登录提示 -->
      <div v-if="!currentUser" :style="{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '16px', color: 'var(--mut)', zIndex: 5 }">
        <div :style="{ fontFamily: serif, fontSize: '20px', fontWeight: 600, letterSpacing: '2px', color: 'var(--ink)' }">请先登录</div>
        <div :style="{ fontSize: '13px' }">登录后即可查看你的知识图谱与学情染色</div>
        <button @click="router.push('/')" class="wj-btn-acc" :style="{ height: '40px', padding: '0 28px', background: 'var(--acc)', border: 'none', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, cursor: 'pointer' }">去登录</button>
      </div>

      <!-- 无课程：空态（禁止回落默认课程 1） -->
      <div v-else-if="!courseId" :style="{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '16px', color: 'var(--mut)', zIndex: 5 }">
        <div :style="{ fontFamily: serif, fontSize: '20px', fontWeight: 600, letterSpacing: '2px', color: 'var(--ink)' }">尚未选择课程</div>
        <div :style="{ fontSize: '13px' }">请先在首页选择一门课程，再查看染色地图</div>
        <button @click="router.push('/')" class="wj-btn-acc" :style="{ height: '40px', padding: '0 28px', background: 'var(--acc)', border: 'none', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, cursor: 'pointer' }">去选课</button>
      </div>

      <svg v-else-if="layout" width="100%" height="100%" :style="{ position: 'absolute', inset: 0, display: 'block', cursor: isPanning ? 'grabbing' : 'grab', transform: mapIn ? 'scale(1)' : 'scale(0.96)', transformOrigin: '50% 50%', transition: 'transform 1.2s cubic-bezier(0.22,1,0.36,1)', userSelect: 'none' }" @wheel.prevent="onWheel" @pointerdown="onSvgPointerDown" @pointermove="onSvgPointerMove" @pointerup="onSvgPointerUp" @pointercancel="onSvgPointerUp">

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
          <g
            v-for="n in nodeList"
            :key="n.id"
            :opacity="n.op"
            role="button"
            :tabindex="0"
            :aria-label="n.short || n.id"
            :aria-pressed="selectedId === n.id ? 'true' : 'false'"
            :style="{ cursor: draggingNode === n.id ? 'grabbing' : 'pointer', transition: 'opacity 0.25s', outline: 'none' }"
            @mouseenter="hoverId = n.id"
            @mouseleave="hoverId = null"
            @click="!wasDragged && (selectedId = n.id)"
            @keydown="onNodeKeydown($event, n.id)"
            @pointerdown.stop="onNodePointerDown($event, n.id)"
          >
            <circle :cx="n.x" :cy="n.y" :r="Math.max(n.r * 2, 18)" fill="transparent" />
            <template v-if="n.status !== 'dim'">
              <circle :cx="n.x" :cy="n.y" :r="n.r * 1.95" :fill="n.c" :opacity="n.glowOuter" />
              <circle :cx="n.x" :cy="n.y" :r="n.r * 1.35" :fill="n.c" :opacity="n.glowInner" />
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

      <!-- 悬停详情（褪色态双分值） -->
      <div
        v-if="hoverTip"
        :style="{ position: 'absolute', left: hoverTip.left + 'px', top: hoverTip.top + 'px', transform: 'translate(-50%, -100%)', marginTop: '-10px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '10px 14px', zIndex: 25, pointerEvents: 'none', boxShadow: '0 8px 24px rgba(0,0,0,0.16)', maxWidth: '240px', transition: 'background-color 0.35s, border-color 0.35s' }"
      >
        <div :style="{ fontSize: '13px', fontWeight: 500, marginBottom: hoverTip.decaying ? '6px' : 0 }">{{ hoverTip.name }}</div>
        <template v-if="hoverTip.decaying">
          <div :style="{ fontSize: '12.5px', color: 'var(--ok)', fontWeight: 500 }">{{ hoverTip.scoreText }}</div>
          <div :style="{ fontSize: '11.5px', color: 'var(--mut)', marginTop: '4px' }">{{ hoverTip.daysText }}</div>
        </template>
      </div>

      <!-- 加载态 -->
      <div v-if="currentUser && courseId && !layout" :style="{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--mut)', fontSize: '13px' }">正在绘制知识图谱……</div>

      <!-- 今日复习侧边栏 -->
      <div v-if="currentUser && reviewOpen" class="wj-review-drawer">
        <ReviewPanel
          :items="reviewItems"
          :total-decaying-count="reviewTotalCount"
          :course-id="courseId"
          :loading="reviewLoading"
          :error="reviewError"
          @refresh="loadReview"
          @close="reviewOpen = false"
        />
      </div>

      <!-- 缩放工具栏 -->
      <div v-if="layout && currentUser" :style="{ position: 'absolute', top: '14px', right: '14px', display: 'flex', alignItems: 'center', gap: '2px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '4px', zIndex: 15, transition: 'background-color 0.35s, border-color 0.35s' }">
        <button @click="zoomIn" class="wj-zoom-btn" :style="{ width: '32px', height: '32px', border: 'none', background: 'transparent', color: 'var(--ink)', fontSize: '17px', cursor: 'pointer', borderRadius: '7px', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }">+</button>
        <span :style="{ minWidth: '44px', textAlign: 'center', fontSize: '12px', color: 'var(--mut)', fontWeight: 500, userSelect: 'none' }">{{ zoomPct }}%</span>
        <button @click="zoomOut" class="wj-zoom-btn" :style="{ width: '32px', height: '32px', border: 'none', background: 'transparent', color: 'var(--ink)', fontSize: '17px', cursor: 'pointer', borderRadius: '7px', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }">-</button>
        <div :style="{ width: '1px', height: '20px', background: 'var(--line)', margin: '0 2px' }"></div>
        <button @click="fitAll" class="wj-zoom-btn" :style="{ width: '32px', height: '32px', border: 'none', background: 'transparent', color: 'var(--mut)', fontSize: '11px', cursor: 'pointer', borderRadius: '7px', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1, fontWeight: 600 }" title="适应窗口">⊡</button>
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
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--ok)', opacity: 0.42 }"></span><span>正在遗忘</span></div>
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--warn)' }"></span><span>薄弱 · 待修</span></div>
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--dim)' }"></span><span>未学</span></div>
          <div :style="legendRow"><span :style="{ width: '9px', height: '9px', borderRadius: '3px', border: '1.5px solid var(--acc)', boxSizing: 'border-box' }"></span><span>当前位置 / 根因</span></div>
        </div>
        <div :style="{ height: '1px', background: 'var(--line)', margin: '11px 0 9px' }"></div>
        <div :style="{ fontSize: '11.5px', color: 'var(--mut)' }">已掌握 {{ stats.ok }}<template v-if="stats.decaying"> · 正在遗忘 {{ stats.decaying }}</template> · 薄弱 {{ stats.warn }} · 未学 {{ stats.dim }}</div>
        <div v-show="width >= 640 && layout && currentUser" :style="{ marginTop: '7px', paddingTop: '7px', borderTop: '1px solid var(--line)', fontSize: '11.5px', color: 'var(--mut)', lineHeight: 1.55, maxWidth: '240px' }">滚轮缩放 · 拖动画布平移 · 右下角缩略图导航</div>
      </div>

      <div v-show="width >= 640 && layout && currentUser && rootCause" :style="{ position: 'absolute', left: '20px', bottom: '20px', fontSize: '12px', color: 'var(--mut)', opacity: 0.75, zIndex: 10, maxWidth: '420px', lineHeight: 1.6 }">滚轮缩放 · 拖动画布平移 · 右下角缩略图导航</div>
      <div v-show="width >= 640" :style="{ position: 'absolute', right: '20px', bottom: width >= 900 && layout && currentUser ? '128px' : '20px', fontSize: '12px', color: 'var(--mut)', opacity: 0.75, zIndex: 10 }">悬停节点查看前置链 · 点击查看详情</div>

      <!-- 抽屉 -->
      <div
        v-if="sel"
        role="dialog"
        aria-modal="false"
        :aria-label="sel.name"
        :style="{ position: 'absolute', right: '16px', top: '16px', bottom: '16px', width: drawerW, boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', display: 'flex', flexDirection: 'column', zIndex: 30, boxShadow: '0 12px 36px rgba(0,0,0,0.16)', animation: 'wjDrawerIn 0.34s cubic-bezier(0.22,1,0.36,1) both', transition: 'background-color 0.35s, border-color 0.35s' }"
      >
        <div :style="{ flex: 1, overflowY: 'auto', padding: '20px 22px 10px' }">
          <div :style="{ display: 'flex', alignItems: 'flex-start', gap: '10px', marginBottom: '12px' }">
            <span :style="{ fontSize: '11.5px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '3px 10px', flex: 'none' }">{{ sel.chapter }}</span>
            <button type="button" aria-label="关闭详情" @click="selectedId = null" class="wj-hover-card2" :style="{ marginLeft: 'auto', width: '28px', height: '28px', border: 'none', background: 'transparent', color: 'var(--mut)', fontSize: '17px', cursor: 'pointer', borderRadius: '6px', lineHeight: 1 }">×</button>
          </div>
          <div :style="{ fontSize: '17px', fontWeight: 600, lineHeight: 1.45, marginBottom: '14px' }">{{ sel.name }}</div>

          <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '8px' }">
            <span :style="{ fontSize: '12px', fontWeight: 500, color: selStatusColor, background: selStatusBg, borderRadius: '999px', padding: '4px 11px' }">{{ selStatusLabel }}</span>
            <span :style="{ fontSize: '20px', fontWeight: 600, color: selStatusColor, marginLeft: 'auto' }">{{ selMasteryText }}</span>
          </div>
          <div v-if="selDecayingDetail" :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.6, marginBottom: '10px' }">{{ selDecayingDetail }}</div>
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
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { useTheme } from '../composables/useTheme.js'
import { useViewport } from '../composables/useViewport.js'
import { useGraphData } from '../composables/useGraphData.js'
import { useStudentCourse } from '../composables/useStudentCourse.js'
import { useSessionStore } from '../store/session.js'
import { computeLayout, shortName, radiusOf } from '../utils/graphLayout.js'
import { fetchTodayReview } from '../api/review.js'
import ReviewPanel from '../components/ReviewPanel.vue'
import DailyBriefCard from '../components/DailyBriefCard.vue'
import { createLatestRequest } from '../utils/latestRequest.js'

const serif = "'Noto Serif SC', serif"
const { theme } = useTheme()
const { width } = useViewport()
const route = useRoute()
const router = useRouter()

const session = useSessionStore()
const { user: currentUser } = storeToRefs(session)
// 无课程 → null（禁止 || 1）
const { courseId } = useStudentCourse()

// 实例级图谱加载：courseId 变化时 latestRequest 丢弃迟到响应
const { data, reload: reloadGraph } = useGraphData(courseId)


const CHAPTERS = ['全部章节', '软件工程概述', '需求确定', '软件项目管理', '系统分析', '系统设计', '对象设计', '软件测试', '部署与维护']

const PAL = {
  ink: { ok: '#57A87E', warn: '#E0A33E', dim: '#4A4D55', cur: '#D85E45', edge: 'rgba(232,227,216,0.10)', label: '#9A948A', labelHi: '#E8E3D8', star: '#E8E3D8', chap: '#9A948A' },
  paper: { ok: '#3D7A5E', warn: '#C8862A', dim: '#C9C2B4', cur: '#B4422E', edge: 'rgba(42,37,32,0.12)', label: '#6F6759', labelHi: '#2A2520', star: '#6F6759', chap: '#6F6759' }
}
const STATUS_LABEL = { ok: '已掌握', warn: '薄弱 · 待修', dim: '未学', cur: '当前位置', decaying: '正在遗忘' }
const STATUS_BG = {
  ink: { ok: 'rgba(87,168,126,0.14)', warn: 'rgba(224,163,62,0.14)', dim: 'rgba(74,77,85,0.3)', cur: 'rgba(216,94,69,0.14)' },
  paper: { ok: 'rgba(61,122,94,0.10)', warn: 'rgba(200,134,42,0.12)', dim: 'rgba(201,194,180,0.25)', cur: 'rgba(180,66,46,0.10)' }
}

const hoverId = ref(null)
const selectedId = ref(null)
const rootCause = ref(false)
const reviewOpen = ref(false)
const reviewItems = ref([])
const reviewTotalCount = ref(0)
const reviewLoading = ref(false)
const reviewError = ref('')
const reviewRequest = createLatestRequest()
let disposed = false

function isAbortError(err) {
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED'
}

function isCurrentReviewContext(request, userId, id) {
  return !disposed
    && request.isCurrent()
    && Number(courseId.value) === id
    && currentUser.value?.id === userId
}

const reviewCount = computed(() => {
  if (reviewTotalCount.value > 0) return reviewTotalCount.value
  return reviewItems.value.length
})

async function loadReview() {
  if (disposed) return
  const userId = currentUser.value?.id
  const id = Number(courseId.value)
  if (!userId || !(id > 0)) {
    reviewRequest.cancel()
    reviewItems.value = []
    reviewTotalCount.value = 0
    reviewLoading.value = false
    reviewError.value = ''
    return
  }
  const request = reviewRequest.next()
  reviewLoading.value = true
  reviewError.value = ''
  reviewItems.value = []
  reviewTotalCount.value = 0
  try {
    const vo = await fetchTodayReview(id, { signal: request.signal })
    if (!isCurrentReviewContext(request, userId, id)) return
    reviewItems.value = vo?.items || []
    reviewTotalCount.value = vo?.totalDecayingCount ?? reviewItems.value.length
  } catch (e) {
    if (!isCurrentReviewContext(request, userId, id) || isAbortError(e)) return
    reviewError.value = e.message || '加载复习队列失败'
    reviewItems.value = []
    reviewTotalCount.value = 0
  } finally {
    if (isCurrentReviewContext(request, userId, id)) reviewLoading.value = false
  }
}

function toggleReview() {
  reviewOpen.value = !reviewOpen.value
  if (reviewOpen.value) {
    selectedId.value = null
    hoverId.value = null
    if (!reviewLoading.value) loadReview()
  }
}
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
const wasDragged = ref(false)
const spaceHeld = ref(false)
const dragTick = ref(0) // 拖拽时递增，强制 nodeList 重新计算
const canvasRef = ref(null)

const GRAPH_W = 1480
const GRAPH_H = 740
const ZOOM_MIN = 0.15
const ZOOM_MAX = 4

const zoomPct = computed(() => Math.round(zoom.value * 100))

function getContainerSize() {
  const el = canvasRef.value
  if (el) return { w: el.clientWidth, h: el.clientHeight }
  return {
    w: typeof window !== 'undefined' ? window.innerWidth : GRAPH_W,
    h: typeof window !== 'undefined' ? Math.max(320, window.innerHeight - 108) : GRAPH_H
  }
}

function clampZoom(z) {
  return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z))
}

// 缩放工具栏（以视口中心为锚点）
function zoomIn() {
  const { w, h } = getContainerSize()
  zoomTo(zoom.value * 1.25, w / 2, h / 2)
}
function zoomOut() {
  const { w, h } = getContainerSize()
  zoomTo(zoom.value / 1.25, w / 2, h / 2)
}

function fitAll() {
  if (!layout.value || !data.value?.nodes?.length) {
    zoom.value = 1
    panX.value = 0
    panY.value = 0
    return
  }
  const L = layout.value
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const n of data.value.nodes) {
    const p = L.pos[n.id]
    if (!p) continue
    const pad = radiusOf(n) + 28
    minX = Math.min(minX, p[0] - pad)
    minY = Math.min(minY, p[1] - pad)
    maxX = Math.max(maxX, p[0] + pad)
    maxY = Math.max(maxY, p[1] + pad)
  }
  if (!Number.isFinite(minX)) {
    zoom.value = 1
    panX.value = 0
    panY.value = 0
    return
  }
  const graphW = Math.max(1, maxX - minX)
  const graphH = Math.max(1, maxY - minY)
  const { w: cw, h: ch } = getContainerSize()
  const margin = 56
  const newZoom = clampZoom(Math.min((cw - margin * 2) / graphW, (ch - margin * 2) / graphH))
  const cx = (minX + maxX) / 2
  const cy = (minY + maxY) / 2
  zoom.value = newZoom
  panX.value = cw / 2 - cx * newZoom
  panY.value = ch / 2 - cy * newZoom
}

function zoomTo(newZoom, cx, cy) {
  newZoom = clampZoom(newZoom)
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
  const newZoom = clampZoom(zoom.value * factor)
  panX.value = mx - (mx - panX.value) * newZoom / zoom.value
  panY.value = my - (my - panY.value) * newZoom / zoom.value
  zoom.value = newZoom
}

// SVG pointer down — 空白处拖动画布平移（节点 pointerdown 已 stop，不会误触）
function onSvgPointerDown(e) {
  if (draggingNode.value) return
  if (e.button === 0 || e.button === 1) {
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
function onSvgPointerUp() {
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
  const { w: cw, h: ch2 } = getContainerSize()
  panX.value = cw / 2 - graphX * zoom.value
  panY.value = ch2 / 2 - graphY * zoom.value
}

// 缩略图视口矩形
const miniView = computed(() => {
  if (!layout.value) return { x: 0, y: 0, w: 150, h: 100 }
  const { w: cw, h: ch2 } = getContainerSize()
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
  // Space on focused map node selects it (handled by onNodeKeydown); only hold-to-pan when not on a node
  const onNode = e.target?.getAttribute?.('role') === 'button' && e.target?.closest?.('svg')
  if (e.code === 'Space' && !e.repeat && !onNode) {
    spaceHeld.value = true
    e.preventDefault()
  }
  if (e.key === '0') { fitAll() }
  if (e.key === '+' || e.key === '=') { zoomIn() }
  if (e.key === '-') { zoomOut() }
  if (e.key === 'Escape' && selectedId.value) {
    selectedId.value = null
  }
}
function onKeyUp(e) {
  if (e.code === 'Space') {
    spaceHeld.value = false
  }
}

/** Keyboard activate map node (Enter / Space) */
function onNodeKeydown(e, id) {
  if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
    e.preventDefault()
    e.stopPropagation()
    selectedId.value = id
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

/** 曾掌握（raw≥75）但有效分已跌破掌握阈值 —— 褪色态判据（由后端 decaying 字段驱动） */
function isDecayingOf(id) {
  return layout.value?.byId[id]?.decaying === true
}

function fmtDays(d) {
  if (d == null) return '—'
  if (d < 1) return '不足 1 天前'
  if (d < 2) return '约 1 天前'
  return `约 ${Math.round(d)} 天前`
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
  const s = { ok: 0, warn: 0, dim: 0, decaying: 0 }
  if (!data.value) return s
  for (const n of data.value.nodes) {
    if (isDecayingOf(n.id)) {
      s.decaying++
      continue
    }
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

const hoverTip = computed(() => {
  if (!hoverId.value || !layout.value || rootCause.value || !canvasRef.value) return null
  const node = layout.value.byId[hoverId.value]
  const p = layout.value.pos[hoverId.value]
  if (!node || !p) return null
  const decaying = isDecayingOf(hoverId.value)
  if (!decaying) return null
  const raw = Math.round(node.rawScore ?? node.masteryScore ?? 0)
  const eff = node.effectiveScore != null ? Math.round(node.effectiveScore) : null
  const r = layout.value.radius[hoverId.value] || 8
  const left = panX.value + p[0] * zoom.value
  const top = panY.value + (p[1] - r - 18) * zoom.value
  return {
    name: shortName(node),
    decaying: true,
    scoreText: eff != null ? `${raw} → 当前约 ${eff}` : `${raw}`,
    daysText: `上次证据 ${fmtDays(node.daysSinceEvidence)}`,
    left,
    top
  }
})

// ── 课程/用户边界变化：作废旧复习请求；切课时同时清空布局 ──
watch([courseId, () => currentUser.value?.id], ([nextCourseId], [previousCourseId]) => {
  if (nextCourseId !== previousCourseId) {
    layout.value = null
    mapIn.value = false
    selectedId.value = null
    hoverId.value = null
  }
  loadReview()
})

// ── 布局 ──
onMounted(() => {
  if (route.query.root === '1') rootCause.value = true
  if (currentUser.value) loadReview()
  if (data.value) buildLayout()
  watch(data, (d) => {
    if (d) buildLayout()
    else layout.value = null
  })
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
})
onUnmounted(() => {
  disposed = true
  reviewRequest.cancel()
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
  if (window.__wjColorMap?.loadReview === loadReview) delete window.__wjColorMap
})

function buildLayout() {
  if (!data.value) {
    layout.value = null
    return
  }
  layout.value = computeLayout(data.value)
  setTimeout(() => {
    mapIn.value = true
    requestAnimationFrame(() => fitAll())
  }, 60)
}

// 暴露 reload 供诊断返回后强制刷新（可选）
void reloadGraph

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
    const decaying = isDecayingOf(n.id)
    const c = decaying ? pal.value.ok : pal.value[s]
    const glowOuter = decaying ? 0.04 : 0.08
    const glowInner = decaying ? 0.09 : 0.18
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
      id: n.id, x: p[0], y: p[1], r, c, status: s, op, isMain, decaying,
      glowOuter, glowInner,
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
const selDecaying = computed(() => (sel.value ? isDecayingOf(sel.value.id) : false))
const selStatus = computed(() => {
  if (!sel.value) return 'dim'
  if (selDecaying.value) return 'decaying'
  return statusOf(sel.value.id)
})
const selStatusColor = computed(() => (selDecaying.value ? pal.value.ok : pal.value[selStatus.value === 'decaying' ? 'ok' : selStatus.value]))
const selStatusBg = computed(() => {
  if (selDecaying.value) return STATUS_BG[theme.value].ok
  const st = selStatus.value === 'decaying' ? 'ok' : selStatus.value
  return STATUS_BG[theme.value][st]
})
const selStatusLabel = computed(() => STATUS_LABEL[selStatus.value] || STATUS_LABEL.dim)
const selMastery = computed(() => {
  if (!sel.value) return null
  if (selDecaying.value && sel.value.effectiveScore != null) return Math.round(sel.value.effectiveScore)
  return masteryOf(sel.value.id)
})
const selMasteryText = computed(() => {
  if (!sel.value) return '未开始'
  if (selDecaying.value) {
    const raw = Math.round(sel.value.rawScore ?? sel.value.masteryScore ?? 0)
    const eff = sel.value.effectiveScore != null ? Math.round(sel.value.effectiveScore) : '—'
    return `${raw} → ${eff}`
  }
  return selMastery.value === null ? '未开始' : selMastery.value + '%'
})
const selMasteryPct = computed(() => (selMastery.value === null ? 0 : selMastery.value) + '%')
const selDecayingDetail = computed(() => {
  if (!selDecaying.value || !sel.value) return ''
  const raw = Math.round(sel.value.rawScore ?? sel.value.masteryScore ?? 0)
  const eff = sel.value.effectiveScore != null ? Math.round(sel.value.effectiveScore) : '—'
  return `学过 ${raw} 分，当前约 ${eff} 分，上次证据 ${fmtDays(sel.value.daysSinceEvidence)}`
})
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
    toggleReview,
    loadReview,
    reviewOpen,
    reviewItems,
    reviewTotalCount,
    statusOf,
    masteryOf,
    isDecayingOf,
    hoverTip,
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
.wj-review-drawer {
  position: absolute;
  top: 16px;
  right: 16px;
  bottom: 16px;
  width: min(420px, calc(100% - 32px));
  z-index: 28;
  box-sizing: border-box;
  animation: wjDrawerIn 0.34s cubic-bezier(0.22,1,0.36,1) both;
}
</style>
