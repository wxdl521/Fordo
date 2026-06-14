<template>
  <div :style="{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: 'var(--bg)', color: 'var(--ink)' }">

    <!-- 顶栏 -->
    <div :style="{ height: '56px', flex: 'none', display: 'flex', alignItems: 'center', gap: '14px', padding: '0 20px', borderBottom: '1px solid var(--line)', transition: 'border-color 0.35s' }">
      <span :style="{ fontFamily: serif, fontSize: '20px', fontWeight: 600, letterSpacing: '3px', whiteSpace: 'nowrap', flex: 'none' }">问津</span>
      <div :style="{ width: '1px', height: '16px', background: 'var(--line)', flex: 'none' }"></div>
      <span v-show="width >= 560" :style="{ fontSize: '13.5px', fontWeight: 500, whiteSpace: 'nowrap', flex: 'none' }">软件工程 · 入口诊断</span>
      <div :style="{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '10px' }">
        <span v-show="width >= 480" :style="{ fontSize: '12px', color: 'var(--mut)', whiteSpace: 'nowrap' }">已答 {{ answeredCount }} · 跳过 {{ skippedCount }}</span>
        <ThemeToggle small />
        <router-link to="/map" class="wj-hover-card2" :style="{ fontSize: '12.5px', color: 'var(--mut)', textDecoration: 'none', padding: '6px 11px', border: '1px solid var(--line)', borderRadius: '8px', whiteSpace: 'nowrap' }">保存并退出</router-link>
      </div>
    </div>

    <!-- 进度条 -->
    <div :style="{ height: '2px', flex: 'none', background: 'var(--card2)', transition: 'background-color 0.35s' }">
      <div :style="{ height: '100%', width: progressPct, background: 'var(--acc)', transition: 'width 0.35s ease' }"></div>
    </div>

    <!-- 答题区 -->
    <div v-if="!done" :style="{ flex: 1, width: '100%', maxWidth: '720px', margin: '0 auto', boxSizing: 'border-box', padding: mainPad, display: 'flex', flexDirection: 'column' }">
      <div :style="{ display: 'flex', alignItems: 'baseline', gap: '12px', marginBottom: '14px' }">
        <span :style="{ fontFamily: serif, fontSize: '15px', color: 'var(--mut)' }">第 {{ idx + 1 }} 题<span :style="{ opacity: 0.55 }"> · 共 25 题</span></span>
        <span :style="{ fontSize: '12px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '2px 10px' }">{{ q.c }}</span>
      </div>

      <div :style="{ height: '4px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden', marginBottom: '26px' }">
        <div :style="{ height: '100%', width: progressPct, background: 'var(--acc)', borderRadius: '99px', transition: 'width 0.4s cubic-bezier(0.22,1,0.36,1)' }"></div>
      </div>

      <div :style="{ fontSize: qSize, fontWeight: 500, lineHeight: 1.65, marginBottom: '28px', textWrap: 'pretty' }">{{ q.q }}</div>

      <div :style="{ display: 'flex', flexDirection: 'column', gap: '10px', marginBottom: '32px' }">
        <div
          v-for="(opt, i) in q.o" :key="i"
          @click="pick(i)"
          class="wj-opt"
          :style="{ display: 'flex', alignItems: 'center', gap: '14px', minHeight: '52px', boxSizing: 'border-box', padding: '13px 16px', background: sel === i ? 'var(--accSoft)' : 'var(--card)', border: '1.5px solid ' + (sel === i ? 'var(--acc)' : 'var(--line)'), borderRadius: '11px', cursor: 'pointer', transition: 'background-color 0.18s, border-color 0.18s' }"
        >
          <span :style="{ width: '26px', height: '26px', flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', border: '1.5px solid ' + (sel === i ? 'var(--acc)' : 'var(--line)'), borderRadius: '50%', fontSize: '12.5px', color: sel === i ? '#FFFDF8' : 'var(--mut)', background: sel === i ? 'var(--acc)' : 'transparent', transition: 'background-color 0.18s, border-color 0.18s, color 0.18s' }">{{ letters[i] }}</span>
          <span :style="{ fontSize: '14.5px', lineHeight: 1.55 }">{{ opt }}</span>
        </div>
      </div>

      <div :style="{ marginTop: 'auto', display: 'flex', alignItems: 'center', gap: '12px', paddingBottom: '8px' }">
        <button @click="goPrev" class="wj-hover-card2" :style="{ height: '42px', padding: '0 18px', background: 'transparent', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--mut)', fontSize: '13px', cursor: 'pointer', whiteSpace: 'nowrap', visibility: idx === 0 ? 'hidden' : 'visible' }">上一题</button>
        <button @click="skip" class="wj-hover-card2" :style="{ marginLeft: 'auto', height: '42px', padding: '0 16px', background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '13px', cursor: 'pointer', whiteSpace: 'nowrap', borderRadius: '9px' }">不确定，跳过</button>
        <button @click="goNext" :style="{ height: '42px', padding: '0 30px', background: hasSel ? 'var(--acc)' : 'var(--card2)', border: 'none', borderRadius: '9px', color: hasSel ? '#FFFDF8' : 'var(--mut)', fontSize: '14px', fontWeight: 500, cursor: hasSel ? 'pointer' : 'not-allowed', whiteSpace: 'nowrap', transition: 'background-color 0.2s, color 0.2s' }">{{ last ? '完成诊断' : '下一题' }}</button>
      </div>

      <div :style="{ textAlign: 'center', fontSize: '12px', color: 'var(--mut)', opacity: 0.75, paddingBottom: '18px' }">这不是考试——答错不扣分，跳过也是有用的信号。</div>
    </div>

    <!-- 完成态 -->
    <div v-else :style="{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '40px 24px 80px', textAlign: 'center', animation: 'wjFadeUp 0.5s ease both' }">
      <div :style="{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--acc)', color: '#FFFDF8', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '24px', marginBottom: '28px' }">✓</div>
      <div :style="{ fontFamily: serif, fontSize: doneTitleSize, fontWeight: 600, lineHeight: 1.5, marginBottom: '14px' }">二十五题，足以看清来路</div>
      <div :style="{ fontSize: '14px', color: 'var(--mut)', lineHeight: 1.8, maxWidth: '420px', marginBottom: '36px' }">你答了 {{ answeredCount }} 题，跳过 {{ skippedCount }} 题。问津会沿知识图谱回溯每一处失分，找到真正的根因。</div>
      <router-link to="/result" class="wj-btn-acc" :style="{ height: '46px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 34px', background: 'var(--acc)', borderRadius: '10px', color: '#FFFDF8', fontSize: '14.5px', fontWeight: 500, textDecoration: 'none' }">查看诊断结果</router-link>
      <button @click="restart" class="wj-underline" :style="{ marginTop: '18px', background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">重新作答</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useViewport } from '../composables/useViewport.js'

