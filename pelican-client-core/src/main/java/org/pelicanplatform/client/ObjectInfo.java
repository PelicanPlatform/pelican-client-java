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
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Metadata about one object or collection. */
public final class ObjectInfo {

    private final ObjectPath path;
    private final long size;
    private final Instant lastModified;
    private final ObjectType type;
    private final String etag;
    private final String contentType;
    private final Map<DigestAlgorithm, String> digests;
    private final URI servedBy;

    private ObjectInfo(Builder b) {
        this.path = b.path;
        this.size = b.size;
        this.lastModified = b.lastModified;
        this.type = b.type;
        this.etag = b.etag;
        this.contentType = b.contentType;
        this.digests =
                b.digests.isEmpty()
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new EnumMap<>(b.digests));
        this.servedBy = b.servedBy;
    }

    public static Builder builder(ObjectPath path) {
        return new Builder(path);
    }

    public ObjectPath path() {
        return path;
    }

    /** The last path segment. */
    public String name() {
        return path.basename();
    }

    /** Size in bytes, or -1 if the server did not report one. */
    public long size() {
        return size;
    }

    public Optional<Instant> lastModified() {
        return Optional.ofNullable(lastModified);
    }

    public ObjectType type() {
        return type;
    }

    public boolean isCollection() {
        return type == ObjectType.COLLECTION;
    }

    public Optional<String> etag() {
        return Optional.ofNullable(etag);
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /** Checksums the server reported, keyed by algorithm, in the server's wire encoding. */
    public Map<DigestAlgorithm, String> digests() {
        return digests;
    }

    /** Which cache or origin answered for this object. */
    public Optional<URI> servedBy() {
        return Optional.ofNullable(servedBy);
    }

    @Override
    public String toString() {
        return String.format(
                "ObjectInfo[%s %s size=%d]", type == ObjectType.COLLECTION ? "coll" : "obj", path, size);
    }

    public static final class Builder {
        private final ObjectPath path;
        private long size = -1;
        private Instant lastModified;
        private ObjectType type = ObjectType.OBJECT;
        private String etag;
        private String contentType;
        private final Map<DigestAlgorithm, String> digests = new EnumMap<>(DigestAlgorithm.class);
        private URI servedBy;

        private Builder(ObjectPath path) {
            this.path = java.util.Objects.requireNonNull(path, "path");
        }

        public Builder size(long size) {
            this.size = size;
            return this;
        }

        public Builder lastModified(Instant lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public Builder type(ObjectType type) {
            this.type = type;
            return this;
        }

        public Builder etag(String etag) {
            this.etag = etag;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder digest(DigestAlgorithm algorithm, String value) {
            this.digests.put(algorithm, value);
            return this;
        }

        public Builder digests(Map<DigestAlgorithm, String> values) {
            this.digests.putAll(values);
            return this;
        }

        public Builder servedBy(URI servedBy) {
            this.servedBy = servedBy;
            return this;
        }

        public ObjectInfo build() {
            return new ObjectInfo(this);
        }
    }
}
