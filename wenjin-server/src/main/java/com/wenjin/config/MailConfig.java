package com.wenjin.config;

import com.wenjin.mail.EmailSender;
import com.wenjin.mail.SmtpEmailSender;
import com.wenjin.mail.TencentSdkSesGateway;
import com.wenjin.mail.TencentSesEmailSender;
import com.wenjin.mail.UnavailableEmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class MailConfig {

    @Bean
    EmailSender emailSender(
            @Value("${wenjin.mail.enabled:false}") boolean enabled,
            @Value("${wenjin.mail.host:gz-smtp.qcloudmail.com}") String host,
            @Value("${wenjin.mail.port:465}") int port,
            @Value("${wenjin.mail.username:}") String username,
            @Value("${wenjin.mail.password:}") String password,
            @Value("${wenjin.mail.from:}") String from,
            @Value("${wenjin.mail.from-name:问津}") String fromName,
            @Value("${wenjin.mail.ses.secret-id:}") String secretId,
            @Value("${wenjin.mail.ses.secret-key:}") String secretKey,
            @Value("${wenjin.mail.ses.region:ap-guangzhou}") String region,
            @Value("${wenjin.mail.ses.template-id:0}") long templateId) {
        if (!enabled) {
            return new UnavailableEmailSender();
        }
        String fromAddress = formatFrom(fromName, from);
        if (templateId > 0 && !isBlank(secretId) && !isBlank(secretKey) && !isBlank(from)) {
            return new TencentSesEmailSender(
                    new TencentSdkSesGateway(secretId, secretKey, region),
                    fromAddress,
                    templateId);
        }
        if (!isBlank(username) && !isBlank(password) && !isBlank(from)) {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            sender.setUsername(username);
            sender.setPassword(password);
            sender.setDefaultEncoding("UTF-8");
            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", host);
            return new SmtpEmailSender(sender, from, fromName);
        }
        return new UnavailableEmailSender();
    }

    static String formatFrom(String fromName, String from) {
        if (isBlank(fromName)) {
            return from;
        }
        return fromName + " <" + from + ">";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
