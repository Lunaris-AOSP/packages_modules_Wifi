/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.server.wifi.rtt;

import static android.net.wifi.rtt.WifiRttManager.CHARACTERISTICS_KEY_BOOLEAN_LCI;
import static android.net.wifi.rtt.WifiRttManager.CHARACTERISTICS_KEY_BOOLEAN_LCR;
import static android.net.wifi.rtt.WifiRttManager.CHARACTERISTICS_KEY_BOOLEAN_ONE_SIDED_RTT;

import static com.android.server.wifi.WifiSettingsConfigStore.WIFI_VERBOSE_LOGGING_ENABLED;
import static com.android.server.wifi.rtt.RttTestUtils.compareListContentsNoOrdering;
import static com.android.server.wifi.rtt.RttTestUtils.getDummyRangingResults;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.test.MockAnswerUtil;
import android.app.test.TestAlarmManager;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.MacAddress;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.aware.IWifiAwareMacAddressProvider;
import android.net.wifi.aware.MacAddrMapping;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.rtt.IRttCallback;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.ResponderConfig;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.Clock;
import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.MockResources;
import com.android.server.wifi.SsidTranslator;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiSettingsConfigStore;
import com.android.server.wifi.hal.WifiRttController;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.wifi.resources.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Unit test harness for the RttServiceImpl class.
 */
@SmallTest
public class RttServiceImplTest extends WifiBaseTest {

    private static final int BACKGROUND_PROCESS_EXEC_GAP_MS = 10 * 60 * 1000;  // 10 minutes.
    private static final int MEASUREMENT_DURATION = 1000;

    private RttServiceImplSpy mDut;
    private TestLooper mMockLooper;
    private TestAlarmManager mAlarmManager;
    private PowerManager mMockPowerManager;
    private BroadcastReceiver mPowerBcastReceiver;
    private BroadcastReceiver mLocationModeReceiver;
    private MockResources mMockResources = new MockResources();

    private final String mPackageName = "some.package.name.for.rtt.app";
    private final String mFeatureId = "some.feature.name.for.rtt.app";
    private int mDefaultUid = 1500;
    private WorkSource mDefaultWs = new WorkSource(mDefaultUid);

    private ArgumentCaptor<Integer> mIntCaptor = ArgumentCaptor.forClass(Integer.class);
    private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor = ArgumentCaptor
            .forClass(IBinder.DeathRecipient.class);
    private ArgumentCaptor<RangingRequest> mRequestCaptor = ArgumentCaptor.forClass(
            RangingRequest.class);
    private ArgumentCaptor<List> mListCaptor = ArgumentCaptor.forClass(List.class);
    private ArgumentCaptor<HalDeviceManager.InterfaceRttControllerLifecycleCallback>
            mRttLifecycleCbCaptor = ArgumentCaptor.forClass(
            HalDeviceManager.InterfaceRttControllerLifecycleCallback.class);
    private ArgumentCaptor<WifiRttController.RttControllerRangingResultsCallback>
            mRangingResultsCbCaptor = ArgumentCaptor.forClass(
            WifiRttController.RttControllerRangingResultsCallback.class);

    private BinderLinkToDeathAnswer mBinderLinkToDeathCounter = new BinderLinkToDeathAnswer();
    private BinderUnlinkToDeathAnswer mBinderUnlinkToDeathCounter = new BinderUnlinkToDeathAnswer();

    private InOrder mInOrder;
    private Bundle mExtras;

    @Mock
    public Context mockContext;

    @Mock
    public ActivityManager mockActivityManager;

    @Mock
    public Clock mockClock;

    @Mock
    public WifiRttController mockRttControllerHal;

    @Mock
    public HalDeviceManager mockHalDeviceManager;

    @Mock
    public RttMetrics mockMetrics;

    @Mock
    public WifiAwareManager mockAwareManager;

    @Mock
    public WifiPermissionsUtil mockPermissionUtil;

    @Mock
    public IBinder mockIbinder;

    @Mock
    public IRttCallback mockCallback;

    @Mock
    WifiSettingsConfigStore mWifiSettingsConfigStore;
    @Mock
    WifiConfigManager mWifiConfigManager;
    WifiConfiguration mWifiConfiguration = new WifiConfiguration();
    @Mock
    SsidTranslator mSsidTranslator;

    /**
     * Using instead of spy to avoid native crash failures - possibly due to
     * spy's copying of state.
     */
    private class RttServiceImplSpy extends RttServiceImpl {
        public int fakeUid;

        RttServiceImplSpy(Context context) {
            super(context);
        }

        /**
         * Return the fake UID instead of the real one: pseudo-spy
         * implementation.
         */
        @Override
        public int getMockableCallingUid() {
            return fakeUid;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new RttServiceImplSpy(mockContext);
        mDut.fakeUid = mDefaultUid;
        mMockLooper = new TestLooper();
        mExtras = new Bundle();

        when(mockContext.checkCallingOrSelfPermission(
                android.Manifest.permission.LOCATION_HARDWARE)).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        when(mockContext.getResources()).thenReturn(mMockResources);
        mMockResources.setInteger(
                R.integer.config_wifiRttBackgroundExecGapMs, BACKGROUND_PROCESS_EXEC_GAP_MS);
        mMockResources.setStringArray(R.array.config_wifiBackgroundRttThrottleExceptionList,
                new String[0]);

        mAlarmManager = new TestAlarmManager();
        when(mockContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());
        mInOrder = inOrder(mAlarmManager.getAlarmManager(), mockContext);

        when(mockContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(
                mockActivityManager);
        when(mockActivityManager.getUidImportance(anyInt())).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);

        when(mockPermissionUtil.checkCallersLocationPermission(eq(mPackageName), eq(mFeatureId),
                anyInt(), anyBoolean(), nullable(String.class))).thenReturn(true);
        when(mockPermissionUtil.isLocationModeEnabled()).thenReturn(true);
        when(mockRttControllerHal.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(
                true);
        when(mockHalDeviceManager.isStarted()).thenReturn(true);
        when(mWifiSettingsConfigStore.get(eq(WIFI_VERBOSE_LOGGING_ENABLED))).thenReturn(true);

        mMockPowerManager = new PowerManager(mockContext, mock(IPowerManager.class),
                mock(IThermalService.class), new Handler(mMockLooper.getLooper()));
        when(mMockPowerManager.isDeviceIdleMode()).thenReturn(false);
        when(mockContext.getSystemServiceName(PowerManager.class)).thenReturn(
                Context.POWER_SERVICE);
        when(mockContext.getSystemService(PowerManager.class)).thenReturn(mMockPowerManager);

        doAnswer(mBinderLinkToDeathCounter).when(mockIbinder).linkToDeath(any(), anyInt());
        doAnswer(mBinderUnlinkToDeathCounter).when(mockIbinder).unlinkToDeath(any(), anyInt());

        mDut.start(mMockLooper.getLooper(), mockClock, mockAwareManager, mockMetrics,
                mockPermissionUtil, mWifiSettingsConfigStore, mockHalDeviceManager,
                mWifiConfigManager, mSsidTranslator);
        mMockLooper.dispatchAll();
        ArgumentCaptor<BroadcastReceiver> bcastRxCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mockContext).registerReceiver(bcastRxCaptor.capture(),
                argThat(filter -> filter.hasAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)));
        verify(mockContext).registerReceiverForAllUsers(bcastRxCaptor.capture(),
                argThat(filter -> filter.hasAction(LocationManager.MODE_CHANGED_ACTION)),
                eq(null), any(Handler.class));
        mPowerBcastReceiver = bcastRxCaptor.getAllValues().get(0);
        mLocationModeReceiver = bcastRxCaptor.getAllValues().get(1);

