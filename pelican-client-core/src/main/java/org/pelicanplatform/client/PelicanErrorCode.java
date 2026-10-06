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

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Client-visible Pelican error codes.
 *
 * <p>GENERATED FILE -- DO NOT EDIT.  Produced by {@code codegen/generate_error_codes.py}
 * from {@code docs/error_codes.yaml} in the PelicanPlatform/pelican repository, so that
 * this client and the Go client cannot drift on what a code means or on whether a
 * failure is retryable.  Run {@code codegen/refresh.sh} to regenerate.
 */
public enum PelicanErrorCode {
    /**
     * If the client failed to start, or was started with invalid parameters, or otherwise believes
     * what it was asked to do is impossible or the request itself is malformed.
     */
    PARAMETER("Parameter", 1000, 4, false),
    /**
     * If the client was started with a file that does not exist.
     */
    PARAMETER_FILE_NOT_FOUND("Parameter.FileNotFound", 1011, 4, false),
    /**
     * Indicates that the client failed to even attempt to contact the server.
     */
    RESOLUTION("Resolution", 2000, 5, false),
    /**
     * The client timed out while querying for federation metadata during discovery. This could be a
     * DNS timeout, a dial timeout, or a header timeout. This is often a transient network error
     * that can be resolved by retrying.
     */
    RESOLUTION_TIMEOUT("Resolution.Timeout", 2001, 5, true),
    /**
     * The client failed to connect while querying for federation metadata during discovery. This
     * could be a network unreachable error, no route to host, proxy connection refused, or DNS
     * connection refused. This is often a transient network error that can be resolved by retrying.
     */
    RESOLUTION_CONNECTION_FAILURE("Resolution.ConnectionFailure", 2002, 5, true),
    /**
     * The client attempted to contact the server at its resolved address and failed to do so.
     */
    CONTACT("Contact", 3000, 6, false),
    /**
     * The client attempted to contact the director at its found address but failed to do so.
     */
    CONTACT_DIRECTOR("Contact.Director", 3001, 6, false),
    /**
     * The client attempted to contact the cache but failed to do so.
     */
    CONTACT_CACHE("Contact.Cache", 3002, 11, true),
    /**
     * The client attempted to contact the origin but failed to do so.
     */
    CONTACT_ORIGIN("Contact.Origin", 3003, 6, false),
    /**
     * The client attempted to contact the registry (usually through the director) but failed to do
     * so.
     */
    CONTACT_REGISTRY("Contact.Registry", 3004, 6, false),
    /**
     * The client attempted to contact a server but the connection was reset by the remote peer.
     * This is often a transient network error that can be resolved by retrying.
     */
    CONTACT_CONNECTION_RESET("Contact.ConnectionReset", 3005, 6, true),
    /**
     * The client attempted to establish a connection to the server but failed before the request
     * could be completed.
     */
    CONTACT_CONNECTION_SETUP("Contact.ConnectionSetup", 3006, 6, true),
    /**
     * The client contacted the server but failed to authenticate, or failed to authorize, or if the
     * server replied with an authorization error when the file was requested or sent.
     */
    AUTHORIZATION("Authorization", 4000, 7, false),
    /**
     * The client requires a credential or token for the transfer but none was discovered or could
     * be generated. The user may need to provide credentials or configure token acquisition.
     */
    AUTHORIZATION_TOKEN_NOT_FOUND("Authorization.TokenNotFound", 4010, 7, false),
    /**
     * If the client successfully contacted the server and received a definitive response that the
     * desired file was not present or could not be created. Usually the submitters fault.
     */
    SPECIFICATION("Specification", 5000, 8, false),
    /**
     * If the client successfully contacted the server but the desired file does not exist for
     * download. The user might have entered the wrong URL or the file might not yet be at the
     * specified origin.
     */
    SPECIFICATION_FILE_NOT_FOUND("Specification.FileNotFound", 5011, 8, false),
    /**
     * If the client successfully contacted the server but the desired file for upload could not be
     * created.
     */
    SPECIFICATION_FILE_NOT_CREATED("Specification.FileNotCreated", 5002, 8, false),
    /**
     * If the client attempted to upload a file but the remote object already exists at the
     * destination and overwrites are not enabled.
     */
    SPECIFICATION_FILE_ALREADY_EXISTS("Specification.FileAlreadyExists", 5012, 8, false),
    /**
     * The client started transferring the file but did not complete it for some reason, or if the
     * file failed post-transfer validation.
     */
    TRANSFER("Transfer", 6000, 9, true),
    /**
     * The client started transferring file(s) but it got cancelled by Pelican as stopped
     * transferring data.
     */
    TRANSFER_STOPPED_TRANSFER("Transfer.StoppedTransfer", 6001, 9, true),
    /**
     * The client started transferring data but the transfer was slower than the minimum configured
     * timeout rate.
     */
    TRANSFER_SLOW_TRANSFER("Transfer.SlowTransfer", 6002, 9, true),
    /**
     * The client started transferring data but the transfer timed out.
     */
    TRANSFER_TIMED_OUT("Transfer.TimedOut", 6003, 9, true),
    /**
     * The client attempted to contact the server but timed out waiting for response headers. This
     * indicates the server did not respond before the header timeout threshold.
     */
    TRANSFER_HEADER_TIMEOUT("Transfer.HeaderTimeout", 6004, 9, true),
    /**
     * The client timed out while querying the director for namespace information. This indicates
     * the director did not respond before the timeout threshold.
     */
    TRANSFER_DIRECTOR_TIMEOUT("Transfer.DirectorTimeout", 6005, 9, true),
    /**
     * The client successfully transferred the file but the checksum computed by the client did not
     * match the checksum reported by the server.
     */
    TRANSFER_CHECKSUM_MISMATCH("Transfer.ChecksumMismatch", 6006, 9, true),
    /**
     * The client required checksum verification but the server did not provide any checksum
     * information or only provided unsupported algorithms.
     */
    TRANSFER_CHECKSUM_MISSING("Transfer.ChecksumMissing", 6007, 9, true),
    /**
     * A cache refused to admit the upstream fetch because the origin has accepted connections but
     * is not delivering data (the origin appears unresponsive). The cache shed the request to keep
     * its worker pool available for healthy origins. The Retry-After hint is surfaced on the
     * client's typed throttle error for external retriers to honor; the Pelican client itself does
     * not sleep on it.
     */
    TRANSFER_ORIGIN_UNRESPONSIVE("Transfer.OriginUnresponsive", 6008, 9, true),
    /**
     * A cache refused to admit the upstream fetch because the origin is transferring data but is
     * already holding its fair share of the cache's worker pool. The cache shed the request to keep
     * the pool available for other origins. The Retry-After hint is surfaced on the client's typed
     * throttle error for external retriers to honor; the Pelican client itself does not sleep on
     * it.
     */
    TRANSFER_ORIGIN_SLOW("Transfer.OriginSlow", 6009, 9, true),
    /**
     * A server rejected the request because it was at capacity. When a cache's fair scheduler
     * reports this reason, its global pending buffer was full and the cache is saturated across all
     * the origins it serves rather than being held up by any single one of them. This is also the
     * generic classification for a 429 that carries no more specific reason, including one from a
     * Pelican service other than a cache. The Retry-After hint is surfaced on the client's typed
     * throttle error for external retriers to honor; the Pelican client itself does not sleep on
     * it.
     */
    TRANSFER_CACHE_OVERLOADED("Transfer.CacheOverloaded", 6010, 9, true);

