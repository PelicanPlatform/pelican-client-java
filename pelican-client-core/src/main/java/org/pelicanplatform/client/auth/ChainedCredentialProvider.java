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

import java.util.List;
import java.util.Optional;

/** Asks each provider in turn and returns the first token offered. */
public final class ChainedCredentialProvider implements CredentialProvider {

    private final List<CredentialProvider> providers;

    private ChainedCredentialProvider(List<CredentialProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public static ChainedCredentialProvider of(CredentialProvider... providers) {
        return new ChainedCredentialProvider(List.of(providers));
    }

    public static ChainedCredentialProvider of(List<CredentialProvider> providers) {
        return new ChainedCredentialProvider(providers);
    }

    @Override
    public Optional<Credential> resolve(CredentialRequest request) {
        for (CredentialProvider provider : providers) {
            Optional<Credential> credential = provider.resolve(request);
            if (credential.isPresent()) {
                return credential;
            }
        }
        return Optional.empty();
    }
}
