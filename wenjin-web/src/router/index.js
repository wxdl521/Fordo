import { createRouter, createWebHashHistory } from 'vue-router'
import Login from '../views/Login.vue'

// 重页懒加载，移出落地首包
const ColorMap = () => import('../views/ColorMap.vue')
const Admin = () => import('../views/Admin.vue')
const Diagnostic = () => import('../views/Diagnostic.vue')
const DiagnosticResult = () => import('../views/DiagnosticResult.vue')
const LearningPath = () => import('../views/LearningPath.vue')
const Companion = () => import('../views/Companion.vue')
const KnowledgePoint = () => import('../views/KnowledgePoint.vue')
const Growth = () => import('../views/Growth.vue')
const MobileMap = () => import('../views/MobileMap.vue')
const TeacherGraphReview = () => import('../views/TeacherGraphReview.vue')
const TeacherQuestionPool = () => import('../views/TeacherQuestionPool.vue')
const TeacherDashboard = () => import('../views/TeacherDashboard.vue')

const routes = [
  { path: '/', name: 'login', component: Login, meta: { title: '登录 / 课程选择' } },
  { path: '/map', name: 'map', component: ColorMap, meta: { title: '染色地图' } },
  { path: '/diagnostic', name: 'diagnostic', component: Diagnostic, meta: { title: '入口诊断' } },
  { path: '/result', name: 'result', component: DiagnosticResult, meta: { title: '诊断结果' } },
  { path: '/path', name: 'path', component: LearningPath, meta: { title: '学习路径' } },
  { path: '/knowledge', name: 'knowledge', component: KnowledgePoint, meta: { title: '知识点详情' } },
  { path: '/companion', name: 'companion', component: Companion, meta: { title: 'AI 学习伴侣' } },
  { path: '/growth', name: 'growth', component: Growth, meta: { title: '成长档案' } },
  { path: '/admin', name: 'admin', component: Admin, meta: { title: '管理后台' } },
  { path: '/mobile', name: 'mobile', component: MobileMap, meta: { title: '移动端地图' } },
  { path: '/teacher/graph', name: 'teacher-graph', component: TeacherGraphReview, meta: { title: '图谱审核工作台' } },
  { path: '/teacher/questions', name: 'teacher-questions', component: TeacherQuestionPool, meta: { title: '题目审核池' } },
  { path: '/teacher/dashboard', name: 'teacher-dashboard', component: TeacherDashboard, meta: { title: '学情看板' } }
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})
