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

package org.pelicanplatform.client.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers tokens so a burst of transfers does not become a burst of token requests.
 *
 * <p>Keyed by issuer set plus requested scopes, because those are what make two tokens
 * interchangeable.  A token is dropped once it is within {@code refreshBefore} of expiring,
 * so a long transfer does not start with a token that expires halfway through.
 */
public final class CachingCredentialProvider implements CredentialProvider {

    private final CredentialProvider delegate;
    private final Duration refreshBefore;
    private final Map<String, Credential> cache = new ConcurrentHashMap<>();

    public CachingCredentialProvider(CredentialProvider delegate, Duration refreshBefore) {
        this.delegate = delegate;
        this.refreshBefore = refreshBefore;
    }

    public static CachingCredentialProvider wrapping(CredentialProvider delegate) {
        return new CachingCredentialProvider(delegate, Duration.ofMinutes(5));
    }

    @Override
    public Optional<Credential> resolve(CredentialRequest request) {
        String key = cacheKey(request);
        if (request.forceRefresh()) {
            cache.remove(key);
        } else {
            Credential cached = cache.get(key);
            if (cached != null && !cached.isExpired(refreshBefore)) {
                return Optional.of(cached);
            }
            if (cached != null) {
                cache.remove(key, cached);
            }
        }
        Optional<Credential> fresh = delegate.resolve(request);
        fresh.ifPresent(
                credential -> {
                    // A token with no expiry claim is not cached: nothing here can tell when it
                    // stops working, and holding it forever turns one rejection into every
                    // rejection.
                    if (credential.expiresAt().isPresent()) {
                        cache.put(key, credential);
                    }
                });
        return fresh;
    }

    private static String cacheKey(CredentialRequest request) {
        return request.federation()
                + "|"
                + request.acceptedIssuers()
                + "|"
                + String.join(" ", request.wlcgScopes());
    }

    /** Drop everything cached; for tests and for a caller that knows its tokens are stale. */
    public void clear() {
        cache.clear();
    }

    Optional<Instant> soonestExpiry() {
        return cache.values().stream().flatMap(c -> c.expiresAt().stream()).min(Instant::compareTo);
    }
}
