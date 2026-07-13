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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
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
    private static final int MAX_DEADLOCK_ATTEMPTS = 3;
    private static final long DEADLOCK_BACKOFF_MS = 40L;

    private final LearningPathMapper learningPathMapper;
    private final LearningPathItemMapper learningPathItemMapper;
    private final StudentCourseMapper studentCourseMapper;
    private final TransactionTemplate requiredTx;

    public LearningPathWriter(LearningPathMapper learningPathMapper,
                              LearningPathItemMapper learningPathItemMapper,
                              StudentCourseMapper studentCourseMapper,
                              PlatformTransactionManager transactionManager) {
        this.learningPathMapper = learningPathMapper;
        this.learningPathItemMapper = learningPathItemMapper;
        this.studentCourseMapper = studentCourseMapper;
        this.requiredTx = new TransactionTemplate(transactionManager);
        this.requiredTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.requiredTx.setName("learning-path-persist");
    }

    /**
     * 短事务写路径。返回带 id 的 {@link LearningPath}；steps 的 itemId 回填到入参。
     */
    public LearningPath persistPath(Long studentId, Long courseId, Long targetId,
                                    List<PreparedPathStep> prepared) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return persistPathLocked(studentId, courseId, targetId, prepared);
        }
        return runWithDeadlockRetry(() -> requiredTx.execute(status ->
                persistPathLocked(studentId, courseId, targetId, prepared)));
    }

    private LearningPath persistPathLocked(Long studentId, Long courseId, Long targetId,
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

    private <T> T runWithDeadlockRetry(java.util.function.Supplier<T> action) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return action.get();
            } catch (RuntimeException ex) {
                if (attempt >= MAX_DEADLOCK_ATTEMPTS || !isRetryableConcurrency(ex)) {
                    throw ex;
                }
                try {
                    Thread.sleep(DEADLOCK_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private static boolean isRetryableConcurrency(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof DeadlockLoserDataAccessException
                    || t instanceof CannotAcquireLockException) {
                return true;
            }
            if (t instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
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
