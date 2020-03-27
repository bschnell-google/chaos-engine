/*
 *    Copyright (c) 2018 - 2020, Thales DIS CPL Canada, Inc
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.thales.chaos.platform.impl;

import com.google.api.gax.core.CredentialsProvider;
import com.google.cloud.redis.v1.*;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;
import com.thales.chaos.container.Container;
import com.thales.chaos.container.ContainerManager;
import com.thales.chaos.container.enums.ContainerHealth;
import com.thales.chaos.containers.impl.GcpMemorystoreInstanceContainer;
import com.thales.chaos.exception.ChaosException;
import com.thales.chaos.platform.Platform;
import com.thales.chaos.platform.enums.ApiStatus;
import com.thales.chaos.platform.enums.PlatformHealth;
import com.thales.chaos.platform.enums.PlatformLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static com.thales.chaos.constants.DataDogConstants.DATADOG_CONTAINER_KEY;
import static net.logstash.logback.argument.StructuredArguments.v;

@ConditionalOnProperty("gcp.memorystore")
@ConfigurationProperties("gcp.memorystore")
@Component
public class GcpMemorystorePlatform extends Platform {
    @Autowired
    private CredentialsProvider computeCredentialsProvider;
    @Autowired
    private ContainerManager containerManager;
    private String projectId;
    private String locations;

    private GcpMemorystorePlatform () {
        log.info("GCP Memorystore Platform created");
    }

    public void setLocations (String locations) {
        this.locations = locations;
    }

    public void setProjectId (String projectId) {
        this.projectId = projectId;
    }

    @Override
    public ApiStatus getApiStatus () {
        try {
            for (String location : getLocationsToList()) {
                LocationName parent = LocationName.of(projectId, location);
                for (Instance instance : getInstanceClient().listInstances(parent).iterateAll()) {
                    log.info(instance.getHost());
                }
            }
        } catch (RuntimeException e) {
            log.error("Caught error when evaluating API Status of Google Cloud Platform", e);
            return ApiStatus.ERROR;
        }
        return ApiStatus.OK;
    }

    private Collection<String> getLocationsToList () {
        if (locations == null || locations.isBlank()) {
            log.warn("Empty locations");
            return Collections.emptySet();
        }
        return Arrays.stream(locations.split(",")).filter(Objects::nonNull).collect(Collectors.toList());
    }

    CloudRedisClient getInstanceClient () {
        try {
            return CloudRedisClient.create(CloudRedisSettings.newBuilder()
                                                             .setCredentialsProvider(computeCredentialsProvider)
                                                             .build());
        } catch (IOException e) {
            // TODO: Create redis specific message
            throw new ChaosException("GCP_REDIS_GENERIC_ERROR", e);
        }
    }

    @Override
    public PlatformLevel getPlatformLevel () {
        return PlatformLevel.IAAS;
    }

    @Override
    public PlatformHealth getPlatformHealth () {
        return null;
    }

    @Override
    protected List<Container> generateRoster () {
        List<Container> roster = new ArrayList<>();
        //for (String location : getLocationsToList()) {
        LocationName parent = LocationName.of(projectId, "-");
        for (Instance instance : getInstanceClient().listInstances(parent).iterateAll()) {
            GcpMemorystoreInstanceContainer container = GcpMemorystoreInstanceContainer.builder()
                                                                                       .withPlatform(this)
                                                                                       .fromInstance(instance)
                                                                                       .build();
            roster.add(container);
            log.info("{}", container);
        }
        //  }
        return roster;
    }

    @Override
    public boolean isContainerRecycled (Container container) {
        return false;
    }

    public String failover (GcpMemorystoreInstanceContainer container,
                            FailoverInstanceRequest.DataProtectionMode mode) throws ExecutionException, InterruptedException {
        log.info("Failover triggered for instance {}", v(DATADOG_CONTAINER_KEY, container));
        FailoverInstanceRequest failoverInstanceRequest = FailoverInstanceRequest.newBuilder().
                setName(container.getName()).setDataProtectionMode(mode).build();
        return getInstanceClient().failoverInstanceAsync(failoverInstanceRequest).getName();
    }

    public boolean isOperationCompleted (String operationName) {
        Operation operation = getOperationsClient().getOperation(operationName);
        return operation.getDone();
    }

    OperationsClient getOperationsClient () {
        return getInstanceClient().getOperationsClient();
    }

    public ContainerHealth isContainerRunning (GcpMemorystoreInstanceContainer container) {
        Instance instance = getInstanceClient().getInstance(container.getName());
        return instance != null && getInstanceClient().getInstance(container.getName())
                                                      .getState() == Instance.State.READY ? ContainerHealth.NORMAL : ContainerHealth.RUNNING_EXPERIMENT;
    }
}
