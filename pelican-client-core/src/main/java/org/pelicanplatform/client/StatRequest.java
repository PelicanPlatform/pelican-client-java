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

/** A metadata lookup for one object or collection. */
public final class StatRequest extends ObjectRequest {

    private final boolean forWrite;

    private StatRequest(Builder b) {
        super(b);
        this.forWrite = b.forWrite;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static StatRequest of(String path) {
        return builder().path(path).build();
    }

    /**
     * Whether this stat is pre-flighting a write.
     *
     * <p>Not cosmetic.  A write destination has to be resolved through the Director's write
     * path: a cache cannot answer for a namespace that grants writes but not reads, and a
     * read-flavored query would hand the caller's write credential to every cache in the
     * answer.  Checking whether an upload target exists is therefore a different question
     * from checking whether a download source does, and has to be asked differently.
     */
    public boolean forWrite() {
        return forWrite;
    }

    public static final class Builder extends AbstractBuilder<Builder> {
        private boolean forWrite;

        private Builder() {}

        public Builder forWrite(boolean forWrite) {
            this.forWrite = forWrite;
            return this;
        }

        public StatRequest build() {
            return new StatRequest(this);
        }
    }
}
