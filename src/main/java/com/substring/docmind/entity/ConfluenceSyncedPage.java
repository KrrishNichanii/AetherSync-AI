package com.substring.docmind.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Tracks the last successfully embedded version of each Confluence page so
 * sync runs only re-embed pages that are new or changed.
 */
@Entity
@Table(name = "confluence_synced_pages", indexes = {
        @Index(name = "idx_confluence_synced_pages_space", columnList = "space_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfluenceSyncedPage {

    @Id
    @Column(name = "page_id", length = 64)
    private String pageId;

    @Column(name = "title", length = 1000)
    private String title;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "space_key", nullable = false, length = 255)
    private String spaceKey;

    /** SHA-256 of title + storage body, used to catch edits that don't bump the version. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    /** Absolute Confluence web UI URL used for citations. */
    @Column(name = "web_url", length = 2000)
    private String webUrl;

    @Column(name = "chunk_count")
    private Integer chunkCount;
}
