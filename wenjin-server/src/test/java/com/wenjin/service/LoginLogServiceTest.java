package com.wenjin.service;

import com.wenjin.entity.LoginLog;
import com.wenjin.mapper.LoginLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginLogServiceTest {

    @Mock
    private LoginLogMapper loginLogMapper;

    private LoginLog captured() {
        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(loginLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("成功登录：success=1，无失败原因")
    void record_success() {
        new LoginLogService(loginLogMapper).record(7L, "alice", true, "IGNORED", "10.0.0.1", "Mozilla/5.0");

        LoginLog row = captured();
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getUsername()).isEqualTo("alice");
        assertThat(row.getSuccess()).isEqualTo(1);
        assertThat(row.getFailReason()).as("成功时不带失败原因").isNull();
        assertThat(row.getIp()).isEqualTo("10.0.0.1");
        assertThat(row.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(row.getCreatedAt()).as("交给数据库 DEFAULT CURRENT_TIMESTAMP").isNull();
    }

    @Test
    @DisplayName("失败登录：success=0，带失败原因；用户名不存在时 user_id 为 null")
    void record_failure() {
        new LoginLogService(loginLogMapper).record(null, "ghost", false, "BAD_CREDENTIALS", "10.0.0.2", null);

        LoginLog row = captured();
        assertThat(row.getUserId()).isNull();
        assertThat(row.getSuccess()).isZero();
        assertThat(row.getFailReason()).isEqualTo("BAD_CREDENTIALS");
        assertThat(row.getUserAgent()).isNull();
    }

    @Test
    @DisplayName("超长字段按列宽截断（username 64 / ip 64 / user_agent 255）")
    void record_truncatesLongFields() {
        new LoginLogService(loginLogMapper).record(null, "u".repeat(100), false, "BAD_CREDENTIALS",
                "9".repeat(100), "a".repeat(400));

        LoginLog row = captured();
        assertThat(row.getUsername()).hasSize(64);
        assertThat(row.getIp()).hasSize(64);
        assertThat(row.getUserAgent()).hasSize(255);
    }

    @Test
    @DisplayName("写库失败：吞掉（只打 warn），不影响登录请求")
    void record_insertFailure_isSwallowed() {
        when(loginLogMapper.insert(any(LoginLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> new LoginLogService(loginLogMapper)
                .record(1L, "alice", true, null, "10.0.0.1", "UA"))
                .doesNotThrowAnyException();
    }
}