const serif = "'Noto Serif SC', serif"
const letters = ['A', 'B', 'C', 'D']
const { width } = useViewport()

const QUESTIONS = [
  { c: '软件工程概述', q: '瀑布模型最显著的特点是什么？', o: ['各阶段顺序进行，前一阶段完成后才进入下一阶段', '每次迭代都交付可运行的软件增量', '通过快速原型不断澄清需求', '开发与运维一体化、持续交付'] },
  { c: '软件工程概述', q: '客户需求不清晰且变化频繁的项目，更适合采用哪种过程模型？', o: ['瀑布模型', '敏捷开发', 'V 模型', '大爆炸式开发'] },
  { c: '软件工程概述', q: '增量模型与原型模型的本质区别在于：', o: ['增量交付的每个版本都是可运行的正式产品的一部分', '增量模型不需要需求分析', '原型模型只能用于界面设计', '两者没有区别'] },
  { c: '需求确定', q: '「系统响应时间不超过 2 秒」属于哪类需求？', o: ['非功能需求', '功能需求', '业务需求', '用户需求'] },
  { c: '需求确定', q: '绘制业务流程图时，泳道（Swimlane）的作用是：', o: ['区分不同角色或部门的职责范围', '表示流程的时间顺序', '标注数据的存储位置', '划分系统的模块边界'] },
  { c: '需求确定', q: '活动图中的菱形节点表示：', o: ['判断/分支', '并发开始', '活动终止', '对象状态'] },
  { c: '需求确定', q: '识别用例参与者（Actor）时，下列哪项不应作为参与者？', o: ['系统内部的一个功能模块', '使用系统的注册用户', '与系统交互的外部支付平台', '定时触发任务的时钟'] },
  { c: '需求确定', q: '确定系统边界的主要目的是：', o: ['明确哪些功能由系统负责、哪些由外部完成', '划分开发团队的分工', '确定数据库的表结构', '估算项目成本'] },
  { c: '需求确定', q: '用例之间的 «include» 关系表示：', o: ['一个用例的执行必然包含另一个用例的行为', '一个用例在特定条件下扩展另一个用例', '两个用例由同一参与者发起', '两个用例共享同一界面'] },
  { c: '需求确定', q: '用例描述中的「基本事件流」指的是：', o: ['最常见、最理想情况下的交互步骤序列', '所有异常情况的处理步骤', '系统启动时的初始化流程', '用例之间的调用顺序'] },
  { c: '系统分析', q: '用名词分析法从需求文本中提取候选实体时，应优先保留：', o: ['在业务中有持久状态和职责的名词', '所有出现过的名词', '只有界面上显示的名词', '动词对应的名词化形式'] },
  { c: '系统分析', q: 'CRC 卡片中的 R（Responsibility）指的是：', o: ['类承担的职责', '类的访问权限', '类的存储位置', '类的版本记录'] },
  { c: '系统分析', q: '领域类图中「一个订单包含多个订单项」应表示为：', o: ['Order 与 OrderItem 之间 1 对多的组合关系', 'Order 继承 OrderItem', 'OrderItem 依赖 Order 的接口', 'Order 与 OrderItem 互为关联类'] },
  { c: '系统分析', q: '聚合与组合的关键区别是：', o: ['组合中部分的生命周期依附于整体，聚合则不然', '聚合的连线用实心菱形表示', '组合只能用于抽象类', '两者只是画法不同，语义相同'] },
  { c: '系统分析', q: '顺序图主要用于描述：', o: ['对象之间按时间顺序的消息交互', '类的静态结构', '系统的部署拓扑', '数据的存储格式'] },
  { c: '系统分析', q: '状态图最适合建模哪类对象？', o: ['行为随状态显著变化的对象（如订单、审批单）', '只有属性没有行为的数据对象', '工具类与辅助函数', '用户界面布局'] },
  { c: '系统设计', q: '分层架构（如表示层/业务层/数据层）的核心原则是：', o: ['上层依赖下层，下层不感知上层', '任意两层可以互相调用', '层数越多性能越好', '每层必须部署在不同服务器'] },
  { c: '系统设计', q: '「高内聚、低耦合」中，高内聚指的是：', o: ['模块内部各元素紧密围绕同一职责', '模块之间共享尽可能多的全局变量', '一个模块实现尽可能多的功能', '模块之间调用层级深'] },
  { c: '系统设计', q: '下列哪种做法会增加模块间耦合？', o: ['模块间直接读写彼此的内部数据', '通过明确定义的接口通信', '用参数传递代替全局变量', '隐藏模块内部实现细节'] },
  { c: '实现与测试', q: '编码规范的首要目的是：', o: ['提高代码的可读性与可维护性', '让程序运行得更快', '减少代码行数', '方便代码加密'] },
  { c: '实现与测试', q: '黑盒测试与白盒测试的区别在于：', o: ['是否依据程序内部结构设计测试用例', '是否需要运行程序', '是否由开发人员执行', '是否使用自动化工具'] },
  { c: '实现与测试', q: '对输入「1–100 的整数」做等价类划分，合理的划分是：', o: ['有效类：1–100；无效类：小于 1、大于 100、非整数', '有效类：所有整数；无效类：所有小数', '每个整数单独一类', '只需测试 50 一个值'] },
  { c: '实现与测试', q: '边界值分析建议优先测试的输入是：', o: ['0、1、100、101 这类边界及其邻近值', '50 等中间值', '随机抽取的任意值', '用户最常输入的值'] },
  { c: '实现与测试', q: '单元测试与集成测试的关系是：', o: ['先验证单个模块正确，再验证模块协作正确', '集成测试通过后才做单元测试', '两者测试内容完全相同', '做了集成测试就无需单元测试'] },
  { c: '实现与测试', q: '上线后为适应新的操作系统版本而修改软件，属于哪类维护？', o: ['适应性维护', '纠错性维护', '完善性维护', '预防性维护'] }
]

