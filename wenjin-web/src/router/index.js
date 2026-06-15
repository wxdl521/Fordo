import { createRouter, createWebHistory } from 'vue-router'
import Diagnostic from '../views/Diagnostic.vue'

// ColorMap 依赖较重的 echarts，Admin 为次要页：懒加载，移出落地首包
const ColorMap = () => import('../views/ColorMap.vue')
const Admin = () => import('../views/Admin.vue')

const routes = [
  { path: '/', redirect: '/diagnostic' },
  { path: '/map', component: ColorMap },
  { path: '/diagnostic', component: Diagnostic },
  { path: '/admin', component: Admin }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})
