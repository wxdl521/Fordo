import { http } from './http.js'

/** 查询学生已选课程列表（含掌握度统计） */
export function getMyCourses(studentId) {
  return http.get('/course/my', { params: { studentId } })
}

/** 查询所有可用课程 */
export function getAvailableCourses() {
  return http.get('/course/available')
}

/** 学生选课 */
export function enroll(studentId, courseId) {
  return http.post('/course/enroll', { studentId, courseId })
}