    private static final Map<Integer, PelicanErrorCode> BY_CODE =
            Stream.of(values())
                    .collect(Collectors.toUnmodifiableMap(PelicanErrorCode::code, Function.identity()));

    private static final Map<String, PelicanErrorCode> BY_TYPE =
            Stream.of(values())
                    .collect(Collectors.toUnmodifiableMap(PelicanErrorCode::type, Function.identity()));

    private final String type;
    private final int code;
    private final int clientExitCode;
    private final boolean retryable;

    PelicanErrorCode(String type, int code, int clientExitCode, boolean retryable) {
        this.type = type;
        this.code = code;
        this.clientExitCode = clientExitCode;
        this.retryable = retryable;
    }

    /** The dotted type name, e.g. {@code Transfer.DirectorTimeout}. */
    public String type() {
        return type;
    }

    /** The numeric code shared with the Go client. */
    public int code() {
        return code;
    }

    /** The process exit code the CLI would use for this failure. */
    public int clientExitCode() {
        return clientExitCode;
    }

    /** Whether re-running the whole operation could plausibly succeed. */
    public boolean retryable() {
        return retryable;
    }

    public static Optional<PelicanErrorCode> byCode(int code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    public static Optional<PelicanErrorCode> byType(String type) {
        return Optional.ofNullable(BY_TYPE.get(type));
    }
}
