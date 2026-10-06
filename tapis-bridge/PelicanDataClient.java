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

package edu.utexas.tacc.tapis.files.lib.clients;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.NotSupportedException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.pelicanplatform.client.Capabilities;
import org.pelicanplatform.client.CreateCollectionRequest;
import org.pelicanplatform.client.DeleteRequest;
import org.pelicanplatform.client.GetObjectRequest;
import org.pelicanplatform.client.ListRequest;
import org.pelicanplatform.client.MoveRequest;
import org.pelicanplatform.client.ObjectExistsException;
import org.pelicanplatform.client.ObjectInfo;
import org.pelicanplatform.client.ObjectNotFoundException;
import org.pelicanplatform.client.ObjectPath;
import org.pelicanplatform.client.ObjectStream;
import org.pelicanplatform.client.PelicanClient;
import org.pelicanplatform.client.PelicanException;
import org.pelicanplatform.client.StatRequest;
import org.pelicanplatform.client.UnsupportedOperationAtOriginException;
import org.pelicanplatform.client.http.RequestBody;

import edu.utexas.tacc.tapis.files.lib.models.FileInfo;
import edu.utexas.tacc.tapis.files.lib.utils.LibUtils;
import edu.utexas.tacc.tapis.systems.client.gen.model.SystemTypeEnum;
import edu.utexas.tacc.tapis.systems.client.gen.model.TapisSystem;

/**
 * Tapis file operations against a Pelican data federation.
 *
 * <p>This class is a bridge and nothing more.  Everything that makes Pelican a federation
 * rather than a server -- discovering the federation's endpoints, asking the Director which
 * caches or origins can serve a path, scoping and attaching a token, failing over between
 * servers, WebDAV -- lives in {@code pelican-client-core}, the way SigV4 and bucket
 * addressing live in the AWS SDK rather than in {@link S3DataClient}.  If a change to this
 * file involves parsing a header or ordering a server list, it belongs in the core library.
 *
 * <p>Mapping from a Tapis system:
 *
 * <ul>
 *   <li>{@code host} is the federation's discovery URL ({@code https://osg-htc.org}) or a
 *       Pelican URL ({@code osdf:///}). It is not a server to connect to.
 *   <li>{@code rootDir} is a namespace prefix such as {@code /ospool/ap40/project}. There is
 *       no bucket.
 *   <li>{@code authnCredential.accessToken} is a WLCG/SciTokens bearer token for the
 *       namespace, if it requires one. Public namespaces need no credential.
 * </ul>
 *
 * <p>Two behaviors differ from the S3 client and are visible to callers:
 *
 * <ul>
 *   <li><b>Writes may be write-once.</b> Many Pelican origins refuse a {@code PUT} over an
 *       object that already exists, where S3 silently replaces it; whether a given origin
 *       does depends on its storage backend (XRootD-fronted origins refuse, native posixv2
 *       origins replace). {@link #upload} surfaces a refusal as an {@link IOException}
 *       rather than quietly deleting and rewriting, because a delete followed by a write is
 *       not atomic and a failure between the two loses the original.
 *   <li><b>Listings are hierarchical.</b> {@link #ls} returns one level, like the SSH and
 *       iRODS clients, because Pelican namespaces are real hierarchies. S3's flat prefix
 *       listing is a consequence of S3 having no directories.
 * </ul>
 */
public class PelicanDataClient implements IRemoteDataClient
{
  private static final Logger log = LoggerFactory.getLogger(PelicanDataClient.class);

  private final String oboTenant;
  private final String oboUser;
  private final TapisSystem system;
  private final PelicanClient client;
  private final ObjectPath rootPath;

  @Override
  public String getOboTenant() { return oboTenant; }
  @Override
  public String getOboUser() { return oboUser; }
  @Override
  public String getSystemId() { return system.getId(); }
  @Override
  public SystemTypeEnum getSystemType() { return system.getSystemType(); }
  @Override
  public TapisSystem getSystem() { return system; }

  public PelicanClient getClient() { return client; }

  public PelicanDataClient(@NotNull String oboTenant1, @NotNull String oboUser1,
                           @NotNull TapisSystem system1, @NotNull PelicanClientCache clientCache)
          throws IOException
  {
    oboTenant = oboTenant1;
    oboUser = oboUser1;
    system = system1;
    try
    {
      client = clientCache.getClient(oboTenant, oboUser, system);
      rootPath = client.basePath();
    }
    catch (PelicanException e)
    {
      throw new IOException(msg("build a client", "", e), e);
    }
  }

  /* **************************************************************************** */
  /*                                Public Methods                                */
  /* **************************************************************************** */

  @Override
  public List<FileInfo> ls(@NotNull String path) throws NotFoundException, IOException
  {
    return ls(path, Long.MAX_VALUE, 0);
  }

  @Override
  public List<FileInfo> ls(@NotNull String path, long limit, long offset)
          throws NotFoundException, IOException
  {
    return ls(path, limit, offset, null);
  }

