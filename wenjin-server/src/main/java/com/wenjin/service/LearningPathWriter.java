package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.entity.LearningPath;
import com.wenjin.entity.LearningPathItem;
import com.wenjin.entity.StudentCourse;
import com.wenjin.mapper.LearningPathItemMapper;
import com.wenjin.mapper.LearningPathMapper;
import com.wenjin.mapper.StudentCourseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习路径短写事务：FOR UPDATE 选课行 → 失效旧路径 → 插入新路径 → 写指针。
 * <p>不含 AI；由 {@link com.wenjin.service.impl.PathServiceImpl} 在事务外算拓扑与理由后调用。
 */
@Service
public class LearningPathWriter {

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final int ITEM_PENDING = 0;

    private final LearningPathMapper learningPathMapper;
    private final LearningPathItemMapper learningPathItemMapper;
    private final StudentCourseMapper studentCourseMapper;

    public LearningPathWriter(LearningPathMapper learningPathMapper,
                              LearningPathItemMapper learningPathItemMapper,
                              StudentCourseMapper studentCourseMapper) {
        this.learningPathMapper = learningPathMapper;
        this.learningPathItemMapper = learningPathItemMapper;
        this.studentCourseMapper = studentCourseMapper;
    }

    /**
     * 短事务写路径。返回带 id 的 {@link LearningPath}；steps 的 itemId 回填到入参。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public LearningPath persistPath(Long studentId, Long courseId, Long targetId,
                                    List<PreparedPathStep> prepared) {
        StudentCourse sc = studentCourseMapper.selectForUpdate(studentId, courseId);
        if (sc == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未选课，无法生成学习路径");
        }

        learningPathMapper.update(null, new UpdateWrapper<LearningPath>()
                .eq("student_id", studentId)
                .eq("course_id", courseId)
                .eq("status", STATUS_ACTIVE)
                .set("status", STATUS_INACTIVE));

        LearningPath path = new LearningPath();
        path.setStudentId(studentId);
        path.setCourseId(courseId);
        path.setTargetNodeId(targetId);
        path.setStatus(STATUS_ACTIVE);
        path.setGeneratedAt(LocalDateTime.now());
        learningPathMapper.insert(path);

        for (PreparedPathStep ps : prepared) {
            LearningPathItem item = new LearningPathItem();
            item.setPathId(path.getId());
            item.setNodeId(ps.nodeId());
            item.setStepOrder(ps.stepOrder());
            item.setStatus(ITEM_PENDING);
            item.setReason(ps.reason());
            learningPathItemMapper.insert(item);
            ps.setItemId(item.getId());
        }

        StudentCourse scUpd = new StudentCourse();
        scUpd.setId(sc.getId());
        scUpd.setCurrentPathId(path.getId());
        studentCourseMapper.updateById(scUpd);

        return path;
    }

    /**
     * 事务外预计算的路径步骤（理由已解析；itemId 在落库后回填）。
     */
    public static final class PreparedPathStep {
        private final Long nodeId;
        private final int stepOrder;
        private final String role;
        private final String reason;
        private Long itemId;

        public PreparedPathStep(Long nodeId, int stepOrder, String role, String reason) {
            this.nodeId = nodeId;
            this.stepOrder = stepOrder;
            this.role = role;
            this.reason = reason;
        }

        public Long nodeId() {
            return nodeId;
        }

        public int stepOrder() {
            return stepOrder;
        }

        public String role() {
            return role;
        }

        public String reason() {
            return reason;
        }

        public Long itemId() {
            return itemId;
        }

        public void setItemId(Long itemId) {
            this.itemId = itemId;
        }
    }
}
