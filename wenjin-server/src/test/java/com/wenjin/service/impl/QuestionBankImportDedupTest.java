package com.wenjin.service.impl;

import com.wenjin.ai.QuestionBankCleanAiClient;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.entity.Course;
import com.wenjin.entity.Question;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.GraphQueryService;
import com.wenjin.service.QuestionWriteService;
import com.wenjin.support.StemHash;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4：题库导入查重（stem_hash）专项验收。
 */
@ExtendWith(MockitoExtension.class)
class QuestionBankImportDedupTest {

    @Mock QuestionBankCleanAiClient aiClient;
    @Mock GraphQueryService graphQueryService;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock CourseMapper courseMapper;
    @Mock CourseAccessService courseAccessService;

    private static final Long COURSE_ID = 1L;

    private QuestionBankImportServiceImpl service() {
        lenient().doNothing().when(courseAccessService).assertManageCourse(anyLong());
        QuestionWriteService writeService = new QuestionWriteService(
                questionMapper, questionOptionMapper, questionNodeMapper,
                courseMapper, graphQueryService);
        return new QuestionBankImportServiceImpl(
                aiClient, courseAccessService, writeService);
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
        q.setDifficulty(2);
        q.setAnalysis("解析");
        QuestionBankFile.BankOption a = new QuestionBankFile.BankOption();
        a.setKey("A");
        a.setText("正确");
        a.setCorrect(true);
        QuestionBankFile.BankOption b = new QuestionBankFile.BankOption();
        b.setKey("B");
        b.setText("错误");
        b.setCorrect(false);
        q.setOptions(new ArrayList<>(List.of(a, b)));
        return q;
    }

    @Test
    @DisplayName("同批内两条相同题干仅落库一条")
    void sameBatchDuplicateOnlyOnce() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course(COURSE_ID));
        when(graphQueryService.codeToId(COURSE_ID)).thenReturn(Map.of("KT07", 70L));
        stubInsertAutoId();
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>());

        QuestionBankFile bank = bankFile(
                bankQuestion("相同题干？", "KT07"),
                bankQuestion("相同题干？", "KT07"));

        ImportBankResult result = service().importFromJson(COURSE_ID, bank);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isEqualTo(1);
        verify(questionMapper, times(1)).insert(any(Question.class));
    }

    @Test
    @DisplayName("与库内已有题干指纹重复时跳过并正确计数")
    void existingStemHashSkipped() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course(COURSE_ID));
        when(graphQueryService.codeToId(COURSE_ID)).thenReturn(Map.of("KT07", 70L, "KT05", 50L));
        stubInsertAutoId();
        Question dbQuestion = new Question();
        dbQuestion.setStem("库内已有的题干？");
        dbQuestion.setStemHash(StemHash.sha256Hex("库内已有的题干？"));
        when(questionMapper.selectList(any())).thenReturn(List.of(dbQuestion));

        QuestionBankFile bank = bankFile(
                bankQuestion("库内已有的题干？", "KT07"),
                bankQuestion("全新题干？", "KT05"));

        ImportBankResult result = service().importFromJson(COURSE_ID, bank);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isEqualTo(1);
        verify(questionMapper, times(1)).insert(any(Question.class));
    }
}
