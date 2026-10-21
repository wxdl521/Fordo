import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import { pinia } from '../store/index.js'
import { useSessionStore } from '../store/session.js'
import { useCourseStore } from '../store/course.js'

// 重页懒加载，移出落地首包
const ColorMap = () => import('../views/ColorMap.vue')
const Diagnostic = () => import('../views/Diagnostic.vue')
const AdaptiveDiagnostic = () => import('../views/AdaptiveDiagnostic.vue')
const DiagnosticResult = () => import('../views/DiagnosticResult.vue')
const LearningPath = () => import('../views/LearningPath.vue')
const Companion = () => import('../views/Companion.vue')
const KnowledgePoint = () => import('../views/KnowledgePoint.vue')
const Growth = () => import('../views/Growth.vue')
const TeacherGraphReview = () => import('../views/TeacherGraphReview.vue')
const TeacherQuestionPool = () => import('../views/TeacherQuestionPool.vue')
const TeacherDashboard = () => import('../views/TeacherDashboard.vue')
const TeacherAgentTrace = () => import('../views/TeacherAgentTrace.vue')
const TeacherRag = () => import('../views/TeacherRag.vue')
const TeacherReports = () => import('../views/TeacherReports.vue')
const GraphExtractReview = () => import('../views/GraphExtractReview.vue')
const NodePractice = () => import('../views/NodePractice.vue')
const AdminLayout = () => import('../layouts/AdminLayout.vue')
const AdminOverview = () => import('../views/AdminOverview.vue')
const AdminUsers = () => import('../views/AdminUsers.vue')
const AdminCourses = () => import('../views/AdminCourses.vue')
const AdminClazzes = () => import('../views/AdminClazzes.vue')
const AdminClazzDetail = () => import('../views/AdminClazzDetail.vue')
const AdminAuditLogs = () => import('../views/AdminAuditLogs.vue')
const AdminLoginLogs = () => import('../views/AdminLoginLogs.vue')

const routes = [
  { path: '/', name: 'login', component: Login, meta: { title: '登录 / 课程选择', public: true } },
  { path: '/map', name: 'map', component: ColorMap, meta: { title: '染色地图' } },
  // Mobile alias: same ColorMap as /map (no placeholder "功能开发中")
  { path: '/mobile', redirect: { name: 'map' } },
  { path: '/diagnostic', name: 'diagnostic', component: Diagnostic, meta: { title: '入口诊断' } },
  { path: '/diagnostic/adaptive', name: 'diagnostic-adaptive', component: AdaptiveDiagnostic, meta: { title: '快速诊断' } },
  { path: '/result', name: 'result', component: DiagnosticResult, meta: { title: '诊断结果' } },
  { path: '/path', name: 'path', component: LearningPath, meta: { title: '学习路径' } },
  { path: '/knowledge', name: 'knowledge', component: KnowledgePoint, meta: { title: '知识点详情' } },
  { path: '/companion', name: 'companion', component: Companion, meta: { title: 'AI 学习伴侣' } },
  { path: '/growth', name: 'growth', component: Growth, meta: { title: '成长档案' } },
  { path: '/teacher/graph', name: 'teacher-graph', component: TeacherGraphReview, meta: { title: '图谱审核工作台' } },
  { path: '/teacher/questions', name: 'teacher-questions', component: TeacherQuestionPool, meta: { title: '题目审核池' } },
  { path: '/teacher/dashboard', name: 'teacher-dashboard', component: TeacherDashboard, meta: { title: '学情看板' } },
  { path: '/teacher/agent-runs', name: 'teacher-agent-runs', component: TeacherAgentTrace, meta: { title: 'Agent 轨迹' } },
  { path: '/teacher/rag', name: 'teacher-rag', component: TeacherRag, meta: { title: '语料审核' } },
  { path: '/teacher/reports', name: 'teacher-reports', component: TeacherReports, meta: { title: '周报' } },
  { path: '/teacher/graph-extract-review', name: 'teacher-graph-extract-review', component: GraphExtractReview, meta: { title: '抽取审核' } },
  { path: '/practice', name: 'practice', component: NodePractice, meta: { title: '节点练习' } },
  // 系统管理后台：独立布局（不带学生/教师 TopBar），仅 role===3 可进入
  {
    path: '/admin',
    component: AdminLayout,
    children: [
      { path: '', redirect: '/admin/overview' },
      { path: 'overview', name: 'admin-overview', component: AdminOverview, meta: { title: '平台总览' } },
      { path: 'users', name: 'admin-users', component: AdminUsers, meta: { title: '用户管理' } },
      { path: 'courses', name: 'admin-courses', component: AdminCourses, meta: { title: '课程管理' } },
      { path: 'clazzes', name: 'admin-clazzes', component: AdminClazzes, meta: { title: '班级管理' } },
      { path: 'clazzes/:id', name: 'admin-clazz-detail', component: AdminClazzDetail, meta: { title: '班级详情' } },
      { path: 'audit-logs', name: 'admin-audit-logs', component: AdminAuditLogs, meta: { title: '操作日志' } },
      { path: 'login-logs', name: 'admin-login-logs', component: AdminLoginLogs, meta: { title: '登录日志' } }
    ]
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

function canAccessTeacher(user) {
  // 1=教师, 3=管理员（与后端 TeacherAuthInterceptor 一致）
  return user && (user.role === 1 || user.role === 3)
}

function isAdminPath(path) {
  return path === '/admin' || path.startsWith('/admin/')
}

// 路由守卫：启动刷新会话；无身份跳登录；教师端仅允许教师/管理员；管理后台仅允许管理员。
router.beforeEach(async (to) => {
  const session = useSessionStore(pinia)
  const course = useCourseStore(pinia)
  if (!session.hydrated) session.hydrate()
  if (!course.hydrated) course.hydrate()

  await session.refreshMeOnce()

  const isPublic = to.meta?.public === true || to.path === '/'
  if (!isPublic && !session.isAuthenticated) {
    return { path: '/' }
  }

  if (to.path.startsWith('/teacher')) {
    if (!canAccessTeacher(session.user)) {
      return { path: '/' }
    }
  }

  if (isAdminPath(to.path)) {
    if (!session.isAdmin) {
      return { path: '/' }
    }
  }
  return true
})
