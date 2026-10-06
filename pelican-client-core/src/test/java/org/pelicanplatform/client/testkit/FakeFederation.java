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

package org.pelicanplatform.client.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

/**
 * An in-process stand-in for a Pelican federation: discovery, a Director, and object servers.
 *
 * <p>Enough of the protocol to exercise the parts that are easy to get wrong -- {@code Link}
 * ordering, the {@code X-Pelican-*} family, per-server failure and failover, a cache that
 * refuses to list, and WebDAV -- without needing XRootD or a container.
 */
public final class FakeFederation implements AutoCloseable {

    /** One object or collection held by a fake object server. */
    public record Entry(byte[] content, boolean collection, Instant lastModified) {
        public static Entry object(byte[] content) {
            return new Entry(content, false, Instant.parse("2026-01-02T03:04:05Z"));
        }

        public static Entry directory() {
            return new Entry(new byte[0], true, Instant.parse("2026-01-02T03:04:05Z"));
        }
    }

    /** A fake cache or origin. */
    public static final class ObjectServerSpec {
        final String name;
        final NavigableMap<String, Entry> entries = new TreeMap<>();
        volatile int forcedStatus = 0;
        volatile String forcedBody = "";
        volatile boolean refusesPropfind;
        volatile String propfindRedirectHost;
        final AtomicInteger requestCount = new AtomicInteger();
        final List<String> seenAuthorization = new CopyOnWriteArrayList<>();

        ObjectServerSpec(String name) {
            this.name = name;
        }

        public ObjectServerSpec put(String path, String content) {
            entries.put(path, Entry.object(content.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        public ObjectServerSpec put(String path, byte[] content) {
            entries.put(path, Entry.object(content));
            return this;
        }

        public ObjectServerSpec collection(String path) {
            entries.put(path, Entry.directory());
            return this;
        }

        /** Make every request to this server fail with a status. */
        public ObjectServerSpec failWith(int status, String body) {
            this.forcedStatus = status;
            this.forcedBody = body;
            return this;
        }

        /** Behave like an XRootD cache: refuse PROPFIND on a collection with 409. */
        public ObjectServerSpec refusePropfind() {
            this.refusesPropfind = true;
            return this;
        }

        /**
         * Behave like the native Go cache: redirect PROPFIND to another host.
         *
         * <p>The host spelling is what matters. A client must not chase an object server's
         * redirect to a host the Director never named, so the test needs the target to differ
         * in authority from the server that issued it while still being reachable.
         */
        public ObjectServerSpec redirectPropfindTo(String hostAndPort) {
            this.propfindRedirectHost = hostAndPort;
            return this;
        }

        public int requestCount() {
            return requestCount.get();
        }

        /** The Authorization headers this server was sent, in order. */
        public List<String> seenAuthorization() {
            return seenAuthorization;
        }

        public boolean has(String path) {
            return entries.containsKey(path);
        }

        public byte[] content(String path) {
            Entry entry = entries.get(path);
            return entry == null ? null : entry.content();
        }
    }

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final HttpServer server;
    private final Map<String, ObjectServerSpec> objectServers = new ConcurrentHashMap<>();
    private final List<String> directorOrder = new CopyOnWriteArrayList<>();

    private volatile String namespacePrefix = "/ns";
    private volatile boolean requireToken;
    private volatile String collectionsServerName;
    private volatile String issuer = "https://issuer.example";
    private volatile String basePath = "/ns";
    private volatile int maxScopeDepth = 3;
    private final AtomicInteger directorQueries = new AtomicInteger();
    private final List<String> directorMethods = new CopyOnWriteArrayList<>();
    private final List<String> directorAuthorization = new CopyOnWriteArrayList<>();
    private volatile int directorFailuresRemaining;
    private volatile int directorFailureStatus = 502;
    private volatile boolean directorProxiesPropfind;

    public FakeFederation() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the fake federation", e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/.well-known/pelican-configuration", this::handleDiscovery);
        server.createContext("/director", this::handleDirector);
        server.createContext("/srv/", this::handleObjectServer);
        server.start();
    }

    // ------------------------------------------------------------------ configuration

    public ObjectServerSpec objectServer(String name) {
        return objectServers.computeIfAbsent(name, ObjectServerSpec::new);
    }

    /** The order (and therefore the {@code pri}) the Director advertises servers in. */
    public FakeFederation directorServers(String... names) {
        directorOrder.clear();
        directorOrder.addAll(List.of(names));
        for (String name : names) {
            objectServer(name);
        }
        return this;
    }

    public FakeFederation namespace(String prefix, boolean requireToken) {
        this.namespacePrefix = prefix;
        this.requireToken = requireToken;
        return this;
    }

    public FakeFederation basePath(String basePath) {
        this.basePath = basePath;
        return this;
    }

