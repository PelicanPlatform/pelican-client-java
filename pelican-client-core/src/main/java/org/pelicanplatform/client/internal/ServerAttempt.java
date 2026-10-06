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

package org.pelicanplatform.client.internal;

import java.net.URI;
import java.util.Optional;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.auth.Credential;
import org.pelicanplatform.client.federation.DirectorResponse;
import org.pelicanplatform.client.federation.ObjectServer;
import org.pelicanplatform.client.http.HttpRequestSpec;
import org.pelicanplatform.client.http.HttpTransport;
import org.pelicanplatform.client.http.UriPaths;

/** Everything one attempt against one object server needs. */
public final class ServerAttempt {

    private final ObjectServer server;
    private final ObjectPath path;
    private final String query;
    private final Credential credential;
    private final HttpTransport transport;
    private final DirectorResponse director;
    private final String userAgent;
    private final String jobId;
    private final java.time.Duration timeout;

    ServerAttempt(
            ObjectServer server,
            ObjectPath path,
            String query,
            Credential credential,
            HttpTransport transport,
            DirectorResponse director,
            String userAgent,
            String jobId,
            java.time.Duration timeout) {
        this.server = server;
        this.path = path;
        this.query = query;
        this.credential = credential;
        this.transport = transport;
        this.director = director;
        this.userAgent = userAgent;
        this.jobId = jobId;
        this.timeout = timeout;
    }

    public ObjectServer server() {
        return server;
    }

    public ObjectPath path() {
        return path;
    }

    public HttpTransport transport() {
        return transport;
    }

    public DirectorResponse director() {
        return director;
    }

    public Optional<Credential> credential() {
        return Optional.ofNullable(credential);
    }

    /** The URL for this attempt's path on this attempt's server. */
    public URI uri() {
        return server.resolve(UriPaths.encode(path), query);
    }

    /** A URL for some other path on this attempt's server. */
    public URI uriFor(ObjectPath other) {
        return server.resolve(UriPaths.encode(other), null);
    }

    /** A request builder pre-loaded with the headers every object-server request carries. */
    public HttpRequestSpec.Builder request(String method) {
        return request(method, uri());
    }

    public HttpRequestSpec.Builder request(String method, URI uri) {
        HttpRequestSpec.Builder builder =
                HttpRequestSpec.builder(uri, method).header("User-Agent", userAgent);
        if (credential != null) {
            builder.header("Authorization", "Bearer " + credential.bearerToken());
        }
        if (jobId != null) {
            builder.header(
                    org.pelicanplatform.client.federation.PelicanHeaders.JOB_ID, jobId);
        }
        if (timeout != null) {
            builder.responseTimeout(timeout);
        }
        return builder;
    }
}
