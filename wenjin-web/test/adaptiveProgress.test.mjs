import assert from 'node:assert/strict'
import { ringSegments } from '../src/utils/adaptiveProgress.js'

const C = 100  // 便于按比例断言的整周长

// 1) 三段齐全 → 弧长按占比、offset 累计、总和=周长
{
  const segs = ringSegments({ tested: 2, inferred: 3, unknown: 5 }, C)
  assert.equal(segs.length, 3, '三段都非零应各占一段')
  assert.deepEqual(segs.map((s) => s.key), ['tested', 'inferred', 'unknown'], '固定顺序 实测→推断→未知')
  assert.equal(segs[0].length, 20, 'tested 2/10 → 20')
  assert.equal(segs[1].length, 30, 'inferred 3/10 → 30')
  assert.equal(segs[2].length, 50, 'unknown 5/10 → 50')
  assert.equal(segs[0].offset, 0, '首段从环起点开始')
  assert.equal(segs[1].offset, 20, '次段接在首段之后')
  assert.equal(segs[2].offset, 50, '末段接在前两段之后')
  const totalLen = segs.reduce((a, s) => a + s.length, 0)
  assert.ok(Math.abs(totalLen - C) < 1e-9, '各段弧长之和 = 周长')
}

// 2) 全部未知（诊断刚开始）→ 未知独占整环
{
  const segs = ringSegments({ tested: 0, inferred: 0, unknown: 7 }, C)
  assert.equal(segs.length, 1, '只有未知一段')
  assert.equal(segs[0].key, 'unknown')
  assert.equal(segs[0].length, C, '独占整环')
  assert.equal(segs[0].offset, 0)
}

// 3) 计数为 0 的段被跳过，且不占 offset
{
  const segs = ringSegments({ tested: 4, inferred: 0, unknown: 4 }, C)
  assert.deepEqual(segs.map((s) => s.key), ['tested', 'unknown'], '推断为 0 应缺席')
  assert.equal(segs[0].length, 50)
  assert.equal(segs[1].offset, 50, 'unknown 紧接 tested，无空档')
}

// 4) 总数为 0 → 空数组（视图只画底环）
{
  assert.deepEqual(ringSegments({ tested: 0, inferred: 0, unknown: 0 }, C), [])
}

// 5) 缺字段 / 非法值 → 当作 0，负数被夹到 0
{
  assert.deepEqual(ringSegments(undefined, C), [], 'progress 缺失不抛错')
  assert.deepEqual(ringSegments({}, C), [], '空对象等同全 0')
  const segs = ringSegments({ tested: -3, inferred: 2, unknown: undefined }, C)
  assert.equal(segs.length, 1, '负实测被夹到 0，只剩推断')
  assert.equal(segs[0].key, 'inferred')
  assert.equal(segs[0].length, C)
}

// 6) 周长非法（0/负）→ 空数组，避免除零/负弧
{
  assert.deepEqual(ringSegments({ tested: 1, inferred: 1, unknown: 1 }, 0), [])
  assert.deepEqual(ringSegments({ tested: 1, inferred: 1, unknown: 1 }, -5), [])
}

// 7) 真实周长（2πr）下弧长按比例，且不超周长
{
  const circ = 2 * Math.PI * 70
  const segs = ringSegments({ tested: 1, inferred: 1, unknown: 2 }, circ)
  const totalLen = segs.reduce((a, s) => a + s.length, 0)
  assert.ok(Math.abs(totalLen - circ) < 1e-9, '真实周长下各段之和仍 = 周长')
  for (const s of segs) assert.ok(s.length <= circ + 1e-9, '单段不超过周长')
}

console.log('adaptiveProgress.test.mjs: 全部通过')