        verify(mockHalDeviceManager).registerRttControllerLifecycleCallback(
                mRttLifecycleCbCaptor.capture(), any());
        mRttLifecycleCbCaptor.getValue().onNewRttController(mockRttControllerHal);
        verify(mockRttControllerHal).registerRangingResultsCallback(
                mRangingResultsCbCaptor.capture());

        validateCorrectRttStatusChangeBroadcast();
        assertTrue(mDut.isAvailable());
    }

    @After
    public void tearDown() throws Exception {
        assertEquals("Binder links != unlinks to death (size)",
                mBinderLinkToDeathCounter.mUniqueExecs.size(),
                mBinderUnlinkToDeathCounter.mUniqueExecs.size());
        assertEquals("Binder links != unlinks to death", mBinderLinkToDeathCounter.mUniqueExecs,
                mBinderUnlinkToDeathCounter.mUniqueExecs);
    }

    /**
     * Validate that we react correctly (i.e. enable/disable RTT availability) when
     * notified that the RTT controller has disappeared and appeared.
     */
    @Test
    public void testRttControllerLifecycle() throws Exception {
        // RTT controller disappears
        mRttLifecycleCbCaptor.getValue().onRttControllerDestroyed();
        assertFalse(mDut.isAvailable());
        validateCorrectRttStatusChangeBroadcast();

        // RTT controller re-appears
        mRttLifecycleCbCaptor.getValue().onNewRttController(mockRttControllerHal);
        verify(mockRttControllerHal, times(2)).registerRangingResultsCallback(any());
        assertTrue(mDut.isAvailable());
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal);
        validateCorrectRttStatusChangeBroadcast();


