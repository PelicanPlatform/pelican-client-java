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

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * What happened when one object server was tried.
 *
 * <p>Pelican hands the client an ordered list of candidate servers, so a failed operation
 * has a failure <em>per server</em>.  Collapsing those into one message loses the
 * difference between "every cache is down" and "the token is missing a scope" -- so they
 * are kept, and {@link AllServersFailedException} prints them all.
 *
 * @param server the cache or origin that was tried
 * @param statusCode the HTTP status, or -1 if the request never got a response
 * @param message a short description of the failure
 * @param elapsed how long the attempt took
 * @param cause the underlying exception, if any
 */
public record AttemptFailure(
        URI server, int statusCode, String message, Duration elapsed, Throwable cause) {

    public static AttemptFailure of(URI server, int statusCode, String message, Duration elapsed) {
        return new AttemptFailure(server, statusCode, message, elapsed, null);
    }

    public Optional<Throwable> causeIfPresent() {
        return Optional.ofNullable(cause);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(server.toString()).append(": ");
        if (statusCode > 0) {
            sb.append("HTTP ").append(statusCode).append(' ');
        }
        sb.append(message);
        if (elapsed != null) {
            sb.append(" (").append(elapsed.toMillis()).append("ms)");
        }
        return sb.toString();
    }
}
