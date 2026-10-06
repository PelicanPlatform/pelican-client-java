# Tapis bridge

Reference sources for adding Pelican federations to
[tapis-files](https://github.com/tapis-project/tapis-files) as a new
`IRemoteDataClient`.

**These files are not built by this repository.** `IRemoteDataClient` and `FileInfo` live in
`tapis-files` itself, which is not published to Maven Central, so the bridge can only be
compiled inside that repo. They are kept here so the core library's API can be reviewed
against its intended consumer, and so that "the bridge is small" is a claim you can check
rather than take on faith.

## Where they go

| File | Destination in `tapis-files` |
| --- | --- |
| `PelicanDataClient.java` | `lib/src/main/java/edu/utexas/tacc/tapis/files/lib/clients/` |
| `PelicanClientCache.java` | `lib/src/main/java/edu/utexas/tacc/tapis/files/lib/caches/` |

## What else has to change in tapis-files

1. **Dependency** in `lib/pom.xml`:

   ```xml
   <dependency>
     <groupId>org.pelicanplatform</groupId>
     <artifactId>pelican-client-core</artifactId>
     <version>0.1.0</version>
   </dependency>
   ```

2. **`RemoteDataClientFactory`** gains a case:

   ```java
   else if (SystemTypeEnum.PELICAN.equals(system.getSystemType()))
   {
     return new PelicanDataClient(oboTenant, oboUser, system, pelicanClientCache);
   }
   ```

   with `@Inject PelicanClientCache pelicanClientCache;` alongside the existing `SystemsCache`.

3. **A message key** `FILES_CLIENT_PELICAN_ERR` in the `FilesMessages` bundle, taking
   (tenant, user, systemId, operation, path, detail).

## What is not ours to decide

- **`SystemTypeEnum.PELICAN`.** The enum is generated from the `tapis-systems` API, so a new
  system type is a change in `tapis-systems` and `tapis-client-java`, not something
  `tapis-files` can make alone. Modelling Pelican as `GLOBUS` or `S3` instead would be less
  work and wrong: the credential shape, the path model and the listing semantics all differ.
- **Credential shape.** The bridge reads `authnCredential.accessToken`, following
  `GlobusDataClient`. If TACC would rather store an OAuth2 client id and secret and have the
  client obtain tokens itself, `pelican-client-core` already has
  `ClientCredentialsProvider` for it and the change is confined to `PelicanClientCache`.
- **System field meanings.** `host` is a federation discovery URL and `rootDir` is a
  namespace prefix. There is no bucket, no port, and `host` is not a server anything
  connects to.

## Behaviors worth agreeing on before this ships

| | Pelican | What the S3 client does | What the bridge does |
| --- | --- | --- | --- |
| Overwriting an object | Refused by some backends (XRootD-fronted), allowed by others (posixv2) | Silently replaces | Throws where the origin refuses, and says why |
| `mkdir` | `MKCOL`, one level, not on every origin | Unimplemented | Creates missing parents; `NotSupportedException` where the origin has no `MKCOL` |
| `ls` | Real hierarchy, one level | Flat prefix listing | One level, like the SSH and iRODS clients |
| `ls` paging | WebDAV has no server-side paging | S3 paginator | Lazy stream, `skip`/`limit` applied as entries arrive |
| `copy` | Native third-party copy (bytes never touch Files) | Server-side `CopyObject` | Streams through Files for now; becomes a one-liner when core exposes TPC |
| Namespaces without `listings` | `get`/`put` work, `ls`/`stat` cannot be answered | n/a | Surfaces the error; the Files UI assumes listability, which needs a product answer |
