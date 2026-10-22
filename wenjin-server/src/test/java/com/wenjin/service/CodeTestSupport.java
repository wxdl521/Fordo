package com.wenjin.service;

import com.wenjin.service.code.CodeExecutionService;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 测试辅助：构造 ObjectProvider&lt;CodeExecutionService&gt;。 */
public final class CodeTestSupport {

    private CodeTestSupport() {
    }

    /** 无 CodeExecutionService bean。 */
    public static ObjectProvider<CodeExecutionService> noCodeService() {
        return providerOf(null);
    }

    @SuppressWarnings("unchecked")
    public static ObjectProvider<CodeExecutionService> providerOf(CodeExecutionService svc) {
        ObjectProvider<CodeExecutionService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(svc);
        return p;
    }
}
