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
          <input v-model="pwd" type="password" placeholder="••••••••" class="wj-input" :style="{ ...inputStyle, marginBottom: '24px' }" @keydown.enter="handleLogin" />
          <button @click="handleLogin" class="wj-btn-acc" :style="{ height: '46px', background: 'var(--acc)', border: 'none', borderRadius: '10px', color: '#FFFDF8', fontSize: '14.5px', fontWeight: 500, cursor: 'pointer', marginBottom: '16px' }">登 录</button>
          <div :style="{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '18px', marginBottom: '12px' }">
            <button @click="handleDemoLogin" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">使用演示账号进入</button>
            <span :style="{ width: '1px', height: '12px', background: 'var(--line)' }"></span>
            <button @click="step = 'register'; loginError = ''" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">没有账号？注册</button>
          </div>
          <div v-if="loginError" :style="{ fontSize: '12.5px', color: '#e74c3c', textAlign: 'center', marginTop: '4px' }">{{ loginError }}</div>
        </div>
      </div>

      <!-- 注册 -->
      <div v-else-if="step === 'register'" :style="{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '24px', animation: 'wjFadeUp 0.45s ease both' }">
        <div :style="{ width: '100%', maxWidth: '340px', display: 'flex', flexDirection: 'column' }">
          <div v-show="width < 880" :style="{ fontFamily: serif, fontSize: '24px', fontWeight: 600, letterSpacing: '4px', marginBottom: '8px' }">问津</div>
          <div :style="{ fontFamily: serif, fontSize: '21px', fontWeight: 600, marginBottom: '6px' }">注册账号</div>
          <div :style="{ fontSize: '13px', color: 'var(--mut)', marginBottom: '24px' }">创建账号，开始你的学习旅程。</div>

          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">用户名</label>
          <input v-model="regUsername" placeholder="请输入用户名" class="wj-input" :style="inputStyle" />

          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">密码</label>
          <input v-model="regPassword" type="password" placeholder="请输入密码" class="wj-input" :style="inputStyle" />

          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">确认密码</label>
          <input v-model="regConfirm" type="password" placeholder="请再次输入密码" class="wj-input" :style="inputStyle" />

          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">真实姓名</label>
          <input v-model="regRealName" placeholder="请输入真实姓名" class="wj-input" :style="inputStyle" />

          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">角色</label>
          <div :style="{ display: 'flex', gap: '10px', marginBottom: '20px' }">
            <button @click="regRole = 2" :style="regRole === 2 ? roleBtnActive : roleBtnInactive" class="wj-role-btn">学生</button>
            <button @click="regRole = 1" :style="regRole === 1 ? roleBtnActive : roleBtnInactive" class="wj-role-btn">教师</button>
          </div>

          <button @click="handleRegister" class="wj-btn-acc" :style="{ height: '46px', background: 'var(--acc)', border: 'none', borderRadius: '10px', color: '#FFFDF8', fontSize: '14.5px', fontWeight: 500, cursor: 'pointer', marginBottom: '16px' }">注 册</button>
          <button @click="step = 'login'; regError = ''" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px', alignSelf: 'center' }">已有账号？登录</button>
          <div v-if="regError" :style="{ fontSize: '12.5px', color: '#e74c3c', textAlign: 'center', marginTop: '10px' }">{{ regError }}</div>
        </div>
      </div>

      <!-- 课程选择 -->
      <div v-else :style="{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '24px', animation: 'wjFadeUp 0.45s ease both' }">
        <div :style="{ width: '100%', maxWidth: '460px', display: 'flex', flexDirection: 'column' }">
          <div :style="{ fontFamily: serif, fontSize: '22px', fontWeight: 600, marginBottom: '6px' }">你好，{{ displayName }}</div>
          <div :style="{ fontSize: '13px', color: 'var(--mut)', marginBottom: '26px' }">选择一门课程进入。</div>

          <div v-if="courseLoading" :style="{ textAlign: 'center', color: 'var(--mut)', fontSize: '13px', padding: '40px 0' }">加载中...</div>

          <template v-else-if="courses.length > 0">
            <div
              v-for="(c, idx) in courses"
              :key="c.courseId"
              class="wj-course-card"
              :style="{ ...courseCard, marginBottom: idx === courses.length - 1 ? '22px' : '14px' }"
            >
              <div :style="{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px', flexWrap: 'wrap' }">
                <span :style="{ fontFamily: serif, fontSize: '17px', fontWeight: 600 }">{{ c.name }}</span>
                <span v-if="c.masteredCount > 0 || c.weakCount > 0" :style="{ fontSize: '11.5px', color: 'var(--ok)', background: 'var(--okSoft)', borderRadius: '999px', padding: '3px 10px' }">进行中</span>
                <span v-else :style="{ fontSize: '11.5px', color: 'var(--mut)', border: '1px solid var(--line)', borderRadius: '999px', padding: '3px 10px' }">未诊断</span>
              </div>
              <div v-if="c.masteredCount > 0 || c.weakCount > 0" :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '14px' }">
                已掌握 {{ c.masteredCount }} / {{ c.masteredCount + c.weakCount + c.unlearnedCount }} 个节点 · 待修薄弱点 {{ c.weakCount }} 个
              </div>
              <div v-else :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '16px' }">先做一次入口诊断（约 25 题 · 15 分钟），问津才能为你点亮这张地图。</div>
              <div v-if="c.masteredCount > 0 || c.weakCount > 0" :style="{ height: '5px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden', marginBottom: '16px' }">
                <div :style="{ height: '100%', width: masteryPercent(c) + '%', background: 'var(--ok)', borderRadius: '99px' }"></div>
              </div>
              <div :style="{ display: 'flex', alignItems: 'center', gap: '12px' }">
                <router-link v-if="c.masteredCount > 0 || c.weakCount > 0" :to="{ path: '/map', query: { courseId: c.courseId } }" class="wj-btn-acc" :style="{ height: '40px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 24px', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, textDecoration: 'none' }">进入课程</router-link>
                <router-link v-else :to="{ path: '/diagnostic', query: { courseId: c.courseId } }" class="wj-hover-card2" :style="{ height: '40px', boxSizing: 'border-box', display: 'inline-flex', alignItems: 'center', padding: '0 20px', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '13px', textDecoration: 'none' }">开始入口诊断</router-link>
                <router-link v-if="c.masteredCount > 0 || c.weakCount > 0" :to="{ path: '/growth', query: { courseId: c.courseId } }" class="wj-underline" :style="{ fontSize: '12.5px', color: 'var(--mut)', textDecoration: 'underline', textUnderlineOffset: '3px' }">成长档案</router-link>
              </div>
            </div>
          </template>

          <div v-else :style="{ textAlign: 'center', padding: '48px 0' }">
            <div :style="{ fontSize: '14px', color: 'var(--mut)', marginBottom: '8px' }">暂无课程</div>
            <div :style="{ fontSize: '12.5px', color: 'var(--mut)' }">请联系教师为你分配课程。</div>
          </div>

          <div :style="{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '18px' }">
            <button @click="handleLogout" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">退出登录</button>
            <!-- 教师端入口仅对教师（role === 1）可见 -->
            <template v-if="isTeacher">
              <span :style="{ width: '1px', height: '12px', background: 'var(--line)' }"></span>
              <router-link to="/teacher/graph" class="wj-underline" :style="{ color: 'var(--mut)', fontSize: '12.5px', textDecoration: 'underline', textUnderlineOffset: '3px' }">我是教师 · 进入教师端</router-link>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useViewport } from '../composables/useViewport.js'
