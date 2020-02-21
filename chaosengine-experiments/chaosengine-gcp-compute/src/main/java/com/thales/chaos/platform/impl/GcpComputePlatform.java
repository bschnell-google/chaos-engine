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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.compute.v1.*;
import com.google.common.collect.Ordering;
import com.thales.chaos.constants.GcpConstants;
import com.thales.chaos.container.Container;
import com.thales.chaos.container.enums.ContainerHealth;
import com.thales.chaos.container.impl.GcpComputeInstanceContainer;
import com.thales.chaos.exception.ChaosException;
import com.thales.chaos.exceptions.enums.GcpComputeChaosErrorCode;
import com.thales.chaos.platform.Platform;
import com.thales.chaos.platform.SshBasedExperiment;
import com.thales.chaos.platform.enums.ApiStatus;
import com.thales.chaos.platform.enums.PlatformHealth;
import com.thales.chaos.platform.enums.PlatformLevel;
import com.thales.chaos.selfawareness.GcpComputeSelfAwareness;
import com.thales.chaos.shellclient.ssh.GcpSSHKeyMetadata;
import com.thales.chaos.shellclient.ssh.SSHCredentials;
import org.apache.commons.net.util.SubnetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.thales.chaos.constants.DataDogConstants.DATADOG_CONTAINER_KEY;
import static com.thales.chaos.services.impl.GcpComputeService.COMPUTE_PROJECT;
import static com.thales.chaos.shellclient.ssh.GcpRuntimeSSHKey.CHAOS_USERNAME;
import static java.util.Collections.emptyList;
import static java.util.function.Predicate.not;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.v;

@ConditionalOnProperty("gcp.compute")
@ConfigurationProperties("gcp.compute")
@Component
public class GcpComputePlatform extends Platform implements SshBasedExperiment<GcpComputeInstanceContainer> {
    private static final Logger log = LoggerFactory.getLogger(GcpComputePlatform.class);
    @Autowired
    private InstanceClient instanceClient;
    @Autowired
    private InstanceGroupClient instanceGroupClient;
    @Autowired
    private InstanceGroupManagerClient instanceGroupManagerClient;
    @Autowired
    private RegionInstanceGroupClient regionInstanceGroupClient;
    @Autowired
    private RegionInstanceGroupManagerClient regionInstanceGroupManagerClient;
    @Autowired
    private ZoneOperationClient zoneOperationClient;
    @Autowired
    @Qualifier(COMPUTE_PROJECT)
    private ProjectName projectName;
    @Autowired
    private GcpComputeSelfAwareness selfAwareness;
    private Map<String, String> includeFilter = Collections.emptyMap();
    private Map<String, String> excludeFilter = Collections.emptyMap();
    @JsonProperty
    private Collection<SubnetUtils.SubnetInfo> routableCIDRBlocks = Collections.emptySet();

    public void setRoutableCIDRBlocks (Collection<String> routableCIDRBlocks) {
        this.routableCIDRBlocks = routableCIDRBlocks.stream()
                                                    .map(SubnetUtils::new)
                                                    .map(SubnetUtils::getInfo)
                                                    .collect(Collectors.toSet());
    }

    @Override
    public ApiStatus getApiStatus () {
        try {
            instanceClient.aggregatedListInstances(projectName);
        } catch (RuntimeException e) {
            log.error("Caught error when evaluating API Status of Google Cloud Platform", e);
            return ApiStatus.ERROR;
        }
        return ApiStatus.OK;
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
        log.debug("Generating roster of GCP Compute instances");
        InstanceClient.AggregatedListInstancesPagedResponse instances = instanceClient.aggregatedListInstances(
                projectName);
        return StreamSupport.stream(instances.iterateAll().spliterator(), false)
                            .map(InstancesScopedList::getInstancesList)
                            .filter(Objects::nonNull)
                            .flatMap(Collection::stream)
                            .filter(not(this::isMe))
                            .filter(this::isNotFiltered)
                            .map(this::createContainerFromInstance)
                            .peek(container -> log.info("Created container {}", v(DATADOG_CONTAINER_KEY, container)))
                            .collect(Collectors.toList());
    }

    private boolean isMe (Instance instance) {
        long id;
        try {
            id = Long.parseLong(instance.getId());
        } catch (NumberFormatException e) {
            return false;
        }
        return selfAwareness.isMe(id);
    }

