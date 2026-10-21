package com.wenjin.mail;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.SendEmailRequest;
import com.tencentcloudapi.ses.v20201002.models.Template;

/**
 * 腾讯云 SES API（个人实名不能 SMTP，走模板发信）。
 */
public class TencentSdkSesGateway implements SesGateway {

    private final SesClient client;

    public TencentSdkSesGateway(String secretId, String secretKey, String region) {
        this.client = new SesClient(new Credential(secretId, secretKey), region);
    }

    TencentSdkSesGateway(SesClient client) {
        this.client = client;
    }

    @Override
    public void sendTemplateEmail(String fromEmailAddress, String to, String subject,
                                  long templateId, String templateDataJson) {
        try {
            SendEmailRequest req = new SendEmailRequest();
            req.setFromEmailAddress(fromEmailAddress);
            req.setDestination(new String[]{to});
            req.setSubject(subject);
            req.setTriggerType(1L);
            Template template = new Template();
            template.setTemplateID(templateId);
            template.setTemplateData(templateDataJson);
            req.setTemplate(template);
            client.SendEmail(req);
        } catch (TencentCloudSDKException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "验证码发送失败，请稍后重试");
        }
    }
}
