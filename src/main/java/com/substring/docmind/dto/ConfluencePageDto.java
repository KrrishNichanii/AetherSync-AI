package com.substring.docmind.dto;

import com.substring.docmind.entity.ConfluenceSyncedPage;

import java.time.LocalDateTime;

public record ConfluencePageDto(
        String pageId,
        String title,
        Integer versionNumber,
        String spaceKey,
        String webUrl,
        Integer chunkCount,
        LocalDateTime lastSyncedAt
) {
    public static ConfluencePageDto from(ConfluenceSyncedPage p) {
        return new ConfluencePageDto(p.getPageId(), p.getTitle(), p.getVersionNumber(), p.getSpaceKey(),
                p.getWebUrl(), p.getChunkCount(), p.getLastSyncedAt());
    }
}
