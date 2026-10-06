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

import java.util.Optional;

/**
 * Supplies bearer tokens for namespaces that require them.
 *
 * <p>An interface rather than a token string because the interesting integrations are not
 * "here is my token": a service fetches a token per end user from its own secret store, a
 * batch job reads one from a file that is periodically refreshed underneath it, and a
 * long-running process has to renew one mid-transfer.  All of those are a function call,
 * and none of them can be expressed as a constructor argument.
 *
 * <p>Returning {@link Optional#empty()} means "I have nothing for this", not "access
 * denied" -- the client then tries the next provider, and finally reports that no
 * credential was available, naming the issuers the namespace would have accepted.
 *
 * <p>Implementations must be thread-safe.
 */
@FunctionalInterface
public interface CredentialProvider {

    Optional<Credential> resolve(CredentialRequest request);

    /** A provider that never supplies anything, for public namespaces. */
    static CredentialProvider none() {
        return request -> Optional.empty();
    }

    /** Try this provider, then another. */
    default CredentialProvider orElse(CredentialProvider fallback) {
        return request -> {
            Optional<Credential> mine = resolve(request);
            return mine.isPresent() ? mine : fallback.resolve(request);
        };
    }
}
