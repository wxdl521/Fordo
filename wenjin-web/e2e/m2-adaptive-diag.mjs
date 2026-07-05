// M2 图引导自适应诊断 — 真机验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起;课程5(软件工程)39 节点/68 前置边/127 APPROVED 题;
//      e2e/m1-data.json 已按当前库重导(边快照必须=68 prereq,导出命令见 m1-practice-loop.mjs 头注)
// 验收清单(02-M2 §4):
//   1 半弱画像:自适应会话 ≤15 题结束,39 节点全分类(无 UNKNOWN) — UI 驱动作答
//   2 传播演示:答错中层节点探针 → 子孙即时变推断态(进度环红区跳增,Δinferred>0)
//   3 结束后 ColorMap 全图着色(39/39 有掌握度) — graph API + 地图截图(一致率由 T9 eval 量化)
//   4 中途放弃:已实测节点掌握度保留、会话不可续答
//   5 既有固定卷零回归(25 题组卷/提交/回溯) + 401/越权探针
import { chromium } from 'playwright'
import { mkdirSync, readFileSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const COURSE = 5
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })

// ── 库导出数据:答案键/图结构 ─────────────────────────────────────────
const data = JSON.parse(readFileSync(new URL('./m1-data.json', import.meta.url), 'utf-8'))
const nodeById = new Map(data.nodes.map(n => [n.id, n]))
const key = new Map()
for (const o of data.options) {
  if (!key.has(o.q)) key.set(o.q, { correct: [], wrong: [] })
  ;(o.c === 1 ? key.get(o.q).correct : key.get(o.q).wrong).push(o.k)
}
for (const v of key.values()) v.correct.sort()

