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

import java.net.URI;
import java.util.Optional;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.http.HttpHeaders;

/**
 * What the Director said about the namespace serving a path, from {@code X-Pelican-Namespace}.
 *
 * <p>{@link #requireToken()} is the field that decides whether a credential is attached at
 * all.  Sending an {@code Authorization} header to a public namespace is not harmless: it
 * hands the caller's bearer token to every cache that answers, including ones the caller
 * never chose.
 */
public final class NamespaceInfo {

    private final ObjectPath namespace;
    private final boolean requireToken;
    private final URI collectionsUrl;

    public NamespaceInfo(ObjectPath namespace, boolean requireToken, URI collectionsUrl) {
        this.namespace = namespace;
        this.requireToken = requireToken;
        this.collectionsUrl = collectionsUrl;
    }

    static NamespaceInfo parse(HttpHeaders headers) {
        HeaderKeyValues kv = HeaderKeyValues.parse(headers, PelicanHeaders.NAMESPACE);
        if (kv.isEmpty()) {
            return new NamespaceInfo(null, false, null);
        }
        ObjectPath ns = kv.first("namespace").map(ObjectPath::of).orElse(null);
        URI collections = kv.first("collections-url").map(URI::create).orElse(null);
        return new NamespaceInfo(ns, kv.flag("require-token"), collections);
    }

    /** The namespace prefix, e.g. {@code /ospool/ap40}. Absent if the Director did not say. */
    public Optional<ObjectPath> namespace() {
        return Optional.ofNullable(namespace);
    }

    /** Whether this namespace requires a bearer token. */
    public boolean requireToken() {
        return requireToken;
    }

    /**
     * An endpoint that serves directory listings for this namespace, if one is advertised.
     *
     * <p>Advertised only when both the namespace and the origin permit listings.  Caches do
     * not serve listings, so this is the endpoint a {@code PROPFIND} should fall back to
     * when a cache answers one with 409.
     */
    public Optional<URI> collectionsUrl() {
        return Optional.ofNullable(collectionsUrl);
    }

    @Override
    public String toString() {
        return String.format(
                "NamespaceInfo[%s requireToken=%s collections=%s]", namespace, requireToken, collectionsUrl);
    }
}
