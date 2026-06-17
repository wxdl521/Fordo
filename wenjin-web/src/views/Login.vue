<template>
  <div :style="{ height: '100vh', display: 'flex', overflow: 'hidden', background: 'var(--bg)', color: 'var(--ink)' }">

    <!-- 左侧品牌栏 -->
    <div
      v-show="width >= 880"
      :style="{ width: '42%', minWidth: '360px', flex: 'none', boxSizing: 'border-box', borderRight: '1px solid var(--line)', padding: '48px 52px', display: 'flex', flexDirection: 'column', transition: 'border-color 0.35s' }"
    >
      <div :style="{ fontFamily: serif, fontSize: '26px', fontWeight: 600, letterSpacing: '4px' }">问津</div>
      <div :style="{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '36px' }">
        <div :style="{ writingMode: 'vertical-rl', fontFamily: serif, fontSize: '20px', letterSpacing: '12px', color: 'var(--ink)', height: '240px' }">使子路问津焉</div>
        <div :style="{ writingMode: 'vertical-rl', fontSize: '12.5px', letterSpacing: '6px', color: 'var(--mut)', height: '200px' }">不知卡在何处，便来此问路</div>
      </div>
      <div :style="{ fontSize: '12px', color: 'var(--mut)', letterSpacing: '2px' }">知识图谱定位学情 · 回溯诊断根因 · 规划学习路径</div>
    </div>

    <!-- 右侧 -->
    <div :style="{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflowY: 'auto' }">
      <div :style="{ flex: 'none', display: 'flex', justifyContent: 'flex-end', padding: '18px 20px 0' }">
        <ThemeToggle small />
      </div>

      <!-- 登录 -->
      <div v-if="step === 'login'" :style="{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '24px', animation: 'wjFadeUp 0.45s ease both' }">
        <div :style="{ width: '100%', maxWidth: '340px', display: 'flex', flexDirection: 'column' }">
          <div v-show="width < 880" :style="{ fontFamily: serif, fontSize: '24px', fontWeight: 600, letterSpacing: '4px', marginBottom: '8px' }">问津</div>
          <div :style="{ fontFamily: serif, fontSize: '21px', fontWeight: 600, marginBottom: '6px' }">欢迎回来</div>
          <div :style="{ fontSize: '13px', color: 'var(--mut)', marginBottom: '28px' }">用学校账号登录，继续你的路径。</div>
          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">学号</label>
          <input v-model="sid" placeholder="如 2023302481" class="wj-input" :style="inputStyle" />
          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">密码</label>
          <input v-model="pwd" type="password" placeholder="••••••••" class="wj-input" :style="{ ...inputStyle, marginBottom: '24px' }" @keydown.enter="step = 'course'" />
          <button @click="step = 'course'" class="wj-btn-acc" :style="{ height: '46px', background: 'var(--acc)', border: 'none', borderRadius: '10px', color: '#FFFDF8', fontSize: '14.5px', fontWeight: 500, cursor: 'pointer', marginBottom: '16px' }">登 录</button>
          <button @click="step = 'course'" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px', alignSelf: 'center' }">使用演示账号进入</button>
        </div>
      </div>

      <!-- 课程选择 -->
      <div v-else :style="{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '24px', animation: 'wjFadeUp 0.45s ease both' }">
        <div :style="{ width: '100%', maxWidth: '460px', display: 'flex', flexDirection: 'column' }">
          <div :style="{ fontFamily: serif, fontSize: '22px', fontWeight: 600, marginBottom: '6px' }">你好，林晚舟</div>
          <div :style="{ fontSize: '13px', color: 'var(--mut)', marginBottom: '26px' }">选择一门课程进入。</div>

          <div class="wj-course-card" :style="courseCard">
            <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px', flexWrap: 'wrap' }">
              <span :style="{ fontFamily: serif, fontSize: '17px', fontWeight: 600 }">软件工程</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--ok)', background: 'var(--okSoft)', borderRadius: '999px', padding: '3px 10px' }">进行中</span>
              <span :style="{ marginLeft: 'auto', fontSize: '12px', color: 'var(--mut)' }">2026 春 · 王立群</span>
            </div>
            <div :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '14px' }">已掌握 22 / 42 个节点 · 待修薄弱点 5 个</div>
            <div :style="{ height: '5px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden', marginBottom: '16px' }">
              <div :style="{ height: '100%', width: '65%', background: 'var(--ok)', borderRadius: '99px' }"></div>
            </div>
            <div :style="{ display: 'flex', alignItems: 'center', gap: '12px' }">
              <router-link to="/map" class="wj-btn-acc" :style="{ height: '40px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 24px', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, textDecoration: 'none' }">进入课程</router-link>
              <router-link to="/growth" class="wj-underline" :style="{ fontSize: '12.5px', color: 'var(--mut)', textDecoration: 'underline', textUnderlineOffset: '3px' }">成长档案</router-link>
            </div>
          </div>

          <div class="wj-course-card" :style="{ ...courseCard, marginBottom: '22px' }">
            <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px', flexWrap: 'wrap' }">
              <span :style="{ fontFamily: serif, fontSize: '17px', fontWeight: 600 }">数据库系统原理</span>
              <span :style="{ fontSize: '11.5px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '3px 10px' }">未诊断</span>
              <span :style="{ marginLeft: 'auto', fontSize: '12px', color: 'var(--mut)' }">2026 春 · 陈每文</span>
            </div>
            <div :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '16px' }">先做一次入口诊断（约 25 题 · 15 分钟），问津才能为你点亮这张地图。</div>
            <router-link to="/diagnostic" class="wj-hover-card2" :style="{ height: '40px', boxSizing: 'border-box', display: 'inline-flex', alignItems: 'center', padding: '0 20px', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '13px', textDecoration: 'none' }">开始入口诊断</router-link>
          </div>

          <div :style="{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '18px' }">
            <button @click="step = 'login'; pwd = ''" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">退出登录</button>
            <span :style="{ width: '1px', height: '12px', background: 'var(--line)' }"></span>
            <router-link to="/teacher/graph" class="wj-underline" :style="{ color: 'var(--mut)', fontSize: '12.5px', textDecoration: 'underline', textUnderlineOffset: '3px' }">我是教师 · 进入教师端</router-link>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useViewport } from '../composables/useViewport.js'

const serif = "'Noto Serif SC', serif"
const { width } = useViewport()
const step = ref('login')
const sid = ref('')
const pwd = ref('')

const inputStyle = {
  height: '44px',
  boxSizing: 'border-box',
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '10px',
  padding: '0 14px',
  fontSize: '14px',
  color: 'var(--ink)',
  outline: 'none',
  marginBottom: '16px',
  transition: 'border-color 0.18s, background-color 0.35s'
}

const courseCard = {
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '13px',
  padding: '20px 22px',
  boxSizing: 'border-box',
  marginBottom: '14px',
  transition: 'background-color 0.35s, border-color 0.2s, transform 0.25s ease, box-shadow 0.25s ease'
}
</script>

<style scoped>
.wj-input:focus {
  border-color: var(--acc) !important;
}
.wj-course-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.07);
  border-color: var(--mut);
}
</style>
