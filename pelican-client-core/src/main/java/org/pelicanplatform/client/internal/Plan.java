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

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.auth.ScopeRequest;
import org.pelicanplatform.client.federation.DirectorFlavor;
import org.pelicanplatform.client.federation.DirectorResponse;
import org.pelicanplatform.client.federation.ObjectServer;

/** One operation, described so the pipeline can run it against whichever servers exist. */
public final class Plan<T> {

    /** The work to do against a single server. */
    @FunctionalInterface
    public interface Action<T> {
        T run(ServerAttempt attempt) throws IOException;
    }

    /**
     * The work to do when the Director answered the request itself.
     *
     * <p>A Director new enough to proxy WebDAV answers a {@code PROPFIND} with a 207 rather
     * than a redirect, which is strictly better for listings: it cannot land on a cache that
     * refuses to list. Owns the passed result and must close it.
     */
    @FunctionalInterface
    public interface ProxyAction<T> {
        T run(org.pelicanplatform.client.federation.DirectorQueryResult result) throws IOException;
    }

    private final String name;
    private final ObjectPath path;
    private final DirectorFlavor flavor;
    private final String query;
    private final List<ScopeRequest> scopes;
    private final boolean replayable;
    private final Function<Integer, Disposition> classifier;
    private final Function<DirectorResponse, List<ObjectServer>> serverSelector;
    private final Action<T> action;
    private final String directorVerb;
    private final ProxyAction<T> proxyAction;
    private final Duration timeout;
    private final String jobId;

    private Plan(Builder<T> b) {
        this.name = b.name;
        this.path = b.path;
        this.flavor = b.flavor;
        this.query = b.query;
        this.scopes = List.copyOf(b.scopes);
        this.replayable = b.replayable;
        this.classifier = b.classifier;
        this.serverSelector = b.serverSelector;
        this.action = b.action;
        this.directorVerb = b.directorVerb;
        this.proxyAction = b.proxyAction;
        this.timeout = b.timeout;
        this.jobId = b.jobId;
    }

    public static <T> Builder<T> builder(String name, ObjectPath path, DirectorFlavor flavor) {
        return new Builder<>(name, path, flavor);
    }

    String name() {
        return name;
    }

    ObjectPath path() {
        return path;
    }

    DirectorFlavor flavor() {
        return flavor;
    }

    String query() {
        return query;
    }

    List<ScopeRequest> scopes() {
        return scopes;
    }

    /**
     * Whether a failed attempt may be repeated against another server.
     *
     * <p>False when the request body is a one-shot stream: by the time an attempt fails, part
     * of it may already have gone out, and starting again from a half-consumed stream writes
     * a truncated object that looks complete.
     */
    boolean replayable() {
        return replayable;
    }

    Disposition classify(int statusCode) {
        return classifier.apply(statusCode);
    }

    List<ObjectServer> selectServers(DirectorResponse response) {
        return serverSelector.apply(response);
    }

    Action<T> action() {
        return action;
    }

    /**
     * The verb to ask the Director about, when it differs from the flavor's.
     *
     * <p>Set for {@code PROPFIND}, which a Director may answer itself. Null means ask with
     * the flavor's own verb and expect a redirect.
     */
    String directorVerb() {
        return directorVerb;
    }

    ProxyAction<T> proxyAction() {
        return proxyAction;
    }

    Duration timeout() {
        return timeout;
    }

    String jobId() {
        return jobId;
    }

    public static final class Builder<T> {
        private final String name;
        private final ObjectPath path;
        private final DirectorFlavor flavor;
        private String query;
        private List<ScopeRequest> scopes = List.of();
        private boolean replayable = true;
        private Function<Integer, Disposition> classifier = Disposition::of;
        private Function<DirectorResponse, List<ObjectServer>> serverSelector =
                DirectorResponse::objectServers;
        private Action<T> action;
        private String directorVerb;
        private ProxyAction<T> proxyAction;
        private Duration timeout;
        private String jobId;

        private Builder(String name, ObjectPath path, DirectorFlavor flavor) {
            this.name = name;
            this.path = path;
            this.flavor = flavor;
        }

        public Builder<T> query(String query) {
            this.query = query;
            return this;
        }

        public Builder<T> scopes(List<ScopeRequest> scopes) {
            this.scopes = scopes;
            return this;
        }

        public Builder<T> replayable(boolean replayable) {
            this.replayable = replayable;
            return this;
        }

        public Builder<T> classifier(Function<Integer, Disposition> classifier) {
            this.classifier = classifier;
            return this;
        }

        /** Override which servers to try. A null selector leaves the default in place. */
        public Builder<T> servers(Function<DirectorResponse, List<ObjectServer>> serverSelector) {
            if (serverSelector != null) {
                this.serverSelector = serverSelector;
            }
            return this;
        }

        public Builder<T> action(Action<T> action) {
            this.action = action;
            return this;
        }

        /**
         * Ask the Director with this verb, and handle a proxied answer with {@code onProxied}.
         *
         * <p>A null verb leaves the flavor's own verb in place, which is what a fallback
         * attempt against a fixed endpoint wants.
         */
        public Builder<T> directorVerb(String directorVerb, ProxyAction<T> onProxied) {
            if (directorVerb != null) {
                this.directorVerb = directorVerb;
                this.proxyAction = onProxied;
            }
            return this;
        }

        public Builder<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder<T> jobId(String jobId) {
            this.jobId = jobId;
            return this;
        }

        public Plan<T> build() {
            return new Plan<>(this);
        }
    }
}
