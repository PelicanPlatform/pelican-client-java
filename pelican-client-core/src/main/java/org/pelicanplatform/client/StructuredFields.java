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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.Map;

/**
 * Serializes an RFC 9651 Structured Fields dictionary.
 *
 * <p>Used for {@code X-Pelican-Object-Metadata}.  Structured Fields rather than embedded
 * JSON because the types survive the trip: {@code run_number=4172} arrives at the origin's
 * metadata catalog as an integer, not as the string {@code "4172"}, and nothing downstream
 * has to guess which strings were meant to be numbers.
 */
final class StructuredFields {

    private StructuredFields() {}

    static String dictionary(Map<String, Object> values) {
        StringBuilder sb = new StringBuilder();
        values.forEach(
                (key, value) -> {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(key(key)).append('=').append(bareItem(key, value));
                });
        return sb.toString();
    }

    /** Dictionary keys are lowercase, and start with a letter or {@code *}. */
    private static String key(String key) {
        if (key == null || key.isEmpty()) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER, "a metadata key must not be empty");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean valid =
                    (c >= 'a' && c <= 'z')
                            || (c >= '0' && c <= '9' && i > 0)
                            || c == '_'
                            || c == '-'
                            || (c == '.' && i > 0)
                            || c == '*';
            if (!valid) {
                throw new PelicanException(
                        PelicanErrorCode.PARAMETER,
                        "metadata key '" + key + "' is not a valid Structured Fields key:"
                                + " keys are lowercase and made of a-z, 0-9, '_', '-', '.' and '*'");
            }
        }
        char first = key.charAt(0);
        if (!((first >= 'a' && first <= 'z') || first == '*')) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER,
                    "metadata key '" + key + "' must start with a lowercase letter or '*'");
        }
        return key;
    }

    private static String bareItem(String key, Object value) {
        if (value == null) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER, "metadata value for '" + key + "' must not be null");
        }
        if (value instanceof Boolean b) {
            return b ? "?1" : "?0";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            long l = ((Number) value).longValue();
            if (l > 999_999_999_999_999L || l < -999_999_999_999_999L) {
                throw new PelicanException(
                        PelicanErrorCode.PARAMETER,
                        "metadata value for '" + key + "' exceeds the Structured Fields integer range");
            }
            return Long.toString(l);
        }
        if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            BigDecimal decimal =
                    (value instanceof BigDecimal bd ? bd : BigDecimal.valueOf(((Number) value).doubleValue()))
                            .setScale(3, RoundingMode.HALF_EVEN);
            return decimal.toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return ":" + Base64.getEncoder().encodeToString(bytes) + ":";
        }
        return string(key, value.toString());
    }

    private static String string(String key, String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw new PelicanException(
                        PelicanErrorCode.PARAMETER,
                        "metadata value for '" + key + "' contains a character that Structured Fields"
                                + " strings cannot carry (only printable ASCII is allowed)");
            }
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}
