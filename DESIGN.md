# pelican-client-java: design

Status: implemented for phases 1-3; see [Implementation status](#14-implementation-status)
Audience: developers working on this library, and the Tapis Files maintainers who will
consume it

Where this document and the code disagree, the code is right and this document has a bug.

## 1. What this is

A Java library that speaks the Pelican data-federation protocol, plus a thin bridge
class that plugs it into [Tapis Files](https://github.com/tapis-project/tapis-files)
as a new `IRemoteDataClient`.

The shape to copy is the one Tapis already uses for S3: the AWS SDK does all the
protocol work, and `S3DataClient` is ~550 lines of parameter marshalling with no
knowledge of SigV4, bucket addressing styles, or retries. `PelicanDataClient` should
be about that size and should contain no knowledge of federation discovery, Director
redirects, the `X-Pelican-*` header family, token scoping, or cache failover.

Two deliverables:

| Artifact | Lives in | Role |
| --- | --- | --- |
| `pelican-client-core` | this repo | Protocol. Framework-free. The "AWS SDK" of the analogy. |
| `PelicanDataClient` | `tapis-files` | ~250-line `IRemoteDataClient` bridge. The "S3DataClient" of the analogy. |

Everything below is about making that second row small.

## 2. Constraints that shape the design

### 2.1 What Pelican actually requires of a client

A Pelican client is an HTTP client wrapped around a three-step resolution dance. In
the Go implementation these live in `pelican_url/discovery.go`, `client/director.go`,
and `client/acquire_token.go`; a Java client has to reimplement all three.

**Discovery.** A `pelican://<host>/<path>` URL names a federation, not a server.
`GET https://<host>/.well-known/pelican-configuration` returns:

```json
{ "discovery_endpoint": "...", "director_endpoint": "...",
  "namespace_registration_endpoint": "...", "jwks_uri": "...",
  "broker_endpoint": "..." }
```

`osdf://` and `stash://` URLs carry no host; they resolve against a fixed discovery
host (`osg-htc.org`). Results are cached — the Go client uses a 30-minute TTL with a
shorter TTL on failures.

**Director query.** The client issues the *verb it intends to use* against
`<director_endpoint>/<path>` with redirects disabled, and expects `307`. The
interesting part is in the response headers, not the `Location`:

```
Link: <https://cache-a:8443/ns/obj>; rel="duplicate"; pri=1; depth=3,
      <https://cache-b:8443/ns/obj>; rel="duplicate"; pri=2
X-Pelican-Namespace: namespace=/ns, require-token=true, collections-url=https://origin:8444
X-Pelican-Authorization: issuer=https://issuer.example
X-Pelican-Token-Generation: issuer=https://issuer.example, base-path=/ns, max-scope-depth=3, strategy=OAuth2
X-Pelican-Broker: https://broker.example
```

Three consequences the API has to absorb:

- The `Link` header is an *ordered candidate list*, not a single answer. Sorting by
  `pri` and failing over on error is the client's job. A one-server-per-request model
  cannot express this, so "which server served this?" has to be an output, not an input.
- The verb matters. A `GET`-flavored query returns caches; a `PUT`-flavored query
  returns only origins that accept writes. Asking with the wrong verb either fails
  (caches cannot answer for a write-only namespace) or, worse, hands a write
  credential to every cache in the list. Read-vs-write is therefore part of *every*
  request type in this API, including stat.
- Auth requirements arrive from the Director, per-namespace, at request time. The
  credential layer must be able to run *after* the Director answers, and must be able
  to scope a request against `base-path` — the Director speaks absolute namespace
  paths, but WLCG scopes are relative to `base-path` (`storage.read:/sub/dir`, not
  `storage.read:/ns/sub/dir`).

**Object-server request.** The actual `GET`/`PUT`/`PROPFIND`/`DELETE`/`MKCOL`/`MOVE`/
`COPY` goes to the chosen cache or origin with `Authorization: Bearer <token>`. The
Pelican V2 origin advertises and serves the full WebDAV verb set; `COPY` is present
only when third-party copy is enabled, and XRootD-backed origins serve a narrower set.
So verb support is a *runtime* property, not a compile-time one.

### 2.2 What Tapis requires of a data client

`IRemoteDataClient` is small and blunt:

```java
List<FileInfo> ls(String path, long limit, long offset);
void           upload(String path, InputStream fileStream);
void           mkdir(String path);
void           move(String srcPath, String dstPath);
void           copy(String srcPath, String dstPath);
void           delete(String path);
FileInfo       getFileInfo(String path, boolean followLinks);
InputStream    getStream(String path);
InputStream    getBytesByRange(String path, long startByte, long count);
```

Paths are relative to the system's `rootDir`. `FileInfo` carries name, path, size,
`lastModified`, `type` (`FILE`/`DIR`/`SYMBOLIC_LINK`/`OTHER`), `mimeType`, `url`, and
POSIX-ish owner/group/permission fields that non-POSIX backends leave null.
Errors are `IOException` / `NotFoundException` / `NotSupportedException`.

Notice `ls(path, limit, offset)`. S3 satisfies it by wrapping the paginator in a
`Stream` and calling `.skip(offset).limit(limit)`. Our listing API should be shaped so
the bridge can do exactly that — which means returning a lazy `Stream<ObjectInfo>`,
not a materialized `List`.

Tapis is on Java 21 (`maven-compiler-plugin-release` 21 in `tapis-bom`), uses the
`javax.ws.rs` (pre-Jakarta) namespace, Jersey, SLF4J, and HK2 injection.

## 3. Module layout

```
pelican-client-java/
  pelican-client-core/        # protocol; deps: SLF4J-api, Jackson-databind. JDK 21.
    .../client/               # public API: PelicanClient, ObjectPath, requests, exceptions
    .../client/federation/    # discovery, Director, X-Pelican-* headers
    .../client/auth/          # credential SPI and the providers
    .../client/http/          # HttpTransport SPI + the JDK implementation
    .../client/dav/           # streaming multi-status parser
    .../client/internal/      # the resolution pipeline
  codegen/                    # error_codes.yaml (vendored) + the generator
  tapis-bridge/               # reference IRemoteDataClient; built inside tapis-files
  pelican-client-transport-apache5/  # not yet written; adds HTTP trailer support
  pelican-client-cli/         # not yet written; a debugging surface, not a product
```

The default JDK transport lives inside `core` rather than in its own module: it is the
only implementation most callers will ever use, and a separate artifact for it would be
ceremony. The Apache5 one is separate because it exists to add a dependency.

The fake federation the tests run against (`testkit.FakeFederation`) is built on the JDK's
own `com.sun.net.httpserver`, so the test suite needs no WireMock and no container.

`core` deliberately does not depend on Jersey, Apache HttpClient, Guava, or anything
in the Tapis dependency graph. Jackson is the one concession: it is already in Tapis
and in nearly every Java service, and hand-rolling JSON for discovery is worse.
WebDAV XML is parsed with the JDK's own StAX, so no XML dependency.

The `HttpTransport` split exists for one concrete reason, see §8.2.

## 4. Core API

The API mirrors the AWS SDK v2 idiom — immutable request objects with builders, an
`AutoCloseable` client — so that the shape is already familiar to whoever maintains
`S3DataClient`.

### 4.1 Paths

Every path in the public API is an `ObjectPath`, never a `String`:

```java
public final class ObjectPath {
    public static ObjectPath of(String absolutePath);          // must start with '/'
    public static ObjectPath resolve(ObjectPath base, String relative);
    public ObjectPath child(String name);                      // rejects "", ".", "..", "/" in name
    public ObjectPath parent();
    public String basename();
    public String toString();                                  // normalized, always absolute
}
```

`resolve` and `child` normalize and then **reject any result that escapes `base`**.
This is deliberate. The Go client hit this exact bug class: a source basename of `..`
joined onto a destination collection silently uploads to the collection's parent, and
`cmd/object_put.go` now has a special-case guard against it. Making `ObjectPath` the
only way to name an object means the guard is in one place, and a Tapis `rootDir` +
user-supplied relative path can never combine into a path outside the system's root.
The bridge's whole implementation of Tapis `PathUtils.getAbsolutePath(rootDir, path)`
becomes `ObjectPath.resolve(rootPath, path)`.

`PelicanUrl` is `federation + ObjectPath + query`:

```java
public final class PelicanUrl {
    public static PelicanUrl parse(String url);                       // pelican://, osdf://, stash://
    public static PelicanUrl parse(String url, URI discoveryUrl);     // for schemeless / osdf:///
    public URI discoveryUri();
    public ObjectPath path();
    public TransferHints hints();   // recursive, pack, directread, skipstat, prefercached
}
```

`TransferHints` are the query parameters Pelican already honors on URLs
(`?recursive`, `?pack=tar.gz`, `?directread`, `?skipstat`, `?prefercached`). Parsing
them in core means a Tapis system whose `rootDir` was configured as
`pelican://osg-htc.org/ns/data?directread` behaves the same as the Go CLI would.

### 4.2 The client

```java
public interface PelicanClient extends AutoCloseable {

    FederationInfo federation();

    ObjectInfo           stat(StatRequest request);
    Stream<ObjectInfo>   list(ListRequest request);
    ObjectStream         get(GetObjectRequest request);
    PutResult            put(PutObjectRequest request);
    void                 delete(DeleteRequest request);
    void                 createCollection(CreateCollectionRequest request);
    void                 move(MoveRequest request);
    CopyResult           copy(CopyRequest request);
    URI                  shareUrl(ShareRequest request);
    Capabilities         capabilities(ObjectPath path);

    // convenience overloads for the 80% case
    default ObjectInfo         stat(String path);
    default Stream<ObjectInfo> list(String path);
    default ObjectStream       get(String path);
}
```

Built as:

```java
PelicanClient client = PelicanClient.builder()
    .federation(URI.create("https://osg-htc.org"))   // or .pelicanUrl("osdf:///ns")
    .credentials(credentialProvider)
    .basePath(ObjectPath.of("/ns/tapis-root"))       // optional; the "bucket" analogue
    .httpTransport(JdkHttpTransport.defaults())
    .retryPolicy(RetryPolicy.defaults())
    .userAgent("tapis-files/26Q3.0")
    .build();
```

`basePath` makes the client behave like an `S3Client` bound to a bucket: relative
paths in requests resolve against it, absolute ones are checked to be inside it. This
is what lets the Tapis bridge stop thinking about `rootDir` after construction.

A `PelicanClient` is thread-safe and expensive-ish (it owns an HTTP connection pool
and the discovery/Director caches). Tapis should cache one per (federation,
credential-identity), the way `SystemsCache` already caches clients.

### 4.3 Value types

```java
public final class ObjectInfo {
    public ObjectPath path();
    public String     name();
    public long       size();                 // -1 if unknown
    public Optional<Instant> lastModified();
    public ObjectType type();                 // OBJECT | COLLECTION
    public Optional<String> etag();
    public Optional<String> contentType();
    public Map<DigestAlgorithm,String> digests();   // from Digest:, when asked for
    public URI servedBy();                    // which object server answered
}
```

`ObjectType` has two values, not four. Pelican has no symlinks and no "other" on the
wire, and inventing them here would only create a mapping the bridge has to undo.
The bridge maps `COLLECTION -> FileInfo.FileType.DIR`, `OBJECT -> FILE`.

```java
public final class ObjectStream extends InputStream {
    public long contentLength();              // -1 if unknown
    public Optional<String> etag();
    public Optional<Instant> lastModified();
    public Map<DigestAlgorithm,String> digests();
    public URI servedBy();
    public boolean servedByCache();
}
```

`ObjectStream extends InputStream` so `getStream()` and `getBytesByRange()` in the
bridge are one-liners, but it still carries the metadata a caller may want. It
verifies length and (optionally) digest on close — see §8.1.

```java
public final class PutResult {
    public ObjectPath path();
    public long bytesWritten();
    public Optional<String> etag();
    public Map<DigestAlgorithm,String> digests();
    public URI servedBy();
    public Optional<MetadataPublishStatus> metadataStatus();  // X-Pelican-Metadata-Status
}
```

### 4.4 Requests

All requests are immutable with builders. The fields that recur:

```java
GetObjectRequest.builder()
    .path("data/file.root")            // String -> ObjectPath.resolve(basePath, ...)
    .range(ByteRange.of(0, 65536))     // optional
    .wantDigests(DigestAlgorithm.CRC32C)
    .verifyDigest(true)
    .timeout(Duration.ofMinutes(5))    // becomes X-Pelican-Timeout
    .jobId(uuid)                       // becomes X-Pelican-JobId
    .build();

PutObjectRequest.builder()
    .path("data/file.root")
    .body(RequestBody.fromInputStream(in))       // or fromFile / fromBytes, known length
    .contentLength(len)                          // optional; absent -> chunked
    .objectMetadata(Map.of("run_number", 4172))  // X-Pelican-Object-Metadata, RFC 9651
    .build();

ListRequest.builder()
    .path("data/")
    .recursive(false)          // Depth: 1 vs a walk
    .build();

StatRequest.builder()
    .path("data/file.root")
    .forWrite(true)            // ask the Director with PUT; see §2.1
    .build();
```

`forWrite` on stat is not a nicety. Pre-flighting an upload destination has to route
through origins, not caches, and must not hand the write credential to a cache. The Go
client learned this as `WithStatUploadDestination`; encoding it in the request type
means a Java caller cannot get it wrong by accident.

## 5. Internal architecture

### 5.1 The resolution pipeline

Every public method is a thin wrapper over one internal generic:

```java
<T> T execute(Operation<T> op);
```

where `execute` runs a fixed pipeline:

1. **Resolve federation.** `PelicanUrl -> FederationInfo`, via `DiscoveryCache`
   (30-minute success TTL, 5-minute failure TTL, single-flight so a thundering herd
   of Tapis requests produces one discovery fetch).
2. **Query Director.** `DirectorRequest(path, flavor)` where `flavor` is `READ` or
   `WRITE`, derived from `op.kind()`. Redirects disabled. Retry per
   `Client.DirectorRetries` semantics, including the "response didn't come from a
   Pelican process, it's probably an ingress while the Director reboots" backoff.
   Cached in `DirectorResponseCache` keyed by `(federation, namespace prefix, flavor)`
   with a short TTL, matching Go's `client/dir_resp_cache.go`.
3. **Obtain credential.** If `X-Pelican-Namespace: require-token=true`, build a
   `CredentialRequest` from the Director's `X-Pelican-Token-Generation` /
   `X-Pelican-Authorization` plus `op.requiredScopes()`, and ask the
   `CredentialProvider` (§6). If `require-token=false`, skip entirely — public
   namespaces must not receive an `Authorization` header they did not ask for.
4. **Select and attempt.** Walk the `pri`-sorted object servers. For each, run
   `op.attempt(server, credential, transport)`. Classify the outcome:
   - success -> return
   - retryable-elsewhere (connect failure, 5xx, 429, 404-at-a-cache) -> next server
   - terminal (401/403 after a credential refresh, 409 write conflict, 405) -> throw
5. **Accumulate.** If every server fails, throw `AllServersFailedException` carrying
   an ordered `List<AttemptFailure>` (server URI, status, message, elapsed). This is
   the Java equivalent of Go's `client/errorAccum.go`, and it is the difference
   between a Tapis user seeing "connection refused" and seeing "tried 3 caches; a and
   b returned 502, c refused the token (missing storage.read:/data)".

An `Operation<T>` is:

```java
interface Operation<T> {
    OperationKind kind();                    // READ | WRITE | LIST | STAT_READ | STAT_WRITE
    ObjectPath path();
    List<ScopeRequest> requiredScopes();
    T attempt(ObjectServer server, Credential cred, HttpTransport t) throws IOException;
    Disposition classify(IOException e);     // default: standard table
}
```

Concretely: `GetOperation`, `PutOperation`, `PropfindOperation`, `MkcolOperation`,
`DeleteOperation`, `MoveOperation`, `CopyOperation`. Each is 30–60 lines. Adding
`pelican object sync` or prestage later means adding an `Operation`, not touching the
pipeline.

### 5.2 Server selection and failover nuances worth writing down

- **Writes never fan out.** A `WRITE` operation with a non-idempotent body can be
  retried against the next server only if the body is replayable
  (`RequestBody.isReplayable()` — true for file/bytes, false for a bare
  `InputStream`). Otherwise a failed attempt is terminal. This must be explicit,
  because the alternative is a silent partial upload followed by a second attempt
  against a fresh origin that starts from a half-consumed stream.
- **Cache 409 on PROPFIND.** XRootD caches answer `PROPFIND` on a collection with
  `409 Conflict`. When every server 409s, retry once against the namespace's
  `collections-url`. The fallback must be depth-bounded to one level so the two
  fallback directions cannot ping-pong — Go's `statHttpImpl(alreadyFellBack bool)`
  carries the same guard.
- **`prefercached` / `directread`** map onto which servers the Director returns; the
  client passes the hints through as query parameters rather than filtering locally.

## 6. Credentials

This is where a service integration differs most from the CLI, so it gets a real SPI
rather than a token string.

```java
public interface CredentialProvider {
    Optional<Credential> resolve(CredentialRequest request);
}

public final class CredentialRequest {
    public URI federation();
    public ObjectPath objectPath();
    public List<URI> acceptedIssuers();      // X-Pelican-Authorization
    public Optional<TokenGenerationHint> hint();  // X-Pelican-Token-Generation
    public List<ScopeRequest> scopes();      // e.g. storage.read on /data/file.root
    public boolean forceRefresh();           // set on retry after a 401/403
}

public final class ScopeRequest {
    public StorageAction action();           // READ | CREATE | MODIFY | STAGE
    public ObjectPath resource();
    public String toWlcgScope(ObjectPath basePath);   // "storage.read:/sub/file"
}
```

`toWlcgScope` is the one piece of scope arithmetic worth centralizing: the Director
reports namespace-absolute paths, the token wants paths relative to `base-path`, and
`max-scope-depth` bounds how specific the scope may be. Every caller getting this
wrong independently is the bug class; one method that takes the Director's
`base-path` and does the subtraction is the fix.

Shipped implementations:

| Provider | Use |
| --- | --- |
| `StaticCredentialProvider` | one bearer token, the test and "Tapis already has a token" case |
| `EnvironmentCredentialProvider` | `BEARER_TOKEN`, `BEARER_TOKEN_FILE`, `$XDG_RUNTIME_DIR/bt_u$UID` — parity with the Go client and HTCondor |
| `FileCredentialProvider` | a token file path, re-read on expiry |
| `ClientCredentialsProvider` | OAuth2 `client_credentials` against the issuer from `X-Pelican-Token-Generation`, with `/.well-known/openid-configuration` lookup, caching, and refresh-before-expiry |
| `ChainedCredentialProvider` | first non-empty wins |
| `CachingCredentialProvider` | decorator; caches by (issuer, scopes), refreshes at 80% of lifetime |

Explicitly **not** shipped in core: the OAuth2 device-authorization flow. It is
interactive, and a service like Tapis must never block a request on a human visiting
a URL. It belongs in `pelican-client-cli` if anywhere.

For Tapis, the expected wiring is a `CredentialProvider` that reads the token out of
the Tapis `SystemsCache` / SK credential for the obo user — i.e. the Pelican token
becomes a new `AuthnCredential` kind, the way `accessKey`/`accessSecret` are for S3.
The library does not need to know that.

Token validation before use (is it expired? does it actually carry a scope that
covers this path?) is worth porting from Go's `tokenIsAcceptable`, because sending a
knowingly-inadequate token costs a round trip and produces a worse error message.

## 7. Operation-by-operation

| Core call | Wire | Notes |
| --- | --- | --- |
| `stat` | Director(READ or WRITE) -> `PROPFIND Depth: 0` | 409-at-cache fallback to `collections-url` |
| `list` | `PROPFIND Depth: 1`, via the Director if it proxies WebDAV, else object servers | lazy `Stream`; recursive = client-side walk |
| `get` | `GET` (+ `Range`, `Want-Digest`) | streaming, digest/length verified |
| `put` | Director(WRITE) -> `PUT` | write-once; 409/412 -> `ObjectExistsException` |
| `delete` | Director(WRITE) -> `DELETE` | |
| `createCollection` | Director(WRITE) -> `MKCOL` | |
| `move` | Director(WRITE) -> `MOVE` + `Destination:` | same-namespace only |
| `copy` | Director(WRITE on dest) -> `COPY` + `Source:` + `TransferHeaderAuthorization:` | third-party copy; see below |
| `shareUrl` | Director(READ/WRITE) -> issue a scoped token | the presigned-URL analogue |
| `capabilities` | `OPTIONS` -> `Allow` / `DAV` | which verbs this origin really has |

### 7.1 Copy is the interesting one

Pelican implements WLCG pull-mode third-party copy: the client sends `COPY` to the
**destination** with `Source: <source URL>` and `TransferHeaderAuthorization: Bearer
<source token>`; the destination origin then pulls the bytes directly and streams
progress markers back on the open connection. Success is `201 Created`; a `200` means
the destination origin does not have the TPC module loaded.

This matters for Tapis beyond `IRemoteDataClient.copy` (which is intra-system).
Tapis Files' cross-system transfer machinery currently proxies bytes through the
Files service. A Pelican-to-Pelican transfer can bypass that entirely. The core API
should therefore expose copy with an explicit source that is not required to be on
the same client:

```java
CopyResult copy(CopyRequest.builder()
    .source(PelicanUrl.parse("osdf:///src/ns/file"))
    .sourceCredential(srcCred)         // forwarded as TransferHeaderAuthorization
    .destination(ObjectPath.of("/dst/ns/file"))
    .progressListener(listener)        // fed by the COPY response's progress markers
    .build());
```

`Capabilities.supportsThirdPartyCopy()` lets the caller decide whether to fall back to
proxying. Getting this into the first release is worth it even if the Tapis bridge
only wires `copy(src, dst)` to it at first.

### 7.2 Listing streams; it never buffers

A multi-status body for a collection with a million entries is hundreds of megabytes, and
WebDAV offers no way to ask for part of one. So nothing in the listing path materializes a
document:

- `MultiStatusParser` pulls one `<D:response>` at a time off a StAX reader
  (`DavEntryReader`), and `list` returns a `Stream<ObjectInfo>` over it. Memory is one
  entry, not one directory.
- The HTTP response stays open until the stream is closed, so a caller that takes the first
  fifty entries and closes stops reading the socket there. `PelicanClient.list` documents
  that the stream must be closed, and the Tapis bridge does it with try-with-resources.
- A Director that proxies `PROPFIND` (Pelican 7.9+ answers it with a 207 rather than a
  redirect) hands the client its open response rather than a buffer, so that path streams
  too — and it avoids the cache-409 dance entirely, since the Director does not have a
  cache's inability to list.
- `recursive(true)` is a lazy depth-first walk that issues one `PROPFIND` per collection
  **when the consumer reaches it**. Taking one entry from a recursive listing of a deep tree
  costs one request, not one per level. A test asserts exactly that, because the natural
  implementation descends eagerly and nothing about the result would reveal it.

The honest limit: `offset` still parses and discards the skipped entries, because WebDAV has
no server-side paging. Streaming fixes the memory cost, not the server's.

### 7.3 Listing: hierarchical, not flat

`S3DataClient.ls` returns a recursive prefix listing, because S3 has no directories.
Pelican has real collections, and its origins serve WebDAV. `list` should default to
`Depth: 1` and match what `SSHDataClient`/`IrodsDataClient` produce, so that the Tapis
Files UI tree works the way users expect. `recursive(true)` is available and is what
the bridge would use if TACC decides Pelican systems should present S3-flavored flat
listings — but that should be a deliberate choice, not an accident of implementation.

## 8. Integrity and errors

### 8.1 Integrity

Pelican origins answer `Want-Digest` (`crc32c` is the platform default; the Go client also knows
`crc32`, `md5`, and `sha` — those four are the whole set) and return `Digest:`. `ObjectStream` therefore:

- counts bytes and throws `TruncatedTransferException` on close if a known
  `Content-Length` was not reached;
- when `verifyDigest(true)`, computes the digest inline and throws
  `ChecksumMismatchException` at EOF on mismatch.

Both are checked at `close()` as well as at EOF, so a caller that closes early still
gets told the transfer was short.

### 8.2 The trailer problem — and why `HttpTransport` is an SPI

Pelican reports late-breaking failures (origin died after headers were sent) via an
`X-Transfer-Status` HTTP trailer, requested with `X-Transfer-Status: true` + `TE:
trailers`. **`java.net.http.HttpClient` does not expose response trailers.** A JDK-only
client cannot read them.

Decision: ship the JDK transport as the default and rely on length + digest
verification, which catches the same failures for any object of known length; define
`HttpTransport` as an interface so that `pelican-client-transport-apache5` (Apache
HttpClient 5 does surface trailers) can be dropped in by a deployment that wants the
richer error text. The SPI also lets Tapis substitute its own instrumented client.

```java
public interface HttpTransport extends Closeable {
    HttpResponseHandle execute(HttpRequestSpec spec);   // streaming response
    boolean supportsTrailers();
}
```

Keeping this behind an interface from day one costs one indirection and avoids a
rewrite later. It is also why `core` must not `import java.net.http` outside the
default transport package.

### 8.3 Error model

`PelicanException extends RuntimeException`, unchecked — matching the AWS SDK, and
required because `list` returns a `Stream` and checked exceptions cannot cross a
`Stream` lambda. The bridge catches and rewraps into Tapis's `IOException` /
`NotFoundException`, exactly as `S3DataClient` catches `S3Exception`.

```
PelicanException
├── FederationDiscoveryException
├── DirectorException
│   └── NoObjectServersException
├── AllServersFailedException          // carries List<AttemptFailure>
├── ObjectNotFoundException
├── AccessDeniedException              // carries issuers + the scope that was missing
├── ObjectExistsException              // write-once conflict
├── UnsupportedOperationAtOriginException   // 405/501 (e.g. MOVE at an XRootD origin)
├── ChecksumMismatchException
└── TruncatedTransferException
```

Every exception carries a `PelicanErrorCode`: the numeric code, dotted type
(`Resolution.Timeout`, `Contact.Director`, …), and `retryable` flag from
`docs/error_codes.yaml` in the Pelican repo.

**Generate `PelicanErrorCode.java` from that YAML at build time.** Hand-transcribing
it guarantees drift, and drift here means a Java client and a Go client disagreeing
about whether a failure is retryable — which is exactly the kind of bug that only
shows up in production. The same argument applies to the `X-Pelican-*` header names:
generate a `PelicanHeaders` constants class from `docs/pelican-http-headers.md`'s
source of truth (`server_structs`) rather than typing the strings twice.

## 9. The Tapis bridge

```java
public class PelicanDataClient implements IRemoteDataClient {
    private final String oboTenant, oboUser;
    private final TapisSystem system;
    private final PelicanClient client;      // from a PelicanClientCache
    private final ObjectPath rootPath;

    public List<FileInfo> ls(String path, long limit, long offset) {
        try (Stream<ObjectInfo> s = client.list(ListRequest.builder()
                    .path(rootPath.resolveString(path)).build())) {
            return s.skip(offset).limit(limit)
                    .map(this::toFileInfo)
                    .collect(toList());
        } catch (ObjectNotFoundException e) { throw new NotFoundException(msg(e)); }
          catch (PelicanException e)        { throw new IOException(msg(e), e); }
    }

    public InputStream getStream(String path) { ... client.get(...) ... }
    public InputStream getBytesByRange(String path, long start, long count) {
        ... .range(ByteRange.of(start, count)) ...      // note: count, not end byte
    }
    public void upload(String path, InputStream in) { ... }
    public void mkdir(String path)  { client.createCollection(...); }
    public void move(String s, String d) { client.move(...); }
    public void copy(String s, String d) { client.copy(...); }
    public void delete(String path) { client.delete(...); }
    public FileInfo getFileInfo(String path, boolean followLinks) {
        try { return toFileInfo(client.stat(rootPath.resolveString(path))); }
        catch (ObjectNotFoundException e) { return null; }   // the S3 client's contract
    }
}
```

Plus a `toFileInfo` mapper and a `RemoteDataClientFactory` case. That is the whole
bridge. If an implementer finds themselves parsing a header or sorting a server list
in this file, something belongs in core instead.

Three integration points on the Tapis side that are *not* ours to decide, and should
be raised with TACC early:

1. **`SystemTypeEnum.PELICAN`.** The enum is generated from the `tapis-systems` API, so
   a new system type is an upstream change in `tapis-systems` + `tapis-client-java`,
   not something `tapis-files` can do alone. Worth asking whether they would rather
   model Pelican as a new type (right answer, more work) or overload `GLOBUS`/`S3`
   (wrong answer, less work).
2. **Credential shape.** Pelican wants a bearer token or OAuth2 client credentials for
   an issuer. The natural fit is a new `AuthnCredential` field set stored in SK.
3. **`rootDir` semantics.** For Pelican, `host` is the federation discovery URL and
   `rootDir` is a namespace prefix like `/ospool/ap40/data`. There is no bucket.

## 10. Semantic mismatches to settle before coding

These are the places where Tapis's contract and Pelican's behavior genuinely differ.
Each needs a decision, not a workaround discovered at runtime:

1. **Write-once, sometimes.** Many Pelican origins reject an overwrite (`remote object
   already exists`) — but it is a backend property, not a Pelican one: an XRootD-fronted
   origin refuses, a native posixv2 origin replaces (confirmed by the conformance run).
   S3 silently overwrites, and `IRemoteDataClient.upload` is documented in S3 terms. Options: surface the error (honest, breaks caller expectations), or
   delete-then-put behind a `PutObjectRequest.overwrite(true)` flag (convenient, not
   atomic, and a failure between the two leaves nothing). Recommendation: surface the
   error by default, offer the flag, and never make the flag the default.
2. **`mkdir`.** `S3DataClient.mkdir` throws `NotImplementedException`. Pelican V2
   origins do serve `MKCOL`, XRootD-backed ones may not. `capabilities()` lets the
   bridge answer honestly per system instead of guessing.
3. **`move`.** Served by V2 origins as WebDAV `MOVE`; not universally available.
   `UnsupportedOperationAtOriginException` -> Tapis `NotSupportedException`.
4. **`ls` paging.** WebDAV has no offset/limit, so `offset`/`limit` are applied
   client-side after a full `PROPFIND`. For a collection with a million entries that
   is a real cost. Worth documenting, and worth asking whether Tapis would accept a
   cursor-based listing API later.
5. **`getFileInfo` returns null for missing paths** (the S3 contract) while
   `ls` throws `NotFoundException`. The bridge must preserve that asymmetry; core
   throws consistently and the bridge translates.
6. **Namespaces that forbid listings.** A namespace can grant reads or writes without
   `listings`. Then `stat` and `ls` simply cannot be answered, while `get`/`put` work
   fine. Tapis's UI assumes listability. This needs a product answer.

## 11. Testing

Three layers, each answering a question the others cannot.

**Unit tests** (117, no network, no XRootD) run against `testkit.FakeFederation`, an
in-process Director + origin built on the JDK's `com.sun.net.httpserver`. They cover
`Link` ordering, malformed `X-Pelican-*` headers, a 307 without `Link`, the
`require-token=false` case (asserting no `Authorization` header is sent), the cache
409-on-PROPFIND fallback, failover ordering, non-replayable bodies on write, and the
laziness of both the listing parser and the recursive walk. `ObjectPath`'s traversal
refusal is property-style tested.

Their limit is structural: the fake implements the protocol *as this library's author read
it out of the Go source*, so it validates the parsing against one reading, not against a
server.

**Conformance tests** (`@Tag("federation")`, 14 of them) answer that. `ci/start-federation.sh`
brings up a real federation and the tests run against it, concentrating on where being
wrong is plausible and silent. They are excluded from `mvn test` by default, so the
ordinary build stays hermetic and offline.

The federation runs as **three separate processes** — director+registry, origin, cache —
from one downloaded binary on a plain runner. Neither native backend starts XRootD:
`serverLaunchesXrootd()` is false for `Origin.StorageType: posixv2` and for
`Cache.EnableV2` alike.

Separate processes rather than one, because co-location hides bugs. A single-process
federation shares a config, a TLS identity, an issuer key, a hostname, a port and a
database between services that are separate everywhere else, and anything that only breaks
across a process boundary passes there and fails in production. Standing the three up
separately immediately turned up four such things (below), two of them in this client.

What that still cannot cover is XRootD's own behavior, which differs from the native
implementations in ways the client has to handle: an XRootD cache answers `PROPFIND` on a
collection with 409 where the Go cache answers 307, and it encodes `crc32c` as base64
where the IANA registry says hex. Reaching those needs the `pelican-dev` image, and is
left for a follow-up.

This requires Pelican **26.0.0-rc.0 or later**: the fix that stops a native-backend origin
from demanding an XRootD binary (`d8812204`) is not in any earlier release, so 7.26.2
cannot run it at all.

A cache also needs `Server.AdvertisementInterval` shortened. A cache can only offer a
namespace it learned from the Director, so at the 1-minute default it is not routable
until roughly 90 seconds after the origin registers; the parameter exists to be shortened
for tests, and the start script sets it to 5s and then waits for the Director to actually
advertise the cache rather than for the process to exist.

**What the first conformance run found.** Four failures, all real:

1. **Writes to a publicly-readable namespace need a credential, and the Director does not
   say so.** `require-token` describes reads; a `PublicReads` + `Writes` namespace reports
   `require-token=false` even to a `PUT`-flavored query, and then the origin returns 401.
   The client now attaches a credential to every write-flavored operation regardless, which
   is safe because a write resolves to origins rather than to every cache.
2. **Origins redirect a collection URL to its trailing-slash form.** `OPTIONS /ns/dir`
   answers 307 to `/ns/dir/`. Treating that as a failure made `capabilities()` fail against
   every real origin. The client now follows one same-scheme, same-authority redirect, and
   only with a replayable body.
3. **Write-once is a backend property, not a Pelican one.** A posixv2 origin replaced the
   object where an XRootD-fronted one refuses. §10.1 and the bridge README said otherwise;
   both were wrong and are corrected.
4. **Not every origin answers `Want-Digest`.** A posixv2 origin with no cached checksums
   reports none, and the client's "you asked for verification and did not get it" error is
   correct. The test now asserts that contract rather than assuming a digest exists.

Splitting the single process into three then found four more. Two were configuration, and
are the reason a single-process federation is not worth trusting:

- **Two services cannot share a server name.** The registry records which key owns a name.
  Co-located, origin and cache share one key and one hostname, so nothing complains; split
  apart, the cache could not prove it owned `localhost` and its registration was refused
  outright (`unable to verify you own the registered server "localhost"`). Each service now
  gets its own `Xrootd.Sitename`.
- **Several state paths default to shared locations under root** —
  `Monitoring.DataLocation` and every `*.DbLocation` land under `/var/lib/pelican`. Three
  processes then fought over one Prometheus TSDB and one `pelican.sqlite`, which surfaced
  as a lock failure, a `UNIQUE constraint failed: users.username` on the admin user each
  process self-enrolls, and — because an unhealthy cache is dropped by the Director
  (`Director.FilterCachesInErrorState` defaults to true) — a cache that registered
  successfully and was simply never routed to. All of them are now pinned per process.

The other two were client bugs:

- **`capabilities()` was read-flavored**, so it asked a *cache* what verbs it supports.
  Caches answer `OPTIONS` with 405, and every verb a caller asks about — `MKCOL`, `MOVE`,
  third-party copy — is origin-side anyway. It is write-flavored now. Co-located, the cache
  and origin were the same server, so this could not fail.
- The cache's listing redirect, below.

And the cache itself found a fifth, which the live federation could not have caught on its
own: **the native cache redirects a listing to the origin** (307) where an XRootD cache
returns 409. In a single-process test federation the redirect stays on the same authority,
so following it works by accident; in a real federation the cache is a different host and
the client refused to follow, failing the listing. Following it would be wrong — the
Director decides which hosts this client talks to, and chasing an object server's redirect
to one it never named would carry the caller's credential there — so an unfollowed redirect
now triggers the same collections-endpoint fallback as a 409. A unit test covers it with
hosts the fake federation can make differ.

It also confirmed two design decisions that would have been expensive to get wrong: a real
`Link` URL carries the origin's own path prefix (`/api/v1.0/origin/data/test/public/obj`),
which is exactly what `DirectorResponse.stripObjectPath` exists to handle so a cached answer
can be reused for siblings; and a real multi-status names the listed collection itself with a
trailing-slash href under that same prefix, which is what the self-entry matching in
`toChild` is written against.

**Still wanted**: a cross-implementation suite running the same scenarios against the Go
client and this one and diffing the observable behavior. The generated error codes cover one
axis of drift; that would cover the rest.

## 12. Suggested phasing

| Phase | Contents | Unblocks |
| --- | --- | --- |
| 1 | `ObjectPath`/`PelicanUrl`, discovery, Director query + header parsing, `HttpTransport` SPI + JDK impl, `get`/`stat`, static + env credentials, error model + generated codes | read-only Tapis system |
| 2 | `list` (PROPFIND + StAX), `put`, `delete`, `mkdir`, `move`, digest verification | full `IRemoteDataClient` |
| 3 | `ClientCredentialsProvider`, caching, token pre-validation, `capabilities` | production Tapis deployment |
| 4 | `copy`/TPC with progress, `shareUrl`, recursive walks | cross-system transfers without proxying bytes |
| 5 | Apache5 transport (trailers), CLI, conformance suite | operational parity with the Go client |

The Tapis bridge can be written against phase 2 and grow.

## 13. Open questions

1. Group/artifact coordinates and where this is published — Maven Central under
   `org.pelicanplatform`, or TACC's repo? Tapis pulls from Central plus
   `maven03.tacc.utexas.edu`.
2. Does TACC want a new `SystemTypeEnum.PELICAN` (§9.1)?
3. Is `osdf://` discovery-host hard-coding acceptable in a service, or should the
   federation always be configured explicitly on the Tapis system?
4. Java 21 is fine for Tapis. Is there a consumer who needs Java 17 or 11? That
   constrains the JDK `HttpClient` feature set only mildly, but it should be decided
   before the first release.
5. Should `shareUrl` (a Director-issued scoped token) be wired to Tapis "postits"?
   It is the closest thing Pelican has to a presigned URL and would let Tapis hand out
   direct cache URLs instead of proxying downloads.

## 14. Implementation status

What exists, builds and is covered by tests (117 of them, `./mvn.sh test`):

| Area | State |
| --- | --- |
| `ObjectPath`, `PelicanUrl`, `TransferHints` | Done. Traversal refusal is property-tested. |
| Federation discovery + cache | Done. 30-minute success TTL, 5-minute failure TTL, single-flight. |
| Director query, `X-Pelican-*` parsing, `Link` ordering | Done, including the restarting-Director retry and the `Location` fallback. |
| `DirectorResponseCache` | Done. Keyed by (federation, prefix, flavor, query, credential digest). |
| `HttpTransport` SPI + JDK transport | Done. Trailers unsupported, as designed; see §8.2. |
| Credentials: static, file, environment, chained, caching, OAuth2 client-credentials | Done. Device flow deliberately absent. |
| `stat`, `list` (lazy, recursive, collections fallback), `get` (ranges, digests) | Done. |
| `put`, `delete`, `createCollection`, `move`, `capabilities` | Done. |
| Error model, generated `PelicanErrorCode` | Done. Codes are generated from `docs/error_codes.yaml`; `codegen/refresh.sh` re-vendors. |
| Conformance against a live federation | Done: 14 tests against director + registry + origin + cache, all native backends, no XRootD and no container. Green in CI. |
| Conformance against XRootD-backed servers | **Not done.** Needs the `pelican-dev` image; it is where the 409-on-PROPFIND path and base64 `crc32c` actually live. |
| Third-party copy (`copy`, `CopyRequest`) | **Not implemented.** Phase 4. The Tapis bridge streams through the service meanwhile. |
| `shareUrl` | **Not implemented.** Phase 4. |
| Apache5 transport, CLI, cross-implementation conformance suite | **Not implemented.** Phase 5. |

Three bugs the tests caught during implementation, recorded because each is a shape that
would recur:

1. The pipeline closed a Director-proxied response while the lazy listing stream built from
   it still needed to read it. Handing an open resource to a caller needs an explicit
   ownership rule, which `Plan.ProxyAction` now states.
2. A namespace requiring a token, on a client built with no credential provider at all, sent
   an anonymous request and reported whatever the server said. It now fails immediately and
   names the missing piece.
3. The recursive walk descended into a sub-collection as soon as it *emitted* it rather than
   when the consumer asked for it, so taking one entry cost two round trips.

Also: `Map.copyOf` in `PutObjectRequest` discarded metadata insertion order, making the
`X-Pelican-Object-Metadata` header non-deterministic.
