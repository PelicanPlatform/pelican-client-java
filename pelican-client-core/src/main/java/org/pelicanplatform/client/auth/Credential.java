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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A bearer token, plus whatever is known about it. */
public final class Credential {

    private final String bearerToken;
    private final Instant expiresAt;
    private final Set<String> scopes;
    private final URI issuer;

    public Credential(String bearerToken, Instant expiresAt, Set<String> scopes, URI issuer) {
        this.bearerToken = java.util.Objects.requireNonNull(bearerToken, "bearerToken");
        this.expiresAt = expiresAt;
        this.scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        this.issuer = issuer;
    }

    /** Build from a serialized JWT, reading expiry, scopes and issuer out of its claims. */
    public static Credential fromJwt(String token) {
        Jwts.Claims claims = Jwts.peek(token);
        return new Credential(token, claims.expiresAt(), claims.scopes(), claims.issuer());
    }

    /** A token whose contents this client makes no claim about. */
    public static Credential opaque(String token) {
        return new Credential(token, null, Set.of(), null);
    }

    public String bearerToken() {
        return bearerToken;
    }

    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    public Set<String> scopes() {
        return scopes;
    }

    public Optional<URI> issuer() {
        return Optional.ofNullable(issuer);
    }

    /** Whether the token is expired, or will be within {@code skew}. */
    public boolean isExpired(Duration skew) {
        return expiresAt != null && Instant.now().plus(skew).isAfter(expiresAt);
    }

    /**
     * Whether this token plainly cannot satisfy the requested scopes.
     *
     * <p>A best-effort check, not a security decision -- the server decides.  Its value is
     * in the error message: catching an inadequate token here says "this token carries
     * storage.read:/other, you need storage.read:/data" instead of costing a round trip to
     * be told 403 with no detail.  Returns true when the token carries no scope claim at
     * all, since nothing can be concluded then.
     */
    public boolean mightSatisfy(List<String> requiredScopes) {
        if (scopes.isEmpty() || requiredScopes.isEmpty()) {
            return true;
        }
        for (String required : requiredScopes) {
            if (satisfiesOne(required)) {
                return true;
            }
        }
        return false;
    }

    private boolean satisfiesOne(String required) {
        int colon = required.indexOf(':');
        String requiredAction = colon < 0 ? required : required.substring(0, colon);
        String requiredResource = colon < 0 ? "/" : required.substring(colon + 1);
        for (String held : scopes) {
            int heldColon = held.indexOf(':');
            String heldAction = heldColon < 0 ? held : held.substring(0, heldColon);
            String heldResource = heldColon < 0 ? "/" : held.substring(heldColon + 1);
            if (!actionCovers(heldAction, requiredAction)) {
                continue;
            }
            if (resourceCovers(heldResource, requiredResource)) {
                return true;
            }
        }
        return false;
    }

    private static boolean actionCovers(String held, String required) {
        if (held.equals(required)) {
            return true;
        }
        return held.equals("storage.modify") && required.equals("storage.create");
    }

    private static boolean resourceCovers(String held, String required) {
        if (held.equals("/") || held.equals(required)) {
            return true;
        }
        String normalized = held.endsWith("/") ? held.substring(0, held.length() - 1) : held;
        return required.startsWith(normalized + "/");
    }

    @Override
    public String toString() {
        // Never the token itself: this ends up in logs.
        return "Credential[issuer=" + issuer + " expires=" + expiresAt + " scopes=" + scopes + "]";
    }
}
