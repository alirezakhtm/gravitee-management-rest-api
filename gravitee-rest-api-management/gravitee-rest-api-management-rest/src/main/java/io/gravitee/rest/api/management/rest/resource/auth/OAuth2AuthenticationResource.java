/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.management.rest.resource.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.gravitee.common.http.MediaType;
import io.gravitee.el.spel.function.JsonPathFunction;
import io.gravitee.rest.api.idp.api.authentication.UserDetails;
import io.gravitee.rest.api.management.rest.utils.BlindTrustManager;
import io.gravitee.rest.api.model.UserEntity;
import io.gravitee.rest.api.model.configuration.identity.IdentityProviderActivationReferenceType;
import io.gravitee.rest.api.model.configuration.identity.SocialIdentityProviderEntity;
import io.gravitee.rest.api.security.utils.AuthoritiesProvider;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.service.GroupService;
import io.gravitee.rest.api.service.RoleService;
import io.gravitee.rest.api.service.SocialIdentityProviderService;
import io.gravitee.rest.api.service.builder.JerseyClientBuilder;
import io.gravitee.rest.api.service.common.GraviteeContext;
import io.gravitee.rest.api.service.configuration.identity.IdentityProviderActivationService;
import io.swagger.annotations.Api;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.inject.Singleton;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.internal.util.collection.MultivaluedStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Singleton
@Api(tags = { "Authentication" })
public class OAuth2AuthenticationResource extends AbstractAuthenticationResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2AuthenticationResource.class);

    private static final String TEMPLATE_ENGINE_PROFILE_ATTRIBUTE = "profile";
    private static final String ACCESS_TOKEN_PROPERTY = "access_token";
    private static final String ID_TOKEN_PROPERTY = "id_token";

    // Dirty hack: only used to force class loading
    static {
        try {
            LOGGER.trace(
                "Loading class to initialize properly JsonPath Cache provider: " + Class.forName(JsonPathFunction.class.getName())
            );
        } catch (ClassNotFoundException ignored) {}
    }

    @Autowired
    private SocialIdentityProviderService socialIdentityProviderService;

    @Autowired
    private Environment environment;

    @Autowired
    private AuthoritiesProvider authoritiesProvider;

    private Client client;

    @PostConstruct
    public void initClient() throws NoSuchAlgorithmException, KeyManagementException {
        final boolean trustAllEnabled = environment.getProperty("security.trustAll", Boolean.class, false);
        final ClientBuilder builder = JerseyClientBuilder.newBuilder(environment);
        if (trustAllEnabled) {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, new TrustManager[] { new BlindTrustManager() }, null);
            builder.sslContext(sc);
        }

        this.client = builder.build();
    }

    @POST
    @Path("exchange")
    @Produces(MediaType.APPLICATION_JSON)
    public Response tokenExchange(
        @PathParam(value = "identity") final String identity,
        @QueryParam(value = "token") final String token,
        @Context final HttpServletResponse servletResponse
    ) throws IOException {
        SocialIdentityProviderEntity identityProvider = socialIdentityProviderService.findById(
            identity,
            new IdentityProviderActivationService.ActivationTarget(
                GraviteeContext.getCurrentOrganization(),
                IdentityProviderActivationReferenceType.ORGANIZATION
            )
        );

        if (identityProvider != null) {
            if (identityProvider.getTokenIntrospectionEndpoint() != null) {
                // Step1. Check the token by invoking the introspection endpoint
                final MultivaluedStringMap introspectData = new MultivaluedStringMap();
                introspectData.add(TOKEN, token);
                Response response = client
                    //TODO: what is the correct introspection URL here ?
                    .target(identityProvider.getTokenIntrospectionEndpoint())
                    .request(javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        String.format(
                            "Basic %s",
                            Base64
                                .getEncoder()
                                .encodeToString((identityProvider.getClientId() + ':' + identityProvider.getClientSecret()).getBytes())
                        )
                    )
                    .post(Entity.form(introspectData));
                introspectData.clear();

                if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                    JsonNode introspectPayload = response.readEntity(JsonNode.class);
                    boolean active = introspectPayload.path("active").asBoolean(true);

                    if (active) {
                        return authenticateUser(identityProvider, servletResponse, token, null, null);
                    } else {
                        return Response.status(Response.Status.UNAUTHORIZED).entity(introspectPayload).build();
                    }
                } else {
                    LOGGER.error(
                        "Token exchange failed with status {}: {}\n{}",
                        response.getStatus(),
                        response.getStatusInfo(),
                        getResponseEntityAsString(response)
                    );
                }

                return Response.status(response.getStatusInfo()).entity(response.getEntity()).build();
            } else {
                return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("Token exchange is not supported for this identity provider")
                    .build();
            }
        }

        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response exchangeAuthorizationCode(
        @PathParam(value = "identity") String identity,
        @Valid @NotNull final Payload payload,
        @Context final HttpServletResponse servletResponse
    ) throws IOException {
        SocialIdentityProviderEntity identityProvider = socialIdentityProviderService.findById(
            identity,
            new IdentityProviderActivationService.ActivationTarget(
                GraviteeContext.getCurrentOrganization(),
                IdentityProviderActivationReferenceType.ORGANIZATION
            )
        );

        if (identityProvider != null) {
            // Step 1. Exchange authorization code for access token.
            final MultivaluedStringMap accessData = new MultivaluedStringMap();
            accessData.add(CLIENT_ID_KEY, payload.getClientId());
            accessData.add(REDIRECT_URI_KEY, payload.getRedirectUri());
            accessData.add(CLIENT_SECRET, identityProvider.getClientSecret());
            accessData.add(CODE_KEY, payload.getCode());
            accessData.add(GRANT_TYPE_KEY, AUTH_CODE);

            Response response = client
                .target(identityProvider.getTokenEndpoint())
                .request(javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.form(accessData));
            accessData.clear();

            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                final Map<String, Object> responseEntity = getResponseEntity(response);
                final String accessToken = (String) responseEntity.get(ACCESS_TOKEN_PROPERTY);
                final String idToken = (String) responseEntity.get(ID_TOKEN_PROPERTY);
                return authenticateUser(identityProvider, servletResponse, accessToken, idToken, payload.getState());
            } else {
                LOGGER.error(
                    "Exchange authorization code failed with status {}: {}\n{}",
                    response.getStatus(),
                    response.getStatusInfo(),
                    getResponseEntityAsString(response)
                );
            }
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        return Response.status(Response.Status.NOT_FOUND).build();
    }

    /**
     * Retrieve profile information about the authenticated oauth end-user and authenticate it in Gravitee.
     *
     * @return Response
     */
    private Response authenticateUser(
        final SocialIdentityProviderEntity socialProvider,
        final HttpServletResponse servletResponse,
        final String accessToken,
        final String idToken,
        final String state
    ) throws IOException {
        // Step 2. Retrieve profile information about the authenticated end-user.
        Response response = client
            .target(socialProvider.getUserInfoEndpoint())
            .request(javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.AUTHORIZATION, String.format(socialProvider.getAuthorizationHeader(), accessToken))
            .get();

        // Step 3. Process the authenticated user.
        final String userInfo = getResponseEntityAsString(response);
        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            return processUser(socialProvider, servletResponse, userInfo, state, accessToken, idToken);
        } else {
            LOGGER.error("User info failed with status {}: {}\n{}", response.getStatus(), response.getStatusInfo(), userInfo);
        }

        return Response.status(response.getStatusInfo()).build();
    }

    private Response processUser(
        final SocialIdentityProviderEntity socialProvider,
        final HttpServletResponse servletResponse,
        final String userInfo,
        final String state,
        final String accessToken,
        final String idToken
    ) {
        UserEntity user = userService.createOrUpdateUserFromSocialIdentityProvider(socialProvider, userInfo);

        final Set<GrantedAuthority> authorities = authoritiesProvider.retrieveAuthorities(user.getId());

        //set user to Authentication Context
        UserDetails userDetails = new UserDetails(user.getId(), "", authorities);
        userDetails.setEmail(user.getEmail());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userDetails, null, authorities));

        return connectUser(user.getId(), state, servletResponse, accessToken, idToken);
    }
}
