import { http } from './http.js'

/** 查询当前登录学生已选课程列表（含掌握度统计） */
export function getMyCourses() {
  return http.get('/course/my')
}

/** 查询所有可用课程 */
export function getAvailableCourses() {
  return http.get('/course/available')
}

/** 学生选课（身份由服务端 principal 派生） */
export function enroll(courseId) {
  return http.post('/course/enroll', { courseId })
}
