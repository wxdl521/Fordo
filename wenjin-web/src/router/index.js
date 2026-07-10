import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import { me } from '../api/user.js'

// 重页懒加载，移出落地首包
const ColorMap = () => import('../views/ColorMap.vue')
const Diagnostic = () => import('../views/Diagnostic.vue')
const AdaptiveDiagnostic = () => import('../views/AdaptiveDiagnostic.vue')
const DiagnosticResult = () => import('../views/DiagnosticResult.vue')
const LearningPath = () => import('../views/LearningPath.vue')
const Companion = () => import('../views/Companion.vue')
const KnowledgePoint = () => import('../views/KnowledgePoint.vue')
const Growth = () => import('../views/Growth.vue')
const MobileMap = () => import('../views/MobileMap.vue')
const TeacherGraphReview = () => import('../views/TeacherGraphReview.vue')
const TeacherQuestionPool = () => import('../views/TeacherQuestionPool.vue')
const TeacherDashboard = () => import('../views/TeacherDashboard.vue')
const GraphExtractReview = () => import('../views/GraphExtractReview.vue')
const NodePractice = () => import('../views/NodePractice.vue')

const routes = [
  { path: '/', name: 'login', component: Login, meta: { title: '登录 / 课程选择' } },
  { path: '/map', name: 'map', component: ColorMap, meta: { title: '染色地图' } },
  { path: '/diagnostic', name: 'diagnostic', component: Diagnostic, meta: { title: '入口诊断' } },
  { path: '/diagnostic/adaptive', name: 'diagnostic-adaptive', component: AdaptiveDiagnostic, meta: { title: '快速诊断' } },
  { path: '/result', name: 'result', component: DiagnosticResult, meta: { title: '诊断结果' } },
  { path: '/path', name: 'path', component: LearningPath, meta: { title: '学习路径' } },
  { path: '/knowledge', name: 'knowledge', component: KnowledgePoint, meta: { title: '知识点详情' } },
  { path: '/companion', name: 'companion', component: Companion, meta: { title: 'AI 学习伴侣' } },
  { path: '/growth', name: 'growth', component: Growth, meta: { title: '成长档案' } },
  { path: '/mobile', name: 'mobile', component: MobileMap, meta: { title: '移动端地图' } },
  { path: '/teacher/graph', name: 'teacher-graph', component: TeacherGraphReview, meta: { title: '图谱审核工作台' } },
  { path: '/teacher/questions', name: 'teacher-questions', component: TeacherQuestionPool, meta: { title: '题目审核池' } },
  { path: '/teacher/dashboard', name: 'teacher-dashboard', component: TeacherDashboard, meta: { title: '学情看板' } },
  { path: '/teacher/graph-extract-review', name: 'teacher-graph-extract-review', component: GraphExtractReview, meta: { title: '抽取审核' } },
  { path: '/practice', name: 'practice', component: NodePractice, meta: { title: '节点练习' } },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

// 当前登录用户缓存（仅展示；权限以服务端 /api/me 与拦截器为准）
function currentUser() {
  try {
    return JSON.parse(localStorage.getItem('wj_user'))
  } catch {
    return null
  }
}

function canAccessTeacher(user) {
  // 1=教师, 3=管理员（与后端 TeacherAuthInterceptor 一致）
  return user && (user.role === 1 || user.role === 3)
}

let sessionRefreshPromise = null

/** 启动时用 /api/me 刷新会话；localStorage 只作展示缓存。 */
function refreshSessionOnce() {
  if (sessionRefreshPromise) return sessionRefreshPromise
  sessionRefreshPromise = (async () => {
    const token = localStorage.getItem('wj_token')
    if (!token) {
      localStorage.removeItem('wj_user')
      return null
    }
    try {
      const user = await me()
      localStorage.setItem('wj_user', JSON.stringify(user))
      return user
    } catch {
      localStorage.removeItem('wj_token')
      localStorage.removeItem('wj_user')
      return null
    }
  })()
  return sessionRefreshPromise
}

// 路由守卫：启动刷新会话；教师端仅允许教师/管理员进入（服务端仍强制校验）。
router.beforeEach(async (to) => {
  await refreshSessionOnce()
  if (to.path.startsWith('/teacher')) {
    const user = currentUser()
    if (!canAccessTeacher(user)) {
      return { path: '/' }
    }
  }
  return true
})
