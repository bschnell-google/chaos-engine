package com.gemalto.chaos.platform.impl;

import com.gemalto.chaos.container.Container;
import com.gemalto.chaos.container.ContainerManager;
import com.gemalto.chaos.container.enums.ContainerHealth;
import com.gemalto.chaos.container.impl.CloudFoundryApplication;
import com.gemalto.chaos.container.impl.CloudFoundryApplicationRoute;
import com.gemalto.chaos.selfawareness.CloudFoundrySelfAwareness;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.*;
import org.cloudfoundry.client.v2.routes.RouteEntity;
import org.cloudfoundry.client.v2.routes.RouteResource;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.RestartApplicationRequest;
import org.cloudfoundry.operations.applications.ScaleApplicationRequest;
import org.cloudfoundry.operations.domains.Domain;
import org.cloudfoundry.operations.domains.Domains;
import org.cloudfoundry.operations.domains.Status;
import org.cloudfoundry.operations.routes.MapRouteRequest;
import org.cloudfoundry.operations.routes.Routes;
import org.cloudfoundry.operations.routes.UnmapRouteRequest;
import org.hamcrest.collection.IsIterableContainingInAnyOrder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.gemalto.chaos.constants.CloudFoundryConstants.*;
import static java.util.UUID.randomUUID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CloudFoundryApplicationPlatformTest {
    private String APPLICATION_NAME = randomUUID().toString();
    private String APPLICATION_ID = randomUUID().toString();
    private String APPLICATION_NAME_2 = randomUUID().toString();
    private String APPLICATION_ID_2 = randomUUID().toString();
    private Integer INSTANCES = 2;
    private CloudFoundryApplication EXPECTED_CONTAINER_1;
    private CloudFoundryApplication EXPECTED_CONTAINER_2;
    private ApplicationSummary.Builder builder_1;
    private ApplicationSummary.Builder builder_2;
    private ApplicationSummary applicationSummary_1;
    private ApplicationSummary applicationSummary_2;
    @MockBean
    private CloudFoundryPlatformInfo cloudFoundryPlatformInfo;
    @MockBean
    private CloudFoundryOperations cloudFoundryOperations;
    @MockBean
    private ContainerManager containerManager;
    @Mock
    private Applications applications;
    @Mock
    private Domains domains;
    @Mock
    private Routes routes;

    @MockBean
    private CloudFoundryClient cloudFoundryClient;
    @MockBean
    private CloudFoundrySelfAwareness cloudFoundrySelfAwareness;
    @Autowired
    private CloudFoundryApplicationPlatform cloudFoundryApplicationPlatform;
    private Domain httpDomain = Domain.builder()
                                      .id("httpDomain")
                                      .name("http.domain.com")
                                      .type("")
                                      .status(Status.SHARED)
                                      .build();
    private Domain tcpDomain = Domain.builder()
                                     .id("tcpDomain")
                                     .name("tcp.domain.com")
                                     .type("tcp")
                                     .status(Status.SHARED)
                                     .build();
    private RouteEntity httpRoute = RouteEntity.builder().host("httpHost").domainId(httpDomain.getId()).build();
    private RouteEntity tcpRoute = RouteEntity.builder().port(666).domainId(tcpDomain.getId()).build();
    private CloudFoundryApplicationRoute app1_httpRoute;
    private CloudFoundryApplicationRoute app1_tcpRoute;

    @Before
    public void setUp () {
        app1_httpRoute = CloudFoundryApplicationRoute.builder()
                                                     .route(httpRoute)
                                                     .domain(httpDomain)
                                                     .applicationName(APPLICATION_NAME)
                                                     .build();
        app1_tcpRoute = CloudFoundryApplicationRoute.builder()
                                                    .route(tcpRoute)
                                                    .domain(tcpDomain)
                                                    .applicationName(APPLICATION_NAME)
                                                    .build();

        List<CloudFoundryApplicationRoute> app1_routes = new ArrayList<>();
        app1_routes.add(app1_httpRoute);
        app1_routes.add(app1_tcpRoute);

        EXPECTED_CONTAINER_1 = CloudFoundryApplication.builder()
                                                      .containerInstances(INSTANCES)
                                                      .applicationID(APPLICATION_ID)
                                                      .platform(cloudFoundryApplicationPlatform)
                                                      .name(APPLICATION_NAME).applicationRoutes(app1_routes)
                                                      .build();
        EXPECTED_CONTAINER_2 = CloudFoundryApplication.builder()
                                                      .containerInstances(INSTANCES)
                                                      .applicationID(APPLICATION_ID_2)
                                                      .platform(cloudFoundryApplicationPlatform)
                                                      .name(APPLICATION_NAME_2).applicationRoutes(new ArrayList<>())
                                                      .build();
        builder_1 = ApplicationSummary.builder()
                                      .diskQuota(0)
                                      .instances(INSTANCES)
                                      .id(APPLICATION_ID).name(APPLICATION_NAME).addAllUrls(Collections.emptySet())
                                      .runningInstances(INSTANCES)
                                      .requestedState(CLOUDFOUNDRY_APPLICATION_STARTED)
                                      .memoryLimit(0);
        builder_2 = ApplicationSummary.builder()
                                      .diskQuota(0)
                                      .instances(INSTANCES)
                                      .id(APPLICATION_ID_2).name(APPLICATION_NAME_2).addAllUrls(Collections.emptySet())
                                      .runningInstances(INSTANCES)
                                      .requestedState(CLOUDFOUNDRY_APPLICATION_STARTED)
                                      .memoryLimit(0);
        applicationSummary_1 = builder_1.build();
        applicationSummary_2 = builder_2.build();
    }

    @Test
    public void getRoster () {
        ApplicationsV2 applicationsV2 = mock(ApplicationsV2.class);
        ApplicationSummary stoppedApplicationSummary = builder_1.requestedState(CLOUDFOUNDRY_APPLICATION_STOPPED)
                                                                .build();
        ApplicationSummary zeroInstancesApplicationSummary = builder_1.instances(0).build();
        Flux<ApplicationSummary> applicationsFlux = Flux.just(applicationSummary_1, applicationSummary_2, stoppedApplicationSummary, zeroInstancesApplicationSummary);
        doReturn(applications).when(cloudFoundryOperations).applications();
        doReturn(applicationsFlux).when(applications).list();
        Flux<Domain> domainFlux = Flux.just(httpDomain, tcpDomain);
        doReturn(domains).when(cloudFoundryOperations).domains();
        doReturn(domainFlux).when(domains).list();
        ListApplicationRoutesRequest app1_listApplicationRoutesRequest = ListApplicationRoutesRequest.builder()
                                                                                                     .applicationId(APPLICATION_ID)
                                                                                                     .build();
        ListApplicationRoutesRequest app2_listApplicationRoutesRequest = ListApplicationRoutesRequest.builder()
                                                                                                     .applicationId(APPLICATION_ID_2)
                                                                                                     .build();
        RouteResource httpRouteResource = RouteResource.builder().entity(httpRoute).build();
        RouteResource tcpRouteResource = RouteResource.builder().entity(tcpRoute).build();
        ListApplicationRoutesResponse app1_listApplicationRoutesResponse = ListApplicationRoutesResponse.builder()
                                                                                                        .resources(httpRouteResource, tcpRouteResource)
                                                                                                        .build();
        Mono<ListApplicationRoutesResponse> app1_listApplicationRoutesResponses = Mono.just(app1_listApplicationRoutesResponse);
        doReturn(applicationsV2).when(cloudFoundryClient).applicationsV2();
        doReturn(app1_listApplicationRoutesResponses).when(applicationsV2)
                                                     .listRoutes(app1_listApplicationRoutesRequest);
        ListApplicationRoutesResponse app2_listApplicationRoutesResponse = ListApplicationRoutesResponse.builder()
                                                                                                        .build();
        Mono<ListApplicationRoutesResponse> app2_listApplicationRoutesResponses = Mono.just(app2_listApplicationRoutesResponse);
        doReturn(applicationsV2).when(cloudFoundryClient).applicationsV2();
        doReturn(app2_listApplicationRoutesResponses).when(applicationsV2)
                                                     .listRoutes(app2_listApplicationRoutesRequest);


        List<Container> roster = cloudFoundryApplicationPlatform.getRoster();
        assertEquals(2, roster.size());
        assertThat(roster, IsIterableContainingInAnyOrder.containsInAnyOrder(EXPECTED_CONTAINER_1, EXPECTED_CONTAINER_2));
    }

    @Test
    public void checkPlatformHealthNormal () {
        Integer INSTANCE_ID = 0;
        ApplicationsV2 applicationsV2 = mock(ApplicationsV2.class);
        ApplicationInstancesResponse applicationInstancesResponse = ApplicationInstancesResponse.builder()
                                                                                                .instance(INSTANCE_ID.toString(), ApplicationInstanceInfo
                                                                                                        .builder()
                                                                                                        .consoleIp("")
                                                                                                        .consolePort(22)
                                                                                                        .debugIp("")
                                                                                                        .debugPort(8888)
                                                                                                        .details("")
                                                                                                        .since(0D)
                                                                                                        .state(CLOUDFOUNDRY_RUNNING_STATE)
                                                                                                        .uptime(0L)
                                                                                                        .build())
                                                                                                .build();
        Mono<ApplicationInstancesResponse> applicationInstancesResponseMono = Mono.just(applicationInstancesResponse);
        doReturn(applicationsV2).when(cloudFoundryClient).applicationsV2();
        doReturn(applicationInstancesResponseMono).when(applicationsV2)
                                                  .instances(any(ApplicationInstancesRequest.class));
        Flux<ApplicationSummary> applicationsFlux = Flux.just(applicationSummary_1);
        doReturn(applications).when(cloudFoundryOperations).applications();
        doReturn(applicationsFlux).when(applications).list();
        assertEquals(ContainerHealth.NORMAL, cloudFoundryApplicationPlatform.checkPlatformHealth());
    }

    @Test
    public void checkPlatformHealthConainerInstanceStarting () {
        Integer INSTANCE_ID = 0;
        ApplicationsV2 applicationsV2 = mock(ApplicationsV2.class);
        ApplicationInstancesResponse applicationInstancesResponse = ApplicationInstancesResponse.builder()
                                                                                                .instance(INSTANCE_ID.toString(), ApplicationInstanceInfo
                                                                                                        .builder()
                                                                                                        .consoleIp("")
                                                                                                        .consolePort(22)
                                                                                                        .debugIp("")
                                                                                                        .debugPort(8888)
                                                                                                        .details("")
                                                                                                        .since(0D)
                                                                                                        .state(CLOUDFOUNDRY_STARTING_STATE)
                                                                                                        .uptime(0L)
                                                                                                        .build())
                                                                                                .build();
        Mono<ApplicationInstancesResponse> applicationInstancesResponseMono = Mono.just(applicationInstancesResponse);
        doReturn(applicationsV2).when(cloudFoundryClient).applicationsV2();
        doReturn(applicationInstancesResponseMono).when(applicationsV2)
                                                  .instances(any(ApplicationInstancesRequest.class));
        Flux<ApplicationSummary> applicationsFlux = Flux.just(applicationSummary_1);
        doReturn(applications).when(cloudFoundryOperations).applications();
        doReturn(applicationsFlux).when(applications).list();
        assertEquals(ContainerHealth.RUNNING_EXPERIMENT, cloudFoundryApplicationPlatform.checkPlatformHealth());
    }

    @Test
    public void rescaleApplication () {
        ScaleApplicationRequest scaleApplicationRequest = ScaleApplicationRequest.builder()
                                                                                 .name(APPLICATION_NAME)
                                                                                 .instances(INSTANCES)
                                                                                 .build();
        Mono monoVoid = mock(Mono.class);
        doReturn(applications).when(cloudFoundryOperations).applications();
        doReturn(monoVoid).when(applications).scale(scaleApplicationRequest);
        cloudFoundryApplicationPlatform.rescaleApplication(APPLICATION_NAME, INSTANCES);
        verify(applications, times(1)).scale(scaleApplicationRequest);
        verify(monoVoid, times(1)).block();
    }

    @Test
    public void restartApplication () {
        RestartApplicationRequest restartApplicationRequest = RestartApplicationRequest.builder()
                                                                                       .name(APPLICATION_NAME)
                                                                                       .build();
        Mono monoVoid = mock(Mono.class);
        doReturn(applications).when(cloudFoundryOperations).applications();
        doReturn(monoVoid).when(applications).restart(restartApplicationRequest);
        cloudFoundryApplicationPlatform.restartApplication(APPLICATION_NAME);
        verify(applications, times(1)).restart(restartApplicationRequest);
        verify(monoVoid, times(1)).block();
    }

    @Test
    public void mapRoute () {
        MapRouteRequest mapRouteRequest = MapRouteRequest.builder()
                                                         .applicationName(APPLICATION_NAME)
                                                         .domain(randomUUID().toString())
                                                         .build();
        Mono monoInt = mock(Mono.class);
        doReturn(routes).when(cloudFoundryOperations).routes();
        doReturn(monoInt).when(routes).map(mapRouteRequest);
        cloudFoundryApplicationPlatform.mapRoute(mapRouteRequest);
        verify(routes, times(1)).map(mapRouteRequest);
        verify(monoInt, times(1)).block();
    }

    @Test
    public void unmapRoute () {
        UnmapRouteRequest unmapRouteRequest = UnmapRouteRequest.builder()
                                                               .applicationName(APPLICATION_NAME)
                                                               .domain(randomUUID().toString())
                                                               .build();
        Mono monoInt = mock(Mono.class);
        doReturn(routes).when(cloudFoundryOperations).routes();
        doReturn(monoInt).when(routes).unmap(unmapRouteRequest);
        cloudFoundryApplicationPlatform.unmapRoute(unmapRouteRequest);
        verify(routes, times(1)).unmap(unmapRouteRequest);
        verify(monoInt, times(1)).block();
    }

    @Configuration
    static class ContextConfiguration {
        @Autowired
        private CloudFoundryOperations cloudFoundryOperations;
        @Autowired
        private CloudFoundryClient cloudFoundryClient;
        @Autowired
        private CloudFoundryPlatformInfo cloudFoundryPlatformInfo;
        @Autowired
        private ContainerManager containerManager;

        @Bean
        CloudFoundryApplicationPlatform cloudFoundryApplicationPlatform () {
            return new CloudFoundryApplicationPlatform(cloudFoundryOperations, cloudFoundryClient, cloudFoundryPlatformInfo);
        }
    }
}