  /**
   * One level of a collection, with Tapis's limit/offset applied to the stream.
   *
   * <p>The core listing is lazy: entries arrive from the origin as it produces them, and
   * {@code limit} stops reading the socket rather than discarding a listing already in
   * memory.  WebDAV has no server-side paging, so {@code offset} entries are still parsed
   * and thrown away -- but they are never all held at once, which is what matters for a
   * collection with a million objects.
   */
  @Override
  public List<FileInfo> ls(@NotNull String path, long limit, long offset, String regex)
          throws NotFoundException, IOException
  {
    Pattern pattern = compile(regex);
    try (Stream<ObjectInfo> entries =
                 client.list(ListRequest.builder().path(resolve(path)).build()))
    {
      Stream<ObjectInfo> filtered = entries;
      if (pattern != null) filtered = filtered.filter(e -> pattern.matcher(e.name()).matches());
      List<FileInfo> out = new ArrayList<>();
      filtered.sorted(Comparator.comparing(e -> e.path().value()))
              .skip(offset)
              .limit(limit)
              .forEach(e -> out.add(toFileInfo(e)));
      return out;
    }
    catch (ObjectNotFoundException e) { throw new NotFoundException(msg("list", path, e)); }
    catch (PelicanException e) { throw new IOException(msg("list", path, e), e); }
  }

  /**
   * Upload, spooling the stream to a temporary file first.
   *
   * <p>The spool is not just convenience (it is what {@link S3DataClient#upload} already
   * does): a body that can be produced only once cannot be retried against another origin,
   * so streaming straight through would give up the federation's failover on every upload.
   */
  @Override
  public void upload(@NotNull String path, @NotNull InputStream fileStream) throws IOException
  {
    File scratchFile = File.createTempFile(UUID.randomUUID().toString(), ".tmp");
    try
    {
      FileUtils.copyInputStreamToFile(fileStream, scratchFile);
      client.put(org.pelicanplatform.client.PutObjectRequest.builder()
                         .path(resolve(path))
                         .body(RequestBody.fromFile(scratchFile.toPath()))
                         .build());
    }
    catch (ObjectExistsException e)
    {
      // This origin refuses to replace an object. Saying so plainly beats a delete-then-write
      // that can lose the original if it fails in between.
      throw new IOException(msg("upload", path, e)
                            + " -- this origin does not allow an object to be replaced;"
                            + " delete it first if that is what you intend", e);
    }
    catch (PelicanException e) { throw new IOException(msg("upload", path, e), e); }
    finally
    {
      Files.deleteIfExists(scratchFile.toPath());
    }
  }

  /**
   * Create a collection, and any missing parents.
   *
   * <p>WebDAV {@code MKCOL} creates exactly one level, while Tapis callers expect
   * {@code mkdir -p}. The walk stops at the first level that already exists.
   */
  @Override
  public void mkdir(@NotNull String path) throws IOException
  {
    ObjectPath target = resolve(path);
    List<ObjectPath> missing = new ArrayList<>();
    for (ObjectPath current = target;
         current.startsWith(rootPath) && !current.equals(rootPath);
         current = current.parent())
    {
      if (client.exists(current.value())) break;
      missing.add(0, current);
    }
    try
    {
      for (ObjectPath create : missing)
      {
        client.createCollection(CreateCollectionRequest.builder().path(create).build());
      }
    }
    catch (UnsupportedOperationAtOriginException e)
    {
      throw new NotSupportedException(msg("mkdir", path, e));
    }
    catch (PelicanException e) { throw new IOException(msg("mkdir", path, e), e); }
  }

  @Override
  public void move(@NotNull String srcPath, @NotNull String dstPath)
          throws NotFoundException, IOException
  {
    try
    {
      client.move(MoveRequest.builder()
                          .path(resolve(srcPath))
                          .destination(resolve(dstPath))
                          .build());
    }
    catch (ObjectNotFoundException e) { throw new NotFoundException(msg("move", srcPath, e)); }
    catch (UnsupportedOperationAtOriginException e)
    {
      throw new NotSupportedException(msg("move", srcPath, e));
    }
    catch (PelicanException e) { throw new IOException(msg("move", srcPath, e), e); }
  }

  /**
   * Copy within the system, by reading and rewriting.
   *
   * <p>Pelican's {@code COPY} is third-party copy: it asks the destination origin to pull
   * from a source URL, which is how a federation moves bytes without a middleman. Until the
   * core library exposes it, this streams through the Files service, which is what the
   * other clients do anyway. When it lands, this method becomes a one-liner and the bytes
   * stop passing through here at all.
   */
  @Override
  public void copy(@NotNull String srcPath, @NotNull String dstPath)
          throws NotFoundException, IOException
  {
    try (ObjectStream source = client.get(GetObjectRequest.builder().path(resolve(srcPath)).build()))
    {
      upload(dstPath, source);
    }
    catch (ObjectNotFoundException e) { throw new NotFoundException(msg("copy", srcPath, e)); }
    catch (PelicanException e) { throw new IOException(msg("copy", srcPath, e), e); }
  }

