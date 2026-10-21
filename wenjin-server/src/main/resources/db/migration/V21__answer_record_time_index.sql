-- V21: 错因辅导（get_recent_mistakes）按学生×课程取最近作答，补时间序复合索引
CREATE INDEX `idx_student_course_time` ON `answer_record` (`student_id`, `course_id`, `answered_at`);
