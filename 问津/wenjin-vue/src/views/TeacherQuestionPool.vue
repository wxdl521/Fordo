<template>
  <div :style="{ minHeight: '100vh', minWidth: '1100px', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)', transition: 'background-color 0.35s, color 0.35s' }">

    <TopBar teacher compact subtitle="软件工程 · 题目审核池">
      <NavLink to="/teacher/graph">图谱审核工作台</NavLink>
      <NavLink to="/teacher/dashboard">学情看板</NavLink>
    </TopBar>

    <div :style="{ flex: 1, width: '100%', maxWidth: '1080px', margin: '0 auto', boxSizing: 'border-box', padding: '28px 24px 48px' }">

      <!-- 工具条 -->
      <div :style="{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px', flexWrap: 'wrap' }">
        <div :style="{ display: 'flex', alignItems: 'center', padding: '3px', gap: '2px', border: '1px solid var(--line)', borderRadius: '10px', transition: 'border-color 0.35s' }">
          <button v-for="t in tabDefs" :key="t.key" @click="setTab(t.key)" :style="segStyle(tab === t.key, '0 14px')">{{ t.label }}</button>
        </div>
        <div :style="{ display: 'flex', alignItems: 'center', gap: '8px' }">
          <span :style="{ fontSize: '12px', color: 'var(--mut)' }">置信度</span>
          <div :style="{ display: 'flex', alignItems: 'center', padding: '3px', gap: '2px', border: '1px solid var(--line)', borderRadius: '10px', transition: 'border-color 0.35s' }">
            <button v-for="c in CONFS" :key="c" @click="setConf(c)" :style="segStyle(conf === c, '0 13px')">{{ c }}</button>
          </div>
        </div>
        <span :style="{ marginLeft: 'auto', fontSize: '12.5px', color: 'var(--mut)' }">本批 12 题 · 由 v0.3 图谱节点描述生成</span>
      </div>

      <!-- 批量操作条 -->
      <div v-if="checkedCount > 0" :style="{ display: 'flex', alignItems: 'center', gap: '12px', background: 'var(--card)', border: '1.5px solid var(--acc)', borderRadius: '11px', padding: '10px 16px', marginBottom: '14px', transition: 'background-color 0.35s' }">
        <span :style="{ fontSize: '13px', fontWeight: 500 }">已选 {{ checkedCount }} 题</span>
        <button @click="batch('passed')" class="wj-btn-acc" :style="{ height: '34px', padding: '0 20px', background: 'var(--acc)', border: 'none', borderRadius: '8px', color: '#FFFDF8', fontSize: '12.5px', fontWeight: 500, cursor: 'pointer' }">批量通过</button>
        <button @click="batch('rejected')" class="wj-hover-card2" :style="{ height: '34px', padding: '0 18px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '8px', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer' }">批量驳回</button>
        <button @click="checked = {}" class="wj-underline" :style="{ marginLeft: 'auto', background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">取消选择</button>
      </div>

      <!-- 全选行 -->
      <div v-if="tab === 'pending' && list.length" :style="{ display: 'flex', alignItems: 'center', gap: '10px', padding: '0 18px 10px' }">
        <div @click="toggleAll" :style="{ width: '17px', height: '17px', flex: 'none', boxSizing: 'border-box', border: '1.5px solid ' + (allChecked ? 'var(--acc)' : 'var(--line)'), background: allChecked ? 'var(--acc)' : 'transparent', borderRadius: '5px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#FFFDF8', fontSize: '11px', transition: 'background-color 0.18s, border-color 0.18s' }">{{ allChecked ? '✓' : '' }}</div>
        <span @click="toggleAll" :style="{ fontSize: '12.5px', color: 'var(--mut)', cursor: 'pointer' }">全选当前筛选结果</span>
      </div>

      <!-- 题目列表 -->
      <div :style="{ display: 'flex', flexDirection: 'column', gap: '12px' }">
        <div v-for="(q, idx) in list" :key="q.id" :style="{ background: 'var(--card)', border: '1.5px solid ' + (checked[q.id] ? 'var(--acc)' : 'var(--line)'), borderRadius: '13px', padding: '18px 20px', boxSizing: 'border-box', display: 'flex', gap: '14px', animation: 'wjFadeUp 0.5s cubic-bezier(0.22,1,0.36,1) both', animationDelay: (Math.min(idx, 8) * 0.045) + 's', transition: 'background-color 0.35s, border-color 0.2s' }">
          <div @click="toggleOne(q)" :style="{ width: '17px', height: '17px', flex: 'none', boxSizing: 'border-box', marginTop: '3px', border: '1.5px solid ' + (checked[q.id] ? 'var(--acc)' : 'var(--line)'), background: checked[q.id] ? 'var(--acc)' : 'transparent', borderRadius: '5px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#FFFDF8', fontSize: '11px', transition: 'background-color 0.18s, border-color 0.18s', visibility: q.status === 'pending' ? 'visible' : 'hidden' }">{{ checked[q.id] ? '✓' : '' }}</div>
          <div :style="{ flex: 1, minWidth: 0 }">
            <div :style="{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px', flexWrap: 'wrap' }">
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '2px 10px' }">{{ q.kt }}</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)' }">单选 · 难度 {{ q.diff }}</span>
              <span :style="{ fontSize: '11.5px', color: q.conf < 70 ? 'var(--warn)' : 'var(--mut)', background: q.conf < 70 ? 'var(--warnSoft)' : 'var(--card2)', borderRadius: '999px', padding: '2px 9px' }">置信度 {{ q.conf }}%</span>
              <span :style="{ marginLeft: 'auto', fontSize: '11px', color: 'var(--mut)', opacity: 0.7 }">{{ q.id.toUpperCase() }}</span>
            </div>
            <div :style="{ fontSize: '14.5px', fontWeight: 500, lineHeight: 1.7, marginBottom: '12px', textWrap: 'pretty' }">{{ q.text }}</div>
            <div :style="{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '7px 18px', marginBottom: '12px' }">
              <div v-for="(o, i) in q.opts" :key="i" :style="{ display: 'flex', alignItems: 'baseline', gap: '8px', fontSize: '13px', color: i === q.ans ? 'var(--ink)' : 'var(--mut)' }">
                <span :style="{ flex: 'none', fontWeight: i === q.ans ? 600 : 400 }">{{ letters[i] }}.</span>
                <span :style="{ lineHeight: 1.6 }">{{ o }}</span>
                <span v-if="i === q.ans" :style="{ flex: 'none', color: 'var(--ok)' }">✓</span>
              </div>
            </div>
            <div v-if="q.flag" :style="{ fontSize: '12px', color: 'var(--warn)', background: 'var(--warnSoft)', borderRadius: '8px', padding: '8px 12px', marginBottom: '12px', lineHeight: 1.6 }">⚠ {{ q.flag }}</div>
            <div :style="{ display: 'flex', alignItems: 'center', gap: '10px' }">
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)', opacity: 0.85 }">{{ q.source }}</span>
              <div v-if="q.status === 'pending'" :style="{ marginLeft: 'auto', display: 'flex', gap: '8px' }">
                <button @click="setItem(q.id, 'passed')" class="wj-btn-acc" :style="{ height: '32px', padding: '0 18px', background: 'var(--acc)', border: 'none', borderRadius: '8px', color: '#FFFDF8', fontSize: '12px', fontWeight: 500, cursor: 'pointer' }">通过</button>
                <button @click="setItem(q.id, 'rejected')" class="wj-hover-card2" :style="{ height: '32px', padding: '0 16px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '8px', color: 'var(--mut)', fontSize: '12px', cursor: 'pointer' }">驳回</button>
              </div>
              <template v-else>
                <span :style="{ marginLeft: 'auto', fontSize: '12px', color: q.status === 'passed' ? 'var(--ok)' : 'var(--mut)' }">{{ q.status === 'passed' ? '已通过' : '已驳回' }}</span>
                <button @click="setItem(q.id, 'pending')" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '11.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">撤销</button>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!list.length" :style="{ textAlign: 'center', padding: '64px 20px' }">
        <div :style="{ fontFamily: serif, fontSize: '17px', letterSpacing: '3px', marginBottom: '8px' }">此处无题</div>
        <div :style="{ fontSize: '12.5px', color: 'var(--mut)' }">当前筛选条件下没有题目。</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import TopBar from '../components/TopBar.vue'
