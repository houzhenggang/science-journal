/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.RemoteException;

import com.google.android.apps.forscience.javalib.Delay;
import com.google.android.apps.forscience.javalib.FailureListener;
import com.google.android.apps.forscience.javalib.FallibleConsumer;
import com.google.android.apps.forscience.javalib.MaybeConsumer;
import com.google.android.apps.forscience.whistlepunk.data.GoosciSensorLayout;
import com.google.android.apps.forscience.whistlepunk.metadata.ApplicationLabel;
import com.google.android.apps.forscience.whistlepunk.metadata.Experiment;
import com.google.android.apps.forscience.whistlepunk.metadata.Project;
import com.google.android.apps.forscience.whistlepunk.metadata.SensorTrigger;
import com.google.android.apps.forscience.whistlepunk.sensorapi.FakeBleClient;
import com.google.android.apps.forscience.whistlepunk.sensorapi.MemorySensorEnvironment;
import com.google.android.apps.forscience.whistlepunk.sensorapi.RecordingSensorObserver;
import com.google.android.apps.forscience.whistlepunk.sensorapi.SensorStatusListener;
import com.google.android.apps.forscience.whistlepunk.sensordb.InMemorySensorDatabase;
import com.google.android.apps.forscience.whistlepunk.wireapi.TransportableSensorOptions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ProxyRecorderControllerTest {
    private static final TransportableSensorOptions BLANK_OPTIONS =
            new TransportableSensorOptions(Maps.<String, String>newHashMap());
    private static final StubDataController DATA_CONTROLLER = new StubDataController() {
        @Override
        public void startRun(Experiment experiment,
                MaybeConsumer<ApplicationLabel> onSuccess) {
            onSuccess.success(new ApplicationLabel(
                    ApplicationLabel.TYPE_RECORDING_START, "start", "start", 0));
        }

        @Override
        public void stopRun(Experiment experiment, String runId,
                List<GoosciSensorLayout.SensorLayout> sensorLayouts,
                MaybeConsumer<ApplicationLabel> onSuccess) {
            onSuccess.success(new ApplicationLabel(
                    ApplicationLabel.TYPE_RECORDING_STOP, "stop", "start", 10));
        }
    };
    private final AlwaysAllowedPolicy mPolicy = new AlwaysAllowedPolicy();
    private final FailureListener mFailureListener =
            new ExplodingFactory().makeListenerForOperation("test");
    private final RecordingSensorObserver mObserver = new RecordingSensorObserver();
    private final RecordingStatusListener mStatusListener = new RecordingStatusListener();
    private SensorRegistry mRegistry = new AllSensorsRegistry();
    private final MemorySensorEnvironment mEnvironment = new MemorySensorEnvironment(
            new InMemorySensorDatabase().makeSimpleRecordingController(),
            new FakeBleClient(null), new MemorySensorHistoryStorage(), null);

    @Test
    public void mostRecentSensorIds() throws RemoteException {
        final String id1 = "id1";
        final String id2 = "id2";
        RecorderListenerRegistry listenerRegistry = new RecorderListenerRegistry();
        RecorderControllerTestImpl rc = new RecorderControllerTestImpl(listenerRegistry);
        ProxyRecorderController prc = new ProxyRecorderController(rc, mPolicy, mFailureListener);

        // Blank only at the beginning of time
        assertEquals(Lists.newArrayList(), prc.getMostRecentObservedSensorIds());
        String observerId1 = rc.startObserving(id1, Collections.<SensorTrigger>emptyList(),
                mObserver, mStatusListener, BLANK_OPTIONS);
        assertEquals(Lists.newArrayList(id1), prc.getMostRecentObservedSensorIds());
        String observerId2 = rc.startObserving(id2, Collections.<SensorTrigger>emptyList(),
                mObserver, mStatusListener, BLANK_OPTIONS);
        assertEquals(Lists.newArrayList(id1, id2), prc.getMostRecentObservedSensorIds());

        // Pausing doesn't clear most recent list
        String pauseId = rc.pauseObservingAll();
        assertEquals(Lists.newArrayList(id1, id2), prc.getMostRecentObservedSensorIds());
        rc.resumeObservingAll(pauseId);
        assertEquals(Lists.newArrayList(id1, id2), prc.getMostRecentObservedSensorIds());

        // Stopping one of several does change list
        rc.stopObserving(id1, observerId1);
        assertEquals(Lists.newArrayList(id2), prc.getMostRecentObservedSensorIds());

        // But stopping the last leaves it in the list
        rc.stopObserving(id2, observerId2);
        assertEquals(Lists.newArrayList(id2), prc.getMostRecentObservedSensorIds());

        RecorderControllerTestImpl rc2 = new RecorderControllerTestImpl(listenerRegistry);
        ProxyRecorderController prc2 = new ProxyRecorderController(rc2, mPolicy, mFailureListener);
        assertEquals(Lists.newArrayList(id2), prc2.getMostRecentObservedSensorIds());
    }

    @Test
    public void gettingRecentIdsThroughListener() throws RemoteException {
        final String id1 = "id1";
        final String id2 = "id2";
        RecorderListenerRegistry listenerRegistry = new RecorderListenerRegistry();
        RecorderControllerTestImpl rc = new RecorderControllerTestImpl(listenerRegistry);
        ProxyRecorderController prc = new ProxyRecorderController(rc, mPolicy, mFailureListener);

        RecordingStateListener stateListener = new RecordingStateListener();
        prc.addRecordingStateListener("listenerId", stateListener);
        assertEquals(Lists.newArrayList(), stateListener.recentObservedSensorIds);
        String observerId1 = rc.startObserving(id1, Collections.<SensorTrigger>emptyList(),
                mObserver, mStatusListener, BLANK_OPTIONS);
        assertEquals(Lists.newArrayList(id1), stateListener.recentObservedSensorIds);
        String observerId2 = rc.startObserving(id2, Collections.<SensorTrigger>emptyList(),
                mObserver, mStatusListener, BLANK_OPTIONS);
        assertEquals(Lists.newArrayList(id1, id2), stateListener.recentObservedSensorIds);

        // Pausing doesn't clear most recent list
        String pauseId = rc.pauseObservingAll();
        assertEquals(Lists.newArrayList(id1, id2), stateListener.recentObservedSensorIds);
        rc.resumeObservingAll(pauseId);
        assertEquals(Lists.newArrayList(id1, id2), stateListener.recentObservedSensorIds);

        // Stopping one of several does change list
        rc.stopObserving(id1, observerId1);
        assertEquals(Lists.newArrayList(id2), stateListener.recentObservedSensorIds);

        // But stopping the last removes from the list
        rc.stopObserving(id2, observerId2);
        assertEquals(Lists.newArrayList(), stateListener.recentObservedSensorIds);

        rc.startObserving(id1, Collections.<SensorTrigger>emptyList(), mObserver, mStatusListener,
                BLANK_OPTIONS);
        assertEquals(Lists.newArrayList(id1), stateListener.recentObservedSensorIds);

        // Connecting another proxy finds the same value
        ProxyRecorderController prc2 = new ProxyRecorderController(rc, mPolicy, mFailureListener);

        RecordingStateListener stateListener2 = new RecordingStateListener();
        prc2.addRecordingStateListener("listenerId2", stateListener2);
        assertEquals(Lists.newArrayList(id1), stateListener2.recentObservedSensorIds);
    }

    @Test
    public void preventMultipleListenersSameId() throws RemoteException {
        RecorderListenerRegistry listenerRegistry = new RecorderListenerRegistry();
        RecorderControllerTestImpl rc = new RecorderControllerTestImpl(listenerRegistry);

        ProxyRecorderController prc = new ProxyRecorderController(rc, mPolicy, mFailureListener);

        prc.addRecordingStateListener("id1", new RecordingStateListener());
        try {
            prc.addRecordingStateListener("id1", new RecordingStateListener());
            fail("Should have thrown, because only one listener at a time right now!");
        } catch (IllegalStateException expected) {
            // success!
        }
    }

    @Test
    public void addAndRemoveListeners() throws RemoteException {
        RecorderListenerRegistry listenerRegistry = new RecorderListenerRegistry();
        RecorderControllerTestImpl rc = new RecorderControllerTestImpl(listenerRegistry);
        ProxyRecorderController prc = new ProxyRecorderController(rc, mPolicy, mFailureListener);
        Project project = new Project(14159);
        Experiment experiment = new Experiment(1618);
        experiment.setTitle("experimentName");
        rc.setSelectedExperiment(experiment);

        RecordingStateListener stateListener = new RecordingStateListener();
        prc.addRecordingStateListener("listenerId", stateListener);
        assertEquals(Lists.newArrayList(), stateListener.recentObservedSensorIds);
        String observerId1 = rc.startObserving("id1", Collections.<SensorTrigger>emptyList(),
                mObserver, mStatusListener, BLANK_OPTIONS);
        assertEquals(Arrays.asList("id1"), stateListener.recentObservedSensorIds);

        listenerRegistry.onSourceStatus("id1", SensorStatusListener.STATUS_CONNECTED);

        assertFalse(stateListener.recentIsRecording);
        rc.startRecording(null, project);
        assertTrue(stateListener.recentIsRecording);

        rc.forceHasData("id1", true);

        rc.stopRecording();
        assertFalse(stateListener.recentIsRecording);
        prc.removeRecordingStateListener("listenerId");
        assertFalse(stateListener.recentIsRecording);
        rc.startRecording(null, project);
        // Still false, because we've stopped listening
        assertFalse(stateListener.recentIsRecording);
        rc.stopRecording();

        rc.stopObserving("id1", observerId1);

        // Still stale, because we've stopped listening
        assertEquals(Arrays.asList("id1"), stateListener.recentObservedSensorIds);
    }

    @Test
    public void failsStartRecording() throws RemoteException {
        RecorderListenerRegistry listenerRegistry = new RecorderListenerRegistry();
        RecorderControllerTestImpl rc = new RecorderControllerTestImpl(listenerRegistry);
        ProxyRecorderController prc = new ProxyRecorderController(rc, mPolicy, mFailureListener);
        Project project = new Project(14159);
        Experiment experiment = new Experiment(1618);
        experiment.setTitle("experimentName");
        rc.setSelectedExperiment(experiment);

        RecordingStateListener stateListener = new RecordingStateListener();
        prc.addRecordingStateListener("listenerId", stateListener);
        rc.startObserving("id1", Collections.<SensorTrigger>emptyList(), mObserver, mStatusListener,
                BLANK_OPTIONS);

        // Not connected - no data
        assertFalse(stateListener.recentIsRecording);
        rc.startRecording(null, project);
        assertFalse(stateListener.recentIsRecording);

        listenerRegistry.onSourceStatus("id1", SensorStatusListener.STATUS_CONNECTING);
        rc.startRecording(null, project);
        assertFalse(stateListener.recentIsRecording);

        listenerRegistry.onSourceStatus("id1", SensorStatusListener.STATUS_CONNECTED);
        rc.startRecording(null, project);
        assertTrue(stateListener.recentIsRecording);
    }

    @Test
    public void failsStopRecording() throws RemoteException {
        RecorderListenerRegistry listenerRegistry = new RecorderListenerRegistry();
        RecorderControllerTestImpl rc = new RecorderControllerTestImpl(listenerRegistry);
        ProxyRecorderController prc = new ProxyRecorderController(rc, mPolicy, mFailureListener);
        Project project = new Project(14159);
        Experiment experiment = new Experiment(1618);
        experiment.setTitle("experimentName");
        rc.setSelectedExperiment(experiment);

        RecordingStateListener stateListener = new RecordingStateListener();
        prc.addRecordingStateListener("listenerId", stateListener);
        rc.startObserving("id1", Collections.<SensorTrigger>emptyList(), mObserver, mStatusListener,
                BLANK_OPTIONS);

        listenerRegistry.onSourceStatus("id1", SensorStatusListener.STATUS_CONNECTED);
        rc.startRecording(null, project);

        // No data yet -- stop recording should not actually stop
        rc.stopRecording();
        assertTrue(stateListener.recentIsRecording);

        // Forcing stop recording should work, though.
        rc.stopRecordingWithoutSaving();
        assertFalse(stateListener.recentIsRecording);

        // This time we will disconnect during recording, and stopRecording should also fail.
        listenerRegistry.onSourceStatus("id1", SensorStatusListener.STATUS_CONNECTED);
        rc.startRecording(null, project);
        listenerRegistry.onSourceStatus("id1", SensorStatusListener.STATUS_DISCONNECTED);
        assertTrue(stateListener.recentIsRecording);

        rc.stopRecording();
        assertTrue(stateListener.recentIsRecording);

        // Forcing stop recording should work, though.
        rc.stopRecordingWithoutSaving();
        assertFalse(stateListener.recentIsRecording);
    }

    private class RecorderControllerTestImpl extends  RecorderControllerImpl {
        RecorderControllerTestImpl(RecorderListenerRegistry listenerRegistry) {
            super(null, mRegistry, mEnvironment, listenerRegistry, null, DATA_CONTROLLER, null,
                    Delay.millis(0));
            this.setRecordActivityInForeground(true);
        }

        @Override
        protected void withBoundRecorderService(FallibleConsumer<RecorderService> c) {
            try {
                c.take(new MockRecorderService());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        void trackStopRecording(Context context, ApplicationLabel stopRecordingLabel,
                List<GoosciSensorLayout.SensorLayout> sensorLayouts) {
            // Do nothing.
        }

        @Override
        void ensureUnarchived(Experiment experiment, Project project,
                DataController dc) {
            // do nothing
        }

        void forceHasData(String sensorId, boolean hasData) {
            StatefulRecorder sr = getRecorders().get(sensorId);
            sr.forceHasDataForTesting(hasData);
        }
    }
}