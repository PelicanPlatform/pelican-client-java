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

package org.pelicanplatform.client.dav;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MultiStatusParserTest {

    private static final String LISTING =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/ns/data/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                    <D:getlastmodified>Tue, 02 Jan 2029 03:04:05 GMT</D:getlastmodified>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
                <D:propstat>
                  <D:prop><D:getcontentlength/></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/ns/data/file.txt</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype/>
                    <D:getcontentlength>1234</D:getcontentlength>
                    <D:getetag>"abc123"</D:getetag>
                    <D:getcontenttype>text/plain</D:getcontenttype>
                    <D:getlastmodified>Tue, 02 Jan 2029 03:04:05 GMT</D:getlastmodified>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
            """;

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesCollectionsAndObjects() {
        List<DavEntry> entries = MultiStatusParser.parse(stream(LISTING));
        assertThat(entries).hasSize(2);

        DavEntry collection = entries.get(0);
        assertThat(collection.href()).isEqualTo("/ns/data/");
        assertThat(collection.isCollection()).isTrue();
        assertThat(collection.lastModified()).contains(Instant.parse("2029-01-02T03:04:05Z"));

        DavEntry file = entries.get(1);
        assertThat(file.isCollection()).isFalse();
        assertThat(file.contentLength()).isEqualTo(1234);
        assertThat(file.etag()).contains("abc123");
        assertThat(file.contentType()).contains("text/plain");
    }

    @Test
    void ignoresPropertiesFromAFailedPropstat() {
        // The collection's second propstat reports getcontentlength as 404. A parser that read
        // it anyway would record a size of zero for every directory.
        DavEntry collection = MultiStatusParser.parse(stream(LISTING)).get(0);
        assertThat(collection.contentLength()).isEqualTo(-1);
    }

    @Test
    void handlesADocumentWithADifferentNamespacePrefix() {
        String xml =
                """
                <?xml version="1.0"?>
                <multistatus xmlns="DAV:">
                  <response>
                    <href>/ns/x</href>
                    <propstat><prop><getcontentlength>7</getcontentlength></prop>
                    <status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                </multistatus>
                """;
        List<DavEntry> entries = MultiStatusParser.parse(stream(xml));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).contentLength()).isEqualTo(7);
    }

    @Test
    void readsLazilyRatherThanBufferingTheDocument() throws IOException {
        // The property the streaming design exists for: a huge listing must not be read into
        // memory before the first entry is available.
        StringBuilder xml = new StringBuilder("<D:multistatus xmlns:D=\"DAV:\">\n");
        for (int i = 0; i < 20_000; i++) {
            xml.append("  <D:response><D:href>/ns/data/file")
                    .append(i)
                    .append("</D:href><D:propstat><D:prop><D:getcontentlength>")
                    .append(i)
                    .append("</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status>")
                    .append("</D:propstat></D:response>\n");
        }
        xml.append("</D:multistatus>\n");
        byte[] document = xml.toString().getBytes(StandardCharsets.UTF_8);

        AtomicLong bytesRead = new AtomicLong();
        InputStream counting =
                new FilterInputStream(new ByteArrayInputStream(document)) {
                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        int n = super.read(b, off, len);
                        if (n > 0) {
                            bytesRead.addAndGet(n);
                        }
                        return n;
                    }
                };

        AtomicBoolean closed = new AtomicBoolean();
        try (DavEntryReader reader = MultiStatusParser.open(counting, () -> closed.set(true))) {
            assertThat(reader.hasNext()).isTrue();
            DavEntry first = reader.next();
            assertThat(first.href()).isEqualTo("/ns/data/file0");
            // Only a buffer's worth has been pulled off the wire, not the whole 20k entries.
            assertThat(bytesRead.get()).isLessThan(document.length / 4);
        }
        assertThat(closed).isTrue();
    }

    @Test
    void closingTheStreamReleasesTheUnderlyingResponse() {
        AtomicBoolean closed = new AtomicBoolean();
        try (Stream<DavEntry> entries =
                MultiStatusParser.stream(stream(LISTING), () -> closed.set(true))) {
            assertThat(entries.limit(1).count()).isEqualTo(1);
        }
        assertThat(closed).isTrue();
    }

    @Test
    void streamsEveryEntry() {
        try (Stream<DavEntry> entries = MultiStatusParser.stream(stream(LISTING), () -> {})) {
            assertThat(entries.map(DavEntry::href)).containsExactly("/ns/data/", "/ns/data/file.txt");
        }
    }
}
