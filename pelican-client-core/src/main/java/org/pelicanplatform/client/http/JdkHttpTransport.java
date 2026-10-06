/***************************************************************
 *
 * Copyright (C) 2026, Pelican Project, Morgridge Institute for Research
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ***************************************************************/

package org.pelicanplatform.client.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import javax.net.ssl.SSLContext;

/**
 * The default {@link HttpTransport}, over {@code java.net.http.HttpClient}.
 *
 * <p>No third-party HTTP library, which keeps this artifact droppable into a service with
 * its own opinions about HTTP clients.  The one thing it cannot do is read response
 * trailers; see {@link #supportsTrailers()}.
 */
public final class JdkHttpTransport implements HttpTransport {

    /**
     * Headers the JDK refuses to let an application set.
     *
     * <p>{@code Content-Length} is on this list, which is why body length is carried by the
     * {@link RequestBody} and turned into a sized {@code BodyPublisher} instead of a header.
     */
    private static final List<String> RESTRICTED =
            List.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client;
    private final ExecutorService ownedExecutor;

    private JdkHttpTransport(HttpClient client, ExecutorService ownedExecutor) {
        this.client = client;
        this.ownedExecutor = ownedExecutor;
    }

    public static JdkHttpTransport defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Wrap a client the caller already configured. It must not follow redirects. */
    public static JdkHttpTransport wrapping(HttpClient client) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "the HttpClient used by a Pelican transport must be built with"
                            + " Redirect.NEVER: the Director's 307 carries routing and"
                            + " authorization headers that a followed redirect would discard");
        }
        return new JdkHttpTransport(client, null);
    }

    @Override
    public HttpResponseHandle execute(HttpRequestSpec spec) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(spec.uri());
        spec.responseTimeout().ifPresent(builder::timeout);

        for (Map.Entry<String, List<String>> entry : spec.headers().entrySet()) {
            if (RESTRICTED.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            for (String value : entry.getValue()) {
                builder.header(entry.getKey(), value);
            }
        }

        builder.method(spec.method(), publisherFor(spec));

        try {
            HttpResponse<InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new JdkResponseHandle(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + spec.uri(), e);
        }
    }

    private static HttpRequest.BodyPublisher publisherFor(HttpRequestSpec spec) throws IOException {
        Optional<RequestBody> body = spec.body();
        if (body.isEmpty()) {
            return HttpRequest.BodyPublishers.noBody();
        }
        RequestBody rb = body.get();
        long length = rb.contentLength();
        InputStream stream = rb.open();
        if (length >= 0) {
            return HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream(() -> stream), length);
        }
        // Unknown length: the JDK sends this chunked.
        return HttpRequest.BodyPublishers.ofInputStream(() -> stream);
    }

    @Override
    public boolean supportsTrailers() {
        return false;
    }

    @Override
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
        client.close();
    }

    private static final class JdkResponseHandle implements HttpResponseHandle {
        private final HttpResponse<InputStream> response;
        private final HttpHeaders headers;

        JdkResponseHandle(HttpResponse<InputStream> response) {
            this.response = response;
            this.headers = HttpHeaders.of(response.headers().map());
        }

        @Override
        public int statusCode() {
            return response.statusCode();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return response.body();
        }

        @Override
        public URI uri() {
            return response.uri();
        }

        @Override
        public void close() throws IOException {
            response.body().close();
        }
    }

    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(30);
        private SSLContext sslContext;
        private HttpClient.Version version = HttpClient.Version.HTTP_1_1;
        private java.net.ProxySelector proxySelector;

        private Builder() {}

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * HTTP version to negotiate.
         *
         * <p>Defaults to HTTP/1.1.  Pelican caches and origins are XRootD-based and speak
         * HTTP/1.1; asking for HTTP/2 costs an ALPN round trip that usually falls back anyway.
         */
        public Builder version(HttpClient.Version version) {
            this.version = version;
            return this;
        }

        public Builder proxySelector(java.net.ProxySelector proxySelector) {
            this.proxySelector = proxySelector;
            return this;
        }

        public JdkHttpTransport build() {
            HttpClient.Builder b =
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(connectTimeout)
                            .version(version);
            if (sslContext != null) {
                b.sslContext(sslContext);
            }
            if (proxySelector != null) {
                b.proxy(proxySelector);
            }
            return new JdkHttpTransport(b.build(), null);
        }
    }
}
