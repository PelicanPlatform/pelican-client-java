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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Per-URL transfer hints, carried as query parameters on a Pelican URL.
 *
 * <p>Pelican honors these on the URL itself so that a configured location string can
 * change transfer behavior without a code change -- a system configured as
 * {@code pelican://example.org/ns/data?directread} should behave the same here as it does
 * under the Go CLI.  Parsing them in the URL type rather than at each call site is what
 * makes that true.
 */
public record TransferHints(
        boolean recursive,
        Optional<PackFormat> pack,
        boolean directRead,
        boolean skipStat,
        boolean preferCached) {

    public static final String QUERY_RECURSIVE = "recursive";
    public static final String QUERY_PACK = "pack";
    public static final String QUERY_DIRECT_READ = "directread";
    public static final String QUERY_SKIP_STAT = "skipstat";
    public static final String QUERY_PREFER_CACHED = "prefercached";

    private static final TransferHints NONE =
            new TransferHints(false, Optional.empty(), false, false, false);

    public static TransferHints none() {
        return NONE;
    }

    /** Archive formats Pelican can pack a collection into on the fly. */
    public enum PackFormat {
        AUTO("auto"),
        TAR("tar"),
        TAR_GZ("tar.gz"),
        TAR_XZ("tar.xz"),
        ZIP("zip");

        private final String wireName;

        PackFormat(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<PackFormat> fromWireName(String name) {
            if (name == null) {
                return Optional.empty();
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (PackFormat f : values()) {
                if (f.wireName.equals(lower)) {
                    return Optional.of(f);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Read hints out of a parsed query string.
     *
     * <p>A valueless parameter ({@code ?recursive}) means true, matching the Go client;
     * an explicit {@code =false} means false.
     */
    public static TransferHints fromQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return NONE;
        }
        Optional<PackFormat> pack = Optional.empty();
        if (query.containsKey(QUERY_PACK)) {
            String raw = query.get(QUERY_PACK);
            pack =
                    (raw == null || raw.isEmpty())
                            ? Optional.of(PackFormat.AUTO)
                            : PackFormat.fromWireName(raw);
            if (pack.isEmpty()) {
                throw new PelicanException(
                        PelicanErrorCode.PARAMETER, "unknown pack format: " + raw);
            }
        }
        return new TransferHints(
                flag(query, QUERY_RECURSIVE),
                pack,
                flag(query, QUERY_DIRECT_READ),
                flag(query, QUERY_SKIP_STAT),
                flag(query, QUERY_PREFER_CACHED));
    }

    private static boolean flag(Map<String, String> query, String key) {
        if (!query.containsKey(key)) {
            return false;
        }
        String value = query.get(key);
        if (value == null || value.isEmpty()) {
            return true;
        }
        return !value.equalsIgnoreCase("false") && !value.equals("0");
    }

    /** Re-render as a query string (without the leading {@code ?}), or empty if no hints are set. */
    public String toQueryString() {
        StringBuilder sb = new StringBuilder();
        if (recursive) {
            append(sb, QUERY_RECURSIVE + "=true");
        }
        pack.ifPresent(p -> append(sb, QUERY_PACK + "=" + p.wireName()));
        if (directRead) {
            append(sb, QUERY_DIRECT_READ + "=true");
        }
        if (skipStat) {
            append(sb, QUERY_SKIP_STAT + "=true");
        }
        if (preferCached) {
            append(sb, QUERY_PREFER_CACHED + "=true");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String pair) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(pair);
    }
}
