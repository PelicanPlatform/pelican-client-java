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
import java.time.Instant;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.pelicanplatform.client.PelicanErrorCode;
import org.pelicanplatform.client.PelicanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls {@code <D:response>} elements off a multi-status document one at a time.
 *
 * <p>The whole point is that the document is never held in memory.  A collection with a
 * million entries produces a multi-status body of hundreds of megabytes, and WebDAV offers
 * no way to ask for part of one; a client that buffered it would fall over on exactly the
 * directories where listing matters most.  Reading incrementally also means a caller that
 * wants the first fifty entries can close the stream after fifty and stop reading the
 * socket, instead of paying for the rest of the transfer.
 *
 * <p>Not thread-safe, and must be closed: closing releases the underlying HTTP response.
 */
public final class DavEntryReader implements Iterator<DavEntry>, Closeable {

    private static final Logger log = LoggerFactory.getLogger(DavEntryReader.class);

    private final XMLStreamReader reader;
    private final Closeable onClose;

    private DavEntry pending;
    private boolean exhausted;
    private boolean closed;

    DavEntryReader(XMLStreamReader reader, Closeable onClose) {
        this.reader = reader;
        this.onClose = onClose;
    }

    @Override
    public boolean hasNext() {
        advance();
        return pending != null;
    }

    @Override
    public DavEntry next() {
        advance();
        if (pending == null) {
            throw new NoSuchElementException();
        }
        DavEntry entry = pending;
        pending = null;
        return entry;
    }

    private void advance() {
        if (pending != null || exhausted || closed) {
            return;
        }
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && MultiStatusParser.isDav(reader, "response")) {
                    pending = readResponse();
                    return;
                }
            }
            exhausted = true;
        } catch (XMLStreamException e) {
            exhausted = true;
            throw new PelicanException(
                    PelicanErrorCode.SPECIFICATION,
                    "could not parse the WebDAV listing returned by the server",
                    e);
        }
    }

    private DavEntry readResponse() throws XMLStreamException {
        String href = null;
        long contentLength = -1;
        Instant lastModified = null;
        boolean collection = false;
        String etag = null;
        String contentType = null;
        String displayName = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT
                    && MultiStatusParser.isDav(reader, "response")) {
                break;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            if (MultiStatusParser.isDav(reader, "href") && href == null) {
                href = reader.getElementText().strip();
            } else if (MultiStatusParser.isDav(reader, "propstat")) {
                MultiStatusParser.Propstat propstat = MultiStatusParser.readPropstat(reader);
                if (!propstat.ok) {
                    continue;
                }
                if (propstat.contentLength >= 0) {
                    contentLength = propstat.contentLength;
                }
                if (propstat.lastModified != null) {
                    lastModified = propstat.lastModified;
                }
                collection |= propstat.collection;
                if (propstat.etag != null) {
                    etag = propstat.etag;
                }
                if (propstat.contentType != null) {
                    contentType = propstat.contentType;
                }
                if (propstat.displayName != null) {
                    displayName = propstat.displayName;
                }
            }
        }
        return new DavEntry(
                href, contentLength, lastModified, collection, etag, contentType, displayName);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            reader.close();
        } catch (XMLStreamException e) {
            log.trace("Ignoring failure closing the XML reader", e);
        } finally {
            if (onClose != null) {
                onClose.close();
            }
        }
    }
}
