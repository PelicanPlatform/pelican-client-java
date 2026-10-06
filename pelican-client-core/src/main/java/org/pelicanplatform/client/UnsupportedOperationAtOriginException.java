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

/**
 * The object server refused the verb (HTTP 405 or 501).
 *
 * <p>Which WebDAV verbs work is a property of the origin's storage backend, discoverable
 * only at runtime: a Pelican V2 origin serves the full set, while an XRootD-backed origin
 * may serve neither {@code MOVE} nor {@code MKCOL}.  See {@link PelicanClient#capabilities}
 * to ask before trying.
 */
public class UnsupportedOperationAtOriginException extends PelicanException {

    private final String method;

    public UnsupportedOperationAtOriginException(String method, String message) {
        super(PelicanErrorCode.SPECIFICATION, message);
        this.method = method;
    }

    /** The HTTP method that was refused. */
    public String method() {
        return method;
    }
}
