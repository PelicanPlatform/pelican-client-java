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

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.http.HttpHeaders;
import org.pelicanplatform.client.http.UriPaths;

/** Everything a Director 307 told us. */
public final class DirectorResponse {

    private final List<ObjectServer> objectServers;
    private final NamespaceInfo namespace;
    private final List<URI> acceptedIssuers;
    private final TokenGenerationHint tokenGeneration;
    private final URI broker;
    private final String jobId;

    DirectorResponse(
            List<ObjectServer> objectServers,
            NamespaceInfo namespace,
            List<URI> acceptedIssuers,
            TokenGenerationHint tokenGeneration,
            URI broker,
            String jobId) {
        this.objectServers = List.copyOf(objectServers);
        this.namespace = namespace;
        this.acceptedIssuers = List.copyOf(acceptedIssuers);
        this.tokenGeneration = tokenGeneration;
        this.broker = broker;
        this.jobId = jobId;
    }

    /**
     * Parse a Director response.
     *
     * <p>Falls back to the {@code Location} header when there are no {@code Link} headers:
     * some Director responses (health-test redirects among them) are plain 307s without the
     * {@code X-Pelican-*} family, and treating those as "no servers" would turn a working
     * redirect into a failure.
     */
    public static DirectorResponse parse(HttpHeaders headers, ObjectPath requestedPath) {
        List<ObjectServer> servers = ObjectServer.parseLinkHeaders(headers);
        if (servers.isEmpty()) {
            String location = headers.firstOrNull("Location");
            if (location != null && !location.isBlank()) {
                servers = List.of(new ObjectServer(URI.create(location), 1, null, null));
            }
        }
        servers = servers.stream().map(s -> stripObjectPath(s, requestedPath)).toList();
        List<URI> issuers =
                HeaderKeyValues.parse(headers, PelicanHeaders.AUTHORIZATION).all("issuer").stream()
                        .map(URI::create)
                        .toList();
        return new DirectorResponse(
                servers,
                NamespaceInfo.parse(headers),
                issuers,
                TokenGenerationHint.parse(headers).orElse(null),
                headers.first(PelicanHeaders.BROKER).map(URI::create).orElse(null),
                headers.firstOrNull(PelicanHeaders.JOB_ID));
    }

    /**
     * Reduce a Director-supplied URL to the server it names.
     *
     * <p>The Director sends complete object URLs -- {@code <https://cache:8443/ns/obj>} --
     * but a cached response is reused for sibling objects in the same namespace, so what
     * needs storing is the server, not one object's URL.  Stripping the requested path here
     * means {@link ObjectServer#resolve} can build a URL for any object in the namespace.
     * If the tail does not match (a server published under a sub-path, say), the URL is kept
     * whole, which is the conservative direction.
     */
    private static ObjectServer stripObjectPath(ObjectServer server, ObjectPath requestedPath) {
        if (requestedPath == null || requestedPath.isRoot()) {
            return server;
        }
        String rawPath = server.uri().getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            return server;
        }
        String encoded = UriPaths.encode(requestedPath);
        String base = null;
        if (rawPath.equals(encoded)) {
            base = "";
        } else if (rawPath.endsWith(encoded)) {
            base = rawPath.substring(0, rawPath.length() - encoded.length());
        } else if (rawPath.equals(requestedPath.value())) {
            base = "";
        } else if (rawPath.endsWith(requestedPath.value())) {
            base = rawPath.substring(0, rawPath.length() - requestedPath.value().length());
        }
        if (base == null) {
            return server;
        }
        URI stripped =
                URI.create(
                        server.uri().getScheme() + "://" + server.uri().getAuthority() + base);
        return new ObjectServer(stripped, server.priority(), server.rel(), server.depth());
    }

    /** Candidate servers, best first. */
    public List<ObjectServer> objectServers() {
        return objectServers;
    }

    public NamespaceInfo namespace() {
        return namespace;
    }

    /** Issuers the namespace accepts tokens from, from {@code X-Pelican-Authorization}. */
    public List<URI> acceptedIssuers() {
        return acceptedIssuers;
    }

    public Optional<TokenGenerationHint> tokenGeneration() {
        return Optional.ofNullable(tokenGeneration);
    }

    /** The origin's broker, for origins that sit behind a firewall. */
    public Optional<URI> broker() {
        return Optional.ofNullable(broker);
    }

    /** The Director's request id, worth logging so a failure can be traced server-side. */
    public Optional<String> jobId() {
        return Optional.ofNullable(jobId);
    }

    public boolean requiresToken() {
        return namespace.requireToken();
    }

    @Override
    public String toString() {
        return String.format("DirectorResponse[servers=%s %s]", objectServers, namespace);
    }
}
