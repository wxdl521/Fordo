package com.wenjin.eval.judge;

import com.wenjin.config.AiProperties;
import com.wenjin.eval.metrics.EvalProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the real judge client when evaluation is enabled ({@code wenjin.features.eval-enabled}, default true;
 * the eval profile sets it on). Tests may supply their own {@link JudgeAiClient} bean.
 */
@Configuration
@EnableConfigurationProperties(EvalProperties.class)
@ConditionalOnProperty(prefix = "wenjin.features", name = "eval-enabled", havingValue = "true", matchIfMissing = true)
public class JudgeConfiguration {

    @Bean
    @ConditionalOnMissingBean(JudgeAiClient.class)
    public JudgeAiClient judgeAiClient(AiProperties ai, EvalProperties eval) {
        return new OpenAiCompatibleJudgeAiClient(ai, eval);
    }

    @Bean
    @ConditionalOnMissingBean(JudgeService.class)
    public JudgeService judgeService(JudgeAiClient client) {
        return new JudgeService(client);
    }
}
