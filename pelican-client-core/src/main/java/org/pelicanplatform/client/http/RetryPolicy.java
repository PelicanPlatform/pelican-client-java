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

package org.pelicanplatform.client.http;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How often, and how patiently, to re-ask a service that answered badly.
 *
 * <p>Used for the Director, which may briefly be behind an ingress that answers 502 while
 * the Director itself restarts.  Object-server failures are handled by moving to the next
 * server rather than by retrying the same one, so they do not come through here.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofSeconds(3), Duration.ofSeconds(30));
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO);
    }

    /**
     * Backoff before attempt {@code attemptIndex} (0-based), with jitter to keep a fleet of
     * clients from re-converging on a recovering Director.
     */
    public Duration backoffBefore(int attemptIndex) {
        if (attemptIndex <= 0) {
            return Duration.ZERO;
        }
        long millis = initialBackoff.toMillis() * attemptIndex;
        long capped = Math.min(millis, maxBackoff.toMillis());
        if (capped <= 0) {
            return Duration.ZERO;
        }
        long jittered = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Duration.ofMillis(jittered);
    }
}
