package com.wenjin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.QuestionBankCleanAiClient;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.entity.Course;
import com.wenjin.entity.Question;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.GraphQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4：题库导入查重 N+1 消除后的专项验收测试。
 * <p>
 * 覆盖：(1) 同批内两条相同题干仅落库一条；(2) 与库内已有题干重复时跳过并正确计数。
 */
@ExtendWith(MockitoExtension.class)
class QuestionBankImportDedupTest {

    @Mock QuestionBankCleanAiClient aiClient;
    @Mock GraphQueryService graphQueryService;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock CourseMapper courseMapper;

    private static final Long COURSE_ID = 1L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QuestionBankImportServiceImpl service() {
        return new QuestionBankImportServiceImpl(
                aiClient, graphQueryService, kgNodeMapper,
                questionMapper, questionOptionMapper, questionNodeMapper,
                courseMapper, OBJECT_MAPPER);
    }

    private void stubInsertAutoId() {
        AtomicLong seq = new AtomicLong(2000L);
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            inv.getArgument(0, Question.class).setId(seq.incrementAndGet());
            return 1;
        });
    }

    private Course course(Long id) {
        Course c = new Course();
        c.setId(id);
        c.setCode("52015CC4B4");
        c.setName("软件工程");
        return c;
    }

    private QuestionBankFile bankFile(QuestionBankFile.BankQuestion... questions) {
        QuestionBankFile f = new QuestionBankFile();
        f.setCourseCode("52015CC4B4");
        f.setQuestions(new ArrayList<>(List.of(questions)));
        return f;
    }

    private QuestionBankFile.BankQuestion bankQuestion(String stem, String nodeCode) {
        QuestionBankFile.BankQuestion q = new QuestionBankFile.BankQuestion();
        q.setStem(stem);
        q.setNodeCode(nodeCode);
        q.setChapter("软件工程概述");
        q.setDifficulty(2);
        q.setAnalysis("解析");
        QuestionBankFile.BankOption a = new QuestionBankFile.BankOption();
        a.setKey("A"); a.setText("正确项"); a.setCorrect(true);
        QuestionBankFile.BankOption b = new QuestionBankFile.BankOption();
        b.setKey("B"); b.setText("干扰项"); b.setCorrect(false);
        q.setOptions(new ArrayList<>(List.of(a, b)));
        return q;
    }

    // ──────────────── 用例 1：同批内两条相同题干只落库一条 ────────────────

    @Test
    @DisplayName("1 同批两条相同题干：imported=1，第二条计入 skipped（批内去重）")
    void sameStemInBatch_onlyOneInserted() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course(COURSE_ID));
        when(graphQueryService.codeToId(COURSE_ID)).thenReturn(Map.of("KT07", 70L));
        stubInsertAutoId();
        // 批前查询：库内无已有题干
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>());

        // 两条题干完全相同
        QuestionBankFile bank = bankFile(
                bankQuestion("什么是瀑布模型？", "KT07"),
                bankQuestion("什么是瀑布模型？", "KT07"));

        ImportBankResult result = service().importFromJson(COURSE_ID, bank);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isEqualTo(1);
        // question 表只写一行
        verify(questionMapper, times(1)).insert(any(Question.class));
    }

    // ──────────────── 用例 2：库内已存在题干 → 跳过且计数正确 ────────────────

    @Test
    @DisplayName("2 库内已有题干：与已有题干重复的那条 skipped，全新题干正常入库")
    void stemAlreadyInDb_skippedWithCorrectCounter() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course(COURSE_ID));
        when(graphQueryService.codeToId(COURSE_ID)).thenReturn(Map.of("KT07", 70L, "KT05", 50L));
        stubInsertAutoId();
        // 批前查询：库内已有 "库内已有的题干？"
        Question dbQuestion = new Question();
        dbQuestion.setStem("库内已有的题干？");
        when(questionMapper.selectList(any())).thenReturn(List.of(dbQuestion));

        QuestionBankFile bank = bankFile(
                bankQuestion("库内已有的题干？", "KT07"),   // 与 DB 重复 → skip
                bankQuestion("全新的题干内容？", "KT05"));   // 新题 → import

        ImportBankResult result = service().importFromJson(COURSE_ID, bank);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isEqualTo(1);
        // 仅新题落库
        verify(questionMapper, times(1)).insert(any(Question.class));
    }
}
