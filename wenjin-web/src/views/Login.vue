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
            <button @click="showRegister" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">没有账号？注册</button>
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

          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">邮箱</label>
          <input v-model="regEmail" placeholder="请输入邮箱" class="wj-input" :style="inputStyle" />

          <label :style="{ fontSize: '12px', color: 'var(--mut)', marginBottom: '7px' }">验证码</label>
          <div :style="{ display: 'flex', gap: '8px', marginBottom: '20px' }">
            <input v-model="regCode" placeholder="6位验证码" class="wj-input" :style="{ ...inputStyle, flex: 1, marginBottom: 0 }" />
            <button
              type="button"
              :disabled="codeWait > 0 || sendingCode"
              @click="handleSendCode"
              class="wj-hover-card2"
              :style="{ height: '44px', padding: '0 12px', border: '1px solid var(--line)', borderRadius: '10px', background: 'var(--card)', color: 'var(--ink)', fontSize: '13px', cursor: codeWait > 0 || sendingCode ? 'not-allowed' : 'pointer', whiteSpace: 'nowrap' }"
            >{{ codeWait > 0 ? codeWait + 's' : '发送验证码' }}</button>
          </div>

          <button @click="handleRegister" class="wj-btn-acc" :style="{ height: '46px', background: 'var(--acc)', border: 'none', borderRadius: '10px', color: '#FFFDF8', fontSize: '14.5px', fontWeight: 500, cursor: 'pointer', marginBottom: '16px' }">注 册</button>
          <button @click="showLogin" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px', alignSelf: 'center' }">已有账号？登录</button>
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
              <div v-else :style="{ fontSize: '12.5px', color: 'var(--mut)', marginBottom: '16px' }">先看一眼地图。这是示例染色，随后再做入口诊断点亮你的学情。</div>
              <div v-if="c.masteredCount > 0 || c.weakCount > 0" :style="{ height: '5px', background: 'var(--card2)', borderRadius: '99px', overflow: 'hidden', marginBottom: '16px' }">
                <div :style="{ height: '100%', width: masteryPercent(c) + '%', background: 'var(--ok)', borderRadius: '99px' }"></div>
              </div>
              <div :style="{ display: 'flex', alignItems: 'center', gap: '12px' }">
                <router-link v-if="c.masteredCount > 0 || c.weakCount > 0" :to="{ path: '/map', query: { courseId: c.courseId } }" @click="rememberStudentCourse(c.courseId)" class="wj-btn-acc" :style="{ height: '40px', boxSizing: 'border-box', display: 'flex', alignItems: 'center', padding: '0 24px', background: 'var(--acc)', borderRadius: '9px', color: '#FFFDF8', fontSize: '13.5px', fontWeight: 500, textDecoration: 'none' }">进入课程</router-link>
                <router-link v-else :to="{ path: '/map', query: { courseId: c.courseId, tour: '1' } }" @click="rememberStudentCourse(c.courseId)" class="wj-hover-card2" :style="{ height: '40px', boxSizing: 'border-box', display: 'inline-flex', alignItems: 'center', padding: '0 20px', border: '1px solid var(--line)', borderRadius: '9px', color: 'var(--ink)', fontSize: '13px', textDecoration: 'none' }">先看地图</router-link>
                <router-link v-if="c.masteredCount > 0 || c.weakCount > 0" :to="{ path: '/growth', query: { courseId: c.courseId } }" @click="rememberStudentCourse(c.courseId)" class="wj-underline" :style="{ fontSize: '12.5px', color: 'var(--mut)', textDecoration: 'underline', textUnderlineOffset: '3px' }">成长档案</router-link>
              </div>
            </div>
          </template>

          <div v-else-if="availableCourses.length === 0" :style="{ textAlign: 'center', padding: '48px 0' }">
            <div :style="{ fontSize: '14px', color: 'var(--mut)', marginBottom: '8px' }">暂无课程</div>
            <div :style="{ fontSize: '12.5px', color: 'var(--mut)' }">还没有已发布的课程，请联系教师发布课程。</div>
          </div>

          <!-- 可选课程（已发布、未选） -->
          <div v-if="availableCourses.length > 0" data-testid="course-plaza" :style="{ marginBottom: '22px' }">
            <div :style="{ fontSize: '12.5px', color: 'var(--mut)', margin: '4px 0 12px' }">可选课程</div>
            <div
              v-for="(c, idx) in availableCourses"
              :key="c.id"
              class="wj-course-card"
              :style="{ ...courseCard, marginBottom: idx === availableCourses.length - 1 ? '0' : '14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }"
            >
              <span :style="{ fontFamily: serif, fontSize: '15.5px', fontWeight: 600 }">{{ c.name }}</span>
              <button
                :data-testid="'enroll-course-' + c.id"
                @click="handleEnroll(c.id)"
                class="wj-btn-acc"
                :style="{ height: '36px', padding: '0 18px', background: 'var(--acc)', border: 'none', borderRadius: '9px', color: '#FFFDF8', fontSize: '13px', fontWeight: 500, cursor: 'pointer' }"
              >选课</button>
            </div>
          </div>

          <div :style="{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '18px', flexWrap: 'wrap' }">
            <button @click="handleLogout" class="wj-underline" :style="{ background: 'transparent', border: 'none', color: 'var(--mut)', fontSize: '12.5px', cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: '3px' }">退出登录</button>
            <!-- 教师端入口仅对教师/管理员可见（role 1 或 3） -->
            <template v-if="isTeacher">
              <span :style="{ width: '1px', height: '12px', background: 'var(--line)' }"></span>
              <router-link to="/teacher/graph" class="wj-underline" :style="{ color: 'var(--mut)', fontSize: '12.5px', textDecoration: 'underline', textUnderlineOffset: '3px' }">我是教师 · 进入教师端</router-link>
            </template>
            <!-- 管理后台入口仅对管理员（role === 3）可见 -->
            <template v-if="isAdmin">
              <span :style="{ width: '1px', height: '12px', background: 'var(--line)' }"></span>
              <router-link to="/admin" data-testid="enter-admin" class="wj-underline" :style="{ color: 'var(--mut)', fontSize: '12.5px', textDecoration: 'underline', textUnderlineOffset: '3px' }">进入管理后台</router-link>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useViewport } from '../composables/useViewport.js'
