/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import static com.android.server.wifi.hotspot2.ANQPRequestManager.ANQP_REQUEST_ALARM_TAG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.test.TestAlarmManager;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.Clock;
import com.android.server.wifi.DeviceConfigFacade;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.wifi.flags.FeatureFlags;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.ANQPRequestManager}.
 */
@SmallTest
public class ANQPRequestManagerTest extends WifiBaseTest {
    private static final long TEST_BSSID = 0x123456L;
    private static final ANQPNetworkKey TEST_ANQP_KEY =
            new ANQPNetworkKey("TestSSID", TEST_BSSID, 0, 0);

    private static final List<Constants.ANQPElementType> R1_ANQP_WITHOUT_RC = Arrays.asList(
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPDomName,
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability);

    private static final List<Constants.ANQPElementType> R1_ANQP_WITH_RC = Arrays.asList(
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPDomName,
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability,
            Constants.ANQPElementType.ANQPRoamingConsortium);

    private static final List<Constants.ANQPElementType> R1R2_ANQP_WITHOUT_RC = Arrays.asList(
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPDomName,
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability,
            Constants.ANQPElementType.HSOSUProviders);

    private static final List<Constants.ANQPElementType> R1R2_ANQP_WITH_RC = Arrays.asList(
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPDomName,
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability,
            Constants.ANQPElementType.ANQPRoamingConsortium,
            Constants.ANQPElementType.HSOSUProviders);

    @Mock
    PasspointEventHandler mHandler;
    @Mock
    Clock mClock;
    ANQPRequestManager mManager;
    private TestAlarmManager mAlarmManager;
    private TestLooper mLooper = new TestLooper();
    @Mock
    WifiInjector mWifiInjector;
    @Mock
    FeatureFlags mFeatureFlags;
    @Mock
    DeviceConfigFacade mDeviceConfigFacade;


