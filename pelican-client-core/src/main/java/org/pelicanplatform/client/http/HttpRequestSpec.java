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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A single HTTP exchange to perform, independent of which HTTP library performs it. */
public final class HttpRequestSpec {

    private final URI uri;
    private final String method;
    private final Map<String, List<String>> headers;
    private final RequestBody body;
    private final Duration responseTimeout;
    private final boolean wantsTrailers;

    private HttpRequestSpec(Builder b) {
        this.uri = b.uri;
        this.method = b.method;
        Map<String, List<String>> copy = new LinkedHashMap<>();
        b.headers.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.headers = Collections.unmodifiableMap(copy);
        this.body = b.body;
        this.responseTimeout = b.responseTimeout;
        this.wantsTrailers = b.wantsTrailers;
    }

    public static Builder builder(URI uri, String method) {
        return new Builder(uri, method);
    }

    /** The same request aimed at a different URL, for following a redirect. */
    public HttpRequestSpec withUri(URI newUri) {
        Builder builder = new Builder(newUri, method);
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        builder.body = body;
        builder.responseTimeout = responseTimeout;
        builder.wantsTrailers = wantsTrailers;
        return builder.build();
    }

    public URI uri() {
        return uri;
    }

    public String method() {
        return method;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Optional<RequestBody> body() {
        return Optional.ofNullable(body);
    }

    /**
     * How long to wait for response <em>headers</em>.
     *
     * <p>Not a limit on the whole transfer: a multi-gigabyte download is expected to take
     * longer than any sensible header timeout, and capping it here would kill healthy
     * transfers.
     */
    public Optional<Duration> responseTimeout() {
        return Optional.ofNullable(responseTimeout);
    }

    /** Whether the caller asked for Pelican's {@code X-Transfer-Status} trailer. */
    public boolean wantsTrailers() {
        return wantsTrailers;
    }

    public static final class Builder {
        private final URI uri;
        private final String method;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private RequestBody body;
        private Duration responseTimeout;
        private boolean wantsTrailers;

        private Builder(URI uri, String method) {
            this.uri = java.util.Objects.requireNonNull(uri, "uri");
            this.method = java.util.Objects.requireNonNull(method, "method");
        }

        public Builder header(String name, String value) {
            if (value != null) {
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            return this;
        }

        public Builder setHeader(String name, String value) {
            headers.remove(name);
            return header(name, value);
        }

        public Builder body(RequestBody body) {
            this.body = body;
            return this;
        }

        public Builder responseTimeout(Duration timeout) {
            this.responseTimeout = timeout;
            return this;
        }

        public Builder wantsTrailers(boolean wantsTrailers) {
            this.wantsTrailers = wantsTrailers;
            return this;
        }

        public HttpRequestSpec build() {
            return new HttpRequestSpec(this);
        }
    }
}
