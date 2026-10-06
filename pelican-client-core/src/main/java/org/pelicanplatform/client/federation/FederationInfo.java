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

package org.pelicanplatform.client.federation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The contents of a federation's {@code /.well-known/pelican-configuration}.
 *
 * <p>Unknown fields are ignored so that a federation running a newer Pelican than this
 * client does not break it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FederationInfo {

    private final URI discoveryEndpoint;
    private final URI directorEndpoint;
    private final List<URI> directorAdvertiseEndpoints;
    private final URI registryEndpoint;
    private final URI jwksUri;
    private final URI brokerEndpoint;

    public FederationInfo(
            @JsonProperty("discovery_endpoint") URI discoveryEndpoint,
            @JsonProperty("director_endpoint") URI directorEndpoint,
            @JsonProperty("director_advertise_endpoints") List<URI> directorAdvertiseEndpoints,
            @JsonProperty("namespace_registration_endpoint") URI registryEndpoint,
            @JsonProperty("jwks_uri") URI jwksUri,
            @JsonProperty("broker_endpoint") URI brokerEndpoint) {
        this.discoveryEndpoint = discoveryEndpoint;
        this.directorEndpoint = directorEndpoint;
        this.directorAdvertiseEndpoints =
                directorAdvertiseEndpoints == null ? List.of() : List.copyOf(directorAdvertiseEndpoints);
        this.registryEndpoint = registryEndpoint;
        this.jwksUri = jwksUri;
        this.brokerEndpoint = brokerEndpoint;
    }

    public Optional<URI> discoveryEndpoint() {
        return Optional.ofNullable(discoveryEndpoint);
    }

    /** Where object requests are resolved. The only endpoint this client strictly needs. */
    public URI directorEndpoint() {
        return directorEndpoint;
    }

    public List<URI> directorAdvertiseEndpoints() {
        return directorAdvertiseEndpoints;
    }

    public Optional<URI> registryEndpoint() {
        return Optional.ofNullable(registryEndpoint);
    }

    public Optional<URI> jwksUri() {
        return Optional.ofNullable(jwksUri);
    }

    public Optional<URI> brokerEndpoint() {
        return Optional.ofNullable(brokerEndpoint);
    }

    @Override
    public String toString() {
        return "FederationInfo[director=" + directorEndpoint + "]";
    }
}
