package com.substring.docmind.dto;

import lombok.*;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CitationDto {

    private UUID documentId;
    private String fileName;
    private Integer chunkIndex;
    private Integer pageNumber;
    private String snippet;
    private Double similarityScore;
    private Map<String, Object> metadata;

    /** "confluence" for synced Confluence pages, "upload" for user-uploaded files. */
    private String sourceType;
    /** Human-readable source name: Confluence page title or uploaded file name. */
    private String sourceTitle;
    /** Absolute link back to the original source (Confluence web UI URL); null for uploads. */
    private String sourceUrl;
    /** Confluence page id, when applicable. */
    private String pageId;
}
