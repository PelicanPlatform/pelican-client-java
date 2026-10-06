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
import java.io.IOException;

/**
 * The HTTP engine this client runs on.
 *
 * <p>An interface rather than a direct dependency on {@code java.net.http} for one
 * concrete reason: the JDK client cannot read HTTP response trailers, and Pelican uses the
 * {@code X-Transfer-Status} trailer to report failures that happen after the response
 * headers have gone out.  Keeping the engine behind an interface means a deployment that
 * wants that signal can supply a transport that reads trailers, and an embedding
 * application can supply its own instrumented or proxy-aware client, without either
 * requiring a change here.
 *
 * <p>Implementations must <b>not</b> follow redirects.  The Director answers with a 307
 * whose headers carry the routing and authorization information this client needs; a
 * transport that transparently followed it would both discard those headers and send the
 * caller's credential to a server that had not been vetted.
 */
public interface HttpTransport extends Closeable {

    HttpResponseHandle execute(HttpRequestSpec spec) throws IOException;

    /** Whether {@link HttpResponseHandle#trailers()} can ever be non-empty. */
    boolean supportsTrailers();
}
