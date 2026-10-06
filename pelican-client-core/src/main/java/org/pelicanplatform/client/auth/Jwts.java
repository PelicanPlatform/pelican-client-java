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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the claims out of a JWT without verifying it.
 *
 * <p>Deliberately no signature check.  A client is not the party that validates a token --
 * the origin is, against the issuer's keys -- and a client that tried would need the
 * issuer's JWKS and would then be tempted to treat its own verdict as authoritative.  What
 * a client legitimately wants from the claims is operational: when does this expire, and is
 * it obviously not for this object.  Both are hints, and both are useless if the token is
 * forged, in which case the server rejects it anyway.
 */
final class Jwts {

    private static final Logger log = LoggerFactory.getLogger(Jwts.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jwts() {}

    record Claims(Instant expiresAt, Set<String> scopes, URI issuer) {
        static Claims empty() {
            return new Claims(null, Set.of(), null);
        }
    }

    static Claims peek(String token) {
        if (token == null) {
            return Claims.empty();
        }
        int first = token.indexOf('.');
        int second = first < 0 ? -1 : token.indexOf('.', first + 1);
        if (first < 0 || second < 0) {
            // Not a JWT: an opaque bearer token, which is legitimate.
            return Claims.empty();
        }
        try {
            byte[] payload =
                    Base64.getUrlDecoder().decode(token.substring(first + 1, second));
            JsonNode node = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));

            Instant expiry = null;
            JsonNode exp = node.get("exp");
            if (exp != null && exp.isNumber()) {
                expiry = Instant.ofEpochSecond(exp.asLong());
            }

            Set<String> scopes = new LinkedHashSet<>();
            JsonNode scope = node.get("scope");
            if (scope != null && scope.isTextual()) {
                for (String s : scope.asText().split("\\s+")) {
                    if (!s.isBlank()) {
                        scopes.add(s);
                    }
                }
            } else if (scope != null && scope.isArray()) {
                scope.forEach(n -> scopes.add(n.asText()));
            }

            URI issuer = null;
            JsonNode iss = node.get("iss");
            if (iss != null && iss.isTextual()) {
                try {
                    issuer = URI.create(iss.asText());
                } catch (IllegalArgumentException e) {
                    log.debug("Token carries an unparseable iss claim: {}", iss.asText());
                }
            }
            return new Claims(expiry, scopes, issuer);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("Could not read claims from a bearer token; treating it as opaque", e);
            return Claims.empty();
        }
    }
}
