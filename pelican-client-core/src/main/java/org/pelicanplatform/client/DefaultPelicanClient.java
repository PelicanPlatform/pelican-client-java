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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.pelicanplatform.client.auth.ScopeRequest;
import org.pelicanplatform.client.auth.StorageAction;
import org.pelicanplatform.client.dav.DavEntry;
import org.pelicanplatform.client.dav.MultiStatusParser;
import org.pelicanplatform.client.federation.DirectorFlavor;
import org.pelicanplatform.client.federation.FederationInfo;
import org.pelicanplatform.client.federation.ObjectServer;
import org.pelicanplatform.client.federation.PelicanHeaders;
import org.pelicanplatform.client.http.HttpHeaders;
import org.pelicanplatform.client.http.HttpRequestSpec;
import org.pelicanplatform.client.http.HttpResponseHandle;
import org.pelicanplatform.client.http.HttpTransport;
import org.pelicanplatform.client.http.RequestBody;
import org.pelicanplatform.client.integrity.DigestHeader;
import org.pelicanplatform.client.internal.Disposition;
import org.pelicanplatform.client.internal.ObjectServerHttpException;
import org.pelicanplatform.client.internal.Plan;
import org.pelicanplatform.client.internal.ResolutionPipeline;
import org.pelicanplatform.client.internal.ServerAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The stock {@link PelicanClient}. */
final class DefaultPelicanClient implements PelicanClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultPelicanClient.class);

    private static final int HTTP_MULTI_STATUS = 207;
    private static final int HTTP_CONFLICT = 409;

    private final ResolutionPipeline pipeline;
    private final ObjectPath basePath;
    private final HttpTransport ownedTransport;

    DefaultPelicanClient(
            ResolutionPipeline pipeline, ObjectPath basePath, HttpTransport ownedTransport) {
        this.pipeline = pipeline;
        this.basePath = basePath;
        this.ownedTransport = ownedTransport;
    }

    @Override
    public URI federation() {
        return pipeline.federation();
    }

    @Override
    public FederationInfo federationInfo() {
        return pipeline.federationInfo();
    }

    @Override
    public ObjectPath basePath() {
        return basePath;
    }

    // ---------------------------------------------------------------- stat

    @Override
    public ObjectInfo stat(StatRequest request) {
        ObjectPath path = request.resolvePath(basePath);
        DirectorFlavor flavor = request.forWrite() ? DirectorFlavor.WRITE : DirectorFlavor.READ;
        StorageAction action = request.forWrite() ? StorageAction.CREATE : StorageAction.READ;

        return withCollectionsFallback(
                path,
                collectionsOnly ->
                        Plan.<ObjectInfo>builder("stat", path, flavor)
                                .scopes(List.of(ScopeRequest.of(action, path)))
                                .classifier(DefaultPelicanClient::classifyPropfind)
                                .servers(collectionsOnly ? DefaultPelicanClient::collectionsServers : null)
                                .directorVerb(
                                        collectionsOnly ? null : "PROPFIND",
                                        result -> {
                                            try (HttpResponseHandle proxied =
                                                    result.proxiedResponse().orElseThrow()) {
                                                return firstEntry(proxied, path)
                                                        .orElseThrow(() -> new ObjectNotFoundException(path));
                                            }
                                        })
                                .timeout(request.timeout().orElse(null))
                                .jobId(request.jobId().orElse(null))
                                .action(
                                        attempt ->
                                                propfind(attempt, path, 0)
                                                        .orElseThrow(() -> new ObjectNotFoundException(path)))
                                .build());
    }

    // ---------------------------------------------------------------- list

    @Override
    public Stream<ObjectInfo> list(ListRequest request) {
        ObjectPath path = request.resolvePath(basePath);
        if (!request.recursive()) {
            return listOneLevel(path, request);
        }
        return walk(path, request);
    }

    private Stream<ObjectInfo> listOneLevel(ObjectPath path, ListRequest request) {
        return withCollectionsFallback(
                path,
                collectionsOnly ->
                        Plan.<Stream<ObjectInfo>>builder("list", path, DirectorFlavor.READ)
                                .scopes(List.of(ScopeRequest.of(StorageAction.READ, path)))
                                .classifier(DefaultPelicanClient::classifyPropfind)
                                .servers(collectionsOnly ? DefaultPelicanClient::collectionsServers : null)
                                .directorVerb(
                                        collectionsOnly ? null : "PROPFIND",
                                        result ->
                                                childStream(
                                                        result.proxiedResponse().orElseThrow(),
                                                        path,
                                                        directorSelfPath(result, path)))
                                .timeout(request.timeout().orElse(null))
                                .jobId(request.jobId().orElse(null))
                                .action(attempt -> propfindChildren(attempt, path))
                                .build());
    }

    /**
     * A lazy depth-first walk.
     *
     * <p>One {@code PROPFIND} per collection, issued only when the consumer reaches it, so an
     * abandoned walk stops costing as soon as the stream is closed and a deep tree never
     * exists in memory all at once.
     */
    private Stream<ObjectInfo> walk(ObjectPath root, ListRequest request) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(listOneLevel(root, request)));

        Iterator<ObjectInfo> walker =
                new Iterator<>() {
                    private ObjectInfo pending;

                    @Override
                    public boolean hasNext() {
                        advance();
                        return pending != null;
                    }

                    @Override
                    public ObjectInfo next() {
                        advance();
                        if (pending == null) {
                            throw new NoSuchElementException();
                        }
                        ObjectInfo out = pending;
                        pending = null;
                        return out;
                    }

                    private void advance() {
                        while (pending == null && !stack.isEmpty()) {
                            Frame frame = stack.peek();
                            if (frame.entries.hasNext()) {
                                ObjectInfo entry = frame.entries.next();
                                if (entry.isCollection()) {
                                    // Remembered, not opened: descending now would issue a PROPFIND
                                    // the caller has not asked for and may never consume.
                                    frame.pendingCollections.addLast(entry.path());
                                }
                                pending = entry;
                                return;
                            }
                            ObjectPath next = frame.pendingCollections.pollFirst();
                            if (next != null) {
                                stack.push(
                                        new Frame(
                                                listOneLevel(
                                                        next,
                                                        ListRequest.builder()
                                                                .path(next)
                                                                .timeout(request.timeout().orElse(null))
                                                                .jobId(request.jobId().orElse(null))
                                                                .build())));
                                continue;
                            }
                            closeQuietly(stack.pop().stream);
                        }
                    }
                };

        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(
                                walker, Spliterator.ORDERED | Spliterator.NONNULL),
                        false)
                .onClose(
                        () -> {
                            while (!stack.isEmpty()) {
                                closeQuietly(stack.pop().stream);
                            }
                        });
    }

    /** One level of a recursive walk: its entries, and the collections it has yet to descend. */
    private static final class Frame {
        final Stream<ObjectInfo> stream;
        final Iterator<ObjectInfo> entries;
        final Deque<ObjectPath> pendingCollections = new ArrayDeque<>();

        Frame(Stream<ObjectInfo> stream) {
            this.stream = stream;
            this.entries = stream.iterator();
        }
    }

    // ---------------------------------------------------------------- get

    @Override
    public ObjectStream get(GetObjectRequest request) {
        ObjectPath path = request.resolvePath(basePath);
        String query = readQuery(request);

        return pipeline.execute(
                Plan.<ObjectStream>builder("get", path, DirectorFlavor.READ)
                        .query(query)
                        .scopes(List.of(ScopeRequest.of(StorageAction.READ, path)))
                        .timeout(request.timeout().orElse(null))
                        .jobId(request.jobId().orElse(null))
                        .action(attempt -> doGet(attempt, request, path))
                        .build());
    }

    private ObjectStream doGet(ServerAttempt attempt, GetObjectRequest request, ObjectPath path)
            throws IOException {
        HttpRequestSpec.Builder spec = attempt.request("GET");
        request.range().ifPresent(range -> spec.header("Range", range.toHeaderValue()));
        if (!request.wantDigests().isEmpty()) {
            spec.header(PelicanHeaders.WANT_DIGEST, DigestHeader.want(request.wantDigests()));
        }

        HttpResponseHandle response = send(attempt, spec.build());
        boolean handedOff = false;
        try {
            int status = response.statusCode();
            if (status != 200 && status != 206) {
                throw new ObjectServerHttpException(
                        attempt.uri(), status, readDetail(response));
            }
            HttpHeaders headers = response.headers();
            Map<DigestAlgorithm, String> digests = DigestHeader.parse(headers);

            ObjectStream.Builder builder =
                    ObjectStream.builder(response.body(), path)
                            .onClose(response)
                            .contentLength(contentLength(headers))
                            .etag(unquote(headers.firstOrNull("ETag")))
                            .lastModified(parseHttpDate(headers.firstOrNull("Last-Modified")))
                            .contentType(headers.firstOrNull("Content-Type"))
                            .digests(digests)
                            .servedBy(attempt.server().uri())
                            .servedByCache(isCache(attempt))
                            .partial(status == 206);
            request.verifyDigest().ifPresent(builder::verify);
            handedOff = true;
            return builder.build();
        } finally {
            if (!handedOff) {
                response.close();
            }
        }
    }

    // ---------------------------------------------------------------- put

    @Override
    public PutResult put(PutObjectRequest request) {
        ObjectPath path = request.resolvePath(basePath);

        if (request.overwrite()) {
            // Not atomic, and documented as such on the request. Anything that exists has to
            // go first, because the origin refuses a PUT over it.
            try {
                delete(DeleteRequest.builder().path(path).build());
            } catch (ObjectNotFoundException e) {
                log.debug("Nothing to remove at {} before an overwriting upload", path);
            }
        }

        StorageAction action = request.overwrite() ? StorageAction.MODIFY : StorageAction.CREATE;
        boolean replayable = request.body().isReplayable();

        return pipeline.execute(
                Plan.<PutResult>builder("put", path, DirectorFlavor.WRITE)
                        .scopes(List.of(ScopeRequest.of(action, path)))
                        .replayable(replayable)
                        .timeout(request.timeout().orElse(null))
                        .jobId(request.jobId().orElse(null))
                        .action(attempt -> doPut(attempt, request, path))
                        .build());
    }

    private PutResult doPut(ServerAttempt attempt, PutObjectRequest request, ObjectPath path)
            throws IOException {
        HttpRequestSpec.Builder spec = attempt.request("PUT").body(request.body());
        request.contentType().ifPresent(type -> spec.header("Content-Type", type));
        if (!request.wantDigests().isEmpty()) {
            spec.header(PelicanHeaders.WANT_DIGEST, DigestHeader.want(request.wantDigests()));
        }
        if (!request.objectMetadata().isEmpty()) {
            spec.header(
                    PelicanHeaders.OBJECT_METADATA,
                    StructuredFields.dictionary(request.objectMetadata()));
        }

        try (HttpResponseHandle response = attempt.transport().execute(spec.build())) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new ObjectServerHttpException(attempt.uri(), status, readDetail(response));
            }
            drain(response.body());
            HttpHeaders headers = response.headers();
            return new PutResult(
                    path,
                    request.body().contentLength(),
                    unquote(headers.firstOrNull("ETag")),
                    DigestHeader.parse(headers),
                    attempt.server().uri(),
                    headers.firstOrNull(PelicanHeaders.METADATA_STATUS));
        }
    }

    // ------------------------------------------------------- delete / mkcol / move

    @Override
    public void delete(DeleteRequest request) {
        ObjectPath path = request.resolvePath(basePath);
        pipeline.execute(
                Plan.<Void>builder("delete", path, DirectorFlavor.WRITE)
                        .scopes(List.of(ScopeRequest.of(StorageAction.MODIFY, path)))
                        .timeout(request.timeout().orElse(null))
                        .jobId(request.jobId().orElse(null))
                        .action(attempt -> simpleVerb(attempt, "DELETE", attempt.request("DELETE")))
                        .build());
    }

    @Override
    public void createCollection(CreateCollectionRequest request) {
        ObjectPath path = request.resolvePath(basePath);
        pipeline.execute(
                Plan.<Void>builder("mkcol", path, DirectorFlavor.WRITE)
                        .scopes(List.of(ScopeRequest.of(StorageAction.CREATE, path)))
                        .timeout(request.timeout().orElse(null))
                        .jobId(request.jobId().orElse(null))
                        .action(attempt -> simpleVerb(attempt, "MKCOL", attempt.request("MKCOL")))
                        .build());
    }

    @Override
    public void move(MoveRequest request) {
        ObjectPath source = request.resolvePath(basePath);
        ObjectPath destination = request.resolveDestination(basePath);
        pipeline.execute(
                Plan.<Void>builder("move", source, DirectorFlavor.WRITE)
                        .scopes(
                                List.of(
                                        ScopeRequest.of(StorageAction.MODIFY, source),
                                        ScopeRequest.of(StorageAction.MODIFY, destination)))
                        .timeout(request.timeout().orElse(null))
                        .jobId(request.jobId().orElse(null))
                        .action(
                                attempt -> {
                                    HttpRequestSpec.Builder spec =
                                            attempt
                                                    .request("MOVE")
                                                    .header("Destination", attempt.uriFor(destination).toString())
                                                    .header("Overwrite", request.overwrite() ? "T" : "F");
                                    return simpleVerb(attempt, "MOVE", spec);
                                })
                        .build());
    }

    // ---------------------------------------------------------------- capabilities

    @Override
    public Capabilities capabilities(ObjectPath path) {
        return pipeline.execute(
                Plan.<Capabilities>builder("options", path, DirectorFlavor.READ)
                        .scopes(List.of(ScopeRequest.of(StorageAction.READ, path)))
                        .action(
                                attempt -> {
                                    try (HttpResponseHandle response =
                                            send(attempt, attempt.request("OPTIONS").build())) {
                                        int status = response.statusCode();
                                        if (status < 200 || status >= 300) {
                                            throw new ObjectServerHttpException(
                                                    attempt.uri(), status, readDetail(response));
                                        }
                                        drain(response.body());
                                        java.util.Set<String> allowed =
                                                new java.util.LinkedHashSet<>(
                                                        response.headers().allElements("Allow"));
                                        allowed.addAll(response.headers().allElements("Public"));
                                        return new Capabilities(
                                                attempt.server().uri(),
                                                allowed,
                                                response.headers().firstOrNull("DAV"));
                                    }
                                })
                        .build());
    }

    @Override
    public void close() {
        if (ownedTransport != null) {
            try {
                ownedTransport.close();
            } catch (IOException e) {
                log.debug("Ignoring failure closing the HTTP transport", e);
            }
        }
    }

    // ---------------------------------------------------------------- WebDAV plumbing

    /**
     * Run a listing plan, falling back to the namespace's collections endpoint.
     *
     * <p>An XRootD cache answers {@code PROPFIND} on a collection with {@code 409 Conflict}:
     * caches serve objects, not directory listings.  The Director hands out caches for a
     * read, so the first attempt at listing can land on servers that structurally cannot
     * answer.  The namespace advertises a {@code collections-url} for exactly this, and one
     * -- and only one -- level of fallback is allowed, so the two directions cannot
     * ping-pong.
     */
    private <T> T withCollectionsFallback(
            ObjectPath path, java.util.function.Function<Boolean, Plan<T>> planFor) {
        try {
            return pipeline.execute(planFor.apply(false));
        } catch (AllServersFailedException e) {
            if (!sawConflict(e)) {
                throw e;
            }
            log.debug(
                    "Every object server refused a PROPFIND of {} with 409; retrying against the"
                            + " namespace's collections endpoint",
                    path);
            try {
                return pipeline.execute(planFor.apply(true));
            } catch (NoObjectServersException noCollections) {
                // The namespace advertises no listing endpoint, so the 409s are the real answer.
                throw e;
            }
        }
    }

    private static boolean sawConflict(AllServersFailedException e) {
        return e.failures().stream().anyMatch(f -> f.statusCode() == HTTP_CONFLICT);
    }

    /** Servers for the collections-endpoint fallback: the one URL the namespace advertises. */
    private static List<ObjectServer> collectionsServers(
            org.pelicanplatform.client.federation.DirectorResponse response) {
        return response
                .namespace()
                .collectionsUrl()
                .map(uri -> List.of(new ObjectServer(uri, 1, "collections", null)))
                .orElseGet(List::of);
    }

    /**
     * A cache cannot list, so a 409 has to be taken elsewhere rather than treated as the
     * conflict it would mean on a write.
     */
    private static Disposition classifyPropfind(int statusCode) {
        if (statusCode == HTTP_CONFLICT) {
            return Disposition.NEXT_SERVER;
        }
        return Disposition.of(statusCode);
    }

    private Optional<ObjectInfo> propfind(ServerAttempt attempt, ObjectPath path, int depth)
            throws IOException {
        try (HttpResponseHandle response = propfindResponse(attempt, depth)) {
            return firstEntry(response, path);
        }
    }

    private Stream<ObjectInfo> propfindChildren(ServerAttempt attempt, ObjectPath path)
            throws IOException {
        HttpResponseHandle response = propfindResponse(attempt, 1);
        boolean handedOff = false;
        try {
            String selfPath = normalizeHref(attempt.uri().getRawPath());
            Stream<ObjectInfo> stream = childStream(response, path, selfPath);
            handedOff = true;
            return stream;
        } finally {
            if (!handedOff) {
                response.close();
            }
        }
    }

    private HttpResponseHandle propfindResponse(ServerAttempt attempt, int depth) throws IOException {
        HttpRequestSpec spec =
                attempt
                        .request("PROPFIND")
                        .header("Depth", String.valueOf(depth))
                        .header("Content-Type", "application/xml; charset=utf-8")
                        .body(RequestBody.fromString(MultiStatusParser.PROPFIND_BODY))
                        .build();
        HttpResponseHandle response = send(attempt, spec);
        if (response.statusCode() != HTTP_MULTI_STATUS) {
            int status = response.statusCode();
            String detail = readDetail(response);
            response.close();
            throw new ObjectServerHttpException(attempt.uri(), status, detail);
        }
        return response;
    }

    /** The first {@code <response>} of a multi-status body, as the object it describes. */
    private Optional<ObjectInfo> firstEntry(HttpResponseHandle response, ObjectPath path)
            throws IOException {
        try (var reader = MultiStatusParser.open(response.body(), null)) {
            if (!reader.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(toObjectInfo(reader.next(), path, response.uri()));
        }
    }

    /**
     * Children of a listed collection, lazily.
     *
     * <p>The collection lists itself first; that entry is recognised by its href matching the
     * URL that was requested, rather than by position or by name, so a child that happens to
     * share its parent's name is not mistaken for it.
     */
    private Stream<ObjectInfo> childStream(
            HttpResponseHandle response, ObjectPath collection, String selfPath) {
        return MultiStatusParser.stream(response.body(), response)
                .map(entry -> toChild(entry, collection, selfPath))
                .filter(java.util.Objects::nonNull);
    }

    private ObjectInfo toChild(DavEntry entry, ObjectPath collection, String selfPath) {
        String href = entry.href();
        if (href == null || href.isBlank()) {
            return null;
        }
        String hrefPath = normalizeHref(hrefPathOf(href));
        if (hrefPath.equals(selfPath)) {
            return null;
        }
        String prefix = selfPath.endsWith("/") ? selfPath : selfPath + "/";
        if (!hrefPath.startsWith(prefix)) {
            log.debug(
                    "Ignoring a listing entry outside the collection that was listed: {} not under {}",
                    hrefPath,
                    selfPath);
            return null;
        }
        String relative = hrefPath.substring(prefix.length());
        if (relative.isEmpty()) {
            return null;
        }
        return toObjectInfo(entry, ObjectPath.resolve(collection, relative), null);
    }

    private ObjectInfo toObjectInfo(DavEntry entry, ObjectPath path, URI servedBy) {
        ObjectInfo.Builder builder =
                ObjectInfo.builder(path)
                        .size(entry.contentLength())
                        .type(entry.isCollection() ? ObjectType.COLLECTION : ObjectType.OBJECT);
        entry.lastModified().ifPresent(builder::lastModified);
        entry.etag().ifPresent(builder::etag);
        entry.contentType().ifPresent(builder::contentType);
        if (servedBy != null) {
            builder.servedBy(servedBy);
        }
        return builder.build();
    }

    /** The self path of a Director-proxied listing: the Director's own URL for the object. */
    private String directorSelfPath(
            org.pelicanplatform.client.federation.DirectorQueryResult result, ObjectPath path) {
        return normalizeHref(
                result.proxiedResponse()
                        .map(r -> r.uri().getRawPath())
                        .orElseGet(() -> org.pelicanplatform.client.http.UriPaths.encode(path)));
    }

    private static String hrefPathOf(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            try {
                return URI.create(href).getRawPath();
            } catch (IllegalArgumentException e) {
                return href;
            }
        }
        int q = href.indexOf('?');
        return q < 0 ? href : href.substring(0, q);
    }

    /** Decode and strip a trailing slash so two spellings of one path compare equal. */
    private static String normalizeHref(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        while (decoded.length() > 1 && decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded.isEmpty() ? "/" : decoded;
    }

    // ---------------------------------------------------------------- small helpers

    /**
     * Send a request to an object server, following one same-server redirect.
     *
     * <p>Pelican origins redirect a collection URL to its trailing-slash form -- an
     * {@code OPTIONS} of {@code /ns/dir} answers 307 to {@code /ns/dir/}. That is ordinary
     * WebDAV behavior, and treating it as a failure makes every such request fail against a
     * real origin.
     *
     * <p>Exactly one hop, and only to the same scheme and authority. A redirect that leaves
     * the server is not followed: the Director decides which servers this client talks to,
     * and a redirect chased blindly would both bypass that and carry the caller's credential
     * somewhere it was never sent deliberately. A non-replayable body is not re-sent either.
     */
    private static HttpResponseHandle send(ServerAttempt attempt, HttpRequestSpec spec)
            throws IOException {
        HttpResponseHandle response = attempt.transport().execute(spec);
        int status = response.statusCode();
        if (status != 301 && status != 302 && status != 307 && status != 308) {
            return response;
        }
        String location = response.headers().firstOrNull("Location");
        if (location == null || location.isBlank()) {
            return response;
        }
        URI target;
        try {
            target = spec.uri().resolve(location);
        } catch (IllegalArgumentException e) {
            return response;
        }
        boolean sameServer =
                java.util.Objects.equals(target.getScheme(), spec.uri().getScheme())
                        && java.util.Objects.equals(target.getAuthority(), spec.uri().getAuthority());
        boolean bodyCanBeResent = spec.body().map(RequestBody::isReplayable).orElse(true);
        if (!sameServer || !bodyCanBeResent) {
            return response;
        }
        log.debug("Following a same-server redirect from {} to {}", spec.uri(), target);
        response.close();
        return attempt.transport().execute(spec.withUri(target));
    }

    private Void simpleVerb(ServerAttempt attempt, String method, HttpRequestSpec.Builder spec)
            throws IOException {
        try (HttpResponseHandle response = send(attempt, spec.build())) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new ObjectServerHttpException(attempt.uri(), status, readDetail(response));
            }
            drain(response.body());
            log.debug("{} of {} succeeded at {}", method, attempt.path(), attempt.server().uri());
            return null;
        }
    }

    private static String readQuery(GetObjectRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.directRead()) {
            sb.append(TransferHints.QUERY_DIRECT_READ).append("=true");
        }
        if (request.preferCached()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(TransferHints.QUERY_PREFER_CACHED).append("=true");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * How many bytes the stream should expect.
     *
     * <p>For a 206 this is the length of the range rather than of the object, which is
     * exactly right: the caller asked for the range, so that is what a short read is short of.
     */
    private static long contentLength(HttpHeaders headers) {
        return parseLong(headers.firstOrNull("Content-Length"));
    }

    private static long parseLong(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String out = value.strip();
        if (out.startsWith("W/")) {
            out = out.substring(2);
        }
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            return out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static Instant parseHttpDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.ZonedDateTime.parse(
                            value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Whether the server that answered was a cache.
     *
     * <p>A heuristic: the Director does not label its {@code Link} entries, so this compares
     * against the namespace's origin-side collections endpoint. Informational only -- nothing
     * in this client behaves differently based on it.
     */
    private static boolean isCache(ServerAttempt attempt) {
        Optional<URI> collections = attempt.director().namespace().collectionsUrl();
        if (collections.isEmpty()) {
            return false;
        }
        return !java.util.Objects.equals(
                collections.get().getAuthority(), attempt.server().uri().getAuthority());
    }

    private static String readDetail(HttpResponseHandle response) {
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(2048);
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            return text.length() > 512 ? text.substring(0, 512) + "..." : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static void drain(InputStream body) {
        try (InputStream in = body) {
            in.readAllBytes();
        } catch (IOException e) {
            log.trace("Ignoring failure draining a response body", e);
        }
    }

    private static void closeQuietly(Stream<?> stream) {
        try {
            stream.close();
        } catch (UncheckedIOException e) {
            log.debug("Ignoring failure closing a listing stream", e);
        }
    }
}