const idx = ref(0)
const answers = ref({})
const skipped = ref({})
const done = ref(false)

const total = QUESTIONS.length
const q = computed(() => QUESTIONS[idx.value])
const sel = computed(() => answers.value[idx.value])
const hasSel = computed(() => typeof sel.value === 'number')
const last = computed(() => idx.value === total - 1)
const answeredCount = computed(() => Object.keys(answers.value).length)
const skippedCount = computed(() => Object.keys(skipped.value).filter((k) => !(k in answers.value)).length)
const progressPct = computed(() => Math.round(((done.value ? total : idx.value + 1) / total) * 100) + '%')

const narrow = computed(() => width.value < 640)
const mainPad = computed(() => (narrow.value ? '28px 18px 12px' : '48px 24px 16px'))
const qSize = computed(() => (narrow.value ? '17px' : '20px'))
const doneTitleSize = computed(() => (narrow.value ? '24px' : '30px'))

function pick(i) {
  answers.value = { ...answers.value, [idx.value]: i }
}
function goNext() {
  if (!hasSel.value) return
  if (last.value) done.value = true
  else idx.value++
}
function goPrev() {
  if (idx.value > 0) idx.value--
}
function skip() {
  skipped.value = { ...skipped.value, [idx.value]: true }
  if (last.value) done.value = true
  else idx.value++
}
function restart() {
  idx.value = 0
  answers.value = {}
  skipped.value = {}
  done.value = false
}
</script>

<style scoped>
.wj-opt:hover {
  border-color: var(--mut) !important;
}
</style>
