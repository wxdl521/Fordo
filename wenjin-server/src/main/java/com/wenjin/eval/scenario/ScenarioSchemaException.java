package com.wenjin.eval.scenario;

/**
 * 评测场景 JSON schema 校验失败。
 */
public class ScenarioSchemaException extends RuntimeException {

    public ScenarioSchemaException(String message) {
        super(message);
    }

    public ScenarioSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