    boolean isNotFiltered (Instance instance) {
        List<Items> itemsList = Optional.of(instance)
                                        .map(Instance::getMetadata)
                                        .map(Metadata::getItemsList)
                                        .orElse(emptyList());
        Collection<Items> includeFilter = getIncludeFilter();
        Collection<Items> excludeFilter = getExcludeFilter();
        boolean hasAllMustIncludes = includeFilter.isEmpty() || itemsList.stream().anyMatch(includeFilter::contains);
        boolean hasNoMustNotIncludes = itemsList.stream().noneMatch(excludeFilter::contains);
        final boolean isNotFiltered = hasAllMustIncludes && hasNoMustNotIncludes;
        if (!isNotFiltered) {
            log.info("Instance {} filtered because of {}, {}",
                    instance.getName(),
                    kv("includeFilter", hasAllMustIncludes),
                    kv("excludeFilter", hasNoMustNotIncludes));
        }
        return isNotFiltered;
    }

    GcpComputeInstanceContainer createContainerFromInstance (Instance instance) {
        String id = instance.getId();
        String name = instance.getName();
        String zone = instance.getZone();
        String publicIP = getPrimaryPublicIP(instance.getNetworkInterfacesList());
        String privateIP = getPrimaryPrivateIP(instance.getNetworkInterfacesList());
        if (zone != null && zone.contains("/")) {
            zone = zone.substring(zone.lastIndexOf('/') + 1);
        }
        String createdBy = Optional.of(instance)
                                   .map(Instance::getMetadata)
                                   .map(Metadata::getItemsList)
                                   .stream()
                                   .flatMap(Collection::stream)
                                   .filter(GcpComputePlatform::isCreatedByItem)
                                   .map(Items::getValue)
                                   .findFirst()
                                   .orElse(null);
        return GcpComputeInstanceContainer.builder()
                                          .withInstanceName(name)
                                          .withUniqueIdentifier(id)
                                          .withPlatform(this)
                                          .withCreatedBy(createdBy)
                                          .withZone(zone)
                                          .withPublicIPAddress(publicIP)
                                          .withPrivateIPAddress(privateIP)
                                          .build();
    }

    private String getPrimaryPublicIP (Collection<NetworkInterface> networkInterfaceList) {
        return Optional.ofNullable(networkInterfaceList)
                       .stream()
                       .flatMap(Collection::stream)
                       .filter(GcpComputePlatform::isPrimaryNic)
                       .map(NetworkInterface::getAccessConfigsList)
                       .flatMap(Collection::stream)
                       .map(AccessConfig::getNatIP)
                       .findFirst()
                       .orElse(null);
    }

    private String getPrimaryPrivateIP (Collection<NetworkInterface> networkInterfaceList) {
        return Optional.ofNullable(networkInterfaceList)
                       .stream()
                       .flatMap(Collection::stream)
                       .filter(GcpComputePlatform::isPrimaryNic)
                       .map(NetworkInterface::getNetworkIP)
                       .findFirst()
                       .orElse(null);
    }

    private static boolean isPrimaryNic (NetworkInterface networkInterface) {
        if (networkInterface == null) return false;
        return "nic0".equals(networkInterface.getName());
    }

    public Collection<Items> getIncludeFilter () {
        return asItemCollection(includeFilter);
    }

    public void setIncludeFilter (Map<String, String> includeFilter) {
        this.includeFilter = includeFilter;
    }

    public Collection<Items> getExcludeFilter () {
        return asItemCollection(excludeFilter);
    }

    public void setExcludeFilter (Map<String, String> excludeFilter) {
        this.excludeFilter = excludeFilter;
    }

    private static boolean isCreatedByItem (Items items) {
        return items.getKey().equals(GcpConstants.CREATED_BY_METADATA_KEY);
    }

    private static Collection<Items> asItemCollection (Map<String, String> itemMap) {
        return itemMap.entrySet()
                      .stream()
                      .map(entrySet -> Items.newBuilder()
                                            .setKey(entrySet.getKey())
                                            .setValue(entrySet.getValue())
                                            .build())
                      .collect(Collectors.toSet());
    }

