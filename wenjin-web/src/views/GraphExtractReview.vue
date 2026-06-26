<template>
  <div :style="pageStyle">
    <!-- 顶部 -->
    <div :style="barStyle">
      <span>课程标准抽取审核 · 节点 <b>{{ nodes.length }}</b> · 边 <b>{{ edges.length }}</b></span>
      <div :style="{ marginLeft: 'auto', display: 'flex', gap: '10px' }">
        <button :disabled="busy || expired || loading" @click="submit" :style="primaryBtn">提交并生成图谱</button>
        <button @click="goBack" :style="ghostBtn">取消返回</button>
      </div>
    </div>

    <div v-if="loading" :style="hintStyle">草稿加载中…</div>
    <div v-else-if="expired" :style="{ ...hintStyle, color: 'var(--accent)' }">
      {{ loadError }}
      <button @click="goBack" :style="{ ...ghostBtn, marginLeft: '10px' }">返回重新上传</button>
    </div>

    <template v-else>
      <!-- 全量替换警示 -->
      <div :style="warnStyle">⚠ 提交将<b>全量替换</b>当前课程图谱(现有节点与边被覆盖,不可撤销)</div>

      <!-- Tab -->
      <div :style="tabBarStyle">
        <button @click="tab = 'nodes'" :style="tab === 'nodes' ? tabActive : tabBtn">节点 ({{ nodes.length }})</button>
        <button @click="tab = 'edges'" :style="tab === 'edges' ? tabActive : tabBtn">边 ({{ edges.length }})</button>
      </div>

      <!-- 节点表 -->
      <div v-show="tab === 'nodes'" :style="tableWrap">
        <table :style="tableStyle">
          <thead>
            <tr>
              <th :style="th">ID</th><th :style="th">名称</th><th :style="th">章节</th>
              <th :style="th">难度</th><th :style="th">关键</th><th :style="th">Bloom</th>
              <th :style="th">描述</th><th :style="th">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(n, i) in nodes" :key="i">
              <td :style="td"><input v-model="n.id" :style="cellInput" /></td>
              <td :style="td"><input v-model="n.name" :style="cellInput" /></td>
              <td :style="td"><input v-model="n.chapter" :style="cellInput" /></td>
              <td :style="td"><input v-model.number="n.difficulty" type="number" :style="cellInputNarrow" /></td>
              <td :style="td"><input type="checkbox" v-model="n.is_key" /></td>
              <td :style="td"><input v-model="n.bloom" :style="cellInputNarrow" /></td>
              <td :style="td"><input v-model="n.description" :style="cellInput" /></td>
              <td :style="td"><button @click="removeNode(i)" :style="delBtn">删除</button></td>
            </tr>
          </tbody>
        </table>
        <button @click="addNode" :style="addBtn">+ 新增节点</button>
      </div>

      <!-- 边表 -->
      <div v-show="tab === 'edges'" :style="tableWrap">
        <table :style="tableStyle">
          <thead>
            <tr><th :style="th">起点</th><th :style="th">终点</th><th :style="th">关系</th><th :style="th">备注</th><th :style="th">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="(e, i) in edges" :key="i">
              <td :style="td">
                <select v-model="e.source" :style="cellInput">
                  <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.id }} · {{ n.name }}</option>
                </select>
              </td>
              <td :style="td">
                <select v-model="e.target" :style="cellInput">
                  <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.id }} · {{ n.name }}</option>
                </select>
              </td>
              <td :style="td">
                <select v-model="e.type" :style="cellInputNarrow">
                  <option>前置</option><option>包含</option><option>相关</option><option>应用</option>
                </select>
              </td>
              <td :style="td"><input v-model="e.note" :style="cellInput" /></td>
              <td :style="td"><button @click="removeEdge(i)" :style="delBtn">删除</button></td>
            </tr>
          </tbody>
        </table>
        <button @click="addEdge" :style="addBtn">+ 新增边</button>
      </div>
    </template>

    <!-- 指标卡 -->
    <div v-if="metrics" :style="overlayStyle">
      <div :style="cardStyle">
        <h3 :style="{ margin: '0 0 12px' }">导入完成 · AI 抽取质量</h3>
        <div :style="metricsGrid">
          <div :style="metricCol">
            <div :style="metricTitle">节点</div>
            <div>召回率 <b>{{ pct(metrics.node.recall) }}</b> · 精确率 <b>{{ pct(metrics.node.precision) }}</b></div>
            <div :style="metricSub">保留 {{ metrics.node.keptCount }} · 删除 {{ metrics.node.deletedCount }} · 新增 {{ metrics.node.addedCount }} · 修改 {{ metrics.node.modifiedCount }}</div>
          </div>
          <div :style="metricCol">
            <div :style="metricTitle">边</div>
            <div>召回率 <b>{{ pct(metrics.edge.recall) }}</b> · 精确率 <b>{{ pct(metrics.edge.precision) }}</b></div>
            <div :style="metricSub">保留 {{ metrics.edge.keptCount }} · 删除 {{ metrics.edge.deletedCount }} · 新增 {{ metrics.edge.addedCount }} · 修改 {{ metrics.edge.modifiedCount }}</div>
          </div>
        </div>
        <button @click="goBack" :style="{ ...primaryBtn, marginTop: '16px' }">查看图谱</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchExtractDraft, commitExtractDraft } from '../api/admin.js'

const route = useRoute()
const router = useRouter()
const draftId = route.query.draftId
const courseCode = route.query.courseCode || '52015CC4B4'

