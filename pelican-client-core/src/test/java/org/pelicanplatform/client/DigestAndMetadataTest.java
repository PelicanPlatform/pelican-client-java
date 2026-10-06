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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pelicanplatform.client.http.HttpHeaders;
import org.pelicanplatform.client.integrity.DigestHeader;

class DigestAndMetadataTest {

    private static HttpHeaders digest(String... values) {
        return HttpHeaders.of(Map.of("Digest", List.of(values)));
    }

    @Test
    void parsesHexCrc32c() {
        assertThat(DigestHeader.parse(digest("crc32c=1a2b3c4d")))
                .containsEntry(DigestAlgorithm.CRC32C, "1a2b3c4d");
    }

    @Test
    void acceptsXrootdsBase64Crc32c() {
        // XRootD base64-encodes crc32c where the IANA registry says hex (xrootd#2456). A client
        // that only read hex would silently record no checksum for every XRootD origin.
        byte[] raw = {0x1a, 0x2b, 0x3c, 0x4d};
        String base64 = java.util.Base64.getEncoder().encodeToString(raw);
        assertThat(base64).hasSize(8).endsWith("==");
        assertThat(DigestHeader.parse(digest("crc32c=" + base64)))
                .containsEntry(DigestAlgorithm.CRC32C, "1a2b3c4d");
    }

    @Test
    void dropsACrcValueTooLongToBeOne() {
        // Some origins answer with a different algorithm under the requested name. Comparing
        // against that would fail every transfer and blame the network.
        assertThat(DigestHeader.parse(digest("crc32c=d41d8cd98f00b204e9800998ecf8427e"))).isEmpty();
    }

    @Test
    void padsAShortCrc() {
        assertThat(DigestHeader.parse(digest("crc32c=abc")))
                .containsEntry(DigestAlgorithm.CRC32C, "00000abc");
    }

    @Test
    void parsesSeveralAlgorithmsAcrossLinesAndCommas() {
        HttpHeaders headers =
                HttpHeaders.of(Map.of("Digest", List.of("md5=abc==, crc32c=00000001", "sha=def==")));
        Map<DigestAlgorithm, String> parsed = DigestHeader.parse(headers);
        assertThat(parsed)
                .containsEntry(DigestAlgorithm.MD5, "abc==")
                .containsEntry(DigestAlgorithm.CRC32C, "00000001")
                .containsEntry(DigestAlgorithm.SHA1, "def==");
    }

    @Test
    void skipsUnknownAlgorithms() {
        assertThat(DigestHeader.parse(digest("sha-512=zzz"))).isEmpty();
    }

    @Test
    void buildsAWantDigestValue() {
        assertThat(DigestHeader.want(List.of())).isEqualTo("crc32c");
        assertThat(DigestHeader.want(List.of(DigestAlgorithm.MD5, DigestAlgorithm.SHA1)))
                .isEqualTo("md5,sha");
    }

    @Test
    void computesDigestsInTheEncodingServersUse() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);

        DigestAlgorithm.Digester crc = DigestAlgorithm.CRC32C.newDigester();
        crc.update(data, 0, data.length);
        java.util.zip.CRC32C expected = new java.util.zip.CRC32C();
        expected.update(data, 0, data.length);
        assertThat(crc.encoded()).isEqualTo(String.format("%08x", expected.getValue()));

        DigestAlgorithm.Digester md5 = DigestAlgorithm.MD5.newDigester();
        md5.update(data, 0, data.length);
        assertThat(md5.encoded()).isEqualTo("XrY7u+Ae7tCTyyK7j1rNww==");
    }

    @Test
    void byteRangeConvertsToAnInclusiveHeader() {
        assertThat(ByteRange.of(0, 100).toHeaderValue()).isEqualTo("bytes=0-99");
        assertThat(ByteRange.of(50, 1).toHeaderValue()).isEqualTo("bytes=50-50");
        assertThat(ByteRange.from(50).toHeaderValue()).isEqualTo("bytes=50-");
        assertThatThrownBy(() -> ByteRange.of(0, 0)).isInstanceOf(PelicanException.class);
        assertThatThrownBy(() -> ByteRange.of(-1, 5)).isInstanceOf(PelicanException.class);
    }

    @Test
    void serializesObjectMetadataAsStructuredFields() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("experiment", "atlas");
        values.put("run_number", 4172L);
        values.put("is_test", false);
        PutObjectRequest request =
                PutObjectRequest.builder()
                        .path("f")
                        .body(org.pelicanplatform.client.http.RequestBody.fromString("x"))
                        .metadata(values)
                        .build();
        assertThat(StructuredFields.dictionary(request.objectMetadata()))
                .isEqualTo("experiment=\"atlas\", run_number=4172, is_test=?0");
    }

    @Test
    void refusesMetadataKeysTheOriginComputes() {
        // The origin overwrites these regardless; failing here tells the caller, instead of
        // letting them believe they set a value that silently disappears.
        assertThatThrownBy(
                        () ->
                                PutObjectRequest.builder()
                                        .path("f")
                                        .body(org.pelicanplatform.client.http.RequestBody.fromString("x"))
                                        .metadata("size", 12L))
                .isInstanceOf(PelicanException.class)
                .hasMessageContaining("computed by the origin");
    }

    @Test
    void refusesMetadataStructuredFieldsCannotCarry() {
        assertThatThrownBy(
                        () ->
                                StructuredFields.dictionary(Map.of("Experiment", "atlas")))
                .isInstanceOf(PelicanException.class);
        assertThatThrownBy(() -> StructuredFields.dictionary(Map.of("note", "café")))
                .isInstanceOf(PelicanException.class)
                .hasMessageContaining("printable ASCII");
    }

    @Test
    void escapesStringsInMetadata() {
        assertThat(StructuredFields.dictionary(Map.of("note", "a \"quoted\" \\ value")))
                .isEqualTo("note=\"a \\\"quoted\\\" \\\\ value\"");
    }
}
