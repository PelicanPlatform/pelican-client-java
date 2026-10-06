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

/**
 * The storage authorization an operation needs, in WLCG terms.
 *
 * <p>Named after the WLCG common JWT profile's {@code storage.*} scopes so that there is
 * one vocabulary between what an operation asks for and what a token carries.
 */
public enum StorageAction {
    /** Read an object, or list a collection. */
    READ("storage.read"),
    /** Write an object that does not exist yet. */
    CREATE("storage.create"),
    /** Overwrite, delete, or rename. Implies {@link #CREATE} per the WLCG profile. */
    MODIFY("storage.modify"),
    /** Ask a cache to stage an object without reading it. */
    STAGE("storage.stage");

    private final String wlcgName;

    StorageAction(String wlcgName) {
        this.wlcgName = wlcgName;
    }

    public String wlcgName() {
        return wlcgName;
    }

    /** Whether holding this action satisfies a request for {@code other}. */
    public boolean satisfies(StorageAction other) {
        if (this == other) {
            return true;
        }
        // Per the WLCG profile, storage.modify implies storage.create.
        return this == MODIFY && other == CREATE;
    }
}