import { login as apiLogin, register as apiRegister } from '../api/user.js'
import { getMyCourses } from '../api/course.js'

const serif = "'Noto Serif SC', serif"
const { width } = useViewport()

const step = ref('login')
const sid = ref('')
const pwd = ref('')
const loginError = ref('')

// 注册表单
const regUsername = ref('')
const regPassword = ref('')
const regConfirm = ref('')
const regRealName = ref('')
const regRole = ref(2)
const regError = ref('')

// 登录后存储的用户信息
const currentUser = ref(null)
const courses = ref([])
const courseLoading = ref(false)

const displayName = computed(() => {
  if (currentUser.value && currentUser.value.realName) {
    return currentUser.value.realName
  }
  return '同学'
})

// 是否教师（role === 1）——决定课程页是否显示「进入教师端」入口
const isTeacher = computed(() => currentUser.value && currentUser.value.role === 1)

function masteryPercent(c) {
  const total = c.masteredCount + c.weakCount + c.unlearnedCount
  if (total === 0) return 0
  return Math.round((c.masteredCount / total) * 100)
}

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

const roleBtnBase = {
  flex: 1,
  height: '40px',
  borderRadius: '10px',
  fontSize: '14px',
  fontWeight: 500,
  cursor: 'pointer',
  transition: 'all 0.18s'
}
const roleBtnActive = {
  ...roleBtnBase,
  background: 'var(--acc)',
  border: '1px solid var(--acc)',
  color: '#FFFDF8'
}
const roleBtnInactive = {
  ...roleBtnBase,
  background: 'transparent',
  border: '1px solid var(--line)',
  color: 'var(--mut)'
}

async function handleLogin() {
  loginError.value = ''
  if (!sid.value || !pwd.value) {
    loginError.value = '请输入用户名和密码'
    return
  }
  try {
    const user = await apiLogin({ username: sid.value, password: pwd.value })
    currentUser.value = user
    localStorage.setItem('wj_user', JSON.stringify(user))
    step.value = 'course'
    loadCourses()
  } catch (e) {
    loginError.value = e.message || '登录失败'
  }
}

function handleDemoLogin() {
  apiLogin({ username: 'demo_student', password: 'demo' })
    .then(user => {
      currentUser.value = user
      localStorage.setItem('wj_user', JSON.stringify(user))
      step.value = 'course'
      loadCourses()
    })
    .catch(e => {
      loginError.value = e.message || '演示登录失败'
    })
}

async function handleRegister() {
  regError.value = ''
  if (!regUsername.value || !regPassword.value || !regConfirm.value || !regRealName.value) {
    regError.value = '请填写所有字段'
    return
  }
  if (regPassword.value !== regConfirm.value) {
    regError.value = '两次密码输入不一致'
    return
  }
  try {
    const user = await apiRegister({
      username: regUsername.value,
      password: regPassword.value,
      realName: regRealName.value,
      role: regRole.value
    })
    currentUser.value = user
    localStorage.setItem('wj_user', JSON.stringify(user))
    step.value = 'course'
    loadCourses()
  } catch (e) {
    regError.value = e.message || '注册失败'
  }
}

function handleLogout() {
  currentUser.value = null
  courses.value = []
  localStorage.removeItem('wj_user')
  sid.value = ''
  pwd.value = ''
  step.value = 'login'
}

async function loadCourses() {
  if (!currentUser.value) return
  courseLoading.value = true
  try {
    courses.value = await getMyCourses(currentUser.value.id)
  } catch {
    courses.value = []
  } finally {
    courseLoading.value = false
  }
}

onMounted(() => {
  const saved = localStorage.getItem('wj_user')
  if (saved) {
    try {
      currentUser.value = JSON.parse(saved)
      step.value = 'course'
      loadCourses()
    } catch { /* ignore */ }
  }
})
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
.wj-role-btn:hover {
  opacity: 0.85;
}
</style>
