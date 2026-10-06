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

/**
 * The Director's advice on getting a token, from {@code X-Pelican-Token-Generation}.
 *
 * <p>{@link #basePath()} is the load-bearing field.  The Director speaks
 * namespace-absolute paths, but a WLCG storage scope is written relative to the token's
 * base path: for an object at {@code /ns/data/file} under base path {@code /ns}, the scope
 * is {@code storage.read:/data/file}.  Every place that arithmetic is redone by hand is a
 * place a token gets minted with a scope that does not cover the object, so
 * {@link org.pelicanplatform.client.auth.ScopeRequest} does it once, from this value.
 */
public final class TokenGenerationHint {

    /** How the Director expects a client to obtain a credential. */
    public enum Strategy {
        OAUTH2,
        VAULT,
        UNKNOWN;

        static Strategy parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case "oauth2" -> OAUTH2;
                case "vault" -> VAULT;
                default -> UNKNOWN;
            };
        }
    }

    private final List<URI> issuers;
    private final ObjectPath basePath;
    private final int maxScopeDepth;
    private final Strategy strategy;
    private final URI vaultServer;

    public TokenGenerationHint(
            List<URI> issuers,
            ObjectPath basePath,
            int maxScopeDepth,
            Strategy strategy,
            URI vaultServer) {
        this.issuers = List.copyOf(issuers);
        this.basePath = basePath;
        this.maxScopeDepth = maxScopeDepth;
        this.strategy = strategy;
        this.vaultServer = vaultServer;
    }

    static Optional<TokenGenerationHint> parse(HttpHeaders headers) {
        HeaderKeyValues kv = HeaderKeyValues.parse(headers, PelicanHeaders.TOKEN_GENERATION);
        if (kv.isEmpty()) {
            return Optional.empty();
        }
        List<URI> issuers = kv.all("issuer").stream().map(URI::create).toList();
        ObjectPath basePath = kv.first("base-path").map(ObjectPath::of).orElse(null);
        int depth = kv.integer("max-scope-depth").orElse(0);
        Strategy strategy = Strategy.parse(kv.first("strategy").orElse(null));
        URI vault = kv.first("vault-server").map(URI::create).orElse(null);
        return Optional.of(new TokenGenerationHint(issuers, basePath, depth, strategy, vault));
    }

    public List<URI> issuers() {
        return issuers;
    }

    /** The prefix that scopes in a token for this namespace are written relative to. */
    public Optional<ObjectPath> basePath() {
        return Optional.ofNullable(basePath);
    }

    /**
     * How many path components below {@link #basePath()} a scope may name.
     *
     * <p>A scope deeper than this is refused by the issuer, so a client asking for a
     * specific object under a shallow limit has to ask for an ancestor instead.
     */
    public int maxScopeDepth() {
        return maxScopeDepth;
    }

    public Strategy strategy() {
        return strategy;
    }

    public Optional<URI> vaultServer() {
        return Optional.ofNullable(vaultServer);
    }

    @Override
    public String toString() {
        return String.format(
                "TokenGenerationHint[issuers=%s basePath=%s maxScopeDepth=%d strategy=%s]",
                issuers, basePath, maxScopeDepth, strategy);
    }
}
