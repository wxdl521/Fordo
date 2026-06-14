<template>
  <div :style="{ height: '100%', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)', position: 'relative', overflow: 'hidden', fontFamily: sans }">

    <!-- 应用栏 + 搜索 -->
    <div :style="{ flex: 'none', padding: '64px 16px 10px', display: 'flex', alignItems: 'center', gap: '12px', position: 'relative', zIndex: 30 }">
      <span :style="{ fontFamily: serif, fontSize: '19px', fontWeight: 600, letterSpacing: '2px', flex: 'none' }">问津</span>
      <div :style="{ position: 'relative', flex: 1 }">
        <input v-model="s.q" placeholder="搜索知识点，直达节点…" class="wjm-search" :style="{ width: '100%', height: '36px', boxSizing: 'border-box', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '10px', padding: '0 12px', color: 'var(--ink)', fontSize: '13px', outline: 'none' }" />
        <div v-if="results.length" :style="{ position: 'absolute', top: '42px', left: 0, right: 0, background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '12px', padding: '5px', zIndex: 40, boxShadow: '0 10px 28px rgba(0,0,0,0.38)' }">
          <div v-for="r in results" :key="r.id" @click="pickResult(r)" class="wjm-press" :style="{ display: 'flex', alignItems: 'center', gap: '9px', minHeight: '44px', boxSizing: 'border-box', padding: '6px 10px', borderRadius: '8px', cursor: 'pointer' }">
            <span :style="{ width: '7px', height: '7px', borderRadius: '50%', background: r.color, flex: 'none' }"></span>
            <span :style="{ fontSize: '13px', lineHeight: 1.35, flex: 1, minWidth: 0 }">{{ r.name }}</span>
            <span :style="{ fontSize: '10.5px', color: 'var(--mut)', flex: 'none' }">{{ r.chapter }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ───── 地图 Tab ───── -->
    <template v-if="s.tab === '地图'">
      <!-- 形态 A：章节卡片列表 -->
      <div v-if="form === 'a'" data-hidebar="1" :style="{ flex: 1, overflowY: 'auto', padding: '6px 16px 14px', display: 'flex', flexDirection: 'column', gap: '10px' }">
        <div :style="{ flex: 'none', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '14px 16px' }">
          <div :style="{ display: 'flex', alignItems: 'baseline', gap: '8px' }">
            <span :style="{ fontSize: '13.5px', fontWeight: 600 }">软件工程 · 染色地图</span>
            <span :style="{ fontSize: '10.5px', color: 'var(--mut)', marginLeft: 'auto' }">v0.3 · 教师已审</span>
          </div>
          <div :style="{ display: 'flex', height: '6px', borderRadius: '99px', overflow: 'hidden', gap: '2px', margin: '12px 0 9px' }">
            <div :style="{ flex: 22, background: 'var(--ok)' }"></div>
            <div :style="{ flex: 5, background: 'var(--warn)' }"></div>
            <div :style="{ flex: 15, background: 'var(--dim)' }"></div>
          </div>
          <div :style="{ fontSize: '11.5px', color: 'var(--mut)' }">已掌握 22 · 薄弱 5 · 未学 15 · 当前在「领域类图绘制」</div>
        </div>

        <div v-for="ch in chaptersA" :key="ch.name" :style="{ flex: 'none', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', overflow: 'hidden' }">
          <div @click="toggleChapter(ch.name)" class="wjm-press" :style="{ display: 'flex', alignItems: 'center', gap: '12px', minHeight: '56px', boxSizing: 'border-box', padding: '11px 14px', cursor: 'pointer' }">
            <div :style="{ flex: 1, minWidth: 0 }">
              <div :style="{ display: 'flex', alignItems: 'center', gap: '7px' }">
                <span :style="{ fontSize: '13.5px', fontWeight: 600 }">{{ ch.name }}</span>
                <span v-if="ch.isCur" :style="{ fontSize: '10px', color: 'var(--acc)', border: '1px solid var(--acc)', borderRadius: '999px', padding: '1.5px 7px', flex: 'none' }">当前</span>
              </div>
              <div :style="{ display: 'flex', height: '5px', borderRadius: '99px', overflow: 'hidden', gap: '2px', marginTop: '9px' }">
                <div v-for="(sg, i) in ch.segs" :key="i" :style="{ flex: 1, background: sg }"></div>
              </div>
            </div>
            <div :style="{ flex: 'none', textAlign: 'right' }">
              <div :style="{ fontSize: '13px', fontWeight: 600 }">{{ ch.frac }}</div>
              <div :style="{ fontSize: '9.5px', color: 'var(--mut)', marginTop: '2px' }">已掌握</div>
            </div>
            <span :style="{ flex: 'none', color: 'var(--mut)', fontSize: '14px', transform: ch.open ? 'rotate(90deg)' : 'none', transition: 'transform 0.25s' }">›</span>
          </div>
          <div v-if="ch.open" :style="{ borderTop: '1px solid var(--line)', padding: '6px 8px 8px', display: 'flex', flexDirection: 'column', animation: 'wjmFade 0.25s ease both' }">
            <div v-for="nd in ch.nodes" :key="nd.id" @click="select(nd.id)" class="wjm-press" :style="{ display: 'flex', alignItems: 'center', gap: '11px', minHeight: '46px', boxSizing: 'border-box', padding: '5px 8px', borderRadius: '9px', cursor: 'pointer' }">
              <span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: nd.color, boxShadow: nd.ring, flex: 'none' }"></span>
              <span :style="{ fontSize: '13px', lineHeight: 1.4, flex: 1, minWidth: 0 }">{{ nd.name }}</span>
              <span :style="{ fontSize: '11.5px', color: nd.mColor, flex: 'none' }">{{ nd.mTxt }}</span>
              <span :style="{ color: 'var(--mut)', fontSize: '12px', flex: 'none', opacity: 0.6 }">›</span>
            </div>
          </div>
        </div>
        <div :style="{ flex: 'none', textAlign: 'center', fontSize: '11px', color: 'var(--mut)', opacity: 0.7, padding: '6px 0 2px' }">点章节展开 · 点知识点查看详情</div>
      </div>

      <!-- 形态 B：章节星图 -->
      <div v-else :style="{ flex: 1, position: 'relative', overflow: 'hidden' }">
        <template v-if="!s.view">
          <svg viewBox="0 0 375 470" preserveAspectRatio="xMidYMid meet" :style="{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block', animation: 'wjmFade 0.4s ease both' }">
            <line v-for="(e, i) in starEdges" :key="'e' + i" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" stroke="var(--line)" stroke-width="1" />
            <g v-for="st in starNodes" :key="st.name" :style="{ cursor: 'pointer' }" @click="s.view = st.name">
              <circle :cx="st.x" :cy="st.y" :r="st.r * 1.9" :fill="st.fill" opacity="0.1" />
              <circle :cx="st.x" :cy="st.y" :r="st.r" :fill="st.fill" opacity="0.92">
                <animate v-if="st.twinkle" attributeName="opacity" values="0.78;0.95;0.78" :dur="st.dur" repeatCount="indefinite" />
              </circle>
              <template v-if="st.cur">
                <circle :cx="st.x" :cy="st.y" :r="st.r + 5" fill="none" stroke="var(--acc)" stroke-width="1.5">
                  <animate attributeName="r" :values="(st.r + 4) + ';' + (st.r + 9) + ';' + (st.r + 4)" dur="2.4s" repeatCount="indefinite" />
                  <animate attributeName="opacity" values="0.9;0.25;0.9" dur="2.4s" repeatCount="indefinite" />
                </circle>
              </template>
              <text :x="st.x" :y="st.y + st.r + 17" text-anchor="middle" font-size="11.5" fill="var(--ink)" opacity="0.92">{{ st.name }}</text>
              <text :x="st.x" :y="st.y + st.r + 31" text-anchor="middle" font-size="9.5" fill="var(--mut)">{{ st.frac }}</text>
              <circle :cx="st.x" :cy="st.y" :r="Math.max(26, st.r + 8)" fill="rgba(0,0,0,0)" />
            </g>
          </svg>
          <div :style="{ position: 'absolute', left: '14px', bottom: '12px', display: 'flex', gap: '11px', alignItems: 'center', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '999px', padding: '7px 13px', fontSize: '10.5px', color: 'var(--mut)', zIndex: 5 }">
            <span :style="lgItem"><span :style="{ width: '7px', height: '7px', borderRadius: '50%', background: 'var(--ok)' }"></span>已掌握</span>
            <span :style="lgItem"><span :style="{ width: '7px', height: '7px', borderRadius: '50%', background: 'var(--warn)' }"></span>薄弱</span>
            <span :style="lgItem"><span :style="{ width: '7px', height: '7px', borderRadius: '50%', background: 'var(--dim)' }"></span>未学</span>
            <span :style="lgItem"><span :style="{ width: '7px', height: '7px', borderRadius: '2px', border: '1.5px solid var(--acc)', boxSizing: 'border-box' }"></span>当前</span>
          </div>
          <div :style="{ position: 'absolute', right: '16px', bottom: '19px', fontSize: '10.5px', color: 'var(--mut)', opacity: 0.7, zIndex: 5 }">点章节潜入子图</div>
        </template>

        <template v-else>
          <div :style="{ position: 'absolute', top: '6px', left: '14px', right: '14px', display: 'flex', alignItems: 'center', gap: '8px', zIndex: 5 }">
            <button @click="s.view = null; s.sel = null" class="wjm-press" :style="{ height: '34px', padding: '0 13px', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '999px', color: 'var(--mut)', fontSize: '12px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '6px' }">← 全部章节</button>
            <span :style="{ fontSize: '13px', fontWeight: 600, marginLeft: 'auto' }">{{ s.view }}</span>
            <span :style="{ fontSize: '11px', color: 'var(--mut)' }">{{ subFrac }}</span>
          </div>
          <svg viewBox="0 0 375 470" preserveAspectRatio="xMidYMid meet" :style="{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block', animation: 'wjmFade 0.3s ease both' }">
            <line v-for="(e, i) in subEdges" :key="'se' + i" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" stroke="var(--line)" stroke-width="1" />
            <g v-for="nd in subNodes" :key="nd.id" :style="{ cursor: 'pointer' }" @click="select(nd.id)">
              <circle :cx="nd.x" :cy="nd.y" :r="nd.r * 1.9" :fill="nd.fill" opacity="0.1" />
              <circle :cx="nd.x" :cy="nd.y" :r="nd.r" :fill="nd.fill" opacity="0.95" />
              <template v-if="nd.cur">
                <circle :cx="nd.x" :cy="nd.y" :r="nd.r + 5" fill="none" stroke="var(--acc)" stroke-width="1.5">
                  <animate attributeName="r" :values="(nd.r + 4) + ';' + (nd.r + 9) + ';' + (nd.r + 4)" dur="2.4s" repeatCount="indefinite" />
                  <animate attributeName="opacity" values="0.9;0.25;0.9" dur="2.4s" repeatCount="indefinite" />
                </circle>
              </template>
              <text :x="nd.x" :y="nd.y + nd.r + 15" text-anchor="middle" font-size="9.5" :fill="nd.cur ? 'var(--ink)' : 'var(--mut)'">{{ nd.short }}</text>
              <text :x="nd.x" :y="nd.y + nd.r + 27" text-anchor="middle" font-size="8.5" :fill="nd.cur ? 'var(--acc)' : 'var(--mut)'" opacity="0.8">{{ nd.mTxt }}</text>
              <circle :cx="nd.x" :cy="nd.y" :r="Math.max(24, nd.r + 8)" fill="rgba(0,0,0,0)" />
            </g>
          </svg>
          <div :style="{ position: 'absolute', right: '16px', bottom: '19px', fontSize: '10.5px', color: 'var(--mut)', opacity: 0.7, zIndex: 5 }">点节点查看详情</div>
        </template>
      </div>
    </template>

    <!-- ───── 路径 Tab ───── -->
    <div v-else-if="s.tab === '路径'" data-hidebar="1" :style="{ flex: 1, overflowY: 'auto', padding: '6px 16px 16px' }">
      <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '15px 16px', marginBottom: '14px' }">
        <div :style="{ fontSize: '11px', color: 'var(--mut)', marginBottom: '10px', display: 'flex', alignItems: 'center', gap: '8px' }"><span :style="{ width: '6px', height: '6px', borderRadius: '50%', background: 'var(--acc)', flex: 'none' }"></span>由 6月10日 入口诊断生成</div>
        <div :style="{ fontFamily: serif, fontSize: '19px', fontWeight: 600, lineHeight: 1.4, marginBottom: '8px' }">突破「领域类图绘制」</div>
        <div :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.7 }">从根因「用例图绘制」补起，沿前置链逐步推进——根基稳了，卡点自然松动。</div>
        <div :style="{ display: 'flex', alignItems: 'center', gap: '11px', marginTop: '16px' }">
          <div :style="{ flex: 1, height: '6px', background: 'var(--card2)', border: '1px solid var(--line)', borderRadius: '99px', overflow: 'hidden', boxSizing: 'border-box' }"><div :style="{ height: '100%', width: '25%', background: 'var(--ok)', borderRadius: '99px' }"></div></div>
          <span :style="{ fontSize: '11px', color: 'var(--mut)', whiteSpace: 'nowrap' }">四步行其一 · 约 2.5h</span>
        </div>
      </div>
      <div :style="{ position: 'relative' }">
        <div :style="{ position: 'absolute', left: '14px', top: '14px', bottom: '22px', width: '2px', background: 'var(--line)' }"></div>
        <div v-for="(step, i) in pathSteps" :key="i" :style="{ position: 'relative', display: 'grid', gridTemplateColumns: '30px 1fr', gap: '12px', marginBottom: '14px', animation: 'wjmRise 0.45s cubic-bezier(0.22,1,0.36,1) both', animationDelay: (i * 0.06) + 's' }">
          <div :style="{ position: 'relative', zIndex: 1, width: '30px', height: '30px', boxSizing: 'border-box', borderRadius: '50%', background: step.markBg, border: step.markBorder, display: 'flex', alignItems: 'center', justifyContent: 'center', color: step.markColor, fontSize: '13px', fontWeight: 600, animation: step.markAnim }">{{ step.mark }}</div>
          <div @click="step.node && select(step.node)" :style="{ background: 'var(--card)', border: step.cardBorder, borderRadius: '12px', padding: '13px 14px', cursor: step.node ? 'pointer' : 'default', opacity: step.cardOpacity }">
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '8px', flexWrap: 'wrap', marginBottom: '5px' }">
              <span :style="{ fontSize: '15px', fontWeight: 600, color: step.nameColor, textDecoration: step.nameDeco, textDecorationColor: 'var(--mut)', textDecorationThickness: '1px' }">{{ step.name }}</span>
              <span :style="{ fontSize: '10.5px', color: 'var(--mut)' }">{{ step.tag }}</span>
            </div>
            <div :style="{ fontSize: '11.5px', color: step.statusColor, marginBottom: '9px' }">{{ step.status }}</div>
            <div :style="{ fontSize: '12px', color: 'var(--mut)', lineHeight: 1.72, marginBottom: step.whyMb }"><span :style="{ color: step.whyLabelColor }">{{ step.whyLabel }}</span> — {{ step.why }}</div>
            <template v-if="step.isCurrent">
              <div :style="{ display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '13px' }">
                <div v-for="(r, ri) in step.resRows" :key="ri" :style="{ display: 'flex', alignItems: 'center', gap: '9px', minHeight: '44px', boxSizing: 'border-box', padding: '7px 10px', background: 'var(--card2)', borderRadius: '8px' }">
                  <span :style="{ fontSize: '10px', letterSpacing: '1px', color: r.tagColor, border: '1px solid ' + r.tagBorder, borderRadius: '5px', padding: '1px 6px', flex: 'none' }">{{ r.tag }}</span>
                  <span :style="{ fontSize: '12px', lineHeight: 1.35, flex: 1, minWidth: 0 }">{{ r.name }}</span>
                  <span :style="{ fontSize: '10.5px', color: 'var(--mut)', flex: 'none', whiteSpace: 'nowrap' }">{{ r.meta }}</span>
                </div>
              </div>
              <button @click.stop="select('KT10-1')" class="wjm-press" :style="{ width: '100%', height: '44px', background: 'var(--acc)', border: 'none', borderRadius: '11px', color: '#FFFDF8', fontSize: '14px', fontWeight: 500, cursor: 'pointer', transition: 'transform 0.15s ease' }">继续学习</button>
            </template>
            <div v-else-if="step.chips.length" :style="{ display: 'flex', gap: '7px', flexWrap: 'wrap' }">
              <span v-for="(c, ci) in step.chips" :key="ci" :style="{ fontSize: '11px', color: 'var(--mut)', background: 'var(--card2)', borderRadius: '6px', padding: '4px 9px' }">{{ c }}</span>
            </div>
          </div>
        </div>
      </div>
      <div :style="{ textAlign: 'center', marginTop: '20px', fontFamily: serif, fontSize: '12px', color: 'var(--mut)', letterSpacing: '2px' }">路漫漫其修远，此处有渡口。</div>
    </div>

    <!-- ───── 练习 Tab ───── -->
    <template v-else-if="s.tab === '练习'">
      <!-- Hub -->
      <div v-if="prac.mode === 'hub'" data-hidebar="1" :style="{ flex: 1, overflowY: 'auto', padding: '6px 16px 16px' }">
        <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '15px 16px', marginBottom: '14px' }">
          <div :style="{ fontFamily: serif, fontSize: '18px', fontWeight: 600, marginBottom: '6px' }">针对练习</div>
          <div :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.7 }">练习从你的薄弱处生长——每组都对准路径上的一个知识点。</div>
          <div :style="{ display: 'flex', gap: '22px', marginTop: '15px' }">
            <div><div :style="{ fontSize: '20px', fontWeight: 600 }">24</div><div :style="statSub">本周已练</div></div>
            <div><div :style="{ fontSize: '20px', fontWeight: 600, color: 'var(--ok)' }">78%</div><div :style="statSub">近期正确率</div></div>
            <div><div :style="{ fontSize: '20px', fontWeight: 600, color: 'var(--warn)' }">5</div><div :style="statSub">待修知识点</div></div>
          </div>
        </div>
        <div :style="{ fontSize: '11px', letterSpacing: '2px', color: 'var(--mut)', margin: '4px 2px 10px' }">推荐练习</div>
        <div :style="{ display: 'flex', flexDirection: 'column', gap: '10px' }">
          <div v-for="set in prac.sets" :key="set.key" @click="startSet(set.key)" class="wjm-press" :style="{ background: 'var(--card)', border: set.accent ? '1.5px solid var(--acc)' : '1px solid var(--line)', borderRadius: '13px', padding: '14px 15px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '13px' }">
            <span :style="{ width: '10px', height: '10px', borderRadius: '50%', background: set.dot, flex: 'none' }"></span>
            <div :style="{ flex: 1, minWidth: 0 }">
              <div :style="{ display: 'flex', alignItems: 'baseline', gap: '7px', flexWrap: 'wrap' }">
                <span :style="{ fontSize: '14.5px', fontWeight: 600 }">{{ set.title }}</span>
                <span :style="{ fontSize: '10.5px', color: set.subColor }">{{ set.sub }}</span>
              </div>
              <div :style="{ fontSize: '11.5px', color: 'var(--mut)', marginTop: '4px' }">{{ set.meta }}</div>
            </div>
            <span :style="{ flex: 'none', width: '30px', height: '30px', borderRadius: '50%', background: set.accent ? 'var(--acc)' : 'var(--card2)', color: set.accent ? '#FFFDF8' : 'var(--mut)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px' }">›</span>
          </div>
        </div>
      </div>

      <!-- Quiz -->
      <div v-else-if="prac.mode === 'quiz'" data-hidebar="1" :style="{ flex: 1, overflowY: 'auto', padding: '10px 16px 16px', display: 'flex', flexDirection: 'column' }">
        <div :style="{ display: 'flex', alignItems: 'center', gap: '11px', marginBottom: '16px' }">
          <button @click="exitSet" class="wjm-press" :style="{ width: '30px', height: '30px', flex: 'none', border: 'none', background: 'transparent', color: 'var(--mut)', fontSize: '18px', cursor: 'pointer', borderRadius: '8px', lineHeight: 1 }">×</button>
          <div :style="{ flex: 1, height: '5px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden' }"><div :style="{ height: '100%', background: 'var(--acc)', borderRadius: '99px', width: prac.progress, transition: 'width 0.3s' }"></div></div>
          <span :style="{ fontSize: '11.5px', color: 'var(--mut)', whiteSpace: 'nowrap' }">{{ prac.qNo }}</span>
        </div>
        <div :style="{ marginBottom: '16px' }">
          <div :style="{ display: 'flex', gap: '8px', marginBottom: '11px' }">
            <span :style="{ fontSize: '11px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '2px 10px' }">{{ prac.qKt }}</span>
            <span :style="{ fontSize: '11px', color: 'var(--mut)' }">单选 · {{ prac.qDiff }}</span>
          </div>
          <div :style="{ fontSize: '16px', fontWeight: 600, lineHeight: 1.6, textWrap: 'pretty' }">{{ prac.qText }}</div>
        </div>
        <div :style="{ display: 'flex', flexDirection: 'column', gap: '9px' }">
          <div v-for="(o, i) in prac.opts" :key="i" @click="pickOption(i)" :style="{ display: 'flex', alignItems: 'center', gap: '11px', minHeight: '52px', boxSizing: 'border-box', padding: '11px 13px', background: o.bg, border: o.border, borderRadius: '11px', cursor: o.cursor }">
            <span :style="{ width: '24px', height: '24px', flex: 'none', boxSizing: 'border-box', borderRadius: '50%', background: o.letterBg, color: o.letterColor, border: o.letterBorder, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '12.5px', fontWeight: 600 }">{{ o.letter }}</span>
            <span :style="{ fontSize: '13.5px', lineHeight: 1.5, flex: 1, minWidth: 0, color: o.textColor }">{{ o.text }}</span>
            <span :style="{ flex: 'none', color: o.iconColor, fontSize: '15px' }">{{ o.icon }}</span>
          </div>
        </div>
        <div v-if="prac.revealed" :style="{ marginTop: '14px', background: prac.fbBg, border: '1px solid ' + prac.fbBorder, borderRadius: '11px', padding: '12px 14px' }">
          <div :style="{ fontSize: '13px', fontWeight: 600, color: prac.fbColor, marginBottom: '6px' }">{{ prac.fbTitle }}</div>
          <div :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.7 }"><span :style="{ color: 'var(--ink)' }">解析 ·</span> {{ prac.fbWhy }}</div>
        </div>
        <div :style="{ flex: 1, minHeight: '12px' }"></div>
        <button v-if="!prac.revealed" @click="submitAnswer" :style="{ marginTop: '16px', width: '100%', height: '46px', background: prac.pick != null ? 'var(--acc)' : 'var(--card2)', border: 'none', borderRadius: '12px', color: prac.pick != null ? '#FFFDF8' : 'var(--mut)', fontSize: '14.5px', fontWeight: 500, cursor: prac.pick != null ? 'pointer' : 'default' }">提交答案</button>
        <button v-else @click="nextQuestion" class="wjm-press" :style="{ marginTop: '16px', width: '100%', height: '46px', background: 'var(--acc)', border: 'none', borderRadius: '12px', color: '#FFFDF8', fontSize: '14.5px', fontWeight: 500, cursor: 'pointer', transition: 'transform 0.15s ease' }">{{ prac.nextLabel }}</button>
      </div>

      <!-- Done -->
      <div v-else :style="{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '0 32px', textAlign: 'center' }">
        <div :style="{ width: '64px', height: '64px', borderRadius: '50%', background: 'var(--okSoft)', border: '1.5px solid var(--ok)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--ok)', fontSize: '30px', marginBottom: '18px' }">✓</div>
        <div :style="{ fontFamily: serif, fontSize: '19px', fontWeight: 600, marginBottom: '8px' }">这一组练完了</div>
        <div :style="{ fontSize: '13px', color: 'var(--mut)', marginBottom: '22px' }">{{ prac.doneScore }} 题正确 · 正确率 {{ prac.doneRate }}</div>
        <div :style="{ width: '100%', background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '13px', padding: '14px 16px', marginBottom: '22px' }">
          <div :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '10px' }">{{ prac.doneNode }} · 掌握度变化</div>
          <div :style="{ display: 'flex', alignItems: 'center', gap: '11px' }">
            <span :style="{ fontSize: '14px', color: 'var(--mut)', flex: 'none' }">{{ prac.doneFrom }}</span>
            <span :style="{ flex: 1, height: '5px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden', position: 'relative' }"><span :style="{ position: 'absolute', left: 0, top: 0, bottom: 0, width: prac.doneTo, background: 'var(--ok)', borderRadius: '99px' }"></span></span>
            <span :style="{ fontSize: '17px', fontWeight: 600, color: 'var(--ok)', flex: 'none' }">{{ prac.doneToText }}</span>
            <span :style="{ fontSize: '12px', color: 'var(--ok)', flex: 'none' }">{{ prac.doneDelta }}</span>
          </div>
        </div>
        <button @click="exitSet" class="wjm-press" :style="{ width: '100%', height: '46px', background: 'var(--acc)', border: 'none', borderRadius: '12px', color: '#FFFDF8', fontSize: '14.5px', fontWeight: 500, cursor: 'pointer' }">返回练习</button>
      </div>
    </template>

    <!-- ───── 我的 Tab ───── -->
    <div v-else data-hidebar="1" :style="{ flex: 1, overflowY: 'auto', padding: '8px 16px 18px' }">
      <div :style="{ display: 'flex', alignItems: 'center', gap: '13px', padding: '2px 2px 14px' }">
        <div :style="{ width: '52px', height: '52px', borderRadius: '50%', background: 'var(--acc)', color: '#FFFDF8', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: serif, fontSize: '22px', fontWeight: 600, flex: 'none' }">晚</div>
        <div :style="{ flex: 1, minWidth: 0 }">
          <div :style="{ fontSize: '17px', fontWeight: 600 }">林晚舟</div>
          <div :style="{ fontSize: '11.5px', color: 'var(--mut)', marginTop: '3px' }">软件工程 · 自 6月10日 入口诊断起</div>
        </div>
      </div>
      <div :style="{ fontFamily: serif, fontSize: '18px', fontWeight: 600, margin: '2px 2px 14px' }">你的地图，正在变绿</div>

      <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '15px 16px', marginBottom: '13px' }">
        <div :style="{ display: 'flex', gap: '14px' }">
          <div :style="{ flex: 1 }"><div :style="{ fontSize: '22px', fontWeight: 600, color: 'var(--acc)' }">65%</div><div :style="statSub2">整体掌握度</div><div :style="{ fontSize: '10px', color: 'var(--ok)', marginTop: '4px' }">较诊断 +13</div></div>
          <div :style="{ width: '1px', background: 'var(--line)', flex: 'none' }"></div>
          <div :style="{ flex: 1 }"><div :style="{ fontSize: '22px', fontWeight: 600 }">22<span :style="{ fontSize: '12px', color: 'var(--mut)', fontWeight: 400 }"> /42</span></div><div :style="statSub2">已掌握节点</div><div :style="{ fontSize: '10px', color: 'var(--mut)', marginTop: '4px' }">诊断时 16</div></div>
          <div :style="{ width: '1px', background: 'var(--line)', flex: 'none' }"></div>
          <div :style="{ flex: 1 }"><div :style="{ fontSize: '22px', fontWeight: 600, color: 'var(--warn)' }">5</div><div :style="statSub2">待修薄弱</div><div :style="{ fontSize: '10px', color: 'var(--mut)', marginTop: '4px' }">诊断时 6</div></div>
        </div>
      </div>

      <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '15px 16px', marginBottom: '14px' }">
        <div :style="{ display: 'flex', alignItems: 'baseline', gap: '10px', marginBottom: '16px' }">
          <span :style="{ fontSize: '11px', letterSpacing: '3px', color: 'var(--mut)' }">地图前后对比</span>
          <span :style="{ fontSize: '11px', color: 'var(--ok)', background: 'var(--okSoft)', borderRadius: '999px', padding: '2px 9px', marginLeft: 'auto' }">+6 个节点转绿</span>
        </div>
        <div :style="{ display: 'flex', flexDirection: 'column', gap: '16px' }">
          <div v-for="(p, pi) in comparePanels" :key="pi">
            <div :style="{ display: 'flex', alignItems: 'baseline', gap: '10px', marginBottom: '11px' }">
              <span :style="{ fontSize: '12.5px', fontWeight: 600 }">{{ p.title }}</span>
              <span :style="{ fontSize: '10.5px', color: 'var(--mut)', marginLeft: 'auto' }">{{ p.sub }}</span>
            </div>
            <div :style="{ display: 'flex', flexDirection: 'column', gap: '8px' }">
              <div v-for="(ch, ci) in p.chapters" :key="ci" :style="{ display: 'flex', alignItems: 'center', gap: '10px' }">
                <span :style="{ width: '78px', flex: 'none', fontSize: '11px', color: 'var(--mut)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }">{{ ch.name }}</span>
                <div :style="{ display: 'flex', flexWrap: 'wrap', gap: '4px' }">
                  <span v-for="(dt, di) in ch.dots" :key="di" :style="{ width: '10px', height: '10px', borderRadius: '50%', background: dt.bg, boxShadow: dt.ring }"></span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div :style="{ display: 'flex', gap: '13px', flexWrap: 'wrap', marginTop: '16px', paddingTop: '13px', borderTop: '1px solid var(--line)' }">
          <span :style="lgItem2"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--ok)' }"></span>已掌握</span>
          <span :style="lgItem2"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--warn)' }"></span>薄弱</span>
          <span :style="lgItem2"><span :style="{ width: '9px', height: '9px', borderRadius: '50%', background: 'var(--dim)' }"></span>未学</span>
          <span @click="goMap" :style="{ marginLeft: 'auto', fontSize: '11.5px', color: 'var(--acc)', cursor: 'pointer' }">在地图上查看 ›</span>
        </div>
      </div>

      <button @click="s.screen = 'diag'" class="wjm-press" :style="{ width: '100%', height: '44px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '11px', color: 'var(--ink)', fontSize: '13px', cursor: 'pointer', marginBottom: '16px' }">查看本次诊断结果</button>
      <div :style="{ textAlign: 'center', fontFamily: serif, fontSize: '13px', color: 'var(--mut)', letterSpacing: '3px', marginTop: '4px' }">来路渐明，前路可期</div>
    </div>

    <!-- ───── 底部 Tab 栏 ───── -->
    <div :style="{ flex: 'none', display: 'flex', borderTop: '1px solid var(--line)', background: 'var(--card)', padding: '6px 10px 24px', position: 'relative', zIndex: 30 }">
      <button v-for="t in TABS" :key="t" @click="s.tab = t; s.sel = null" :style="{ flex: 1, height: '48px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '3px', background: 'transparent', border: 'none', cursor: 'pointer', color: s.tab === t ? 'var(--acc)' : 'var(--mut)', padding: 0 }">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" :style="{ display: 'block' }">
          <template v-if="t === '地图'"><path d="M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2z" /><path d="M9 4v14" /><path d="M15 6v14" /></template>
          <template v-else-if="t === '路径'"><circle cx="6" cy="18.5" r="2.2" /><circle cx="18" cy="5.5" r="2.2" /><path d="M8.2 18.5H15a3.5 3.5 0 0 0 0-7H9a3.5 3.5 0 0 1 0-7h6.8" /></template>
          <template v-else-if="t === '练习'"><path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" /></template>
          <template v-else><circle cx="12" cy="8" r="3.6" /><path d="M5 20c0-3.8 3.1-6 7-6s7 2.2 7 6" /></template>
        </svg>
        <span :style="{ fontSize: '10.5px', fontWeight: s.tab === t ? 600 : 400 }">{{ t }}</span>
      </button>
    </div>

    <!-- ───── 诊断结果（应用内屏） ───── -->
    <div v-if="s.screen === 'diag'" :style="{ position: 'absolute', inset: 0, zIndex: 38, background: 'var(--bg)', display: 'flex', flexDirection: 'column', animation: 'wjmFade 0.28s ease both' }">
      <div :style="{ flex: 'none', padding: '62px 16px 12px', display: 'flex', alignItems: 'center', gap: '10px', borderBottom: '1px solid var(--line)' }">
        <button @click="s.screen = 'app'" class="wjm-press" :style="{ width: '30px', height: '30px', flex: 'none', border: 'none', background: 'transparent', color: 'var(--mut)', fontSize: '19px', cursor: 'pointer', borderRadius: '8px', lineHeight: 1 }">‹</button>
        <span :style="{ fontFamily: serif, fontSize: '16px', fontWeight: 600, letterSpacing: '1px' }">诊断结果</span>
        <span :style="{ marginLeft: 'auto', fontSize: '11px', color: 'var(--mut)' }">入口诊断 · 第 1 次</span>
      </div>
      <div data-hidebar="1" :style="{ flex: 1, overflowY: 'auto', padding: '16px 16px 22px' }">
        <div :style="{ fontSize: '11.5px', color: 'var(--mut)', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }"><span>6月10日 21:32</span><span :style="{ opacity: 0.5 }">·</span><span>25 题</span><span :style="{ opacity: 0.5 }">·</span><span>用时 14 分 28 秒</span></div>
        <div :style="{ fontSize: '11px', letterSpacing: '3px', color: 'var(--mut)', marginBottom: '12px' }">诊断结论</div>
        <div :style="{ fontFamily: serif, fontSize: '23px', fontWeight: 600, lineHeight: 1.4, marginBottom: '12px' }">你卡在<br />「领域类图绘制」</div>
        <div :style="{ fontSize: '13.5px', color: 'var(--mut)', lineHeight: 1.8, marginBottom: '22px' }">根本原因更可能是前置点「用例图绘制」掌握薄弱，而不是这个知识点本身——参与者与系统边界识别不清，业务实体便提取困难，领域类图自然无从下手。</div>
        <div :style="{ display: 'flex', flexDirection: 'column', alignItems: 'stretch', gap: '7px', marginBottom: '24px' }">
          <div :style="{ position: 'relative', background: 'var(--card2)', border: '1.5px solid var(--acc)', borderRadius: '11px', padding: '14px 16px', display: 'flex', alignItems: 'center', gap: '12px' }">
            <span :style="{ position: 'absolute', top: '-9px', left: '14px', background: 'var(--acc)', color: '#FFFDF8', fontSize: '10px', letterSpacing: '1px', borderRadius: '5px', padding: '2px 8px' }">根因</span>
            <div :style="{ flex: 1 }"><div :style="{ fontSize: '14px', fontWeight: 500 }">用例图绘制</div><div :style="{ fontSize: '11px', color: 'var(--mut)', marginTop: '3px' }">参与者与系统边界识别</div></div>
            <div :style="{ textAlign: 'right', flex: 'none' }"><div :style="{ fontSize: '20px', fontWeight: 600, color: 'var(--warn)' }">48%</div><div :style="{ fontSize: '10.5px', color: 'var(--mut)' }">薄弱</div></div>
          </div>
          <div :style="{ textAlign: 'center', color: 'var(--mut)', fontSize: '14px', lineHeight: 1 }">↓</div>
          <div :style="{ background: 'var(--card2)', border: '1px solid var(--line)', borderRadius: '11px', padding: '14px 16px', display: 'flex', alignItems: 'center', gap: '12px' }">
            <div :style="{ flex: 1 }"><div :style="{ fontSize: '14px', fontWeight: 500 }">业务实体识别</div><div :style="{ fontSize: '11px', color: 'var(--mut)', marginTop: '3px' }">从用例提取候选实体</div></div>
            <div :style="{ textAlign: 'right', flex: 'none' }"><div :style="{ fontSize: '20px', fontWeight: 600, color: 'var(--warn)' }">52%</div><div :style="{ fontSize: '10.5px', color: 'var(--mut)' }">薄弱</div></div>
          </div>
          <div :style="{ textAlign: 'center', color: 'var(--mut)', fontSize: '14px', lineHeight: 1 }">↓</div>
          <div :style="{ position: 'relative', background: 'var(--card2)', border: '1px solid var(--acc)', borderRadius: '11px', padding: '14px 16px', display: 'flex', alignItems: 'center', gap: '12px' }">
            <span :style="{ position: 'absolute', top: '-9px', left: '14px', background: 'var(--card)', border: '1px solid var(--acc)', color: 'var(--acc)', fontSize: '10px', letterSpacing: '1px', borderRadius: '5px', padding: '1px 8px' }">当前卡点</span>
            <div :style="{ flex: 1 }"><div :style="{ fontSize: '14px', fontWeight: 500 }">领域类图绘制</div><div :style="{ fontSize: '11px', color: 'var(--mut)', marginTop: '3px' }">实体属性与关系建模</div></div>
            <div :style="{ textAlign: 'right', flex: 'none' }"><div :style="{ fontSize: '20px', fontWeight: 600, color: 'var(--acc)' }">34%</div><div :style="{ fontSize: '10.5px', color: 'var(--mut)' }">待突破</div></div>
          </div>
        </div>
        <button @click="s.screen = 'app'; s.tab = '路径'; s.sel = null" class="wjm-press" :style="{ width: '100%', height: '48px', border: 'none', background: 'var(--acc)', borderRadius: '12px', color: '#FFFDF8', fontSize: '15px', fontWeight: 500, cursor: 'pointer', marginBottom: '10px', transition: 'transform 0.15s ease' }">生成学习路径</button>
        <button @click="s.screen = 'app'; s.tab = '地图'; s.sel = null; s.view = null" class="wjm-press" :style="{ width: '100%', height: '44px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '12px', color: 'var(--ink)', fontSize: '13.5px', cursor: 'pointer', marginBottom: '24px' }">在地图上查看回溯链</button>
        <div :style="{ background: 'var(--card)', border: '1px solid var(--line)', borderRadius: '14px', padding: '16px 16px 6px' }">
          <div :style="{ fontSize: '11px', letterSpacing: '3px', color: 'var(--mut)', marginBottom: '6px' }">判断依据</div>
          <div v-for="(b, i) in diagBases" :key="i" :style="{ display: 'flex', gap: '13px', padding: '13px 0', borderBottom: i < diagBases.length - 1 ? '1px solid var(--line)' : 'none' }">
            <span :style="{ fontFamily: serif, fontSize: '14px', color: 'var(--mut)', flex: 'none', paddingTop: '1px' }">{{ b.ord }}</span>
            <div :style="{ flex: 1, minWidth: 0 }"><div :style="{ fontSize: '13px', lineHeight: 1.6, marginBottom: '3px' }" v-html="b.title"></div><div :style="{ fontSize: '11.5px', color: 'var(--mut)' }">{{ b.sub }}</div></div>
            <span :style="{ fontSize: '17px', fontWeight: 600, color: b.color, flex: 'none' }">{{ b.pct }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ───── 知识点底部抽屉 ───── -->
    <template v-if="sel">
      <div @click="s.sel = null" :style="{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 40, animation: 'wjmFade 0.25s ease both' }"></div>
      <div :style="{ position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: '76%', background: 'var(--card)', borderTop: '1px solid var(--line)', borderRadius: '20px 20px 0 0', zIndex: 41, display: 'flex', flexDirection: 'column', animation: 'wjmSheetIn 0.32s cubic-bezier(0.22,1,0.36,1) both' }">
        <div :style="{ flex: 'none', display: 'flex', justifyContent: 'center', padding: '9px 0 2px' }"><div :style="{ width: '38px', height: '4px', borderRadius: '99px', background: 'var(--line)' }"></div></div>
        <div data-hidebar="1" :style="{ flex: 1, overflowY: 'auto', padding: '8px 20px 10px' }">
          <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '10px' }">
            <span :style="{ fontSize: '11px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '3px 10px', flex: 'none' }">{{ sel.chapter }}</span>
            <button @click="s.sel = null" class="wjm-press" :style="{ marginLeft: 'auto', width: '32px', height: '32px', border: 'none', background: 'transparent', color: 'var(--mut)', fontSize: '18px', cursor: 'pointer', borderRadius: '8px', lineHeight: 1 }">×</button>
          </div>
          <div :style="{ fontSize: '16.5px', fontWeight: 600, lineHeight: 1.45, marginBottom: '13px' }">{{ sel.name }}</div>
          <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '8px' }">
            <span :style="{ fontSize: '12px', fontWeight: 500, color: selStatusColor, background: selStatusBg, borderRadius: '999px', padding: '4px 11px' }">{{ selStatusLabel }}</span>
            <span :style="{ fontSize: '19px', fontWeight: 600, color: selStatusColor, marginLeft: 'auto' }">{{ selMasteryText }}</span>
          </div>
          <div :style="{ height: '5px', background: 'var(--line)', borderRadius: '99px', overflow: 'hidden', marginBottom: '13px' }"><div :style="{ height: '100%', borderRadius: '99px', background: selStatusColor, width: selMasteryPct, transition: 'width 0.4s ease' }"></div></div>
          <div :style="{ display: 'flex', gap: '15px', fontSize: '11.5px', color: 'var(--mut)', marginBottom: '12px', flexWrap: 'wrap' }">
            <span>难度 <span :style="{ letterSpacing: '1px' }">{{ selDiffDots }}</span></span>
            <span>认知层级 · {{ sel.bloom }}</span>
            <span v-if="sel.is_key" :style="{ color: 'var(--ink)' }">考核重点</span>
          </div>
          <div :style="{ fontSize: '12.5px', color: 'var(--mut)', lineHeight: 1.75, paddingBottom: '14px', borderBottom: '1px solid var(--line)' }">{{ sel.description }}</div>
          <div :style="{ fontSize: '11px', letterSpacing: '2px', color: 'var(--mut)', margin: '14px 0 9px' }">前置知识点</div>
          <div v-if="selPrereqs.length" :style="{ display: 'flex', flexDirection: 'column', gap: '6px' }">
            <div v-for="p in selPrereqs" :key="p.id" @click="select(p.id)" class="wjm-press" :style="{ display: 'flex', alignItems: 'center', gap: '9px', minHeight: '44px', boxSizing: 'border-box', padding: '8px 11px', border: '1px solid var(--line)', borderRadius: '10px', cursor: 'pointer' }">
              <span :style="{ width: '8px', height: '8px', borderRadius: '50%', background: p.color, flex: 'none' }"></span>
              <span :style="{ fontSize: '12.5px', lineHeight: 1.4, flex: 1, minWidth: 0 }">{{ p.name }}</span>
              <span :style="{ fontSize: '10.5px', color: 'var(--mut)', flex: 'none' }">{{ p.statusLabel }}</span>
            </div>
          </div>
          <div v-else :style="{ fontSize: '12.5px', color: 'var(--mut)' }">无前置依赖，可直接学习</div>
          <div :style="{ fontSize: '11px', letterSpacing: '2px', color: 'var(--mut)', margin: '15px 0 9px' }">学习资源</div>
          <div :style="{ display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '8px' }">
            <div v-for="(res, i) in selResources" :key="i" class="wjm-press" :style="{ display: 'flex', alignItems: 'center', gap: '9px', minHeight: '44px', boxSizing: 'border-box', padding: '8px 11px', border: '1px solid var(--line)', borderRadius: '10px', cursor: 'pointer' }">
              <span :style="{ fontSize: '10px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '5px', padding: '2px 6px', flex: 'none' }">{{ res.t }}</span>
              <span :style="{ fontSize: '12.5px' }">{{ res.n }}</span>
            </div>
          </div>
        </div>
        <div :style="{ flex: 'none', display: 'flex', gap: '10px', padding: '12px 20px 28px', borderTop: '1px solid var(--line)' }">
          <button class="wjm-press" :style="{ flex: 1, height: '44px', background: 'var(--acc)', border: 'none', borderRadius: '11px', color: '#FFFDF8', fontSize: '14px', fontWeight: 500, cursor: 'pointer', transition: 'transform 0.15s ease' }">{{ selCta }}</button>
          <button class="wjm-press" :style="{ height: '44px', padding: '0 16px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '11px', color: 'var(--ink)', fontSize: '13px', cursor: 'pointer' }">问 AI 伴侣</button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { reactive, computed } from 'vue'

