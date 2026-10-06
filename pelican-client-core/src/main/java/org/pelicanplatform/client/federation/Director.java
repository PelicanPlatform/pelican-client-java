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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.pelicanplatform.client.DirectorException;
import org.pelicanplatform.client.ObjectNotFoundException;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.PelicanErrorCode;
import org.pelicanplatform.client.http.HttpRequestSpec;
import org.pelicanplatform.client.http.HttpResponseHandle;
import org.pelicanplatform.client.http.HttpTransport;
import org.pelicanplatform.client.http.RetryPolicy;
import org.pelicanplatform.client.http.UriPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the Director where an object lives.
 *
 * <p>Redirects are never followed.  The Director's 307 is not a redirect to be chased but a
 * carrier for routing and authorization metadata: following it transparently would discard
 * the {@code Link} list (leaving one server where the federation offered several) and would
 * send the caller's credential to the first host named without the client having decided
 * that it should.
 */
public final class Director {

    private static final Logger log = LoggerFactory.getLogger(Director.class);

    private static final int HTTP_MULTI_STATUS = 207;
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final HttpTransport transport;
    private final RetryPolicy retryPolicy;
    private final Duration timeout;
    private final String userAgent;
    private final boolean debug;

    public Director(
            HttpTransport transport,
            RetryPolicy retryPolicy,
            Duration timeout,
            String userAgent,
            boolean debug) {
        this.transport = transport;
        this.retryPolicy = retryPolicy;
        this.timeout = timeout;
        this.userAgent = userAgent;
        this.debug = debug;
    }

    /** Resolve a path for reading or writing. */
    public DirectorResponse resolve(
            FederationInfo federation,
            ObjectPath path,
            DirectorFlavor flavor,
            String query,
            String bearerToken) {
        try (DirectorQueryResult result =
                query(federation, path, flavor.httpMethod(), query, bearerToken, null)) {
            return result.response();
        } catch (IOException e) {
            throw new DirectorException(
                    PelicanErrorCode.CONTACT_DIRECTOR, "failed to close the Director response", e);
        }
    }

    /**
     * Ask the Director about a path.
     *
     * @param httpMethod the verb whose routing is being asked about
     * @param jobId a correlation id echoed into the Director's logs; may be null
     */
    public DirectorQueryResult query(
            FederationInfo federation,
            ObjectPath path,
            String httpMethod,
            String query,
            String bearerToken,
            String jobId) {

        URI target = buildUri(federation.directorEndpoint(), path, query);
        IOException transportFailure = null;

        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            sleep(retryPolicy.backoffBefore(attempt));

            HttpRequestSpec.Builder spec =
                    HttpRequestSpec.builder(target, httpMethod)
                            .header("User-Agent", userAgent)
                            .responseTimeout(timeout);
            if (bearerToken != null && !bearerToken.isEmpty()) {
                spec.header("Authorization", "Bearer " + bearerToken);
            }
            if (jobId != null) {
                spec.header(PelicanHeaders.JOB_ID, jobId);
            }
            if (timeout != null) {
                spec.header(PelicanHeaders.TIMEOUT, formatDuration(timeout));
            }
            if (debug) {
                spec.header(PelicanHeaders.DEBUG, "true");
            }

            HttpResponseHandle response = null;
            boolean handedOff = false;
            try {
                response = transport.execute(spec.build());
                int status = response.statusCode();
                boolean fromDirector = isFromPelican(response);

                // An ingress in front of a restarting Director answers with its own errors.
                // Those are worth waiting out; the Director's own errors are not.
                if (!fromDirector && (status == 502 || status == 404 || status == 500)) {
                    if (attempt == 0) {
                        log.warn(
                                "Response from {} did not come from a Pelican process (HTTP {});"
                                        + " the Director may be restarting, will retry",
                                target,
                                status);
                    }
                    transportFailure = new IOException("HTTP " + status + " from a non-Pelican process");
                    continue;
                }
                // The Director restarted and has not yet re-collected server advertisements.
                if (fromDirector && status == HTTP_TOO_MANY_REQUESTS) {
                    if (attempt == 0) {
                        log.warn("The Director is still discovering federation services; will retry");
                    }
                    transportFailure = new IOException("HTTP 429 from the Director");
                    continue;
                }

                if (status == HTTP_MULTI_STATUS) {
                    // Hand the open response to the caller so a large listing streams.
                    handedOff = true;
                    return new DirectorQueryResult(
                            status, DirectorResponse.parse(response.headers(), path), response);
                }
                if (status == HTTP_TEMPORARY_REDIRECT) {
                    // Drain so the connection can be reused; a 307 body is only debug detail.
                    drain(response.body());
                    return new DirectorQueryResult(
                            status, DirectorResponse.parse(response.headers(), path), null);
                }
                throw toException(response, status, path, httpMethod, target);
            } catch (HttpTimeoutException e) {
                throw new DirectorException(
                        PelicanErrorCode.TRANSFER_DIRECTOR_TIMEOUT,
                        "timed out querying the Director at " + target,
                        e);
            } catch (IOException e) {
                transportFailure = e;
                log.debug("Director query attempt {} to {} failed", attempt + 1, target, e);
            } finally {
                if (response != null && !handedOff) {
                    try {
                        response.close();
                    } catch (IOException e) {
                        log.trace("Ignoring failure closing a Director response", e);
                    }
                }
            }
        }

