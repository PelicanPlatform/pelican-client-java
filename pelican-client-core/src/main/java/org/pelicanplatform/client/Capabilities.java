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
import java.util.Set;
import java.util.TreeSet;

/**
 * Which HTTP verbs the object server serving a path actually supports.
 *
 * <p>Worth asking rather than assuming: a Pelican V2 origin serves the full WebDAV verb
 * set, while an XRootD-backed origin may serve neither {@code MOVE} nor {@code MKCOL}, and
 * {@code COPY} appears only where third-party copy is enabled.  A caller that needs to
 * degrade gracefully (offer a "rename" button, or proxy bytes instead of asking for a
 * server-side copy) can check here instead of catching a 405 after the fact.
 *
 * <p>This answers for <em>one path</em>, not for a server. Origins vary their {@code Allow}
 * by what the path is: the same origin advertises {@code GET, HEAD, PUT} on an object and
 * {@code DELETE, MOVE, PROPPATCH} on a collection. Ask about the path you mean to act on.
 */
public final class Capabilities {

    private final URI server;
    private final Set<String> allowedMethods;
    private final String davHeader;

    public Capabilities(URI server, Set<String> allowedMethods, String davHeader) {
        this.server = server;
        TreeSet<String> upper = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        upper.addAll(allowedMethods);
        this.allowedMethods = java.util.Collections.unmodifiableSet(upper);
        this.davHeader = davHeader;
    }

    public URI server() {
        return server;
    }

    public Set<String> allowedMethods() {
        return allowedMethods;
    }

    public boolean supports(String method) {
        return allowedMethods.contains(method);
    }

    public boolean supportsListing() {
        return supports("PROPFIND");
    }

    public boolean supportsCreateCollection() {
        return supports("MKCOL");
    }

    public boolean supportsMove() {
        return supports("MOVE");
    }

    /** Whether this origin advertises WebDAV third-party copy. */
    public boolean supportsThirdPartyCopy() {
        return supports("COPY");
    }

    /** The raw {@code DAV:} header, if the server sent one. */
    public String davHeader() {
        return davHeader;
    }

    @Override
    public String toString() {
        return "Capabilities" + allowedMethods + " at " + server;
    }
}
