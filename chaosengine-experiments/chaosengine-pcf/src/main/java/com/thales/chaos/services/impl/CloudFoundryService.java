package com.thales.chaos.services.impl;

import com.thales.chaos.services.CloudService;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.doppler.DopplerClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.doppler.ReactorDopplerClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.cloudfoundry.reactor.uaa.ReactorUaaClient;
import org.cloudfoundry.uaa.UaaClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "cf")
@ConditionalOnProperty({ "cf.apihost" })
public class CloudFoundryService implements CloudService {
    private String apiHost;
    private Integer port = 443;
    private String username;
    private String password;
    private String organization;
    private String space = "default";

    public void setApiHost (String apiHost) {
        this.apiHost = apiHost;
    }

    public void setPort (Integer port) {
        this.port = port;
    }

    public void setUsername (String username) {
        this.username = username;
    }

    public void setPassword (String password) {
        this.password = password;
    }

    public void setOrganization (String organization) {
        this.organization = organization;
    }

    public void setSpace (String space) {
        this.space = space;
    }

    @Bean
    @RefreshScope
    ConnectionContext connectionContext () {
        return DefaultConnectionContext.builder().apiHost(apiHost).port(port).build();
    }

    @Bean
    @RefreshScope
    TokenProvider tokenProvider () {
        return PasswordGrantTokenProvider.builder().password(password).username(username).build();
    }

    @Bean
    @RefreshScope
    CloudFoundryClient cloudFoundryClient (ConnectionContext connectionContext, TokenProvider tokenProvider) {
        return ReactorCloudFoundryClient.builder()
                                        .connectionContext(connectionContext)
                                        .tokenProvider(tokenProvider)
                                        .build();
    }

    @Bean
    @RefreshScope
    DopplerClient dopplerClient (ConnectionContext connectionContext, TokenProvider tokenProvider) {
        return ReactorDopplerClient.builder().connectionContext(connectionContext).tokenProvider(tokenProvider).build();
    }

    @Bean
    @RefreshScope
    UaaClient uaaClient (ConnectionContext connectionContext, TokenProvider tokenProvider) {
        return ReactorUaaClient.builder().connectionContext(connectionContext).tokenProvider(tokenProvider).build();
    }

    @Bean
    @RefreshScope
    CloudFoundryOperations cloudFoundryOperations (CloudFoundryClient cloudFoundryClient, DopplerClient dopplerClient, UaaClient uaaClient) {
        return DefaultCloudFoundryOperations.builder()
                                            .cloudFoundryClient(cloudFoundryClient)
                                            .dopplerClient(dopplerClient)
                                            .uaaClient(uaaClient)
                                            .organization(organization)
                                            .space(space)
                                            .build();
    }
}