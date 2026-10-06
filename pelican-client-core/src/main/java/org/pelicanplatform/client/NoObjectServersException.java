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
 * The Director answered, but named no cache or origin able to serve the request.
 *
 * <p>Distinct from {@link ObjectNotFoundException}: the object may well exist, but no
 * server that could serve it is currently advertised.  For a write this usually means no
 * origin in the namespace accepts writes.
 */
public class NoObjectServersException extends PelicanException {

    public NoObjectServersException(String message) {
        super(PelicanErrorCode.CONTACT, message);
    }
}