    @Override
    public boolean isContainerRecycled (Container container) {
        if (!(container instanceof GcpComputeInstanceContainer)) throw new IllegalArgumentException();
        GcpComputeInstanceContainer gcpContainer = (GcpComputeInstanceContainer) container;
        String oldUUID = gcpContainer.getUniqueIdentifier();
        ProjectZoneInstanceName instanceName = ProjectZoneInstanceName.of(gcpContainer.getInstanceName(),
                projectName.getProject(),
                gcpContainer.getZone());
        try {
            Instance instance = instanceClient.getInstance(instanceName);
            String instanceId = instance.getId();
            return !Objects.equals(instanceId, oldUUID);
        } catch (ApiException e) {
            if (e.getStatusCode().getCode().getHttpStatusCode() == 404) return true;
            throw e;
        }
    }

    public String stopInstance (GcpComputeInstanceContainer container) {
        log.info("Stopping instance {}", v(DATADOG_CONTAINER_KEY, container));
        ProjectZoneInstanceName instance = getProjectZoneInstanceNameOfContainer(container, projectName);
        return instanceClient.stopInstance(instance).getSelfLink();
    }

    static ProjectZoneInstanceName getProjectZoneInstanceNameOfContainer (GcpComputeInstanceContainer container,
                                                                          ProjectName projectName) {
        return ProjectZoneInstanceName.newBuilder()
                                      .setInstance(container.getUniqueIdentifier())
                                      .setZone(container.getZone())
                                      .setProject(projectName.getProject())
                                      .build();
    }

    public ContainerHealth isContainerRunning (GcpComputeInstanceContainer gcpComputeInstanceContainer) {
        ProjectZoneInstanceName instanceName = getProjectZoneInstanceNameOfContainer(gcpComputeInstanceContainer,
                projectName);
        Instance instance;
        try {
            instance = instanceClient.getInstance(instanceName);
        } catch (ApiException e) {
            return Optional.of(e)
                           .map(ApiException::getStatusCode)
                           .map(StatusCode::getCode)
                           .filter(code -> code == StatusCode.Code.NOT_FOUND)
                           .map(code -> ContainerHealth.DOES_NOT_EXIST)
                           .orElse(ContainerHealth.RUNNING_EXPERIMENT);
        }
        String status = instance.getStatus();
        return "RUNNING".equals(status) ? ContainerHealth.NORMAL : ContainerHealth.RUNNING_EXPERIMENT;
    }

    public String simulateMaintenance (GcpComputeInstanceContainer container) {
        log.info("Simulating Host Maintenance for Google Compute instance {}", v(DATADOG_CONTAINER_KEY, container));
        final Operation operation = instanceClient.simulateMaintenanceEventInstance(
                getProjectZoneInstanceNameOfContainer(container, projectName));
        return operation.getSelfLink();
    }

    public String setTags (GcpComputeInstanceContainer container, List<String> tags) {
        log.info("Setting tags of instance {} to {}", v(DATADOG_CONTAINER_KEY, container), tags);
        ProjectZoneInstanceName instance = getProjectZoneInstanceNameOfContainer(container, projectName);
        String oldFingerprint = instanceClient.getInstance(instance).getTags().getFingerprint();
        return setTagsSafe(container, tags, oldFingerprint);
    }

    public String setTagsSafe (GcpComputeInstanceContainer container, List<String> newTags, String oldFingerprint) {
        Tags tagChangeRequest = Tags.newBuilder().addAllItems(newTags != null ? newTags : Collections.emptyList()).
                setFingerprint(oldFingerprint).build();
        ProjectZoneInstanceName instance = getProjectZoneInstanceNameOfContainer(container, projectName);
        return instanceClient.setTagsInstance(instance, tagChangeRequest).getSelfLink();
    }

    public boolean checkTags (GcpComputeInstanceContainer container, List<String> expectedTags) {
        log.debug("Evaluating tags of {}, expecting {}", v(DATADOG_CONTAINER_KEY, container), expectedTags);
        ProjectZoneInstanceName instance = getProjectZoneInstanceNameOfContainer(container, projectName);
        List<String> actualTags = instanceClient.getInstance(instance).getTags().getItemsList();
        log.debug("Actual tags are {}", actualTags);
        actualTags = actualTags == null ? Collections.emptyList() : Ordering.natural().sortedCopy(actualTags);
        expectedTags = expectedTags == null ? Collections.emptyList() : Ordering.natural().sortedCopy(expectedTags);
        return actualTags.equals(expectedTags);
    }

    public void startInstance (GcpComputeInstanceContainer container) {
        log.info("Starting instance {}", v(DATADOG_CONTAINER_KEY, container));
        ProjectZoneInstanceName instance = getProjectZoneInstanceNameOfContainer(container, projectName);
        instanceClient.startInstance(instance);
    }

