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

package org.pelicanplatform.client.federation;

/**
 * Which kind of access a Director query is asking about.
 *
 * <p>This is not cosmetic.  A read-flavored query returns caches; a write-flavored one
 * returns only origins that accept writes.  Asking with the wrong flavor either fails
 * outright -- a cache cannot answer for a namespace that grants writes but not reads -- or
 * fails dangerously, by handing a write credential to every cache in the answer.  So the
 * flavor is derived from the operation rather than chosen at the call site.
 */
public enum DirectorFlavor {
    /** Resolve for reading: caches first, then origins. Queried with {@code GET}. */
    READ("GET"),
    /** Resolve for writing: origins that accept writes. Queried with {@code PUT}. */
    WRITE("PUT");

    private final String httpMethod;

    DirectorFlavor(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    /** The HTTP method used to ask the Director. */
    public String httpMethod() {
        return httpMethod;
    }
}
