package com.substring.docmind.service.confluence;

import com.substring.docmind.config.ConfluenceProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real RestClient against a local HTTP server mimicking Confluence Cloud v2. */
class ConfluenceClientTest {

    private HttpServer server;
    private String origin;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile int pagesStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/wiki/api/v2/spaces", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(path + "?" + query);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

            if (path.equals("/wiki/api/v2/spaces")) {
                respond(exchange, 200, "{\"results\":[{\"id\":\"4242\",\"key\":\"MFS\",\"name\":\"MFS\"}]}");
            } else if (path.equals("/wiki/api/v2/spaces/4242/pages") && !query.contains("cursor=")) {
                if (pagesStatus != 200) {
                    respond(exchange, pagesStatus, "{\"message\":\"nope\"}");
                    return;
                }
                respond(exchange, 200, """
                        {"results":[{"id":"1","title":"Architecture","status":"current",
                          "version":{"number":7},
                          "body":{"storage":{"value":"<p>Hello</p>","representation":"storage"}},
                          "_links":{"webui":"/spaces/MFS/pages/1/Architecture"}}],
                         "_links":{"next":"/wiki/api/v2/spaces/4242/pages?cursor=abc%%3D%%3D&limit=100","base":"%s/wiki"}}
                        """.formatted(origin));
            } else if (path.equals("/wiki/api/v2/spaces/4242/pages")) {
                respond(exchange, 200, """
                        {"results":[{"id":"2","title":"Runbook","status":"current",
                          "version":{"number":2},
                          "body":{"storage":{"value":"<p>Steps</p>","representation":"storage"}},
                          "_links":{"webui":"/spaces/MFS/pages/2/Runbook"}}],
                         "_links":{"base":"%s/wiki"}}
                        """.formatted(origin));
            } else {
                respond(exchange, 404, "{}");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ConfluenceClient client() {
        ConfluenceProperties props = new ConfluenceProperties();
        props.setBaseUrl(origin + "/wiki/");
        props.setUsername("bot@example.com");
        props.setApiToken("secret-token");
        return new ConfluenceClient(props);
    }

    @Test
    void fetchesAllPagesAcrossCursorPaginationWithBasicAuth() {
        List<ConfluenceClient.ConfluencePage> pages = client().fetchAllPublishedPages("MFS");

        assertThat(pages).extracting(ConfluenceClient.ConfluencePage::id).containsExactly("1", "2");
        assertThat(pages.getFirst().version()).isEqualTo(7);
        assertThat(pages.getFirst().storageBody()).isEqualTo("<p>Hello</p>");
        assertThat(pages.getFirst().webUrl()).isEqualTo(origin + "/wiki/spaces/MFS/pages/1/Architecture");

        assertThat(requests.get(0)).isEqualTo("/wiki/api/v2/spaces?keys=MFS&limit=1");
        assertThat(requests.get(1)).contains("status=current").contains("body-format=storage");
        // cursor must be forwarded verbatim, not double-encoded
        assertThat(requests.get(2)).contains("cursor=abc%3D%3D");

        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("bot@example.com:secret-token".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders).allMatch(expectedAuth::equals);
    }

    @Test
    void authFailureProducesActionableError() {
        pagesStatus = 401;
        assertThatThrownBy(() -> client().fetchAllPublishedPages("MFS"))
                .isInstanceOf(ConfluenceApiException.class)
                .hasMessageContaining("CONFLUENCE_API_TOKEN");
    }

    @Test
    void unconfiguredClientFailsFast() {
        ConfluenceClient unconfigured = new ConfluenceClient(new ConfluenceProperties());
        assertThatThrownBy(() -> unconfigured.resolveSpaceId("MFS"))
                .isInstanceOf(ConfluenceApiException.class)
                .hasMessageContaining("not configured");
    }
}
