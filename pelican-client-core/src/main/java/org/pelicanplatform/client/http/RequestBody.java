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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The body of a write request.
 *
 * <p>{@link #isReplayable()} is the field that matters for correctness.  Pelican hands
 * back several candidate origins, and failing over to the next one after a partial write
 * is only safe if the body can be produced again from the start.  A caller that hands us a
 * bare {@link InputStream} has already consumed part of it by the time an attempt fails,
 * so that write must not be retried elsewhere -- the alternative is a silently truncated
 * object.  Making this a property of the body, rather than a flag someone remembers to
 * pass, is what keeps that decision from being made wrongly.
 */
public abstract class RequestBody {

    /** Content length in bytes, or -1 when unknown (the request will be chunked). */
    public abstract long contentLength();

    /** Whether {@link #open()} can be called more than once. */
    public abstract boolean isReplayable();

    /** Open a stream over the body. */
    public abstract InputStream open() throws IOException;

    public static RequestBody fromBytes(byte[] bytes) {
        byte[] copy = bytes.clone();
        return new RequestBody() {
            @Override
            public long contentLength() {
                return copy.length;
            }

            @Override
            public boolean isReplayable() {
                return true;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(copy);
            }
        };
    }

    public static RequestBody fromString(String s) {
        return fromBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static RequestBody fromFile(Path path) {
        return new RequestBody() {
            @Override
            public long contentLength() {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return -1;
                }
            }

            @Override
            public boolean isReplayable() {
                return true;
            }

            @Override
            public InputStream open() throws IOException {
                return Files.newInputStream(path);
            }
        };
    }

    /**
     * A body read from a stream the caller already holds.
     *
     * @param contentLength the length if known, or -1 to send chunked
     */
    public static RequestBody fromInputStream(InputStream stream, long contentLength) {
        AtomicBoolean consumed = new AtomicBoolean(false);
        return new RequestBody() {
            @Override
            public long contentLength() {
                return contentLength;
            }

            @Override
            public boolean isReplayable() {
                return false;
            }

            @Override
            public InputStream open() throws IOException {
                if (!consumed.compareAndSet(false, true)) {
                    throw new IOException(
                            "this request body is backed by a one-shot InputStream and has already"
                                    + " been consumed; it cannot be retried against another server");
                }
                return stream;
            }
        };
    }

    public static RequestBody empty() {
        return fromBytes(new byte[0]);
    }
}
