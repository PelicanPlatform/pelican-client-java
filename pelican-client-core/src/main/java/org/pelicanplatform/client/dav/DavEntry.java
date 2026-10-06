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

import java.time.Instant;
import java.util.Optional;

/** One {@code <D:response>} from a WebDAV multi-status document. */
public final class DavEntry {

    private final String href;
    private final long contentLength;
    private final Instant lastModified;
    private final boolean collection;
    private final String etag;
    private final String contentType;
    private final String displayName;

    DavEntry(
            String href,
            long contentLength,
            Instant lastModified,
            boolean collection,
            String etag,
            String contentType,
            String displayName) {
        this.href = href;
        this.contentLength = contentLength;
        this.lastModified = lastModified;
        this.collection = collection;
        this.etag = etag;
        this.contentType = contentType;
        this.displayName = displayName;
    }

    /** The raw, still percent-encoded {@code <D:href>}. */
    public String href() {
        return href;
    }

    /** Size in bytes, or -1 when the server reported none (as for a collection). */
    public long contentLength() {
        return contentLength;
    }

    public Optional<Instant> lastModified() {
        return Optional.ofNullable(lastModified);
    }

    public boolean isCollection() {
        return collection;
    }

    public Optional<String> etag() {
        return Optional.ofNullable(etag);
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    public Optional<String> displayName() {
        return Optional.ofNullable(displayName);
    }

    @Override
    public String toString() {
        return "DavEntry[" + href + (collection ? " collection" : " size=" + contentLength) + "]";
    }
}
