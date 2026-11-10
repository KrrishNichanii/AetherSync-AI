package com.substring.docmind.service.confluence;

import com.substring.docmind.config.AppProperties;
import com.substring.docmind.config.ConfluenceProperties;
import com.substring.docmind.dto.ConfluencePageDto;
import com.substring.docmind.dto.ConfluenceSyncRunDto;
import com.substring.docmind.dto.ConfluenceSyncStatusDto;
import com.substring.docmind.entity.ConfluenceSyncRun;
import com.substring.docmind.entity.ConfluenceSyncedPage;
import com.substring.docmind.repository.ConfluenceSyncRunRepository;
import com.substring.docmind.repository.ConfluenceSyncedPageRepository;
import com.substring.docmind.service.confluence.ConfluenceClient.ConfluencePage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps the pgvector store in sync with a Confluence space.
 * <p>
 * Each run lists every published page, compares version number and content hash
 * against {@code confluence_synced_pages}, and only re-embeds pages that are new
 * or changed. Pages that disappeared from Confluence have their vectors purged,
 * but only after a complete, successful listing so a transient API failure can
 * never wipe the index.
 */
@Service
public class ConfluenceSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceSyncService.class);

    /** Metadata value identifying chunks that came from Confluence. */
    public static final String SOURCE_CONFLUENCE = "confluence";

    private static final int MAX_ERROR_LENGTH = 3900;
    private static final int PROGRESS_SAVE_EVERY = 10;

    private final ConfluenceClient client;
    private final ConfluenceContentConverter converter;
    private final ConfluenceProperties properties;
    private final AppProperties appProperties;
    private final VectorStore vectorStore;
    private final ConfluenceSyncedPageRepository pageRepository;
    private final ConfluenceSyncRunRepository runRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Long> currentRunId = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "confluence-sync");
        t.setDaemon(true);
        return t;
    });

    public ConfluenceSyncService(ConfluenceClient client,
                                 ConfluenceContentConverter converter,
                                 ConfluenceProperties properties,
                                 AppProperties appProperties,
                                 VectorStore vectorStore,
                                 ConfluenceSyncedPageRepository pageRepository,
                                 ConfluenceSyncRunRepository runRepository) {
        this.client = client;
        this.converter = converter;
        this.properties = properties;
        this.appProperties = appProperties;
        this.vectorStore = vectorStore;
        this.pageRepository = pageRepository;
        this.runRepository = runRepository;
    }

    /* ─── Triggers ─────────────────────────────────────────────────── */

    /**
     * Starts a sync in the background and returns the freshly created run record.
     *
     * @throws ConfluenceApiException      if Confluence credentials are not configured
     * @throws SyncAlreadyRunningException if another run is in progress
     */
    public ConfluenceSyncRun triggerAsync(ConfluenceSyncRun.Trigger trigger, String triggeredBy) {
        if (!properties.isConfigured()) {
            throw new ConfluenceApiException("Confluence is not configured. Set CONFLUENCE_BASE_URL, "
                    + "CONFLUENCE_USERNAME, CONFLUENCE_API_TOKEN and CONFLUENCE_SPACE_KEY.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new SyncAlreadyRunningException(currentRunId.get());
        }

        ConfluenceSyncRun run;
        try {
            run = runRepository.save(ConfluenceSyncRun.builder()
                    .spaceKey(properties.getSpaceKey())
                    .triggerType(trigger)
                    .triggeredBy(triggeredBy)
                    .status(ConfluenceSyncRun.Status.RUNNING)
                    .startedAt(LocalDateTime.now())
                    .build());
            currentRunId.set(run.getId());
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }

        try {
            executor.execute(() -> {
                try {
                    executeRun(run);
                } catch (Throwable t) {
                    log.error("Confluence sync run {} crashed", run.getId(), t);
                    finish(run, ConfluenceSyncRun.Status.FAILED, "Sync crashed: " + t.getMessage());
                } finally {
                    currentRunId.set(null);
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            currentRunId.set(null);
            running.set(false);
            finish(run, ConfluenceSyncRun.Status.FAILED, "Sync executor rejected the run: " + e.getMessage());
            throw new ConfluenceApiException("Could not start Confluence sync", e);
        }
        return run;
    }

    @Scheduled(cron = "${confluence.sync.cron:0 0 */6 * * *}")
    public void scheduledSync() {
        if (!properties.getSync().isEnabled()) {
            return;
        }
        if (!properties.isConfigured()) {
            log.debug("Skipping scheduled Confluence sync: credentials are not configured");
            return;
        }
        try {
            triggerAsync(ConfluenceSyncRun.Trigger.SCHEDULED, null);
        } catch (SyncAlreadyRunningException e) {
            log.info("Skipping scheduled Confluence sync: run {} is still in progress", e.getRunId());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // A run left RUNNING means the JVM died mid-sync; close it out so status is accurate.
        for (ConfluenceSyncRun stale : runRepository.findByStatus(ConfluenceSyncRun.Status.RUNNING)) {
            stale.setStatus(ConfluenceSyncRun.Status.FAILED);
            stale.setFinishedAt(LocalDateTime.now());
            stale.setErrorMessage("Interrupted by application shutdown");
            runRepository.save(stale);
        }

        if (!properties.isConfigured()) {
            log.warn("Confluence sync is not configured (CONFLUENCE_USERNAME / CONFLUENCE_API_TOKEN missing); "
                    + "the knowledge base will only contain uploaded documents.");
            return;
        }
        log.info("Confluence sync configured for space {} at {} (scheduled: {}, cron: {})",
                properties.getSpaceKey(), properties.normalizedBaseUrl(),
                properties.getSync().isEnabled(), properties.getSync().getCron());

        if (properties.getSync().isOnStartup()) {
            try {
                triggerAsync(ConfluenceSyncRun.Trigger.STARTUP, null);
            } catch (RuntimeException e) {
                log.warn("Could not start Confluence sync on startup: {}", e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Optional<Long> getCurrentRunId() {
        return Optional.ofNullable(currentRunId.get());
    }

    /* ─── Status queries ───────────────────────────────────────────── */

    public ConfluenceSyncStatusDto getStatus() {
        String spaceKey = properties.getSpaceKey();
        ConfluenceSyncRunDto current = getCurrentRunId()
                .flatMap(runRepository::findById)
                .map(ConfluenceSyncRunDto::from)
                .orElse(null);
        ConfluenceSyncRunDto last = runRepository.findFirstByOrderByStartedAtDesc()
                .map(ConfluenceSyncRunDto::from)
                .orElse(null);
        LocalDateTime lastSuccess = runRepository.findFirstByStatusInOrderByStartedAtDesc(
                        List.of(ConfluenceSyncRun.Status.SUCCESS, ConfluenceSyncRun.Status.PARTIAL_SUCCESS))
                .map(ConfluenceSyncRun::getFinishedAt)
                .orElse(null);

        return new ConfluenceSyncStatusDto(
                properties.isConfigured(),
                properties.getSync().isEnabled(),
                properties.getSync().getCron(),
                properties.normalizedBaseUrl(),
                spaceKey,
                isRunning(),
                pageRepository.countBySpaceKey(spaceKey),
                pageRepository.sumChunkCountBySpaceKey(spaceKey),
                lastSuccess,
                current,
                last);
    }

    public List<ConfluenceSyncRunDto> getRecentRuns(int limit) {
        int size = Math.clamp(limit, 1, 100);
        return runRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, size)).stream()
                .map(ConfluenceSyncRunDto::from)
                .toList();
    }

    public Optional<ConfluenceSyncRunDto> getRun(Long id) {
        return runRepository.findById(id).map(ConfluenceSyncRunDto::from);
    }

    public List<ConfluencePageDto> getSyncedPages() {
        return pageRepository.findBySpaceKeyOrderByTitleAsc(properties.getSpaceKey()).stream()
                .map(ConfluencePageDto::from)
                .toList();
    }

    /* ─── Run execution ────────────────────────────────────────────── */

    void executeRun(ConfluenceSyncRun run) {
        String spaceKey = run.getSpaceKey();
        log.info("Confluence sync run {} started for space {} (trigger: {})", run.getId(), spaceKey,
                run.getTriggerType());

        List<ConfluencePage> pages;
        try {
            pages = client.fetchAllPublishedPages(spaceKey);
        } catch (Exception e) {
            log.error("Confluence sync run {} could not list pages", run.getId(), e);
            finish(run, ConfluenceSyncRun.Status.FAILED, "Failed to list pages: " + e.getMessage());
            return;
        }

        Map<String, ConfluenceSyncedPage> tracked = pageRepository.findBySpaceKey(spaceKey).stream()
                .collect(Collectors.toMap(ConfluenceSyncedPage::getPageId, Function.identity()));

        TokenTextSplitter splitter = buildSplitter();
        Set<String> seen = new HashSet<>();
        List<String> errors = new ArrayList<>();
        run.setPagesScanned(pages.size());

        int processed = 0;
        for (ConfluencePage page : pages) {
            if (!seen.add(page.id())) {
                continue; // defensive: duplicate across pagination boundaries
            }
            ConfluenceSyncedPage existing = tracked.get(page.id());
            String hash = contentHash(page);

            if (existing != null && Objects.equals(existing.getVersionNumber(), page.version())
                    && hash.equals(existing.getContentHash())) {
                run.setPagesUnchanged(run.getPagesUnchanged() + 1);
            } else {
                try {
                    int chunks = ingestPage(page, spaceKey, splitter);
                    pageRepository.save(ConfluenceSyncedPage.builder()
                            .pageId(page.id())
                            .title(truncate(page.title(), 1000))
                            .versionNumber(page.version())
                            .spaceKey(spaceKey)
                            .contentHash(hash)
                            .webUrl(truncate(page.webUrl(), 2000))
                            .chunkCount(chunks)
                            .lastSyncedAt(LocalDateTime.now())
                            .build());
                    run.setChunksWritten(run.getChunksWritten() + chunks);
                    if (existing == null) {
                        run.setPagesAdded(run.getPagesAdded() + 1);
                    } else {
                        run.setPagesUpdated(run.getPagesUpdated() + 1);
                    }
                    log.info("Indexed Confluence page '{}' (id={}, v{}, {} chunks)", page.title(), page.id(),
                            page.version(), chunks);
                } catch (Exception e) {
                    // Tracking row is left untouched so the page is retried on the next run.
                    run.setPagesFailed(run.getPagesFailed() + 1);
                    errors.add("'" + page.title() + "' (" + page.id() + "): " + e.getMessage());
                    log.error("Failed to index Confluence page '{}' (id={})", page.title(), page.id(), e);
                }
            }

            if (++processed % PROGRESS_SAVE_EVERY == 0) {
                runRepository.save(run);
            }
        }

        // Purge pages that no longer exist (deleted, archived or unpublished) in Confluence.
        for (ConfluenceSyncedPage stale : tracked.values()) {
            if (seen.contains(stale.getPageId())) {
                continue;
            }
            try {
                deletePageVectors(stale.getPageId(), null);
                pageRepository.delete(stale);
                run.setPagesDeleted(run.getPagesDeleted() + 1);
                log.info("Purged vectors for deleted Confluence page '{}' (id={})", stale.getTitle(),
                        stale.getPageId());
            } catch (Exception e) {
                errors.add("purge '" + stale.getTitle() + "' (" + stale.getPageId() + "): " + e.getMessage());
                log.error("Failed to purge deleted Confluence page {}", stale.getPageId(), e);
            }
        }

        ConfluenceSyncRun.Status status;
        if (errors.isEmpty()) {
            status = ConfluenceSyncRun.Status.SUCCESS;
        } else if (run.getPagesFailed() < seen.size() || seen.isEmpty()) {
            status = ConfluenceSyncRun.Status.PARTIAL_SUCCESS;
        } else {
            status = ConfluenceSyncRun.Status.FAILED;
        }
        finish(run, status, errors.isEmpty() ? null : String.join("\n", errors));

        log.info("Confluence sync run {} finished with {}: scanned={}, added={}, updated={}, unchanged={}, "
                        + "deleted={}, failed={}, chunks={}", run.getId(), status, run.getPagesScanned(),
                run.getPagesAdded(), run.getPagesUpdated(), run.getPagesUnchanged(), run.getPagesDeleted(),
                run.getPagesFailed(), run.getChunksWritten());
    }

    /**
     * Chunks, enriches and embeds one page. New chunks are written before the old
     * ones are removed, so a failure mid-way never leaves the page unsearchable.
     *
     * @return number of chunks written
     */
    private int ingestPage(ConfluencePage page, String spaceKey, TokenTextSplitter splitter) {
        String markdown = converter.toMarkdown(page.storageBody());
        String syncId = UUID.randomUUID().toString();

        if (markdown.isBlank()) {
            deletePageVectors(page.id(), null);
            return 0;
        }

        // Prefixing the title gives every page (and usually its first chunk) a strong topical anchor.
        Document pageDoc = new Document("# " + page.title() + "\n\n" + markdown);
        List<Document> chunks = splitter.apply(List.of(pageDoc));
        if (chunks.isEmpty()) {
            deletePageVectors(page.id(), null);
            return 0;
        }

        List<Document> enriched = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", SOURCE_CONFLUENCE);
            meta.put("pageId", page.id());
            meta.put("pageTitle", page.title());
            meta.put("spaceKey", spaceKey);
            meta.put("url", page.webUrl());
            meta.put("pageVersion", page.version());
            meta.put("chunkIndex", i);
            meta.put("totalChunks", chunks.size());
            meta.put("syncId", syncId);
            // Keeps existing UI components that display "fileName" working.
            meta.put("fileName", page.title());
            enriched.add(new Document(chunks.get(i).getText(), meta));
        }

        vectorStore.add(enriched);
        deletePageVectors(page.id(), syncId);
        return enriched.size();
    }

    /**
     * Deletes Confluence vectors for a page. When {@code keepSyncId} is given,
     * chunks written by that ingest are preserved and only older ones removed.
     */
    private void deletePageVectors(String pageId, String keepSyncId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op base = b.and(b.eq("source", SOURCE_CONFLUENCE), b.eq("pageId", pageId));
        Filter.Expression expression = keepSyncId == null
                ? base.build()
                : b.and(base, b.ne("syncId", keepSyncId)).build();
        vectorStore.delete(expression);
    }

    private TokenTextSplitter buildSplitter() {
        AppProperties.RagProperties rag = appProperties.getRag();
        return TokenTextSplitter.builder()
                .withChunkSize(rag.getChunkSize())
                .withMinChunkSizeChars(rag.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(rag.getMinChunkLengthToEmbed())
                .withMaxNumChunks(rag.getMaxNumChunks())
                .withKeepSeparator(true)
                .build();
    }

    private void finish(ConfluenceSyncRun run, ConfluenceSyncRun.Status status, String errorMessage) {
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now());
        run.setErrorMessage(truncate(errorMessage, MAX_ERROR_LENGTH));
        try {
            runRepository.save(run);
        } catch (Exception e) {
            log.error("Could not persist final state of Confluence sync run {}", run.getId(), e);
        }
    }

    static String contentHash(ConfluencePage page) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(page.title().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(page.storageBody().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