        throw new DirectorException(
                PelicanErrorCode.CONTACT_DIRECTOR,
                "could not query the Director at " + target
                        + (transportFailure == null ? "" : ": " + transportFailure.getMessage()),
                transportFailure);
    }

    private RuntimeException toException(
            HttpResponseHandle response, int status, ObjectPath path, String method, URI target) {
        String detail = readErrorDetail(response);
        if (status == 404) {
            // The Director distinguishes "no such object" from "no server has it" in prose;
            // both mean the caller's path is not currently gettable, so both surface as
            // not-found rather than as a Director failure.
            throw new ObjectNotFoundException(path);
        }
        if (status == 403 || status == 401) {
            throw new DirectorException(
                    PelicanErrorCode.AUTHORIZATION,
                    String.format(
                            "the Director refused a %s of %s (HTTP %d)%s",
                            method, path, status, detail.isEmpty() ? "" : ": " + detail));
        }
        return new DirectorException(
                PelicanErrorCode.CONTACT_DIRECTOR,
                String.format(
                        "unexpected HTTP %d from the Director at %s for %s of %s%s",
                        status, target, method, path, detail.isEmpty() ? "" : ": " + detail));
    }

    /** Pull the {@code msg} out of the Director's JSON error body, when there is one. */
    private String readErrorDetail(HttpResponseHandle response) {
        try {
            byte[] body = response.body().readAllBytes();
            if (body.length == 0) {
                return "";
            }
            String contentType =
                    response.headers().first("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).strip();
            if (contentType.contains("application/json")) {
                JsonNode node = new ObjectMapper().readTree(body);
                JsonNode msg = node.get("msg");
                if (msg == null) {
                    msg = node.get("error");
                }
                if (msg != null && msg.isTextual()) {
                    return msg.asText();
                }
            }
            return text.length() > 512 ? text.substring(0, 512) + "..." : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isFromPelican(HttpResponseHandle response) {
        return response.headers().first("Server").orElse("").startsWith("pelican/");
    }

    static URI buildUri(URI directorEndpoint, ObjectPath path, String query) {
        String base = directorEndpoint.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String uri = base + UriPaths.encode(path);
        if (query != null && !query.isEmpty()) {
            uri = uri + "?" + query;
        }
        return URI.create(uri);
    }

    /** Render a duration the way Go's {@code time.ParseDuration} reads it back. */
    static String formatDuration(Duration d) {
        long millis = d.toMillis();
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return millis + "ms";
    }

    private static void drain(InputStream body) {
        try (InputStream in = body) {
            in.readAllBytes();
        } catch (IOException e) {
            log.trace("Ignoring failure draining a Director response body", e);
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectorException(
                    PelicanErrorCode.CONTACT_DIRECTOR, "interrupted while querying the Director", e);
        }
    }

    /** Exposed for the client's timeout plumbing. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }
}
