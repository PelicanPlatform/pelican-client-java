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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.pelicanplatform.client.PelicanErrorCode;
import org.pelicanplatform.client.PelicanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a WebDAV {@code 207 Multi-Status} listing incrementally.
 *
 * <p>Uses the JDK's own StAX parser, so listing costs no XML dependency, and pulls one
 * {@code <D:response>} at a time so that a large collection is never materialized -- see
 * {@link DavEntryReader}.
 *
 * <p>Properties are only taken from a {@code <propstat>} whose {@code <status>} is 2xx: a
 * server that reports {@code 404 Not Found} for {@code getcontentlength} on a collection is
 * being correct, and reading that block anyway would record a size of zero for every
 * directory.
 */
public final class MultiStatusParser {

    private static final Logger log = LoggerFactory.getLogger(MultiStatusParser.class);
    private static final String DAV_NS = "DAV:";

    private MultiStatusParser() {}

    /**
     * Open a lazy reader over a multi-status body.
     *
     * @param onClose released when the reader is closed; normally the HTTP response
     */
    public static DavEntryReader open(InputStream body, Closeable onClose) {
        try {
            return new DavEntryReader(newFactory().createXMLStreamReader(body), onClose);
        } catch (XMLStreamException e) {
            throw new PelicanException(
                    PelicanErrorCode.SPECIFICATION, "could not read the server's WebDAV listing", e);
        }
    }

    /**
     * A lazy stream over a multi-status body.
     *
     * <p>The caller must close the stream (try-with-resources): until then the HTTP response
     * it is reading from stays open.
     */
    public static Stream<DavEntry> stream(InputStream body, Closeable onClose) {
        DavEntryReader reader = open(body, onClose);
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(
                                reader, Spliterator.ORDERED | Spliterator.NONNULL),
                        false)
                .onClose(
                        () -> {
                            try {
                                reader.close();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
    }

    /**
     * Read a whole multi-status body into a list.
     *
     * <p>For small, known-bounded documents -- a {@code Depth: 0} stat, or a test.  Prefer
     * {@link #stream} for anything that could be a real directory.
     */
    public static List<DavEntry> parse(InputStream body) {
        List<DavEntry> entries = new ArrayList<>();
        try (DavEntryReader reader = open(body, null)) {
            while (reader.hasNext()) {
                entries.add(reader.next());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // A listing is untrusted input from whichever origin answered; no external entities,
        // no DTDs, no entity expansion.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        return factory;
    }

    static final class Propstat {
        boolean ok = true;
        long contentLength = -1;
        Instant lastModified;
        boolean collection;
        String etag;
        String contentType;
        String displayName;
    }

    static Propstat readPropstat(XMLStreamReader reader) throws XMLStreamException {
        Propstat out = new Propstat();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT && isDav(reader, "propstat")) {
                break;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            if (isDav(reader, "status")) {
                out.ok = isSuccess(reader.getElementText());
            } else if (isDav(reader, "getcontentlength")) {
                out.contentLength = parseLong(reader.getElementText());
            } else if (isDav(reader, "getlastmodified")) {
                out.lastModified = parseHttpDate(reader.getElementText());
            } else if (isDav(reader, "creationdate") && out.lastModified == null) {
                out.lastModified = parseIsoDate(reader.getElementText());
            } else if (isDav(reader, "getetag")) {
                out.etag = unquote(reader.getElementText().strip());
            } else if (isDav(reader, "getcontenttype")) {
                out.contentType = reader.getElementText().strip();
            } else if (isDav(reader, "displayname")) {
                out.displayName = reader.getElementText().strip();
            } else if (isDav(reader, "resourcetype")) {
                out.collection = readResourceType(reader);
            }
        }
        return out;
    }

    private static boolean readResourceType(XMLStreamReader reader) throws XMLStreamException {
        boolean collection = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT && isDav(reader, "resourcetype")) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT && isDav(reader, "collection")) {
                collection = true;
            }
        }
        return collection;
    }

    static boolean isDav(XMLStreamReader reader, String localName) {
        return localName.equals(reader.getLocalName())
                && (reader.getNamespaceURI() == null || DAV_NS.equals(reader.getNamespaceURI()));
    }

    private static boolean isSuccess(String statusLine) {
        // "HTTP/1.1 200 OK"
        for (String part : statusLine.strip().split("\\s+")) {
            try {
                int code = Integer.parseInt(part);
                return code >= 200 && code < 300;
            } catch (NumberFormatException ignored) {
                // keep looking for the numeric field
            }
        }
        return true;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Instant parseHttpDate(String text) {
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return parseIsoDate(value);
        }
    }

    private static Instant parseIsoDate(String text) {
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            log.debug("Could not parse a WebDAV timestamp: {}", value);
            return null;
        }
    }

    private static String unquote(String s) {
        String value = s;
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** The body of a {@code PROPFIND} asking for the properties this client uses. */
    public static final String PROPFIND_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getlastmodified/>
                <D:getetag/>
                <D:getcontenttype/>
                <D:displayname/>
              </D:prop>
            </D:propfind>
            """;
}
