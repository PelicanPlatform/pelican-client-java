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

package org.pelicanplatform.client.federation;

/**
 * Header names used on the wire between a Pelican client and the federation.
 *
 * <p>Collected here rather than spelled inline: a typo in a header name produces a silent
 * behavior change (an ignored routing hint, a credential that never gets sent) rather than
 * a compile error, and silent is the worst kind of wrong.
 */
public final class PelicanHeaders {

    private PelicanHeaders() {}

    // Sent by the client.
    public static final String TIMEOUT = "X-Pelican-Timeout";
    public static final String JOB_ID = "X-Pelican-JobId";
    public static final String DEBUG = "X-Pelican-Debug";
    public static final String COORDINATE = "X-Pelican-Coordinate";
    public static final String TRANSFER_STATUS = "X-Transfer-Status";
    public static final String OBJECT_METADATA = "X-Pelican-Object-Metadata";
    public static final String WANT_DIGEST = "Want-Digest";

    // Returned by the Director.
    public static final String NAMESPACE = "X-Pelican-Namespace";
    public static final String AUTHORIZATION = "X-Pelican-Authorization";
    public static final String TOKEN_GENERATION = "X-Pelican-Token-Generation";
    public static final String BROKER = "X-Pelican-Broker";

    // Returned by origins and caches.
    public static final String DIGEST = "Digest";
    public static final String METADATA_STATUS = "X-Pelican-Metadata-Status";

    // Third-party copy.
    public static final String TPC_SOURCE = "Source";
    public static final String TPC_TRANSFER_HEADER_PREFIX = "TransferHeader";
}