const props = defineProps({
  form: { type: String, required: true },
  data: { type: Object, default: null }
})

const serif = "'Noto Serif SC', serif"
const sans = "'PingFang SC', 'HarmonyOS Sans', sans-serif"
const TABS = ['地图', '路径', '练习', '我的']
const CURRENT = 'KT10-2'

const statSub = { fontSize: '10.5px', color: 'var(--mut)', marginTop: '2px' }
const statSub2 = { fontSize: '10.5px', color: 'var(--mut)', marginTop: '3px' }
const lgItem = { display: 'flex', alignItems: 'center', gap: '5px' }
const lgItem2 = { display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '10.5px', color: 'var(--mut)' }

const MASTERY = {
  KT01: [92, 'ok'], KT02: [88, 'ok'], 'KT02-1': [90, 'ok'], 'KT02-2': [81, 'ok'],
  KT03: [84, 'ok'], 'KT03-1': [86, 'ok'], 'KT03-2': [78, 'ok'],
  KT04: [90, 'ok'], KT05: [83, 'ok'], 'KT05-1': [85, 'ok'], 'KT05-2': [80, 'ok'],
  KT06: [82, 'ok'], KT27: [88, 'ok'], KT29: [86, 'ok'],
  'KT07-1': [79, 'ok'], 'KT07-4': [77, 'ok'], 'KT07-2': [81, 'ok'],
  KT13: [75, 'ok'], KT14: [72, 'ok'], KT15: [73, 'ok'], KT16: [76, 'ok'], KT17: [74, 'ok'],
  KT07: [62, 'warn'], 'KT07-3': [55, 'warn'], KT10: [58, 'warn'], 'KT10-1': [52, 'warn'],
  'KT10-2': [34, 'cur']
}
const SHORT = {
  KT01: '软工概念与生命周期', KT02: '传统过程模型', KT03: '现代过程模型', KT04: '需求概念与目标',
  KT05: '业务流程分析', KT06: '团队组织管理', KT07: '用例模型', KT10: '领域模型', KT13: '软件设计概念',
  KT14: '通用设计原则', KT15: '架构设计', KT16: '交互设计', KT17: '数据设计', KT18: 'OO设计原则',
  KT19: '设计类构建', KT20: '类设计转代码', KT22: '测试概念与过程', KT23: '黑盒测试方法',
  KT24: '部署与维护', KT25: '软件质量管理', KT27: 'UML建模工具', KT28: '数据库建模工具', KT29: '原型设计工具'
}
const CHAPTERS = ['软件工程概述', '需求确定', '软件项目管理', '系统分析', '系统设计', '对象设计', '软件测试', '部署与维护']
const ANCH = {
  软件工程概述: [64, 152], 软件项目管理: [152, 58], 需求确定: [148, 272], 系统分析: [237, 170],
  系统设计: [258, 334], 对象设计: [300, 72], 软件测试: [318, 218], 部署与维护: [318, 412]
}
const PAL = { ok: 'var(--ok)', warn: 'var(--warn)', dim: 'var(--dim)', cur: 'var(--acc)' }
const STATUS_LABEL = { ok: '已掌握', warn: '薄弱 · 待修', dim: '未学', cur: '当前位置' }
const STATUS_BG = { ok: 'var(--okSoft)', warn: 'var(--warnSoft)', dim: 'var(--card2)', cur: 'var(--accSoft)' }
const CH = [
  { name: '软件工程概述', b: 'ggggggg', a: 'ggggggg' },
  { name: '需求确定', b: 'ggggwwwd', a: 'gggggwwd' },
  { name: '系统分析', b: 'ggwwcdddd', a: 'ggwwcdddd' },
  { name: '系统设计', b: 'gddddddd', a: 'ggggggdd' },
  { name: '实现与测试', b: 'ggddddd', a: 'ggddddd' },
  { name: '软件维护', b: 'ddd', a: 'ddd' }
]
const QB = {
  be1: { kt: '业务实体识别', diff: '●●○', text: '网上书店需求中「读者可将图书加入购物车」一句，最适合提取为候选业务实体的名词组是：', opts: ['读者、图书、购物车', '加入、购物车', '需求、系统、功能', '读者、操作、流程'], ans: 0, why: '候选业务实体来自需求中的关键名词；动词（加入）与抽象词（需求/系统）一般不作为领域实体。' },
  be2: { kt: '业务实体识别', diff: '●●○', text: '「学生选修课程，每门课程由一位教师讲授」中，候选实体最完整的一组是：', opts: ['学生、课程、教师', '选修、讲授', '学生、选修', '课程、一位'], ans: 0, why: '名词分析法先圈出名词作候选实体，动词（选修、讲授）通常表达实体间的关系。' },
  be3: { kt: '业务实体识别', diff: '●○○', text: '下列哪一项最不可能成为领域模型中的业务实体？', opts: ['「保存」按钮的点击动作', '订单', '商品', '客户'], ans: 0, why: '界面操作属于交互层，不是领域概念；订单、商品、客户才是典型业务实体。' },
  cd1: { kt: '领域类图绘制', diff: '●●●', text: '「一个订单包含多个订单项，订单项不能脱离订单单独存在」，正确的建模方式是：', opts: ['Order 与 OrderItem 的组合关系', 'Order 与 OrderItem 的聚合关系', 'OrderItem 继承 Order', '两者之间的依赖关系'], ans: 0, why: '部分不能脱离整体独立存在时用组合（实心菱形）；若可独立存在则用聚合。' },
  cd2: { kt: '领域类图绘制', diff: '●●○', text: '表示「一位教师可讲授多门课程，一门课程仅一位教师」的多重性标注是：', opts: ['教师 1 —— * 课程', '教师 * —— * 课程', '教师 1 —— 1 课程', '教师 * —— 1 课程'], ans: 0, why: '一对多关系：从教师端看为 1，从课程端看为 *（多）。' },
  cd3: { kt: '领域类图绘制', diff: '●●○', text: '绘制领域类图时，应优先确定的是：', opts: ['实体的属性与实体间关系', '每个类的具体方法实现', '数据库表的索引', '界面布局'], ans: 0, why: '领域类图关注概念结构（属性 + 关系），方法实现与存储细节属于设计、编码阶段。' },
  uc: { kt: '用例关系', diff: '●●●', text: '「打印回执」只在用户勾选发票时才执行，它与「支付订单」的关系是：', opts: ['«extend» 扩展关系', '«include» 包含关系', '泛化关系', '关联关系'], ans: 0, why: '可选的、在特定条件下才发生的步骤用 «extend»；总会发生的公共步骤才用 «include»。' },
  arch: { kt: '分层架构', diff: '●●●', text: '三层架构中，业务逻辑层直接读写数据库表，违反了哪条原则？', opts: ['各层只依赖其直接下层的接口', '上层不得调用下层', '层间必须异步通信', '每层必须独立部署'], ans: 0, why: '分层架构要求逐层、面向接口依赖；跨层直连数据库破坏了层次隔离与可替换性。' }
}
const SETS = [
  { key: 'cur', title: '业务实体识别', sub: '当前步骤 · 推荐', node: 'KT10-1', dot: 'var(--acc)', subColor: 'var(--acc)', accent: true, qids: ['be1', 'be2', 'be3'] },
  { key: 'gap', title: '领域类图绘制', sub: '你的卡点', node: 'KT10-2', dot: 'var(--warn)', subColor: 'var(--warn)', accent: false, qids: ['cd1', 'cd2', 'cd3'] },
  { key: 'wrong', title: '错题重练', sub: '上次做错', node: 'KT07-3', dot: 'var(--mut)', subColor: 'var(--mut)', accent: false, qids: ['uc', 'arch'] }
]
const PATH_DEF = [
  { st: 'done', node: 'KT07-2', mark: '✓', name: '用例图绘制', tag: '需求确定 · 根因', status: '已完成 · 掌握度 48% → 81%', whyLabel: '为什么先学', why: '参与者与系统边界识别是实体提取的直接基础，根因不补，后面每步都是空中楼阁。', chips: ['视频 · 用例建模精讲', '练习 · 边界识别 8/8 对'] },
  { st: 'current', node: 'KT10-1', mark: '2', name: '业务实体识别', tag: '系统分析', status: '当前步骤 · 掌握度 52%', whyLabel: '为什么学这个', why: '类图画不出来，多半是实体没提对。这一步教你从用例与业务描述中提取候选实体，是领域类图的全部原材料。', resRows: [
    { tag: '视频', name: '从用例到实体：提取的三条线索', meta: '看到 06:42', accent: true },
    { tag: '讲义', name: '名词分析法与 CRC 卡片', meta: '12 页', accent: false },
    { tag: '练习', name: '针对练习 · 实体提取 10 题', meta: '约 15 分钟', accent: false }
  ] },
  { st: 'pending', node: 'KT10-2', mark: '3', name: '领域类图绘制', tag: '系统分析 · 你的卡点', status: '待开始 · 掌握度 34%', whyLabel: '为什么学这个', why: '这就是诊断发现的卡点本身。前两步打通后再回来，实体属性与关系建模会顺手得多。', chips: ['视频 · 25 分钟', '讲义 · 16 页', '练习 · 12 题'] },
  { st: 'locked', node: null, mark: '4', name: '突破检验', tag: '', status: '完成前三步后解锁', whyLabel: '检验方式', why: '一组跨越整条回溯链的混合测验（12 题），验证卡点是否真正打通——通过后，地图上这条链会一起染绿。', chips: null }
]
const diagBases = [
  { ord: '一', title: '「领域类图绘制」6 题错 4，错误集中在<span style="color:var(--ink);font-weight:500">实体识别与关系判断</span>', sub: '非画图规范问题，指向更上游的概念理解', pct: '67%', color: 'var(--acc)' },
  { ord: '二', title: '前置点「用例图绘制」测得 48%，失分集中在<span style="color:var(--ink);font-weight:500">参与者与边界识别</span>', sub: '该能力恰是实体提取的直接基础', pct: '48%', color: 'var(--warn)' },
  { ord: '三', title: '更上游的「业务流程分析」已掌握（83%），回溯到此为止', sub: '问题不在业务理解，而在用例建模这一步', pct: '83%', color: 'var(--ok)' }
]
const letters = ['A', 'B', 'C', 'D']

