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

package org.pelicanplatform.client.federation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.pelicanplatform.client.FederationDiscoveryException;
import org.pelicanplatform.client.PelicanErrorCode;
import org.pelicanplatform.client.http.HttpRequestSpec;
import org.pelicanplatform.client.http.HttpResponseHandle;
import org.pelicanplatform.client.http.HttpTransport;
import org.pelicanplatform.client.http.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a federation reference to its endpoints, and remembers the answer.
 *
 * <p>Caching is not an optimization here so much as a politeness: a service doing many
 * transfers would otherwise hit one well-known endpoint once per object.  Successes are
 * held for 30 minutes and failures for 5, matching the Go client, and concurrent lookups
 * of the same federation collapse into one request so a burst of work produces one fetch
 * rather than a stampede.
 */
public final class FederationDiscovery {

    private static final Logger log = LoggerFactory.getLogger(FederationDiscovery.class);

    public static final String WELL_KNOWN_PATH = "/.well-known/pelican-configuration";

    private static final Duration SUCCESS_TTL = Duration.ofMinutes(30);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(5);

    private final HttpTransport transport;
    private final RetryPolicy retryPolicy;
    private final Duration timeout;
    private final String userAgent;
    private final Map<URI, CacheEntry> cache = new ConcurrentHashMap<>();

    public FederationDiscovery(
            HttpTransport transport, RetryPolicy retryPolicy, Duration timeout, String userAgent) {
        this.transport = transport;
        this.retryPolicy = retryPolicy;
        this.timeout = timeout;
        this.userAgent = userAgent;
    }

    /** Resolve a federation, using the cache when it is fresh. */
    public FederationInfo discover(URI federation) {
        CacheEntry entry =
                cache.compute(
                        federation,
                        (key, existing) -> {
                            if (existing != null && !existing.isExpired()) {
                                return existing;
                            }
                            return new CacheEntry(key);
                        });
        return entry.get(this);
    }

    /** Drop any cached answer for a federation. */
    public void invalidate(URI federation) {
        cache.remove(federation);
    }

    private FederationInfo fetch(URI federation) {
        URI target = URI.create(stripTrailingSlash(federation.toString()) + WELL_KNOWN_PATH);
        IOException lastFailure = null;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            sleep(retryPolicy.backoffBefore(attempt));
            HttpRequestSpec spec =
                    HttpRequestSpec.builder(target, "GET")
                            .header("User-Agent", userAgent)
                            .header("Accept", "application/json")
                            .responseTimeout(timeout)
                            .build();
            try (HttpResponseHandle response = transport.execute(spec)) {
                if (response.statusCode() != 200) {
                    lastFailure =
                            new IOException(
                                    "HTTP " + response.statusCode() + " from " + target);
                    if (response.statusCode() < 500) {
                        break;
                    }
                    continue;
                }
                return parse(response.body(), target);
            } catch (HttpTimeoutException e) {
                throw new FederationDiscoveryException(
                        PelicanErrorCode.RESOLUTION_TIMEOUT,
                        "timed out fetching federation metadata from " + target,
                        e);
            } catch (IOException e) {
                lastFailure = e;
                log.debug("Federation discovery attempt {} for {} failed", attempt + 1, target, e);
            }
        }
        throw new FederationDiscoveryException(
                PelicanErrorCode.RESOLUTION_CONNECTION_FAILURE,
                "could not fetch federation metadata from " + target
                        + (lastFailure == null ? "" : ": " + lastFailure.getMessage()),
                lastFailure);
    }

    private FederationInfo parse(InputStream body, URI source) {
        ObjectMapper mapper =
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        FederationInfo info;
        try {
            info = mapper.readValue(body, FederationInfo.class);
        } catch (IOException e) {
            throw new FederationDiscoveryException(
                    PelicanErrorCode.RESOLUTION,
                    "federation metadata at " + source + " is not valid Pelican configuration",
                    e);
        }
        if (info.directorEndpoint() == null) {
            throw new FederationDiscoveryException(
                    PelicanErrorCode.RESOLUTION,
                    "federation metadata at " + source + " names no director_endpoint");
        }
        return info;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FederationDiscoveryException(
                    PelicanErrorCode.RESOLUTION, "interrupted during federation discovery", e);
        }
    }

    /**
     * One federation's cached answer.
     *
     * <p>The lock makes concurrent callers for the same federation wait for one fetch rather
     * than each starting their own.
     */
    private static final class CacheEntry {
        private final URI federation;
        private FederationInfo value;
        private RuntimeException failure;
        private Instant expiresAt = Instant.MIN;

        CacheEntry(URI federation) {
            this.federation = federation;
        }

        synchronized boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        synchronized FederationInfo get(FederationDiscovery owner) {
            if (!isExpired()) {
                if (failure != null) {
                    throw failure;
                }
                if (value != null) {
                    return value;
                }
            }
            try {
                value = owner.fetch(federation);
                failure = null;
                expiresAt = Instant.now().plus(SUCCESS_TTL);
                return value;
            } catch (RuntimeException e) {
                failure = e;
                value = null;
                expiresAt = Instant.now().plus(FAILURE_TTL);
                throw e;
            }
        }
    }
}
