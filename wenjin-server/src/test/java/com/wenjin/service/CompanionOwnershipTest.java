package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 会话归属校验单测（Mockito + CurrentUser，无 @SpringBootTest）：
 * 非属主 getMessages / deleteConversation → FORBIDDEN；属主 → 放行。
 *
 * <p>注：手写 BaseMapper stub 在 MP 3.5.7 因接口抽象方法数量大幅增加而编译困难；
 * 此处改用 Mockito（已在 spring-boot-starter-test classpath，现有测试亦在用），
 * 满足"不用 @SpringBootTest"约束。</p>
 */
@ExtendWith(MockitoExtension.class)
class CompanionOwnershipTest {

    @Mock
    private CompanionConversationMapper conversationMapper;

    @Mock
    private CompanionMessageMapper messageMapper;

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    // ─── 工厂方法 ────────────────────────────────────────────────────────────

    private CompanionServiceImpl service() {
        // 其余依赖不涉及：传 null 不会被触达
        return new CompanionServiceImpl(
                conversationMapper, messageMapper,
                null, null, null, null, null);
    }

    private CompanionConversation conv(long convId, long ownerStudentId) {
        CompanionConversation c = new CompanionConversation();
        c.setId(convId);
        c.setStudentId(ownerStudentId);
        return c;
    }

    // ─── getMessages ─────────────────────────────────────────────────────────

    @Test
    void getMessages_nonOwner_throwsForbidden() {
        CurrentUser.set(1L); // 当前用户 1，会话属主 2
        when(conversationMapper.selectById(99L)).thenReturn(conv(99L, 2L));

        assertThatThrownBy(() -> service().getMessages(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    int code = ((BusinessException) ex).getCode();
                    assert code == ResultCode.FORBIDDEN.getCode()
                            : "期望 403，实际 " + code;
                });
    }

    @Test
    void getMessages_owner_doesNotThrow() {
        CurrentUser.set(2L); // 当前用户 2 == 属主 2
        when(conversationMapper.selectById(99L)).thenReturn(conv(99L, 2L));
        when(messageMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertThatCode(() -> service().getMessages(99L)).doesNotThrowAnyException();
    }

    @Test
    void getMessages_noCurrentUser_throwsUnauthorized() {
        // 未登录（CurrentUser 未设）
        assertThatThrownBy(() -> service().getMessages(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    int code = ((BusinessException) ex).getCode();
                    assert code == ResultCode.UNAUTHORIZED.getCode()
                            : "期望 401，实际 " + code;
                });
    }

    @Test
    void getMessages_conversationNotFound_throwsForbidden() {
        CurrentUser.set(2L);
        when(conversationMapper.selectById(999L)).thenReturn(null); // 会话不存在

        assertThatThrownBy(() -> service().getMessages(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    int code = ((BusinessException) ex).getCode();
                    assert code == ResultCode.FORBIDDEN.getCode()
                            : "期望 403，实际 " + code;
                });
    }

    // ─── deleteConversation ──────────────────────────────────────────────────

    @Test
    void deleteConversation_nonOwner_throwsForbidden() {
        CurrentUser.set(1L); // 当前用户 1，属主 2
        when(conversationMapper.selectById(99L)).thenReturn(conv(99L, 2L));

        assertThatThrownBy(() -> service().deleteConversation(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    int code = ((BusinessException) ex).getCode();
                    assert code == ResultCode.FORBIDDEN.getCode()
                            : "期望 403，实际 " + code;
                });
    }

    @Test
    void deleteConversation_owner_doesNotThrow() {
        CurrentUser.set(2L); // 当前用户 2 == 属主 2
        when(conversationMapper.selectById(99L)).thenReturn(conv(99L, 2L));
        when(messageMapper.delete(org.mockito.ArgumentMatchers.any())).thenReturn(0);
        when(conversationMapper.deleteById(99L)).thenReturn(1);

        assertThatCode(() -> service().deleteConversation(99L)).doesNotThrowAnyException();
    }
}
