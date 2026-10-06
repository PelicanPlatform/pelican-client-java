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
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A parsed Pelican URL: which federation, which object, and any transfer hints.
 *
 * <p>A {@code pelican://} URL names a <em>federation</em>, not a server.  The host is a
 * discovery endpoint that has to be resolved to a Director before anything can be fetched,
 * so this type deliberately exposes a {@link #discoveryUri()} rather than anything that
 * looks like it could be connected to directly.
 *
 * <p>Handles the spellings Pelican accepts in the wild:
 *
 * <ul>
 *   <li>{@code pelican://example.org/ns/obj} -- federation in the host
 *   <li>{@code osdf:///ns/obj} -- the OSDF, whose discovery host is fixed
 *   <li>{@code osdf://ns/obj} -- the same, with the third slash forgotten
 *   <li>{@code stash:///ns/obj} -- the historical name for the same thing
 *   <li>{@code token+pelican://...} -- HTCondor prefixes a credential name onto the scheme
 *   <li>{@code /ns/obj} -- schemeless, only with an explicitly supplied federation
 * </ul>
 */
public final class PelicanUrl {

    public static final String SCHEME_PELICAN = "pelican";
    public static final String SCHEME_OSDF = "osdf";
    public static final String SCHEME_STASH = "stash";

    private static final Set<String> KNOWN_SCHEMES =
            Set.of(SCHEME_PELICAN, SCHEME_OSDF, SCHEME_STASH);

    /** Discovery host for {@code osdf://} and {@code stash://} URLs, which carry no host. */
    public static final String OSDF_DISCOVERY_HOST = "osg-htc.org";

    private final URI discoveryUri;
    private final ObjectPath path;
    private final TransferHints hints;
    private final String scheme;
    private final String credentialName;

    private PelicanUrl(
            URI discoveryUri,
            ObjectPath path,
            TransferHints hints,
            String scheme,
            String credentialName) {
        this.discoveryUri = discoveryUri;
        this.path = path;
        this.hints = hints;
        this.scheme = scheme;
        this.credentialName = credentialName;
    }

    public static PelicanUrl parse(String url) {
        return parse(url, null);
    }

    /**
     * Parse a Pelican URL, falling back to {@code defaultFederation} when the URL carries no
     * federation of its own.
     *
     * @param url a {@code pelican://}, {@code osdf://}, {@code stash://} or (with a default
     *     federation) schemeless path
     * @param defaultFederation discovery endpoint to use when the URL names none; may be null
     */
    public static PelicanUrl parse(String url, URI defaultFederation) {
        if (url == null || url.isBlank()) {
            throw new PelicanException(PelicanErrorCode.PARAMETER, "URL must not be empty");
        }
        String raw = url.strip();

        String rawScheme = schemeOf(raw);
        String credentialName = null;
        String scheme = rawScheme;
        if (rawScheme != null && rawScheme.contains("+")) {
            // HTCondor writes `<credential-name>+pelican://...` to say which credential to use.
            int last = rawScheme.lastIndexOf('+');
            credentialName = rawScheme.substring(0, last);
            scheme = rawScheme.substring(last + 1);
        }
        if (scheme != null) {
            scheme = scheme.toLowerCase(Locale.ROOT).replace('_', '-');
        }

        if (scheme == null) {
            if (defaultFederation == null) {
                throw new PelicanException(
                        PelicanErrorCode.PARAMETER,
                        "a URL without a scheme can only be used together with an explicit"
                                + " federation: " + url);
            }
            SplitPath sp = splitPathAndQuery(raw);
            return new PelicanUrl(
                    normalizeDiscovery(defaultFederation),
                    ObjectPath.of(sp.path().startsWith("/") ? sp.path() : "/" + sp.path()),
                    TransferHints.fromQuery(sp.query()),
                    SCHEME_PELICAN,
                    credentialName);
        }
        if (!KNOWN_SCHEMES.contains(scheme)) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER,
                    String.format(
                            "scheme '%s' not understood; must be one of '%s', '%s' or '%s'",
                            scheme, SCHEME_PELICAN, SCHEME_OSDF, SCHEME_STASH));
        }

        String remainder = raw.substring(raw.indexOf("://") + 3);
        SplitPath sp;
        URI discovery;
        if (scheme.equals(SCHEME_OSDF) || scheme.equals(SCHEME_STASH)) {
            // osdf:///ns/obj is the correct spelling; osdf://ns/obj is the common typo, and
            // Pelican treats the "host" as the first path segment rather than rejecting it.
            discovery = URI.create("https://" + OSDF_DISCOVERY_HOST);
            sp = splitPathAndQuery(remainder.startsWith("/") ? remainder : "/" + remainder);
        } else {
            int slash = remainder.indexOf('/');
            String authority = slash < 0 ? remainder : remainder.substring(0, slash);
            String rest = slash < 0 ? "/" : remainder.substring(slash);
            if (authority.isEmpty()) {
                if (defaultFederation == null) {
                    throw new PelicanException(
                            PelicanErrorCode.PARAMETER,
                            "pelican:// URL has no federation host and none was supplied: " + url);
                }
                discovery = normalizeDiscovery(defaultFederation);
            } else {
                discovery = toDiscoveryUri(authority, url);
            }
            sp = splitPathAndQuery(rest);
        }

        return new PelicanUrl(
                discovery,
                ObjectPath.of(sp.path()),
                TransferHints.fromQuery(sp.query()),
                scheme,
                credentialName);
    }

    /** Build a URL directly from a federation and an already-validated path. */
    public static PelicanUrl of(URI federation, ObjectPath path, TransferHints hints) {
        return new PelicanUrl(
                normalizeDiscovery(federation),
                path,
                hints == null ? TransferHints.none() : hints,
                SCHEME_PELICAN,
                null);
    }

    private static String schemeOf(String raw) {
        int idx = raw.indexOf("://");
        if (idx <= 0) {
            return null;
        }
        String candidate = raw.substring(0, idx);
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean ok =
                    Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return candidate;
    }

    private static URI toDiscoveryUri(String authority, String originalUrl) {
        try {
            return new URI("https", authority, null, null, null);
        } catch (URISyntaxException e) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER, "invalid federation host in " + originalUrl, e);
        }
    }

    /** Accept a bare host, or a full https URL, as a federation reference. */
    public static URI normalizeDiscovery(URI federation) {
        if (federation == null) {
            throw new PelicanException(PelicanErrorCode.PARAMETER, "federation must not be null");
        }
        if (federation.getScheme() == null) {
            return URI.create("https://" + federation.toString());
        }
        String scheme = federation.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https") || scheme.equals("http")) {
            // Strip any path: only the origin matters for discovery.
            try {
                return new URI(scheme, federation.getAuthority(), null, null, null);
            } catch (URISyntaxException e) {
                throw new PelicanException(
                        PelicanErrorCode.PARAMETER, "invalid federation URL: " + federation, e);
            }
        }
        if (KNOWN_SCHEMES.contains(scheme)) {
            return parse(federation.toString()).discoveryUri();
        }
        throw new PelicanException(
                PelicanErrorCode.PARAMETER,
                "federation must be an https URL or a Pelican URL, got: " + federation);
    }

    private record SplitPath(String path, Map<String, String> query) {}

    private static SplitPath splitPathAndQuery(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        String pathPart = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        Map<String, String> query = new LinkedHashMap<>();
        if (q >= 0) {
            for (String pair : pathAndQuery.substring(q + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                query.put(
                        URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        String decodedPath = URLDecoder.decode(pathPart, StandardCharsets.UTF_8);
        return new SplitPath(decodedPath.isEmpty() ? "/" : decodedPath, query);
    }

    /** Where to fetch {@code /.well-known/pelican-configuration}. */
    public URI discoveryUri() {
        return discoveryUri;
    }

    public ObjectPath path() {
        return path;
    }

    public TransferHints hints() {
        return hints;
    }

    /** The normalized scheme: {@code pelican}, {@code osdf} or {@code stash}. */
    public String scheme() {
        return scheme;
    }

    /** The credential name HTCondor prefixed onto the scheme, if any. */
    public Optional<String> credentialName() {
        return Optional.ofNullable(credentialName);
    }

    public PelicanUrl withPath(ObjectPath newPath) {
        return new PelicanUrl(discoveryUri, newPath, hints, scheme, credentialName);
    }

    @Override
    public String toString() {
        String query = hints.toQueryString();
        String base;
        if (scheme.equals(SCHEME_OSDF) || scheme.equals(SCHEME_STASH)) {
            base = scheme + "://" + path;
        } else {
            base = scheme + "://" + discoveryUri.getAuthority() + path;
        }
        return query.isEmpty() ? base : base + "?" + query;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PelicanUrl other
                && discoveryUri.equals(other.discoveryUri)
                && path.equals(other.path)
                && hints.equals(other.hints);
    }

    @Override
    public int hashCode() {
        return discoveryUri.hashCode() * 31 + path.hashCode();
    }
}
