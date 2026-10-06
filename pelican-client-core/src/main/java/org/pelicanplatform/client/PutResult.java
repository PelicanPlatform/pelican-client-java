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

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/** The outcome of a successful upload. */
public final class PutResult {

    private final ObjectPath path;
    private final long bytesWritten;
    private final String etag;
    private final Map<DigestAlgorithm, String> digests;
    private final URI servedBy;
    private final String metadataStatus;

    public PutResult(
            ObjectPath path,
            long bytesWritten,
            String etag,
            Map<DigestAlgorithm, String> digests,
            URI servedBy,
            String metadataStatus) {
        this.path = path;
        this.bytesWritten = bytesWritten;
        this.etag = etag;
        this.digests = digests == null ? Collections.emptyMap() : Map.copyOf(digests);
        this.servedBy = servedBy;
        this.metadataStatus = metadataStatus;
    }

    public ObjectPath path() {
        return path;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public Optional<String> etag() {
        return Optional.ofNullable(etag);
    }

    /** Checksums the origin computed for the stored object, if it reported any. */
    public Map<DigestAlgorithm, String> digests() {
        return digests;
    }

    /** The origin that accepted the write. */
    public URI servedBy() {
        return servedBy;
    }

    /**
     * The origin's {@code X-Pelican-Metadata-Status}: the outcome of its first attempt to
     * publish this object to a configured metadata catalog. Absent unless the export has
     * metadata publishing enabled.
     */
    public Optional<String> metadataStatus() {
        return Optional.ofNullable(metadataStatus);
    }

    @Override
    public String toString() {
        return String.format("PutResult[%s %d bytes via %s]", path, bytesWritten, servedBy);
    }
}
