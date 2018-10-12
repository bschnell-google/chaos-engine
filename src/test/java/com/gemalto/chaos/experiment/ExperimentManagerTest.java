package com.gemalto.chaos.experiment;

import com.gemalto.chaos.calendar.HolidayManager;
import com.gemalto.chaos.container.Container;
import com.gemalto.chaos.experiment.enums.ExperimentState;
import com.gemalto.chaos.notification.NotificationManager;
import com.gemalto.chaos.platform.Platform;
import com.gemalto.chaos.platform.PlatformManager;
import com.gemalto.chaos.platform.impl.CloudFoundryApplicationPlatform;
import com.gemalto.chaos.platform.impl.CloudFoundryContainerPlatform;
import org.hamcrest.collection.IsIterableContainingInAnyOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(SpringJUnit4ClassRunner.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class ExperimentManagerTest {
    @Autowired
    private ExperimentManager experimentManager;
    @MockBean
    private NotificationManager notificationManager;
    @MockBean
    private PlatformManager platformManager;
    @MockBean
    private HolidayManager holidayManager;
    @Mock
    private Experiment experiment1;
    @Mock
    private Experiment experiment2;
    @Mock
    private Experiment experiment3;
    @Mock
    private Container container1;
    @Mock
    private Container container2;
    @Mock
    private Container container3;
    @MockBean
    private Platform platform;


    @Test
    public void startExperiments () {
        List<Container> containerList = new ArrayList<>();
        containerList.add(container1);
        containerList.add(container2);
        when(platformManager.getPlatforms()).thenReturn(Collections.singleton(platform));
        when(platform.startAttack()).thenReturn(platform);
        when(platform.generateExperimentRoster()).thenCallRealMethod();
        when(platform.getRoster()).thenReturn(containerList);
        when(platform.canExperiment()).thenReturn(true);
        when(container1.canExperiment()).thenReturn(true);
        when(container1.createExperiment()).thenReturn(experiment1);
        when(container2.canExperiment()).thenReturn(false);
        experimentManager.startExperiments();
        assertThat(experimentManager.getNewExperimentQueue(), hasItem(experiment1));
        verify(container2, times(1)).canExperiment();
        verify(container2, times(0)).createExperiment();
    }

    //SCT-6233
    public void noExperimentsOnHolidays () {
        CloudFoundryApplicationPlatform pcfApplicationPlatform = mock(CloudFoundryApplicationPlatform.class);
        List<Container> containerListApps = new ArrayList<>();
        containerListApps.add(container1);
        containerListApps.add(container2);
        List<Platform> platforms = new ArrayList<>();
        platforms.add(pcfApplicationPlatform);
        when(platformManager.getPlatforms()).thenReturn(platforms);
        when(pcfApplicationPlatform.startAttack()).thenReturn(pcfApplicationPlatform);
        when(pcfApplicationPlatform.getRoster()).thenReturn(containerListApps);
        when(pcfApplicationPlatform.generateExperimentRoster()).thenCallRealMethod();
        when(pcfApplicationPlatform.canExperiment()).thenReturn(true);
        when(container1.canExperiment()).thenReturn(true);
        when(container1.createExperiment()).thenReturn(experiment1);
        when(container1.getPlatform()).thenReturn(pcfApplicationPlatform);
        when(container2.canExperiment()).thenReturn(true);
        when(container2.createExperiment()).thenReturn(experiment2);
        when(container2.getPlatform()).thenReturn(pcfApplicationPlatform);
        when(experiment1.startExperiment(notificationManager)).thenReturn(true);
        when(experiment2.startExperiment(notificationManager)).thenReturn(true);
        when(experiment1.getContainer()).thenReturn(container1);
        when(experiment2.getContainer()).thenReturn(container2);
        when(holidayManager.isHoliday()).thenReturn(false);
        when(holidayManager.isOutsideWorkingHours()).thenReturn(true);
        experimentManager.startExperiments();
        Queue<Experiment> experiments = experimentManager.getNewExperimentQueue();
        experimentManager.updateExperimentStatus();
        Set<Experiment> activeExperiments = experimentManager.getActiveExperiments();
        int activeExperimentsCount = activeExperiments.size();
        assertEquals(0, activeExperimentsCount);
    }

    //SCT-5854
    @Test
    public void avoidOverlappingExperiments () {
        CloudFoundryApplicationPlatform pcfApplicationPlatform = mock(CloudFoundryApplicationPlatform.class);
        CloudFoundryContainerPlatform pcfContainerPlatform = mock(CloudFoundryContainerPlatform.class);
        List<Container> containerListApps = new ArrayList<>();
        containerListApps.add(container1);
        containerListApps.add(container2);
        List<Container> containerListContainers = new ArrayList<>();
        containerListContainers.add(container3);
        List<Platform> platforms = new ArrayList<>();
        platforms.add(pcfApplicationPlatform);
        platforms.add(pcfContainerPlatform);
        when(platformManager.getPlatforms()).thenReturn(platforms);
        when(pcfApplicationPlatform.startAttack()).thenReturn(pcfApplicationPlatform);
        when(pcfApplicationPlatform.getRoster()).thenReturn(containerListApps);
        when(pcfApplicationPlatform.generateExperimentRoster()).thenCallRealMethod();
        when(pcfApplicationPlatform.canExperiment()).thenReturn(true);
        when(pcfContainerPlatform.startAttack()).thenReturn(pcfContainerPlatform);
        when(pcfContainerPlatform.getRoster()).thenReturn(containerListContainers);
        when(pcfContainerPlatform.canExperiment()).thenReturn(true);
        when(pcfContainerPlatform.generateExperimentRoster()).thenCallRealMethod();
        when(container1.canExperiment()).thenReturn(true);
        when(container1.createExperiment()).thenReturn(experiment1);
        when(container1.getPlatform()).thenReturn(pcfApplicationPlatform);
        when(container2.canExperiment()).thenReturn(true);
        when(container2.createExperiment()).thenReturn(experiment2);
        when(container2.getPlatform()).thenReturn(pcfApplicationPlatform);
        when(container3.canExperiment()).thenReturn(true);
        when(container3.createExperiment()).thenReturn(experiment3);
        when(container3.getPlatform()).thenReturn(pcfContainerPlatform);
        when(experiment1.startExperiment(notificationManager)).thenReturn(true);
        when(experiment2.startExperiment(notificationManager)).thenReturn(true);
        when(experiment3.startExperiment(notificationManager)).thenReturn(true);
        when(experiment1.getContainer()).thenReturn(container1);
        when(experiment2.getContainer()).thenReturn(container2);
        when(experiment3.getContainer()).thenReturn(container3);
        when(holidayManager.isHoliday()).thenReturn(false);
        when(holidayManager.isOutsideWorkingHours()).thenReturn(false);
        experimentManager.startExperiments();
        Queue<Experiment> experiments = experimentManager.getNewExperimentQueue();
        int scheduledExperimentsCount = experiments.size();
        experimentManager.startExperiments();
        Queue<Experiment> experiments2 = experimentManager.getNewExperimentQueue();
        // new startExperiments invocation should not add new experiment until newAttackQueue is empty
        assertEquals(experiments, experiments2);
        experimentManager.updateExperimentStatus();
        Set<Experiment> activeExperiments = experimentManager.getActiveExperiments();
        int activeExperimentsCount = activeExperiments.size();
        //number active experiments should be equal to number of previously scheduled experiments
        assertEquals(scheduledExperimentsCount, activeExperimentsCount);
        //all active experiments should belong to same platform layer
        Platform experimentPlatform = activeExperiments.iterator().next().getContainer().getPlatform();
        assertEquals(1, activeExperiments.stream()
                                         .collect(Collectors.groupingBy(experiment -> experiment.getContainer()
                                                                                                .getPlatform()))
                                     .size());
    }

    @Test
    public void removeFinishedExperiments () {
        CloudFoundryApplicationPlatform pcfApplicationPlatform = mock(CloudFoundryApplicationPlatform.class);
        List<Container> containerListApps = new ArrayList<>();
        containerListApps.add(container1);
        containerListApps.add(container2);
        List<Platform> platforms = new ArrayList<>();
        platforms.add(pcfApplicationPlatform);
        when(platformManager.getPlatforms()).thenReturn(platforms);
        when(pcfApplicationPlatform.startAttack()).thenReturn(pcfApplicationPlatform);
        when(pcfApplicationPlatform.getRoster()).thenReturn(containerListApps);
        when(pcfApplicationPlatform.canExperiment()).thenReturn(true);
        when(pcfApplicationPlatform.generateExperimentRoster()).thenCallRealMethod();
        when(container1.canExperiment()).thenReturn(true);
        when(container1.createExperiment()).thenReturn(experiment1);
        when(container1.getPlatform()).thenReturn(pcfApplicationPlatform);
        when(container2.canExperiment()).thenReturn(true);
        when(container2.createExperiment()).thenReturn(experiment2);
        when(container2.getPlatform()).thenReturn(pcfApplicationPlatform);
        when(experiment1.startExperiment(notificationManager)).thenReturn(true);
        when(experiment2.startExperiment(notificationManager)).thenReturn(true);
        when(experiment1.getContainer()).thenReturn(container1);
        when(experiment2.getContainer()).thenReturn(container2);
        when(holidayManager.isHoliday()).thenReturn(false);
        when(holidayManager.isOutsideWorkingHours()).thenReturn(false);
        //schedule experiments
        experimentManager.startExperiments();
        experimentManager.updateExperimentStatus();
        //check they are active
        assertEquals(2, experimentManager.getActiveExperiments().size());
        when(experiment1.getExperimentState()).thenReturn(ExperimentState.FINISHED);
        when(experiment2.getExperimentState()).thenReturn(ExperimentState.NOT_YET_STARTED);
        //one experiment to be removed
        experimentManager.updateExperimentStatus();
        assertEquals(1, experimentManager.getActiveExperiments().size());
        when(experiment2.getExperimentState()).thenReturn(ExperimentState.FINISHED);
        experimentManager.updateExperimentStatus();
        //all experiments should be removed
        assertEquals(0, experimentManager.getActiveExperiments().size());
    }


    @Test
    public void experimentContainerId () {
        Long containerId = new Random().nextLong();
        Collection<Platform> platforms = Collections.singleton(platform);
        List<Container> roster = new ArrayList<>();
        roster.add(container1);
        roster.add(container2);
        when(platformManager.getPlatforms()).thenReturn(platforms);
        when(platform.getRoster()).thenReturn(roster);
        when(container1.getIdentity()).thenReturn(containerId);
        when(container2.getIdentity()).thenReturn(containerId + 1);
        doReturn(experiment1).when(container1).createExperiment();
        assertThat(experimentManager.experimentContainerId(containerId), IsIterableContainingInAnyOrder.containsInAnyOrder(experiment1));
        verify(container1, times(1)).createExperiment();
        verify(container2, times(0)).createExperiment();
    }

    @Configuration
    static class ExperimentManagerTestConfiguration {
        @Autowired
        private NotificationManager notificationManager;
        @Autowired
        private PlatformManager platformManager;
        @Autowired
        private HolidayManager holidayManager;

        @Bean
        ExperimentManager experimentManager () {
            return new ExperimentManager(notificationManager, platformManager, holidayManager);
        }
    }
}