package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.ExtractCommitResult;
import com.wenjin.dto.ExtractionMetrics;
import com.wenjin.dto.GraphExtractDraftResponse;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.entity.ExtractionReview;
import com.wenjin.mapper.ExtractionReviewMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.support.GraphDraftStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/** 抽取草稿暂存 + 审核提交(diff 算指标→全量替换导入→落库)+ 历史查询。 */
@Service
public class GraphExtractReviewService {

    private final GraphExtractionService graphExtractionService;
    private final GraphDraftStore draftStore;
    private final ExtractionMetricsCalculator metricsCalculator;
    private final GraphService graphService;
    private final ExtractionReviewMapper reviewMapper;
    private final CourseAccessService courseAccessService;

    public GraphExtractReviewService(GraphExtractionService graphExtractionService,
                                     GraphDraftStore draftStore,
                                     ExtractionMetricsCalculator metricsCalculator,
                                     GraphService graphService,
                                     ExtractionReviewMapper reviewMapper,
                                     CourseAccessService courseAccessService) {
        this.graphExtractionService = graphExtractionService;
        this.draftStore = draftStore;
        this.metricsCalculator = metricsCalculator;
        this.graphService = graphService;
        this.reviewMapper = reviewMapper;
        this.courseAccessService = courseAccessService;
    }

    public GraphExtractDraftResponse extractAndStash(String courseCode, MultipartFile file) {
        GraphImportRequest draft = graphExtractionService.extract(courseCode, file);
        Long teacherId = CurrentUser.requireTeacher().userId();
        String draftId = draftStore.save(teacherId, courseCode, draft);
        return new GraphExtractDraftResponse(draftId, draft);
    }

    public GraphImportRequest getDraft(String draftId) {
        Long teacherId = CurrentUser.requireTeacher().userId();
        GraphDraftStore.Entry entry = draftStore.get(draftId, teacherId);
        courseAccessService.assertManageCourseCode(entry.courseCode);
        return entry.draft;
    }

    /** 全量替换导入与指标落库同事务：insert 失败则图谱替换一并回滚。 */
    @Transactional
    public ExtractCommitResult commit(String draftId, GraphImportRequest finalGraph) {
        Long teacherId = CurrentUser.requireTeacher().userId();
        GraphDraftStore.Entry entry = draftStore.get(draftId, teacherId);
        // 尽早校验课程归属，避免无权限主体触发指标计算/导入副作用
        courseAccessService.assertManageCourseCode(entry.courseCode);
        ExtractionMetrics metrics = metricsCalculator.calculate(entry.draft, finalGraph);
        GraphImportResult importResult = graphService.importGraph(entry.courseCode, finalGraph);
        reviewMapper.insert(toReview(entry.courseCode, importResult, metrics));
        draftStore.remove(draftId, teacherId);
        return new ExtractCommitResult(importResult, metrics);
    }

    public List<ExtractionReview> history(String courseCode) {
        courseAccessService.assertManageCourseCode(courseCode);
        return reviewMapper.selectList(new QueryWrapper<ExtractionReview>()
                .eq("course_code", courseCode)
                .orderByDesc("created_at", "id"));
    }

    private ExtractionReview toReview(String courseCode, GraphImportResult result, ExtractionMetrics m) {
        ExtractionReview r = new ExtractionReview();
        r.setCourseCode(courseCode);
        r.setCourseId(result.getCourseId());
        r.setNodeAiCount(m.getNode().getAiCount());
        r.setNodeKeptCount(m.getNode().getKeptCount());
        r.setNodeDeletedCount(m.getNode().getDeletedCount());
        r.setNodeAddedCount(m.getNode().getAddedCount());
        r.setNodeModifiedCount(m.getNode().getModifiedCount());
        r.setNodeFinalCount(m.getNode().getFinalCount());
        r.setNodeRecall(m.getNode().getRecall());
        r.setNodePrecision(m.getNode().getPrecision());
        r.setEdgeAiCount(m.getEdge().getAiCount());
        r.setEdgeKeptCount(m.getEdge().getKeptCount());
        r.setEdgeDeletedCount(m.getEdge().getDeletedCount());
        r.setEdgeAddedCount(m.getEdge().getAddedCount());
        r.setEdgeModifiedCount(m.getEdge().getModifiedCount());
        r.setEdgeFinalCount(m.getEdge().getFinalCount());
        r.setEdgeRecall(m.getEdge().getRecall());
        r.setEdgePrecision(m.getEdge().getPrecision());
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }
}
