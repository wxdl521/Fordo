package com.wenjin.service;

import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.ReviewAllRequest;
import com.wenjin.dto.TeacherQuestionPageVO;

public interface TeacherQuestionService {
    TeacherQuestionPageVO list(Long courseId, Integer status, String nodeCode, String conf, int page, int size);
    int review(Long courseId, QuestionReviewRequest req);
    /** T6: 服务端全量审批，select+update 同一事务，避免前端分页漂移问题。*/
    int reviewAll(Long courseId, ReviewAllRequest req);
}
