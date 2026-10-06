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

package org.pelicanplatform.client.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.pelicanplatform.client.PelicanErrorCode;
import org.pelicanplatform.client.PelicanException;
import org.pelicanplatform.client.http.HttpRequestSpec;
import org.pelicanplatform.client.http.HttpResponseHandle;
import org.pelicanplatform.client.http.HttpTransport;
import org.pelicanplatform.client.http.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Obtains tokens with the OAuth2 client-credentials grant.
 *
 * <p>The grant for a service: no human, no browser, no device flow.  The device
 * authorization grant the Pelican CLI uses is deliberately absent from this library --
 * blocking a request until someone visits a URL is not something a server-side caller can
 * survive, and having the option available invites exactly that.
 *
 * <p>The issuer is taken from the Director's answer rather than configured, so a client
 * configured with one credential works across every namespace whose issuer accepts it.
 * The token endpoint is discovered from the issuer's
 * {@code /.well-known/openid-configuration}.
 */
public final class ClientCredentialsProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpTransport transport;
    private final String clientId;
    private final String clientSecret;
    private final Set<URI> allowedIssuers;
    private final Duration timeout;
    private final Map<URI, URI> tokenEndpoints = new ConcurrentHashMap<>();

    /**
     * @param allowedIssuers issuers this client's credentials may be sent to; empty means any
     *     issuer the Director names. Restricting it is strongly advised: without a list, a
     *     Director that named a hostile issuer would be handed the client secret.
     */
    public ClientCredentialsProvider(
            HttpTransport transport,
            String clientId,
            String clientSecret,
            Set<URI> allowedIssuers,
            Duration timeout) {
        this.transport = transport;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.allowedIssuers = allowedIssuers == null ? Set.of() : Set.copyOf(allowedIssuers);
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    @Override
    public Optional<Credential> resolve(CredentialRequest request) {
        List<URI> issuers = request.acceptedIssuers();
        if (issuers.isEmpty()) {
            issuers =
                    request.hint().map(org.pelicanplatform.client.federation.TokenGenerationHint::issuers)
                            .orElse(List.of());
        }
        for (URI issuer : issuers) {
            if (!allowedIssuers.isEmpty() && !allowedIssuers.contains(issuer)) {
                log.debug("Not sending client credentials to unlisted issuer {}", issuer);
                continue;
            }
            try {
                return Optional.of(fetch(issuer, request.wlcgScopes()));
            } catch (PelicanException e) {
                log.debug("Client-credentials grant against {} failed", issuer, e);
            }
        }
        return Optional.empty();
    }

    private Credential fetch(URI issuer, List<String> scopes) {
        URI tokenEndpoint = tokenEndpoints.computeIfAbsent(issuer, this::discoverTokenEndpoint);

        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (!scopes.isEmpty()) {
            form.append("&scope=")
                    .append(URLEncoder.encode(String.join(" ", scopes), StandardCharsets.UTF_8));
        }

        String basic =
                Base64.getEncoder()
                        .encodeToString(
                                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequestSpec spec =
                HttpRequestSpec.builder(tokenEndpoint, "POST")
                        .header("Authorization", "Basic " + basic)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .responseTimeout(timeout)
                        .body(RequestBody.fromString(form.toString()))
                        .build();

        try (HttpResponseHandle response = transport.execute(spec)) {
            byte[] body = response.body().readAllBytes();
            if (response.statusCode() != 200) {
                throw new PelicanException(
                        PelicanErrorCode.AUTHORIZATION,
                        String.format(
                                "token endpoint %s returned HTTP %d: %s",
                                tokenEndpoint,
                                response.statusCode(),
                                new String(body, StandardCharsets.UTF_8).strip()));
            }
            JsonNode node = MAPPER.readTree(body);
            JsonNode accessToken = node.get("access_token");
            if (accessToken == null || !accessToken.isTextual()) {
                throw new PelicanException(
                        PelicanErrorCode.AUTHORIZATION,
                        "token endpoint " + tokenEndpoint + " returned no access_token");
            }
            Credential credential = Credential.fromJwt(accessToken.asText());
            if (credential.expiresAt().isEmpty()) {
                // An opaque token still has a usable lifetime from expires_in.
                JsonNode expiresIn = node.get("expires_in");
                if (expiresIn != null && expiresIn.isNumber()) {
                    return new Credential(
                            accessToken.asText(),
                            Instant.now().plusSeconds(expiresIn.asLong()),
                            credential.scopes(),
                            issuer);
                }
            }
            return credential;
        } catch (IOException e) {
            throw new PelicanException(
                    PelicanErrorCode.AUTHORIZATION,
                    "could not reach the token endpoint " + tokenEndpoint,
                    e);
        }
    }

    private URI discoverTokenEndpoint(URI issuer) {
        String base = issuer.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI configUri = URI.create(base + "/.well-known/openid-configuration");
        HttpRequestSpec spec =
                HttpRequestSpec.builder(configUri, "GET")
                        .header("Accept", "application/json")
                        .responseTimeout(timeout)
                        .build();
        try (HttpResponseHandle response = transport.execute(spec)) {
            if (response.statusCode() != 200) {
                throw new PelicanException(
                        PelicanErrorCode.AUTHORIZATION,
                        "issuer metadata at " + configUri + " returned HTTP " + response.statusCode());
            }
            JsonNode node = MAPPER.readTree(response.body().readAllBytes());
            JsonNode endpoint = node.get("token_endpoint");
            if (endpoint == null || !endpoint.isTextual()) {
                throw new PelicanException(
                        PelicanErrorCode.AUTHORIZATION,
                        "issuer metadata at " + configUri + " names no token_endpoint");
            }
            return URI.create(endpoint.asText());
        } catch (IOException e) {
            throw new PelicanException(
                    PelicanErrorCode.AUTHORIZATION, "could not fetch issuer metadata " + configUri, e);
        }
    }
}
