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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pelicanplatform.client.auth.StaticCredentialProvider;
import org.pelicanplatform.client.http.JdkHttpTransport;
import org.pelicanplatform.client.http.RequestBody;

/**
 * Conformance tests against a real Pelican federation.
 *
 * <p>Everything else in this suite runs against {@code FakeFederation}, which implements
 * the protocol as this library's author read it out of the Go source -- so it checks the
 * parsing against one reading of the protocol, not against a server.  These tests exist to
 * check the reading, and they concentrate on the places where being wrong is plausible and
 * silent: the shape of a {@code Link} URL, the encoding of a checksum, whether a scope
 * computed relative to an advertised base path is one the origin will actually accept.
 *
 * <p>Skipped unless {@code PELICAN_TEST_DISCOVERY_URL} is set, so an ordinary
 * {@code mvn test} stays hermetic and offline. Start a federation with
 * {@code ci/start-federation.sh} and source the env file it writes.
 */
@Tag("federation")
class FederationConformanceTest {

    private static URI federation;
    private static String publicPrefix;
    private static String protectedPrefix;
    private static String token;
    private static SSLContext sslContext;

    private static PelicanClient publicClient;
    private static PelicanClient protectedClient;

    @BeforeAll
    static void setUp() throws Exception {
        String discovery = System.getenv("PELICAN_TEST_DISCOVERY_URL");
        assumeTrue(
                discovery != null && !discovery.isBlank(),
                "PELICAN_TEST_DISCOVERY_URL is not set; run ci/start-federation.sh first");

        federation = URI.create(discovery);
        publicPrefix = env("PELICAN_TEST_PUBLIC_PREFIX", "/test/public");
        protectedPrefix = env("PELICAN_TEST_PROTECTED_PREFIX", "/test/protected");
        token = System.getenv("PELICAN_TEST_TOKEN");
        sslContext = trustOnly(System.getenv("PELICAN_TEST_CA_FILE"));

        // A token even for the public namespace: reads there are public, but writes are
        // not, and the client only attaches it where it is needed.
        publicClient = client(publicPrefix, token);
        protectedClient = client(protectedPrefix, token);
    }

