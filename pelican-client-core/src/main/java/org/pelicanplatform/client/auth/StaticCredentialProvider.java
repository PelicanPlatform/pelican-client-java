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

/** Hands out one token, whatever is asked for. */
public final class StaticCredentialProvider implements CredentialProvider {

    private final Credential credential;

    private StaticCredentialProvider(Credential credential) {
        this.credential = credential;
    }

    public static StaticCredentialProvider of(String bearerToken) {
        return new StaticCredentialProvider(Credential.fromJwt(bearerToken));
    }

    public static StaticCredentialProvider of(Credential credential) {
        return new StaticCredentialProvider(credential);
    }

    @Override
    public Optional<Credential> resolve(CredentialRequest request) {
        // A forced refresh means this exact token was just rejected. Offering it again would
        // make the client retry an identical request and report the same failure twice.
        if (request.forceRefresh()) {
            return Optional.empty();
        }
        return Optional.of(credential);
    }
}
