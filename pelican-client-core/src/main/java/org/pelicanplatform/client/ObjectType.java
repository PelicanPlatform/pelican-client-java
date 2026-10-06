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
 * What an entry in a namespace is.
 *
 * <p>Two values, not four.  Pelican's wire protocol distinguishes only objects from
 * collections; adding symlink or "other" here would invent a distinction the protocol
 * cannot report and that every caller would then have to unmap.
 */
public enum ObjectType {
    OBJECT,
    COLLECTION;

    public boolean isCollection() {
        return this == COLLECTION;
    }
}
