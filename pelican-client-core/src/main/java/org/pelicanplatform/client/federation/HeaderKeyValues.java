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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.pelicanplatform.client.http.HttpHeaders;

/**
 * Parser for Pelican's {@code key=value, key=value} header convention.
 *
 * <p>Matches the Go implementation's quirks deliberately, because the two have to agree
 * about the same bytes: all whitespace inside a pair is stripped (so
 * {@code base-path=/foo bar} is not a thing Pelican can express), and a pair without an
 * {@code =} is skipped rather than treated as a valueless flag.
 *
 * <p>Unlike the Go version this keeps repeated keys, because
 * {@code X-Pelican-Authorization} legitimately repeats {@code issuer=}.
 */
final class HeaderKeyValues {

    private final Map<String, List<String>> pairs;

    private HeaderKeyValues(Map<String, List<String>> pairs) {
        this.pairs = pairs;
    }

    /** Parse every element of every line of a header. */
    static HeaderKeyValues parse(HttpHeaders headers, String headerName) {
        return parseElements(headers.allElements(headerName));
    }

    static HeaderKeyValues parse(String rawHeaderValue) {
        return parseElements(HttpHeaders.splitElements(rawHeaderValue));
    }

    private static HeaderKeyValues parseElements(List<String> elements) {
        Map<String, List<String>> pairs = new LinkedHashMap<>();
        for (String element : elements) {
            String compact = element.replaceAll("\\s", "");
            int eq = compact.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = compact.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = compact.substring(eq + 1);
            pairs.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return new HeaderKeyValues(pairs);
    }

    boolean isEmpty() {
        return pairs.isEmpty();
    }

    Optional<String> first(String key) {
        List<String> list = pairs.get(key.toLowerCase(Locale.ROOT));
        return (list == null || list.isEmpty()) ? Optional.empty() : Optional.of(list.get(0));
    }

    List<String> all(String key) {
        return pairs.getOrDefault(key.toLowerCase(Locale.ROOT), List.of());
    }

    boolean flag(String key) {
        return first(key).map(v -> v.equalsIgnoreCase("true") || v.equals("1")).orElse(false);
    }

    Optional<Integer> integer(String key) {
        return first(key)
                .flatMap(
                        v -> {
                            try {
                                return Optional.of(Integer.parseInt(v));
                            } catch (NumberFormatException e) {
                                return Optional.empty();
                            }
                        });
    }
}
