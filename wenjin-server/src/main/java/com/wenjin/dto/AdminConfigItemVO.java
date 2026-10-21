package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 系统配置单项。
 *
 * <p>secret 项：{@code value} / {@code defaultValue} 恒为 null，只给 {@code configured} 与 {@code masked}
 * （末 4 位，前端拼成 {@code ••••abcd}）。{@code configured / masked / options / min / max / nullable} 只在适用类型下出现。</p>
 */
@Data
public class AdminConfigItemVO {

    private String key;
    private String label;
    private String description;
    /** string / secret / bool / number / enum */
    private String type;

    /** 当前生效值（secret 恒 null） */
    private Object value;
    /** 启动时快照的默认值（yml / 环境变量；secret 恒 null） */
    private Object defaultValue;
    /** 库里有覆盖值 */
    private boolean overridden;

    /** secret：当前是否已配置 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean configured;
    /** secret：末 4 位（如 abcd，前端显示 ••••abcd）；未配置或密钥过短为 null */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String masked;
    /** enum：可选值 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> options;
    /** number：下限（含） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Number min;
    /** number：上限（含） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Number max;
    /** number：true=可留空（各功能用内置默认） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean nullable;

    /** 覆盖值最后保存时间（ISO-8601）；未覆盖为 null */
    private String updatedAt;
    /** 覆盖值最后保存人 sys_user.id；未覆盖为 null */
    private Long updatedBy;
}
