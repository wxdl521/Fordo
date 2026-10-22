package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 教师 AI 助教会话列表项。 */
@Data
public class TeacherConversationVO {

    private Long id;

    private Long courseId;

    private String title;

    private LocalDateTime updatedAt;
}
