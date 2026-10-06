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

import java.time.Duration;
import java.util.Optional;

/**
 * Fields every request shares.
 *
 * <p>A request names its target either relative to the client's base path or as an already
 * validated {@link ObjectPath}; both end up going through {@link ObjectPath#resolve}, so a
 * relative path can never address anything outside the base the client was built with.
 */
public abstract class ObjectRequest {

    private final String relativePath;
    private final ObjectPath absolutePath;
    private final Duration timeout;
    private final String jobId;

    protected ObjectRequest(AbstractBuilder<?> builder) {
        this.relativePath = builder.relativePath;
        this.absolutePath = builder.absolutePath;
        this.timeout = builder.timeout;
        this.jobId = builder.jobId;
        if (relativePath == null && absolutePath == null) {
            throw new PelicanException(PelicanErrorCode.PARAMETER, "a request must name a path");
        }
    }

    /** Resolve this request's target against a client's base path. */
    public ObjectPath resolvePath(ObjectPath basePath) {
        if (absolutePath != null) {
            if (basePath != null && !absolutePath.startsWith(basePath)) {
                throw new PathValidationException(
                        absolutePath + " is outside this client's base path " + basePath);
            }
            return absolutePath;
        }
        return ObjectPath.resolve(basePath == null ? ObjectPath.root() : basePath, relativePath);
    }

    /** How long to wait for a server's response headers. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** A correlation id propagated to the federation's logs. */
    public Optional<String> jobId() {
        return Optional.ofNullable(jobId);
    }

    /** Self-typed builder base, so subclass builders keep their own type through setters. */
    @SuppressWarnings("unchecked")
    public abstract static class AbstractBuilder<B extends AbstractBuilder<B>> {
        private String relativePath;
        private ObjectPath absolutePath;
        private Duration timeout;
        private String jobId;

        /** A path relative to the client's base path. */
        public B path(String path) {
            this.relativePath = path;
            this.absolutePath = null;
            return (B) this;
        }

        /** An already validated federation-absolute path. */
        public B path(ObjectPath path) {
            this.absolutePath = path;
            this.relativePath = null;
            return (B) this;
        }

        public B timeout(Duration timeout) {
            this.timeout = timeout;
            return (B) this;
        }

        public B jobId(String jobId) {
            this.jobId = jobId;
            return (B) this;
        }
    }
}
