package com.wenjin.service.impl;

import com.wenjin.dto.CodeCaseItem;
import com.wenjin.dto.CodeQuestionUpdateRequest;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.dto.TeacherQuestionVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionCodeCase;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.QuestionCodeCaseMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.GraphQueryService;
import com.wenjin.service.QuestionWriteService;
import com.wenjin.service.code.CodeTestCase;
import com.wenjin.support.AnswerGrader;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C2：JSON 导入编程题校验、DbCodeTestCaseSource、教师编辑、AnswerGrader 跳过 CODE。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodeQuestionImportAndSourceTest {

    @Mock GraphQueryService graphQueryService;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock CourseMapper courseMapper;
    @Mock QuestionCodeCaseMapper codeCaseMapper;
    @Mock CourseAccessService courseAccessService;
    @Mock KgNodeMapper nodeMapper;

    private static final Long COURSE_ID = 1L;

    private QuestionWriteService write() {
        Course c = new Course();
        c.setId(COURSE_ID);
        when(courseMapper.selectById(COURSE_ID)).thenReturn(c);
        when(graphQueryService.codeToId(COURSE_ID)).thenReturn(Map.of("KT1", 10L));
        when(questionMapper.selectList(any())).thenReturn(List.of());
        AtomicLong seq = new AtomicLong(500);
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            inv.getArgument(0, Question.class).setId(seq.incrementAndGet());
            return 1;
        });
        return new QuestionWriteService(questionMapper, questionOptionMapper, questionNodeMapper,
                courseMapper, graphQueryService, codeCaseMapper);
    }

    private CodeCaseItem item(String in, String out, Boolean hidden) {
        CodeCaseItem c = new CodeCaseItem();
        c.setStdin(in);
        c.setExpectedStdout(out);
        c.setHidden(hidden);
        return c;
    }

    private QuestionBankFile.BankQuestion codeQ(String stem, List<CodeCaseItem> cases) {
        QuestionBankFile.BankQuestion q = new QuestionBankFile.BankQuestion();
        q.setStem(stem);
        q.setNodeCode("KT1");
        q.setType("CODE");
        q.setStarterCode("public class Main {}");
        q.setCases(cases);
        return q;
    }

    private QuestionBankFile bank(QuestionBankFile.BankQuestion... qs) {
        QuestionBankFile f = new QuestionBankFile();
        f.setQuestions(List.of(qs));
        return f;
    }

    @Test
    @DisplayName("导入 CODE 题：type=5、APPROVED、starterCode、用例按序号落库且含 hidden；不写选项")
    void importsCodeQuestionWithCases() {
        ImportBankResult r = write().persistBank(COURSE_ID, bank(codeQ("求和",
                List.of(item("1 2", "3", false), item("5 5", "10", true)))), false);

        assertThat(r.getImported()).isEqualTo(1);
        assertThat(r.getSkipped()).isZero();
        ArgumentCaptor<Question> qc = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper).insert(qc.capture());
        assertThat(qc.getValue().getType()).isEqualTo(5);
        assertThat(qc.getValue().getStatus()).isEqualTo(QuestionStatus.APPROVED);
        assertThat(qc.getValue().getStarterCode()).isEqualTo("public class Main {}");
        assertThat(qc.getValue().getCodeLanguage()).isEqualTo("java");
        ArgumentCaptor<QuestionCodeCase> cc = ArgumentCaptor.forClass(QuestionCodeCase.class);
        verify(codeCaseMapper, times(2)).insert(cc.capture());
        assertThat(cc.getAllValues()).extracting(QuestionCodeCase::getSeq).containsExactly(1, 2);
        assertThat(cc.getAllValues()).extracting(QuestionCodeCase::getHidden).containsExactly(0, 1);
        verify(questionOptionMapper, never()).insert(any(com.wenjin.entity.QuestionOption.class));
    }

    @Test
    @DisplayName("导入校验：无用例 / 缺 expectedStdout / 用例超限 的 CODE 题被跳过并计入 invalidCodeSkipped")
    void invalidCodeQuestionsSkipped() {
        List<CodeCaseItem> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(item("", "x", false));
        }
        ImportBankResult r = write().persistBank(COURSE_ID, bank(
                codeQ("无用例", List.of()),
                codeQ("缺期望", List.of(item("1", null, false))),
                codeQ("超限", tooMany)), false);

        assertThat(r.getImported()).isZero();
        assertThat(r.getSkipped()).isEqualTo(3);
        assertThat(r.getInvalidCodeSkipped()).isEqualTo(3);
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    @DisplayName("缺省 type 沿用旧行为：单选，不碰用例表")
    void defaultTypeStillSingleChoice() {
        QuestionBankFile.BankQuestion q = new QuestionBankFile.BankQuestion();
        q.setStem("单选题");
        q.setNodeCode("KT1");
        QuestionBankFile.BankOption a = new QuestionBankFile.BankOption();
        a.setKey("A");
        a.setText("x");
        a.setCorrect(true);
        q.setOptions(List.of(a));

        ImportBankResult r = write().persistBank(COURSE_ID, bank(q), false);

        assertThat(r.getImported()).isEqualTo(1);
        ArgumentCaptor<Question> qc = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper).insert(qc.capture());
        assertThat(qc.getValue().getType()).isEqualTo(1);
        verify(codeCaseMapper, never()).insert(any(QuestionCodeCase.class));
    }

    // ── CodeTestCaseSource ─────────────────────────────────────────────────

    private QuestionCodeCase row(int seq, String in, String out, int hidden) {
        QuestionCodeCase c = new QuestionCodeCase();
        c.setQuestionId(7L);
        c.setSeq(seq);
        c.setStdin(in);
        c.setExpectedStdout(out);
        c.setHidden(hidden);
        return c;
    }

    private Question q(int type, int status) {
        Question q = new Question();
        q.setId(7L);
        q.setType(type);
        q.setStatus(status);
        return q;
    }

    @Test
    @DisplayName("DbCodeTestCaseSource：APPROVED 且 CODE 才返回用例；其余返回空")
    void caseSourceOnlyApprovedCode() {
        DbCodeTestCaseSource src = new DbCodeTestCaseSource(questionMapper, codeCaseMapper);
        when(codeCaseMapper.selectList(any())).thenReturn(List.of(row(1, "a", "b", 0), row(2, null, "c", 1)));

        when(questionMapper.selectById(7L)).thenReturn(q(5, QuestionStatus.APPROVED));
        List<CodeTestCase> cases = src.casesFor(7L);
        assertThat(cases).containsExactly(new CodeTestCase(1, "a", "b", false), new CodeTestCase(2, "", "c", true));

        when(questionMapper.selectById(7L)).thenReturn(q(5, QuestionStatus.PENDING));
        assertThat(src.casesFor(7L)).isEmpty();
        when(questionMapper.selectById(7L)).thenReturn(q(1, QuestionStatus.APPROVED));
        assertThat(src.casesFor(7L)).isEmpty();
        when(questionMapper.selectById(7L)).thenReturn(null);
        assertThat(src.casesFor(7L)).isEmpty();
    }

    // ── 教师编辑 + VO ────────────────────────────────────────────────────────

    private TeacherQuestionServiceImpl teacher() {
        return new TeacherQuestionServiceImpl(questionMapper, questionOptionMapper, questionNodeMapper,
                nodeMapper, courseAccessService, codeCaseMapper, write());
    }

    @Test
    @DisplayName("教师 updateCode：整体替换用例、更新 starterCode，返回含 hidden 的全部用例")
    void teacherUpdateCode() {
        Question existing = q(5, QuestionStatus.APPROVED);
        existing.setCourseId(COURSE_ID);
        when(questionMapper.selectById(7L)).thenReturn(existing);
        when(codeCaseMapper.selectList(any())).thenReturn(List.of(row(1, "1", "2", 0), row(2, "3", "4", 1)));
        CodeQuestionUpdateRequest req = new CodeQuestionUpdateRequest();
        req.setStarterCode("class Main {}");
        req.setCases(List.of(item("1", "2", false), item("3", "4", true)));

        TeacherQuestionVO vo = teacher().updateCode(COURSE_ID, 7L, req);

        verify(courseAccessService).assertQuestionsBelongToCourse(COURSE_ID, List.of(7L));
        verify(questionMapper).update(any(), any());
        verify(codeCaseMapper).delete(any());
        verify(codeCaseMapper, times(2)).insert(any(QuestionCodeCase.class));
        assertThat(vo.getStarterCode()).isEqualTo("class Main {}");
        assertThat(vo.getCases()).hasSize(2);
        assertThat(vo.getCases().get(1).getHidden()).isTrue();
        assertThat(vo.getCases().get(1).getStdin()).isEqualTo("3");
    }

    @Test
    @DisplayName("教师 updateCode：非编程题 / 无用例 被拒")
    void teacherUpdateCodeRejects() {
        when(questionMapper.selectById(7L)).thenReturn(q(1, QuestionStatus.APPROVED));
        CodeQuestionUpdateRequest req = new CodeQuestionUpdateRequest();
        req.setCases(List.of(item("", "x", false)));
        assertThatThrownBy(() -> teacher().updateCode(COURSE_ID, 7L, req))
                .isInstanceOf(IllegalArgumentException.class);

        when(questionMapper.selectById(7L)).thenReturn(q(5, QuestionStatus.APPROVED));
        CodeQuestionUpdateRequest empty = new CodeQuestionUpdateRequest();
        empty.setCases(List.of());
        assertThatThrownBy(() -> teacher().updateCode(COURSE_ID, 7L, empty))
                .isInstanceOf(IllegalArgumentException.class);
        verify(codeCaseMapper, never()).insert(any(QuestionCodeCase.class));
    }

    @Test
    @DisplayName("AnswerGrader：编程题一律跳过（correct=null），不按选项逻辑误判")
    void graderSkipsCode() {
        AnswerGrader.GradeResult r = new AnswerGrader().grade(5, "class Main{}", Set.of());
        assertThat(r.correct()).isNull();
        assertThat(r.isGradeable()).isFalse();
    }
}
