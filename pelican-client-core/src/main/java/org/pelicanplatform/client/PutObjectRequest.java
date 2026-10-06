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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.pelicanplatform.client.http.RequestBody;

/** An upload. */
public final class PutObjectRequest extends ObjectRequest {

    /**
     * Metadata keys the origin computes for itself and a client may not set.
     *
     * <p>Rejected here rather than silently dropped at the origin, so a caller who thought
     * they were setting {@code size} finds out.
     */
    private static final Set<String> RESERVED_METADATA_KEYS =
            Set.of("path", "size", "etag", "created_at");

    private final RequestBody body;
    private final String contentType;
    private final Map<String, Object> objectMetadata;
    private final List<DigestAlgorithm> wantDigests;
    private final boolean overwrite;

    private PutObjectRequest(Builder b) {
        super(b);
        if (b.body == null) {
            throw new PelicanException(PelicanErrorCode.PARAMETER, "an upload must have a body");
        }
        this.body = b.body;
        this.contentType = b.contentType;
        // LinkedHashMap, not Map.copyOf: the header must serialize the same way every time,
        // and Map.copyOf does not keep insertion order.
        this.objectMetadata =
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.objectMetadata));
        this.wantDigests = List.copyOf(b.wantDigests);
        this.overwrite = b.overwrite;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RequestBody body() {
        return body;
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /** Custom metadata for {@code X-Pelican-Object-Metadata}. */
    public Map<String, Object> objectMetadata() {
        return objectMetadata;
    }

    public List<DigestAlgorithm> wantDigests() {
        return wantDigests;
    }

    /**
     * Whether to replace an object that already exists.
     *
     * <p>Off by default, because many Pelican origins are write-once and refuse the
     * {@code PUT}. Whether yours does is a property of its storage backend, not of Pelican:
     * an XRootD-fronted origin refuses the second write, while a native posixv2 origin
     * replaces the object. Since a caller cannot tell from here, the safe default is to let
     * the refusal surface.
     *
     * <p>Turning this on makes the client delete the existing object first, which is <b>not
     * atomic</b>: a failure between the delete and the write leaves neither the old object
     * nor the new one. It exists for callers porting code from an overwrite-by-default
     * object store who have decided they can live with that; it is not a default and should
     * not become one.
     */
    public boolean overwrite() {
        return overwrite;
    }

    public static final class Builder extends AbstractBuilder<Builder> {
        private RequestBody body;
        private String contentType;
        private final Map<String, Object> objectMetadata = new LinkedHashMap<>();
        private List<DigestAlgorithm> wantDigests = List.of();
        private boolean overwrite;

        private Builder() {}

        public Builder body(RequestBody body) {
            this.body = body;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        /**
         * Attach one custom metadata field, forwarded to the origin's metadata catalog.
         *
         * <p>Values keep their type on the wire: a Long stays an integer, a Boolean stays a
         * boolean.  See {@code X-Pelican-Object-Metadata}.
         */
        public Builder metadata(String key, Object value) {
            if (RESERVED_METADATA_KEYS.contains(key)) {
                throw new PelicanException(
                        PelicanErrorCode.PARAMETER,
                        "object metadata key '" + key + "' is computed by the origin and cannot be set");
            }
            objectMetadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> values) {
            values.forEach(this::metadata);
            return this;
        }

        public Builder wantDigests(DigestAlgorithm... algorithms) {
            this.wantDigests = List.of(algorithms);
            return this;
        }

        public Builder overwrite(boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        public PutObjectRequest build() {
            return new PutObjectRequest(this);
        }
    }
}
