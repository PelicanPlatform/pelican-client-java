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

package org.pelicanplatform.client.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a bearer token from a file, re-reading it when it expires.
 *
 * <p>The re-read is the point: token files are routinely refreshed in place by an agent
 * (HTCondor, {@code htgettoken}, a sidecar), and a client that cached the first read would
 * start failing after the first lifetime for no visible reason.
 */
public final class FileCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(FileCredentialProvider.class);
    private static final Duration EXPIRY_SKEW = Duration.ofMinutes(1);

    private final Path path;
    private volatile Credential cached;
    private volatile long cachedModifiedTime = -1;

    public FileCredentialProvider(Path path) {
        this.path = path;
    }

    @Override
    public Optional<Credential> resolve(CredentialRequest request) {
        try {
            if (!Files.isReadable(path)) {
                return Optional.empty();
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            Credential current = cached;
            boolean stale =
                    current == null
                            || modified != cachedModifiedTime
                            || current.isExpired(EXPIRY_SKEW)
                            || request.forceRefresh();
            if (stale) {
                String contents = Files.readString(path, StandardCharsets.UTF_8).strip();
                if (contents.isEmpty()) {
                    return Optional.empty();
                }
                current = Credential.fromJwt(contents);
                cached = current;
                cachedModifiedTime = modified;
            }
            if (current.isExpired(Duration.ZERO)) {
                log.debug("Token in {} is expired", path);
                return Optional.empty();
            }
            return Optional.of(current);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the token file " + path, e);
        }
    }

    @Override
    public String toString() {
        return "FileCredentialProvider[" + path + "]";
    }
}
