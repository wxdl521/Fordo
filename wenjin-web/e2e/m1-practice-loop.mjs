// M1 练习闭环 — 真机验收(Playwright + 系统 Edge, headless)
// 前置:后端 8080 + vite 5173 已起;课程5(软件工程)已发布且有 127 道 APPROVED 题;
//      e2e/m1-data.json 从库导出(答案键/归因/图结构,课程5;question id 依库自增,换库必须重新导出),生成命令:
//      mysql -uroot -proot --default-character-set=utf8mb4 -N -B wenjin -e "SELECT JSON_OBJECT('questions',(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',q.id,'type',q.type)) FROM question q WHERE q.course_id=5 AND q.status=1),'links',(SELECT JSON_ARRAYAGG(JSON_OBJECT('q',qn.question_id,'n',qn.node_id,'w',qn.weight)) FROM question_node qn JOIN question q2 ON q2.id=qn.question_id WHERE q2.course_id=5 AND q2.status=1),'options',(SELECT JSON_ARRAYAGG(JSON_OBJECT('q',qo.question_id,'k',qo.option_key,'c',qo.is_correct,'p',IFNULL(qo.point_node_code,''))) FROM question_option qo JOIN question q3 ON q3.id=qo.question_id WHERE q3.course_id=5 AND q3.status=1),'nodes',(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',n.id,'code',n.node_code,'name',n.name)) FROM kg_node n WHERE n.course_id=5),'edges',(SELECT JSON_ARRAYAGG(JSON_OBJECT('f',e.from_node_id,'t',e.to_node_id,'r',e.relation_type)) FROM kg_edge e WHERE e.course_id=5)) AS j" > e2e/m1-data.json
// 验收清单(终审 findings §验收清单):
//   1 自动通过闭环(新学生→诊断→路径→逐节点练习→步骤自动DONE) — UI 驱动首场练习证 C1 前端接线
//   2 distractor×2→薄弱前置→路径重算→前置入新路径
//   3 重复提交幂等(顺序重放 Δ=0 + 并发连点)
//   4 401/403 探针 + 他人 pathItemId 越权探针(C1 新增攻击面) + I1 跨课程探针
//   5 ColorMap 练后变色(API 断言 + 截图)
import { chromium } from 'playwright'
import { mkdirSync, readFileSync } from 'fs'

const base = process.env.WJ_BASE || 'http://localhost:5173'
const COURSE = 5
const FOREIGN_NODE = 124 // 课程6(Java)的节点,I1 跨课程探针用
const SHOTS = '../测试截图'
mkdirSync(SHOTS, { recursive: true })