        // RTT controller switch - previous is invalid and new one is created. Should not send the
        // broadcast
        mRttLifecycleCbCaptor.getValue().onNewRttController(mockRttControllerHal);
        verify(mockRttControllerHal, times(3)).registerRangingResultsCallback(any());
        mInOrder.verify(mockContext, never())
                .sendBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL));
    }

    /**
     * Validate successful ranging flow.
     */
    @Test
    public void testRangingFlow() throws Exception {
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<Pair<List<RangingResult>, List<RangingResult>>> results = new ArrayList<>();

        for (int i = 0; i < numIter; ++i) { // even: MC, non-MC, Aware, odd: MC only
            if (i % 2 == 0) {
                requests[i] = RttTestUtils.getDummyRangingRequestMcOnly((byte) i,
                        RangingRequest.getDefaultRttBurstSize());
            } else {
                requests[i] = RttTestUtils.getDummyRangingRequest((byte) i);
            }
            results.add(RttTestUtils.getDummyRangingResults(requests[i]));
        }

        ClockAnswer clock = new ClockAnswer();
        doAnswer(clock).when(mockClock).getWallClockMillis();
        clock.time = 100;
        // (1) request 10 ranging operations
        for (int i = 0; i < numIter; ++i) {
            mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, requests[i],
                    mockCallback, mExtras);
        }
        mMockLooper.dispatchAll();

        for (int i = 0; i < numIter; ++i) {
            clock.time += MEASUREMENT_DURATION;
            // (2) verify that the request was issued to the WifiRttController
            verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(requests[i]));
            verifyWakeupSet(i % 2 != 0, 0);

            // (3) HAL calls back with result
            mRangingResultsCbCaptor.getValue()
                    .onRangingResults(mIntCaptor.getValue(), results.get(i).second);
            mMockLooper.dispatchAll();

            // (4) verify that results dispatched
            verify(mockCallback).onRangingResults(results.get(i).second);
            verifyWakeupCancelled();

            // (5) replicate results - shouldn't dispatch another callback
            mRangingResultsCbCaptor.getValue()
                    .onRangingResults(mIntCaptor.getValue(), results.get(i).second);
            mMockLooper.dispatchAll();
        }

        // verify metrics
        for (int i = 0; i < numIter; ++i) {
            verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(requests[i]));
            verify(mockMetrics).recordResult(eq(requests[i]), eq(results.get(i).second),
                    eq(MEASUREMENT_DURATION));
        }
        verify(mockMetrics, times(numIter)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate a successful secure ranging flow.
     */
    @Test
    public void testSecureRanging() throws RemoteException {
        RangingRequest request = RttTestUtils.getDummySecureRangingRequest(
                RangingRequest.SECURITY_MODE_OPPORTUNISTIC);
        mWifiConfiguration.preSharedKey = "TEST_PASSWORD";
        WifiSsid ssid = request.mRttPeers.get(
                1).getSecureRangingConfig().getPasnConfig().getWifiSsid();
        when(mWifiConfigManager.getConfiguredNetworkWithPassword(eq(ssid),
                eq(WifiConfiguration.SECURITY_TYPE_SAE))).thenReturn(mWifiConfiguration);
        when(mSsidTranslator.getTranslatedSsid(eq(ssid))).thenReturn(ssid);

        // Make sure the second peer is configured with no password for SAE.
        assertNull(request.mRttPeers.get(1).getSecureRangingConfig().getPasnConfig().getPassword());

        ClockAnswer clock = new ClockAnswer();
        doAnswer(clock).when(mockClock).getWallClockMillis();
        clock.time = 100;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request, mockCallback,
                mExtras);
        mMockLooper.dispatchAll();
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), mRequestCaptor.capture());
        verifyWakeupSet(false, 0);
        RangingRequest halRequest = mRequestCaptor.getValue();
        assertNotEquals("Request to WifiRttController is not null", null, halRequest);
        assertEquals("Size of request", request.mRttPeers.size(), halRequest.mRttPeers.size());

        for (int i = 0; i < request.mRttPeers.size(); ++i) {
            assertEquals("SecureRangingConfig is not same",
                    request.mRttPeers.get(i).getSecureRangingConfig(),
                    halRequest.mRttPeers.get(i).getSecureRangingConfig());
        }

        // Make sure password is set for second peer from WifiConfiguration for the SAE.
        assertEquals("Password is not set", "TEST_PASSWORD", halRequest.mRttPeers.get(
                1).getSecureRangingConfig().getPasnConfig().getPassword());

        // Verify ranging results are processed correctly
        Pair<List<RangingResult>, List<RangingResult>> resultsPair = getDummyRangingResults(
                halRequest);
        mRangingResultsCbCaptor.getValue().onRangingResults(mIntCaptor.getValue(),
                resultsPair.first);
        mMockLooper.dispatchAll();
        verify(mockCallback).onRangingResults(mListCaptor.capture());
        assertTrue(compareListContentsNoOrdering(resultsPair.second, mListCaptor.getValue()));

        verifyWakeupCancelled();
        verifyNoMoreInteractions(mockRttControllerHal, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate a successful ranging flow with PeerHandles (i.e. verify translations)
     */
    @Test
    public void testRangingFlowUsingAwarePeerHandles() throws Exception {
        if (SdkLevel.isAtLeastT()) {
            when(mockPermissionUtil.checkNearbyDevicesPermission(any(), eq(true), any()))
                    .thenReturn(true);
        }
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0xA);
        PeerHandle peerHandle1 = new PeerHandle(1022);
        PeerHandle peerHandle2 = new PeerHandle(1023);
        PeerHandle peerHandle3 = new PeerHandle(1024);
        request.mRttPeers.add(ResponderConfig.fromWifiAwarePeerHandleWithDefaults(peerHandle1));
        request.mRttPeers.add(ResponderConfig.fromWifiAwarePeerHandleWithDefaults(peerHandle2));
        request.mRttPeers.add(ResponderConfig.fromWifiAwarePeerHandleWithDefaults(peerHandle3));
        MacAddrMapping peerMapping1 = new MacAddrMapping();
        MacAddrMapping peerMapping2 = new MacAddrMapping();
        MacAddrMapping peerMapping3 = new MacAddrMapping();
        peerMapping1.peerId = peerHandle1.peerId;
        peerMapping2.peerId = peerHandle2.peerId;
        peerMapping3.peerId = peerHandle3.peerId;
        peerMapping1.macAddress = MacAddress.fromString("AA:BB:CC:DD:EE:FF").toByteArray();
        peerMapping2.macAddress = MacAddress.fromString("BB:BB:BB:EE:EE:EE").toByteArray();
        peerMapping3.macAddress = null; // bad answer from Aware (expired?)
        MacAddrMapping[] peerHandleToMacList = {peerMapping1, peerMapping2, peerMapping3};

        AwareTranslatePeerHandlesToMac answer = new AwareTranslatePeerHandlesToMac(mDefaultUid,
                peerHandleToMacList);
        doAnswer(answer).when(mockAwareManager).requestMacAddresses(anyInt(), any(), any());

        // issue request
        ClockAnswer clock = new ClockAnswer();
        doAnswer(clock).when(mockClock).getWallClockMillis();
        clock.time = 100;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // verify that the request is translated from the PeerHandle issued to WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), mRequestCaptor.capture());
        verifyWakeupSet(true, 0);

        RangingRequest finalRequest = mRequestCaptor.getValue();
        assertNotEquals("Request to WifiRttController is not null", null, finalRequest);
        assertEquals("Size of request", request.mRttPeers.size() - 1,
                finalRequest.mRttPeers.size());
        assertEquals("Aware peer 1 MAC", MacAddress.fromBytes(peerMapping1.macAddress),
                finalRequest.mRttPeers.get(finalRequest.mRttPeers.size() - 2).macAddress);
        assertEquals("Aware peer 2 MAC", MacAddress.fromBytes(peerMapping2.macAddress),
                finalRequest.mRttPeers.get(finalRequest.mRttPeers.size() - 1).macAddress);

        // issue results - but remove the one for peer #2
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(mRequestCaptor.getValue());
        results.first.remove(results.first.size() - 1);
        RangingResult removed = results.second.remove(results.second.size() - 1);
        results.second.add(new RangingResult.Builder()
                .setPeerHandle(removed.getPeerHandle())
                .build());
        clock.time += MEASUREMENT_DURATION;
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.first);
        mMockLooper.dispatchAll();

        // verify that results with MAC addresses filtered out and replaced by PeerHandles issued
        // to callback
        verify(mockCallback).onRangingResults(mListCaptor.capture());
        verifyWakeupCancelled();

        assertEquals(results.second, mListCaptor.getValue());
        assertTrue(compareListContentsNoOrdering(results.second, mListCaptor.getValue()));

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request));
        verify(mockMetrics).recordResult(eq(finalRequest), eq(results.first),
                eq(MEASUREMENT_DURATION));
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
        if (SdkLevel.isAtLeastT()) {
            // Nearby permission should never be checked here since the request contains APs others
            // than Aware APs.
            verify(mockPermissionUtil, never()).checkNearbyDevicesPermission(any(), anyBoolean(),
                    any());
        }
    }

    /**
     * Verify that for ranging request to only aware APs, nearby devices' permission can be used
     * to bypass location check.
     * @throws Exception
     */
    @Test
    public void testRangingOnlyAwareAps() throws Exception {
        final int burstSize = 31;
        assumeTrue(SdkLevel.isAtLeastT());
        mExtras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE, mock(
                AttributionSource.class));
        when(mockPermissionUtil.checkNearbyDevicesPermission(any(), eq(true), any()))
                .thenReturn(true);
        RangingRequest request =
                new RangingRequest.Builder()
                        .addWifiAwarePeer(MacAddress.fromString("08:09:08:07:06:01"))
                        .addWifiAwarePeer(MacAddress.fromString("08:09:08:07:06:02"))
                        .setRttBurstSize(burstSize)
                        .build();

        // issue request
        ClockAnswer clock = new ClockAnswer();
        doAnswer(clock).when(mockClock).getWallClockMillis();
        clock.time = 100;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // verify that the request is translated from the PeerHandle issued to WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), mRequestCaptor.capture());
        verifyWakeupSet(true, 0);

        assertEquals(burstSize, mRequestCaptor.getValue().getRttBurstSize());

        // issue results
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(mRequestCaptor.getValue());
        clock.time += MEASUREMENT_DURATION;
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.first);
        mMockLooper.dispatchAll();

        // Verify permission checks. Post T Aware ranging can be done with nearby permission.
        verify(mockPermissionUtil, times(2)).checkNearbyDevicesPermission(
                any(), eq(true), any());
        verify(mockPermissionUtil, never()).enforceFineLocationPermission(any(), any(), anyInt());
    }

    /**
     * Validate failed ranging flow (WifiRttController failure).
     */
    @Test
    public void testRangingFlowHalFailure() throws Exception {
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<Pair<List<RangingResult>, List<RangingResult>>> results = new ArrayList<>();

        for (int i = 0; i < numIter; ++i) {
            requests[i] = RttTestUtils.getDummyRangingRequest((byte) i);
            results.add(RttTestUtils.getDummyRangingResults(requests[i]));
        }

        // (1) request 10 ranging operations: fail the first one
        when(mockRttControllerHal.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(
                false);
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, requests[0],
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        when(mockRttControllerHal.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(
                true);
        for (int i = 1; i < numIter; ++i) {
            mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, requests[i],
                    mockCallback, mExtras);
        }
        mMockLooper.dispatchAll();

        for (int i = 0; i < numIter; ++i) {
            // (2) verify that the request was issued to the WifiRttController
            verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(requests[i]));

            // (3) verify that failure callback dispatched (for the WifiRttController failure)
            if (i == 0) {
                verify(mockCallback).onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
            } else {
                verifyWakeupSet(true, 0);
            }

            // (4) on failed HAL: even if the HAL calls back with result we shouldn't dispatch
            // callback, otherwise expect result
            mRangingResultsCbCaptor.getValue()
                    .onRangingResults(mIntCaptor.getValue(), results.get(i).second);
            mMockLooper.dispatchAll();

            if (i != 0) {
                verify(mockCallback).onRangingResults(results.get(i).second);
                verifyWakeupCancelled();
            }
        }

        // verify metrics
        for (int i = 0; i < numIter; ++i) {
            verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(requests[i]));
            if (i != 0) {
                verify(mockMetrics).recordResult(eq(requests[i]), eq(results.get(i).second),
                        anyInt());
            }
        }
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_HAL_FAILURE);
        verify(mockMetrics, times(numIter - 1)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate a ranging flow for an app whose LOCATION runtime permission is revoked.
     */
    @Test
    public void testRangingRequestWithoutRuntimePermission() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // (2) verify that the request was issued to the WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // (3) HAL calls back with result - should get a FAILED callback
        when(mockPermissionUtil.checkCallersLocationPermission(eq(mPackageName), eq(mFeatureId),
                anyInt(), anyBoolean(), nullable(String.class))).thenReturn(false);

        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.second);
        mMockLooper.dispatchAll();

        verify(mockCallback).onRangingFailure(eq(RangingResultCallback.STATUS_CODE_FAIL));
        verifyWakeupCancelled();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request));
        verify(mockMetrics).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_LOCATION_PERMISSION_MISSING);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that the ranging app's binder death clears record of request - no callbacks are
     * attempted.
     */
    @Test
    public void testBinderDeathOfRangingApp() throws Exception {
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<Pair<List<RangingResult>, List<RangingResult>>> results = new ArrayList<>();

        for (int i = 0; i < numIter; ++i) {
            requests[i] = RttTestUtils.getDummyRangingRequest((byte) i);
            results.add(RttTestUtils.getDummyRangingResults(requests[i]));
        }

        // (1) request 10 ranging operations: even/odd with different UIDs
        for (int i = 0; i < numIter; ++i) {
            mDut.fakeUid = mDefaultUid + i % 2;
            mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, requests[i],
                    mockCallback, mExtras);
        }
        mMockLooper.dispatchAll();

        // (2) capture death listeners
        verify(mockIbinder, times(numIter)).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());

        for (int i = 0; i < numIter; ++i) {
            // (3) verify first request and all odd requests were issued to the WifiRttController
            if (i == 0 || i % 2 == 1) {
                verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(requests[i]));
                verifyWakeupSet(true, 0);
            }

            // (4) trigger first death recipient (which will map to the even UID)
            if (i == 0) {
                mDeathRecipientCaptor.getAllValues().get(0).binderDied();
                mMockLooper.dispatchAll();

                verify(mockRttControllerHal).rangeCancel(eq(mIntCaptor.getValue()),
                        (ArrayList) mListCaptor.capture());
                RangingRequest request0 = requests[0];
                assertEquals(request0.mRttPeers.size(), mListCaptor.getValue().size());
                assertTrue(MacAddress.fromString("00:01:02:03:04:00")
                        .equals(mListCaptor.getValue().get(0)));
                assertTrue(MacAddress.fromString("0A:0B:0C:0D:0E:00")
                        .equals(mListCaptor.getValue().get(1)));
                assertTrue(MacAddress.fromString("08:09:08:07:06:05")
                        .equals(mListCaptor.getValue().get(2)));
            }

            // (5) HAL calls back with all results - should get requests for the odd attempts and
            // should only get callbacks for the odd attempts (the non-dead UID), but this simulates
            // invalid results (or possibly the firmware not cancelling some requests)
            mRangingResultsCbCaptor.getValue()
                    .onRangingResults(mIntCaptor.getValue(), results.get(i).second);
            mMockLooper.dispatchAll();
            if (i == 0) {
                verifyWakeupCancelled(); // as the first (dispatched) request is aborted
            }
            if (i % 2 == 1) {
                verify(mockCallback).onRangingResults(results.get(i).second);
                verifyWakeupCancelled();
            }
        }

        // verify metrics
        WorkSource oddWs = new WorkSource(mDefaultUid + 1);
        for (int i = 0; i < numIter; ++i) {
            verify(mockMetrics).recordRequest(eq((i % 2) == 0 ? mDefaultWs : oddWs),
                    eq(requests[i]));
            if (i % 2 == 1) {
                verify(mockMetrics).recordResult(eq(requests[i]), eq(results.get(i).second),
                        anyInt());
            }
        }
        verify(mockMetrics, times(numIter / 2)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that a ranging app which uses WorkSource and dies (binder death) results in the
     * request cleanup.
     */
    @Test
    public void testBinderDeathWithWorkSource() throws Exception {
        WorkSource ws = new WorkSource(100);

        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, ws, request, mockCallback,
                mExtras);
        mMockLooper.dispatchAll();

        verify(mockIbinder).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // (2) execute binder death
        mDeathRecipientCaptor.getValue().binderDied();
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeCancel(eq(mIntCaptor.getValue()), any());
        verifyWakeupCancelled();

        // (3) provide results back - should be ignored
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.second);
        mMockLooper.dispatchAll();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(ws), eq(request));
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when a cancelRanging is called, using the same work source specification as the
     * request, that the request is cancelled.
     */
    @Test
    public void testCancelRangingFullMatch() throws Exception {
        int uid1 = 10;
        int uid2 = 20;
        int uid3 = 30;
        int uid4 = 40;
        WorkSource worksourceRequest = new WorkSource(uid1);
        worksourceRequest.add(uid2);
        worksourceRequest.add(uid3);
        worksourceRequest.createWorkChain().addNode(uid4, "foo");
        WorkSource worksourceCancel = new WorkSource(uid2);
        worksourceCancel.add(uid3);
        worksourceCancel.add(uid1);
        worksourceCancel.createWorkChain().addNode(uid4, "foo");

        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, worksourceRequest, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(worksourceRequest), eq(request));

        // (2) verify that the request was issued to the WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // (3) cancel the request
        mDut.cancelRanging(worksourceCancel);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeCancel(eq(mIntCaptor.getValue()), any());
        verifyWakeupCancelled();

        // (4) send results back from the HAL
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.second);
        mMockLooper.dispatchAll();

        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when a cancelRanging is called - but specifies a subset of the WorkSource
     * uids then the ranging proceeds.
     */
    @Test
    public void testCancelRangingPartialMatch() throws Exception {
        int uid1 = 10;
        int uid2 = 20;
        int uid3 = 30;
        WorkSource worksourceRequest = new WorkSource(uid1);
        worksourceRequest.add(uid2);
        worksourceRequest.createWorkChain().addNode(uid3, null);
        WorkSource worksourceCancel = new WorkSource(uid1);
        worksourceCancel.add(uid2);

        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, worksourceRequest, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(worksourceRequest), eq(request));

        // (2) verify that the request was issued to the WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // (3) cancel the request
        mDut.cancelRanging(worksourceCancel);

        // (4) send results back from the HAL
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.second);
        mMockLooper.dispatchAll();

        verify(mockCallback).onRangingResults(results.second);
        verifyWakeupCancelled();

        // verify metrics
        verify(mockMetrics).recordResult(eq(request), eq(results.second), anyInt());
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when an unexpected result is provided by the HAL it is not propagated to
     * caller (unexpected = different command ID).
     */
    @Test
    public void testUnexpectedResult() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // (2) verify that the request was issued to the WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // (3) HAL calls back with result - but wrong ID
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue() + 1,
                RttTestUtils.getDummyRangingResults(null).second);
        mMockLooper.dispatchAll();

        // (4) now send results with correct ID (different set of results to differentiate)
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.second);
        mMockLooper.dispatchAll();

        // (5) verify that results dispatched
        verify(mockCallback).onRangingResults(results.second);
        verifyWakeupCancelled();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request));
        verify(mockMetrics).recordResult(eq(request), eq(results.second), anyInt());
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that the HAL returns results with "missing" entries (i.e. some requests don't get
     * results) they are filled-in with FAILED results.
     */
    @Test
    public void testMissingResults() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(request);
        results.first.remove(1); // remove a direct AWARE request
        RangingResult removed = results.second.remove(1);
        results.second.add(new RangingResult.Builder()
                .setMacAddress(removed.getMacAddress())
                .build());
        results.first.remove(0); // remove an AP request
        removed = results.second.remove(0);
        results.second.add(new RangingResult.Builder()
                .setMacAddress(removed.getMacAddress())
                .build());

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // (2) verify that the request was issued to the WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // (3) return results with missing entries
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), results.second);
        mMockLooper.dispatchAll();

        // (5) verify that (full) results dispatched
        verify(mockCallback).onRangingResults(mListCaptor.capture());
        assertTrue(compareListContentsNoOrdering(results.second, mListCaptor.getValue()));
        verifyWakeupCancelled();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request));
        verify(mockMetrics).recordResult(eq(request), eq(results.second), anyInt());
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when HAL returns an empty result set (completely empty) - they are filled-in
     * with FAILED results.
     */
    @Test
    public void testMissingAllResults() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RangingResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(request);
        List<RangingResult> allFailResults = new ArrayList<>();
        for (RangingResult result : results.second) {
            allFailResults.add(new RangingResult.Builder()
                    .setMacAddress(result.getMacAddress())
                    .build());
        }

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // (2) verify that the request was issued to the WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // (3) return results with ALL results missing
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), new ArrayList<>());
        mMockLooper.dispatchAll();

        // (5) verify that (full) results dispatched
        verify(mockCallback).onRangingResults(mListCaptor.capture());
        assertTrue(compareListContentsNoOrdering(allFailResults, mListCaptor.getValue()));
        verifyWakeupCancelled();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request));
        verify(mockMetrics).recordResult(eq(request), eq(new ArrayList<>()), anyInt());
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when the HAL times out we fail, clean-up the queue and move to the next
     * request.
     */
    @Test
    public void testRangingTimeout() throws Exception {
        RangingRequest request1 = RttTestUtils.getDummyRangingRequest((byte) 1);
        RangingRequest request2 = RttTestUtils.getDummyRangingRequest((byte) 2);
        Pair<List<RangingResult>, List<RangingResult>> result1 =
                RttTestUtils.getDummyRangingResults(request1);
        Pair<List<RangingResult>, List<RangingResult>> result2 =
                RttTestUtils.getDummyRangingResults(request2);

        // (1) request 2 ranging operation
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request1,
                mockCallback, mExtras);
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request2,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // verify that request 1 was issued to the WifiRttController
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request1));
        int cmdId1 = mIntCaptor.getValue();
        verifyWakeupSet(true, 0);

        // (2) time-out
        mAlarmManager.dispatch(RttServiceImpl.HAL_RANGING_TIMEOUT_TAG);
        mMockLooper.dispatchAll();

        // verify that the failure callback + request 2 were issued to the WifiRttController
        verify(mockRttControllerHal).rangeCancel(eq(cmdId1), any());
        verify(mockCallback).onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request2));
        verifyWakeupSet(true, 0);

        // (3) send both result 1 and result 2
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(cmdId1, result1.second);
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result2.second);
        mMockLooper.dispatchAll();

        // verify that only result 2 is forwarded to client
        verify(mockCallback).onRangingResults(result2.second);
        verifyWakeupCancelled();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request1));
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request2));
        verify(mockMetrics).recordResult(eq(request2), eq(result2.second), anyInt());
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_TIMEOUT);
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that ranging requests from background apps are throttled. The sequence is:
     * - Time 1: Background request -> ok
     * - Time 2 = t1 + 0.5gap: Background request -> fail (throttled)
     * - Time 3 = t1 + 1.1gap: Background request -> ok
     * - Time 4 = t3 + small: Foreground request -> ok
     * - Time 5 = t4 + small: Background request -> fail (throttled)
     */
    @Test
    public void testRangingThrottleBackground() throws Exception {
        RangingRequest request1 = RttTestUtils.getDummyRangingRequest((byte) 1);
        RangingRequest request2 = RttTestUtils.getDummyRangingRequest((byte) 2);
        RangingRequest request3 = RttTestUtils.getDummyRangingRequest((byte) 3);
        RangingRequest request4 = RttTestUtils.getDummyRangingRequest((byte) 4);
        RangingRequest request5 = RttTestUtils.getDummyRangingRequest((byte) 5);
        RangingRequest request6 = RttTestUtils.getDummyRangingRequest((byte) 6);

        Pair<List<RangingResult>, List<RangingResult>> result1 =
                RttTestUtils.getDummyRangingResults(request1);
        Pair<List<RangingResult>, List<RangingResult>> result3 =
                RttTestUtils.getDummyRangingResults(request3);
        Pair<List<RangingResult>, List<RangingResult>> result4 =
                RttTestUtils.getDummyRangingResults(request4);
        Pair<List<RangingResult>, List<RangingResult>> result6 =
                RttTestUtils.getDummyRangingResults(request6);

        InOrder cbInorder = inOrder(mockCallback);

        ClockAnswer clock = new ClockAnswer();
        doAnswer(clock).when(mockClock).getElapsedSinceBootMillis();
        when(mockActivityManager.getUidImportance(anyInt())).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE); // far background

        // (1) issue a request at time t1: should be dispatched since first one!
        clock.time = 100;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request1,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request1));
        verifyWakeupSet(true, clock.time);

        // (1.1) get result
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result1.second);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingResults(result1.second);
        verifyWakeupCancelled();

        // (2) issue a request at time t2 = t1 + 0.5 gap: should be rejected (throttled)
        clock.time = 100 + BACKGROUND_PROCESS_EXEC_GAP_MS / 2;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request2,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);

        // (3) issue a request at time t3 = t1 + 1.1 gap: should be dispatched since enough time
        clock.time = 100 + BACKGROUND_PROCESS_EXEC_GAP_MS * 11 / 10;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request3,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request3));
        verifyWakeupSet(true, clock.time);

        // (3.1) get result
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result3.second);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingResults(result3.second);
        verifyWakeupCancelled();

        // (4) issue a foreground request at t4 = t3 + small: should be dispatched (foreground!)
        when(mockActivityManager.getUidImportance(anyInt())).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);

        clock.time = clock.time + 5;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request4,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request4));
        verifyWakeupSet(true, clock.time);

        // (4.1) get result
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result4.second);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingResults(result4.second);
        verifyWakeupCancelled();

        // (5) issue a background request at t5 = t4 + small: should be rejected (throttled)
        when(mockActivityManager.getUidImportance(anyInt())).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);

        clock.time = clock.time + 5;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request5,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);

        // (6) issue a background request from exception list at t6 = t5 + small: should be
        // dispatched
        when(mockActivityManager.getUidImportance(anyInt())).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);
        mMockResources.setStringArray(R.array.config_wifiBackgroundRttThrottleExceptionList,
                new String[]{mPackageName});

        clock.time = clock.time + 5;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request6,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request6));
        verifyWakeupSet(true, clock.time);

        // (6.1) get result
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result6.second);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingResults(result6.second);
        verifyWakeupCancelled();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request1));
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request2));
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request3));
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request4));
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request5));
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request6));
        verify(mockMetrics).recordResult(eq(request1), eq(result1.second), anyInt());
        verify(mockMetrics).recordResult(eq(request3), eq(result3.second), anyInt());
        verify(mockMetrics).recordResult(eq(request4), eq(result4.second), anyInt());
        verify(mockMetrics).recordResult(eq(request6), eq(result6.second), anyInt());
        verify(mockMetrics, times(2)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_THROTTLE);
        verify(mockMetrics, times(4)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that throttling of background request handles multiple work source correctly:
     * - Time t1: background request uid=10: ok
     * - Time t2 = t1+small: background request ws={10,20}: ok
     * - Time t3 = t1+gap: background request uid=10: fail (throttled)
     */
    @Test
    public void testRangingThrottleBackgroundWorkSources() throws Exception {
        runTestRangingThrottleBackgroundWorkSources(false);
    }

    @Test
    public void testRangingThrottleBackgroundChainedWorkSources() throws Exception {
        runTestRangingThrottleBackgroundWorkSources(true);
    }

    private void runTestRangingThrottleBackgroundWorkSources(boolean isChained) throws Exception {
        final WorkSource wsReq1 = new WorkSource();
        final WorkSource wsReq2 = new WorkSource();
        if (isChained) {
            wsReq1.createWorkChain().addNode(10, "foo");

            wsReq2.createWorkChain().addNode(10, "foo");
            wsReq2.createWorkChain().addNode(20, "bar");
        } else {
            wsReq1.add(10);

            wsReq2.add(10);
            wsReq2.add(20);
        }

        RangingRequest request1 = RttTestUtils.getDummyRangingRequest((byte) 1);
        RangingRequest request2 = RttTestUtils.getDummyRangingRequest((byte) 2);
        RangingRequest request3 = RttTestUtils.getDummyRangingRequest((byte) 3);

        Pair<List<RangingResult>, List<RangingResult>> result1 =
                RttTestUtils.getDummyRangingResults(request1);
        Pair<List<RangingResult>, List<RangingResult>> result2 =
                RttTestUtils.getDummyRangingResults(request2);

        InOrder cbInorder = inOrder(mockCallback);

        ClockAnswer clock = new ClockAnswer();
        doAnswer(clock).when(mockClock).getElapsedSinceBootMillis();
        when(mockActivityManager.getUidImportance(anyInt())).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE); // far background

        // (1) issue a request at time t1 for {10}: should be dispatched since first one!
        clock.time = 100;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, wsReq1, request1,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request1));
        verifyWakeupSet(true, clock.time);

        // (1.1) get result
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result1.second);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingResults(result1.second);
        verifyWakeupCancelled();

        // (2) issue a request at time t2 = t1 + 0.5 gap for {10,20}: should be dispatched since
        //     uid=20 should not be throttled
        clock.time = 100 + BACKGROUND_PROCESS_EXEC_GAP_MS / 2;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, wsReq2, request2,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request2));
        verifyWakeupSet(true, clock.time);

        // (2.1) get result
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result2.second);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingResults(result2.second);
        verifyWakeupCancelled();

        // (3) issue a request at t3 = t1 + 1.1 * gap for {10}: should be rejected (throttled)
        clock.time = 100 + BACKGROUND_PROCESS_EXEC_GAP_MS * 11 / 10;
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, wsReq1, request3,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);

        // verify metrics
        verify(mockMetrics).recordRequest(eq(wsReq1), eq(request1));
        verify(mockMetrics).recordRequest(eq(wsReq2), eq(request2));
        verify(mockMetrics).recordRequest(eq(wsReq1), eq(request3));
        verify(mockMetrics).recordResult(eq(request1), eq(result1.second), anyInt());
        verify(mockMetrics).recordResult(eq(request2), eq(result2.second), anyInt());
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_THROTTLE);
        verify(mockMetrics, times(2)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that flooding the service with ranging requests will cause it to start rejecting
     * rejects from the flooding uid. Single UID.
     */
    @Test
    public void testRejectFloodingRequestsSingleUid() throws Exception {
        runFloodRequestsTest(true, false);
    }

    /**
     * Validate that flooding the service with ranging requests will cause it to start rejecting
     * rejects from the flooding uid. WorkSource (all identical).
     */
    @Test
    public void testRejectFloodingRequestsIdenticalWorksources() throws Exception {
        runFloodRequestsTest(false, false);
    }

    @Test
    public void testRejectFloodingRequestsIdenticalChainedWorksources() throws Exception {
        runFloodRequestsTest(false, true);
    }

    /**
     * Validate that flooding the service with ranging requests will cause it to start rejecting
     * rejects from the flooding uid. WorkSource (with one constant UID but other varying UIDs -
     * the varying UIDs should prevent the flood throttle)
     */
    @Test
    public void testDontRejectFloodingRequestsVariousUids() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 1);
        WorkSource ws = new WorkSource(10);

        // 1. issue a request
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, ws, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // 2. issue FLOOD LEVEL requests + 10 at various UIDs - no failure expected
        for (int i = 0; i < RttServiceImpl.MAX_QUEUED_PER_UID + 10; ++i) {
            WorkSource wsExtra = new WorkSource(ws);
            wsExtra.add(11 + i);
            mDut.startRanging(mockIbinder, mPackageName, mFeatureId, wsExtra, request,
                    mockCallback, mExtras);
        }
        mMockLooper.dispatchAll();

        // 3. clear queue
        mDut.disable();
        mMockLooper.dispatchAll();

        verifyWakeupCancelled();
        verify(mockRttControllerHal).rangeCancel(eq(mIntCaptor.getValue()), any());
        verify(mockCallback, times(RttServiceImpl.MAX_QUEUED_PER_UID + 11))
                .onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);

        // verify metrics
        for (int i = 0; i < RttServiceImpl.MAX_QUEUED_PER_UID + 11; ++i) {
            WorkSource wsExtra = new WorkSource(10);
            if (i != 0) {
                wsExtra.add(10 + i);
            }
            verify(mockMetrics).recordRequest(eq(wsExtra), eq(request));
        }
        verify(mockMetrics, times(RttServiceImpl.MAX_QUEUED_PER_UID + 11))
                .recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_RTT_NOT_AVAILABLE);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    @Test
    public void testGetRttCharacteristics() {
        WifiRttController.Capabilities cap = new WifiRttController.Capabilities();
        cap.lcrSupported = true;
        cap.oneSidedRttSupported = true;
        cap.lciSupported = true;
        when(mockRttControllerHal.getRttCapabilities()).thenReturn(cap);
        Bundle characteristics = mDut.getRttCharacteristics();
        assertTrue(characteristics.getBoolean(CHARACTERISTICS_KEY_BOOLEAN_ONE_SIDED_RTT));
        assertTrue(characteristics.getBoolean(CHARACTERISTICS_KEY_BOOLEAN_LCI));
        assertTrue(characteristics.getBoolean(CHARACTERISTICS_KEY_BOOLEAN_LCR));
    }

    /**
     * Utility to run configurable tests for flooding range requests.
     * - Execute a single request
     * - Flood service with requests: using same ID or same WorkSource
     * - Provide results (to clear queue) and execute another test: validate succeeds
     */
    private void runFloodRequestsTest(boolean useUids, boolean useChainedWorkSources)
            throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 1);
        Pair<List<RangingResult>, List<RangingResult>> result =
                RttTestUtils.getDummyRangingResults(request);

        WorkSource ws = new WorkSource();
        if (useChainedWorkSources) {
            ws.createWorkChain().addNode(10, "foo");
            ws.createWorkChain().addNode(20, "bar");
            ws.createWorkChain().addNode(30, "baz");
        } else {
            ws.add(10);
            ws.add(20);
            ws.add(30);
        }

        InOrder cbInorder = inOrder(mockCallback);
        InOrder controllerInorder = inOrder(mockRttControllerHal);

        // 1. issue a request
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, useUids ? null : ws, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        controllerInorder.verify(mockRttControllerHal).rangeRequest(
                mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // 2. issue FLOOD LEVEL requests + 10: should get 11 failures (10 extra + 1 original)
        for (int i = 0; i < RttServiceImpl.MAX_QUEUED_PER_UID + 10; ++i) {
            mDut.startRanging(mockIbinder, mPackageName, mFeatureId, useUids ? null : ws, request,
                    mockCallback, mExtras);
        }
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback, times(11)).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL);

        // 3. provide results
        mRangingResultsCbCaptor.getValue()
                .onRangingResults(mIntCaptor.getValue(), result.second);
        mMockLooper.dispatchAll();

        cbInorder.verify(mockCallback).onRangingResults(result.second);
        verifyWakeupCancelled();

        controllerInorder.verify(mockRttControllerHal).rangeRequest(
                mIntCaptor.capture(), eq(request));
        verifyWakeupSet(true, 0);

        // 4. issue a request: don't expect a failure
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, useUids ? null : ws, request,
                mockCallback, mExtras);
        mMockLooper.dispatchAll();

        // 5. clear queue
        mDut.disable();
        mMockLooper.dispatchAll();

        verifyWakeupCancelled();
        controllerInorder.verify(mockRttControllerHal).rangeCancel(
                eq(mIntCaptor.getValue()), any());
        cbInorder.verify(mockCallback, times(RttServiceImpl.MAX_QUEUED_PER_UID)).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);

        // verify metrics
        verify(mockMetrics, times(RttServiceImpl.MAX_QUEUED_PER_UID + 12)).recordRequest(
                eq(useUids ? mDefaultWs : ws), eq(request));
        verify(mockMetrics).recordResult(eq(request), eq(result.second), anyInt());
        verify(mockMetrics, times(11)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_THROTTLE);
        verify(mockMetrics, times(RttServiceImpl.MAX_QUEUED_PER_UID)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_RTT_NOT_AVAILABLE);
        verify(mockMetrics).recordOverallStatus(WifiMetricsProto.WifiRttLog.OVERALL_SUCCESS);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback,
                mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when Wi-Fi gets disabled (HAL level) the ranging queue gets cleared.
     */
    @Test
    public void testDisableWifiFlow() throws Exception {
        runDisableRttFlow(FAILURE_MODE_DISABLE_WIFI);
    }

    /**
     * Validate that when Doze mode starts, RTT gets disabled and the ranging queue gets cleared.
     */
    @Test
    public void testDozeModeFlow() throws Exception {
        runDisableRttFlow(FAILURE_MODE_ENABLE_DOZE);
    }

    /**
     * Validate that when locationing is disabled, RTT gets disabled and the ranging queue gets
     * cleared.
     */
    @Test
    public void testLocationingOffFlow() throws Exception {
        runDisableRttFlow(FAILURE_MODE_DISABLE_LOCATIONING);
    }


    private static final int FAILURE_MODE_DISABLE_WIFI = 0;
    private static final int FAILURE_MODE_ENABLE_DOZE = 1;
    private static final int FAILURE_MODE_DISABLE_LOCATIONING = 2;

    /**
     * Actually execute the disable RTT flow: either by disabling Wi-Fi or enabling doze.
     *
     * @param failureMode The mechanism by which RTT is to be disabled. One of the FAILURE_MODE_*
     *                    constants above.
     */
    private void runDisableRttFlow(int failureMode) throws Exception {
        RangingRequest request1 = RttTestUtils.getDummyRangingRequest((byte) 1);
        RangingRequest request2 = RttTestUtils.getDummyRangingRequest((byte) 2);
        RangingRequest request3 = RttTestUtils.getDummyRangingRequest((byte) 3);

        IRttCallback mockCallback2 = mock(IRttCallback.class);
        IRttCallback mockCallback3 = mock(IRttCallback.class);

        // (1) request 2 ranging operations: request 1 should be sent to the WifiRttController
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request1,
                mockCallback, mExtras);
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request2,
                mockCallback2, mExtras);
        mMockLooper.dispatchAll();

        verify(mockRttControllerHal).rangeRequest(mIntCaptor.capture(), eq(request1));
        verifyWakeupSet(true, 0);

        // (2) disable RTT: all requests should "fail"
        if (failureMode == FAILURE_MODE_DISABLE_WIFI) {
            mRttLifecycleCbCaptor.getValue().onRttControllerDestroyed();
        } else if (failureMode == FAILURE_MODE_ENABLE_DOZE) {
            simulatePowerStateChangeDoze(true);
        } else if (failureMode == FAILURE_MODE_DISABLE_LOCATIONING) {
            when(mockPermissionUtil.isLocationModeEnabled()).thenReturn(false);
            simulateLocationModeChange();
        }
        mMockLooper.dispatchAll();

        assertFalse(mDut.isAvailable());
        validateCorrectRttStatusChangeBroadcast();
        if (failureMode != FAILURE_MODE_DISABLE_WIFI) {
            verify(mockRttControllerHal).rangeCancel(eq(mIntCaptor.getValue()), any());
        }
        verify(mockCallback).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
        verify(mockCallback2).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
        verifyWakeupCancelled();

        // (3) issue another request: it should fail
        mDut.startRanging(mockIbinder, mPackageName, mFeatureId, null, request3,
                mockCallback3, mExtras);
        mMockLooper.dispatchAll();

        verify(mockCallback3).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);

        // (4) enable RTT: nothing should happen (no requests in queue!)
        if (failureMode == FAILURE_MODE_DISABLE_WIFI) {
            mRttLifecycleCbCaptor.getValue().onNewRttController(mockRttControllerHal);
            verify(mockRttControllerHal, times(2)).registerRangingResultsCallback(any());
        } else if (failureMode == FAILURE_MODE_ENABLE_DOZE) {
            simulatePowerStateChangeDoze(false);
        } else if (failureMode == FAILURE_MODE_DISABLE_LOCATIONING) {
            when(mockPermissionUtil.isLocationModeEnabled()).thenReturn(true);
            simulateLocationModeChange();
        }
        mMockLooper.dispatchAll();

        assertTrue(mDut.isAvailable());
        validateCorrectRttStatusChangeBroadcast();

        // verify metrics
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request1));
        verify(mockMetrics).recordRequest(eq(mDefaultWs), eq(request2));
        verify(mockMetrics, times(3)).recordOverallStatus(
                WifiMetricsProto.WifiRttLog.OVERALL_RTT_NOT_AVAILABLE);
        verify(mockMetrics).enableVerboseLogging(anyBoolean());
        verifyNoMoreInteractions(mockRttControllerHal, mockMetrics, mockCallback, mockCallback2,
                mockCallback3, mAlarmManager.getAlarmManager());
    }

    /*
     * Utilities
     */

    /**
     * Simulate power state change due to doze. Changes the power manager return values and
     * dispatches a broadcast.
     */
    private void simulatePowerStateChangeDoze(boolean isDozeOn) {
        when(mMockPowerManager.isDeviceIdleMode()).thenReturn(isDozeOn);

        Intent intent = new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        mPowerBcastReceiver.onReceive(mockContext, intent);
    }

    /**
     * Simulate the broadcast which is dispatched when a LOCATION_MODE is modified.
     */
    private void simulateLocationModeChange() {
        Intent intent = new Intent(LocationManager.MODE_CHANGED_ACTION);
        mLocationModeReceiver.onReceive(mockContext, intent);
    }

    private void verifyWakeupSet(boolean useAwareTimeout, long baseTime) {
        ArgumentCaptor<Long> longCaptor = ArgumentCaptor.forClass(Long.class);

        mInOrder.verify(mAlarmManager.getAlarmManager()).setExact(anyInt(), longCaptor.capture(),
                eq(RttServiceImpl.HAL_RANGING_TIMEOUT_TAG), any(AlarmManager.OnAlarmListener.class),
                any(Handler.class));

        assertEquals(baseTime + (useAwareTimeout ? RttServiceImpl.HAL_AWARE_RANGING_TIMEOUT_MS
                : RttServiceImpl.HAL_RANGING_TIMEOUT_MS), longCaptor.getValue().longValue());
    }

    private void verifyWakeupCancelled() {
        mInOrder.verify(mAlarmManager.getAlarmManager()).cancel(
                any(AlarmManager.OnAlarmListener.class));
    }

    /**
     * Validates that the broadcast sent on RTT status change is correct.
     *
     */
    private void validateCorrectRttStatusChangeBroadcast() {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        mInOrder.verify(mockContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(intent.getValue().getAction(), WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED);
    }

    private class AwareTranslatePeerHandlesToMac extends MockAnswerUtil.AnswerWithArguments {
        private int mExpectedUid;
        private MacAddrMapping[] mPeerIdToMacList;

        AwareTranslatePeerHandlesToMac(int expectedUid, MacAddrMapping[] peerIdToMacList) {
            mExpectedUid = expectedUid;
            mPeerIdToMacList = peerIdToMacList;
        }

        public void answer(int uid, int[] peerIds, IWifiAwareMacAddressProvider callback) {
            assertEquals("Invalid UID", mExpectedUid, uid);

            List<MacAddrMapping> result = new ArrayList();
            for (int peerId: peerIds) {
                byte[] macBytes = null;
                for (MacAddrMapping mapping : mPeerIdToMacList) {
                    if (mapping.peerId == peerId) {
                        macBytes = mapping.macAddress;
                        break;
                    }
                }
                MacAddrMapping mapping = new MacAddrMapping();
                mapping.peerId = peerId;
                mapping.macAddress = macBytes;
                result.add(mapping);
            }

            try {
                callback.macAddress(result.toArray(new MacAddrMapping[0]));
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private class BinderDeathAnswerBase extends MockAnswerUtil.AnswerWithArguments {
        protected Set<IBinder.DeathRecipient> mUniqueExecs = new HashSet<>();
    }

    private class BinderLinkToDeathAnswer extends BinderDeathAnswerBase {
        public void answer(IBinder.DeathRecipient recipient, int flags) {
            mUniqueExecs.add(recipient);
        }
    }

    private class BinderUnlinkToDeathAnswer extends BinderDeathAnswerBase {
        public boolean answer(IBinder.DeathRecipient recipient, int flags) {
            mUniqueExecs.add(recipient);
            return true;
        }
    }

    private class ClockAnswer extends MockAnswerUtil.AnswerWithArguments {
        public long time;

        public long answer() {
            return time;
        }
    }
}
