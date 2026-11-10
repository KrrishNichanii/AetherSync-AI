package com.substring.docmind.service.confluence;

public class SyncAlreadyRunningException extends RuntimeException {

    private final Long runId;

    public SyncAlreadyRunningException(Long runId) {
        super("A Confluence sync is already running" + (runId != null ? " (run " + runId + ")" : ""));
        this.runId = runId;
    }

    public Long getRunId() {
        return runId;
    }
}
