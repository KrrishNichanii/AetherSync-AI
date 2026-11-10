package com.substring.docmind.controller;

import com.substring.docmind.dto.ApiResponse;
import com.substring.docmind.dto.ConfluencePageDto;
import com.substring.docmind.dto.ConfluenceSyncRunDto;
import com.substring.docmind.dto.ConfluenceSyncStatusDto;
import com.substring.docmind.entity.ConfluenceSyncRun;
import com.substring.docmind.entity.User;
import com.substring.docmind.exception.ResourceNotFoundException;
import com.substring.docmind.service.confluence.ConfluenceSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/sync/confluence")
@Tag(name = "Confluence Sync", description = "Synchronize the Confluence knowledge base into the vector store")
@RequiredArgsConstructor
public class ConfluenceSyncController {

    private final ConfluenceSyncService syncService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Trigger an incremental Confluence workspace sync (runs in the background)")
    public ResponseEntity<ApiResponse<ConfluenceSyncRunDto>> triggerSync(Authentication authentication) {
        String triggeredBy = authentication != null && authentication.getPrincipal() instanceof User u
                ? u.getUsername()
                : null;
        ConfluenceSyncRun run = syncService.triggerAsync(ConfluenceSyncRun.Trigger.MANUAL, triggeredBy);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ok(ConfluenceSyncRunDto.from(run),
                "Confluence sync started"));
    }

    @GetMapping("/status")
    @Operation(summary = "Current sync status, index size and the most recent run")
    public ResponseEntity<ApiResponse<ConfluenceSyncStatusDto>> status() {
        return ResponseEntity.ok(ok(syncService.getStatus(), null));
    }

    @GetMapping("/runs")
    @Operation(summary = "Recent sync runs (newest first)")
    public ResponseEntity<ApiResponse<List<ConfluenceSyncRunDto>>> runs(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ok(syncService.getRecentRuns(limit), null));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Details and error log of a single sync run")
    public ResponseEntity<ApiResponse<ConfluenceSyncRunDto>> run(@PathVariable Long id) {
        ConfluenceSyncRunDto run = syncService.getRun(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sync run " + id + " not found"));
        return ResponseEntity.ok(ok(run, null));
    }

    @GetMapping("/pages")
    @Operation(summary = "Confluence pages currently indexed in the knowledge base")
    public ResponseEntity<ApiResponse<List<ConfluencePageDto>>> pages() {
        return ResponseEntity.ok(ok(syncService.getSyncedPages(), null));
    }

    private static <T> ApiResponse<T> ok(T data, String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
