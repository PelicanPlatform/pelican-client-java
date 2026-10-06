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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds a token the way the rest of the WLCG tooling does.
 *
 * <p>Looks, in order, at {@code BEARER_TOKEN}, then {@code BEARER_TOKEN_FILE}, then the
 * {@code bt_u<uid>} file under {@code $XDG_RUNTIME_DIR} and {@code /tmp} that the WLCG
 * bearer-token discovery convention defines.  This is what lets a process launched by
 * HTCondor, or run after {@code htgettoken}, work without being configured.
 *
 * <p>The uid for the {@code bt_u<uid>} form is read from {@code /proc/self/status}, which
 * exists on Linux and not on macOS; on platforms without it those two locations are
 * skipped and the environment variables still work.  A service that cares should configure
 * a {@link FileCredentialProvider} explicitly rather than depend on discovery.
 */
public final class EnvironmentCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentCredentialProvider.class);
    private static final Duration EXPIRY_SKEW = Duration.ofMinutes(1);

    private final Map<String, String> environment;

    public EnvironmentCredentialProvider() {
        this(System.getenv());
    }

    /** For tests: use a supplied environment instead of the process's. */
    public EnvironmentCredentialProvider(Map<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public Optional<Credential> resolve(CredentialRequest request) {
        String inline = environment.get("BEARER_TOKEN");
        if (inline != null && !inline.isBlank()) {
            Credential credential = Credential.fromJwt(inline.strip());
            if (!credential.isExpired(EXPIRY_SKEW)) {
                return Optional.of(credential);
            }
            log.debug("BEARER_TOKEN is set but expired; looking at token files instead");
        }
        for (Path candidate : candidateFiles()) {
            Optional<Credential> found = read(candidate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private List<Path> candidateFiles() {
        List<Path> paths = new ArrayList<>();
        String explicit = environment.get("BEARER_TOKEN_FILE");
        if (explicit != null && !explicit.isBlank()) {
            paths.add(Path.of(explicit.strip()));
        }
        Optional<Long> uid = currentUid();
        if (uid.isPresent()) {
            String name = "bt_u" + uid.get();
            String runtimeDir = environment.get("XDG_RUNTIME_DIR");
            if (runtimeDir != null && !runtimeDir.isBlank()) {
                paths.add(Path.of(runtimeDir, name));
            }
            paths.add(Path.of("/tmp", name));
        }
        return paths;
    }

    private Optional<Credential> read(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return Optional.empty();
            }
            String contents = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (contents.isEmpty()) {
                return Optional.empty();
            }
            Credential credential = Credential.fromJwt(contents);
            if (credential.isExpired(EXPIRY_SKEW)) {
                log.debug("Token in {} is expired", path);
                return Optional.empty();
            }
            return Optional.of(credential);
        } catch (IOException e) {
            log.debug("Could not read a candidate token file {}", path, e);
            return Optional.empty();
        }
    }

    private Optional<Long> currentUid() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isReadable(status)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("Uid:")) {
                    String[] fields = line.split("\\s+");
                    if (fields.length > 1) {
                        return Optional.of(Long.parseLong(fields[1]));
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            log.trace("Could not determine the current uid", e);
        }
        return Optional.empty();
    }
}