// ── 半弱弱点集:与 EvalSupport.kahnBackHalf 同构(最长路分层,按(layer,id)排序取后半) ──
function kahnBackHalf(nodes, edges) {
  const ids = nodes.map(n => n.id).sort((a, b) => a - b)
  const idSet = new Set(ids)
  const adj = new Map(ids.map(i => [i, []]))
  const inDeg = new Map(ids.map(i => [i, 0]))
  for (const e of edges) {
    if (e.r !== 1 || !idSet.has(e.f) || !idSet.has(e.t) || e.f === e.t) continue
    adj.get(e.f).push(e.t)
    inDeg.set(e.t, inDeg.get(e.t) + 1)
  }
  const layer = new Map()
  const q = ids.filter(i => inDeg.get(i) === 0)
  for (const i of q) layer.set(i, 0)
  while (q.length) {
    const c = q.shift()
    for (const d of adj.get(c)) {
      layer.set(d, Math.max(layer.get(d) ?? 0, (layer.get(c) ?? 0) + 1))
      inDeg.set(d, inDeg.get(d) - 1)
      if (inDeg.get(d) === 0) q.push(d)
    }
  }
  const sorted = [...ids].sort((a, b) => (layer.get(a) ?? 0) - (layer.get(b) ?? 0) || a - b)
  const take = Math.ceil(sorted.length / 2)
  return new Set(sorted.slice(sorted.length - take))
}
const weak = kahnBackHalf(data.nodes, data.edges)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
page.on('dialog', d => d.accept()) // abandon 的 window.confirm
const fail = []
const check = (name, cond, extra = '') => {
  console.log(`${cond ? '✓' : '✗'} ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) fail.push(name)
}
const info = (msg) => console.log('  ·', msg)

async function api(method, path, token, body) {
  return await page.evaluate(async ({ method, path, token, body }) => {
    const res = await fetch('/api' + path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token != null ? { Authorization: 'Bearer ' + token } : {})
      },
      body: body ? JSON.stringify(body) : undefined
    })
    const json = await res.json().catch(() => null)
    return { status: res.status, body: json }
  }, { method, path, token, body })
}

async function registerStudent(prefix) {
  const name = prefix + Date.now()
  const reg = await api('POST', '/register', null, { username: name, password: 'demo123', realName: 'M2验收', role: 2 })
  if (reg.body?.code !== 0) throw new Error('注册失败 ' + name)
  const login = await api('POST', '/login', null, { username: name, password: 'demo123' })
  const s = { token: login.body?.data?.token, id: login.body?.data?.user?.id, name }
  await api('POST', '/course/enroll', s.token, { studentId: s.id, courseId: COURSE })
  return s
}

/** 读 __wjAdaptive 快照(refs 解包) */
async function adaptiveState() {
  return await page.evaluate(() => {
    const h = window.__wjAdaptive
    if (!h) return null
    return {
      sessionId: h.sessionId.value, done: h.done.value, started: h.started.value,
      probeNodeId: h.probe.value?.nodeId ?? null,
      questions: h.questions.value.map(q => ({ questionId: q.questionId, optionKeys: q.optionKeys })),
      progress: { ...h.progress.value },
      summary: h.summary.value ? { ...h.summary.value, nodes: (h.summary.value.nodes || []).map(n => ({ ...n })) } : null,
      askedCount: h.askedCount.value, canSubmit: h.canSubmit.value,
      loading: h.loading.value, submitting: h.submitting.value,
      error: h.error.value, submitError: h.submitError.value
    }
  })
}

async function waitAdaptive(pred, tries = 50, ms = 300) {
  for (let i = 0; i < tries; i++) {
    const st = await adaptiveState()
    if (st && pred(st)) return st
    await page.waitForTimeout(ms)
  }
  return null
}

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  info(`半弱弱点集 ${weak.size}/39: ` + [...weak].sort((a, b) => a - b).map(i => nodeById.get(i)?.code).join(','))

  const S1 = await registerStudent('m2half')
  const S2 = await registerStudent('m2quit')
  check('两个验收学生注册+选课', !!S1.token && !!S2.token, `S1=${S1.id} S2=${S2.id}`)

  // ══ 验收1+2:UI 驱动半弱画像自适应会话 ═══════════════════════════════
  await page.evaluate(({ u, t }) => {
    localStorage.setItem('wj_token', t)
    localStorage.setItem('wj_user', JSON.stringify(u))
  }, { u: { id: S1.id, role: 2, name: S1.name }, t: S1.token })

  await page.goto(`${base}/diagnostic?courseId=${COURSE}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  const quickCard = page.locator('.diag-mode-card', { hasText: '快速诊断' })
  check('UI:诊断入口出现双模式选择层', await quickCard.count() > 0)
  await page.screenshot({ path: SHOTS + '/m2-mode-select.png' })
  await quickCard.first().click()

  let st = await waitAdaptive(s => s.started && !s.loading && s.probeNodeId != null)
  check('UI:快速诊断页 start 成功(首探针就绪)', !!st, st ? `首探针 node=${nodeById.get(st.probeNodeId)?.code}` : '')
  if (!st) throw new Error('自适应页未就绪,无法继续')

  let propagationSeen = null // {node, dInferred} 首个 Δinferred>0 的 FAIL 探针
  let probeCount = 0
  const seq = []
  while (!st.done && probeCount < 12) {
    probeCount++
    const nodeId = st.probeNodeId
    const pass = !weak.has(nodeId)
    const before = st.progress
    // 逐题点选:pass 选正确键,fail 选错误键
    for (let i = 0; i < st.questions.length; i++) {
      const q = st.questions[i]
      const k = key.get(q.questionId)
      const target = pass ? k?.correct[0] : (k?.wrong[0] ?? q.optionKeys.find(x => x !== k?.correct[0]))
      const optIndex = q.optionKeys.indexOf(target)
      await page.evaluate(({ i, optIndex }) => window.__wjAdaptive.pick(i, optIndex), { i, optIndex: optIndex >= 0 ? optIndex : 0 })
    }
    const ready = await waitAdaptive(s => s.canSubmit, 10, 100)
    check(`探针${probeCount} ${nodeById.get(nodeId)?.code} 全题已选可提交`, !!ready)
    if (probeCount === 1) await page.screenshot({ path: SHOTS + '/m2-probe1.png' })
    await page.evaluate(() => window.__wjAdaptive.submitAnswer())
    st = await waitAdaptive(s => !s.submitting && (s.done || s.probeNodeId !== nodeId || s.submitError))
    if (!st || st.submitError) throw new Error('探针提交失败: ' + (st?.submitError || '超时'))
    seq.push(`${nodeById.get(nodeId)?.code}${pass ? '+' : '-'}`)
    if (!pass && !propagationSeen) {
      const d = st.progress.inferred - before.inferred
      if (d > 0) {
        propagationSeen = { node: nodeById.get(nodeId)?.code, dInferred: d }
        await page.screenshot({ path: SHOTS + '/m2-propagation-jump.png' })
      }
    }
  }
  info('探针序列: ' + seq.join(' '))

  // 验收2:传播演示
  check('验收2:答错节点→子孙即时推断(某 FAIL 探针 Δinferred>0)', !!propagationSeen,
    propagationSeen ? `${propagationSeen.node} Δinferred=+${propagationSeen.dInferred}` : '')

  // 验收1:≤15 题 + 全分类
  check('验收1:会话正常结束(done)', st.done)
  check('验收1:题数 ≤15', st.askedCount <= 15, `实际=${st.askedCount}`)
  check('验收1:UNKNOWN=0(progress)', st.progress.unknown === 0, `unknown=${st.progress.unknown}`)
  const sm = st.summary
  check('验收1:summary 覆盖 39 节点', (sm?.nodes || []).length === 39, `nodes=${sm?.nodes?.length}`)
  check('验收1:summary 无 UNKNOWN 态节点', (sm?.nodes || []).every(n => n.state !== 'UNKNOWN'),
    `tested=${sm?.tested} infP=${sm?.inferredPass} infF=${sm?.inferredFail} unknown=${sm?.unknown}`)
  await page.screenshot({ path: SHOTS + '/m2-adaptive-done.png' })

  // 结果页(T8 证据卡片)
  await page.evaluate(() => window.__wjAdaptive.goResult())
  await page.waitForTimeout(1500)
  check('UI:结束跳转结果页 /result', page.url().includes('/result'))
  const ev = await api('GET', `/diagnostic/adaptive/evidence?studentId=${S1.id}&courseId=${COURSE}`, S1.token)
  check('T8:证据端点返回已完成会话', ev.body?.code === 0 && ev.body?.data?.sessionId != null,
    `tested=${ev.body?.data?.tested} infP=${ev.body?.data?.inferredPass} infF=${ev.body?.data?.inferredFail}`)
  check('T8:证据 unknown=0 且四类计数和=39', ev.body?.data &&
    ev.body.data.unknown === 0 &&
    ev.body.data.tested + ev.body.data.inferredPass + ev.body.data.inferredFail === 39)
  await page.screenshot({ path: SHOTS + '/m2-result-evidence.png' })

  // ══ 验收3:ColorMap 全图着色(39/39 有掌握度) ══════════════════════════
  const g = await api('GET', `/graph/${COURSE}?studentId=${S1.id}`, S1.token)
  const gNodes = g.body?.data?.nodes || []
  const colored = gNodes.filter(n => n.masteryScore != null)
  check('验收3:graph API 39/39 节点有掌握度(全图着色)', gNodes.length === 39 && colored.length === 39,
    `${colored.length}/${gNodes.length}`)
  await page.goto(`${base}/map?courseId=${COURSE}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(2500)
  await page.screenshot({ path: SHOTS + '/m2-colormap-after.png' })
  info('地图截图已存(一致率量化=T9 eval 终表,见 m2-task-9-report.md)')

  // ══ 验收4:中途放弃(API 驱动,S2) ═════════════════════════════════════
  const st2 = await api('POST', '/diagnostic/adaptive/start', S2.token, { studentId: S2.id, courseId: COURSE })
  check('S2:start 成功', st2.body?.code === 0 && st2.body?.data?.probe != null)
  const sess2 = st2.body.data
  const probeNode2 = sess2.probe.nodeId
  const ans2 = sess2.probe.questions.map(q => ({ questionId: q.questionId, studentAnswer: key.get(q.questionId)?.correct[0] || 'A' }))
  const a2 = await api('POST', `/diagnostic/adaptive/${sess2.sessionId}/answer`, S2.token, { studentId: S2.id, answers: ans2 })
  check('S2:答对首探针', a2.body?.code === 0)
  const ab = await api('POST', `/diagnostic/adaptive/${sess2.sessionId}/abandon`, S2.token, { studentId: S2.id })
  check('S2:abandon 成功', ab.body?.code === 0)
  const g2 = await api('GET', `/graph/${COURSE}?studentId=${S2.id}`, S2.token)
  const g2colored = (g2.body?.data?.nodes || []).filter(n => n.masteryScore != null)
  // graph NodeVO 以 nodeCode 为唯一标识(无数字 id)
  check('验收4:已实测节点掌握度保留', g2colored.some(n => n.nodeCode === nodeById.get(probeNode2)?.code),
    `实测节点=${nodeById.get(probeNode2)?.code} 有掌握度行=${g2colored.length}个(${g2colored.map(n => n.nodeCode).join(',')})`)
  // EWMA 按题目关联节点落库(一探针2题可触≥1节点),放弃不调 finalize ⇒ 不得出现全图推断落库
  check('验收4:放弃不落推断掌握度(行数远小于39)', g2colored.length > 0 && g2colored.length < 10, `${g2colored.length}`)
  const cont = await api('POST', `/diagnostic/adaptive/${sess2.sessionId}/answer`, S2.token, { studentId: S2.id, answers: ans2 })
  check('验收4:放弃后不可续答(非0码)', cont.body?.code !== 0 || cont.status >= 400, `status=${cont.status} code=${cont.body?.code}`)

  // ══ 验收5:固定卷零回归 + 鉴权探针 ═══════════════════════════════════
  const paper = await api('GET', `/diagnostic/paper?courseId=${COURSE}`, S2.token)
  const pqs = paper.body?.data?.questions || []
  check('验收5:固定卷组卷 25 题', pqs.length === 25, `${pqs.length}题`)
  const fixedAns = pqs.map(q => ({ questionId: q.questionId, optionKey: key.get(q.questionId)?.correct[0] || 'A' }))
  const fSub = await api('POST', '/diagnostic/submit', S2.token, { studentId: S2.id, courseId: COURSE, answers: fixedAns })
  check('验收5:固定卷提交成功', fSub.body?.code === 0)
  const fRes = await api('GET', `/diagnostic/result?studentId=${S2.id}&courseId=${COURSE}`, S2.token)
  check('验收5:固定卷回溯结果可取', fRes.body?.code === 0)

  const noTok = await api('POST', '/diagnostic/adaptive/start', null, { studentId: S1.id, courseId: COURSE })
  // 项目统一信封风格:HTTP 200 + Result.code=401
  check('鉴权:无令牌 start 拒绝(信封 code=401)', noTok.body?.code === 401, `status=${noTok.status} code=${noTok.body?.code}`)
  const cross = await api('POST', `/diagnostic/adaptive/${sess2.sessionId}/answer`, S1.token, { studentId: S1.id, answers: ans2 })
  check('鉴权:跨学生续答他人会话被拒(403/404/非0)', cross.status === 403 || cross.status === 404 || cross.body?.code !== 0,
    `status=${cross.status}`)

} catch (e) {
  console.error('!! 脚本异常:', e.message)
  fail.push('脚本异常: ' + e.message)
} finally {
  await browser.close()
}

console.log(fail.length === 0 ? '\n== M2 真机验收 ALL PASS ==' : `\n== FAILED ${fail.length} 项 ==\n` + fail.join('\n'))
process.exit(fail.length === 0 ? 0 : 1)
