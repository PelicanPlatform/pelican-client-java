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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.pelicanplatform.client.ObjectPath;

/**
 * Caches Director answers by namespace prefix.
 *
 * <p>A service transferring a directory full of objects would otherwise ask the Director
 * once per object for an answer that is the same every time, since the answer is really
 * about the namespace.  Entries are stored under the namespace prefix the Director
 * reported and found again by longest-prefix match.
 *
 * <p>The key includes more than the prefix, and each extra component prevents a specific
 * way of being wrong:
 *
 * <ul>
 *   <li><b>Flavor</b> -- a read's answer is caches and a write's is origins.  Handing a
 *       writer a reader's answer would address the write to servers that reject it and
 *       disclose the write credential to every cache in the list.
 *   <li><b>Query</b> -- {@code directread} and {@code prefercached} change which servers the
 *       Director picks, so an answer obtained under one does not answer for another.
 *   <li><b>Credential</b> -- a Director may answer a credentialed request differently, so an
 *       authenticated answer must not be served to an anonymous caller or to one holding a
 *       different token.  Stored as a short digest: cache keys end up in debug logs, and a
 *       bearer token must not.
 * </ul>
 */
public final class DirectorResponseCache {

    private final Duration ttl;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    public DirectorResponseCache(Duration ttl) {
        this.ttl = ttl;
    }

    /** A cache that never holds anything, for callers that want every query to be fresh. */
    public static DirectorResponseCache disabled() {
        return new DirectorResponseCache(Duration.ZERO);
    }

    /** The part of the key that is not the namespace prefix. */
    public record Flavor(URI federation, DirectorFlavor flavor, String query, String credentialTag) {

        public static Flavor of(
                URI federation, DirectorFlavor flavor, String query, String bearerToken) {
            return new Flavor(
                    federation,
                    flavor,
                    query == null ? "" : normalizeQuery(query),
                    fingerprint(bearerToken));
        }
    }

    private record Key(Flavor flavor, String prefix) {}

    private record Entry(DirectorResponse response, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /** Find a cached answer covering {@code path}, preferring the most specific one. */
    public Optional<DirectorResponse> get(Flavor flavor, ObjectPath path) {
        if (ttl.isZero() || ttl.isNegative()) {
            return Optional.empty();
        }
        String best = null;
        Entry bestEntry = null;
        for (Map.Entry<Key, Entry> e : entries.entrySet()) {
            Key key = e.getKey();
            if (!key.flavor().equals(flavor)) {
                continue;
            }
            if (!e.getValue().isFresh()) {
                entries.remove(key, e.getValue());
                continue;
            }
            if (!covers(key.prefix(), path)) {
                continue;
            }
            if (best == null || key.prefix().length() > best.length()) {
                best = key.prefix();
                bestEntry = e.getValue();
            }
        }
        return Optional.ofNullable(bestEntry).map(Entry::response);
    }

    /**
     * Remember an answer.
     *
     * <p>Only stored when the Director named the namespace it was answering for: without a
     * prefix there is no safe span of paths to reuse it over.
     */
    public void put(Flavor flavor, DirectorResponse response) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        Optional<ObjectPath> namespace = response.namespace().namespace();
        if (namespace.isEmpty()) {
            return;
        }
        entries.put(
                new Key(flavor, namespace.get().value()),
                new Entry(response, Instant.now().plus(ttl)));
    }

    public void clear() {
        entries.clear();
    }

    private static boolean covers(String prefix, ObjectPath path) {
        if (prefix.equals("/")) {
            return true;
        }
        String value = path.value();
        return value.equals(prefix) || value.startsWith(prefix + "/");
    }

    private static String normalizeQuery(String rawQuery) {
        String[] parts = rawQuery.split("&");
        java.util.Arrays.sort(parts);
        return String.join("&", parts);
    }

    /** Reduce a bearer token to a short tag that identifies it without disclosing it. */
    private static String fingerprint(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}
