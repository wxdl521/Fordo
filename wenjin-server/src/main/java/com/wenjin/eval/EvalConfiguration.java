package com.wenjin.eval;

import com.wenjin.eval.hard.HardAssertChecker;
import com.wenjin.eval.scenario.ScenarioLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Eval profile beans (M6). Active under {@code eval} profile when {@code wenjin.features.eval-enabled=true}.
 * <p>Fixture wiring that needs DB is provided by IT/test config; pure helpers are always available here.
 */
@Configuration
@Profile("eval")
@ConditionalOnProperty(prefix = "wenjin.features", name = "eval-enabled", havingValue = "true", matchIfMissing = true)
public class EvalConfiguration {

    @Bean
    public ScenarioLoader scenarioLoader() {
        return new ScenarioLoader();
    }

    @Bean
    public HardAssertChecker hardAssertChecker() {
        return new HardAssertChecker();
    }
}
