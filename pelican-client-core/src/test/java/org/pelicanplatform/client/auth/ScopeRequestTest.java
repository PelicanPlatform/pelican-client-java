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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.pelicanplatform.client.ObjectPath;

class ScopeRequestTest {

    @Test
    void writesScopesRelativeToTheBasePath() {
        // The Director speaks federation-absolute paths; a WLCG scope is relative to the
        // token's base path. Getting this backwards mints a token that does not cover the
        // object it was minted for, and the 403 that follows says nothing about why.
        ScopeRequest scope =
                ScopeRequest.of(StorageAction.READ, ObjectPath.of("/ns/data/file.root"));
        assertThat(scope.toWlcgScope(ObjectPath.of("/ns")))
                .isEqualTo("storage.read:/data/file.root");
    }

    @Test
    void theBasePathItselfIsTheRootScope() {
        ScopeRequest scope = ScopeRequest.of(StorageAction.READ, ObjectPath.of("/ns"));
        assertThat(scope.toWlcgScope(ObjectPath.of("/ns"))).isEqualTo("storage.read:/");
    }

    @Test
    void withoutABasePathTheResourceIsAlreadyAbsolute() {
        ScopeRequest scope = ScopeRequest.of(StorageAction.CREATE, ObjectPath.of("/ns/f"));
        assertThat(scope.toWlcgScope(null)).isEqualTo("storage.create:/ns/f");
    }

    @Test
    void truncatesToTheAdvertisedMaxScopeDepth() {
        // An issuer refuses a scope deeper than it advertises, so a broader scope that will be
        // granted beats a precise one that will not.
        ScopeRequest scope = ScopeRequest.of(StorageAction.READ, ObjectPath.of("/ns/a/b/c/d"));
        assertThat(scope.toWlcgScope(ObjectPath.of("/ns"), 2)).isEqualTo("storage.read:/a/b");
        assertThat(scope.toWlcgScope(ObjectPath.of("/ns"), 4)).isEqualTo("storage.read:/a/b/c/d");
        assertThat(scope.toWlcgScope(ObjectPath.of("/ns"), 9)).isEqualTo("storage.read:/a/b/c/d");
        assertThat(scope.toWlcgScope(ObjectPath.of("/ns"), 0)).isEqualTo("storage.read:/a/b/c/d");
    }

    @Test
    void modifyImpliesCreate() {
        assertThat(StorageAction.MODIFY.satisfies(StorageAction.CREATE)).isTrue();
        assertThat(StorageAction.CREATE.satisfies(StorageAction.MODIFY)).isFalse();
        assertThat(StorageAction.READ.satisfies(StorageAction.READ)).isTrue();
        assertThat(StorageAction.READ.satisfies(StorageAction.CREATE)).isFalse();
    }
}
