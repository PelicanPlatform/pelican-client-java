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

import java.util.Optional;

/**
 * Base class for every failure this client reports.
 *
 * <p>Unchecked, for two reasons: it matches the AWS SDK v2 idiom that callers bridging
 * this library into an object-store abstraction already know, and {@link
 * PelicanClient#list} hands back a {@link java.util.stream.Stream}, across whose lambdas
 * a checked exception cannot travel.
 *
 * <p>Every instance carries a {@link PelicanErrorCode}, so a caller can ask whether a
 * failure is worth retrying without pattern-matching on messages.
 */
public class PelicanException extends RuntimeException {

    private final PelicanErrorCode errorCode;

    public PelicanException(PelicanErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PelicanException(PelicanErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** The Pelican error code, shared by number and name with the Go client. */
    public PelicanErrorCode errorCode() {
        return errorCode;
    }

    /** Whether re-running the operation could plausibly succeed. */
    public boolean retryable() {
        return errorCode != null && errorCode.retryable();
    }

    public Optional<PelicanErrorCode> code() {
        return Optional.ofNullable(errorCode);
    }
}
