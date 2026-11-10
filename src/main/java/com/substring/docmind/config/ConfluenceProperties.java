package com.substring.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Connection and scheduling settings for the Confluence knowledge-base sync.
 * Bound from the {@code confluence.*} keys in application config, which in turn
 * read the CONFLUENCE_* environment variables.
 */
@Component
@ConfigurationProperties(prefix = "confluence")
@Getter
@Setter
public class ConfluenceProperties {

    /** Site wiki root, e.g. https://vector11.atlassian.net/wiki (no trailing slash). */
    private String baseUrl = "https://vector11.atlassian.net/wiki";

    /** Atlassian account email used for Basic auth. */
    private String username;

    /** Atlassian API token used for Basic auth. */
    private String apiToken;

    /** Space key to synchronize. */
    private String spaceKey = "MFS";

    /** Page size requested from the Confluence v2 API (max 250). */
    private int pageLimit = 100;

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);

    private Sync sync = new Sync();

    @Getter
    @Setter
    public static class Sync {
        /** Master switch for scheduled runs. Manual triggers work regardless. */
        private boolean enabled = true;

        /** Spring cron expression for scheduled runs. */
        private String cron = "0 0 */6 * * *";

        /** Run one sync shortly after the application starts. */
        private boolean onStartup = false;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(username) && StringUtils.hasText(apiToken)
                && StringUtils.hasText(spaceKey);
    }

    /** Base URL without any trailing slash. */
    public String normalizedBaseUrl() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
