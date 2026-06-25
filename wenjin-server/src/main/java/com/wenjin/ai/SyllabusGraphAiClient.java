package com.wenjin.ai;

import com.wenjin.dto.GraphImportRequest;

/** 课程标准文本 → 图谱草稿(nodes/edges,推断边以『待复核』前缀标注)。 */
public interface SyllabusGraphAiClient {
    GraphImportRequest extract(String syllabusText);
}
