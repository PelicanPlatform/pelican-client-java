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

/** A listing of a collection. */
public final class ListRequest extends ObjectRequest {

    private final boolean recursive;

    private ListRequest(Builder b) {
        super(b);
        this.recursive = b.recursive;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ListRequest of(String path) {
        return builder().path(path).build();
    }

    /**
     * Whether to descend into sub-collections.
     *
     * <p>Off by default: Pelican namespaces are real hierarchies, and a listing of one level
     * is what a caller browsing a tree wants.  A recursive listing walks lazily, opening one
     * {@code PROPFIND} per collection as the caller consumes it, so an abandoned walk stops
     * costing anything as soon as the stream is closed.
     */
    public boolean recursive() {
        return recursive;
    }

    public static final class Builder extends AbstractBuilder<Builder> {
        private boolean recursive;

        private Builder() {}

        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        public ListRequest build() {
            return new ListRequest(this);
        }
    }
}