import { login as apiLogin, register as apiRegister, sendEmailCode as apiSendEmailCode } from '../api/user.js'
import { getMyCourses, getAvailableCourses, enroll } from '../api/course.js'
import { useSessionStore } from '../store/session.js'
import { useCourseStore } from '../store/course.js'
import { createLatestRequest } from '../utils/latestRequest.js'

const serif = "'Noto Serif SC', serif"
const { width } = useViewport()
const router = useRouter()
const session = useSessionStore()
const courseStore = useCourseStore()
const { user: currentUser } = storeToRefs(session)

const step = ref('login')
const sid = ref('')
const pwd = ref('')
const loginError = ref('')

// 注册表单
const regUsername = ref('')
const regPassword = ref('')
const regConfirm = ref('')
const regRealName = ref('')
const regEmail = ref('')
const regCode = ref('')
const codeWait = ref(0)
const sendingCode = ref(false)
const regError = ref('')
let codeTimer = null

const courses = ref([])
const availableCourses = ref([])
const courseLoading = ref(false)
const authRequest = createLatestRequest()
const coursesRequest = createLatestRequest()
let disposed = false

function isAbortError(error) {
  return error?.name === 'AbortError'
    || error?.name === 'CanceledError'
    || error?.code === 'ERR_CANCELED'
}

function beginAuthRequest() {
  coursesRequest.cancel()
  courseLoading.value = false
  return authRequest.next()
}

function commitLogin(data, request) {
  if (disposed || !request.isCurrent()) return false
  session.setSession({ token: data.token, user: data.user })
  session.resetRefreshGate()
  step.value = 'course'
  if (session.isAdmin) {
    // 管理员登录后直接进管理后台，不再展示学生课程选择页
    void enterAdminConsole()
    return true
  }
  void loadCourses()
  return true
}

async function enterAdminConsole() {
  courseLoading.value = true // 跳转期间显示「加载中」，避免闪一下空课程页
  try {
    const failure = await router.push('/admin/overview')
    if (!failure) return
  } catch {
    /* 路由异常 → 退回课程页 */
  }
  if (disposed) return
  void loadCourses()
}

function showRegister() {
  authRequest.cancel()
  loginError.value = ''
  step.value = 'register'
}

function showLogin() {
  authRequest.cancel()
  regError.value = ''
  step.value = 'login'
}

const displayName = computed(() => {
  if (currentUser.value && currentUser.value.realName) {
    return currentUser.value.realName
  }
  return '同学'
})

// 是否教师/管理员（role 1 或 3）——决定课程页是否显示「进入教师端」入口
const isTeacher = computed(() => session.isTeacher)
// 是否管理员（role 3）——决定课程页是否显示「进入管理后台」入口
const isAdmin = computed(() => session.isAdmin)

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

async function handleLogin() {
  loginError.value = ''
  if (!sid.value || !pwd.value) {
    loginError.value = '请输入用户名和密码'
    return
  }
  const request = beginAuthRequest()
  try {
    const data = await apiLogin(
      { username: sid.value, password: pwd.value },
      { signal: request.signal }
    )
    commitLogin(data, request)
  } catch (e) {
    if (disposed || !request.isCurrent() || isAbortError(e)) return
    loginError.value = e.message || '登录失败'
  }
}

