/**
 * 聚光灯导览步骤。target = 页面上 data-tour 的值。
 * finish: 'diagnostic' 结束后进入口诊断；'close' 只关掉。
 */

export const TOUR_STORAGE_PREFIX = 'wj.tour.v1'

export const STUDENT_STEPS = [
  {
    id: 'map-canvas',
    title: '这是染色地图',
    body: '每个圆是一个知识点。现在是示例染色，做完入口诊断后会变成你的真实学情。',
    next: '下一步',
  },
  {
    id: 'map-legend',
    title: '颜色怎么读',
    body: '绿是已掌握，琥珀是薄弱待修，灰是还没学。方框标出你当前卡着的位置。',
    next: '下一步',
  },
  {
    id: 'map-review',
    title: '今日复习',
    body: '学过的点会慢慢淡忘。这里列出今天该捡回来的节点，点开就能练。',
    next: '下一步',
  },
  {
    id: 'map-root',
    title: '薄弱根因回溯',
    body: '不是只会标红。它会顺着前置链往回找，告诉你真正卡住的是哪一环。',
    next: '下一步',
  },
  {
    id: 'nav-diagnostic',
    title: '入口诊断',
    body: '新课先在这里摸底。大约十几分钟，问津才能按你的进度点亮地图。',
    next: '下一步',
  },
  {
    id: 'nav-companion',
    title: 'AI 伴侣',
    body: '随时问「我哪里弱」「给我出题」。它会带着当前课程上下文回答。',
    next: '开始入口诊断',
  },
]

export const TEACHER_STEPS = [
  {
    id: 'nav-graph',
    title: '图谱审核',
    body: '教师端从这里开始。核对节点和前置边，学生地图就靠这份图。',
    next: '下一步',
  },
  {
    id: 'teacher-course',
    title: '当前课程',
    body: '先选中要管的课。后面的审核、题目、学情都跟着这门课走。',
    next: '下一步',
  },
  {
    id: 'teacher-graph-ops',
    title: '图谱操作',
    body: '导入现成图谱，或从课程标准抽取。预览没问题再落到正式图。',
    next: '下一步',
  },
  {
    id: 'teacher-link',
    title: '连线模式',
    body: '打开后可以在节点之间补前置边。先点源点，再点目标点。',
    next: '下一步',
  },
  {
    id: 'nav-questions',
    title: '题目审核',
    body: '题目过审后，学生才能在节点上真正练起来。',
    next: '下一步',
  },
  {
    id: 'nav-dashboard',
    title: '学情看板',
    body: '看班级掌握和薄弱分布。学生用过伴侣后，也可去 Agent 轨迹回放。',
    next: '开始使用',
  },
]

export function stepsForRole(isTeacher) {
  return isTeacher ? TEACHER_STEPS : STUDENT_STEPS
}

export function storageKey(userId, isTeacher) {
  const role = isTeacher ? 'teacher' : 'student'
  const uid = userId != null ? String(userId) : 'anon'
  return `${TOUR_STORAGE_PREFIX}.${role}.${uid}`
}
