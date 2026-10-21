package com.wenjin.mail;

/**
 * 腾讯云 SES SendEmail 的窄接口，便于单测替掉 SDK。
 */
public interface SesGateway {

    void sendTemplateEmail(String fromEmailAddress, String to, String subject,
                           long templateId, String templateDataJson);
}