async function handleDemoLogin() {
  loginError.value = ''
  const request = beginAuthRequest()
  try {
    const data = await apiLogin(
      { username: 'demo_student', password: 'demo' },
      { signal: request.signal }
    )
    commitLogin(data, request)
  } catch (e) {
    if (disposed || !request.isCurrent() || isAbortError(e)) return
    loginError.value = e.message || '演示登录失败'
  }
}

async function handleSendCode() {
  regError.value = ''
  if (!regEmail.value) {
    regError.value = '请先填写邮箱'
    return
  }
  sendingCode.value = true
  try {
    await apiSendEmailCode({ email: regEmail.value })
    codeWait.value = 60
    if (codeTimer) clearInterval(codeTimer)
    codeTimer = setInterval(() => {
      if (codeWait.value <= 1) {
        codeWait.value = 0
        clearInterval(codeTimer)
        codeTimer = null
        return
      }
      codeWait.value -= 1
    }, 1000)
  } catch (e) {
    if (isAbortError(e)) return
    regError.value = e.message || '验证码发送失败'
  } finally {
    sendingCode.value = false
  }
}

async function handleRegister() {
  regError.value = ''
  if (!regUsername.value || !regPassword.value || !regConfirm.value || !regRealName.value || !regEmail.value || !regCode.value) {
    regError.value = '请填写所有字段'
    return
  }
  if (regPassword.value !== regConfirm.value) {
    regError.value = '两次密码输入不一致'
    return
  }
  const request = beginAuthRequest()
  try {
    await apiRegister({
      username: regUsername.value,
      password: regPassword.value,
      realName: regRealName.value,
      email: regEmail.value,
      code: regCode.value
    }, { signal: request.signal })
    if (disposed || !request.isCurrent()) return
    // 注册不发令牌，紧接着用同一凭据登录拿令牌（保持"注册即进入"的体验）
    const data = await apiLogin(
      { username: regUsername.value, password: regPassword.value },
      { signal: request.signal }
    )
    commitLogin(data, request)
  } catch (e) {
    if (disposed || !request.isCurrent() || isAbortError(e)) return
    regError.value = e.message || '注册失败'
  }
}

function handleLogout() {
  authRequest.cancel()
  coursesRequest.cancel()
  courseLoading.value = false
  session.clearSession()
  session.resetRefreshGate()
  courses.value = []
  availableCourses.value = []
  sid.value = ''
  pwd.value = ''
  step.value = 'login'
}

async function loadCourses() {
  const requestToken = session.token
  const requestUserId = session.userId
  if (disposed || !currentUser.value || !requestToken || !requestUserId) {
    coursesRequest.cancel()
    courseLoading.value = false
    return
  }
  const request = coursesRequest.next()
  const isCurrentOwner = () => !disposed
    && request.isCurrent()
    && session.token === requestToken
    && session.userId === requestUserId
  courseLoading.value = true
  let nextCourses = []
  let nextAvailableCourses = []
  try {
    nextCourses = await getMyCourses({ signal: request.signal })
    if (!isCurrentOwner()) return
  } catch (error) {
    if (!isCurrentOwner() || isAbortError(error)) return
  }
  try {
    const all = await getAvailableCourses({ signal: request.signal })
    if (!isCurrentOwner()) return
    const enrolledIds = new Set((nextCourses || []).map(c => c.courseId))
    nextAvailableCourses = (all || []).filter(c => !enrolledIds.has(c.id))
  } catch (error) {
    if (!isCurrentOwner() || isAbortError(error)) return
  } finally {
    if (isCurrentOwner()) courseLoading.value = false
  }
  if (!isCurrentOwner()) return
  courses.value = nextCourses
  availableCourses.value = nextAvailableCourses
}

async function handleEnroll(courseId) {
  if (!currentUser.value) return
  try {
    await enroll(courseId)
    await loadCourses()
  } catch (e) {
    alert(e.message || '选课失败')
  }
}

/** 进入某课前记住 studentCourseId（供地图/诊断等页共享） */
function rememberStudentCourse(courseId) {
  courseStore.setStudentCourseId(courseId)
}

onMounted(async () => {
  if (!session.hydrated) session.hydrate()
  if (!session.token) return
  const user = await session.refreshMe()
  if (!disposed && user) {
    step.value = 'course'
    void loadCourses()
  }
})

onBeforeUnmount(() => {
  disposed = true
  authRequest.cancel()
  coursesRequest.cancel()
  if (codeTimer) {
    clearInterval(codeTimer)
    codeTimer = null
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
</style>
