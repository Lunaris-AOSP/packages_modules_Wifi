/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.wifi;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.wifi.TestUtil.createCapabilityBitset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.util.ScanResultUtil;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.google.android.collect.Sets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.Set;

/**
 * Unit tests for {@link WakeupEvaluator}.
 */
@SmallTest
public class WakeupEvaluatorTest extends WifiBaseTest {

    private static final String SAVED_SSID_1 = "saved ssid 1";
    private static final String SAVED_SSID_2 = "saved ssid 2";
    private static final String UNSAVED_SSID = "unsaved ssid";

    private static final int FREQ_24 = 2412;
    private static final int FREQ_5 = 5200;

    private static final int THRESHOLD_24 = -100;
    private static final int THRESHOLD_5 = -90;
    private final ScoringParams mScoringParams = new ScoringParams();

    @Mock private WifiInjector mWifiInjector;
    @Mock private ActiveModeWarden mActiveModeWarden;
    @Mock private ClientModeManager mPrimaryClientModeManager;
    @Mock private WifiGlobals mWifiGlobals;

    private WakeupEvaluator mWakeupEvaluator;
    private MockitoSession mSession;

    private ScanResult makeScanResult(String ssid, int frequency, int level) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.setWifiSsid(WifiSsid.fromUtf8Text(ssid));
        scanResult.frequency = frequency;
        scanResult.level = level;
        scanResult.capabilities = "[]";

        return scanResult;
    }

    private Set<ScanResultMatchInfo> getSavedNetworks() {
        Set<ScanResultMatchInfo> networks = new ArraySet<>();
        networks.add(ScanResultMatchInfo.fromWifiConfiguration(
                WifiConfigurationTestUtil.createOpenNetwork(
                        ScanResultUtil.createQuotedSsid(SAVED_SSID_1))));
        networks.add(ScanResultMatchInfo.fromWifiConfiguration(
                WifiConfigurationTestUtil.createOpenNetwork(
                        ScanResultUtil.createQuotedSsid(SAVED_SSID_2))));
        return networks;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // static mocking
        mSession = mockitoSession()
                .mockStatic(WifiInjector.class, withSettings().lenient())
                .strictness(Strictness.LENIENT)
                .startMocking();

        when(WifiInjector.getInstance()).thenReturn(mWifiInjector);
        when(mWifiInjector.getActiveModeWarden()).thenReturn(mActiveModeWarden);
        when(mActiveModeWarden.getPrimaryClientModeManager()).thenReturn(mPrimaryClientModeManager);
        when(mPrimaryClientModeManager.getSupportedFeaturesBitSet()).thenReturn(
                createCapabilityBitset(
                        WifiManager.WIFI_FEATURE_WPA3_SAE, WifiManager.WIFI_FEATURE_OWE));
        when(mWifiInjector.getWifiGlobals()).thenReturn(mWifiGlobals);
        when(mWifiGlobals.isWpa3SaeUpgradeEnabled()).thenReturn(true);
        when(mWifiGlobals.isOweUpgradeEnabled()).thenReturn(true);

        String params = "rssi2=-120:" + THRESHOLD_24 + ":-2:-1" + ","
                      + "rssi5=-120:" + THRESHOLD_5 + ":-2:-1";
        assertTrue(params, mScoringParams.update(params));
        mWakeupEvaluator = new WakeupEvaluator(mScoringParams);
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    /**
     * Verify that isBelowThreshold returns true for networks below the filter threshold.
     */
    @Test
    public void isBelowThreshold_returnsTrueWhenRssiIsBelowThreshold() {
        ScanResult scanResult24 = makeScanResult(SAVED_SSID_1, FREQ_24, THRESHOLD_24 - 1);
        assertTrue(mWakeupEvaluator.isBelowThreshold(scanResult24));

        ScanResult scanResult5 = makeScanResult(SAVED_SSID_1, FREQ_5, THRESHOLD_5 - 1);
        assertTrue(mWakeupEvaluator.isBelowThreshold(scanResult5));
    }

    /**
     * Verify that isBelowThreshold returns false for networks above the filter threshold.
     */
    @Test
    public void isBelowThreshold_returnsFalseWhenRssiIsAboveThreshold() {
        ScanResult scanResult24 = makeScanResult(SAVED_SSID_1, FREQ_24, THRESHOLD_24 + 1);
        assertFalse(mWakeupEvaluator.isBelowThreshold(scanResult24));

        ScanResult scanResult5 = makeScanResult(SAVED_SSID_1, FREQ_5, THRESHOLD_5 + 1);
        assertFalse(mWakeupEvaluator.isBelowThreshold(scanResult5));
    }

    /**
     * Verify that findViableNetwork does not select ScanResult that is not present in the
     * WifiConfigurations.
     */
    @Test
    public void findViableNetwork_returnsNullWhenScanResultIsNotInSavedNetworks() {
        Set<ScanResult> scanResults = Collections.singleton(
                makeScanResult(UNSAVED_SSID, FREQ_24, THRESHOLD_24 + 1));

        ScanResult scanResult = mWakeupEvaluator.findViableNetwork(scanResults, getSavedNetworks());

        assertNull(scanResult);
    }

    /**
     * Verify that findViableNetwork does not select a scan result that is below the threshold.
     */
    @Test
    public void findViableNetwork_returnsNullWhenScanResultIsBelowThreshold() {
        Set<ScanResult> scanResults = Collections.singleton(
                makeScanResult(SAVED_SSID_1, FREQ_24, THRESHOLD_24 - 1));

        ScanResult scanResult = mWakeupEvaluator.findViableNetwork(scanResults, getSavedNetworks());
        assertNull(scanResult);
    }

    /**
     * Verify that findViableNetwork returns a viable ScanResult.
     */
    @Test
    public void findViableNetwork_returnsConnectableScanResult() {
        ScanResult savedScanResult = makeScanResult(SAVED_SSID_1, FREQ_24, THRESHOLD_24 + 1);
        Set<ScanResult> scanResults = Collections.singleton(savedScanResult);

        ScanResult scanResult = mWakeupEvaluator.findViableNetwork(scanResults, getSavedNetworks());
        assertEquals(savedScanResult, scanResult);
    }

    /**
     * Verify that findViableNetwork returns the viable ScanResult with the highest RSSI.
     */
    @Test
    public void findViableNetwork_returnsConnectableScanResultWithHighestRssi() {
        ScanResult savedScanResultLow = makeScanResult(SAVED_SSID_1, FREQ_24, THRESHOLD_24 + 1);
        ScanResult savedScanResultHigh = makeScanResult(SAVED_SSID_1, FREQ_24, THRESHOLD_24 + 10);
        Set<ScanResult> scanResults = Sets.newArraySet(savedScanResultLow, savedScanResultHigh);

        ScanResult scanResult = mWakeupEvaluator.findViableNetwork(scanResults, getSavedNetworks());
        assertEquals(savedScanResultHigh, scanResult);
    }
}