const s = reactive({
  tab: '地图',
  open: props.form === 'a' ? '系统分析' : null,
  view: null,
  sel: null,
  q: '',
  screen: 'app',
  pset: null, pIdx: 0, pPick: null, pReveal: false, pAns: [], pSummary: false
})

// ── 索引 ──
const byId = computed(() => {
  const m = {}
  if (props.data) props.data.nodes.forEach((n) => { m[n.id] = n })
  return m
})
const chMap = computed(() => {
  const m = {}
  if (props.data) props.data.nodes.forEach((n) => { (m[n.chapter] = m[n.chapter] || []).push(n) })
  return m
})
const preds = computed(() => {
  const m = {}
  if (props.data) props.data.edges.forEach((e) => { if (e.type === '前置') (m[e.target] = m[e.target] || []).push(e.source) })
  return m
})

function statusOf(id) { if (id === CURRENT) return 'cur'; const m = MASTERY[id]; return m ? m[1] : 'dim' }
function masteryOf(id) { const m = MASTERY[id]; return m ? m[0] : null }
function shortName(n) { return SHORT[n.id] || n.name }
function chStats(cname) {
  const nodes = chMap.value[cname] || []
  let ok = 0, warn = 0, hasCur = false
  nodes.forEach((n) => { const st = statusOf(n.id); if (st === 'ok') ok++; if (st === 'warn') warn++; if (st === 'cur') hasCur = true })
  return { nodes, ok, warn, hasCur }
}
function dotsOf(code) {
  const map = { g: 'var(--ok)', w: 'var(--warn)', d: 'var(--dim)', c: 'var(--acc)' }
  return code.split('').map((ch) => ({ bg: map[ch] || 'var(--dim)', ring: ch === 'c' ? '0 0 0 2.5px var(--accSoft)' : 'none' }))
}