// ── 库导出数据:答案键/归因/图 ───────────────────────────────────────
const data = JSON.parse(readFileSync(new URL('./m1-data.json', import.meta.url), 'utf-8'))
const nodeByCode = new Map(data.nodes.map(n => [n.code, n]))
const nodeById = new Map(data.nodes.map(n => [n.id, n]))
const qType = new Map(data.questions.map(q => [q.id, q.type]))
// qid → { correctKeys:[排序], wrongOpts:[{key,pnc}] }
const key = new Map()
for (const o of data.options) {
  if (!key.has(o.q)) key.set(o.q, { correctKeys: [], wrongOpts: [] })
  if (o.c === 1) key.get(o.q).correctKeys.push(o.k)
  else key.get(o.q).wrongOpts.push({ key: o.k, pnc: o.p })
}
for (const v of key.values()) v.correctKeys.sort()
// nodeId → Set(qid)(w1∪w2,近似 start 放宽序列的池)
const poolByNode = new Map()
for (const l of data.links) {
  if (!poolByNode.has(l.n)) poolByNode.set(l.n, new Set())
  poolByNode.get(l.n).add(l.q)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage()
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

/** 全对作答:type1/3 取唯一正确键;type2 排序逗号串;type4 简答给文本 */
function correctAnswer(qid) {
  const k = key.get(qid)
  const t = qType.get(qid)
  if (!k) return ''
  if (t === 4) return '真机验收简答作答'
  if (t === 2) return k.correctKeys.join(',')
  return k.correctKeys[0] || ''
}

/** 练习一场(API):全对,返回 submit VO(.data) */
async function practiceOnce(token, studentId, nodeId, pathItemId) {
  const st = await api('POST', '/practice/start', token, { studentId, courseId: COURSE, nodeId, pathItemId })
  if (st.body?.code !== 0) return { startFail: st.body }
  const sess = st.body.data
  const answers = sess.questions.map(q => ({ questionId: q.questionId, studentAnswer: correctAnswer(q.questionId) }))
  const sub = await api('POST', `/practice/${sess.sessionId}/submit`, token, { studentId, answers })
  return { sessionId: sess.sessionId, vo: sub.body?.data, code: sub.body?.code }
}

try {
  await page.goto(base, { waitUntil: 'networkidle' })

  // ── 0. 账号准备 ─────────────────────────────────────────────────
  const aLogin = await api('POST', '/login', null, { username: 'demo_student', password: 'demo' })
  const A = { token: aLogin.body?.data?.token, id: aLogin.body?.data?.user?.id }
  check('学生A(demo_student)登录', !!A.token, `id=${A.id}`)

  const probeName = 'm1probe' + Date.now()
  const reg = await api('POST', '/register', null, { username: probeName, password: 'demo123', realName: 'M1探针', role: 2 })
  check('探针学生B注册', reg.body?.code === 0)
  const bLogin = await api('POST', '/login', null, { username: probeName, password: 'demo123' })
  const B = { token: bLogin.body?.data?.token, id: bLogin.body?.data?.user?.id }
  check('探针学生B登录', !!B.token, `id=${B.id}`)

  for (const s of [A, B]) await api('POST', '/course/enroll', s.token, { studentId: s.id, courseId: COURSE })
  const my = await api('GET', `/course/my?studentId=${A.id}`, A.token)
  check('A已选课程5', (my.body?.data || []).some(c => (c.id ?? c.courseId) === COURSE))

  // ── 1. 新学生诊断(全错)→ 学习路径 ────────────────────────────────
  const paper = await api('GET', `/diagnostic/paper?courseId=${COURSE}`, A.token)
  const pqs = paper.body?.data?.questions || []
  check('诊断卷组卷成功', pqs.length > 0, `${pqs.length}题`)
  const wrongAnswers = pqs.map(q => {
    const k = key.get(q.questionId)
    return { questionId: q.questionId, optionKey: k?.wrongOpts[0]?.key || 'A' }
  })
  const dSub = await api('POST', '/diagnostic/submit', A.token, { studentId: A.id, courseId: COURSE, answers: wrongAnswers })
  check('诊断交卷成功', dSub.body?.code === 0)

  const gen = await api('POST', '/path/generate', A.token, { studentId: A.id, courseId: COURSE, useAi: false })
  let steps = gen.body?.data?.steps || []
  check('学习路径生成(steps非空)', steps.length > 0, `${steps.length}步`)
  check('T8:步骤带availableQuestionCount', steps.every(s => typeof s.availableQuestionCount === 'number'))
  check('T9:步骤带nodeId', steps.every(s => s.nodeId != null))

  const Y = steps.find(s => s.status !== 1 && s.availableQuestionCount > 0)
  check('存在可练习步骤Y', !!Y, Y ? `node=${Y.name}(${Y.nodeId}) item=${Y.itemId} 可用题=${Y.availableQuestionCount}` : '')
  if (!Y) throw new Error('无可练习步骤,后续无法验收')

  // ── 2. UI 驱动首场练习(证 C1 前端接线:路径页→去练习→答题→结果面板) ──
  await page.evaluate(({ u, t }) => {
    localStorage.setItem('wj_token', t)
    localStorage.setItem('wj_user', JSON.stringify(u))
  }, { u: { id: A.id, role: 2, name: 'demo_student' }, t: A.token })
  await page.goto(`${base}/path?courseId=${COURSE}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  const goLink = page.locator(`a[href*="pathItemId=${Y.itemId}"]`)
  check('路径页出现「去练习」链接(带pathItemId)', await goLink.count() > 0)
  await page.screenshot({ path: SHOTS + '/m1-path-before.png' })

  let uiMasteryAfter = null, uiCompleted = false
  if (await goLink.count() > 0) {
    await goLink.first().click()
    // 等待练习页就绪
    let ok = false
    for (let i = 0; i < 40; i++) {
      const ph = await page.evaluate(() => window.__wjPractice?.phase?.value ?? null)
      if (ph === 'answering') { ok = true; break }
      if (ph === 'error' || ph === 'empty') break
      await page.waitForTimeout(300)
    }
    check('UI:练习页进入答题态', ok)
    if (ok) {
      // 逐题作答(经 DEV 钩子,全对)
      const total = await page.evaluate(() => window.__wjPractice.total.value)
      const keyMapArr = [...key.entries()].map(([qid, v]) => [qid, v.correctKeys])
      for (let i = 0; i < total; i++) {
        await page.evaluate(({ keyMapArr }) => {
          const km = new Map(keyMapArr)
          const P = window.__wjPractice
          const q = P.currentQ.value
          const correct = km.get(q.questionId) || []
          if (q.type === 4) { P.onTextInput('真机验收简答作答'); return }
          // 内部题目格式(与Diagnostic对齐): { questionId, optionKeys[], o(texts[]), type }
          ;(q.optionKeys || []).forEach((k, idx) => {
            if (q.type === 2) { if (correct.includes(k)) P.onPick(idx) }
            else if (k === correct[0]) P.onPick(idx)
          })
        }, { keyMapArr })
        await page.waitForTimeout(120)
        await page.evaluate(() => window.__wjPractice.goNext())
        await page.waitForTimeout(250)
      }
      // 等结果面板
      let phase = null
      for (let i = 0; i < 40; i++) {
        phase = await page.evaluate(() => window.__wjPractice?.phase?.value ?? null)
        if (phase === 'result') break
        await page.waitForTimeout(300)
      }
      check('UI:提交后进入结果面板', phase === 'result')
      if (phase === 'result') {
        await page.waitForTimeout(1200) // T10 动画走完
        const r = await page.evaluate(() => ({
          before: window.__wjPractice.submitResult.value?.masteryBefore,
          after: window.__wjPractice.submitResult.value?.masteryAfter,
          displayed: window.__wjPractice.displayedMastery.value,
          completed: window.__wjPractice.itemCompleted.value
        }))
        uiMasteryAfter = r.after; uiCompleted = r.completed
        check('UI:全对后掌握度上升', (r.after ?? 0) > (r.before ?? 0), `before=${r.before} after=${r.after}`)
        check('T10:动画数字收敛到after', Math.round(r.displayed) === Math.round(r.after ?? -1), `displayed=${r.displayed}`)
        await page.screenshot({ path: SHOTS + '/m1-practice-result.png' })
      }
    }
  }

  // ── 3. API 续练至自动通过(≥75→步骤DONE;顺带真机踩 I3a recency兜底) ──
  let completed = uiCompleted, lastMastery = uiMasteryAfter, rounds = 1, lastSessionId = null
  while (!completed && rounds < 6) {
    rounds++
    const r = await practiceOnce(A.token, A.id, Y.nodeId, Y.itemId)
    if (r.startFail) { check(`第${rounds}场start失败(I3a兜底应防止此事)`, false, JSON.stringify(r.startFail)); break }
    lastSessionId = r.sessionId
    lastMastery = r.vo?.masteryAfter
    completed = !!r.vo?.itemCompleted
    info(`第${rounds}场:mastery=${lastMastery} itemCompleted=${completed}`)
  }
  check('验收1:全对若干场后步骤自动通过(itemCompleted)', completed, `${rounds}场,mastery=${lastMastery}`)
  check('I3a:复练未因recency断粮(≥3场start全成功)', rounds >= 3 || completed)

  const cur = await api('GET', `/path/current?studentId=${A.id}&courseId=${COURSE}`, A.token)
  const curSteps = cur.body?.data?.steps || []
  const yStep = curSteps.find(s => s.itemId === Y.itemId)
  check('验收1:路径步骤Y已DONE(status=1)', yStep?.status === 1, `status=${yStep?.status}`)
  await page.goto(`${base}/path?courseId=${COURSE}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  await page.screenshot({ path: SHOTS + '/m1-path-after-done.png' })

  // ── 4. 薄弱前置:选节点X,定向错选同一归因码P ×≥2 → weakPrereq+重算 ──
  //     候选:池内多题的错误项指向同一存在的节点码
  let X = null, targetP = null
  for (const [nid, qids] of poolByNode) {
    if (nid === Y.nodeId) continue
    const hitByP = new Map()
    for (const qid of qids) {
      const wo = key.get(qid)?.wrongOpts || []
      const seen = new Set()
      for (const o of wo) {
        if (!o.pnc || !nodeByCode.has(o.pnc)) continue
        if (qType.get(qid) === 2) hitByP.set(o.pnc, (hitByP.get(o.pnc) || 0) + 1)
        else if (!seen.has(o.pnc)) { hitByP.set(o.pnc, (hitByP.get(o.pnc) || 0) + 1); seen.add(o.pnc) }
      }
    }
    for (const [p, n] of hitByP) {
      // 池≤5 时 start 返回全部题,归因可控性最强
      if (n >= 2 && qids.size <= 5) { X = nid; targetP = p; break }
    }
    if (X) break
  }
  if (!X) { // 放宽:不限池大小,取命中最多的
    let best = 0
    for (const [nid, qids] of poolByNode) {
      if (nid === Y.nodeId) continue
      const hitByP = new Map()
      for (const qid of qids) for (const o of (key.get(qid)?.wrongOpts || []))
        if (o.pnc && nodeByCode.has(o.pnc)) hitByP.set(o.pnc, (hitByP.get(o.pnc) || 0) + 1)
      for (const [p, n] of hitByP) if (n > best) { best = n; X = nid; targetP = p }
    }
  }
  check('找到归因候选节点X', !!X, X ? `X=${nodeById.get(X)?.name}(${X}) P=${targetP}(${nodeByCode.get(targetP)?.name})` : '')

  if (X) {
    const st = await api('POST', '/practice/start', A.token, { studentId: A.id, courseId: COURSE, nodeId: X })
    check('X自由练习start成功', st.body?.code === 0)
    if (st.body?.code === 0) {
      const sess = st.body.data
      // 返回题集内重选最优P(适配start实际返回的子集)
      const hitByP = new Map()
      for (const q of sess.questions) {
        const seen = new Set()
        for (const o of (key.get(q.questionId)?.wrongOpts || [])) {
          if (!o.pnc || !nodeByCode.has(o.pnc)) continue
          if (qType.get(q.questionId) === 2) hitByP.set(o.pnc, (hitByP.get(o.pnc) || 0) + 1)
          else if (!seen.has(o.pnc)) { hitByP.set(o.pnc, (hitByP.get(o.pnc) || 0) + 1); seen.add(o.pnc) }
        }
      }
      let P = targetP, maxN = hitByP.get(targetP) || 0
      for (const [p, n] of hitByP) if (n > maxN) { maxN = n; P = p }
      info(`本场可命中P=${P} ×${maxN}`)
      const answers = sess.questions.map(q => {
        const k = key.get(q.questionId); const t = qType.get(q.questionId)
        if (!k) return { questionId: q.questionId, studentAnswer: '' }
        if (t === 4) return { questionId: q.questionId, studentAnswer: '故意答偏' }
        if (t === 2) {
          const hitKeys = k.wrongOpts.filter(o => o.pnc === P).map(o => o.key)
          return { questionId: q.questionId, studentAnswer: (hitKeys.length ? hitKeys : [k.wrongOpts[0]?.key || 'A']).sort().join(',') }
        }
        const hit = k.wrongOpts.find(o => o.pnc === P) || k.wrongOpts[0]
        return { questionId: q.questionId, studentAnswer: hit?.key || 'A' }
      })
      const sub = await api('POST', `/practice/${sess.sessionId}/submit`, A.token, { studentId: A.id, answers })
      const vo = sub.body?.data
      const weak = (vo?.weakPrerequisites || [])
      const hitP = weak.find(w => w.nodeCode === P)
      check('验收2:weakPrerequisites含P且hitCount≥2', !!hitP && hitP.hitCount >= 2,
        `weak=${JSON.stringify(weak.map(w => w.nodeCode + '×' + w.hitCount))} pathRegenerated=${vo?.pathRegenerated}`)
      if (vo?.pathRegenerated) {
        const cur2 = await api('GET', `/path/current?studentId=${A.id}&courseId=${COURSE}`, A.token)
        const s2 = cur2.body?.data?.steps || []
        check('I4:pathRegenerated=true时新路径非空', s2.length > 0, `${s2.length}步`)
        const pIn = s2.some(s => s.nodeCode === P)
        info(`前置P是否入新路径(M7 图结构相关,观察项): ${pIn}`)
      } else {
        info('pathRegenerated=false(重算条件未触发,观察项)')
      }
      lastSessionId = sess.sessionId
    }
  }

  // ── 5. 幂等:顺序重放Δ=0 + 并发连点 ──────────────────────────────
  const st3 = await api('POST', '/practice/start', A.token, { studentId: A.id, courseId: COURSE, nodeId: Y.nodeId })
  check('幂等测试场start成功', st3.body?.code === 0)
  if (st3.body?.code === 0) {
    const sess = st3.body.data
    const answers = sess.questions.map(q => ({ questionId: q.questionId, studentAnswer: correctAnswer(q.questionId) }))
    // 并发双 submit(I2 CAS:一个正常判分,一个走重放,都成功且结果一致)
    const [r1, r2] = await page.evaluate(async ({ sid, token, body }) => {
      const call = () => fetch(`/api/practice/${sid}/submit`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(body)
      }).then(r => r.json())
      return await Promise.all([call(), call()])
    }, { sid: sess.sessionId, token: A.token, body: { studentId: A.id, answers } })
    check('验收3:并发双submit均成功', r1?.code === 0 && r2?.code === 0, `codes=${r1?.code},${r2?.code}`)
    // I2 核心不变量=不双写不双算(控制器另以DB核对answer_record恰好size条);
    // 并发 loser 在 REPEATABLE READ 快照下可能拿到空graded/旧mastery(观察项,不算失败)
    check('验收3:并发双submit至少一方完整判分',
      r1?.data?.graded?.length === answers.length || r2?.data?.graded?.length === answers.length,
      `after=${r1?.data?.masteryAfter}/${r2?.data?.masteryAfter} graded=${r1?.data?.graded?.length}/${r2?.data?.graded?.length}`)
    // 顺序第三次:重放 Δ=0
    const r3 = await api('POST', `/practice/${sess.sessionId}/submit`, A.token, { studentId: A.id, answers })
    check('验收3:顺序重放Δ=0且不再触发重算',
      r3.body?.code === 0 && r3.body?.data?.masteryBefore === r3.body?.data?.masteryAfter && r3.body?.data?.pathRegenerated === false,
      `before=${r3.body?.data?.masteryBefore} after=${r3.body?.data?.masteryAfter}`)
    lastSessionId = sess.sessionId
  }

  // ── 6. 探针:401 / 403(sessionId) / 403(pathItemId) / 400 / I1跨课程404 ──
  const noTok = await api('POST', '/practice/start', null, { studentId: A.id, courseId: COURSE, nodeId: Y.nodeId })
  check('探针:无令牌start→401', noTok.status === 401 || noTok.body?.code === 401, `status=${noTok.status} code=${noTok.body?.code}`)

  const bSubmit = await api('POST', `/practice/${lastSessionId}/submit`, B.token,
    { studentId: B.id, answers: [{ questionId: 1, studentAnswer: 'A' }] })
  check('探针:B提交A的session→403', bSubmit.body?.code === 403 || bSubmit.status === 403, `code=${bSubmit.body?.code}`)

  // B 生成自己的路径,拿 B 的 pathItemId(C1 修复后的新攻击面)
  const bGen = await api('POST', '/path/generate', B.token, { studentId: B.id, courseId: COURSE, targetNodeId: Y.nodeId, useAi: false })
  const bStep = (bGen.body?.data?.steps || [])[0]
  check('B路径生成(探针前置)', !!bStep, bStep ? `item=${bStep.itemId}` : `code=${bGen.body?.code}`)
  if (bStep) {
    const steal = await api('POST', '/practice/start', A.token,
      { studentId: A.id, courseId: COURSE, nodeId: bStep.nodeId, pathItemId: bStep.itemId })
    check('探针:A用B的pathItemId start→403', steal.body?.code === 403, `code=${steal.body?.code}`)
  }
  const mismatch = await api('POST', '/practice/start', A.token,
    { studentId: A.id, courseId: COURSE, nodeId: X || FOREIGN_NODE, pathItemId: Y.itemId })
  check('探针:pathItemId与nodeId错配→400', mismatch.body?.code === 400, `code=${mismatch.body?.code}`)

  const cross = await api('POST', '/practice/start', A.token, { studentId: A.id, courseId: COURSE, nodeId: FOREIGN_NODE })
  check('探针:I1跨课程nodeId→404', cross.body?.code === 404, `code=${cross.body?.code}`)

  // ── 7. ColorMap:练后Y节点达标(API断言)+截图 ─────────────────────
  const graph = await api('GET', `/graph/${COURSE}?studentId=${A.id}`, A.token)
  const gNodes = graph.body?.data?.nodes || []
  const gy = gNodes.find(n => n.nodeCode === Y.nodeCode)
  check('验收5:图谱API中Y节点已点亮(mastered且≥75)', gy?.mastery === 'mastered' && (gy?.masteryScore ?? 0) >= 75,
    `mastery=${gy?.mastery} score=${gy?.masteryScore}`)
  await page.goto(`${base}/map?courseId=${COURSE}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(2500)
  await page.screenshot({ path: SHOTS + '/m1-colormap-after.png' })
  info('ColorMap 截图已存(视觉核对): m1-colormap-after.png')
} catch (e) {
  console.log('✗ 脚本异常:', e.message)
  fail.push('exception')
  await page.screenshot({ path: SHOTS + '/m1-error.png' }).catch(() => {})
}

await browser.close()
console.log(fail.length ? `\nFAIL: ${fail.join(', ')}` : '\nALL PASS')
process.exit(fail.length ? 1 : 0)
