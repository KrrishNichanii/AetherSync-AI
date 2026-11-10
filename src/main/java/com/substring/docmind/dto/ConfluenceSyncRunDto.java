package com.substring.docmind.dto;

import com.substring.docmind.entity.ConfluenceSyncRun;

import java.time.Duration;
import java.time.LocalDateTime;

public record ConfluenceSyncRunDto(
        Long id,
        String spaceKey,
        ConfluenceSyncRun.Trigger triggerType,
        ConfluenceSyncRun.Status status,
        String triggeredBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        int pagesScanned,
        int pagesAdded,
        int pagesUpdated,
        int pagesUnchanged,
        int pagesDeleted,
        int pagesFailed,
        int chunksWritten,
        String errorMessage
) {
    public static ConfluenceSyncRunDto from(ConfluenceSyncRun run) {
        if (run == null) {
            return null;
        }
        Long duration = run.getStartedAt() != null && run.getFinishedAt() != null
                ? Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis()
                : null;
        return new ConfluenceSyncRunDto(run.getId(), run.getSpaceKey(), run.getTriggerType(), run.getStatus(),
                run.getTriggeredBy(), run.getStartedAt(), run.getFinishedAt(), duration, run.getPagesScanned(),
                run.getPagesAdded(), run.getPagesUpdated(), run.getPagesUnchanged(), run.getPagesDeleted(),
                run.getPagesFailed(), run.getChunksWritten(), run.getErrorMessage());
    }
}
