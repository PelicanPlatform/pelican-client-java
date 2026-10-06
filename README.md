# pelican-client-java

A Java client for [Pelican](https://pelicanplatform.org/) data federations, and a bridge
that makes a Pelican federation usable as a [Tapis](https://tapis-project.github.io/) Files
system.

The split, in one sentence: `pelican-client-core` does what the AWS SDK does for Tapis's
`S3DataClient` — federation discovery, Director resolution, token scoping, cache failover,
WebDAV — so that the `IRemoteDataClient` implementation is a few hundred lines of parameter
marshalling.

See [DESIGN.md](DESIGN.md) for why it is shaped this way, and
[tapis-bridge/](tapis-bridge/) for the Tapis side.

## Using it

```java
try (PelicanClient client = PelicanClient.builder()
        .federation("https://osg-htc.org")
        .basePath("/ospool/ap40/data")          // acts like a bucket
        .credentials(new EnvironmentCredentialProvider())
        .userAgent("my-service/1.2")
        .build()) {

    // Read. The stream verifies length, and checksum if asked, as it is read.
    try (ObjectStream in = client.get(GetObjectRequest.builder()
            .path("samples/run42.root")
            .verifyDigest(DigestAlgorithm.CRC32C)
            .build())) {
        Files.copy(in, Path.of("run42.root"));
        System.out.println("served by " + in.servedBy());
    }

    // List. Lazy: close the stream, and a limit stops reading the socket.
    try (Stream<ObjectInfo> entries = client.list("samples")) {
        entries.limit(50).forEach(e -> System.out.println(e.name() + " " + e.size()));
    }

    // Write. Pelican origins are write-once; this throws ObjectExistsException
    // rather than silently replacing.
    client.put(PutObjectRequest.builder()
            .path("samples/new.root")
            .body(RequestBody.fromFile(Path.of("new.root")))
            .metadata("run_number", 4172L)
            .build());
}
```

A client is thread-safe and holds a connection pool plus the discovery and Director caches,
so keep one per (federation, credential identity) rather than one per operation.

Paths are never bare strings internally: a relative path is resolved against `basePath` by
`ObjectPath`, which refuses anything that would climb out of it.

## Building

No JDK or Maven needed on the host — `./mvn.sh` runs Maven in a container:

```sh
./mvn.sh test          # 117 unit tests, no network, no XRootD
./mvn.sh install
```

With a local JDK 21 and Maven, plain `mvn test` works too.

## Conformance tests

The unit tests run against an in-process fake, which checks the parsing against this
library's reading of the protocol rather than against a server. The conformance tests check
the reading:

```sh
PELICAN_WITH_CACHE=1 ci/start-federation.sh /tmp/fed.env
. /tmp/fed.env
mvn test -Dtest.excludedGroups= -Dgroups=federation
ci/stop-federation.sh
```

That brings up a director, registry, origin and cache from one downloaded binary in about
ten seconds. No XRootD and no container: both native backends (`Origin.StorageType:
posixv2` and `Cache.EnableV2`) start none. Needs Pelican 26.0.0-rc.0 or later — earlier
releases demand an XRootD binary even for a backend that never starts one.

Conformance tests are excluded from `mvn test` by default, so the ordinary build stays
offline.

## Regenerating error codes

`PelicanErrorCode` is generated from `docs/error_codes.yaml` in the
[Pelican repo](https://github.com/PelicanPlatform/pelican), so the Java and Go clients
cannot drift on what a code means or on whether a failure is retryable:

```sh
codegen/refresh.sh ../pelican
```

## Status

Phases 1–3 of [DESIGN.md](DESIGN.md) are implemented: discovery, Director resolution,
credentials, `stat` / `list` / `get` / `put` / `delete` / `mkcol` / `move` / `capabilities`.
Third-party copy and sharing URLs are not yet implemented. See
[Implementation status](DESIGN.md#14-implementation-status).
