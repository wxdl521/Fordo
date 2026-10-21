package com.wenjin.mail;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用已审核的 SES 模板发验证码。正文里的 6 位数字填入模板变量 {@code code}。
 */
public class TencentSesEmailSender implements EmailSender {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final SesGateway gateway;
    private final String fromEmailAddress;
    private final long templateId;

    public TencentSesEmailSender(SesGateway gateway, String fromEmailAddress, long templateId) {
        this.gateway = gateway;
        this.fromEmailAddress = fromEmailAddress;
        this.templateId = templateId;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void send(String to, String subject, String text) {
        Matcher m = CODE.matcher(text == null ? "" : text);
        if (!m.find()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "验证码发送失败，请稍后重试");
        }
        String json = "{\"code\":\"" + m.group(1) + "\"}";
        gateway.sendTemplateEmail(fromEmailAddress, to, subject, templateId, json);
    }
}
