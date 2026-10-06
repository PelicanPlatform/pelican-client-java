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

package org.pelicanplatform.client.internal;

/**
 * What to do about one server's refusal.
 *
 * <p>The distinction that matters is between failures that another server might not have
 * and failures that every server will repeat.  Retrying a 403 against four more caches
 * produces four more 403s, a slower error, and four more copies of the caller's token on
 * the wire; giving up on a 502 that one cache happened to emit throws away a federation's
 * whole point.
 */
public enum Disposition {
    /** The attempt worked. */
    SUCCESS,
    /** Another server might do better. Try the next one. */
    NEXT_SERVER,
    /** This server does not have it. Try the next, but remember it as a not-found. */
    NOT_FOUND,
    /** The credential was refused; every server will refuse it too. */
    TERMINAL_AUTH,
    /** The destination already exists, or a precondition failed. */
    TERMINAL_CONFLICT,
    /** The server does not implement the verb. */
    TERMINAL_UNSUPPORTED,
    /** Anything else that will not be improved by asking elsewhere. */
    TERMINAL;

    public boolean isTerminal() {
        return this == TERMINAL_AUTH
                || this == TERMINAL_CONFLICT
                || this == TERMINAL_UNSUPPORTED
                || this == TERMINAL;
    }

    /** The default classification of an HTTP status from an object server. */
    public static Disposition of(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return SUCCESS;
        }
        return switch (statusCode) {
            case 401, 403 -> TERMINAL_AUTH;
            case 404, 410 -> NOT_FOUND;
            case 405, 501 -> TERMINAL_UNSUPPORTED;
            case 409, 412 -> TERMINAL_CONFLICT;
            // A cache that is busy, restarting, or proxied by something that is: ask elsewhere.
            case 425, 429, 500, 502, 503, 504 -> NEXT_SERVER;
            default -> statusCode >= 500 ? NEXT_SERVER : TERMINAL;
        };
    }
}
