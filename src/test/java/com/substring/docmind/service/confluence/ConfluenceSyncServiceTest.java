package com.substring.docmind.service.confluence;

import com.substring.docmind.config.AppProperties;
import com.substring.docmind.config.ConfluenceProperties;
import com.substring.docmind.entity.ConfluenceSyncRun;
import com.substring.docmind.entity.ConfluenceSyncedPage;
import com.substring.docmind.repository.ConfluenceSyncRunRepository;
import com.substring.docmind.repository.ConfluenceSyncedPageRepository;
import com.substring.docmind.service.confluence.ConfluenceClient.ConfluencePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfluenceSyncServiceTest {

    private ConfluenceClient client;
    private VectorStore vectorStore;
    private ConfluenceSyncedPageRepository pageRepo;
    private ConfluenceSyncRunRepository runRepo;
    private ConfluenceSyncService service;

    @BeforeEach
    void setUp() {
        client = mock(ConfluenceClient.class);
        vectorStore = mock(VectorStore.class);
        pageRepo = mock(ConfluenceSyncedPageRepository.class);
        runRepo = mock(ConfluenceSyncRunRepository.class);
        when(runRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfluenceProperties props = new ConfluenceProperties();
        props.setUsername("bot@example.com");
        props.setApiToken("token");

        AppProperties app = new AppProperties();
        app.getRag().setChunkSize(200);
        app.getRag().setMinChunkSizeChars(50);

        service = new ConfluenceSyncService(client, new ConfluenceContentConverter(), props, app, vectorStore,
                pageRepo, runRepo);
    }

    private static ConfluencePage page(String id, int version, String body) {
        return new ConfluencePage(id, "Page " + id, version, body,
                "https://vector11.atlassian.net/wiki/spaces/MFS/pages/" + id + "/Page");
    }

    private static ConfluenceSyncedPage tracked(ConfluencePage p) {
        return ConfluenceSyncedPage.builder().pageId(p.id()).title(p.title()).versionNumber(p.version())
                .spaceKey("MFS").contentHash(ConfluenceSyncService.contentHash(p))
                .lastSyncedAt(LocalDateTime.now()).chunkCount(1).build();
    }

    private static ConfluenceSyncRun newRun() {
        return ConfluenceSyncRun.builder().id(1L).spaceKey("MFS").triggerType(ConfluenceSyncRun.Trigger.MANUAL)
                .status(ConfluenceSyncRun.Status.RUNNING).startedAt(LocalDateTime.now()).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void newPageIsChunkedEnrichedAndEmbedded() {
        ConfluencePage p = page("101", 1, "<h1>Payments</h1><p>The MFS service processes payments.</p>");
        when(client.fetchAllPublishedPages("MFS")).thenReturn(List.of(p));
        when(pageRepo.findBySpaceKey("MFS")).thenReturn(List.of());

        ConfluenceSyncRun run = newRun();
        service.executeRun(run);

        ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(added.capture());
        Document chunk = added.getValue().getFirst();
        assertThat(chunk.getMetadata())
                .containsEntry("source", "confluence")
                .containsEntry("pageId", "101")
                .containsEntry("pageTitle", "Page 101")
                .containsEntry("spaceKey", "MFS")
                .containsEntry("url", "https://vector11.atlassian.net/wiki/spaces/MFS/pages/101/Page")
                .containsKey("syncId");
        assertThat(chunk.getText()).contains("# Page 101").contains("processes payments");

        // Old chunks for the page are removed, keeping the ones just written.
        ArgumentCaptor<Filter.Expression> deleted = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(vectorStore).delete(deleted.capture());
        assertThat(deleted.getValue().toString()).contains("pageId").contains("101").contains("syncId")
                .contains(chunk.getMetadata().get("syncId").toString()).contains("NE");

        verify(pageRepo).save(any(ConfluenceSyncedPage.class));
        assertThat(run.getStatus()).isEqualTo(ConfluenceSyncRun.Status.SUCCESS);
        assertThat(run.getPagesAdded()).isEqualTo(1);
        assertThat(run.getChunksWritten()).isPositive();
    }

    @Test
    void unchangedPageIsSkipped() {
        ConfluencePage p = page("101", 3, "<p>Stable content for the runbook page.</p>");
        when(client.fetchAllPublishedPages("MFS")).thenReturn(List.of(p));
        when(pageRepo.findBySpaceKey("MFS")).thenReturn(List.of(tracked(p)));

        ConfluenceSyncRun run = newRun();
        service.executeRun(run);

        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        assertThat(run.getPagesUnchanged()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(ConfluenceSyncRun.Status.SUCCESS);
    }

    @Test
    void newVersionIsReembedded() {
        ConfluencePage old = page("101", 3, "<p>Old content for the runbook page.</p>");
        ConfluencePage updated = page("101", 4, "<p>New content for the runbook page.</p>");
        when(client.fetchAllPublishedPages("MFS")).thenReturn(List.of(updated));
        when(pageRepo.findBySpaceKey("MFS")).thenReturn(List.of(tracked(old)));

        ConfluenceSyncRun run = newRun();
        service.executeRun(run);

        verify(vectorStore).add(any());
        assertThat(run.getPagesUpdated()).isEqualTo(1);
        assertThat(run.getPagesAdded()).isZero();
    }

    @Test
    void pageDeletedInConfluenceIsPurged() {
        ConfluencePage gone = page("999", 2, "<p>Deprecated page content here.</p>");
        when(client.fetchAllPublishedPages("MFS")).thenReturn(List.of());
        ConfluenceSyncedPage trackedGone = tracked(gone);
        when(pageRepo.findBySpaceKey("MFS")).thenReturn(List.of(trackedGone));

        ConfluenceSyncRun run = newRun();
        service.executeRun(run);

        ArgumentCaptor<Filter.Expression> deleted = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(vectorStore).delete(deleted.capture());
        assertThat(deleted.getValue().toString()).contains("999").doesNotContain("syncId");
        verify(pageRepo).delete(trackedGone);
        assertThat(run.getPagesDeleted()).isEqualTo(1);
    }

    @Test
    void listingFailureNeverPurgesExistingPages() {
        when(client.fetchAllPublishedPages("MFS")).thenThrow(new ConfluenceApiException("HTTP 503"));
        when(pageRepo.findBySpaceKey("MFS")).thenReturn(List.of(tracked(page("1", 1, "<p>x</p>"))));

        ConfluenceSyncRun run = newRun();
        service.executeRun(run);

        verifyNoInteractions(vectorStore);
        verify(pageRepo, never()).delete(any());
        assertThat(run.getStatus()).isEqualTo(ConfluenceSyncRun.Status.FAILED);
        assertThat(run.getErrorMessage()).contains("HTTP 503");
    }

    @Test
    void embeddingFailureMarksPartialSuccessAndLeavesTrackingUntouched() {
        ConfluencePage ok = page("1", 1, "<p>First page with enough content to embed.</p>");
        ConfluencePage bad = page("2", 1, "<p>Second page with enough content to embed.</p>");
        when(client.fetchAllPublishedPages("MFS")).thenReturn(List.of(ok, bad));
        when(pageRepo.findBySpaceKey("MFS")).thenReturn(List.of());
        doNothing().doThrow(new RuntimeException("quota exceeded")).when(vectorStore).add(any());

        ConfluenceSyncRun run = newRun();
        service.executeRun(run);

        assertThat(run.getStatus()).isEqualTo(ConfluenceSyncRun.Status.PARTIAL_SUCCESS);
        assertThat(run.getPagesAdded()).isEqualTo(1);
        assertThat(run.getPagesFailed()).isEqualTo(1);
        assertThat(run.getErrorMessage()).contains("quota exceeded");
        verify(pageRepo, times(1)).save(any(ConfluenceSyncedPage.class));
    }
}
