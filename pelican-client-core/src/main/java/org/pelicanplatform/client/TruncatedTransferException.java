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
 * The response body ended before the advertised {@code Content-Length}.
 *
 * <p>This is the check that stands in for Pelican's {@code X-Transfer-Status} trailer,
 * which the JDK HTTP client cannot read.  An origin that dies mid-object produces a short
 * body; without this check the caller would see a silently truncated file.
 */
public class TruncatedTransferException extends PelicanException {

    public TruncatedTransferException(ObjectPath path, long expected, long actual) {
        super(
                PelicanErrorCode.TRANSFER_STOPPED_TRANSFER,
                String.format(
                        "transfer of %s ended early: expected %d bytes, read %d", path, expected, actual));
    }
}
