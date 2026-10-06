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

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.federation.TokenGenerationHint;

/**
 * Everything a {@link CredentialProvider} needs in order to produce a token.
 *
 * <p>Note what is in here: the issuers and base path come from the Director's answer, which
 * means a provider is asked <em>after</em> the federation has said what it will accept, not
 * before.  A provider that has to mint or exchange a token therefore has the information to
 * mint the right one.
 */
public final class CredentialRequest {

    private final URI federation;
    private final ObjectPath objectPath;
    private final List<URI> acceptedIssuers;
    private final TokenGenerationHint hint;
    private final List<ScopeRequest> scopes;
    private final boolean forceRefresh;
    private final String credentialName;

    private CredentialRequest(Builder b) {
        this.federation = b.federation;
        this.objectPath = b.objectPath;
        this.acceptedIssuers = List.copyOf(b.acceptedIssuers);
        this.hint = b.hint;
        this.scopes = List.copyOf(b.scopes);
        this.forceRefresh = b.forceRefresh;
        this.credentialName = b.credentialName;
    }

    public static Builder builder(URI federation, ObjectPath objectPath) {
        return new Builder(federation, objectPath);
    }

    public URI federation() {
        return federation;
    }

    public ObjectPath objectPath() {
        return objectPath;
    }

    /** Issuers the namespace accepts, from {@code X-Pelican-Authorization}. May be empty. */
    public List<URI> acceptedIssuers() {
        return acceptedIssuers;
    }

    public Optional<TokenGenerationHint> hint() {
        return Optional.ofNullable(hint);
    }

    public List<ScopeRequest> scopes() {
        return scopes;
    }

    /** The scopes as WLCG strings, already made relative to the advertised base path. */
    public List<String> wlcgScopes() {
        ObjectPath basePath = hint == null ? null : hint.basePath().orElse(null);
        int maxDepth = hint == null ? 0 : hint.maxScopeDepth();
        return scopes.stream().map(s -> s.toWlcgScope(basePath, maxDepth)).toList();
    }

    /** True when a previously supplied credential was rejected and a fresh one is wanted. */
    public boolean forceRefresh() {
        return forceRefresh;
    }

    /** The credential name from a {@code name+pelican://} URL, if the caller used one. */
    public Optional<String> credentialName() {
        return Optional.ofNullable(credentialName);
    }

    public CredentialRequest withForceRefresh() {
        Builder b = new Builder(federation, objectPath);
        b.acceptedIssuers = acceptedIssuers;
        b.hint = hint;
        b.scopes = scopes;
        b.credentialName = credentialName;
        b.forceRefresh = true;
        return b.build();
    }

    @Override
    public String toString() {
        return String.format(
                "CredentialRequest[%s scopes=%s issuers=%s refresh=%s]",
                objectPath, wlcgScopes(), acceptedIssuers, forceRefresh);
    }

    public static final class Builder {
        private final URI federation;
        private final ObjectPath objectPath;
        private List<URI> acceptedIssuers = List.of();
        private TokenGenerationHint hint;
        private List<ScopeRequest> scopes = List.of();
        private boolean forceRefresh;
        private String credentialName;

        private Builder(URI federation, ObjectPath objectPath) {
            this.federation = federation;
            this.objectPath = objectPath;
        }

        public Builder acceptedIssuers(List<URI> acceptedIssuers) {
            this.acceptedIssuers = acceptedIssuers;
            return this;
        }

        public Builder hint(TokenGenerationHint hint) {
            this.hint = hint;
            return this;
        }

        public Builder scopes(List<ScopeRequest> scopes) {
            this.scopes = scopes;
            return this;
        }

        public Builder forceRefresh(boolean forceRefresh) {
            this.forceRefresh = forceRefresh;
            return this;
        }

        public Builder credentialName(String credentialName) {
            this.credentialName = credentialName;
            return this;
        }

        public CredentialRequest build() {
            return new CredentialRequest(this);
        }
    }
}
