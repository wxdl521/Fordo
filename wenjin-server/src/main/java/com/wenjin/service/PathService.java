package com.wenjin.service;

import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PathGenerateRequest;

/**
 * 学习路径服务（PRD 8.3）：聚焦卡点生成拓扑路径、加载当前路径、标记完成。
 */
public interface PathService {

    /** 生成（重算）学习路径：旧路径置失效，写入新路径与明细。 */
    LearningPathVO generate(PathGenerateRequest req);

    /** 加载当前有效路径（无则空）。 */
    LearningPathVO getCurrent(Long studentId, Long courseId);

    /** 标记某步完成（幂等：仅未完成时写 completed_at）。 */
    void completeItem(Long itemId);
}