function select(id) { s.sel = id }
function toggleChapter(name) { s.open = s.open === name ? null : name }
function goMap() { s.tab = '地图'; s.screen = 'app' }

// ── 搜索 ──
const results = computed(() => {
  if (!props.data || !s.q.trim()) return []
  const q = s.q.trim().toLowerCase()
  return props.data.nodes
    .filter((n) => n.name.toLowerCase().indexOf(q) !== -1 || (SHORT[n.id] || '').toLowerCase().indexOf(q) !== -1)
    .slice(0, 5)
    .map((n) => ({ id: n.id, name: shortName(n), chapter: n.chapter, color: PAL[statusOf(n.id)] }))
})
function pickResult(r) {
  s.q = ''; s.sel = r.id; s.tab = '地图'
  if (props.form === 'b') s.view = r.chapter
}

// ── 形态 A：章节卡片 ──
const chaptersA = computed(() => CHAPTERS.map((cname) => {
  const cs = chStats(cname)
  const open = s.open === cname
  return {
    name: cname, isCur: cs.hasCur, open, frac: cs.ok + '/' + cs.nodes.length,
    segs: cs.nodes.map((n) => PAL[statusOf(n.id)]),
    nodes: cs.nodes.map((n) => {
      const sn = statusOf(n.id); const mn = masteryOf(n.id)
      return {
        id: n.id, name: shortName(n), color: PAL[sn],
        ring: sn === 'cur' ? '0 0 0 2.5px var(--bg), 0 0 0 4px var(--acc)' : 'none',
        mTxt: sn === 'cur' ? '当前 · ' + mn + '%' : (mn === null ? '未学' : mn + '%'),
        mColor: sn === 'dim' ? 'var(--mut)' : PAL[sn]
      }
    })
  }
}))