  /**
   * Delete an object, or a collection and everything under it.
   *
   * <p>The recursive walk is client-side: WebDAV {@code DELETE} on a collection is allowed
   * to be recursive but Pelican origins do not promise it, and a caller that asked to remove
   * a directory should not have to find out which.
   */
  @Override
  public void delete(@NotNull String path) throws NotFoundException, NotSupportedException, IOException
  {
    ObjectPath target = resolve(path);
    try
    {
      FileInfo info = getFileInfo(path, true);
      if (info == null) throw new NotFoundException(msg("delete", path, null));
      if (info.isDir())
      {
        List<ObjectPath> toRemove = new ArrayList<>();
        try (Stream<ObjectInfo> entries =
                     client.list(ListRequest.builder().path(target).recursive(true).build()))
        {
          entries.forEach(e -> toRemove.add(e.path()));
        }
        // Deepest first, so a collection is empty by the time it is removed.
        toRemove.sort(Comparator.comparingInt((ObjectPath p) -> p.value().length()).reversed());
        for (ObjectPath child : toRemove)
        {
          client.delete(DeleteRequest.builder().path(child).build());
        }
      }
      client.delete(DeleteRequest.builder().path(target).build());
    }
    catch (ObjectNotFoundException e) { throw new NotFoundException(msg("delete", path, e)); }
    catch (PelicanException e) { throw new IOException(msg("delete", path, e), e); }
  }

  /**
   * File info, or null when nothing is there.
   *
   * <p>Null rather than an exception, matching {@link S3DataClient#getFileInfo}. Pelican has
   * no symlinks, so {@code followLinks} has nothing to act on.
   */
  @Override
  public FileInfo getFileInfo(@NotNull String path, boolean followLinks) throws IOException
  {
    try
    {
      return toFileInfo(client.stat(StatRequest.builder().path(resolve(path)).build()));
    }
    catch (ObjectNotFoundException e) { return null; }
    catch (PelicanException e) { throw new IOException(msg("stat", path, e), e); }
  }

  @Override
  public InputStream getStream(@NotNull String path) throws IOException
  {
    try
    {
      return client.get(GetObjectRequest.builder().path(resolve(path)).build());
    }
    catch (ObjectNotFoundException e) { throw new NotFoundException(msg("read", path, e)); }
    catch (PelicanException e) { throw new IOException(msg("read", path, e), e); }
  }

  @Override
  public InputStream getBytesByRange(@NotNull String path, long startByte, long count)
          throws IOException
  {
    try
    {
      return client.get(GetObjectRequest.builder()
                                .path(resolve(path))
                                .range(org.pelicanplatform.client.ByteRange.of(startByte, count))
                                .build());
    }
    catch (ObjectNotFoundException e) { throw new NotFoundException(msg("read", path, e)); }
    catch (PelicanException e) { throw new IOException(msg("read", path, e), e); }
  }

  /**
   * Which WebDAV verbs the origin behind a path actually serves.
   *
   * <p>Not part of {@link IRemoteDataClient}, but worth exposing: whether {@code mkdir} and
   * {@code move} work depends on the origin's storage backend, and a UI that knows can grey
   * out a button instead of offering one that returns 405.
   */
  public Capabilities getCapabilities(@NotNull String path) throws IOException
  {
    try { return client.capabilities(resolve(path)); }
    catch (PelicanException e) { throw new IOException(msg("query capabilities of", path, e), e); }
  }

  /* **************************************************************************** */
  /*                                Private Methods                               */
  /* **************************************************************************** */

  /**
   * Resolve a Tapis-relative path against the system's rootDir.
   *
   * <p>All of Tapis's {@code PathUtils.getAbsolutePath} in one call, and safer: {@link
   * ObjectPath#resolve} refuses a path that would climb out of the root rather than joining
   * it, so no combination of {@code ..} in a user-supplied path can reach another namespace.
   */
  private ObjectPath resolve(String path)
  {
    return ObjectPath.resolve(rootPath, StringUtils.isBlank(path) ? "" : path);
  }

  private FileInfo toFileInfo(ObjectInfo info)
  {
    FileInfo out = new FileInfo();
    out.setName(info.name());
    out.setPath(info.path().relativeTo(rootPath));
    out.setSize(info.size() < 0 ? 0L : info.size());
    out.setType(info.isCollection() ? FileInfo.FileType.DIR : FileInfo.FileType.FILE);
    info.lastModified().ifPresent(out::setLastModified);
    info.contentType().ifPresent(out::setMimeType);
    out.setUrl(String.format("tapis://%s/%s", system.getId(), info.path().relativeTo(rootPath)));
    return out;
  }

  private static Pattern compile(String regex)
  {
    if (StringUtils.isBlank(regex)) return null;
    String expression = StringUtils.removeStart(regex, IRemoteDataClient.REGEX_PREFIX);
    return Pattern.compile(expression);
  }

  private String msg(String operation, String path, Exception e)
  {
    return LibUtils.getMsg("FILES_CLIENT_PELICAN_ERR", oboTenant, oboUser, system.getId(),
                           operation, path, e == null ? "not found" : e.getMessage());
  }
}
