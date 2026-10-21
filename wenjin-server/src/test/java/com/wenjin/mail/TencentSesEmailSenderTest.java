package com.wenjin.mail;

import com.wenjin.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TencentSesEmailSenderTest {

    @Test
    void send_fillsTemplateCodeFromBody() {
        RecordingGateway gw = new RecordingGateway();
        TencentSesEmailSender sender = new TencentSesEmailSender(
                gw, "问津项目组 <fordo@mail.thingxue.com>", 100091L);

        sender.send("alice@example.com", "问津注册验证码",
                "你的注册验证码是 654321，5分钟内有效。如非本人操作请忽略。");

        assertThat(gw.from).isEqualTo("问津项目组 <fordo@mail.thingxue.com>");
        assertThat(gw.to).isEqualTo("alice@example.com");
        assertThat(gw.subject).isEqualTo("问津注册验证码");
        assertThat(gw.templateId).isEqualTo(100091L);
        assertThat(gw.json).isEqualTo("{\"code\":\"654321\"}");
    }

    @Test
    void send_withoutSixDigitCode_throws() {
        RecordingGateway gw = new RecordingGateway();
        TencentSesEmailSender sender = new TencentSesEmailSender(gw, "a@b.com", 1L);
        assertThatThrownBy(() -> sender.send("a@b.com", "s", "no code here"))
                .isInstanceOf(BusinessException.class);
        assertThat(gw.to).isNull();
    }

    private static final class RecordingGateway implements SesGateway {
        String from;
        String to;
        String subject;
        long templateId;
        String json;

        @Override
        public void sendTemplateEmail(String fromEmailAddress, String to, String subject,
                                      long templateId, String templateDataJson) {
            this.from = fromEmailAddress;
            this.to = to;
            this.subject = subject;
            this.templateId = templateId;
            this.json = templateDataJson;
        }
    }
}
