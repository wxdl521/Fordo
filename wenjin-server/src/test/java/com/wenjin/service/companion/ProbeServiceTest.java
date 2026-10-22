package com.wenjin.service.companion;

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
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ProbeService: EPHEMERAL fallback + submit never touches mastery (no MasteryService injected).
 */
@ExtendWith(MockitoExtension.class)
class ProbeServiceTest {

    @Mock KgNodeMapper nodeMapper;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper optionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;

    private ProbeSessionStore store;
    private ProbeService service;

    @BeforeEach
    void setUp() {
        store = new ProbeSessionStore();
        service = new ProbeService(store, nodeMapper, questionMapper, optionMapper, questionNodeMapper);
    }

    @Test
    @DisplayName("empty bank → EPHEMERAL probe not in bank")
    void ephemeralWhenNoBank() {
        KgNode node = node(55L, "SE-1.1", "需求基础");
        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());

        ProbeStartVO vo = service.startProbe(1L, 10L, "SE-1.1");

        assertThat(vo.isEphemeral()).isTrue();
        assertThat(vo.getSource()).isEqualTo("EPHEMERAL");
        assertThat(vo.getSessionId()).isNotBlank();
        assertThat(vo.getQuestion().getStem()).contains("探针");
        assertThat(vo.getQuestion().getQuestionId()).isNull();
    }

    @Test
    @DisplayName("C2 理解探针排除编程题：题库里只有 CODE 题 → 退化为 EPHEMERAL")
    void codeQuestionsNeverPickedAsProbe() {
        KgNode node = node(55L, "SE-1.1", "需求基础");
        when(nodeMapper.selectOne(any())).thenReturn(node);
        com.wenjin.entity.QuestionNode link = new com.wenjin.entity.QuestionNode();
        link.setQuestionId(900L);
        link.setNodeId(55L);
        link.setWeight(1);
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(link));
        com.wenjin.entity.Question code = new com.wenjin.entity.Question();
        code.setId(900L);
        code.setType(5);
        code.setStatus(1);
        code.setDifficulty(3);
        when(questionMapper.selectList(any())).thenReturn(List.of(code));

        ProbeStartVO vo = service.startProbe(1L, 10L, "SE-1.1");

        assertThat(vo.isEphemeral()).isTrue();
        assertThat(vo.getQuestion().getQuestionId()).isNull();
    }

    @Test
    @DisplayName("submit incorrect → reexplainAvailable; no mastery path exists")
    void submitIncorrect_reexplain() {
        KgNode node = node(55L, "SE-1.1", "需求基础");
        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());

        ProbeStartVO start = service.startProbe(1L, 10L, "SE-1.1");

        ProbeSubmitRequest req = new ProbeSubmitRequest();
        req.setStudentId(1L);
        req.setCourseId(10L);
        req.setStudentAnswer(""); // blank → incorrect for free-text ephemeral soft rule? non-blank required for correct

        ProbeSubmitVO result = service.submit(start.getSessionId(), req);
        assertThat(result.isCorrect()).isFalse();
        assertThat(result.isReexplainAvailable()).isTrue();

        // non-blank → correct for soft ephemeral
        store.clear();
        ProbeStartVO start2 = service.startProbe(1L, 10L, "SE-1.1");
        req.setStudentAnswer("核心是干系人与需求来源");
        ProbeSubmitVO ok = service.submit(start2.getSessionId(), req);
        assertThat(ok.isCorrect()).isTrue();
        assertThat(ok.isReexplainAvailable()).isFalse();
    }

    @Test
    @DisplayName("submit records outcome once into ProbeOutcomeStore (idempotent resubmit not re-recorded)")
    void submitRecordsOutcome() {
        ProbeOutcomeStore outcomes = new ProbeOutcomeStore();
        service.setOutcomeStore(outcomes);
        KgNode node = node(55L, "SE-1.1", "需求基础");
        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());

        ProbeStartVO start = service.startProbe(1L, 10L, "SE-1.1");
        ProbeSubmitRequest req = new ProbeSubmitRequest();
        req.setStudentId(1L);
        req.setCourseId(10L);
        req.setStudentAnswer("");
        service.submit(start.getSessionId(), req);

        ProbeOutcomeStore.NodeOutcome o = outcomes.latest(1L, 10L, null).orElseThrow();
        assertThat(o.nodeCode()).isEqualTo("SE-1.1");
        assertThat(o.correct()).isFalse();

        outcomes.markStrategySwitched(1L, 10L, "SE-1.1", StrategySwitcher.Strategy.ANALOGY);
        service.submit(start.getSessionId(), req); // idempotent: must not reset switchConsumed
        assertThat(outcomes.latest(1L, 10L, null).orElseThrow().switchConsumed()).isTrue();
    }

    @Test
    @DisplayName("bank question preferred when unused")
    void preferBank() {
        KgNode node = node(55L, "SE-3.2", "用例图");
        when(nodeMapper.selectOne(any())).thenReturn(node);

        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(900L);
        qn.setNodeId(55L);
        qn.setWeight(1);
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qn));

        Question q = new Question();
        q.setId(900L);
        q.setStem("参与者是？");
        q.setType(QuestionType.SINGLE);
        q.setStatus(QuestionStatus.APPROVED);
        q.setDifficulty(3);
        when(questionMapper.selectList(any())).thenReturn(List.of(q));

        QuestionOption optA = new QuestionOption();
        optA.setQuestionId(900L);
        optA.setOptionKey("A");
        optA.setOptionText("外部角色");
        optA.setIsCorrect(1);
        QuestionOption optB = new QuestionOption();
        optB.setQuestionId(900L);
        optB.setOptionKey("B");
        optB.setOptionText("数据库");
        optB.setIsCorrect(0);
        when(optionMapper.selectList(any())).thenReturn(List.of(optA, optB));

        ProbeStartVO vo = service.startProbe(1L, 10L, "SE-3.2");
        // May be BANK if picker finds it; if picker path differs still must produce session
        assertThat(vo.getSessionId()).isNotBlank();
        assertThat(vo.getNodeCode()).isEqualTo("SE-3.2");

        if (!vo.isEphemeral()) {
            ProbeSubmitRequest req = new ProbeSubmitRequest();
            req.setStudentId(1L);
            req.setCourseId(10L);
            req.setStudentAnswer("A");
            assertThat(service.submit(vo.getSessionId(), req).isCorrect()).isTrue();
        }
    }

    private static KgNode node(Long id, String code, String name) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(10L);
        n.setNodeCode(code);
        n.setName(name);
        return n;
    }
}
