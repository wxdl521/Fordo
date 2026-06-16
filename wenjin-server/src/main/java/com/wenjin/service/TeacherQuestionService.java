package com.wenjin.service;

import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.TeacherQuestionPageVO;

public interface TeacherQuestionService {
    TeacherQuestionPageVO list(Long courseId, Integer status, String nodeCode, String conf, int page, int size);
    int review(Long courseId, QuestionReviewRequest req);
}
