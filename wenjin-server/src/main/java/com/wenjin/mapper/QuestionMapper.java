package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.dto.QuestionStatusCount;
import com.wenjin.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 题目表 Mapper。
 * <p>教师题库列表/计数/全量审批下推数据库（LIMIT/OFFSET、GROUP BY、谓词 UPDATE）。
 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    /**
     * 教师题库分页：筛选条件下推 SQL，ORDER BY confidence DESC（nulls last），LIMIT/OFFSET。
     * nodeCode 非空时经 question_node + kg_node JOIN 过滤主考点。
     */
    List<Question> selectTeacherPage(@Param("courseId") Long courseId,
                                     @Param("status") Integer status,
                                     @Param("nodeCode") String nodeCode,
                                     @Param("conf") String conf,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    /** 与 {@link #selectTeacherPage} 相同筛选条件下的总数。 */
    long countTeacherFiltered(@Param("courseId") Long courseId,
                              @Param("status") Integer status,
                              @Param("nodeCode") String nodeCode,
                              @Param("conf") String conf);

    /** 课程题库按 status GROUP BY 计数（不受列表筛选影响）。 */
    List<QuestionStatusCount> countGroupedByStatus(@Param("courseId") Long courseId);

    /**
     * 按筛选谓词单次 UPDATE 审核状态（reviewAll）。
     * nodeCode / conf / status 语义与列表筛选一致。
     *
     * @return 受影响行数
     */
    int updateStatusByFilter(@Param("courseId") Long courseId,
                             @Param("targetStatus") int targetStatus,
                             @Param("status") Integer status,
                             @Param("nodeCode") String nodeCode,
                             @Param("conf") String conf);

    /**
     * 按筛选条件取最多 {@code limit} 条 id（用于分批 UPDATE，每批 ≤500）。
     */
    List<Long> selectIdsByFilter(@Param("courseId") Long courseId,
                                 @Param("status") Integer status,
                                 @Param("nodeCode") String nodeCode,
                                 @Param("conf") String conf,
                                 @Param("limit") int limit);

    /** 将给定 id 列表批量更新状态（须同属 courseId）。 */
    int updateStatusByIds(@Param("courseId") Long courseId,
                          @Param("ids") List<Long> ids,
                          @Param("targetStatus") int targetStatus);
}
