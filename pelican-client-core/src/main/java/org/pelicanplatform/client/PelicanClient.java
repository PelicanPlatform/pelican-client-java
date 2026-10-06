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

import java.net.URI;
import java.util.stream.Stream;
import org.pelicanplatform.client.federation.FederationInfo;

/**
 * A client for one Pelican federation.
 *
 * <p>Presents a federation as an object store.  Everything that makes Pelican a federation
 * rather than a server -- discovering the federation's endpoints, asking the Director which
 * caches or origins can serve a path, obtaining and scoping a token for the namespace,
 * trying servers in priority order and failing over -- happens underneath these calls.
 *
 * <p>A client is thread-safe and holds a connection pool and several caches, so it is worth
 * keeping one per (federation, credential identity) rather than making one per operation.
 * Close it when done.
 *
 * <p>All failures are {@link PelicanException}s, which are unchecked.
 */
public interface PelicanClient extends AutoCloseable {

    /** Begin building a client. */
    static PelicanClientBuilder builder() {
        return new PelicanClientBuilder();
    }

    /** The federation's discovery URI. */
    URI federation();

    /** The federation's resolved endpoints. Triggers discovery on first use. */
    FederationInfo federationInfo();

    /** The prefix relative paths are resolved against, or the root. */
    ObjectPath basePath();

    /**
     * Metadata for one object or collection.
     *
     * @throws ObjectNotFoundException if nothing exists at the path
     */
    ObjectInfo stat(StatRequest request);

    /**
     * List a collection.
     *
     * <p>Lazy: entries arrive as the server produces them and the underlying response stays
     * open until the stream is closed, so listing a collection with a million entries costs
     * a million entries' worth of neither memory nor patience.  <b>Close the stream</b>
     * (try-with-resources); a caller that takes the first fifty entries and closes stops
     * reading the socket there.
     *
     * @throws ObjectNotFoundException if the collection does not exist
     */
    Stream<ObjectInfo> list(ListRequest request);

    /**
     * Read an object.
     *
     * <p>The returned stream verifies length, and checksum if asked, as it is read. Close it.
     */
    ObjectStream get(GetObjectRequest request);

    /**
     * Write an object.
     *
     * <p>Many Pelican origins are write-once: uploading over an existing object throws
     * {@link ObjectExistsException} unless the request opts in to overwriting. Whether a
     * given origin refuses depends on its storage backend, so a caller that needs one
     * behavior should ask for it explicitly rather than rely on the default.
     */
    PutResult put(PutObjectRequest request);

    /** Delete an object, or an empty collection. */
    void delete(DeleteRequest request);

    /** Create one collection whose parent already exists. */
    void createCollection(CreateCollectionRequest request);

    /** Rename within the namespace. */
    void move(MoveRequest request);

    /**
     * Which verbs the server responsible for a path actually supports.
     *
     * <p>Worth asking before relying on {@code MKCOL}, {@code MOVE} or third-party copy:
     * what an origin serves depends on its storage backend, and the answer is only available
     * at runtime.
     */
    Capabilities capabilities(ObjectPath path);

    // Convenience overloads for the common shape.

    default ObjectInfo stat(String path) {
        return stat(StatRequest.of(path));
    }

    default Stream<ObjectInfo> list(String path) {
        return list(ListRequest.of(path));
    }

    default ObjectStream get(String path) {
        return get(GetObjectRequest.of(path));
    }

    default void delete(String path) {
        delete(DeleteRequest.of(path));
    }

    default void createCollection(String path) {
        createCollection(CreateCollectionRequest.of(path));
    }

    /** True if something exists at the path. */
    default boolean exists(String path) {
        try {
            stat(path);
            return true;
        } catch (ObjectNotFoundException e) {
            return false;
        }
    }

    @Override
    void close();
}
