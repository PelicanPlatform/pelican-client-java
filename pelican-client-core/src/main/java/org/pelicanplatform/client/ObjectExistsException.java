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
 * The destination of a write already exists.
 *
 * <p>Many Pelican origins are write-once: a {@code PUT} over an existing object is refused
 * rather than silently replacing it. Whether a given origin behaves that way depends on its
 * storage backend -- an XRootD-fronted origin refuses, a native posixv2 origin replaces --
 * so a caller that needs one behavior or the other cannot assume either. Callers porting
 * code written against an overwrite-by-default object store will meet this where they did
 * not expect to; see {@code PutObjectRequest.Builder#overwrite(boolean)} for the
 * (non-atomic) opt-out.
 */
public class ObjectExistsException extends PelicanException {

    private final ObjectPath path;

    public ObjectExistsException(ObjectPath path) {
        super(PelicanErrorCode.SPECIFICATION_FILE_ALREADY_EXISTS, "object already exists: " + path);
        this.path = path;
    }

    public ObjectPath path() {
        return path;
    }
}
