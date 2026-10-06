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

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import org.pelicanplatform.client.ObjectPath;

/** Percent-encoding for object paths going into a URL. */
public final class UriPaths {

    private UriPaths() {}

    /**
     * Characters allowed unencoded in a path segment.
     *
     * <p>{@code URLEncoder} cannot be used here: it encodes for {@code application/x-www-
     * form-urlencoded}, where a space becomes {@code +}, which in a path means a literal
     * plus sign. Objects whose names contain spaces would end up at the wrong URL.
     */
    private static final BitSet UNRESERVED = new BitSet(128);

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            UNRESERVED.set(c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            UNRESERVED.set(c);
        }
        for (char c = '0'; c <= '9'; c++) {
            UNRESERVED.set(c);
        }
        for (char c : "-._~!$&'()*+,;=:@".toCharArray()) {
            UNRESERVED.set(c);
        }
    }

    /** Percent-encode an object path, leaving the separating slashes intact. */
    public static String encode(ObjectPath path) {
        return encode(path.value());
    }

    public static String encode(String path) {
        StringBuilder out = new StringBuilder(path.length() + 16);
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c == '/' || (c < 128 && UNRESERVED.get(c))) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }
}
