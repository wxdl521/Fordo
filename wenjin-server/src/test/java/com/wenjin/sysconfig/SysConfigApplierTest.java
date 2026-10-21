package com.wenjin.sysconfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * SysConfigApplier：就绪时先快照默认值再应用覆盖；任何异常都不外抛（不阻断启动）。
 */
class SysConfigApplierTest {

    @Test
    @DisplayName("ApplicationReady：snapshotDefaults 先于 applyStoredOverrides")
    void snapshotThenApply() {
        SysConfigService service = mock(SysConfigService.class);

        new SysConfigApplier(service).onApplicationReady();

        InOrder order = inOrder(service);
        order.verify(service).snapshotDefaults();
        order.verify(service).applyStoredOverrides();
    }

    @Test
    @DisplayName("初始化抛异常也不外抛")
    void failureSwallowed() {
        SysConfigService service = mock(SysConfigService.class);
        doThrow(new IllegalStateException("boom")).when(service).snapshotDefaults();

        assertThatCode(() -> new SysConfigApplier(service).onApplicationReady()).doesNotThrowAnyException();
    }
}
