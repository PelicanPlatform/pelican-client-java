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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pelicanplatform.client.auth.StaticCredentialProvider;
import org.pelicanplatform.client.http.RequestBody;
import org.pelicanplatform.client.http.RetryPolicy;
import org.pelicanplatform.client.testkit.FakeFederation;

class PelicanClientTest {

    private FakeFederation federation;

    @BeforeEach
    void setUp() {
        federation = new FakeFederation();
    }

    @AfterEach
    void tearDown() {
        federation.close();
    }

    private PelicanClientBuilder clientBuilder() {
        return PelicanClient.builder()
                .federation(federation.discoveryUri())
                .basePath("/ns")
                .timeout(Duration.ofSeconds(10))
                .retryPolicy(new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50)));
    }

    private static String read(ObjectStream stream) {
        try (ObjectStream s = stream) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ get

    @Test
    void getsAnObjectThroughDiscoveryAndTheDirector() {
        federation.directorServers("cache1").objectServer("cache1").put("/ns/data/file", "hello");

        try (PelicanClient client = clientBuilder().build()) {
            assertThat(read(client.get("data/file"))).isEqualTo("hello");
        }
        assertThat(federation.objectServer("cache1").requestCount()).isEqualTo(1);
    }

    @Test
    void failsOverToTheNextServerInPriorityOrder() {
        federation.directorServers("cache1", "cache2", "origin");
        federation.objectServer("cache1").failWith(502, "bad gateway");
        federation.objectServer("cache2").failWith(503, "unavailable");
        federation.objectServer("origin").put("/ns/data/file", "made it");

        try (PelicanClient client = clientBuilder().build()) {
            assertThat(read(client.get("data/file"))).isEqualTo("made it");
        }
        assertThat(federation.objectServer("cache1").requestCount()).isEqualTo(1);
        assertThat(federation.objectServer("cache2").requestCount()).isEqualTo(1);
    }

    @Test
    void reportsEveryServerItTriedWhenAllOfThemFail() {
        federation.directorServers("cache1", "cache2");
        federation.objectServer("cache1").failWith(502, "bad gateway");
        federation.objectServer("cache2").failWith(503, "unavailable");

        try (PelicanClient client = clientBuilder().build()) {
            assertThatThrownBy(() -> client.get("data/file"))
                    .isInstanceOf(AllServersFailedException.class)
                    .satisfies(
                            e -> {
                                List<AttemptFailure> failures =
                                        ((AllServersFailedException) e).failures();
                                assertThat(failures).hasSize(2);
                                assertThat(failures.get(0).statusCode()).isEqualTo(502);
                                assertThat(failures.get(1).statusCode()).isEqualTo(503);
                            })
                    .hasMessageContaining("502")
                    .hasMessageContaining("503");
        }
    }

    @Test
    void aMissingObjectIsNotFoundRatherThanAServerFailure() {
        federation.directorServers("cache1", "cache2");

        try (PelicanClient client = clientBuilder().build()) {
            assertThatThrownBy(() -> client.get("data/missing"))
                    .isInstanceOf(ObjectNotFoundException.class);
        }
        // Both caches were asked: one cache not holding an object says nothing about the next.
        assertThat(federation.objectServer("cache2").requestCount()).isEqualTo(1);
    }

    @Test
    void readsAByteRange() {
        federation.directorServers("cache1").objectServer("cache1").put("/ns/data/file", "0123456789");

        try (PelicanClient client = clientBuilder().build()) {
            ObjectStream stream =
                    client.get(GetObjectRequest.builder().path("data/file").range(ByteRange.of(2, 3)).build());
            assertThat(stream.isPartial()).isTrue();
            assertThat(read(stream)).isEqualTo("234");
        }
    }

    @Test
    void verifiesTheChecksumTheServerReports() {
        federation.directorServers("cache1").objectServer("cache1").put("/ns/data/file", "hello");

        try (PelicanClient client = clientBuilder().build()) {
            ObjectStream stream =
                    client.get(
                            GetObjectRequest.builder()
                                    .path("data/file")
                                    .verifyDigest(DigestAlgorithm.CRC32C)
                                    .build());
            assertThat(read(stream)).isEqualTo("hello");
            assertThat(stream.digests()).containsKey(DigestAlgorithm.CRC32C);
        }
    }

    @Test
    void reportsWhichServerAnsweredAndTheObjectsMetadata() {
        federation.directorServers("cache1").objectServer("cache1").put("/ns/data/file", "hello");

        try (PelicanClient client = clientBuilder().build()) {
            try (ObjectStream stream = client.get("data/file")) {
                assertThat(stream.contentLength()).isEqualTo(5);
                assertThat(stream.etag()).contains("etag-5");
                assertThat(stream.lastModified()).isPresent();
                assertThat(stream.servedBy()).isEqualTo(federation.serverUri("cache1"));
                stream.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void sendsNoCredentialToAPublicNamespace() {
        // Attaching a token a namespace did not ask for hands it to every cache that answers.
        federation.namespace("/ns", false).directorServers("cache1");
        federation.objectServer("cache1").put("/ns/data/file", "public");

        try (PelicanClient client =
                clientBuilder()
                        .credentials(StaticCredentialProvider.of(FakeFederation.token("storage.read:/")))
                        .build()) {
            assertThat(read(client.get("data/file"))).isEqualTo("public");
        }
        assertThat(federation.objectServer("cache1").seenAuthorization()).containsExactly("");
    }

    @Test
    void sendsTheCredentialWhenTheNamespaceRequiresOne() {
        federation.namespace("/ns", true).directorServers("origin");
        federation.objectServer("origin").put("/ns/data/file", "secret");
        String token = FakeFederation.token("storage.read:/");

        try (PelicanClient client =
                clientBuilder().credentials(StaticCredentialProvider.of(token)).build()) {
            assertThat(read(client.get("data/file"))).isEqualTo("secret");
        }
        assertThat(federation.objectServer("origin").seenAuthorization())
                .containsExactly("Bearer " + token);
        // The Director is re-asked with the credential: it may route a credentialed request
        // differently from an anonymous one.
        assertThat(federation.directorAuthorization()).contains("Bearer " + token);
    }

    @Test
    void explainsWhatScopeWasNeededWhenNoCredentialIsAvailable() {
        federation.namespace("/ns", true).basePath("/ns").directorServers("origin");

        try (PelicanClient client = clientBuilder().build()) {
            assertThatThrownBy(() -> client.get("data/file"))
                    .isInstanceOf(AccessDeniedException.class)
                    .satisfies(
                            e -> {
                                AccessDeniedException denied = (AccessDeniedException) e;
                                assertThat(denied.credentialWasPresented()).isFalse();
                                assertThat(denied.requiredScopes())
                                        .containsExactly("storage.read:/data/file");
                                assertThat(denied.acceptedIssuers()).isNotEmpty();
                            });
        }
    }

    @Test
    void aRejectedCredentialIsTerminalRatherThanTriedEverywhere() {
        // Retrying a 403 against every cache produces more 403s, a slower error, and more
        // copies of the caller's token on the wire.
        federation.namespace("/ns", true).directorServers("cache1", "cache2");
        federation.objectServer("cache1").failWith(403, "forbidden");
        federation.objectServer("cache2").put("/ns/data/file", "unreachable");

        try (PelicanClient client =
                clientBuilder()
                        .credentials(StaticCredentialProvider.of(FakeFederation.token("storage.read:/")))
                        .build()) {
            assertThatThrownBy(() -> client.get("data/file"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("storage.read:/data/file");
        }
        assertThat(federation.objectServer("cache2").requestCount()).isZero();
    }

    // ------------------------------------------------------------------ stat and list

    @Test
    void statsAnObject() {
        federation.directorServers("origin").objectServer("origin").put("/ns/data/file", "hello");

        try (PelicanClient client = clientBuilder().build()) {
            ObjectInfo info = client.stat("data/file");
            assertThat(info.path()).isEqualTo(ObjectPath.of("/ns/data/file"));
            assertThat(info.size()).isEqualTo(5);
            assertThat(info.isCollection()).isFalse();
            assertThat(info.etag()).contains("etag-5");
        }
    }

    @Test
    void statsACollection() {
        federation.directorServers("origin").objectServer("origin").collection("/ns/data");

        try (PelicanClient client = clientBuilder().build()) {
            ObjectInfo info = client.stat("data");
            assertThat(info.isCollection()).isTrue();
            assertThat(info.size()).isEqualTo(-1);
        }
    }

    @Test
    void statOfAMissingPathIsNotFound() {
        federation.directorServers("origin").objectServer("origin");

        try (PelicanClient client = clientBuilder().build()) {
            assertThatThrownBy(() -> client.stat("nope")).isInstanceOf(ObjectNotFoundException.class);
            assertThat(client.exists("nope")).isFalse();
        }
    }

    @Test
    void listsACollectionOneLevelDeep() {
        federation.directorServers("origin");
        federation
                .objectServer("origin")
                .collection("/ns/data")
                .put("/ns/data/a", "aaa")
                .put("/ns/data/b", "bb")
                .collection("/ns/data/sub")
                .put("/ns/data/sub/deep", "d");

        try (PelicanClient client = clientBuilder().build();
                Stream<ObjectInfo> entries = client.list("data")) {
            List<ObjectInfo> list = entries.toList();
            assertThat(list).extracting(ObjectInfo::name).containsExactlyInAnyOrder("a", "b", "sub");
            assertThat(list)
                    .filteredOn(ObjectInfo::isCollection)
                    .extracting(ObjectInfo::name)
                    .containsExactly("sub");
            assertThat(list)
                    .filteredOn(i -> i.name().equals("a"))
                    .first()
                    .satisfies(i -> assertThat(i.size()).isEqualTo(3));
        }
    }

    @Test
    void doesNotIncludeTheCollectionItselfInItsListing() {
        federation.directorServers("origin");
        federation.objectServer("origin").collection("/ns/data").put("/ns/data/a", "aaa");

        try (PelicanClient client = clientBuilder().build();
                Stream<ObjectInfo> entries = client.list("data")) {
            assertThat(entries.map(ObjectInfo::path))
                    .containsExactly(ObjectPath.of("/ns/data/a"));
        }
    }

    @Test
    void listsRecursivelyAndLazily() {
        federation.directorServers("origin");
        federation
                .objectServer("origin")
                .collection("/ns/data")
                .put("/ns/data/a", "a")
                .collection("/ns/data/sub")
                .put("/ns/data/sub/deep", "d")
                .collection("/ns/data/sub/deeper")
                .put("/ns/data/sub/deeper/leaf", "l");

        try (PelicanClient client = clientBuilder().build();
                Stream<ObjectInfo> entries =
                        client.list(ListRequest.builder().path("data").recursive(true).build())) {
            assertThat(entries.map(i -> i.path().value()))
                    .containsExactlyInAnyOrder(
                            "/ns/data/a",
                            "/ns/data/sub",
                            "/ns/data/sub/deep",
                            "/ns/data/sub/deeper",
                            "/ns/data/sub/deeper/leaf");
        }
    }

    @Test
    void aRecursiveWalkStopsCostingWhenTheCallerStops() {
        // The walk issues one PROPFIND per collection, as the consumer reaches it. Taking one
        // entry must not descend the whole tree.
        federation.directorServers("origin");
        federation
                .objectServer("origin")
                .collection("/ns/data")
                .collection("/ns/data/sub")
                .put("/ns/data/sub/deep", "d");

        try (PelicanClient client = clientBuilder().build()) {
            int before = federation.objectServer("origin").requestCount();
            try (Stream<ObjectInfo> entries =
                    client.list(ListRequest.builder().path("data").recursive(true).build())) {
                assertThat(entries.findFirst()).isPresent();
            }
            // One PROPFIND for /ns/data. Descending into sub would be a second.
            assertThat(federation.objectServer("origin").requestCount() - before).isEqualTo(1);
        }
    }

    @Test
    void fallsBackToTheCollectionsEndpointWhenCachesRefuseToList() {
        // An XRootD cache answers PROPFIND on a collection with 409: caches serve objects, not
        // listings. The namespace advertises a collections-url for exactly this case.
        federation.directorServers("cache1", "cache2").collectionsServer("origin");
        federation.objectServer("cache1").refusePropfind().collection("/ns/data");
        federation.objectServer("cache2").refusePropfind().collection("/ns/data");
        federation.objectServer("origin").collection("/ns/data").put("/ns/data/a", "aaa");

        try (PelicanClient client = clientBuilder().build();
                Stream<ObjectInfo> entries = client.list("data")) {
            assertThat(entries.map(ObjectInfo::name)).containsExactly("a");
        }
        assertThat(federation.objectServer("cache1").requestCount()).isEqualTo(1);
        assertThat(federation.objectServer("cache2").requestCount()).isEqualTo(1);
    }

    @Test
    void fallsBackWhenACacheRedirectsAListingToAnotherHost() {
        // The native Go cache answers PROPFIND on a collection with a 307 to the origin
        // rather than with the 409 an XRootD cache returns. Following that redirect would
        // mean talking to a host the Director never named, and carrying the caller's
        // credential there, so the client goes to the advertised collections endpoint
        // instead -- the same place the 409 path ends up.
        federation.directorServers("cache1").collectionsServer("origin");
        String elsewhere = "localhost:" + federation.discoveryUri().getPort();
        federation.objectServer("cache1").redirectPropfindTo(elsewhere).collection("/ns/data");
        federation.objectServer("origin").collection("/ns/data").put("/ns/data/a", "aaa");

        try (PelicanClient client = clientBuilder().build();
                Stream<ObjectInfo> entries = client.list("data")) {
            assertThat(entries.map(ObjectInfo::name)).containsExactly("a");
        }
        assertThat(federation.objectServer("cache1").requestCount()).isEqualTo(1);
    }

    @Test
    void usesADirectorThatAnswersPropfindItself() {
        federation.directorServers("origin").collectionsServer("origin").directorProxiesPropfind(true);
        federation.objectServer("origin").collection("/ns/data").put("/ns/data/a", "aaa");

        try (PelicanClient client = clientBuilder().build();
                Stream<ObjectInfo> entries = client.list("data")) {
            assertThat(entries.map(ObjectInfo::name)).containsExactly("a");
        }
        // The listing never touched an object server.
        assertThat(federation.objectServer("origin").requestCount()).isZero();
        assertThat(federation.directorMethods()).contains("PROPFIND");
    }

    // ------------------------------------------------------------------ writes

    @Test
    void uploadsAnObject() {
        federation.directorServers("origin").objectServer("origin");

        try (PelicanClient client = clientBuilder().build()) {
            PutResult result =
                    client.put(
                            PutObjectRequest.builder()
                                    .path("data/new")
                                    .body(RequestBody.fromString("contents"))
                                    .build());
            assertThat(result.path()).isEqualTo(ObjectPath.of("/ns/data/new"));
            assertThat(result.etag()).contains("etag-8");
        }
        assertThat(federation.objectServer("origin").content("/ns/data/new"))
                .isEqualTo("contents".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aWriteIsResolvedThroughTheDirectorsWritePath() {
        // A read-flavored query returns caches, which reject writes and would be handed the
        // caller's write credential.
        federation.directorServers("origin").objectServer("origin");

        try (PelicanClient client = clientBuilder().build()) {
            client.put(
                    PutObjectRequest.builder().path("data/new").body(RequestBody.fromString("x")).build());
        }
        assertThat(federation.directorMethods()).contains("PUT").doesNotContain("GET");
    }

    @Test
    void refusesToOverwriteAnExistingObject() {
        federation.directorServers("origin").objectServer("origin").put("/ns/data/file", "old");

        try (PelicanClient client = clientBuilder().build()) {
            assertThatThrownBy(
                            () ->
                                    client.put(
                                            PutObjectRequest.builder()
                                                    .path("data/file")
                                                    .body(RequestBody.fromString("new"))
                                                    .build()))
                    .isInstanceOf(ObjectExistsException.class);
        }
        assertThat(federation.objectServer("origin").content("/ns/data/file"))
                .isEqualTo("old".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void overwritesOnlyWhenAskedTo() {
        federation.directorServers("origin").objectServer("origin").put("/ns/data/file", "old");

        try (PelicanClient client = clientBuilder().build()) {
            client.put(
                    PutObjectRequest.builder()
                            .path("data/file")
                            .body(RequestBody.fromString("new"))
                            .overwrite(true)
                            .build());
        }
        assertThat(federation.objectServer("origin").content("/ns/data/file"))
                .isEqualTo("new".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refusesToRetryAOneShotStreamAgainstAnotherServer() {
        // Half of a one-shot stream has already gone out by the time an attempt fails; starting
        // again from it would write a truncated object that looks complete.
        federation.directorServers("origin1", "origin2");
        federation.objectServer("origin1").failWith(500, "boom");
        federation.objectServer("origin2");

        try (PelicanClient client = clientBuilder().build()) {
            assertThatThrownBy(
                            () ->
                                    client.put(
                                            PutObjectRequest.builder()
                                                    .path("data/new")
                                                    .body(
                                                            RequestBody.fromInputStream(
                                                                    new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                                                                    1))
                                                    .build()))
                    .isInstanceOf(PelicanException.class)
                    .hasMessageContaining("one-shot stream");
        }
        assertThat(federation.objectServer("origin2").requestCount()).isZero();
    }

    @Test
    void aReplayableBodyDoesFailOver() {
        federation.directorServers("origin1", "origin2");
        federation.objectServer("origin1").failWith(500, "boom");
        federation.objectServer("origin2");

        try (PelicanClient client = clientBuilder().build()) {
            client.put(
                    PutObjectRequest.builder()
                            .path("data/new")
                            .body(RequestBody.fromString("x"))
                            .build());
        }
        assertThat(federation.objectServer("origin2").content("/ns/data/new")).isNotNull();
    }

    @Test
    void deletesCreatesCollectionsAndMoves() {
        federation.directorServers("origin");
        federation.objectServer("origin").put("/ns/data/file", "x");

        try (PelicanClient client = clientBuilder().build()) {
            client.createCollection("data/newcoll");
            assertThat(federation.objectServer("origin").has("/ns/data/newcoll")).isTrue();

            client.move(MoveRequest.builder().path("data/file").destination("data/moved").build());
            assertThat(federation.objectServer("origin").has("/ns/data/moved")).isTrue();
            assertThat(federation.objectServer("origin").has("/ns/data/file")).isFalse();

            client.delete("data/moved");
            assertThat(federation.objectServer("origin").has("/ns/data/moved")).isFalse();
        }
    }

    @Test
    void reportsWhichVerbsTheServerSupports() {
        federation.directorServers("origin").objectServer("origin").collection("/ns/data");

        try (PelicanClient client = clientBuilder().build()) {
            Capabilities capabilities = client.capabilities(ObjectPath.of("/ns/data"));
            assertThat(capabilities.supportsListing()).isTrue();
            assertThat(capabilities.supportsCreateCollection()).isTrue();
            assertThat(capabilities.supportsMove()).isTrue();
            assertThat(capabilities.supportsThirdPartyCopy()).isFalse();
            assertThat(capabilities.davHeader()).isEqualTo("1,2");
        }
    }

    // ------------------------------------------------------------------ resolution

    @Test
    void waitsOutARestartingDirector() {
        // A 502 from something that is not a Pelican process is an ingress in front of a
        // Director that is coming back up, and is worth waiting for.
        federation.directorFailures(2, 502);
        federation.directorServers("cache1").objectServer("cache1").put("/ns/data/file", "hello");

        try (PelicanClient client = clientBuilder().build()) {
            assertThat(read(client.get("data/file"))).isEqualTo("hello");
        }
        assertThat(federation.directorQueryCount()).isEqualTo(3);
    }

    @Test
    void reusesOneDirectorAnswerAcrossSiblingObjects() {
        federation.directorServers("cache1");
        federation
                .objectServer("cache1")
                .put("/ns/data/a", "a")
                .put("/ns/data/b", "b")
                .put("/ns/other/c", "c");

        try (PelicanClient client = clientBuilder().build()) {
            read(client.get("data/a"));
            read(client.get("data/b"));
            read(client.get("other/c"));
        }
        // All three are in the namespace the Director named, so one answer covers them.
        assertThat(federation.directorQueryCount()).isEqualTo(1);
    }

    @Test
    void doesNotReuseAReadAnswerForAWrite() {
        federation.directorServers("origin");
        federation.objectServer("origin").put("/ns/data/a", "a");

        try (PelicanClient client = clientBuilder().build()) {
            read(client.get("data/a"));
            client.put(
                    PutObjectRequest.builder().path("data/b").body(RequestBody.fromString("b")).build());
        }
        assertThat(federation.directorMethods()).containsExactly("GET", "PUT");
    }

    @Test
    void relativePathsCannotEscapeTheBasePath() {
        federation.directorServers("origin").objectServer("origin");

        try (PelicanClient client = clientBuilder().build()) {
            assertThatThrownBy(() -> client.get("../other/secret"))
                    .isInstanceOf(PathValidationException.class)
                    .hasMessageContaining("escapes its base");
        }
        assertThat(federation.directorQueryCount()).isZero();
    }

    @Test
    void takesTheFederationAndBasePathFromAPelicanUrl() {
        federation.directorServers("origin").objectServer("origin").put("/ns/data/file", "hello");

        try (PelicanClient client =
                PelicanClient.builder()
                        .federation(federation.discoveryUri())
                        .basePath("/ns")
                        .retryPolicy(RetryPolicy.none())
                        .build()) {
            assertThat(client.basePath()).isEqualTo(ObjectPath.of("/ns"));
            assertThat(read(client.get("data/file"))).isEqualTo("hello");
        }
    }

    @Test
    void exposesTheFederationsEndpoints() {
        federation.directorServers("origin");
        try (PelicanClient client = clientBuilder().build()) {
            assertThat(client.federationInfo().directorEndpoint().toString())
                    .isEqualTo(federation.discoveryUri() + "/director");
            assertThat(client.federationInfo().brokerEndpoint()).isPresent();
        }
    }
}
