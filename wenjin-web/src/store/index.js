import { createPinia } from 'pinia'

/** 应用唯一 Pinia 实例，供 main / router / http 等非组件上下文复用。 */
export const pinia = createPinia()

export default pinia
