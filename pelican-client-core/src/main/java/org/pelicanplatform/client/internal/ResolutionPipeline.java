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
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.pelicanplatform.client.AccessDeniedException;
import org.pelicanplatform.client.AllServersFailedException;
import org.pelicanplatform.client.AttemptFailure;
import org.pelicanplatform.client.NoObjectServersException;
import org.pelicanplatform.client.ObjectNotFoundException;
import org.pelicanplatform.client.PelicanErrorCode;
import org.pelicanplatform.client.PelicanException;
import org.pelicanplatform.client.UnsupportedOperationAtOriginException;
import org.pelicanplatform.client.auth.Credential;
import org.pelicanplatform.client.auth.CredentialProvider;
import org.pelicanplatform.client.auth.CredentialRequest;
import org.pelicanplatform.client.federation.Director;
import org.pelicanplatform.client.federation.DirectorResponse;
import org.pelicanplatform.client.federation.DirectorResponseCache;
import org.pelicanplatform.client.federation.FederationDiscovery;
import org.pelicanplatform.client.federation.FederationInfo;
import org.pelicanplatform.client.federation.ObjectServer;
import org.pelicanplatform.client.http.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an operation: discover, resolve, authorize, then try servers until one works.
 *
 * <p>Every operation goes through here, which is the point.  The order of those four steps,
 * the decision about whether a credential is attached at all, the rule for when a failure
 * is worth taking elsewhere, and the accumulation of per-server failures into one legible
 * error are all things that have exactly one correct answer and many plausible wrong ones.
 * Written once, they are right for {@code get} and {@code list} and {@code mkcol} alike;
 * written per operation, they would be right for whichever one was written most recently.
 */
