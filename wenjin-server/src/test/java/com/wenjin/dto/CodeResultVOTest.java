package com.wenjin.dto;

import com.wenjin.service.code.CodeExecutionService.CaseResult;
import com.wenjin.service.code.CodeExecutionService.CodeJudgeResult;
import com.wenjin.service.code.CodeExecutionService.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeResultVOTest {

    @Test
    @DisplayName("ofJudge：hidden 用例防御性再脱敏（即使上游误填了输入/期望/实际），非 hidden 保留预览")
    void ofJudge_redactsHiddenCasesDefensively() {
        CodeJudgeResult r = new CodeJudgeResult(Verdict.WRONG_ANSWER, 1, 2, null, List.of(
                new CaseResult(1, true, false, Verdict.ACCEPTED, "1 2", "3", "3", 120),
                new CaseResult(2, false, true, Verdict.WRONG_ANSWER, "SECRET_IN", "SECRET_EXP", "SECRET_ACT", 130)),
                250, "输出与期望不一致");

        CodeResultVO vo = CodeResultVO.ofJudge(r);

        assertThat(vo.mode()).isEqualTo(CodeResultVO.MODE_JUDGE);
        assertThat(vo.cases()).hasSize(2);
        CaseResult visible = vo.cases().get(0);
        assertThat(visible.stdinPreview()).isEqualTo("1 2");
        assertThat(visible.expectedPreview()).isEqualTo("3");
        assertThat(visible.actualPreview()).isEqualTo("3");
        CaseResult hidden = vo.cases().get(1);
        assertThat(hidden.hidden()).isTrue();
        assertThat(hidden.passed()).isFalse();
        assertThat(hidden.verdict()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(hidden.durationMs()).isEqualTo(130);
        assertThat(hidden.index()).isEqualTo(2);
        assertThat(hidden.stdinPreview()).isNull();
        assertThat(hidden.expectedPreview()).isNull();
        assertThat(hidden.actualPreview()).isNull();
        assertThat(vo.toString()).doesNotContain("SECRET");
    }
}