const nodes = ref([])
const edges = ref([])
const tab = ref('nodes')
const loading = ref(true)
const expired = ref(false)
const loadError = ref('')
const busy = ref(false)
const metrics = ref(null)

onMounted(async () => {
  if (!draftId) { expired.value = true; loadError.value = '缺少草稿标识,请重新上传'; loading.value = false; return }
  try {
    const draft = await fetchExtractDraft(draftId)
    // 注意:后端 NodeItem.isKey 标注 @JsonProperty("is_key"),草稿 JSON 用 is_key,
    // 这里统一以 is_key 绑定/回传,切勿改成 isKey(否则关键标记不回显也不保存)
    nodes.value = (draft.nodes || []).map(n => ({ ...n, is_key: !!n.is_key }))
    edges.value = (draft.edges || []).map(e => ({ ...e }))
  } catch (e) {
    expired.value = true
    loadError.value = e.message || '草稿已过期,请重新上传'
  } finally {
    loading.value = false
  }
})

function addNode() {
  nodes.value.push({ id: 'N_' + Date.now(), name: '', chapter: '', difficulty: 1, is_key: false, bloom: '', description: '' })
}
function removeNode(i) {
  const id = nodes.value[i].id
  nodes.value.splice(i, 1)
  const before = edges.value.length
  edges.value = edges.value.filter(e => e.source !== id && e.target !== id)
  const removed = before - edges.value.length
  if (removed > 0) alert(`已联动移除引用该节点的 ${removed} 条边`)
}
function addEdge() {
  const first = nodes.value[0]?.id || ''
  edges.value.push({ source: first, target: first, type: '前置', note: '' })
}
function removeEdge(i) { edges.value.splice(i, 1) }

function validate() {
  const rawIds = nodes.value.map(n => n.id)
  if (rawIds.some(id => !(id || '').trim())) return '存在 ID 为空的节点'
  if (new Set(rawIds).size !== rawIds.length) return '存在重复的节点 ID'
  const idSet = new Set(rawIds)
  for (const e of edges.value) {
    if (!idSet.has(e.source) || !idSet.has(e.target)) return `边 ${e.source}→${e.target} 引用了不存在的节点`
  }
  return ''
}

async function submit() {
  const err = validate()
  if (err) { alert(err); return }
  if (!confirm('提交将全量替换当前课程图谱,不可撤销。确定继续?')) return
  busy.value = true
  try {
    const finalGraph = {
      course: { code: courseCode },
      nodes: nodes.value,
      edges: edges.value
    }
    const res = await commitExtractDraft(draftId, finalGraph)
    metrics.value = res.metrics
  } catch (e) {
    alert(e.message || '提交失败')
  } finally {
    busy.value = false
  }
}

function goBack() { router.push('/teacher/graph') }
function pct(v) { return v == null ? '—' : (Number(v) * 100).toFixed(1) + '%' }

// ── 样式 ──
const pageStyle = { minHeight: '100vh', background: 'var(--bg)', color: 'var(--text)', padding: '0 0 40px' }
const barStyle = { display: 'flex', alignItems: 'center', gap: '12px', padding: '14px 20px', borderBottom: '1px solid var(--line)', fontSize: '14px' }
const hintStyle = { padding: '40px 20px', textAlign: 'center', color: 'var(--text-mut)' }
const warnStyle = { margin: '12px 20px', padding: '10px 12px', border: '1px solid var(--line)', borderRadius: '8px', color: 'var(--accent)', fontSize: '13px' }
const tabBarStyle = { display: 'flex', gap: '8px', padding: '0 20px' }
const tabBtn = { padding: '8px 16px', background: 'transparent', color: 'var(--text-mut)', border: '1px solid var(--line)', borderRadius: '8px 8px 0 0', cursor: 'pointer' }
const tabActive = { ...tabBtn, color: 'var(--text)', background: 'var(--panel)', borderBottom: 'none' }
const tableWrap = { margin: '0 20px', padding: '12px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: '0 8px 8px 8px', overflowX: 'auto' }
const tableStyle = { width: '100%', borderCollapse: 'collapse', fontSize: '13px' }
const th = { textAlign: 'left', padding: '6px 8px', borderBottom: '1px solid var(--line)', color: 'var(--text-mut)', whiteSpace: 'nowrap' }
const td = { padding: '4px 6px', borderBottom: '1px solid var(--line)' }
const cellInput = { width: '100%', minWidth: '90px', padding: '4px 6px', background: 'var(--bg)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: '4px' }
const cellInputNarrow = { ...cellInput, minWidth: '60px', width: '70px' }
const primaryBtn = { padding: '8px 16px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: '8px', cursor: 'pointer' }
const ghostBtn = { padding: '8px 16px', background: 'transparent', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: '8px', cursor: 'pointer' }
const delBtn = { padding: '3px 10px', background: 'transparent', color: 'var(--accent)', border: '1px solid var(--line)', borderRadius: '4px', cursor: 'pointer' }
const addBtn = { marginTop: '10px', padding: '6px 14px', background: 'transparent', color: 'var(--text)', border: '1px dashed var(--line)', borderRadius: '8px', cursor: 'pointer' }
const overlayStyle = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.45)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50 }
const cardStyle = { width: '440px', maxWidth: '90vw', padding: '20px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: '12px' }
const metricsGrid = { display: 'flex', gap: '16px' }
const metricCol = { flex: 1, padding: '12px', background: 'var(--bg)', borderRadius: '8px', fontSize: '13px' }
const metricTitle = { fontWeight: 600, marginBottom: '6px' }
const metricSub = { marginTop: '6px', color: 'var(--text-mut)', fontSize: '12px' }
</script>
