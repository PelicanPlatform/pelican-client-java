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

package org.pelicanplatform.client.integrity;

import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.pelicanplatform.client.DigestAlgorithm;
import org.pelicanplatform.client.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes RFC 3230 {@code Digest} / {@code Want-Digest} headers.
 *
 * <p>Two real-world quirks are handled here rather than at the call sites, because both
 * produce a wrong answer rather than an error if missed:
 *
 * <ul>
 *   <li>XRootD base64-encodes {@code crc32c} where the IANA registry says hex
 *       (<a href="https://github.com/xrootd/xrootd/issues/2456">xrootd#2456</a>).  Both
 *       spellings are accepted; a 4-byte base64 value is recognisable by its trailing
 *       {@code ==}.
 *   <li>An origin that does not implement the algorithm asked for sometimes answers with a
 *       different one under the requested name.  A {@code crc32c} value longer than eight
 *       hex characters cannot be a 32-bit checksum, so it is dropped rather than compared
 *       against -- a comparison there would fail every time and blame the transfer.
 * </ul>
 */
public final class DigestHeader {

    private static final Logger log = LoggerFactory.getLogger(DigestHeader.class);

    private DigestHeader() {}

    /** The value for a {@code Want-Digest} request header. */
    public static String want(List<DigestAlgorithm> algorithms) {
        if (algorithms == null || algorithms.isEmpty()) {
            return DigestAlgorithm.DEFAULT.httpName();
        }
        return String.join(
                ",", algorithms.stream().map(DigestAlgorithm::httpName).toList());
    }

    /** Parse every {@code Digest} header line the server sent. */
    public static Map<DigestAlgorithm, String> parse(HttpHeaders headers) {
        Map<DigestAlgorithm, String> out = new EnumMap<>(DigestAlgorithm.class);
        for (String element : headers.allElements("Digest")) {
            int eq = element.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = element.substring(0, eq).strip();
            String value = element.substring(eq + 1).strip();
            DigestAlgorithm algorithm = DigestAlgorithm.fromHttpName(name).orElse(null);
            if (algorithm == null) {
                log.debug("Server reported a checksum in an algorithm this client does not know: {}", name);
                continue;
            }
            String normalized = normalize(algorithm, value);
            if (normalized != null) {
                out.put(algorithm, normalized);
            }
        }
        return out;
    }

    /** Bring a server-reported value into this client's canonical encoding, or drop it. */
    private static String normalize(DigestAlgorithm algorithm, String value) {
        if (algorithm != DigestAlgorithm.CRC32C && algorithm != DigestAlgorithm.CRC32) {
            return value;
        }
        if (value.length() == 8 && value.endsWith("==")) {
            // XRootD's base64-encoded CRC32C.
            try {
                byte[] decoded = Base64.getDecoder().decode(value);
                if (decoded.length == 4) {
                    return HexFormat.of().formatHex(decoded);
                }
            } catch (IllegalArgumentException e) {
                log.debug("A CRC value looked base64-encoded but would not decode: {}", value);
            }
        }
        if (value.length() > 8) {
            log.warn(
                    "Server reported {}={} ({} characters, expected at most 8 for a 32-bit"
                            + " checksum); the origin may not support {} and answered with a"
                            + " different algorithm. Ignoring this checksum.",
                    algorithm.httpName(),
                    value,
                    value.length(),
                    algorithm.httpName());
            return null;
        }
        // CRC values are written without leading zeros by some servers.
        return padHex(value, 8);
    }

    private static String padHex(String value, int width) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.length() >= width) {
            return lower;
        }
        return "0".repeat(width - lower.length()) + lower;
    }
}
