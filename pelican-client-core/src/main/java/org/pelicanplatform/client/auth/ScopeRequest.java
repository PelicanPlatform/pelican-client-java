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

import org.pelicanplatform.client.ObjectPath;

/**
 * One authorization an operation needs: an action on a path.
 *
 * <p>The reason this is a type rather than a formatted string is {@link #toWlcgScope}.  The
 * Director speaks federation-absolute paths ({@code /ns/data/file}) while a WLCG storage
 * scope is written relative to the token's base path ({@code storage.read:/data/file}).
 * Every place that subtraction is redone by hand is a place a token gets minted with a
 * scope that does not cover the object it was minted for, and the resulting 403 says
 * nothing about why.  It is done here, once.
 *
 * @param action what the operation needs to do
 * @param resource the federation-absolute path it needs to do it to
 */
public record ScopeRequest(StorageAction action, ObjectPath resource) {

    public static ScopeRequest of(StorageAction action, ObjectPath resource) {
        return new ScopeRequest(action, resource);
    }

    /**
     * Render as a WLCG scope string relative to a base path.
     *
     * @param basePath the token's base path, from {@code X-Pelican-Token-Generation}; null
     *     means the resource is already relative to the issuer's root
     * @param maxScopeDepth how many path components below the base path a scope may name; a
     *     deeper resource is truncated to an ancestor, because an issuer refuses a scope
     *     deeper than it advertises and a broader scope that is granted beats a precise one
     *     that is not. Zero or negative means no limit.
     */
    public String toWlcgScope(ObjectPath basePath, int maxScopeDepth) {
        String relative =
                basePath == null ? resource.value() : resource.asScopeResource(basePath);
        return action.wlcgName() + ":" + truncate(relative, maxScopeDepth);
    }

    public String toWlcgScope(ObjectPath basePath) {
        return toWlcgScope(basePath, 0);
    }

    private static String truncate(String relative, int maxDepth) {
        if (maxDepth <= 0 || relative.equals("/")) {
            return relative;
        }
        String[] segments = relative.split("/");
        // segments[0] is empty because the string is leading-slashed.
        int available = segments.length - 1;
        if (available <= maxDepth) {
            return relative;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= maxDepth; i++) {
            sb.append('/').append(segments[i]);
        }
        return sb.toString();
    }
}
