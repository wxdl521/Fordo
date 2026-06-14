import { ref } from 'vue'

// 单例加载课程知识图谱，各页面共享。
// 依次尝试若干候选路径，兼容 Vite 构建（public/ 映射到根）与免构建预览。
const data = ref(null)
let loading = null

const CANDIDATES = [
  'data/graph.json',
  './data/graph.json',
  'public/data/graph.json',
  './public/data/graph.json'
]

async function loadFirst() {
  for (const url of CANDIDATES) {
    try {
      const r = await fetch(url)
      if (r.ok) return await r.json()
    } catch (e) {}
  }
  throw new Error('graph.json 加载失败')
}

export function useGraphData() {
  if (!data.value && !loading) {
    loading = loadFirst().then((json) => {
      data.value = json
      return json
    })
  }
  return { data, ready: () => !!data.value }
}