    public FakeFederation maxScopeDepth(int depth) {
        this.maxScopeDepth = depth;
        return this;
    }

    /** Advertise a {@code collections-url} pointing at one of the object servers. */
    public FakeFederation collectionsServer(String name) {
        this.collectionsServerName = name;
        objectServer(name);
        return this;
    }

    /** Make the next {@code count} Director queries fail as a restarting Director would. */
    public FakeFederation directorFailures(int count, int status) {
        this.directorFailuresRemaining = count;
        this.directorFailureStatus = status;
        return this;
    }

    /** Answer PROPFIND at the Director itself, as a Director new enough to proxy WebDAV does. */
    public FakeFederation directorProxiesPropfind(boolean proxies) {
        this.directorProxiesPropfind = proxies;
        return this;
    }

    public URI discoveryUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI serverUri(String name) {
        return URI.create(discoveryUri() + "/srv/" + name);
    }

    public int directorQueryCount() {
        return directorQueries.get();
    }

    public List<String> directorMethods() {
        return directorMethods;
    }

    public List<String> directorAuthorization() {
        return directorAuthorization;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ handlers

    private void handleDiscovery(HttpExchange exchange) throws IOException {
        String json =
                String.format(
                        "{\"discovery_endpoint\":\"%s\",\"director_endpoint\":\"%s/director\","
                                + "\"namespace_registration_endpoint\":\"%s/registry\","
                                + "\"jwks_uri\":\"%s/.well-known/issuer.jwks\","
                                + "\"broker_endpoint\":\"%s/broker\"}",
                        discoveryUri(), discoveryUri(), discoveryUri(), discoveryUri(), discoveryUri());
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        respond(exchange, 200, json.getBytes(StandardCharsets.UTF_8));
    }

    private void handleDirector(HttpExchange exchange) throws IOException {
        directorQueries.incrementAndGet();
        directorMethods.add(exchange.getRequestMethod());
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        directorAuthorization.add(auth == null ? "" : auth);

        if (directorFailuresRemaining > 0) {
            directorFailuresRemaining--;
            // No Server: pelican/ header: this is what an ingress in front of a restarting
            // Director looks like, and the client is expected to wait it out.
            respond(exchange, directorFailureStatus, "bad gateway".getBytes(StandardCharsets.UTF_8));
            return;
        }

        exchange.getResponseHeaders().add("Server", "pelican/7.19.0");
        String objectPath = decodedPath(exchange).substring("/director".length());
        if (objectPath.isEmpty()) {
            objectPath = "/";
        }

        addPelicanHeaders(exchange, objectPath);

        if (directorProxiesPropfind && exchange.getRequestMethod().equals("PROPFIND")) {
            ObjectServerSpec origin = lookupServer(collectionsServerName != null
                    ? collectionsServerName
                    : directorOrder.get(directorOrder.size() - 1));
            byte[] body =
                    multiStatus(
                            origin, objectPath, depthOf(exchange), "/director" + encode(objectPath));
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            respond(exchange, 207, body);
            return;
        }

        List<String> names = new ArrayList<>(directorOrder);
        if (names.isEmpty()) {
            respond(exchange, 404, "no servers".getBytes(StandardCharsets.UTF_8));
            return;
        }
        StringBuilder link = new StringBuilder();
        int priority = 1;
        for (String name : names) {
            if (link.length() > 0) {
                link.append(", ");
            }
            link.append('<')
                    .append(serverUri(name))
                    .append(encode(objectPath))
                    .append(">; rel=\"duplicate\"; pri=")
                    .append(priority)
                    .append("; depth=")
                    .append(priority);
            priority++;
        }
        exchange.getResponseHeaders().add("Link", link.toString());
        exchange.getResponseHeaders().add("Location", serverUri(names.get(0)) + encode(objectPath));
        respond(exchange, 307, new byte[0]);
    }

    private void addPelicanHeaders(HttpExchange exchange, String objectPath) {
        StringBuilder ns = new StringBuilder();
        ns.append("namespace=").append(namespacePrefix);
        ns.append(", require-token=").append(requireToken);
        if (collectionsServerName != null) {
            ns.append(", collections-url=").append(serverUri(collectionsServerName));
        }
        exchange.getResponseHeaders().add("X-Pelican-Namespace", ns.toString());
        exchange.getResponseHeaders().add("X-Pelican-JobId", "job-" + directorQueries.get());
        if (requireToken) {
            exchange.getResponseHeaders().add("X-Pelican-Authorization", "issuer=" + issuer);
            exchange
                    .getResponseHeaders()
                    .add(
                            "X-Pelican-Token-Generation",
                            String.format(
                                    "issuer=%s, base-path=%s, max-scope-depth=%d, strategy=OAuth2",
                                    issuer, basePath, maxScopeDepth));
        }
    }

    private void handleObjectServer(HttpExchange exchange) throws IOException {
        String fullPath = decodedPath(exchange);
        String remainder = fullPath.substring("/srv/".length());
        int slash = remainder.indexOf('/');
        String name = slash < 0 ? remainder : remainder.substring(0, slash);
        String objectPath = slash < 0 ? "/" : remainder.substring(slash);

        ObjectServerSpec spec = objectServers.get(name);
        if (spec == null) {
            respond(exchange, 404, "no such server".getBytes(StandardCharsets.UTF_8));
            return;
        }
        spec.requestCount.incrementAndGet();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        spec.seenAuthorization.add(auth == null ? "" : auth);

        if (spec.forcedStatus != 0) {
            respond(exchange, spec.forcedStatus, spec.forcedBody.getBytes(StandardCharsets.UTF_8));
            return;
        }

        switch (exchange.getRequestMethod()) {
            case "GET" -> serveGet(exchange, spec, objectPath);
            case "PUT" -> servePut(exchange, spec, objectPath);
            case "DELETE" -> serveDelete(exchange, spec, objectPath);
            case "MKCOL" -> serveMkcol(exchange, spec, objectPath);
            case "MOVE" -> serveMove(exchange, spec, objectPath);
            case "PROPFIND" -> servePropfind(exchange, spec, objectPath, fullPath);
            case "OPTIONS" -> serveOptions(exchange, spec);
            default -> respond(exchange, 405, "not allowed".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveGet(HttpExchange exchange, ObjectServerSpec spec, String objectPath)
            throws IOException {
        Entry entry = spec.entries.get(objectPath);
        if (entry == null || entry.collection()) {
            respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] content = entry.content();
        String wantDigest = exchange.getRequestHeaders().getFirst("Want-Digest");
        if (wantDigest != null && wantDigest.contains("crc32c")) {
            CRC32C crc = new CRC32C();
            crc.update(content, 0, content.length);
            exchange.getResponseHeaders().add("Digest", "crc32c=" + String.format("%08x", crc.getValue()));
        }
        exchange.getResponseHeaders().add("ETag", "\"etag-" + content.length + "\"");
        exchange.getResponseHeaders().add("Last-Modified", HTTP_DATE.format(entry.lastModified()));

        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring("bytes=".length()).split("-", -1);
            int start = Integer.parseInt(bounds[0]);
            int end = bounds.length > 1 && !bounds[1].isEmpty()
                    ? Integer.parseInt(bounds[1])
                    : content.length - 1;
            end = Math.min(end, content.length - 1);
            if (start > end) {
                respond(exchange, 416, new byte[0]);
                return;
            }
            byte[] slice = new byte[end - start + 1];
            System.arraycopy(content, start, slice, 0, slice.length);
            exchange
                    .getResponseHeaders()
                    .add("Content-Range", String.format("bytes %d-%d/%d", start, end, content.length));
            respond(exchange, 206, slice);
            return;
        }
        respond(exchange, 200, content);
    }

    private void servePut(HttpExchange exchange, ObjectServerSpec spec, String objectPath)
            throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (spec.entries.containsKey(objectPath)) {
            // Pelican origins are write-once.
            respond(exchange, 409, "remote object already exists".getBytes(StandardCharsets.UTF_8));
            return;
        }
        spec.entries.put(objectPath, Entry.object(body));
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        exchange.getResponseHeaders().add("Digest", "crc32c=" + String.format("%08x", crc.getValue()));
        exchange.getResponseHeaders().add("ETag", "\"etag-" + body.length + "\"");
        respond(exchange, 201, new byte[0]);
    }

    private void serveDelete(HttpExchange exchange, ObjectServerSpec spec, String objectPath)
            throws IOException {
        if (spec.entries.remove(objectPath) == null) {
            respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 204, new byte[0]);
    }

    private void serveMkcol(HttpExchange exchange, ObjectServerSpec spec, String objectPath)
            throws IOException {
        if (spec.entries.containsKey(objectPath)) {
            respond(exchange, 405, "already exists".getBytes(StandardCharsets.UTF_8));
            return;
        }
        spec.entries.put(objectPath, Entry.directory());
        respond(exchange, 201, new byte[0]);
    }

    private void serveMove(HttpExchange exchange, ObjectServerSpec spec, String objectPath)
            throws IOException {
        Entry entry = spec.entries.get(objectPath);
        if (entry == null) {
            respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String destination = exchange.getRequestHeaders().getFirst("Destination");
        if (destination == null) {
            respond(exchange, 400, "no destination".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String destPath = URI.create(destination).getPath();
        destPath = destPath.substring(("/srv/" + spec.name).length());
        spec.entries.remove(objectPath);
        spec.entries.put(destPath, entry);
        respond(exchange, 201, new byte[0]);
    }

    private void servePropfind(
            HttpExchange exchange, ObjectServerSpec spec, String objectPath, String fullPath)
            throws IOException {
        exchange.getRequestBody().readAllBytes();
        Entry entry = spec.entries.get(objectPath);
        if (entry == null) {
            respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (spec.propfindRedirectHost != null && entry.collection()) {
            exchange
                    .getResponseHeaders()
                    .add(
                            "Location",
                            "http://" + spec.propfindRedirectHost + encode(fullPath));
            respond(exchange, 307, new byte[0]);
            return;
        }
        if (spec.refusesPropfind && entry.collection()) {
            // What an XRootD cache does: it serves objects, not listings.
            respond(exchange, 409, "conflict".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = multiStatus(spec, objectPath, depthOf(exchange), encode(fullPath));
        exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
        respond(exchange, 207, body);
    }

    private void serveOptions(HttpExchange exchange, ObjectServerSpec spec) throws IOException {
        exchange
                .getResponseHeaders()
                .add("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL, MOVE");
        exchange.getResponseHeaders().add("DAV", "1,2");
        respond(exchange, 200, new byte[0]);
    }

    private byte[] multiStatus(
            ObjectServerSpec spec, String objectPath, int depth, String selfHref) {
        Entry self = spec.entries.get(objectPath);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n");
        appendResponse(sb, selfHref, self == null ? Entry.directory() : self);

        if (depth > 0 && (self == null || self.collection())) {
            String prefix = objectPath.equals("/") ? "/" : objectPath + "/";
            for (Map.Entry<String, Entry> e : spec.entries.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith(prefix) || key.equals(objectPath)) {
                    continue;
                }
                String relative = key.substring(prefix.length());
                if (relative.contains("/")) {
                    continue;
                }
                String href = selfHref.endsWith("/") ? selfHref + encode(relative)
                        : selfHref + "/" + encode(relative);
                appendResponse(sb, href, e.getValue());
            }
        }
        sb.append("</D:multistatus>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendResponse(StringBuilder sb, String href, Entry entry) {
        sb.append("  <D:response>\n");
        sb.append("    <D:href>").append(href).append(entry.collection() ? "/" : "").append("</D:href>\n");
        sb.append("    <D:propstat>\n      <D:prop>\n");
        sb.append("        <D:resourcetype>");
        if (entry.collection()) {
            sb.append("<D:collection/>");
        }
        sb.append("</D:resourcetype>\n");
        if (!entry.collection()) {
            sb.append("        <D:getcontentlength>")
                    .append(entry.content().length)
                    .append("</D:getcontentlength>\n");
            sb.append("        <D:getetag>\"etag-")
                    .append(entry.content().length)
                    .append("\"</D:getetag>\n");
        }
        sb.append("        <D:getlastmodified>")
                .append(HTTP_DATE.format(ZonedDateTime.ofInstant(entry.lastModified(), ZoneOffset.UTC)))
                .append("</D:getlastmodified>\n");
        sb.append("      </D:prop>\n      <D:status>HTTP/1.1 200 OK</D:status>\n");
        sb.append("    </D:propstat>\n");
        if (entry.collection()) {
            // A correct server reports the properties it does not have for a collection as 404
            // in a second propstat block; a parser that reads it anyway records size 0.
            sb.append("    <D:propstat>\n      <D:prop><D:getcontentlength/></D:prop>\n");
            sb.append("      <D:status>HTTP/1.1 404 Not Found</D:status>\n    </D:propstat>\n");
        }
        sb.append("  </D:response>\n");
    }

    private ObjectServerSpec lookupServer(String name) {
        ObjectServerSpec spec = objectServers.get(name);
        if (spec == null) {
            throw new IllegalStateException("no fake object server named " + name);
        }
        return spec;
    }

    private static int depthOf(HttpExchange exchange) {
        String depth = exchange.getRequestHeaders().getFirst("Depth");
        if (depth == null) {
            return 1;
        }
        return depth.equals("infinity") ? Integer.MAX_VALUE : Integer.parseInt(depth.strip());
    }

    private static String decodedPath(HttpExchange exchange) {
        return URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
    }

    private static String encode(String path) {
        return org.pelicanplatform.client.http.UriPaths.encode(path);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /** A JWT-shaped token with the given scopes, unsigned; nothing here verifies signatures. */
    public static String token(String... scopes) {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("iss", "https://issuer.example");
        String payload =
                base64Url(
                        String.format(
                                "{\"iss\":\"%s\",\"exp\":%d,\"scope\":\"%s\"}",
                                claims.get("iss"),
                                Instant.now().plusSeconds(3600).getEpochSecond(),
                                String.join(" ", scopes)));
        return header + "." + payload + ".";
    }

    private static String base64Url(String json) {
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
