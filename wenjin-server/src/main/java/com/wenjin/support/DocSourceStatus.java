package com.wenjin.support;

/**
 * doc_source.status 稳定取值（M8）。
 */
public final class DocSourceStatus {

    public static final int INGESTING = 0;
    public static final int READY = 1;
    public static final int FAILED = 2;
    public static final int ARCHIVED = 3;

    private DocSourceStatus() {}
}