// ── 形态 B：章节星图 ──
const starEdges = computed(() => {
  if (!props.data) return []
  const seen = {}; const out = []
  props.data.edges.forEach((e) => {
    if (e.type !== '前置') return
    const na = byId.value[e.source]; const nb = byId.value[e.target]
    if (!na || !nb || na.chapter === nb.chapter) return
    const key = na.chapter + '>' + nb.chapter
    if (seen[key]) return
    seen[key] = true
    const a = ANCH[na.chapter]; const b = ANCH[nb.chapter]
    if (!a || !b) return
    out.push({ x1: a[0], y1: a[1], x2: b[0], y2: b[1] })
  })
  return out
})
const starNodes = computed(() => CHAPTERS.map((cname, ci) => {
  const pos = ANCH[cname]; const stt = chStats(cname); const count = stt.nodes.length
  const r = Math.min(21, 10 + count * 1.1)
  const fill = (stt.warn > 0 || stt.hasCur) ? PAL.warn : (stt.ok > 0 ? PAL.ok : PAL.dim)
  return { name: cname, x: pos[0], y: pos[1], r, fill, cur: stt.hasCur, frac: stt.ok + '/' + count + ' 已掌握', twinkle: ci % 3 === 0, dur: (4 + ci * 0.6) + 's' }
}))
const subFrac = computed(() => { const cs = chStats(s.view); return cs.ok + '/' + cs.nodes.length + ' 已掌握' })
const subPos = computed(() => {
  const nodes = chMap.value[s.view] || []; const N = nodes.length; const pos = {}
  const cols = N <= 1 ? 1 : (N <= 3 ? N : (N === 4 ? 2 : 3))
  const rows = Math.ceil(N / cols)
  const cx0 = 187, colGap = 117, rowGap = 94
  const yStart = Math.max(104, 240 - (rows - 1) * rowGap / 2)
  nodes.forEach((n, i) => {
    const row = Math.floor(i / cols); const inRow = Math.min(cols, N - row * cols); const idxInRow = i - row * cols
    pos[n.id] = [cx0 - (inRow - 1) * colGap / 2 + idxInRow * colGap, yStart + row * rowGap]
  })
  return pos
})
const subEdges = computed(() => {
  if (!props.data || !s.view) return []
  const pos = subPos.value; const out = []
  props.data.edges.forEach((e) => { const a = pos[e.source]; const b = pos[e.target]; if (a && b) out.push({ x1: a[0], y1: a[1], x2: b[0], y2: b[1] }) })
  return out
})
const subNodes = computed(() => {
  if (!s.view) return []
  const nodes = chMap.value[s.view] || []; const pos = subPos.value
  return nodes.map((n) => {
    const st = statusOf(n.id); const r = n.is_key ? 12 : 9.5; const mv = masteryOf(n.id)
    return { id: n.id, x: pos[n.id][0], y: pos[n.id][1], r, fill: PAL[st], cur: st === 'cur', short: shortName(n), mTxt: mv === null ? '未学' : mv + '%' }
  })
})

