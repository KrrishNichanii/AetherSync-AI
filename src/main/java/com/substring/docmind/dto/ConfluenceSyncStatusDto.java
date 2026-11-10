package com.substring.docmind.dto;

import java.time.LocalDateTime;

public record ConfluenceSyncStatusDto(
        boolean configured,
        boolean scheduleEnabled,
        String cron,
        String baseUrl,
        String spaceKey,
        boolean running,
        long syncedPages,
        long indexedChunks,
        LocalDateTime lastSuccessfulSyncAt,
        ConfluenceSyncRunDto currentRun,
        ConfluenceSyncRunDto lastRun
) {
}