    /**
     * Test setup.
     */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mAlarmManager = new TestAlarmManager();
        when(mWifiInjector.getDeviceConfigFacade()).thenReturn(mDeviceConfigFacade);
        when(mWifiInjector.getAlarmManager()).thenReturn(mAlarmManager.getAlarmManager());
        mManager = new ANQPRequestManager(mHandler, mClock, mWifiInjector,
                new Handler(mLooper.getLooper()));
    }

    /**
     * Verify that the expected set of ANQP elements are being requested when the targeted AP
     * doesn't provide roaming consortium OIs and doesn't support Hotspot 2.0 Release 2 ANQP
     * elements, based on the IEs in the scan result .
     *
     * @throws Exception
     */
    @Test
    public void requestR1ANQPElementsWithoutRC() throws Exception {
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
    }

    /**
     * Verify that the expected set of ANQP elements are being requested when the targeted AP does
     * provide roaming consortium OIs and doesn't support Hotspot 2.0 Release ANQP elements, based
     * on the IEs in the scan result.
     *
     * @throws Exception
     */
    @Test
    public void requestR1ANQPElementsWithRC() throws Exception {
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITH_RC)).thenReturn(true);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, true,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITH_RC);
    }

    /**
     * Verify that the expected set of ANQP elements are being requested when the targeted AP
     * doesn't provide roaming consortium OIs and does support Hotspot 2.0 Release ANQP elements,
     * based on the IEs in the scan result.
     *
     * @throws Exception
     */
    @Test
    public void requestR1R2ANQPElementsWithoutRC() throws Exception {
        when(mHandler.requestANQP(TEST_BSSID, R1R2_ANQP_WITHOUT_RC)).thenReturn(true);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R2);
        verify(mHandler).requestANQP(TEST_BSSID, R1R2_ANQP_WITHOUT_RC);
    }

    /**
     * Verify that the expected set of ANQP elements are being requested when the targeted AP does
     * provide roaming consortium OIs and support Hotspot 2.0 Release ANQP elements, based on the
     * IEs in the scan result.
     *
     * @throws Exception
     */
    @Test
    public void requestR1R2ANQPElementsWithRC() throws Exception {
        when(mHandler.requestANQP(TEST_BSSID, R1R2_ANQP_WITH_RC)).thenReturn(true);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, true,
                NetworkDetail.HSRelease.R2);
        verify(mHandler).requestANQP(TEST_BSSID, R1R2_ANQP_WITH_RC);
    }

    /**
     * Verify that an immediate attempt to request ANQP elements from an AP will succeed when the
     * previous request is failed on sending.
     *
     * @throws Exception
     */
    @Test
    public void requestANQPElementsAfterRequestSendFailure() throws Exception {
        // Initial request failed to send.
        long startTime = 0;
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(false);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startTime);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
        reset(mHandler);

        // Verify that new request is not being held off after previous send failure.
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startTime);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
    }

    /**
     * Verify that an immediate attempt to request ANQP elements from an AP will succeed when the
     * previous request is completed with success.
     *
     * @throws Exception
     */
    @Test
    public void requestANQPElementsAfterRequestSucceeded() throws Exception {
        // Send the initial request.
        long startTime = 0;
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startTime);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
        reset(mHandler);

        // Request completed with success. Verify that the key associated with the request
        // is returned.
        assertEquals(TEST_ANQP_KEY, mManager.onRequestCompleted(TEST_BSSID, true));

        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startTime + 1);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
    }

    /**
     * Verify that an immediate attempt to request ANQP elements from an AP will fail when the
     * previous request is completed with failure.  The request will succeed after the hold off time
     * is up.
     *
     * @throws Exception
     */
    @Test
    public void requestANQPElementsAfterRequestFailed() throws Exception {
        // Send the initial request.
        long startTime = 0;
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startTime);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
        reset(mHandler);

        // Request completed with failure.  Verify that the key associated with the request
        // is returned
        assertEquals(TEST_ANQP_KEY, mManager.onRequestCompleted(TEST_BSSID, false));

        // Attempt another request will fail since the hold off time is not up yet.
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startTime + 1);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler, never()).requestANQP(anyLong(), any());

        // Attempt another request will succeed after the hold off time is up.
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        when(mClock.getElapsedSinceBootMillis())
                .thenReturn(startTime + ANQPRequestManager.BASE_HOLDOFF_TIME_MILLISECONDS);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
    }

    /**
     * Verify the hold off time for each unanswered query, and that it will stay the same after
     * reaching the max hold off count {@link ANQPRequestManager#MAX_HOLDOFF_COUNT}.
     *
     * @throws Exception
     */
    @Test
    public void requestANQPElementsWithMaxRetries() throws Exception {
        long currentTime = 0;

        // Initial request.
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTime);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
        reset(mHandler);
        mManager.onRequestCompleted(TEST_BSSID, false);

        // Sending the request with the hold off time based on the current hold off count.
        for (int i = 0; i <= ANQPRequestManager.MAX_HOLDOFF_COUNT; i++) {
            long currentHoldOffTime = ANQPRequestManager.BASE_HOLDOFF_TIME_MILLISECONDS * (1 << i);
            currentTime += (currentHoldOffTime - 1);

            // Request will fail before the hold off time is up.
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTime);
            mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                    NetworkDetail.HSRelease.R1);
            verify(mHandler, never()).requestANQP(anyLong(), any());

            // Request will succeed when the hold off time is up.
            currentTime += 1;
            when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTime);
            mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                    NetworkDetail.HSRelease.R1);
            verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
            reset(mHandler);
            mManager.onRequestCompleted(TEST_BSSID, false);
        }

        // Verify that the hold off time is max out at the maximum hold off count.
        currentTime += (ANQPRequestManager.BASE_HOLDOFF_TIME_MILLISECONDS
                * (1 << ANQPRequestManager.MAX_HOLDOFF_COUNT) - 1);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTime);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler, never()).requestANQP(anyLong(), any());

        currentTime += 1;
        when(mHandler.requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC)).thenReturn(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTime);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R1);
        verify(mHandler).requestANQP(TEST_BSSID, R1_ANQP_WITHOUT_RC);
        reset(mHandler);
    }

    /**
     * Verify that the expected set of ANQP elements are being requested when the targeted AP
     * doesn't provide roaming consortium OIs and does support Hotspot 2.0 Release 3 ANQP elements,
     * based on the IEs in the scan result.
     *
     * @throws Exception
     */
    @Test
    public void requestR1R2ANQPElementsWithoutRCForR3() throws Exception {
        when(mHandler.requestANQP(TEST_BSSID, R1R2_ANQP_WITHOUT_RC)).thenReturn(true);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, false,
                NetworkDetail.HSRelease.R3);
        verify(mHandler).requestANQP(TEST_BSSID, R1R2_ANQP_WITHOUT_RC);
    }

    /**
     * Verify that the expected set of ANQP elements are being requested when the targeted AP does
     * provide roaming consortium OIs and support Hotspot 2.0 Release 3 ANQP elements, based on the
     * IEs in the scan result.
     *
     * @throws Exception
     */
    @Test
    public void requestR1R2ANQPElementsWithRCForR3() throws Exception {
        when(mHandler.requestANQP(TEST_BSSID, R1R2_ANQP_WITH_RC)).thenReturn(true);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, true,
                NetworkDetail.HSRelease.R3);
        verify(mHandler).requestANQP(TEST_BSSID, R1R2_ANQP_WITH_RC);
    }

    /**
     * Verify that the Venue URL ANQP element is being requested when called.
     *
     * @throws Exception
     */
    @Test
    public void requestVenueUrlAnqpElement() throws Exception {
        when(mHandler.requestVenueUrlAnqp(TEST_BSSID)).thenReturn(true);
        assertTrue(mManager.requestVenueUrlAnqpElement(TEST_BSSID, TEST_ANQP_KEY));
    }

    @Test
    public void testWaitResponseEnabled() {
        when(mHandler.requestANQP(anyLong(), any())).thenReturn(true);
        mManager.requestANQPElements(TEST_BSSID, TEST_ANQP_KEY, true,
                NetworkDetail.HSRelease.R3);
        mManager.requestANQPElements(TEST_BSSID + 1, TEST_ANQP_KEY, true,
                NetworkDetail.HSRelease.R3);
        mManager.requestANQPElements(TEST_BSSID + 2, TEST_ANQP_KEY, true,
                NetworkDetail.HSRelease.R3);
        verify(mHandler).requestANQP(TEST_BSSID, R1R2_ANQP_WITH_RC);
        verify(mHandler).requestANQP(anyLong(), any());
        // Request completed, should process next one
        mManager.onRequestCompleted(TEST_BSSID, true);
        verify(mHandler).requestANQP(TEST_BSSID + 1, R1R2_ANQP_WITH_RC);
        verify(mHandler, times(2)).requestANQP(anyLong(), any());
        assertTrue(mAlarmManager.isPending(ANQP_REQUEST_ALARM_TAG));
        // If alarm has been triggered(request time out), should process next request
        mAlarmManager.dispatch(ANQP_REQUEST_ALARM_TAG);
        mLooper.dispatchAll();
        verify(mHandler).requestANQP(TEST_BSSID + 2, R1R2_ANQP_WITH_RC);
        verify(mHandler, times(3)).requestANQP(anyLong(), any());
        mManager.onRequestCompleted(TEST_BSSID + 2, true);
        // No more request in the queue, should process new request immediately
        mManager.requestANQPElements(TEST_BSSID + 3, TEST_ANQP_KEY, true,
                NetworkDetail.HSRelease.R3);
        verify(mHandler).requestANQP(TEST_BSSID + 3, R1R2_ANQP_WITH_RC);
    }
}
