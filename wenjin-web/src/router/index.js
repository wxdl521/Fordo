import { createRouter, createWebHistory } from 'vue-router'
import Diagnostic from '../views/Diagnostic.vue'

// 重页懒加载，移出落地首包
const ColorMap = () => import('../views/ColorMap.vue')
const Admin = () => import('../views/Admin.vue')
const DiagnosticResult = () => import('../views/DiagnosticResult.vue')
const LearningPath = () => import('../views/LearningPath.vue')
const Companion = () => import('../views/Companion.vue')
const KnowledgePoint = () => import('../views/KnowledgePoint.vue')
const Growth = () => import('../views/Growth.vue')
const TeacherGraphReview = () => import('../views/TeacherGraphReview.vue')
const TeacherQuestionPool = () => import('../views/TeacherQuestionPool.vue')
const TeacherDashboard = () => import('../views/TeacherDashboard.vue')

const routes = [
  { path: '/', redirect: '/diagnostic' },
  { path: '/map', component: ColorMap },
  { path: '/diagnostic', component: Diagnostic },
  { path: '/result', component: DiagnosticResult },
  { path: '/path', component: LearningPath },
  { path: '/companion', component: Companion },
  { path: '/knowledge', component: KnowledgePoint },
  { path: '/growth', component: Growth },
  { path: '/admin', component: Admin },
  { path: '/teacher/graph', component: TeacherGraphReview },
  { path: '/teacher/questions', component: TeacherQuestionPool },
  { path: '/teacher/dashboard', component: TeacherDashboard }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})