import NavLink from '../components/NavLink.vue'

const serif = "'Noto Serif SC', serif"
const letters = ['A', 'B', 'C', 'D']
const CONFS = ['全部', '≥85%', '70–84%', '<70%']

const tab = ref('pending')
const conf = ref('全部')
const checked = ref({})
const items = ref([
  { id: 'q1', kt: '业务实体识别', diff: '●●○', conf: 95, status: 'pending', text: '网上书店需求中「读者可将图书加入购物车」一句，最适合提取为候选业务实体的名词组是：', opts: ['读者、图书、购物车', '加入、购物车', '需求、系统、功能', '读者、操作、流程'], ans: 0, source: '由 KT10-2 描述与教材 6.2 节例题生成 · 今天 09:14' },
  { id: 'q2', kt: '领域类图绘制', diff: '●●●', conf: 92, status: 'pending', text: '「一个订单包含多个订单项，订单项不能脱离订单单独存在」，正确的建模方式是：', opts: ['Order 与 OrderItem 之间的组合关系', 'Order 与 OrderItem 之间的聚合关系', 'OrderItem 继承 Order', '两者之间的依赖关系'], ans: 0, source: '由 KT10-1 描述生成 · 今天 09:14' },
  { id: 'q3', kt: '用例描述', diff: '●●○', conf: 88, status: 'pending', text: '编写用例描述时，「会员卡余额不足导致支付失败」的处理步骤应写入：', opts: ['备选事件流', '基本事件流', '前置条件', '用例目标'], ans: 0, source: '由 KT07-2 描述生成 · 今天 09:14' },
  { id: 'q4', kt: '等价类划分法', diff: '●●○', conf: 86, status: 'pending', text: '某输入框要求输入 6–18 位的密码，下列哪组等价类划分是合理的？', opts: ['有效类：6–18 位；无效类：少于 6 位、多于 18 位', '有效类：所有字符串；无效类：空串', '每种长度单独一类', '只需测试 12 位一种情况'], ans: 0, source: '由 KT23-1 描述生成 · 昨天 16:40' },
  { id: 'q5', kt: '分层架构', diff: '●●●', conf: 79, status: 'pending', text: '在典型三层架构中，业务逻辑层直接读写数据库表，违反了哪条原则？', opts: ['各层只依赖其直接下层的接口', '上层不得调用下层', '层间必须异步通信', '每层必须独立部署'], ans: 0, source: '由 KT15-1 描述生成 · 昨天 16:40' },
  { id: 'q6', kt: 'OO设计原则', diff: '●●●', conf: 76, status: 'pending', text: '「新增一种折扣方式时，不应修改既有结算代码，而是通过扩展实现」体现了哪条原则？', opts: ['开闭原则', '单一职责原则', '接口隔离原则', '迪米特法则'], ans: 0, source: '由 KT18-1 描述生成 · 昨天 16:40' },
  { id: 'q7', kt: '边界值分析法', diff: '●●○', conf: 71, status: 'pending', text: '对「1–100 的整数」做边界值分析，优先选取的测试输入是：', opts: ['0、1、100、101', '50、51、52', '-100、200、300', '任意随机整数'], ans: 0, source: '由 KT23-2 描述生成 · 昨天 16:40' },
  { id: 'q8', kt: '用例关系', diff: '●●●', conf: 64, status: 'pending', text: '「打印回执」只在用户勾选发票时才执行，它与「支付订单」的关系是：', opts: ['«extend» 扩展关系', '«include» 包含关系', '泛化关系', '关联关系'], ans: 0, flag: '选项 A 与 B 表述接近，建议核对干扰项区分度后再通过。', source: '由 KT07-3 描述生成 · 昨天 16:40' },
  { id: 'q9', kt: '测试概念与过程', diff: '●○○', conf: 58, status: 'pending', text: '下列关于回归测试的说法，正确的是：', opts: ['修改代码后重跑既有用例，确认未引入新缺陷', '只在首次集成时执行', '由最终用户在生产环境执行', '与单元测试完全相同'], ans: 0, flag: '题干来源于教材脚注，证据较弱；干扰项 C 接近验收测试定义，易混淆。', source: '由 KT22 描述生成 · 昨天 16:40' },
  { id: 'q10', kt: '传统过程模型', diff: '●●○', conf: 91, status: 'passed', text: '瀑布模型中，需求变更代价最高的阶段是：', opts: ['运维阶段', '需求分析阶段', '设计阶段', '编码阶段'], ans: 0, source: '由 KT02 描述生成 · 6月10日' },
  { id: 'q11', kt: '现代过程模型', diff: '●●○', conf: 89, status: 'passed', text: '敏捷开发中「每两周交付一个可运行版本」体现的是：', opts: ['迭代式增量交付', '大爆炸式集成', '阶段化评审', '文档驱动开发'], ans: 0, source: '由 KT03 描述生成 · 6月10日' },
  { id: 'q12', kt: '需求概念与目标', diff: '●○○', conf: 49, status: 'rejected', text: '软件需求就是用户提出的全部想法，开发团队应全部实现。这种说法：', opts: ['错误', '正确', '部分正确', '无法判断'], ans: 0, source: '由 KT04 描述生成 · 6月10日' }
])

