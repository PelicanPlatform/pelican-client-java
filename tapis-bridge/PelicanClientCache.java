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

package edu.utexas.tacc.tapis.files.lib.caches;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;

import org.pelicanplatform.client.PelicanClient;
import org.pelicanplatform.client.auth.Credential;
import org.pelicanplatform.client.auth.CredentialProvider;
import org.pelicanplatform.client.http.JdkHttpTransport;
import org.pelicanplatform.client.http.HttpTransport;

import edu.utexas.tacc.tapis.systems.client.gen.model.TapisSystem;

/**
 * One {@link PelicanClient} per (tenant, user, system).
 *
 * <p>A client is worth reusing: it owns an HTTP connection pool and the federation-discovery
 * and Director caches, which is where most of the saving is. A federation of a hundred
 * objects in one namespace costs one discovery fetch and one Director query if the client is
 * shared, and two hundred of each if it is not.
 *
 * <p>The HTTP transport is shared across every client here, so connection pooling spans
 * systems that live in the same federation.
 */
@Singleton
@Named
public class PelicanClientCache
{
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final HttpTransport transport = JdkHttpTransport.defaults();
  private final ConcurrentMap<Key, PelicanClient> clients = new ConcurrentHashMap<>();

  private record Key(String tenant, String user, String systemId, String credentialTag) {}

  public PelicanClient getClient(String oboTenant, String oboUser, TapisSystem system)
  {
    String token = accessToken(system).orElse("");
    Key key = new Key(oboTenant, oboUser, system.getId(), Integer.toHexString(token.hashCode()));
    return clients.computeIfAbsent(key, ignored -> build(system, token));
  }

  private PelicanClient build(TapisSystem system, String token)
  {
    // host is a federation discovery URL, not a server: "https://osg-htc.org" or "osdf:///".
    URI federation = URI.create(system.getHost());
    String rootDir = StringUtils.isBlank(system.getRootDir()) ? "/" : system.getRootDir();

    CredentialProvider credentials =
            token.isEmpty()
                    ? null
                    : new CachingTokenProvider(token);

    return PelicanClient.builder()
            .federation(federation)
            .basePath(rootDir)
            .credentials(credentials)
            .timeout(TIMEOUT)
            .userAgent("tapis-files")
            .build();
  }

  private static Optional<String> accessToken(TapisSystem system)
  {
    if (system.getAuthnCredential() == null) return Optional.empty();
    return Optional.ofNullable(system.getAuthnCredential().getAccessToken())
            .filter(StringUtils::isNotBlank);
  }

  /**
   * The system's stored token.
   *
   * <p>Declines on a forced refresh rather than handing back the same token: the client asks
   * for a refresh only when that exact token has just been rejected, and offering it again
   * would make it retry an identical request and report the same failure twice. A future
   * version that can re-fetch from SK should do so here instead.
   */
  private record CachingTokenProvider(String token) implements CredentialProvider
  {
    @Override
    public Optional<Credential> resolve(org.pelicanplatform.client.auth.CredentialRequest request)
    {
      if (request.forceRefresh()) return Optional.empty();
      return Optional.of(Credential.fromJwt(token));
    }
  }

  /** Drop a cached client, e.g. after its credential is rotated. */
  public void invalidate(String oboTenant, String oboUser, String systemId)
  {
    clients.keySet().removeIf(
            k -> Objects.equals(k.tenant(), oboTenant)
                 && Objects.equals(k.user(), oboUser)
                 && Objects.equals(k.systemId(), systemId));
  }
}
