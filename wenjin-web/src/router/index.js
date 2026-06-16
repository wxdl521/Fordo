import { createRouter, createWebHistory } from 'vue-router'
import Diagnostic from '../views/Diagnostic.vue'

// 重页懒加载，移出落地首包
const ColorMap = () => import('../views/ColorMap.vue')
const Admin = () => import('../views/Admin.vue')
const DiagnosticResult = () => import('../views/DiagnosticResult.vue')
const LearningPath = () => import('../views/LearningPath.vue')

const routes = [
  { path: '/', redirect: '/diagnostic' },
  { path: '/map', component: ColorMap },
  { path: '/diagnostic', component: Diagnostic },
  { path: '/result', component: DiagnosticResult },
  { path: '/path', component: LearningPath },
  { path: '/admin', component: Admin }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})
