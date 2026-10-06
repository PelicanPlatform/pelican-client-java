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

import java.util.List;
import java.util.Optional;

/** A read of an object, or of part of one. */
public final class GetObjectRequest extends ObjectRequest {

    private final ByteRange range;
    private final List<DigestAlgorithm> wantDigests;
    private final DigestAlgorithm verifyDigest;
    private final boolean directRead;
    private final boolean preferCached;

    private GetObjectRequest(Builder b) {
        super(b);
        this.range = b.range;
        this.wantDigests = List.copyOf(b.wantDigests);
        this.verifyDigest = b.verifyDigest;
        this.directRead = b.directRead;
        this.preferCached = b.preferCached;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static GetObjectRequest of(String path) {
        return builder().path(path).build();
    }

    public Optional<ByteRange> range() {
        return Optional.ofNullable(range);
    }

    /** Algorithms to ask the server for via {@code Want-Digest}. */
    public List<DigestAlgorithm> wantDigests() {
        return wantDigests;
    }

    /** The algorithm to verify the body against as it is read, if any. */
    public Optional<DigestAlgorithm> verifyDigest() {
        return Optional.ofNullable(verifyDigest);
    }

    /** Ask the Director to route past the caches, straight to an origin. */
    public boolean directRead() {
        return directRead;
    }

    /** Ask the Director to favour caches that already hold the object. */
    public boolean preferCached() {
        return preferCached;
    }

    public static final class Builder extends AbstractBuilder<Builder> {
        private ByteRange range;
        private List<DigestAlgorithm> wantDigests = List.of();
        private DigestAlgorithm verifyDigest;
        private boolean directRead;
        private boolean preferCached;

        private Builder() {}

        public Builder range(ByteRange range) {
            this.range = range;
            return this;
        }

        public Builder wantDigests(DigestAlgorithm... algorithms) {
            this.wantDigests = List.of(algorithms);
            return this;
        }

        /**
         * Verify the bytes as they are read.
         *
         * <p>Implies asking the server for that algorithm.  If the server reports no such
         * digest the read fails rather than silently going unverified -- a caller that asked
         * for verification and got none has not got what it asked for.
         */
        public Builder verifyDigest(DigestAlgorithm algorithm) {
            this.verifyDigest = algorithm;
            if (algorithm != null && !wantDigests.contains(algorithm)) {
                this.wantDigests =
                        java.util.stream.Stream.concat(wantDigests.stream(), java.util.stream.Stream.of(algorithm))
                                .toList();
            }
            return this;
        }

        public Builder directRead(boolean directRead) {
            this.directRead = directRead;
            return this;
        }

        public Builder preferCached(boolean preferCached) {
            this.preferCached = preferCached;
            return this;
        }

        public GetObjectRequest build() {
            return new GetObjectRequest(this);
        }
    }
}