    public boolean isContainerGroupAtCapacity (GcpComputeInstanceContainer container) {
        log.debug("Checking group actual size vs desired size for {}", v(DATADOG_CONTAINER_KEY, container));
        String group = getContainerGroup(container);
        if (ProjectZoneInstanceGroupManagerName.isParsableFrom(group)) {
            return isContainerZoneGroupAtDesiredCapacity(container);
        } else if (ProjectRegionInstanceGroupManagerName.isParsableFrom(group)) {
            return isContainerRegionGroupAtDesiredCapacity(container);
        }
        log.debug("Could not find either a zone or regional group for {}", group);
        return false;
    }

    private String getContainerGroup (GcpComputeInstanceContainer container) {
        String group = container.getAggregationIdentifier();
        if (group.startsWith("projects/")) {
            group = group.substring("projects/".length());
        }
        return group;
    }

    private boolean isContainerZoneGroupAtDesiredCapacity (GcpComputeInstanceContainer container) {
        String group = getContainerGroup(container);
        ProjectZoneInstanceGroupManagerName projectZoneInstanceGroupManagerName = ProjectZoneInstanceGroupManagerName.parse(
                group);
        ProjectZoneInstanceGroupName projectZoneInstanceGroupName = ProjectZoneInstanceGroupName.of(
                projectZoneInstanceGroupManagerName.getInstanceGroupManager(),
                projectZoneInstanceGroupManagerName.getProject(),
                projectZoneInstanceGroupManagerName.getZone());
        Integer actualSize = instanceGroupClient.getInstanceGroup(projectZoneInstanceGroupName).getSize();
        Integer targetSize = instanceGroupManagerClient.getInstanceGroupManager(projectZoneInstanceGroupManagerName)
                                                       .getTargetSize();
        log.debug("For group {}, {}, {}", group, kv("actualSize", actualSize), kv("targetSize", targetSize));
        return targetSize.compareTo(actualSize) <= 0;
    }

    private boolean isContainerRegionGroupAtDesiredCapacity (GcpComputeInstanceContainer container) {
        String group = getContainerGroup(container);
        ProjectRegionInstanceGroupManagerName projectRegionInstanceGroupManagerName = ProjectRegionInstanceGroupManagerName
                .parse(group);
        ProjectRegionInstanceGroupName projectRegionInstanceGroupName = ProjectRegionInstanceGroupName.of(
                projectRegionInstanceGroupManagerName.getInstanceGroupManager(),
                projectRegionInstanceGroupManagerName.getProject(),
                projectRegionInstanceGroupManagerName.getRegion());
        Integer actualSize = regionInstanceGroupClient.getRegionInstanceGroup(projectRegionInstanceGroupName).getSize();
        Integer targetSize = regionInstanceGroupManagerClient.getRegionInstanceGroupManager(
                projectRegionInstanceGroupManagerName).getTargetSize();
        log.debug("For group {}, {}, {}", group, kv("actualSize", actualSize), kv("targetSize", targetSize));
        return targetSize.compareTo(actualSize) <= 0;
    }

    public String restartContainer (GcpComputeInstanceContainer container) {
        log.info("Restarting instance {}", v(DATADOG_CONTAINER_KEY, container));
        return instanceClient.resetInstance(getProjectZoneInstanceNameOfContainer(container, projectName))
                             .getSelfLink();
    }

    public boolean isOperationComplete (String operationId) {
        if (operationId == null) return false;
        if (operationId.startsWith(ProjectZoneOperationName.SERVICE_ADDRESS)) {
            operationId = operationId.substring(ProjectZoneOperationName.SERVICE_ADDRESS.length());
        }
        log.info("Checking status of Google Compute Operation {}", operationId);
        Operation zoneOperation = zoneOperationClient.getZoneOperation(ProjectZoneOperationName.parse(operationId));
        return zoneOperation.getProgress() >= 100;
    }

    public String recreateInstanceInInstanceGroup (GcpComputeInstanceContainer container) {
        String group = getContainerGroup(container);
        String instanceName = ProjectZoneInstanceName.newBuilder()
                                                     .setInstance(container.getInstanceName())
                                                     .setZone(container.getZone())
                                                     .setProject(projectName.getProject())
                                                     .build()
                                                     .toString();
        if (ProjectZoneInstanceGroupManagerName.isParsableFrom(group))
            return recreateInstanceInZoneInstanceGroup(instanceName, group);
        else if (ProjectRegionInstanceGroupManagerName.isParsableFrom(group))
            return recreateInstanceInRegionInstanceGroup(instanceName, group);
        throw new ChaosException(GcpComputeChaosErrorCode.GCP_COMPUTE_GENERIC_ERROR);
    }

