package com.wenjin.sysconfig;

/**
 * 可编辑配置项的值类型；{@link #wire()} 是接口里 {@code type} 字段的取值。
 */
public enum ConfigType {
    STRING("string"),
    /** 密钥：AES-GCM 加密落库，接口只回 configured + 末 4 位掩码 */
    SECRET("secret"),
    BOOL("bool"),
    NUMBER("number"),
    ENUM("enum");

    private final String wire;

    ConfigType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