// ── 路径 ──
const pathSteps = computed(() => PATH_DEF.map((d) => {
  const done = d.st === 'done', cur = d.st === 'current', locked = d.st === 'locked'
  const outline = d.st === 'pending' || locked
  const hasChips = !!(d.chips && d.chips.length)
  return {
    mark: d.mark, name: d.name, tag: d.tag, status: d.status, why: d.why, whyLabel: d.whyLabel, node: d.node,
    markBg: done ? 'var(--ok)' : (cur ? 'var(--acc)' : 'var(--bg)'),
    markBorder: outline ? '2px solid var(--line)' : 'none',
    markColor: outline ? 'var(--mut)' : '#FFFDF8',
    markAnim: cur ? 'wjmPulse 2.4s ease-out infinite' : 'none',
    cardBorder: cur ? '1.5px solid var(--acc)' : (locked ? '1px dashed var(--line)' : '1px solid var(--line)'),
    cardOpacity: outline ? 0.85 : 1,
    nameColor: done ? 'var(--mut)' : 'var(--ink)',
    nameDeco: done ? 'line-through' : 'none',
    statusColor: done ? 'var(--ok)' : (cur ? 'var(--acc)' : 'var(--mut)'),
    whyLabelColor: done ? 'var(--ok)' : (cur ? 'var(--acc)' : 'var(--ink)'),
    whyMb: (cur || hasChips) ? '13px' : '0',
    isCurrent: cur, chips: d.chips || [],
    resRows: (d.resRows || []).map((r) => ({ tag: r.tag, name: r.name, meta: r.meta, tagColor: r.accent ? 'var(--acc)' : 'var(--mut)', tagBorder: r.accent ? 'var(--acc)' : 'var(--line)' }))
  }
}))

