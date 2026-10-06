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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * Checksum algorithms Pelican servers can report through {@code Want-Digest} / {@code Digest}.
 *
 * <p>The set is closed on purpose -- it is exactly what the Go client knows, so the two
 * implementations verify the same things.  Note the encodings differ per algorithm (RFC
 * 3230 says base64, the IANA registry says hex for the CRCs), which is why encoding lives
 * here and not at the call site.
 */
public enum DigestAlgorithm {
    /** The Pelican default. Hex-encoded per the IANA http-dig-alg registry. */
    CRC32C("crc32c", Encoding.HEX, 4),
    CRC32("crc32", Encoding.HEX, 4),
    MD5("md5", Encoding.BASE64, 16),
    /** SHA-1, spelled {@code sha} on the wire per RFC 3230. */
    SHA1("sha", Encoding.BASE64, 20);

    /** The algorithm a client asks for when it has no preference. */
    public static final DigestAlgorithm DEFAULT = CRC32C;

    private enum Encoding {
        HEX,
        BASE64
    }

    private final String httpName;
    private final Encoding encoding;
    private final int byteLength;

    DigestAlgorithm(String httpName, Encoding encoding, int byteLength) {
        this.httpName = httpName;
        this.encoding = encoding;
        this.byteLength = byteLength;
    }

    /** The token used in {@code Want-Digest} and {@code Digest} headers. */
    public String httpName() {
        return httpName;
    }

    public int byteLength() {
        return byteLength;
    }

    public static Optional<DigestAlgorithm> fromHttpName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String lower = name.strip().toLowerCase(Locale.ROOT);
        for (DigestAlgorithm a : values()) {
            if (a.httpName.equals(lower)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** Encode a raw digest value the way a server would report it for this algorithm. */
    public String encode(byte[] value) {
        return switch (encoding) {
            case HEX -> HexFormat.of().formatHex(value);
            case BASE64 -> Base64.getEncoder().encodeToString(value);
        };
    }

    /** A fresh accumulator for this algorithm. */
    public Digester newDigester() {
        return switch (this) {
            case CRC32C -> new ChecksumDigester(this, new CRC32C());
            case CRC32 -> new ChecksumDigester(this, new CRC32());
            case MD5 -> new MessageDigestDigester(this, "MD5");
            case SHA1 -> new MessageDigestDigester(this, "SHA-1");
        };
    }

    /** Accumulates bytes and produces the value in this algorithm's wire encoding. */
    public interface Digester {
        void update(byte[] buffer, int offset, int length);

        /** The digest in the encoding a server would use. Callable once the stream is complete. */
        String encoded();

        DigestAlgorithm algorithm();
    }

    private static final class ChecksumDigester implements Digester {
        private final DigestAlgorithm algorithm;
        private final Checksum checksum;

        ChecksumDigester(DigestAlgorithm algorithm, Checksum checksum) {
            this.algorithm = algorithm;
            this.checksum = checksum;
        }

        @Override
        public void update(byte[] buffer, int offset, int length) {
            checksum.update(buffer, offset, length);
        }

        @Override
        public String encoded() {
            long value = checksum.getValue();
            byte[] bytes = {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
            };
            return algorithm.encode(bytes);
        }

        @Override
        public DigestAlgorithm algorithm() {
            return algorithm;
        }
    }

    private static final class MessageDigestDigester implements Digester {
        private final DigestAlgorithm algorithm;
        private final MessageDigest digest;

        MessageDigestDigester(DigestAlgorithm algorithm, String jcaName) {
            this.algorithm = algorithm;
            try {
                this.digest = MessageDigest.getInstance(jcaName);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(jcaName + " is required by the JDK spec", e);
            }
        }

        @Override
        public void update(byte[] buffer, int offset, int length) {
            digest.update(buffer, offset, length);
        }

        @Override
        public String encoded() {
            return algorithm.encode(digest.digest());
        }

        @Override
        public DigestAlgorithm algorithm() {
            return algorithm;
        }
    }
}
