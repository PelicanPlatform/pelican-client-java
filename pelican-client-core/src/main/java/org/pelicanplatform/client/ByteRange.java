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
 * A half-open byte range: {@code count} bytes starting at {@code firstByte}.
 *
 * <p>Expressed as offset+count rather than first+last because that is how callers think
 * about it, and because the off-by-one between the two conventions (HTTP's {@code Range}
 * header is inclusive of the last byte) is a classic source of one-byte-short reads.
 * Doing the subtraction in exactly one place removes the opportunity.
 */
public record ByteRange(long firstByte, long count) {

    public ByteRange {
        if (firstByte < 0) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER, "range start must not be negative: " + firstByte);
        }
        if (count <= 0) {
            throw new PelicanException(
                    PelicanErrorCode.PARAMETER, "range count must be positive: " + count);
        }
    }

    public static ByteRange of(long firstByte, long count) {
        return new ByteRange(firstByte, count);
    }

    /** From {@code firstByte} to the end of the object. */
    public static ByteRange from(long firstByte) {
        return new ByteRange(firstByte, Long.MAX_VALUE - firstByte);
    }

    public boolean isOpenEnded() {
        return count == Long.MAX_VALUE - firstByte;
    }

    /** The value for an HTTP {@code Range} header, whose last byte is inclusive. */
    public String toHeaderValue() {
        if (isOpenEnded()) {
            return "bytes=" + firstByte + "-";
        }
        return "bytes=" + firstByte + "-" + (firstByte + count - 1);
    }
}
