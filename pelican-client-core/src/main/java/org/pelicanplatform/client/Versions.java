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
 * This library's identity on the wire.
 *
 * <p>The Director inspects the User-Agent to decide whether it supports a client's version
 * and to report a useful error when it does not, so sending something recognisable is worth
 * the few lines.
 */
final class Versions {

    private static final String NAME = "pelican-client-java";

    private Versions() {}

    static String version() {
        String implementation = Versions.class.getPackage().getImplementationVersion();
        return implementation == null ? "dev" : implementation;
    }

    static String userAgent(String callerSuffix) {
        String base = NAME + "/" + version();
        if (callerSuffix == null || callerSuffix.isBlank()) {
            return base;
        }
        return base + " " + callerSuffix.strip();
    }
}
