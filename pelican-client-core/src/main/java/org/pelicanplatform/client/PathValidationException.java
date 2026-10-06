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
 * A path could not be built: it was malformed, or it resolved outside the base it was
 * resolved against.
 *
 * <p>The second case is the reason this type exists.  A relative path containing {@code ..}
 * joined onto a base prefix can silently address the base's parent; the Go client hit
 * exactly this when an inferred source basename of {@code ..} caused an upload to land in
 * the collection above the one the user named.  {@link ObjectPath} refuses instead of
 * joining, so no caller has to remember to check.
 */
public class PathValidationException extends PelicanException {

    public PathValidationException(String message) {
        super(PelicanErrorCode.PARAMETER, message);
    }
}
