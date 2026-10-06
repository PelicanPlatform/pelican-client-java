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

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;
import org.pelicanplatform.client.http.HttpResponseHandle;

/**
 * What the Director answered.
 *
 * <p>Usually a 307 whose headers name the servers to talk to.  A Director new enough to
 * proxy WebDAV can instead answer a {@code PROPFIND} itself with a 207, which is strictly
 * better for listings -- it avoids having to discover that the cache picked for reading
 * refuses to list.
 *
 * <p>A proxied answer hands back the still-open response rather than its bytes, so a
 * listing of a large collection streams through the parser instead of being buffered.  That
 * makes this {@link Closeable}: close it in every case, including the redirect case where
 * closing does nothing.
 */
public final class DirectorQueryResult implements Closeable {

    private final int statusCode;
    private final DirectorResponse response;
    private final HttpResponseHandle proxied;

    DirectorQueryResult(int statusCode, DirectorResponse response, HttpResponseHandle proxied) {
        this.statusCode = statusCode;
        this.response = response;
        this.proxied = proxied;
    }

    public int statusCode() {
        return statusCode;
    }

    public DirectorResponse response() {
        return response;
    }

    /** True when the Director answered the request itself rather than redirecting. */
    public boolean isProxied() {
        return proxied != null;
    }

    /** The open proxied response, when {@link #isProxied()}. Owned by this object. */
    public Optional<HttpResponseHandle> proxiedResponse() {
        return Optional.ofNullable(proxied);
    }

    @Override
    public void close() throws IOException {
        if (proxied != null) {
            proxied.close();
        }
    }
}
