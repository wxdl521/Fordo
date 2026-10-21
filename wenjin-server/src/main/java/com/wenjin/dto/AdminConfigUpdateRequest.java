package com.wenjin.dto;

import lombok.Data;
import lombok.ToString;

/**
 * 保存一个配置覆盖值：{@code {value}}。
 * <p>类型随配置项而定（bool / number / string），由服务层按白名单登记校验；secret 传明文（只走 HTTPS、只进密文落库）。
 * string 空串非法——想恢复默认请用 DELETE。</p>
 */
@Data
public class AdminConfigUpdateRequest {

    /** secret 为明文：不进 toString，防止被任何日志带出 */
    @ToString.Exclude
    private Object value;
}
