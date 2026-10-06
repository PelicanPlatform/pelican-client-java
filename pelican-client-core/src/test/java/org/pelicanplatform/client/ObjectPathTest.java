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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ObjectPathTest {

    @ParameterizedTest
    @CsvSource({
        "/a/b, /a/b",
        "/a//b, /a/b",
        "/a/./b, /a/b",
        "/a/b/, /a/b",
        "/a/b/c/../d, /a/b/d",
        "/, /",
        "///, /",
        "/a/../b, /b",
    })
    void normalizes(String input, String expected) {
        assertThat(ObjectPath.of(input).value()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative/path", "", "   "})
    void rejectsNonAbsolute(String input) {
        assertThatThrownBy(() -> ObjectPath.of(input)).isInstanceOf(PathValidationException.class);
    }

    @Test
    void rejectsClimbingAboveRoot() {
        assertThatThrownBy(() -> ObjectPath.of("/a/../.."))
                .isInstanceOf(PathValidationException.class)
                .hasMessageContaining("above the federation root");
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> ObjectPath.of("/a/b\u0000c"))
                .isInstanceOf(PathValidationException.class);
    }

    @Test
    void resolvesRelativeToBase() {
        ObjectPath base = ObjectPath.of("/ns/root");
        assertThat(ObjectPath.resolve(base, "data/file").value()).isEqualTo("/ns/root/data/file");
    }

    @Test
    void treatsALeadingSlashAsStillRelativeToTheBase() {
        // A system exposing a root directory addresses "/data/f" as "<root>/data/f", not as the
        // federation root. Any other reading would let a leading slash escape the configured root.
        ObjectPath base = ObjectPath.of("/ns/root");
        assertThat(ObjectPath.resolve(base, "/data/file").value()).isEqualTo("/ns/root/data/file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../sibling", "../../etc", "data/../../escape", "/../escape"})
    void refusesToResolveOutsideTheBase(String relative) {
        ObjectPath base = ObjectPath.of("/ns/root");
        assertThatThrownBy(() -> ObjectPath.resolve(base, relative))
                .isInstanceOf(PathValidationException.class)
                .hasMessageContaining("escapes its base");
    }

    @Test
    void resolvingToTheBaseItselfIsAllowed() {
        ObjectPath base = ObjectPath.of("/ns/root");
        assertThat(ObjectPath.resolve(base, "data/..")).isEqualTo(base);
        assertThat(ObjectPath.resolve(base, "")).isEqualTo(base);
        assertThat(ObjectPath.resolve(base, "/")).isEqualTo(base);
        assertThat(ObjectPath.resolve(base, null)).isEqualTo(base);
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", ".", "", "a/b", "/"})
    void rejectsBadChildNames(String name) {
        assertThatThrownBy(() -> ObjectPath.of("/ns").child(name))
                .isInstanceOf(PathValidationException.class);
    }

    @Test
    void childAppendsOneSegment() {
        assertThat(ObjectPath.of("/ns").child("file").value()).isEqualTo("/ns/file");
        assertThat(ObjectPath.root().child("ns").value()).isEqualTo("/ns");
    }

    @Test
    void parentAndBasename() {
        ObjectPath path = ObjectPath.of("/a/b/c");
        assertThat(path.basename()).isEqualTo("c");
        assertThat(path.parent().value()).isEqualTo("/a/b");
        assertThat(ObjectPath.of("/a").parent()).isEqualTo(ObjectPath.root());
        assertThat(ObjectPath.root().parent()).isEqualTo(ObjectPath.root());
        assertThat(ObjectPath.root().basename()).isEmpty();
    }

    @Test
    void startsWithIsSegmentwise() {
        // "/ns/rootie" must not count as being under "/ns/root", or a prefix check becomes a
        // string prefix check and the namespace next door becomes reachable.
        assertThat(ObjectPath.of("/ns/rootie").startsWith(ObjectPath.of("/ns/root"))).isFalse();
        assertThat(ObjectPath.of("/ns/root/x").startsWith(ObjectPath.of("/ns/root"))).isTrue();
        assertThat(ObjectPath.of("/ns/root").startsWith(ObjectPath.of("/ns/root"))).isTrue();
        assertThat(ObjectPath.of("/anything").startsWith(ObjectPath.root())).isTrue();
    }

    @Test
    void relativeToAndScopeResource() {
        ObjectPath base = ObjectPath.of("/ns");
        assertThat(ObjectPath.of("/ns/data/file").relativeTo(base)).isEqualTo("data/file");
        assertThat(ObjectPath.of("/ns").relativeTo(base)).isEmpty();
        assertThat(ObjectPath.of("/ns/data/file").asScopeResource(base)).isEqualTo("/data/file");
        assertThat(ObjectPath.of("/ns").asScopeResource(base)).isEqualTo("/");
        assertThat(ObjectPath.of("/ns/f").asScopeResource(ObjectPath.root())).isEqualTo("/ns/f");
    }

    @Test
    void relativeToRejectsPathsOutsideTheBase() {
        assertThatThrownBy(() -> ObjectPath.of("/other").relativeTo(ObjectPath.of("/ns")))
                .isInstanceOf(PathValidationException.class);
    }
}