    @AfterAll
    static void tearDown() {
        if (publicClient != null) {
            publicClient.close();
        }
        if (protectedClient != null) {
            protectedClient.close();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static PelicanClient client(String basePath, String bearerToken) {
        PelicanClientBuilder builder =
                PelicanClient.builder()
                        .federation(federation)
                        .basePath(basePath)
                        .timeout(Duration.ofSeconds(30))
                        .userAgent("pelican-client-java-conformance")
                        .httpTransport(JdkHttpTransport.builder().sslContext(sslContext).build());
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.credentials(StaticCredentialProvider.of(bearerToken));
        }
        return builder.build();
    }

    /**
     * A trust store holding only the test federation's CA.
     *
     * <p>Deliberately not "trust everything": disabling verification would mean the TLS path
     * this client actually uses in production goes untested here, which is the one place a
     * conformance run can cheaply cover it.
     */
    private static SSLContext trustOnly(String caFile) throws Exception {
        if (caFile == null || caFile.isBlank() || !Files.isReadable(Path.of(caFile))) {
            return SSLContext.getDefault();
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int index = 0;
        try (InputStream in = Files.newInputStream(Path.of(caFile))) {
            for (Certificate certificate :
                    CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trustStore.setCertificateEntry("pelican-test-ca-" + index++, certificate);
            }
        }
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static String read(ObjectStream stream) throws IOException {
        try (ObjectStream s = stream) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ resolution

    @Test
    void discoversTheFederation() {
        assertThat(publicClient.federationInfo().directorEndpoint()).isNotNull();
    }

    @Test
    void readsAPublicObject() throws IOException {
        assertThat(read(publicClient.get("hello.txt"))).contains("hello from a public namespace");
    }

    /**
     * The Director advertises complete object URLs, and a real origin sits under a path
     * prefix ({@code /api/v1.0/origin/data}). A cached Director answer is reused for other
     * objects in the namespace, so the prefix has to survive while the object path is
     * replaced. Reading two siblings is what catches getting that backwards.
     */
    @Test
    void reusesADirectorAnswerAcrossSiblingObjects() throws IOException {
        assertThat(read(publicClient.get("hello.txt"))).isNotEmpty();
        assertThat(read(publicClient.get("range.txt"))).isNotEmpty();
        assertThat(read(publicClient.get("subdir/a.txt"))).isNotEmpty();
    }

    @Test
    void readsAByteRange() throws IOException {
        ObjectStream stream =
                publicClient.get(
                        GetObjectRequest.builder().path("range.txt").range(ByteRange.of(2, 3)).build());
        assertThat(stream.isPartial()).isTrue();
        assertThat(read(stream)).isEqualTo("234");
    }

    /**
     * The checksum encoding a real origin uses.
     *
     * <p>The IANA registry says {@code crc32c} is hex; XRootD sends base64. Whichever this
     * origin does, the client has to compare like with like -- and a mismatch here surfaces
     * as a checksum failure on every transfer, which reads like data corruption.
     */
    @Test
    void verifiesAChecksumAgainstARealOrigin() throws IOException {
        // Whether an origin answers Want-Digest depends on its backend -- a posixv2 origin
        // with no cached checksums reports none. Both outcomes are correct; what must hold is
        // that a digest, when offered, is in an encoding this client can compare against, and
        // that a caller who asked for verification and got none is told so rather than being
        // handed unverified bytes.
        ObjectStream probe = publicClient.get(GetObjectRequest.builder().path("hello.txt").wantDigests(DigestAlgorithm.CRC32C).build());
        String contents = read(probe);
        assertThat(contents).isNotEmpty();

        if (probe.digests().containsKey(DigestAlgorithm.CRC32C)) {
            ObjectStream verified =
                    publicClient.get(
                            GetObjectRequest.builder()
                                    .path("hello.txt")
                                    .verifyDigest(DigestAlgorithm.CRC32C)
                                    .build());
            assertThat(read(verified)).isEqualTo(contents);
        } else {
            System.out.println("origin reports no crc32c digest; asserting the missing-digest contract");
            assertThatThrownBy(
                            () ->
                                    read(
                                            publicClient.get(
                                                    GetObjectRequest.builder()
                                                            .path("hello.txt")
                                                            .verifyDigest(DigestAlgorithm.CRC32C)
                                                            .build())))
                    .isInstanceOf(PelicanException.class)
                    .hasMessageContaining("reported no crc32c digest");
        }
    }

    // ------------------------------------------------------------------ metadata

    @Test
    void statsAnObjectAndACollection() {
        ObjectInfo object = publicClient.stat("hello.txt");
        assertThat(object.isCollection()).isFalse();
        assertThat(object.size()).isGreaterThan(0);

        ObjectInfo collection = publicClient.stat("subdir");
        assertThat(collection.isCollection()).isTrue();
    }

    @Test
    void statOfAMissingObjectIsNotFound() {
        assertThatThrownBy(() -> publicClient.stat("no-such-object"))
                .isInstanceOf(ObjectNotFoundException.class);
    }

    /**
     * A real multi-status body names each entry by an href carrying the origin's own path
     * prefix and, for a collection, a trailing slash. Mapping those back onto federation
     * paths -- and recognising which one is the collection itself -- is the fiddliest
     * parsing in the client.
     */
    @Test
    void listsACollection() {
        try (Stream<ObjectInfo> entries = publicClient.list("subdir")) {
            List<ObjectInfo> list = entries.toList();
            assertThat(list).extracting(ObjectInfo::name).containsExactlyInAnyOrder("a.txt", "b.txt", "nested");
            assertThat(list)
                    .filteredOn(ObjectInfo::isCollection)
                    .extracting(ObjectInfo::name)
                    .containsExactly("nested");
            assertThat(list)
                    .allSatisfy(e -> assertThat(e.path().startsWith(publicClient.basePath())).isTrue());
        }
    }

    @Test
    void listsRecursively() {
        try (Stream<ObjectInfo> entries =
                publicClient.list(ListRequest.builder().path("subdir").recursive(true).build())) {
            assertThat(entries.map(ObjectInfo::name))
                    .contains("a.txt", "b.txt", "nested", "deep.txt");
        }
    }

    /**
     * Also covers two things a single-process federation cannot show: that {@code OPTIONS}
     * is asked of an origin rather than of a cache (a cache answers 405), and the collection
     * redirect, where an origin answers {@code OPTIONS} of a collection with a 307 to its
     * trailing-slash form.
     */
    @Test
    void reportsTheVerbsTheOriginServes() {
        Capabilities capabilities = publicClient.capabilities(publicClient.basePath());
        assertThat(capabilities.allowedMethods()).isNotEmpty();
        assertThat(capabilities.supportsListing()).isTrue();
        // Printed rather than asserted: which verbs exist is a property of the backend, and
        // the point of this call is to find out rather than to require.
        System.out.println("origin verbs: " + capabilities.allowedMethods());
    }

    // ------------------------------------------------------------------ writes

    /**
     * Whether a second write to the same path is refused.
     *
     * <p>Not an assertion that it is. Write-once is enforced by some storage backends and not
     * others -- an XRootD-fronted origin refuses the second {@code PUT}, a native posixv2
     * origin replaces the object -- so the contract this client can promise is only that the
     * refusal, where it happens, arrives as {@link ObjectExistsException} rather than as a
     * bare 409. The observed behavior is printed, because which backends do which is exactly
     * the kind of thing that should be discovered here rather than assumed.
     */
    @Test
    void reportsWhetherTheBackendEnforcesWriteOnce() throws IOException {
        String name = "conformance-" + UUID.randomUUID() + ".txt";
        try {
            publicClient.put(
                    PutObjectRequest.builder()
                            .path(name)
                            .body(RequestBody.fromString("first"))
                            .build());

            Throwable refusal = null;
            try {
                publicClient.put(
                        PutObjectRequest.builder()
                                .path(name)
                                .body(RequestBody.fromString("second"))
                                .build());
            } catch (PelicanException e) {
                refusal = e;
            }

            if (refusal == null) {
                System.out.println("backend allows overwrite: the second PUT replaced the object");
                assertThat(read(publicClient.get(name))).isEqualTo("second");
            } else {
                System.out.println("backend enforces write-once: " + refusal.getClass().getSimpleName());
                assertThat(refusal).isInstanceOf(ObjectExistsException.class);
                assertThat(read(publicClient.get(name))).isEqualTo("first");
            }
        } finally {
            try {
                publicClient.delete(name);
            } catch (PelicanException e) {
                System.out.println("cleanup of " + name + " failed: " + e.getMessage());
            }
        }
    }

    @Test
    void roundTripsAnObject() throws IOException {
        String name = "conformance-roundtrip-" + UUID.randomUUID() + ".txt";
        String contents = "round trip contents";
        try {
            publicClient.put(
                    PutObjectRequest.builder().path(name).body(RequestBody.fromString(contents)).build());
            assertThat(read(publicClient.get(name))).isEqualTo(contents);
            assertThat(publicClient.stat(name).size()).isEqualTo(contents.length());
        } finally {
            publicClient.delete(name);
        }
        assertThat(publicClient.exists(name)).isFalse();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void aProtectedNamespaceRefusesAnUncredentialedClient() {
        try (PelicanClient anonymous = client(protectedPrefix, null)) {
            assertThatThrownBy(() -> anonymous.get("secret.txt"))
                    .isInstanceOf(AccessDeniedException.class)
                    .satisfies(
                            e -> {
                                AccessDeniedException denied = (AccessDeniedException) e;
                                assertThat(denied.credentialWasPresented()).isFalse();
                                assertThat(denied.acceptedIssuers()).isNotEmpty();
                                assertThat(denied.requiredScopes()).containsExactly("storage.read:/secret.txt");
                            });
        }
    }

    /**
     * The scope arithmetic, end to end.
     *
     * <p>The token is minted with scopes relative to the namespace's base path, which is what
     * the Director advertises in {@code X-Pelican-Token-Generation}. If this client computed
     * that relationship differently from the way the origin reads it, this is where it shows.
     */
    @Test
    void readsAProtectedObjectWithATokenScopedToTheNamespace() throws IOException {
        assumeTrue(token != null && !token.isBlank(), "PELICAN_TEST_TOKEN is not set");
        assertThat(read(protectedClient.get("secret.txt"))).contains("protected contents");
    }
}