public final class ResolutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ResolutionPipeline.class);

    private final URI federation;
    private final FederationDiscovery discovery;
    private final Director director;
    private final DirectorResponseCache directorCache;
    private final CredentialProvider credentials;
    private final HttpTransport transport;
    private final String userAgent;
    private final Duration defaultTimeout;

    public ResolutionPipeline(
            URI federation,
            FederationDiscovery discovery,
            Director director,
            DirectorResponseCache directorCache,
            CredentialProvider credentials,
            HttpTransport transport,
            String userAgent,
            Duration defaultTimeout) {
        this.federation = federation;
        this.discovery = discovery;
        this.director = director;
        this.directorCache = directorCache;
        this.credentials = credentials;
        this.transport = transport;
        this.userAgent = userAgent;
        this.defaultTimeout = defaultTimeout;
    }

    public URI federation() {
        return federation;
    }

    public FederationInfo federationInfo() {
        return discovery.discover(federation);
    }

    public HttpTransport transport() {
        return transport;
    }

    public String userAgent() {
        return userAgent;
    }

    /** Resolve a path without running anything, for callers that need the Director's answer. */
    public DirectorResponse resolve(
            org.pelicanplatform.client.ObjectPath path,
            org.pelicanplatform.client.federation.DirectorFlavor flavor,
            String query) {
        return resolveDirector(federationInfo(), path, flavor, query, null);
    }

    public <T> T execute(Plan<T> plan) {
        FederationInfo fedInfo = federationInfo();
        DirectorResponse dirResp =
                resolveDirector(fedInfo, plan.path(), plan.flavor(), plan.query(), null);

        Credential credential = credentialFor(dirResp, plan, false);
        String token = credential == null ? null : credential.bearerToken();

        if (plan.directorVerb() != null) {
            // Ask the Director about the verb we mean to use; it may answer it outright.
            org.pelicanplatform.client.federation.DirectorQueryResult result = null;
            boolean handedOff = false;
            try {
                result =
                        director.query(
                                fedInfo, plan.path(), plan.directorVerb(), plan.query(), token, plan.jobId());
                if (result.isProxied() && plan.proxyAction() != null) {
                    // The action takes ownership of the open response -- a listing it turns into a
                    // lazy stream has to keep reading it long after this method returns, so the
                    // pipeline must not close it here.
                    handedOff = true;
                    return plan.proxyAction().run(result);
                }
                dirResp = result.response();
            } catch (IOException e) {
                throw new PelicanException(
                        PelicanErrorCode.CONTACT_DIRECTOR,
                        "failed while asking the Director for a " + plan.directorVerb() + " of "
                                + plan.path(),
                        e);
            } catch (RuntimeException e) {
                handedOff = false;
                throw e;
            } finally {
                if (result != null && !handedOff) {
                    try {
                        result.close();
                    } catch (IOException e) {
                        log.trace("Ignoring failure closing a Director response", e);
                    }
                }
            }
        } else if (credential != null && dirResp.requiresToken()) {
            // A namespace that requires a token but for which the Director answered
            // anonymously may well answer differently once it can see who is asking:
            // re-resolve with the credential in hand rather than routing a privileged
            // request as an anonymous one.
            dirResp = resolveDirector(fedInfo, plan.path(), plan.flavor(), plan.query(), token);
        }

        List<ObjectServer> servers = plan.selectServers(dirResp);
        if (servers.isEmpty()) {
            throw new NoObjectServersException(
                    String.format(
                            "the Director named no server able to serve a %s of %s%s",
                            plan.name(),
                            plan.path(),
                            plan.flavor() == org.pelicanplatform.client.federation.DirectorFlavor.WRITE
                                    ? " (no origin in this namespace accepts writes)"
                                    : ""));
        }

        List<AttemptFailure> failures = new ArrayList<>();
        boolean allNotFound = true;
        boolean credentialRefreshed = false;

        for (ObjectServer server : servers) {
            Instant started = Instant.now();
            try {
                return plan.action().run(attempt(server, plan, dirResp, credential));
            } catch (ObjectServerHttpException e) {
                Disposition disposition = plan.classify(e.statusCode());

                // One retry with a fresh credential, in case the one we had merely expired.
                if (disposition == Disposition.TERMINAL_AUTH
                        && !credentialRefreshed
                        && plan.replayable()
                        && credentials != null) {
                    credentialRefreshed = true;
                    Credential refreshed = credentialFor(dirResp, plan, true);
                    if (refreshed != null
                            && (credential == null
                                    || !refreshed.bearerToken().equals(credential.bearerToken()))) {
                        log.debug("Retrying {} against {} with a refreshed credential", plan.name(), server);
                        credential = refreshed;
                        try {
                            return plan.action().run(attempt(server, plan, dirResp, credential));
                        } catch (ObjectServerHttpException retryFailure) {
                            e = retryFailure;
                            disposition = plan.classify(retryFailure.statusCode());
                        } catch (IOException retryFailure) {
                            failures.add(
                                    new AttemptFailure(
                                            server.uri(),
                                            -1,
                                            retryFailure.getMessage(),
                                            Duration.between(started, Instant.now()),
                                            retryFailure));
                            continue;
                        }
                    }
                }

                failures.add(
                        AttemptFailure.of(
                                server.uri(),
                                e.statusCode(),
                                e.detail().isEmpty() ? httpReason(e.statusCode()) : e.detail(),
                                Duration.between(started, Instant.now())));

                if (disposition != Disposition.NOT_FOUND) {
                    allNotFound = false;
                }
                if (disposition.isTerminal()) {
                    throw terminal(disposition, plan, dirResp, credential, e, failures);
                }
            } catch (IOException e) {
                allNotFound = false;
                failures.add(
                        new AttemptFailure(
                                server.uri(), -1, describe(e), Duration.between(started, Instant.now()), e));
            }

            if (!plan.replayable()) {
                // The request body cannot be produced a second time, so there is nothing to
                // send to the next server. Say so, rather than failing later and obscurely.
                throw new PelicanException(
                        PelicanErrorCode.TRANSFER_STOPPED_TRANSFER,
                        String.format(
                                "%s of %s failed against %s and cannot be retried elsewhere: the request"
                                        + " body is a one-shot stream. Supply the body as a file or byte"
                                        + " array to allow failover. Attempts: %s",
                                plan.name(), plan.path(), server.uri(), failures));
            }
        }

        if (allNotFound && !failures.isEmpty()) {
            throw new ObjectNotFoundException(plan.path());
        }
        throw new AllServersFailedException(
                PelicanErrorCode.TRANSFER, plan.path(), plan.name(), failures);
    }

    private ServerAttempt attempt(
            ObjectServer server, Plan<?> plan, DirectorResponse dirResp, Credential credential) {
        return new ServerAttempt(
                server,
                plan.path(),
                plan.query(),
                credential,
                transport,
                dirResp,
                userAgent,
                plan.jobId(),
                plan.timeout() == null ? defaultTimeout : plan.timeout());
    }

    private RuntimeException terminal(
            Disposition disposition,
            Plan<?> plan,
            DirectorResponse dirResp,
            Credential credential,
            ObjectServerHttpException e,
            List<AttemptFailure> failures) {
        return switch (disposition) {
            case TERMINAL_AUTH ->
                    new AccessDeniedException(
                            plan.path(),
                            dirResp.acceptedIssuers(),
                            requiredScopes(dirResp, plan),
                            credential != null,
                            e.detail());
            case TERMINAL_CONFLICT ->
                    new org.pelicanplatform.client.ObjectExistsException(plan.path());
            case TERMINAL_UNSUPPORTED ->
                    new UnsupportedOperationAtOriginException(
                            plan.name(),
                            String.format(
                                    "the server at %s does not support what %s of %s requires (HTTP %d)."
                                            + " Not every Pelican origin serves the full WebDAV verb set;"
                                            + " ask capabilities() before relying on it.",
                                    e.server(), plan.name(), plan.path(), e.statusCode()));
            default ->
                    new AllServersFailedException(
                            PelicanErrorCode.TRANSFER, plan.path(), plan.name(), failures);
        };
    }

    private List<String> requiredScopes(DirectorResponse dirResp, Plan<?> plan) {
        org.pelicanplatform.client.ObjectPath basePath =
                dirResp.tokenGeneration()
                        .flatMap(org.pelicanplatform.client.federation.TokenGenerationHint::basePath)
                        .orElse(dirResp.namespace().namespace().orElse(null));
        int maxDepth = dirResp.tokenGeneration().map(h -> h.maxScopeDepth()).orElse(0);
        return plan.scopes().stream().map(s -> s.toWlcgScope(basePath, maxDepth)).toList();
    }

    private DirectorResponse resolveDirector(
            FederationInfo fedInfo,
            org.pelicanplatform.client.ObjectPath path,
            org.pelicanplatform.client.federation.DirectorFlavor flavor,
            String query,
            String bearerToken) {
        DirectorResponseCache.Flavor key =
                DirectorResponseCache.Flavor.of(federation, flavor, query, bearerToken);
        Optional<DirectorResponse> cached = directorCache.get(key, path);
        if (cached.isPresent()) {
            return cached.get();
        }
        DirectorResponse fresh = director.resolve(fedInfo, path, flavor, query, bearerToken);
        directorCache.put(key, fresh);
        return fresh;
    }

    private Credential credentialFor(DirectorResponse dirResp, Plan<?> plan, boolean forceRefresh) {
        boolean required = dirResp.requiresToken();

        // `require-token` describes whether READS need a credential. A namespace can be
        // publicly readable and still require one to write -- "PublicReads" plus "Writes" is
        // an ordinary configuration -- and the Director reports require-token=false for it
        // even when asked with PUT. So a write attaches whatever credential it can get hold
        // of regardless, which is safe because a write-flavored query resolves to origins
        // that accept the write rather than to every cache in the federation.
        boolean writeOperation = plan.flavor() == org.pelicanplatform.client.federation.DirectorFlavor.WRITE;
        if (!required && !writeOperation) {
            return null;
        }
        if (!required && credentials == null) {
            // Best effort: no provider, and nothing says a token is needed. Let the server
            // decide, and report what it says.
            return null;
        }
        if (credentials == null) {
            // Sending the request anyway would produce a 403 whose message says nothing about
            // the real problem, which is that this client was never given anywhere to get a
            // token from.
            throw new AccessDeniedException(
                    plan.path(),
                    dirResp.acceptedIssuers(),
                    requiredScopes(dirResp, plan),
                    false,
                    "this client was built without a credential provider, and "
                            + dirResp.namespace().namespace().map(Object::toString).orElse("this namespace")
                            + " requires a token");
        }
        CredentialRequest request =
                CredentialRequest.builder(federation, plan.path())
                        .acceptedIssuers(dirResp.acceptedIssuers())
                        .hint(dirResp.tokenGeneration().orElse(null))
                        .scopes(plan.scopes())
                        .forceRefresh(forceRefresh)
                        .build();
        Optional<Credential> credential = credentials.resolve(request);
        if (credential.isEmpty() && !forceRefresh && required) {
            throw new AccessDeniedException(
                    plan.path(),
                    dirResp.acceptedIssuers(),
                    requiredScopes(dirResp, plan),
                    false,
                    "no configured credential provider produced a token for this namespace");
        }
        return credential.orElse(null);
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String httpReason(int statusCode) {
        return switch (statusCode) {
            case 400 -> "bad request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not found";
            case 405 -> "method not allowed";
            case 409 -> "conflict";
            case 416 -> "range not satisfiable";
            case 429 -> "too many requests";
            case 500 -> "internal server error";
            case 502 -> "bad gateway";
            case 503 -> "service unavailable";
            case 504 -> "gateway timeout";
            default -> "unexpected status";
        };
    }
}
