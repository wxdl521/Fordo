package com.wenjin.mail;

/** 未配置 SMTP 时的占位实现：发码接口返回服务不可用。 */
public class UnavailableEmailSender implements EmailSender {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void send(String to, String subject, String text) {
        throw new IllegalStateException("mail disabled");
    }
}
