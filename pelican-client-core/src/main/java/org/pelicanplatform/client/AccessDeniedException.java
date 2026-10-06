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

package org.pelicanplatform.client;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The object server rejected the request's credential (HTTP 401 or 403).
 *
 * <p>Carries what the Director said about how to fix it: which issuers the namespace
 * accepts, and the scope the operation needed.  A caller that manages credentials can use
 * those to go get a better token instead of guessing.
 */
public class AccessDeniedException extends PelicanException {

    private final ObjectPath path;
    private final List<URI> acceptedIssuers;
    private final List<String> requiredScopes;
    private final boolean credentialWasPresented;

    public AccessDeniedException(
            ObjectPath path,
            List<URI> acceptedIssuers,
            List<String> requiredScopes,
            boolean credentialWasPresented,
            String detail) {
        super(
                credentialWasPresented
                        ? PelicanErrorCode.AUTHORIZATION
                        : PelicanErrorCode.AUTHORIZATION_TOKEN_NOT_FOUND,
                buildMessage(path, acceptedIssuers, requiredScopes, credentialWasPresented, detail));
        this.path = path;
        this.acceptedIssuers = List.copyOf(acceptedIssuers);
        this.requiredScopes = List.copyOf(requiredScopes);
        this.credentialWasPresented = credentialWasPresented;
    }

    private static String buildMessage(
            ObjectPath path,
            List<URI> issuers,
            List<String> scopes,
            boolean credentialWasPresented,
            String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(credentialWasPresented ? "access denied for " : "no credential available for ");
        sb.append(path);
        if (!scopes.isEmpty()) {
            sb.append("; needed scope ").append(String.join(" or ", scopes));
        }
        if (!issuers.isEmpty()) {
            sb.append("; namespace accepts tokens from ");
            sb.append(String.join(", ", issuers.stream().map(URI::toString).toList()));
        }
        if (detail != null && !detail.isBlank()) {
            sb.append("; server said: ").append(detail.strip());
        }
        return sb.toString();
    }

    public ObjectPath path() {
        return path;
    }

    /** Issuers the Director said this namespace accepts, from {@code X-Pelican-Authorization}. */
    public List<URI> acceptedIssuers() {
        return acceptedIssuers;
    }

    /** The WLCG scopes the operation asked for, e.g. {@code storage.read:/sub/file}. */
    public List<String> requiredScopes() {
        return requiredScopes;
    }

    /** False when no credential could be obtained at all, rather than one being rejected. */
    public boolean credentialWasPresented() {
        return credentialWasPresented;
    }

    public Optional<URI> firstIssuer() {
        return acceptedIssuers.isEmpty() ? Optional.empty() : Optional.of(acceptedIssuers.get(0));
    }
}
