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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.http.HttpHeaders;

class DirectorResponseTest {

    private static HttpHeaders headers(Map<String, List<String>> raw) {
        return HttpHeaders.of(raw);
    }

    @Test
    void sortsObjectServersByPriority() {
        // Deliberately out of order: the Director sends them sorted today, and a client that
        // relied on that would be a function of the other side's implementation detail.
        HttpHeaders h =
                headers(
                        Map.of(
                                "Link",
                                List.of(
                                        "<https://c-slow:8443/ns/obj>; rel=\"duplicate\"; pri=3, "
                                                + "<https://c-fast:8443/ns/obj>; rel=\"duplicate\"; pri=1, "
                                                + "<https://c-mid:8443/ns/obj>; rel=\"duplicate\"; pri=2")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/ns/obj"));
        assertThat(response.objectServers())
                .extracting(s -> s.uri().getHost())
                .containsExactly("c-fast", "c-mid", "c-slow");
    }

    @Test
    void stripsTheObjectPathSoAnAnswerCanBeReusedForSiblings() {
        HttpHeaders h =
                headers(Map.of("Link", List.of("<https://cache:8443/ns/data/obj>; pri=1")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/ns/data/obj"));
        ObjectServer server = response.objectServers().get(0);
        assertThat(server.uri()).isEqualTo(URI.create("https://cache:8443"));
        assertThat(server.resolve("/ns/data/other", null))
                .isEqualTo(URI.create("https://cache:8443/ns/data/other"));
    }

    @Test
    void keepsAServerPathItCannotAccountFor() {
        // If the Link URL does not end with the path we asked for, the extra path is the
        // server's own prefix, and dropping it would address a different endpoint.
        HttpHeaders h = headers(Map.of("Link", List.of("<https://cache:8443/unrelated>; pri=1")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/ns/obj"));
        assertThat(response.objectServers().get(0).uri())
                .isEqualTo(URI.create("https://cache:8443/unrelated"));
    }

    @Test
    void acceptsLinkHeadersSentAsSeveralLines() {
        HttpHeaders h =
                headers(
                        Map.of(
                                "Link",
                                List.of(
                                        "<https://a:8443/ns/obj>; pri=2", "<https://b:8443/ns/obj>; pri=1")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/ns/obj"));
        assertThat(response.objectServers()).extracting(s -> s.uri().getHost()).containsExactly("b", "a");
    }

    @Test
    void fallsBackToLocationWhenThereAreNoLinkHeaders() {
        // Some Director responses -- health-test redirects among them -- are plain 307s. Reading
        // those as "no servers" would turn a working redirect into a failure.
        HttpHeaders h = headers(Map.of("Location", List.of("https://origin:8444/ns/obj")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/ns/obj"));
        assertThat(response.objectServers()).hasSize(1);
        assertThat(response.objectServers().get(0).uri()).isEqualTo(URI.create("https://origin:8444"));
    }

    @Test
    void parsesTheNamespaceHeader() {
        HttpHeaders h =
                headers(
                        Map.of(
                                "X-Pelican-Namespace",
                                List.of(
                                        "namespace=/foo/bar, require-token=true,"
                                                + " collections-url=https://origin:8444")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/foo/bar/obj"));
        assertThat(response.namespace().namespace()).contains(ObjectPath.of("/foo/bar"));
        assertThat(response.requiresToken()).isTrue();
        assertThat(response.namespace().collectionsUrl()).contains(URI.create("https://origin:8444"));
    }

    @Test
    void aMissingNamespaceHeaderMeansNoTokenIsRequired() {
        DirectorResponse response = DirectorResponse.parse(HttpHeaders.empty(), ObjectPath.of("/x"));
        assertThat(response.requiresToken()).isFalse();
        assertThat(response.namespace().namespace()).isEmpty();
    }

    @Test
    void parsesRepeatedIssuers() {
        // X-Pelican-Authorization repeats one key, which a naive key=value map would collapse.
        HttpHeaders h =
                headers(
                        Map.of(
                                "X-Pelican-Authorization",
                                List.of("issuer=https://a.example, issuer=https://b.example")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/x"));
        assertThat(response.acceptedIssuers())
                .containsExactly(URI.create("https://a.example"), URI.create("https://b.example"));
    }

    @Test
    void parsesTheTokenGenerationHeader() {
        HttpHeaders h =
                headers(
                        Map.of(
                                "X-Pelican-Token-Generation",
                                List.of(
                                        "issuer=https://issuer.example, base-path=/foo/bar,"
                                                + " max-scope-depth=2, strategy=OAuth2")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/foo/bar/obj"));
        TokenGenerationHint hint = response.tokenGeneration().orElseThrow();
        assertThat(hint.issuers()).containsExactly(URI.create("https://issuer.example"));
        assertThat(hint.basePath()).contains(ObjectPath.of("/foo/bar"));
        assertThat(hint.maxScopeDepth()).isEqualTo(2);
        assertThat(hint.strategy()).isEqualTo(TokenGenerationHint.Strategy.OAUTH2);
    }

    @Test
    void readsTheBrokerAndJobId() {
        HttpHeaders h =
                headers(
                        Map.of(
                                "X-Pelican-Broker", List.of("https://broker.example"),
                                "X-Pelican-JobId", List.of("job-1")));
        DirectorResponse response = DirectorResponse.parse(h, ObjectPath.of("/x"));
        assertThat(response.broker()).contains(URI.create("https://broker.example"));
        assertThat(response.jobId()).contains("job-1");
    }
}
