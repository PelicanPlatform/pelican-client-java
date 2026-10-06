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

/**
 * A rename within one namespace.
 *
 * <p>Source and destination are both resolved against the client's base path, and both end
 * up on the same origin: WebDAV {@code MOVE} is a server-side operation, so a move across
 * namespaces is not a move but a copy and a delete.
 */
public final class MoveRequest extends ObjectRequest {

    private final String destinationRelative;
    private final ObjectPath destinationAbsolute;
    private final boolean overwrite;

    private MoveRequest(Builder b) {
        super(b);
        this.destinationRelative = b.destinationRelative;
        this.destinationAbsolute = b.destinationAbsolute;
        this.overwrite = b.overwrite;
        if (destinationRelative == null && destinationAbsolute == null) {
            throw new PelicanException(PelicanErrorCode.PARAMETER, "a move must name a destination");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public ObjectPath resolveDestination(ObjectPath basePath) {
        if (destinationAbsolute != null) {
            if (basePath != null && !destinationAbsolute.startsWith(basePath)) {
                throw new PathValidationException(
                        destinationAbsolute + " is outside this client's base path " + basePath);
            }
            return destinationAbsolute;
        }
        return ObjectPath.resolve(
                basePath == null ? ObjectPath.root() : basePath, destinationRelative);
    }

    /** Whether to replace an existing destination. Sent as the WebDAV {@code Overwrite} header. */
    public boolean overwrite() {
        return overwrite;
    }

    public static final class Builder extends AbstractBuilder<Builder> {
        private String destinationRelative;
        private ObjectPath destinationAbsolute;
        private boolean overwrite;

        private Builder() {}

        public Builder destination(String path) {
            this.destinationRelative = path;
            this.destinationAbsolute = null;
            return this;
        }

        public Builder destination(ObjectPath path) {
            this.destinationAbsolute = path;
            this.destinationRelative = null;
            return this;
        }

        public Builder overwrite(boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        public MoveRequest build() {
            return new MoveRequest(this);
        }
    }
}