// ── 练习 ──
const prac = computed(() => {
  if (s.pset == null) {
    return {
      mode: 'hub',
      sets: SETS.map((set) => ({ key: set.key, title: set.title, sub: set.sub, subColor: set.subColor, dot: set.dot, accent: set.accent, meta: set.qids.length + ' 题 · 约 ' + (set.qids.length * 2) + ' 分钟' }))
    }
  }
  const set = SETS.find((x) => x.key === s.pset)
  const total = set.qids.length
  if (s.pSummary) {
    const correct = s.pAns.filter(Boolean).length
    const from = masteryOf(set.node) || 40
    const delta = correct * 3
    const to = Math.min(from + delta, 99)
    return { mode: 'done', doneScore: correct + ' / ' + total, doneRate: Math.round(correct / total * 100) + '%', doneNode: set.title, doneFrom: from + '%', doneTo: to + '%', doneToText: to + '%', doneDelta: delta > 0 ? '+' + delta : '±0' }
  }
  const idx = s.pIdx
  const q = QB[set.qids[idx]]
  const pick = s.pPick
  const reveal = s.pReveal
  const isCorrect = reveal && pick === q.ans
  return {
    mode: 'quiz', pick, revealed: reveal,
    qKt: q.kt, qDiff: q.diff, qText: q.text, qNo: '第 ' + (idx + 1) + ' / ' + total + ' 题',
    progress: Math.round((idx + (reveal ? 1 : 0)) / total * 100) + '%',
    opts: q.opts.map((t, i) => {
      const seld = pick === i; const corr = reveal && i === q.ans; const wrong = reveal && seld && i !== q.ans
      return {
        letter: letters[i], text: t,
        bg: corr ? 'var(--okSoft)' : (wrong ? 'var(--accSoft)' : (seld && !reveal ? 'var(--accSoft)' : 'var(--card)')),
        border: corr ? '1.5px solid var(--ok)' : (wrong ? '1.5px solid var(--acc)' : (seld && !reveal ? '1.5px solid var(--acc)' : '1px solid var(--line)')),
        letterBg: corr ? 'var(--ok)' : ((wrong || (seld && !reveal)) ? 'var(--acc)' : 'transparent'),
        letterColor: (corr || wrong || (seld && !reveal)) ? '#FFFDF8' : 'var(--mut)',
        letterBorder: (corr || wrong || (seld && !reveal)) ? 'none' : '1px solid var(--line)',
        textColor: (corr || wrong || (seld && !reveal)) ? 'var(--ink)' : (reveal ? 'var(--mut)' : 'var(--ink)'),
        icon: corr ? '✓' : (wrong ? '✗' : ''),
        iconColor: corr ? 'var(--ok)' : (wrong ? 'var(--acc)' : 'transparent'),
        cursor: reveal ? 'default' : 'pointer'
      }
    }),
    fbBg: isCorrect ? 'var(--okSoft)' : 'var(--accSoft)',
    fbBorder: isCorrect ? 'var(--ok)' : 'var(--acc)',
    fbColor: isCorrect ? 'var(--ok)' : 'var(--acc)',
    fbTitle: isCorrect ? '回答正确' : ('正确答案是 ' + letters[q.ans]),
    fbWhy: q.why,
    nextLabel: idx === total - 1 ? '查看结果' : '下一题'
  }
})
function startSet(key) { s.pset = key; s.pIdx = 0; s.pPick = null; s.pReveal = false; s.pAns = []; s.pSummary = false }
function exitSet() { s.pset = null }
function pickOption(i) { if (!s.pReveal) s.pPick = i }
function submitAnswer() {
  if (s.pPick == null) return
  const set = SETS.find((x) => x.key === s.pset)
  const q = QB[set.qids[s.pIdx]]
  s.pReveal = true
  s.pAns = s.pAns.concat([s.pPick === q.ans])
}
function nextQuestion() {
  const set = SETS.find((x) => x.key === s.pset)
  if (s.pIdx === set.qids.length - 1) s.pSummary = true
  else { s.pIdx++; s.pPick = null; s.pReveal = false }
}

