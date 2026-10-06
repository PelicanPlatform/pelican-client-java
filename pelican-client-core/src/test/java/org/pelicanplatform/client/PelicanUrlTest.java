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

import java.net.URI;
import org.junit.jupiter.api.Test;

class PelicanUrlTest {

    @Test
    void parsesAFederationFromTheHost() {
        PelicanUrl url = PelicanUrl.parse("pelican://example.org/ns/obj");
        assertThat(url.discoveryUri()).isEqualTo(URI.create("https://example.org"));
        assertThat(url.path().value()).isEqualTo("/ns/obj");
    }

    @Test
    void keepsAPortInTheFederationHost() {
        assertThat(PelicanUrl.parse("pelican://example.org:8444/ns/obj").discoveryUri())
                .isEqualTo(URI.create("https://example.org:8444"));
    }

    @Test
    void osdfUsesTheFixedDiscoveryHost() {
        PelicanUrl url = PelicanUrl.parse("osdf:///ns/obj");
        assertThat(url.discoveryUri().getHost()).isEqualTo(PelicanUrl.OSDF_DISCOVERY_HOST);
        assertThat(url.path().value()).isEqualTo("/ns/obj");
    }

    @Test
    void osdfToleratesTheMissingThirdSlash() {
        // osdf://ns/obj is a common typo; Pelican reads the "host" as the first path segment
        // rather than rejecting the URL, and a client that did otherwise would lose the segment.
        assertThat(PelicanUrl.parse("osdf://ns/obj").path().value()).isEqualTo("/ns/obj");
        assertThat(PelicanUrl.parse("stash://ns/obj").path().value()).isEqualTo("/ns/obj");
    }

    @Test
    void readsACondorCredentialNameOffTheScheme() {
        PelicanUrl url = PelicanUrl.parse("mytoken+osdf:///ns/obj");
        assertThat(url.credentialName()).contains("mytoken");
        assertThat(url.scheme()).isEqualTo("osdf");
        assertThat(url.path().value()).isEqualTo("/ns/obj");
    }

    @Test
    void parsesTransferHints() {
        PelicanUrl url =
                PelicanUrl.parse("pelican://example.org/ns/obj?recursive&pack=tar.gz&directread");
        assertThat(url.hints().recursive()).isTrue();
        assertThat(url.hints().pack()).contains(TransferHints.PackFormat.TAR_GZ);
        assertThat(url.hints().directRead()).isTrue();
        assertThat(url.hints().skipStat()).isFalse();
    }

    @Test
    void anExplicitFalseTurnsAHintOff() {
        assertThat(PelicanUrl.parse("pelican://e.org/ns?recursive=false").hints().recursive())
                .isFalse();
    }

    @Test
    void schemelessNeedsAFederation() {
        assertThatThrownBy(() -> PelicanUrl.parse("/ns/obj"))
                .isInstanceOf(PelicanException.class)
                .hasMessageContaining("without a scheme");
        assertThat(PelicanUrl.parse("/ns/obj", URI.create("https://example.org")).path().value())
                .isEqualTo("/ns/obj");
    }

    @Test
    void rejectsAnUnknownScheme() {
        assertThatThrownBy(() -> PelicanUrl.parse("s3://bucket/key"))
                .isInstanceOf(PelicanException.class)
                .hasMessageContaining("not understood");
    }

    @Test
    void percentEncodingIsDecodedIntoThePath() {
        assertThat(PelicanUrl.parse("pelican://e.org/ns/a%20b").path().value()).isEqualTo("/ns/a b");
    }

    @Test
    void normalizesAFederationReference() {
        assertThat(PelicanUrl.normalizeDiscovery(URI.create("example.org")))
                .isEqualTo(URI.create("https://example.org"));
        assertThat(PelicanUrl.normalizeDiscovery(URI.create("https://example.org/some/path")))
                .isEqualTo(URI.create("https://example.org"));
        assertThat(PelicanUrl.normalizeDiscovery(URI.create("osdf:///ns")).getHost())
                .isEqualTo(PelicanUrl.OSDF_DISCOVERY_HOST);
    }
}
