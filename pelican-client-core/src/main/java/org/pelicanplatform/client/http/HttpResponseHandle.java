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

import java.io.Closeable;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

/**
 * A response whose body has not been read yet.
 *
 * <p>Streaming rather than buffered, because Pelican objects are routinely larger than
 * heap.  Closing the handle without draining the body must release the connection.
 */
public interface HttpResponseHandle extends Closeable {

    int statusCode();

    HttpHeaders headers();

    /** The body. Never null; may be empty. */
    InputStream body();

    /** The URI actually requested (useful after a caller-driven redirect). */
    URI uri();

    /**
     * Trailers, once the body has been fully read, if this transport can see them.
     *
     * <p>Pelican reports late-breaking transfer failures in an {@code X-Transfer-Status}
     * trailer.  A transport that cannot read trailers returns empty here, and the caller
     * falls back to length and checksum verification.
     */
    default Optional<HttpHeaders> trailers() {
        return Optional.empty();
    }
}
