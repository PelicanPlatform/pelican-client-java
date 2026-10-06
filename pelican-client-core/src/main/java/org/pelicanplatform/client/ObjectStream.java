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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The bytes of an object, plus what the server said about them.
 *
 * <p>Extends {@link InputStream} so it drops straight into a caller that just wants a
 * stream, while still carrying the metadata a caller that wants it can reach.
 *
 * <p>It also verifies as it reads.  Pelican reports a transfer that failed after the
 * response headers went out in an {@code X-Transfer-Status} trailer, which the default
 * (JDK) transport cannot see; without some other check, an origin that dies mid-object
 * would hand back a short file that looks complete.  So the byte count is compared against
 * {@code Content-Length} at end of stream and at {@link #close()}, and, when the caller
 * asked for it, a checksum is computed inline and compared with the server's.  A caller
 * that stops reading early and closes still gets told the stream was short.
 */
public final class ObjectStream extends InputStream {

    private final InputStream delegate;
    private final AutoCloseable onClose;
    private final ObjectPath path;
    private final long contentLength;
    private final String etag;
    private final Instant lastModified;
    private final String contentType;
    private final Map<DigestAlgorithm, String> digests;
    private final URI servedBy;
    private final boolean servedByCache;
    private final DigestAlgorithm.Digester digester;
    private final DigestAlgorithm verifyAlgorithm;
    private final boolean partial;

    private long bytesRead;
    private boolean endOfStream;
    private boolean verified;
    private boolean closed;

    ObjectStream(Builder b) {
        this.delegate = b.delegate;
        this.onClose = b.onClose;
        this.path = b.path;
        this.contentLength = b.contentLength;
        this.etag = b.etag;
        this.lastModified = b.lastModified;
        this.contentType = b.contentType;
        this.digests = b.digests == null ? Map.of() : Map.copyOf(b.digests);
        this.servedBy = b.servedBy;
        this.servedByCache = b.servedByCache;
        this.verifyAlgorithm = b.verifyAlgorithm;
        this.digester = b.verifyAlgorithm == null ? null : b.verifyAlgorithm.newDigester();
        this.partial = b.partial;
    }

    static Builder builder(InputStream delegate, ObjectPath path) {
        return new Builder(delegate, path);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = delegate.read(buffer, offset, length);
        if (n < 0) {
            endOfStream = true;
            verify();
            return -1;
        }
        bytesRead += n;
        if (digester != null) {
            digester.update(buffer, offset, n);
        }
        return n;
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            delegate.close();
        } finally {
            if (onClose != null) {
                try {
                    onClose.close();
                } catch (Exception e) {
                    if (e instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException(e);
                }
            }
        }
        // A caller that read everything and then closed must still hear about a mismatch;
        // one that deliberately stopped early is told the stream was short, which is true.
        verify();
    }

    private void verify() {
        if (verified) {
            return;
        }
        verified = true;
        if (contentLength >= 0 && bytesRead < contentLength) {
            throw new TruncatedTransferException(path, contentLength, bytesRead);
        }
        if (digester == null || !endOfStream) {
            return;
        }
        String expected = digests.get(verifyAlgorithm);
        if (expected == null) {
            throw new PelicanException(
                    PelicanErrorCode.TRANSFER_CHECKSUM_MISSING,
                    String.format(
                            "checksum verification was requested for %s but the server reported no %s"
                                    + " digest",
                            path, verifyAlgorithm.httpName()));
        }
        String actual = digester.encoded();
        if (!expected.equalsIgnoreCase(actual)) {
            throw new ChecksumMismatchException(verifyAlgorithm, expected, actual, path);
        }
    }

    public ObjectPath path() {
        return path;
    }

    /** The object's length in bytes, or -1 if the server did not say. */
    public long contentLength() {
        return contentLength;
    }

    /** How many bytes have been read so far. */
    public long bytesRead() {
        return bytesRead;
    }

    public Optional<String> etag() {
        return Optional.ofNullable(etag);
    }

    public Optional<Instant> lastModified() {
        return Optional.ofNullable(lastModified);
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /** Checksums the server reported for this object. */
    public Map<DigestAlgorithm, String> digests() {
        return digests;
    }

    /** Which cache or origin served these bytes. */
    public URI servedBy() {
        return servedBy;
    }

    /** True when the server that answered was a cache rather than the origin. */
    public boolean servedByCache() {
        return servedByCache;
    }

    /** True when this is a byte range rather than the whole object. */
    public boolean isPartial() {
        return partial;
    }

    static final class Builder {
        private final InputStream delegate;
        private final ObjectPath path;
        private AutoCloseable onClose;
        private long contentLength = -1;
        private String etag;
        private Instant lastModified;
        private String contentType;
        private Map<DigestAlgorithm, String> digests;
        private URI servedBy;
        private boolean servedByCache;
        private DigestAlgorithm verifyAlgorithm;
        private boolean partial;

        private Builder(InputStream delegate, ObjectPath path) {
            this.delegate = delegate;
            this.path = path;
        }

        Builder onClose(AutoCloseable onClose) {
            this.onClose = onClose;
            return this;
        }

        Builder contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        Builder etag(String etag) {
            this.etag = etag;
            return this;
        }

        Builder lastModified(Instant lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        Builder digests(Map<DigestAlgorithm, String> digests) {
            this.digests = digests;
            return this;
        }

        Builder servedBy(URI servedBy) {
            this.servedBy = servedBy;
            return this;
        }

        Builder servedByCache(boolean servedByCache) {
            this.servedByCache = servedByCache;
            return this;
        }

        Builder verify(DigestAlgorithm algorithm) {
            this.verifyAlgorithm = algorithm;
            return this;
        }

        Builder partial(boolean partial) {
            this.partial = partial;
            return this;
        }

        ObjectStream build() {
            return new ObjectStream(this);
        }
    }
}
