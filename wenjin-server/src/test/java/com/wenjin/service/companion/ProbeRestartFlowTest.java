package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.ProbeStartVO;
import com.wenjin.dto.ProbeSubmitRequest;
import com.wenjin.dto.ProbeSubmitVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.companion.StrategySwitcher.Strategy;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 「模拟重启」：每次 {@link #restart()} 都用同一对 repository 重建 store / ProbeService / ReflectionCoordinator，
 * 验证学生刷新页面后探针仍可提交、30 分钟换策略状态机跨重启不丢。
 */
@ExtendWith(MockitoExtension.class)
class ProbeRestartFlowTest {

    @Mock KgNodeMapper nodeMapper;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper optionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;

    private final ProbeTestClock clock = new ProbeTestClock(Instant.parse("2026-10-23T08:00:00Z"));
    private final InMemoryProbeSessionRepository sessionRepo = new InMemoryProbeSessionRepository();
    private final InMemoryProbeOutcomeRepository outcomeRepo = new InMemoryProbeOutcomeRepository();

    private ProbeSessionStore sessions;
    private ProbeOutcomeStore outcomes;
    private ProbeService service;
    private ReflectionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        KgNode node = new KgNode();
        node.setId(55L);
        node.setCourseId(10L);
        node.setNodeCode("SE-1.1");
        node.setName("需求基础");
        lenient().when(nodeMapper.selectOne(any())).thenReturn(node);
        lenient().when(questionNodeMapper.selectList(any())).thenReturn(List.of());
        restart();
    }

    /** 新进程：全部有状态组件重建，数据只在 repository 里。 */
    private void restart() {
        sessions = new ProbeSessionStore(sessionRepo, clock, () -> Duration.ofHours(24));
        outcomes = new ProbeOutcomeStore(outcomeRepo, clock);
        service = new ProbeService(sessions, nodeMapper, questionMapper, optionMapper, questionNodeMapper);
        service.setOutcomeStore(outcomes);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setReflectionEnabled(true);
        coordinator = new ReflectionCoordinator(flags, service, outcomes);
    }

    private ProbeSubmitVO submit(String sessionId, String answer) {
        ProbeSubmitRequest req = new ProbeSubmitRequest();
        req.setStudentId(1L);
        req.setCourseId(10L);
        req.setStudentAnswer(answer);
        return service.submit(sessionId, req);
    }

    @Test
    @DisplayName("发题 → 重启 → 提交成功；再次重启后重复提交返回首次结果")
    void submitAfterRestart() {
        ProbeStartVO start = service.startProbe(1L, 10L, "SE-1.1");
        restart();

        ProbeSubmitVO first = submit(start.getSessionId(), "核心是干系人");
        assertThat(first.isCorrect()).isTrue();

        restart();
        ProbeSubmitVO again = submit(start.getSessionId(), ""); // 空答案若重判将变错
        assertThat(again.isCorrect()).isTrue();
    }

    @Test
    @DisplayName("TTL 过期的会话提交 → 探针会话不存在；归属不符仍 FORBIDDEN")
    void expiredAndForeignSession() {
        ProbeStartVO start = service.startProbe(1L, 10L, "SE-1.1");
        restart();

        ProbeSubmitRequest foreign = new ProbeSubmitRequest();
        foreign.setStudentId(2L);
        foreign.setCourseId(10L);
        foreign.setStudentAnswer("x");
        assertThatThrownBy(() -> service.submit(start.getSessionId(), foreign))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");

        clock.advance(Duration.ofHours(25));
        assertThatThrownBy(() -> submit(start.getSessionId(), "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("换策略：未通过 → 重启 → 选下一策略；重启后同一次失败不会再切换（switchConsumed 持久）")
    void strategySwitchAcrossRestarts() {
        ProbeStartVO start = service.startProbe(1L, 10L, "SE-1.1");
        submit(start.getSessionId(), ""); // incorrect
        restart();

        Optional<ReflectionCoordinator.StrategyDirective> d = coordinator.resolveStrategyDirective(1L, 10L, "SE-1.1");
        assertThat(d).isPresent();
        assertThat(d.get().strategy()).isEqualTo(Strategy.ANALOGY);
        assertThat(d.get().previousStrategy()).isNull();

        restart();
        assertThat(coordinator.resolveStrategyDirective(1L, 10L, "SE-1.1")).isEmpty();

        // 再探一次仍未通过：带上上次生效策略，并从下一个策略起选
        ProbeStartVO second = service.startProbe(1L, 10L, "SE-1.1");
        submit(second.getSessionId(), "");
        restart();
        Optional<ReflectionCoordinator.StrategyDirective> d2 = coordinator.resolveStrategyDirective(1L, 10L, "SE-1.1");
        assertThat(d2).isPresent();
        assertThat(d2.get().previousStrategy()).isEqualTo(Strategy.ANALOGY);
        assertThat(d2.get().strategy()).isEqualTo(Strategy.EXAMPLE);
    }

    @Test
    @DisplayName("30 分钟窗口按持久化的探针时间计算，重启不重置；过窗不再换策略")
    void windowSurvivesRestart() {
        ProbeStartVO start = service.startProbe(1L, 10L, "SE-1.1");
        submit(start.getSessionId(), "");

        clock.advance(Duration.ofMinutes(31));
        restart();
        assertThat(coordinator.resolveStrategyDirective(1L, 10L, "SE-1.1")).isEmpty();
    }

    @Test
    @DisplayName("题库题去重跨重启：第二次探针优先选另一道没发过的题")
    void bankQuestionNotRepeatedAfterRestart() {
        QuestionNode qn1 = link(900L);
        QuestionNode qn2 = link(901L);
        lenient().when(questionNodeMapper.selectList(any())).thenReturn(List.of(qn1, qn2));
        lenient().when(questionMapper.selectList(any())).thenReturn(List.of(bankQuestion(900L), bankQuestion(901L)));
        lenient().when(optionMapper.selectList(any())).thenReturn(List.of(option("A", 1), option("B", 0)));

        ProbeStartVO first = service.startProbe(1L, 10L, "SE-1.1");
        assertThat(first.isEphemeral()).isFalse();
        restart();
        ProbeStartVO second = service.startProbe(1L, 10L, "SE-1.1");
        assertThat(second.isEphemeral()).isFalse();
        assertThat(second.getQuestion().getQuestionId()).isNotEqualTo(first.getQuestion().getQuestionId());
    }

    @Test
    @DisplayName("不泄漏答案：下发的 ProbeStartVO 序列化后不含标准答案键 / 正确标记（BANK 与 EPHEMERAL）")
    void startVoNeverExposesAnswer() throws Exception {
        lenient().when(questionNodeMapper.selectList(any())).thenReturn(List.of(link(900L)));
        lenient().when(questionMapper.selectList(any())).thenReturn(List.of(bankQuestion(900L)));
        lenient().when(optionMapper.selectList(any())).thenReturn(List.of(option("A", 0), option("Z", 1)));
        ProbeStartVO bank = service.startProbe(1L, 10L, "SE-1.1");
        assertThat(bank.isEphemeral()).isFalse();

        lenient().when(questionNodeMapper.selectList(any())).thenReturn(List.of()); // empty bank → EPHEMERAL
        ProbeStartVO ephemeral = service.startProbe(1L, 10L, "SE-1.1");
        assertThat(ephemeral.isEphemeral()).isTrue();

        ObjectMapper om = new ObjectMapper();
        for (ProbeStartVO vo : List.of(bank, ephemeral)) {
            String json = om.writeValueAsString(vo).toLowerCase();
            assertThat(json).doesNotContain("correct", "answer");
        }
        // 服务端仍持有答案用于判分
        assertThat(sessions.find(bank.getSessionId())).get()
                .extracting(ProbeSessionStore.ProbeSession::correctKey).isEqualTo("Z");
    }

    @Test
    @DisplayName("探针属性：LimitsProperties.reflection 默认 TTL 24h、保留 14 天")
    void limitsDefaults() {
        LimitsProperties.Reflection r = new LimitsProperties().getReflection();
        assertThat(r.getProbeSessionTtlHours()).isEqualTo(24);
        assertThat(r.getProbeRetentionDays()).isEqualTo(14);
    }

    private static QuestionNode link(long questionId) {
        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(questionId);
        qn.setNodeId(55L);
        qn.setWeight(1);
        return qn;
    }

    private static Question bankQuestion(long id) {
        Question q = new Question();
        q.setId(id);
        q.setStem("参与者是？");
        q.setType(QuestionType.SINGLE);
        q.setStatus(QuestionStatus.APPROVED);
        q.setDifficulty(3);
        return q;
    }

    private static QuestionOption option(String key, int correct) {
        QuestionOption o = new QuestionOption();
        o.setQuestionId(900L);
        o.setOptionKey(key);
        o.setOptionText("opt " + key);
        o.setIsCorrect(correct);
        return o;
    }
}