const counts = computed(() => {
  const c = { pending: 0, passed: 0, rejected: 0 }
  items.value.forEach((q) => c[q.status]++)
  return c
})
const tabDefs = computed(() => [
  { key: 'pending', label: '待审核 ' + counts.value.pending },
  { key: 'passed', label: '已通过 ' + counts.value.passed },
  { key: 'rejected', label: '已驳回 ' + counts.value.rejected }
])

const list = computed(() =>
  items.value
    .filter((q) => {
      if (q.status !== tab.value) return false
      if (conf.value === '≥85%') return q.conf >= 85
      if (conf.value === '70–84%') return q.conf >= 70 && q.conf < 85
      if (conf.value === '<70%') return q.conf < 70
      return true
    })
    .sort((a, b) => b.conf - a.conf)
)

const checkedCount = computed(() => Object.keys(checked.value).length)
const visIds = computed(() => list.value.map((q) => q.id))
const allChecked = computed(() => tab.value === 'pending' && visIds.value.length > 0 && visIds.value.every((id) => checked.value[id]))

function segStyle(on, pad) {
  return { height: '30px', padding: pad, border: 'none', borderRadius: '8px', fontSize: '12.5px', cursor: 'pointer', background: on ? 'var(--card2)' : 'transparent', color: on ? 'var(--ink)' : 'var(--mut)', transition: 'background-color 0.2s, color 0.2s' }
}
function setTab(k) { tab.value = k; checked.value = {} }
function setConf(c) { conf.value = c; checked.value = {} }
function setItem(id, status) {
  items.value = items.value.map((q) => (q.id === id ? { ...q, status } : q))
  const c = { ...checked.value }
  delete c[id]
  checked.value = c
}
function toggleOne(q) {
  if (q.status !== 'pending') return
  const c = { ...checked.value }
  if (c[q.id]) delete c[q.id]; else c[q.id] = true
  checked.value = c
}
function toggleAll() {
  if (allChecked.value) { checked.value = {}; return }
  const c = {}
  visIds.value.forEach((id) => { c[id] = true })
  checked.value = c
}
function batch(status) {
  items.value = items.value.map((q) => (checked.value[q.id] ? { ...q, status } : q))
  checked.value = {}
}
</script>
