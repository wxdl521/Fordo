package com.wenjin.mail;

import java.util.ArrayList;
import java.util.List;

/** 测试用：记录发出的邮件，可模拟不可用或发送失败。 */
public class RecordingEmailSender implements EmailSender {

    public final List<String> recipients = new ArrayList<>();
    public final List<String> subjects = new ArrayList<>();
    public final List<String> bodies = new ArrayList<>();
    public boolean available = true;
    public RuntimeException failWith;

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void send(String to, String subject, String text) {
        if (failWith != null) {
            throw failWith;
        }
        recipients.add(to);
        subjects.add(subject);
        bodies.add(text);
    }
}
