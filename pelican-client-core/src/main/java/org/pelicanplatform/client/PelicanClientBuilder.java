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

import java.net.URI;
import java.time.Duration;
import org.pelicanplatform.client.auth.CredentialProvider;
import org.pelicanplatform.client.federation.Director;
import org.pelicanplatform.client.federation.DirectorResponseCache;
import org.pelicanplatform.client.federation.FederationDiscovery;
import org.pelicanplatform.client.http.HttpTransport;
import org.pelicanplatform.client.http.JdkHttpTransport;
import org.pelicanplatform.client.http.RetryPolicy;
import org.pelicanplatform.client.internal.ResolutionPipeline;

/** Builds a {@link PelicanClient}. */
public final class PelicanClientBuilder {

    private URI federation;
    private ObjectPath basePath = ObjectPath.root();
    private CredentialProvider credentials;
    private HttpTransport transport;
    private boolean ownsTransport = true;
    private RetryPolicy retryPolicy = RetryPolicy.defaults();
    private Duration timeout = Duration.ofSeconds(30);
    private Duration directorCacheTtl = Duration.ofMinutes(5);
    private String userAgent;
    private boolean directorDebug;

    PelicanClientBuilder() {}

    /**
     * The federation to talk to: a discovery URL ({@code https://osg-htc.org}) or a Pelican
     * URL ({@code osdf:///}).
     */
    public PelicanClientBuilder federation(URI federation) {
        this.federation = PelicanUrl.normalizeDiscovery(federation);
        return this;
    }

    public PelicanClientBuilder federation(String federation) {
        return federation(URI.create(federation));
    }

    /**
     * Take the federation and base path from a Pelican URL.
     *
     * <p>Convenient for a system configured as one string:
     * {@code pelican://example.org/ns/root}.
     */
    public PelicanClientBuilder pelicanUrl(String url) {
        PelicanUrl parsed = PelicanUrl.parse(url);
        this.federation = parsed.discoveryUri();
        this.basePath = parsed.path();
        return this;
    }

    /**
     * The prefix relative paths resolve against.
     *
     * <p>Acts like a bucket: a client built with {@code /ns/project} resolves {@code data/f}
     * to {@code /ns/project/data/f} and refuses any relative path that would escape it.
     */
    public PelicanClientBuilder basePath(ObjectPath basePath) {
        this.basePath = basePath == null ? ObjectPath.root() : basePath;
        return this;
    }

    public PelicanClientBuilder basePath(String basePath) {
        return basePath(ObjectPath.of(basePath));
    }

    /** Where bearer tokens come from. Omit for a federation of public namespaces only. */
    public PelicanClientBuilder credentials(CredentialProvider credentials) {
        this.credentials = credentials;
        return this;
    }

    /**
     * Use a caller-supplied HTTP engine.
     *
     * <p>The client will not close a transport it did not create, so one HTTP engine can be
     * shared by several clients.
     */
    public PelicanClientBuilder httpTransport(HttpTransport transport) {
        this.transport = transport;
        this.ownsTransport = false;
        return this;
    }

    /** How many times to re-ask a Director that answered badly. */
    public PelicanClientBuilder retryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    /** How long to wait for any one server's response headers. */
    public PelicanClientBuilder timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * How long a Director answer may be reused for other objects in the same namespace.
     *
     * <p>{@link Duration#ZERO} disables the cache, at the cost of a Director round trip per
     * operation.
     */
    public PelicanClientBuilder directorCacheTtl(Duration ttl) {
        this.directorCacheTtl = ttl;
        return this;
    }

    /** Appended to this library's own User-Agent, so federation operators can see who is calling. */
    public PelicanClientBuilder userAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    /** Ask the Director to explain its routing decisions in its response. */
    public PelicanClientBuilder directorDebug(boolean directorDebug) {
        this.directorDebug = directorDebug;
        return this;
    }

    public PelicanClient build() {
        if (federation == null) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER,
                    "a client needs a federation: call federation(...) or pelicanUrl(...)");
        }
        HttpTransport effectiveTransport = transport;
        boolean owns = ownsTransport;
        if (effectiveTransport == null) {
            effectiveTransport = JdkHttpTransport.defaults();
            owns = true;
        }
        String effectiveUserAgent = Versions.userAgent(userAgent);
        FederationDiscovery discovery =
                new FederationDiscovery(effectiveTransport, retryPolicy, timeout, effectiveUserAgent);
        Director director =
                new Director(effectiveTransport, retryPolicy, timeout, effectiveUserAgent, directorDebug);
        ResolutionPipeline pipeline =
                new ResolutionPipeline(
                        federation,
                        discovery,
                        director,
                        new DirectorResponseCache(directorCacheTtl),
                        credentials,
                        effectiveTransport,
                        effectiveUserAgent,
                        timeout);
        return new DefaultPelicanClient(pipeline, basePath, owns ? effectiveTransport : null);
    }
}
