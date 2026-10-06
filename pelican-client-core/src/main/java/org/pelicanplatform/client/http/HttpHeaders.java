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

package org.pelicanplatform.client.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A case-insensitive view of HTTP headers that preserves repeats.
 *
 * <p>Both properties matter here: Pelican's {@code Link} and {@code Digest} headers can be
 * sent either as one comma-joined value or as several header lines, and a client that
 * reads only the first line silently ignores half the caches a Director offered.
 */
public final class HttpHeaders {

    private final Map<String, List<String>> values;

    private HttpHeaders(Map<String, List<String>> values) {
        this.values = values;
    }

    public static HttpHeaders of(Map<String, List<String>> raw) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        raw.forEach((k, v) -> map.computeIfAbsent(k, ignored -> new ArrayList<>()).addAll(v));
        map.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return new HttpHeaders(Collections.unmodifiableMap(map));
    }

    public static HttpHeaders empty() {
        return new HttpHeaders(Collections.emptyMap());
    }

    /** The first value for a header, or empty. */
    public Optional<String> first(String name) {
        List<String> list = values.get(name);
        return (list == null || list.isEmpty()) ? Optional.empty() : Optional.of(list.get(0));
    }

    public String firstOrNull(String name) {
        return first(name).orElse(null);
    }

    /** Every value sent for a header, in order. */
    public List<String> all(String name) {
        return values.getOrDefault(name, Collections.emptyList());
    }

    /**
     * Every comma-separated element across every line of a header.
     *
     * <p>Quoted strings and angle-bracketed URIs are respected, so a {@code Link} header
     * whose URI contains a comma does not get split in half.
     */
    public List<String> allElements(String name) {
        List<String> out = new ArrayList<>();
        for (String line : all(name)) {
            out.addAll(splitElements(line));
        }
        return out;
    }

    /** Split an HTTP list header on commas that are not inside {@code "..."} or {@code <...>}. */
    public static List<String> splitElements(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean inAngle = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !inAngle) {
                inQuotes = !inQuotes;
            } else if (c == '<' && !inQuotes) {
                inAngle = true;
            } else if (c == '>' && !inQuotes) {
                inAngle = false;
            }
            if (c == ',' && !inQuotes && !inAngle) {
                addIfPresent(out, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfPresent(out, current);
        return out;
    }

    private static void addIfPresent(List<String> out, StringBuilder sb) {
        String trimmed = sb.toString().strip();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
    }

    public Map<String, List<String>> asMap() {
        return values;
    }

    @Override
    public String toString() {
        return new LinkedHashMap<>(values).toString();
    }
}
