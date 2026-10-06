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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * A normalized, absolute path to an object or collection inside a federation.
 *
 * <p>Nothing in the public API takes a path as a bare {@code String}.  That is deliberate:
 * joining a caller-supplied relative path onto a configured prefix is the operation that
 * produces directory-traversal bugs, and doing it in one audited place is the only way to
 * be sure every call site is safe.  {@link #resolve} and {@link #child} normalize first and
 * then refuse any result that leaves the base, so a caller cannot construct an escape even
 * by trying.
 *
 * <p>Instances are canonical: always absolute, never with a trailing slash (except the
 * root), never containing {@code .}, {@code ..} or empty segments.  Whether a path names a
 * collection is a property of the object, not a spelling of the path, so
 * {@code /a/b} and {@code /a/b/} are the same {@code ObjectPath}.
 */
public final class ObjectPath implements Comparable<ObjectPath> {

    private static final ObjectPath ROOT = new ObjectPath("/");

    private final String value;

    private ObjectPath(String value) {
        this.value = value;
    }

    /** The federation-absolute root, {@code /}. */
    public static ObjectPath root() {
        return ROOT;
    }

    /**
     * Build a path from an absolute path string.
     *
     * @throws PathValidationException if the path is not absolute, is malformed, or uses
     *     {@code ..} to climb above the root
     */
    public static ObjectPath of(String absolutePath) {
        if (absolutePath == null) {
            throw new PathValidationException("path must not be null");
        }
        String trimmed = absolutePath.strip();
        if (trimmed.isEmpty()) {
            throw new PathValidationException("path must not be empty");
        }
        if (!trimmed.startsWith("/")) {
            throw new PathValidationException(
                    "path must be absolute (start with '/'): " + absolutePath
                            + " -- use ObjectPath.resolve(base, relative) for a relative path");
        }
        return new ObjectPath(normalize(trimmed));
    }

    /**
     * Resolve a caller-supplied path against a base prefix.
     *
     * <p>The relative path is always interpreted relative to {@code base}, whether or not it
     * begins with {@code /}: a client bound to {@code /ns/root} resolves both {@code data/f}
     * and {@code /data/f} to {@code /ns/root/data/f}.  This matches how systems that expose a
     * configured root directory are normally addressed, and removes the ambiguity that
     * otherwise lets a leading slash quietly mean "the whole federation".
     *
     * @throws PathValidationException if the result would fall outside {@code base}
     */
    public static ObjectPath resolve(ObjectPath base, String relative) {
        Objects.requireNonNull(base, "base");
        if (relative == null) {
            return base;
        }
        String trimmed = relative.strip();
        if (trimmed.isEmpty() || trimmed.equals("/") || trimmed.equals(".")) {
            return base;
        }
        String joined = base.isRoot() ? "/" + trimmed : base.value + "/" + trimmed;
        ObjectPath candidate = new ObjectPath(normalize(joined));
        if (!candidate.startsWith(base)) {
            throw new PathValidationException(
                    String.format(
                            "relative path %s escapes its base %s (would resolve to %s)",
                            relative, base, candidate));
        }
        return candidate;
    }

    /**
     * Append a single path segment.
     *
     * @throws PathValidationException if the name is empty, contains a slash, or is
     *     {@code .} or {@code ..}
     */
    public ObjectPath child(String name) {
        if (name == null || name.isEmpty()) {
            throw new PathValidationException("child name must not be empty");
        }
        if (name.indexOf('/') >= 0) {
            throw new PathValidationException(
                    "child name must be a single segment, got: " + name
                            + " -- use ObjectPath.resolve for a multi-segment path");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new PathValidationException("child name must not be '" + name + "'");
        }
        checkSegment(name);
        return new ObjectPath(isRoot() ? "/" + name : value + "/" + name);
    }

    /** The containing collection, or the root if this is already a top-level path. */
    public ObjectPath parent() {
        if (isRoot()) {
            return ROOT;
        }
        int slash = value.lastIndexOf('/');
        return slash == 0 ? ROOT : new ObjectPath(value.substring(0, slash));
    }

    /** The last path segment, or {@code ""} for the root. */
    public String basename() {
        return isRoot() ? "" : value.substring(value.lastIndexOf('/') + 1);
    }

    public boolean isRoot() {
        return value.equals("/");
    }

    /** True if this path is {@code prefix} or sits underneath it. */
    public boolean startsWith(ObjectPath prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isRoot()) {
            return true;
        }
        return value.equals(prefix.value) || value.startsWith(prefix.value + "/");
    }

    /**
     * This path expressed relative to {@code base}, without a leading slash.
     *
     * @throws PathValidationException if this path is not under {@code base}
     */
    public String relativeTo(ObjectPath base) {
        if (!startsWith(base)) {
            throw new PathValidationException(this + " is not under " + base);
        }
        if (value.equals(base.value)) {
            return "";
        }
        return value.substring(base.isRoot() ? 1 : base.value.length() + 1);
    }

    /**
     * This path as a scope resource relative to a token's base path: always leading-slashed,
     * as WLCG storage scopes are written.
     *
     * @throws PathValidationException if this path is not under {@code basePath}
     */
    public String asScopeResource(ObjectPath basePath) {
        String relative = relativeTo(basePath);
        return relative.isEmpty() ? "/" : "/" + relative;
    }

    /** The canonical string form: absolute, normalized, no trailing slash. */
    public String value() {
        return value;
    }

    private static String normalize(String raw) {
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (stack.isEmpty()) {
                    throw new PathValidationException(
                            "path climbs above the federation root: " + raw);
                }
                stack.removeLast();
                continue;
            }
            checkSegment(segment);
            stack.addLast(segment);
        }
        if (stack.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", stack);
    }

    private static void checkSegment(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '\0' || (c < 0x20 && c != '\t') || c == 0x7f) {
                throw new PathValidationException(
                        "path segment contains a control character: " + escape(segment));
            }
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\x%02x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public int compareTo(ObjectPath other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ObjectPath other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
