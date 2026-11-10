package com.substring.docmind.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Audit log row for one Confluence synchronization run. */
@Entity
@Table(name = "confluence_sync_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfluenceSyncRun {

    public enum Status { RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED }

    public enum Trigger { MANUAL, SCHEDULED, STARTUP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_key", nullable = false)
    private String spaceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Trigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    /** Username of whoever triggered a manual run; null for scheduled runs. */
    private String triggeredBy;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private int pagesScanned;
    private int pagesAdded;
    private int pagesUpdated;
    private int pagesUnchanged;
    private int pagesDeleted;
    private int pagesFailed;
    private int chunksWritten;

    @Column(length = 4000)
    private String errorMessage;
}
