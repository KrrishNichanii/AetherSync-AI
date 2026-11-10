package com.substring.docmind.service.confluence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.substring.docmind.config.ConfluenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Thin client over the Confluence Cloud REST API v2.
 * <p>
 * Endpoints used:
 * <ul>
 *   <li>{@code GET /api/v2/spaces?keys={key}} to resolve the numeric space id</li>
 *   <li>{@code GET /api/v2/spaces/{id}/pages?status=current&body-format=storage} for published pages</li>
 * </ul>
 */
@Component
public class ConfluenceClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClient.class);
    private static final int MAX_ATTEMPTS = 4;

    private final ConfluenceProperties properties;
    private volatile RestClient restClient;
    private volatile String clientFingerprint;

    public ConfluenceClient(ConfluenceProperties properties) {
        this.properties = properties;
    }

    /** A published page with its storage-format body and absolute web URL. */
    public record ConfluencePage(String id, String title, int version, String storageBody, String webUrl) {
    }

    /** Resolves the space key (e.g. "MFS") to Confluence's numeric space id. */
    public String resolveSpaceId(String spaceKey) {
        URI uri = UriComponentsBuilder.fromUriString(properties.normalizedBaseUrl() + "/api/v2/spaces")
                .queryParam("keys", spaceKey)
                .queryParam("limit", 1)
                .build()
                .toUri();

        SpaceListResponse response = withRetry("resolve space " + spaceKey,
                () -> client().get().uri(uri).retrieve().body(SpaceListResponse.class));

        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new ConfluenceApiException("Confluence space '" + spaceKey
                    + "' was not found or the configured account cannot access it.");
        }
        return response.results().getFirst().id();
    }

    /** Fetches every published page in the space, following cursor pagination. */
    public List<ConfluencePage> fetchAllPublishedPages(String spaceKey) {
        String spaceId = resolveSpaceId(spaceKey);
        int limit = Math.clamp(properties.getPageLimit(), 1, 250);

        URI next = UriComponentsBuilder.fromUriString(properties.normalizedBaseUrl() + "/api/v2/spaces/{id}/pages")
                .queryParam("status", "current")
                .queryParam("body-format", "storage")
                .queryParam("limit", limit)
                .buildAndExpand(spaceId)
                .encode()
                .toUri();

        List<ConfluencePage> pages = new ArrayList<>();
        int requestCount = 0;
        while (next != null) {
            URI requestUri = next;
            PageListResponse response = withRetry("list pages of space " + spaceKey,
                    () -> client().get().uri(requestUri).retrieve().body(PageListResponse.class));
            requestCount++;

            if (response == null) {
                throw new ConfluenceApiException("Empty response while listing pages of space " + spaceKey);
            }

            String linkBase = response.links() != null && StringUtils.hasText(response.links().base())
                    ? response.links().base()
                    : properties.normalizedBaseUrl();

            if (response.results() != null) {
                for (PageResult result : response.results()) {
                    pages.add(toPage(result, linkBase, spaceKey));
                }
            }

            next = resolveNextLink(response.links());
        }

        log.info("Fetched {} published Confluence pages from space {} in {} request(s)",
                pages.size(), spaceKey, requestCount);
        return pages;
    }

    private ConfluencePage toPage(PageResult result, String linkBase, String spaceKey) {
        int version = result.version() != null && result.version().number() != null ? result.version().number() : 0;
        String body = result.body() != null && result.body().storage() != null
                && result.body().storage().value() != null ? result.body().storage().value() : "";

        String webUrl;
        if (result.links() != null && StringUtils.hasText(result.links().webui())) {
            webUrl = stripTrailingSlash(linkBase) + result.links().webui();
        } else {
            webUrl = properties.normalizedBaseUrl() + "/spaces/" + spaceKey + "/pages/" + result.id();
        }
        String title = StringUtils.hasText(result.title()) ? result.title() : "Untitled page " + result.id();
        return new ConfluencePage(result.id(), title, version, body, webUrl);
    }

    /**
     * The v2 API returns {@code _links.next} as a path relative to the site origin
     * (e.g. {@code /wiki/api/v2/spaces/1/pages?cursor=...}), already URL-encoded.
     */
    private URI resolveNextLink(Links links) {
        if (links == null || !StringUtils.hasText(links.next())) {
            return null;
        }
        String next = links.next();
        if (next.startsWith("http://") || next.startsWith("https://")) {
            return URI.create(next);
        }
        URI base = URI.create(properties.normalizedBaseUrl());
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        return URI.create(origin + (next.startsWith("/") ? next : "/" + next));
    }

    private <T> T withRetry(String operation, Supplier<T> call) {
        long backoffMs = 1000;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException e) {
                HttpStatusCode status = e.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    throw new ConfluenceApiException("Confluence rejected the credentials (HTTP " + status.value()
                            + ") while trying to " + operation
                            + ". Check CONFLUENCE_USERNAME / CONFLUENCE_API_TOKEN and space permissions.", e);
                }
                if (status.value() != 429 || attempt >= MAX_ATTEMPTS) {
                    throw new ConfluenceApiException("Confluence API error (HTTP " + status.value() + ") while trying to "
                            + operation + ": " + e.getResponseBodyAsString(), e);
                }
                backoffMs = retryAfterMillis(e, backoffMs);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ConfluenceApiException("Confluence API unavailable while trying to " + operation
                            + ": " + e.getMessage(), e);
                }
            }
            log.warn("Confluence call '{}' failed (attempt {}/{}), retrying in {} ms", operation, attempt,
                    MAX_ATTEMPTS, backoffMs);
            sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, 30_000);
        }
    }

    private long retryAfterMillis(HttpClientErrorException e, long fallback) {
        String retryAfter = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (retryAfter != null) {
            try {
                return Math.min(Long.parseLong(retryAfter.trim()) * 1000, 60_000);
            } catch (NumberFormatException ignored) {
                // HTTP-date form; fall back to exponential backoff
            }
        }
        return fallback;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConfluenceApiException("Confluence sync interrupted", ie);
        }
    }

    /** Lazily builds the client so credential changes in config are honoured without restarts in tests. */
    private RestClient client() {
        if (!properties.isConfigured()) {
            throw new ConfluenceApiException("Confluence is not configured. Set CONFLUENCE_BASE_URL, "
                    + "CONFLUENCE_USERNAME, CONFLUENCE_API_TOKEN and CONFLUENCE_SPACE_KEY.");
        }
        String fingerprint = properties.normalizedBaseUrl() + "|" + properties.getUsername() + "|"
                + properties.getApiToken().hashCode();
        RestClient current = restClient;
        if (current == null || !fingerprint.equals(clientFingerprint)) {
            synchronized (this) {
                if (restClient == null || !fingerprint.equals(clientFingerprint)) {
                    HttpClient httpClient = HttpClient.newBuilder()
                            .connectTimeout(properties.getConnectTimeout())
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
                    factory.setReadTimeout(properties.getReadTimeout());

                    restClient = RestClient.builder()
                            .requestFactory(factory)
                            .defaultHeaders(h -> {
                                h.setBasicAuth(properties.getUsername(), properties.getApiToken());
                                h.set("Accept", "application/json");
                            })
                            .build();
                    clientFingerprint = fingerprint;
                }
                current = restClient;
            }
        }
        return current;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /* ─── Confluence v2 JSON shapes (only the fields we use) ─── */

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpaceListResponse(List<Space> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Space(String id, String key, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageListResponse(List<PageResult> results, @JsonProperty("_links") Links links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageResult(String id, String status, String title, String spaceId, Version version, Body body,
                      @JsonProperty("_links") PageLinks links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Version(Integer number) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Body(Storage storage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Storage(String value, String representation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Links(String next, String base) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageLinks(String webui, String tinyui) {
    }
}
