package com.wenjin.eval.runner;

import com.wenjin.service.code.CodeExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * Eval profile only: a deterministic, in-memory {@link CodeExecutionService} so scenario replay never needs a
 * sandbox. {@code @Primary} keeps it winning over any real implementation present in the context.
 * Behaviour is fixed: free runs always COMPILE_ERROR; judge always WRONG_ANSWER (1 of 3 cases failing, 1 hidden).
 */
@Configuration
@Profile("eval")
@ConditionalOnProperty(prefix = "wenjin.features", name = "eval-enabled", havingValue = "true", matchIfMissing = true)
public class EvalCodeExecutionConfiguration {

    @Bean
    @Primary
    public CodeExecutionService evalCodeExecutionService() {
        return new FakeEvalCodeExecutionService();
    }

    static final class FakeEvalCodeExecutionService implements CodeExecutionService {

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CodeRunResult run(long studentId, String source, String stdin) {
            return new CodeRunResult(Verdict.COMPILE_ERROR, "", "", null,
                    "Main.java:5: error: ';' expected\n        int total = 0\n                     ^\n1 error",
                    false, 12L, null);
        }

        @Override
        public CodeJudgeResult judge(long studentId, long questionId, String source) {
            return new CodeJudgeResult(Verdict.WRONG_ANSWER, 1, 3, null, List.of(
                    new CaseResult(1, true, false, Verdict.ACCEPTED, "3", "6", "6", 10L),
                    new CaseResult(2, false, false, Verdict.WRONG_ANSWER, "4", "10", "6", 11L),
                    new CaseResult(3, false, true, Verdict.WRONG_ANSWER, null, null, null, 9L)),
                    30L, null);
        }
    }
}