    private String recreateInstanceInZoneInstanceGroup (String instanceName, String group) {
        ProjectZoneInstanceGroupManagerName projectZoneInstanceGroupManagerName = ProjectZoneInstanceGroupManagerName.parse(
                group);
        InstanceGroupManagersRecreateInstancesRequest request;
        request = InstanceGroupManagersRecreateInstancesRequest.newBuilder().addInstances(instanceName).build();
        Operation operation = instanceGroupManagerClient.recreateInstancesInstanceGroupManager(
                projectZoneInstanceGroupManagerName,
                request);
        log.debug("Operation to recreate instance created: {}", v("operation", operation));
        return operation.getSelfLink();
    }

    private String recreateInstanceInRegionInstanceGroup (String instanceName, String group) {
        ProjectRegionInstanceGroupManagerName projectRegionInstanceGroupManagerName = ProjectRegionInstanceGroupManagerName
                .parse(group);
        RegionInstanceGroupManagersRecreateRequest request;
        request = RegionInstanceGroupManagersRecreateRequest.newBuilder().addInstances(instanceName).build();
        Operation operation = regionInstanceGroupManagerClient.recreateInstancesRegionInstanceGroupManager(
                projectRegionInstanceGroupManagerName,
                request);
        log.debug("Operation to recreate instance created: {}", v("operation", operation));
        return operation.getSelfLink();
    }

    public String getLatestInstanceId (String instanceName, String zone) {
        return instanceClient.getInstance(ProjectZoneInstanceName.of(instanceName, projectName.getProject(), zone))
                             .getId();
    }

    public Fingerprint<List<String>> getFirewallTags (GcpComputeInstanceContainer container) {
        ProjectZoneInstanceName instanceName = getProjectZoneInstanceNameOfContainer(container, projectName);
        Tags tags = instanceClient.getInstance(instanceName).getTags();
        return fingerprintTags(tags);
    }

    private static Fingerprint<List<String>> fingerprintTags (Tags tags) {
        if (tags == null) return null;
        return new Fingerprint<>(tags.getFingerprint(), List.copyOf(tags.getItemsList()));
    }

    public String getBestEndpoint (String privateIPAddress, String publicIPAddress) {
        if (privateIPAddress != null && isAddressRoutable(privateIPAddress)) return privateIPAddress;
        return publicIPAddress;
    }

