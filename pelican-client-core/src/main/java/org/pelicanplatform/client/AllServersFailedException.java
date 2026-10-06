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

import java.util.List;
import java.util.stream.Collectors;

/**
 * Every object server the Director named was tried and every one failed.
 *
 * <p>The per-server detail is the point.  "Connection refused" tells a user nothing about
 * a federation with four caches; "tried 3 servers: cache-a 502, cache-b connection
 * refused, origin rejected the token (missing storage.read:/data)" tells them which knob
 * to turn.
 */
public class AllServersFailedException extends PelicanException {

    private final transient List<AttemptFailure> failures;
    private final ObjectPath path;

    public AllServersFailedException(
            PelicanErrorCode code, ObjectPath path, String operation, List<AttemptFailure> failures) {
        super(code, buildMessage(path, operation, failures));
        this.failures = List.copyOf(failures);
        this.path = path;
    }

    private static String buildMessage(
            ObjectPath path, String operation, List<AttemptFailure> failures) {
        return String.format(
                "%s of %s failed against all %d object server%s: %s",
                operation,
                path,
                failures.size(),
                failures.size() == 1 ? "" : "s",
                failures.stream().map(AttemptFailure::toString).collect(Collectors.joining("; ")));
    }

    /** One entry per server tried, in the order they were tried. */
    public List<AttemptFailure> failures() {
        return failures;
    }

    public ObjectPath path() {
        return path;
    }
}
