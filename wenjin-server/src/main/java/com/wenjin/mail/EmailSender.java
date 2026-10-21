package com.wenjin.mail;

/**
 * 发出注册验证码等事务邮件。测试用假实现记录正文；生产走 SMTP。
 */
public interface EmailSender {

    /** 通道是否已配置且可发信。 */
    boolean available();

    /**
     * 发送纯文本邮件。
     *
     * @param to      收件人
     * @param subject 主题
     * @param text    正文（可含验证码）
     */
    void send(String to, String subject, String text);
}