    public boolean isAddressRoutable (String privateIPAddress) {
        return routableCIDRBlocks.stream().anyMatch(subnetInfo -> {
            try {
                return subnetInfo.isInRange(privateIPAddress);
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    public static class Fingerprint<T> {
        private String fingerprint;
        private T object;

        public Fingerprint (String fingerprint, T object) {
            this.fingerprint = Objects.requireNonNull(fingerprint);
            this.object = Objects.requireNonNull(object);
        }

        public T get () {
            return object;
        }

        public String getFingerprint () {
            return fingerprint;
        }

        @Override
        public int hashCode () {
            return Objects.hash(fingerprint, object);
        }

        @Override
        public boolean equals (Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Fingerprint<?> that = (Fingerprint<?>) o;
            return fingerprint.equals(that.fingerprint) && object.equals(that.object);
        }
    }

    @Override
    public String getEndpoint (GcpComputeInstanceContainer container) {
        return container.getSSHEndpoint();
    }

    private Metadata getInstanceMetadata (ProjectZoneInstanceName instanceName) {
        return instanceClient.getInstance(instanceName).getMetadata();
    }

    private Map<String, String> itemListAsMap (List<Items> items) {
        if (items == null) return new HashMap<>();
        return items.stream().filter(Objects::nonNull).collect(Collectors.toMap(Items::getKey, Items::getValue));
    }

    private List<Items> mapAsItemList (Map<String, String> items) {
        return items.entrySet()
                    .stream()
                    .filter(Objects::nonNull)
                    .map(this::mapEntryAsItem)
                    .collect(Collectors.toList());
    }

    void waitForOperation (Operation operation) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        synchronized (operation) {
            executor.scheduleWithFixedDelay(() -> {
                synchronized (operation) {
                    if (isOperationComplete(operation.getSelfLink())) {
                        operation.notify();
                    }
                }
            }, 1, 1, TimeUnit.SECONDS);
            try {
                // 5 minute timeout since that is our default experiment timeout.
                operation.wait(1000 /* sec / mil */ * 60 /* min / sec */ * 5 /* minutes */);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChaosException(GcpComputeChaosErrorCode.GCP_COMPUTE_GENERIC_ERROR, e);
            } finally {
                executor.shutdown();
            }
        }
    }

    private Items mapEntryAsItem (Map.Entry<String, String> item) {
        String key = item.getKey();
        String value = item.getValue();
        return Items.newBuilder().setKey(key).setValue(value).build();
    }

    @Override
    public SSHCredentials getSshCredentials (GcpComputeInstanceContainer container) {
        addSSHKey(container);
        return container.getSSHCredentials();
    }

    @Override
    public String getRunningDirectory () {
        return "/run/";
    }

    void addSSHKey (GcpComputeInstanceContainer container) {
        addSSHKey(container, true);
    }

    void addSSHKey (GcpComputeInstanceContainer container, boolean allowRetry) {
        ProjectZoneInstanceName instanceName = getProjectZoneInstanceNameOfContainer(container, projectName);
        Metadata existingMetadata = getInstanceMetadata(instanceName);
        Map<String, String> metadataMap = itemListAsMap(existingMetadata.getItemsList());
        String sshKeys = metadataMap.get("ssh-keys");
        // Format keys into objects for comparison and manipulation as a LinkedHashSet
        // This maintains order but also duplicate detection.
        Set<GcpSSHKeyMetadata> existingKeys = new LinkedHashSet<>(GcpSSHKeyMetadata.parseMetadata(sshKeys != null ? sshKeys : ""));
        // If the key already exists, we're done here.
        if (!existingKeys.add(container.getGcpSSHKeyMetadata())) return;
        Predicate<GcpSSHKeyMetadata> isNewKey = gcpSSHKeyMetadata -> gcpSSHKeyMetadata.equals(container.getGcpSSHKeyMetadata());
        Predicate<GcpSSHKeyMetadata> isChaosKey = gcpSSHKeyMetadata -> Objects.equals(gcpSSHKeyMetadata.getUsername(),
                CHAOS_USERNAME);
        List<GcpSSHKeyMetadata> filteredKeys = existingKeys.stream()
                                                           .filter(isNewKey.or(isChaosKey.negate()))
                                                           .collect(Collectors.toList());
        log.info("Key did not exist in container {}, adding new public key to metadata",
                v(DATADOG_CONTAINER_KEY, container));
        // Format the list back into a String and put it back into the Metadata Map.
        metadataMap.put("ssh-keys", GcpSSHKeyMetadata.metadataFormat(filteredKeys));
        Metadata newMetadata = Metadata.newBuilder()
                                       .addAllItems(mapAsItemList(metadataMap))
                                       .setFingerprint(existingMetadata.getFingerprint())
                                       .build();
        Operation operation = instanceClient.setMetadataInstance(instanceName, newMetadata);
        waitForOperation(operation);
        if (allowRetry) addSSHKey(container, false);
    }

    @Override
    public void recycleContainer (GcpComputeInstanceContainer container) {
        String operationId = recreateInstanceInInstanceGroup(container);
        performTaskAfterOperationCompletes(operationId, container::updateUUID);
    }

    void performTaskAfterOperationCompletes (String operationSelfLink, Runnable task) {
        ScheduledExecutorService operationWatcher = Executors.newSingleThreadScheduledExecutor();
        ExecutorService taskRunner = Executors.newSingleThreadExecutor();
        taskRunner.submit(() -> {
            synchronized (operationSelfLink) {
                try {
                    operationSelfLink.wait(1000 /* sec / mil */ * 60 /* min / sec */ * 5 /* minutes */);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ChaosException(GcpComputeChaosErrorCode.GCP_COMPUTE_GENERIC_ERROR, e);
                } finally {
                    operationWatcher.shutdown();
                }
                task.run();
            }
        });
        operationWatcher.scheduleWithFixedDelay(() -> {
            synchronized (operationSelfLink) {
                if (isOperationComplete(operationSelfLink)) {
                    operationSelfLink.notify();
                    operationWatcher.shutdown();
                }
            }
        }, 5, 1, TimeUnit.SECONDS);
    }
}
