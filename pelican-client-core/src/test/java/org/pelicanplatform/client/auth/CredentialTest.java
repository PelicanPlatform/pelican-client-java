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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CredentialTest {

    private static String jwt(String scope, Instant expiry) {
        String payload =
                String.format(
                        "{\"iss\":\"https://issuer.example\",\"exp\":%d,\"scope\":\"%s\"}",
                        expiry.getEpochSecond(), scope);
        return "e30."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".";
    }

    @Test
    void readsExpiryScopesAndIssuerFromAJwt() {
        Instant expiry = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Credential credential = Credential.fromJwt(jwt("storage.read:/data offline_access", expiry));
        assertThat(credential.expiresAt()).contains(expiry);
        assertThat(credential.scopes()).containsExactlyInAnyOrder("storage.read:/data", "offline_access");
        assertThat(credential.issuer()).map(Object::toString).contains("https://issuer.example");
    }

    @Test
    void treatsANonJwtAsOpaque() {
        Credential credential = Credential.fromJwt("an-opaque-token");
        assertThat(credential.bearerToken()).isEqualTo("an-opaque-token");
        assertThat(credential.expiresAt()).isEmpty();
        assertThat(credential.scopes()).isEmpty();
    }

    @Test
    void detectsExpiryWithSkew() {
        Credential soon = Credential.fromJwt(jwt("storage.read:/", Instant.now().plusSeconds(30)));
        assertThat(soon.isExpired(Duration.ZERO)).isFalse();
        assertThat(soon.isExpired(Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void scopeCoverageIsHierarchical() {
        Credential credential = Credential.fromJwt(jwt("storage.read:/data", Instant.now().plusSeconds(600)));
        assertThat(credential.mightSatisfy(List.of("storage.read:/data/sub/file"))).isTrue();
        assertThat(credential.mightSatisfy(List.of("storage.read:/data"))).isTrue();
        assertThat(credential.mightSatisfy(List.of("storage.read:/other"))).isFalse();
        // "/database" is not under "/data": a string prefix check would say otherwise.
        assertThat(credential.mightSatisfy(List.of("storage.read:/database"))).isFalse();
        assertThat(credential.mightSatisfy(List.of("storage.create:/data"))).isFalse();
    }

    @Test
    void modifyCoversCreate() {
        Credential credential =
                Credential.fromJwt(jwt("storage.modify:/data", Instant.now().plusSeconds(600)));
        assertThat(credential.mightSatisfy(List.of("storage.create:/data/f"))).isTrue();
    }

    @Test
    void aRootScopeCoversEverything() {
        Credential credential = Credential.fromJwt(jwt("storage.read:/", Instant.now().plusSeconds(600)));
        assertThat(credential.mightSatisfy(List.of("storage.read:/anything/at/all"))).isTrue();
    }

    @Test
    void aTokenWithNoScopeClaimIsNotPrejudged() {
        // Nothing can be concluded from an opaque token, and refusing to send it would break
        // every issuer that does not put scopes in the JWT.
        assertThat(Credential.opaque("x").mightSatisfy(List.of("storage.read:/data"))).isTrue();
    }

    @Test
    void toStringNeverLeaksTheToken() {
        Credential credential = new Credential("super-secret", null, Set.of(), null);
        assertThat(credential.toString()).doesNotContain("super-secret");
    }
}
