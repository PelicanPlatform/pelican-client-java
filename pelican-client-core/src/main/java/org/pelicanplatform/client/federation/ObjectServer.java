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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.pelicanplatform.client.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One cache or origin the Director offered, with the priority it assigned.
 *
 * @param uri where to send the object request
 * @param priority the Director's {@code pri}; lower is better
 * @param rel the Metalink {@code rel}, normally {@code duplicate}
 * @param depth the Metalink {@code depth}, when present
 */
public record ObjectServer(URI uri, int priority, String rel, Integer depth) {

    private static final Logger log = LoggerFactory.getLogger(ObjectServer.class);

    /**
     * Read the ordered candidate list out of a Director response's {@code Link} headers.
     *
     * <p>The list is re-sorted by {@code pri} rather than trusted to arrive in order.  The
     * Director does send it sorted today; depending on that would make this client's
     * behavior a function of an implementation detail on the other side of the wire.
     */
    public static List<ObjectServer> parseLinkHeaders(HttpHeaders headers) {
        List<ObjectServer> servers = new ArrayList<>();
        for (String element : headers.allElements("Link")) {
            parseOne(element).ifPresent(servers::add);
        }
        servers.sort(Comparator.comparingInt(ObjectServer::priority));
        return servers;
    }

    private static Optional<ObjectServer> parseOne(String element) {
        URI uri = null;
        int priority = Integer.MAX_VALUE;
        String rel = null;
        Integer depth = null;

        for (String part : element.split(";")) {
            String token = part.strip();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("<") && token.endsWith(">")) {
                String raw = token.substring(1, token.length() - 1);
                try {
                    uri = URI.create(raw);
                } catch (IllegalArgumentException e) {
                    log.warn("Director offered an unparseable object server URL: {}", raw);
                    return Optional.empty();
                }
                continue;
            }
            int eq = token.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = token.substring(0, eq).strip().toLowerCase(java.util.Locale.ROOT);
            String value = unquote(token.substring(eq + 1).strip());
            switch (key) {
                case "pri" -> priority = parseIntOr(value, Integer.MAX_VALUE);
                case "rel" -> rel = value;
                case "depth" -> depth = parseIntOrNull(value);
                default -> {
                    // Unknown Link parameters are ignored, per RFC 8288.
                }
            }
        }
        if (uri == null) {
            return Optional.empty();
        }
        return Optional.of(new ObjectServer(uri, priority, rel, depth));
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A URL for {@code path} on this server. */
    public URI resolve(String encodedPath, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getAuthority());
        String base = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        sb.append(base);
        if (!encodedPath.startsWith("/")) {
            sb.append('/');
        }
        sb.append(encodedPath);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return URI.create(sb.toString());
    }

    @Override
    public String toString() {
        return uri + " (pri=" + (priority == Integer.MAX_VALUE ? "-" : priority) + ")";
    }
}