// ── 我的 ──
const comparePanels = computed(() => [
  { title: '6月10日 · 诊断时', sub: '已掌握 16 · 薄弱 6', chapters: CH.map((c) => ({ name: c.name, dots: dotsOf(c.b) })) },
  { title: '今天', sub: '已掌握 22 · 薄弱 5', chapters: CH.map((c) => ({ name: c.name, dots: dotsOf(c.a) })) }
])

// ── 抽屉 ──
const sel = computed(() => (s.sel ? byId.value[s.sel] : null))
const selStatus = computed(() => (sel.value ? statusOf(sel.value.id) : 'dim'))
const selStatusColor = computed(() => PAL[selStatus.value])
const selStatusBg = computed(() => STATUS_BG[selStatus.value])
const selStatusLabel = computed(() => STATUS_LABEL[selStatus.value])
const selMastery = computed(() => (sel.value ? masteryOf(sel.value.id) : null))
const selMasteryText = computed(() => (selMastery.value === null ? '未开始' : selMastery.value + '%'))
const selMasteryPct = computed(() => (selMastery.value === null ? 0 : selMastery.value) + '%')
const selDiffDots = computed(() => (sel.value ? '●●●●●'.slice(0, sel.value.difficulty) + '○○○○○'.slice(0, 5 - sel.value.difficulty) : ''))
const selCta = computed(() => (selStatus.value === 'dim' ? '开始学习' : '开始针对练习'))
const selPrereqs = computed(() => {
  if (!sel.value) return []
  return (preds.value[sel.value.id] || []).map((pid) => {
    const pn = byId.value[pid]; const ps = statusOf(pid)
    return { id: pid, name: shortName(pn), color: PAL[ps], statusLabel: STATUS_LABEL[ps] }
  })
})
const selResources = computed(() => {
  if (!sel.value) return []
  return [{ t: '课件', n: '《软件工程》讲义 · ' + sel.value.chapter }, { t: '视频', n: shortName(sel.value) + ' · 精讲视频' }]
})
</script>

<style scoped>
.wjm-search:focus { border-color: var(--mut) !important; }
.wjm-press:active { background: var(--card2); }
</style>
