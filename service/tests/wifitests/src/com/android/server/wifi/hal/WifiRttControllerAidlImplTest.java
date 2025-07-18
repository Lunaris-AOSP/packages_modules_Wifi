/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wifi.hal;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.hardware.wifi.Akm;
import android.hardware.wifi.CipherSuite;
import android.hardware.wifi.IWifiRttController;
import android.hardware.wifi.IWifiRttControllerEventCallback;
import android.hardware.wifi.RttBw;
import android.hardware.wifi.RttCapabilities;
import android.hardware.wifi.RttConfig;
import android.hardware.wifi.RttPeerType;
import android.hardware.wifi.RttPreamble;
import android.hardware.wifi.RttResult;
import android.hardware.wifi.RttStatus;
import android.hardware.wifi.RttType;
import android.hardware.wifi.WifiChannelWidthInMhz;
import android.hardware.wifi.WifiInformationElement;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.ResponderConfig;

import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.rtt.RttTestUtils;

import org.hamcrest.core.IsNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class WifiRttControllerAidlImplTest extends WifiBaseTest {
    private WifiRttControllerAidlImpl mDut;
    @Mock private IWifiRttController mIWifiRttControllerMock;
    @Mock private WifiRttController.RttControllerRangingResultsCallback mRangingResultsCallbackMock;

    @Rule public ErrorCollector collector = new ErrorCollector();

    private ArgumentCaptor<RttConfig[]> mRttConfigCaptor =
            ArgumentCaptor.forClass(RttConfig[].class);
    private ArgumentCaptor<ArrayList> mRttResultCaptor = ArgumentCaptor.forClass(ArrayList.class);
    private ArgumentCaptor<IWifiRttControllerEventCallback.Stub> mEventCallbackCaptor =
            ArgumentCaptor.forClass(IWifiRttControllerEventCallback.Stub.class);
    private ArgumentCaptor<android.hardware.wifi.MacAddress[]> mMacAddressCaptor =
            ArgumentCaptor.forClass(android.hardware.wifi.MacAddress[].class);

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mIWifiRttControllerMock.getCapabilities()).thenReturn(getFullRttCapabilities());
        createAndInitializeDut();
    }

    private void createAndInitializeDut() throws Exception {
        mDut = new WifiRttControllerAidlImpl(mIWifiRttControllerMock);
        mDut.setup();
        mDut.registerRangingResultsCallback(mRangingResultsCallbackMock);
        verify(mIWifiRttControllerMock)
                .registerEventCallback(mEventCallbackCaptor.capture());
        verify(mIWifiRttControllerMock).getCapabilities();
        clearInvocations(mIWifiRttControllerMock);
    }

    /**
     * Verify RTT burst duration with respect to different burst sizes.
     */
    @Test
    public void testBurstDuration() throws Exception {
        int cmdId = 55;
        RangingRequest request = RttTestUtils.getDummyRangingRequestMcOnly((byte) 0, 8);
        mDut.rangeRequest(cmdId, request);
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());
        RttConfig[] halRequest = mRttConfigCaptor.getValue();
        RttConfig rttConfig = halRequest[0];
        collector.checkThat("(1) Rtt burst size", rttConfig.numFramesPerBurst, equalTo(8));
        collector.checkThat("(1) Rtt burst duration", rttConfig.burstDuration, equalTo(9));

        cmdId = 56;
        request = RttTestUtils.getDummyRangingRequestMcOnly((byte) 0, 20);
        mDut.rangeRequest(cmdId, request);
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());
        halRequest = mRttConfigCaptor.getValue();
        rttConfig = halRequest[0];
        collector.checkThat("(2) Rtt burst size", rttConfig.numFramesPerBurst, equalTo(20));
        collector.checkThat("(2) Rtt burst duration", rttConfig.burstDuration, equalTo(10));

        cmdId = 57;
        request = RttTestUtils.getDummyRangingRequestMcOnly((byte) 0, 30);
        mDut.rangeRequest(cmdId, request);
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());
        halRequest = mRttConfigCaptor.getValue();
        rttConfig = halRequest[0];
        collector.checkThat("(3) Rtt burst size", rttConfig.numFramesPerBurst, equalTo(30));
        collector.checkThat("(3) Rtt burst duration", rttConfig.burstDuration, equalTo(11));

        verifyNoMoreInteractions(mIWifiRttControllerMock);

    }

    /**
     * Validate successful 802.11az secure ranging flow.
     */
    @Test
    public void testOpportunisticSecureRangeRequest() throws Exception {
        int cmdId = 66;
        RangingRequest request = RttTestUtils.getDummySecureRangingRequest(
                RangingRequest.SECURITY_MODE_OPPORTUNISTIC);
        // Issue range request
        mDut.rangeRequest(cmdId, request);
        // Verify HAL call and parameters
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());
        // Verify contents of HAL request (hard codes knowledge from getDummySecureRangingRequest
        RttConfig[] halRequest = mRttConfigCaptor.getValue();
        collector.checkThat("number of entries", halRequest.length,
                equalTo(request.mRttPeers.size()));
        verifyNoMoreInteractions(mIWifiRttControllerMock);

        // 1. SAE with password
        RttConfig rttConfig = halRequest[0];
        collector.checkThat("entry 0: MAC", rttConfig.type,
                equalTo(RttType.TWO_SIDED_11AZ_NTB_SECURE));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.enableSecureHeLtf,
                equalTo(true));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnComebackCookie,
                equalTo(new byte[]{1, 2, 3, 4, 5}));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.baseAkm,
                equalTo(Akm.SAE));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.cipherSuite,
                equalTo(CipherSuite.GCMP_256));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.passphrase,
                equalTo("TEST_PASSWORD".getBytes()));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.pmkid,
                equalTo(null));

        // 2. SAE with no password will downgraded to unauthenticated PASN in case of
        // SECURITY_MODE_OPPORTUNISTIC
        rttConfig = halRequest[1];
        collector.checkThat("entry 0: MAC", rttConfig.type,
                equalTo(RttType.TWO_SIDED_11AZ_NTB_SECURE));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.enableSecureHeLtf,
                equalTo(true));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnComebackCookie,
                equalTo(null));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.baseAkm,
                equalTo(Akm.PASN));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.cipherSuite,
                equalTo(CipherSuite.GCMP_256));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.passphrase,
                equalTo(null));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.pmkid,
                equalTo(null));

        // 3. Secure ranging with unauthenticated PASN
        rttConfig = halRequest[2];
        collector.checkThat("entry 0: MAC", rttConfig.type,
                equalTo(RttType.TWO_SIDED_11AZ_NTB_SECURE));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.enableSecureHeLtf,
                equalTo(true));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnComebackCookie,
                equalTo(null));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.baseAkm,
                equalTo(Akm.PASN));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.cipherSuite,
                equalTo(CipherSuite.GCMP_256));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig.pasnConfig.pmkid,
                equalTo(null));

        // 4. Open security will use TWO_SIDED_11AZ_NTB
        rttConfig = halRequest[3];
        collector.checkThat("entry 0: MAC", rttConfig.type, equalTo(RttType.TWO_SIDED_11AZ_NTB));
        collector.checkThat("entry 0: secure Config", rttConfig.secureConfig, equalTo(null));
    }
    /**
     * Validate successful ranging flow.
     */
    @Test
    public void testRangeRequest() throws Exception {
        int cmdId = 55;
        RangingRequest request = RttTestUtils.getDummyRangingRequestWith11az((byte) 0);

        // (1) issue range request
        mDut.rangeRequest(cmdId, request);

        // (2) verify HAL call and parameters
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());

        // verify contents of HAL request (hard codes knowledge from getDummyRangingRequest()).
        RttConfig[] halRequest = mRttConfigCaptor.getValue();

        collector.checkThat("number of entries", halRequest.length,
                equalTo(request.mRttPeers.size()));

        RttConfig rttConfig = halRequest[0];
        collector.checkThat("entry 0: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("00:01:02:03:04:00").toByteArray()));
        collector.checkThat("entry 0: rtt type", rttConfig.type, equalTo(RttType.TWO_SIDED));
        collector.checkThat("entry 0: peer type", rttConfig.peer, equalTo(RttPeerType.AP));
        collector.checkThat("entry 0: lci", rttConfig.mustRequestLci, equalTo(true));
        collector.checkThat("entry 0: lcr", rttConfig.mustRequestLcr, equalTo(true));
        collector.checkThat("entry 0: rtt burst size", rttConfig.numFramesPerBurst,
                equalTo(RangingRequest.getMaxRttBurstSize()));

        rttConfig = halRequest[1];
        collector.checkThat("entry 1: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("0A:0B:0C:0D:0E:00").toByteArray()));
        collector.checkThat("entry 1: rtt type", rttConfig.type, equalTo(RttType.ONE_SIDED));
        collector.checkThat("entry 1: peer type", rttConfig.peer, equalTo(RttPeerType.AP));
        collector.checkThat("entry 1: lci", rttConfig.mustRequestLci, equalTo(true));
        collector.checkThat("entry 1: lcr", rttConfig.mustRequestLcr, equalTo(true));
        collector.checkThat("entry 1: rtt burst size", rttConfig.numFramesPerBurst,
                equalTo(RangingRequest.getMaxRttBurstSize()));

        rttConfig = halRequest[2];
        collector.checkThat("entry 2: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("08:09:08:07:06:05").toByteArray()));
        collector.checkThat("entry 2: rtt type", rttConfig.type, equalTo(RttType.TWO_SIDED));
        collector.checkThat("entry 2: peer type", rttConfig.peer, equalTo(RttPeerType.NAN_TYPE));
        collector.checkThat("entry 2: lci", rttConfig.mustRequestLci, equalTo(false));
        collector.checkThat("entry 2: lcr", rttConfig.mustRequestLcr, equalTo(false));
        collector.checkThat("entry 2: rtt burst size", rttConfig.numFramesPerBurst,
                equalTo(RangingRequest.getMaxRttBurstSize()));

        rttConfig = halRequest[3];
        collector.checkThat("entry 0: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("00:11:22:33:44:00").toByteArray()));
        collector.checkThat("entry 0: rtt type", rttConfig.type,
                equalTo(RttType.TWO_SIDED_11AZ_NTB));
        collector.checkThat("entry 0: peer type", rttConfig.peer, equalTo(RttPeerType.AP));
        collector.checkThat("entry 0: lci", rttConfig.mustRequestLci, equalTo(true));
        collector.checkThat("entry 0: lcr", rttConfig.mustRequestLcr, equalTo(true));
        collector.checkThat("entry 0: rtt burst size", rttConfig.numFramesPerBurst,
                equalTo(RangingRequest.getMaxRttBurstSize()));
        // ntbMinMeasurementTime in units of 100 us
        // DEFAULT_NTB_MIN_TIME_BETWEEN_MEASUREMENTS_MICROS = 250000 --> 2500 * 100 us
        collector.checkThat("", rttConfig.ntbMinMeasurementTime, equalTo(2500L));
        // ntbMaxMeasurementTime in units of 10 ms
        // DEFAULT_NTB_MAX_TIME_BETWEEN_MEASUREMENTS_MICROS = 15000000 --> 1500 * 10 ms
        collector.checkThat("", rttConfig.ntbMaxMeasurementTime, equalTo(1500L));
        verifyNoMoreInteractions(mIWifiRttControllerMock);
    }

    /**
     * Validate successful ranging flow - with privileges access but with limited capabilities:
     * - No single-sided RTT
     * - No LCI/LCR
     * - Limited BW
     * - Limited Preamble
     */
    @Test
    public void testRangeRequestWithLimitedCapabilities() throws Exception {
        int cmdId = 55;
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);

        // update capabilities to a limited set
        RttCapabilities cap = getFullRttCapabilities();
        cap.rttOneSidedSupported = false;
        cap.lciSupported = false;
        cap.lcrSupported = false;
        cap.bwSupport = RttBw.BW_10MHZ | RttBw.BW_160MHZ;
        cap.preambleSupport = RttPreamble.LEGACY;
        reset(mIWifiRttControllerMock);
        when(mIWifiRttControllerMock.getCapabilities()).thenReturn(cap);
        createAndInitializeDut();

        // Note: request 1: BW = 40MHz --> 10MHz, Preamble = HT (since 40MHz) -> Legacy

        // (1) issue range request
        mDut.rangeRequest(cmdId, request);

        // (2) verify HAL call and parameters
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());

        // verify contents of HAL request (hard codes knowledge from getDummyRangingRequest()).
        RttConfig[] halRequest = mRttConfigCaptor.getValue();

        assertEquals("number of entries", halRequest.length, 2);

        RttConfig rttConfig = halRequest[0];
        collector.checkThat("entry 0: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("00:01:02:03:04:00").toByteArray()));
        collector.checkThat("entry 0: rtt type", rttConfig.type, equalTo(
                RttType.TWO_SIDED));
        collector.checkThat("entry 0: peer type", rttConfig.peer, equalTo(
                RttPeerType.AP));
        collector.checkThat("entry 0: lci", rttConfig.mustRequestLci, equalTo(false));
        collector.checkThat("entry 0: lcr", rttConfig.mustRequestLcr, equalTo(false));
        collector.checkThat("entry 0: channel.width", rttConfig.channel.width, equalTo(
                WifiChannelWidthInMhz.WIDTH_40));
        collector.checkThat("entry 0: bw", rttConfig.bw, equalTo(RttBw.BW_10MHZ));
        collector.checkThat("entry 0: preamble", rttConfig.preamble, equalTo(
                RttPreamble.LEGACY));

        rttConfig = halRequest[1];
        collector.checkThat("entry 1: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("08:09:08:07:06:05").toByteArray()));
        collector.checkThat("entry 1: rtt type", rttConfig.type, equalTo(
                RttType.TWO_SIDED));
        collector.checkThat("entry 1: peer type", rttConfig.peer, equalTo(
                RttPeerType.NAN_TYPE));
        collector.checkThat("entry 1: lci", rttConfig.mustRequestLci, equalTo(false));
        collector.checkThat("entry 1: lcr", rttConfig.mustRequestLcr, equalTo(false));

        verifyNoMoreInteractions(mIWifiRttControllerMock);
    }

    /**
     * Validate IEEE 802.11az ranging request on an IEEE 802.11mc capable device. Expectation is
     * RTT type has to be downgraded to 11mc and pre-amble needs to be adjusted based on the band
     * of operation.
     */
    @Test
    public void test11azRangeRequestOn11mcCapableDevice() throws Exception {
        int cmdId = 55;
        RangingRequest request = RttTestUtils.getDummyRangingRequestWith11az((byte) 0);

        // update capabilities to enable 11mc only
        RttCapabilities cap = getFullRttCapabilities();
        cap.ntbInitiatorSupported = false;
        reset(mIWifiRttControllerMock);
        when(mIWifiRttControllerMock.getCapabilities()).thenReturn(cap);
        createAndInitializeDut();

        mDut.rangeRequest(cmdId, request);
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());
        RttConfig[] halRequest = mRttConfigCaptor.getValue();

        collector.checkThat("number of entries", halRequest.length,
                equalTo(request.mRttPeers.size()));

        RttConfig rttConfig = halRequest[0];
        collector.checkThat("entry 0: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("00:01:02:03:04:00").toByteArray()));
        collector.checkThat("entry 0: rtt type", rttConfig.type, equalTo(RttType.TWO_SIDED));
        collector.checkThat("entry 0: peer type", rttConfig.peer, equalTo(RttPeerType.AP));
        collector.checkThat("", rttConfig.preamble, equalTo(RttPreamble.VHT));

        rttConfig = halRequest[1];
        collector.checkThat("entry 1: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("0A:0B:0C:0D:0E:00").toByteArray()));
        collector.checkThat("entry 1: rtt type", rttConfig.type, equalTo(RttType.ONE_SIDED));
        collector.checkThat("entry 1: peer type", rttConfig.peer, equalTo(RttPeerType.AP));
        collector.checkThat("", rttConfig.preamble, equalTo(RttPreamble.HT));

        rttConfig = halRequest[2];
        collector.checkThat("entry 2: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("08:09:08:07:06:05").toByteArray()));
        collector.checkThat("entry 2: rtt type", rttConfig.type, equalTo(RttType.TWO_SIDED));
        collector.checkThat("entry 2: peer type", rttConfig.peer, equalTo(RttPeerType.NAN_TYPE));
        collector.checkThat("", rttConfig.preamble, equalTo(RttPreamble.HT));

        rttConfig = halRequest[3];
        collector.checkThat("entry 3: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("00:11:22:33:44:00").toByteArray()));
        collector.checkThat("entry 3: rtt type", rttConfig.type, equalTo(RttType.TWO_SIDED_11MC));
        collector.checkThat("entry 3: peer type", rttConfig.peer, equalTo(RttPeerType.AP));
        collector.checkThat("entry 3: preamble", rttConfig.preamble, equalTo(RttPreamble.VHT));

        verifyNoMoreInteractions(mIWifiRttControllerMock);

    }
    /**
     * Validate successful ranging flow - with privileges access but with limited capabilities:
     * - Very limited BW
     * - Very limited Preamble
     */
    @Test
    public void testRangeRequestWithLimitedCapabilitiesNoOverlap() throws Exception {
        int cmdId = 55;
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);

        // update capabilities to a limited set
        RttCapabilities cap = getFullRttCapabilities();
        cap.bwSupport = RttBw.BW_80MHZ;
        cap.preambleSupport = RttPreamble.VHT;
        reset(mIWifiRttControllerMock);
        when(mIWifiRttControllerMock.getCapabilities()).thenReturn(cap);
        createAndInitializeDut();

        // Note: request 1: BW = 40MHz --> no overlap -> dropped
        // Note: request 2: BW = 160MHz --> 160MHz, preamble = VHT (since 160MHz) -> no overlap,
        //                                                                           dropped

        // (1) issue range request
        mDut.rangeRequest(cmdId, request);

        // (2) verify HAL call and parameters
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());

        // verify contents of HAL request (hard codes knowledge from getDummyRangingRequest()).
        RttConfig[] halRequest = mRttConfigCaptor.getValue();

        collector.checkThat("number of entries", halRequest.length, equalTo(1));

        RttConfig rttConfig = halRequest[0];
        collector.checkThat("entry 0: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("08:09:08:07:06:05").toByteArray()));
        collector.checkThat("entry 0: rtt type", rttConfig.type, equalTo(
                RttType.TWO_SIDED));
        collector.checkThat("entry 0: peer type", rttConfig.peer, equalTo(
                RttPeerType.NAN_TYPE));
        collector.checkThat("entry 0: lci", rttConfig.mustRequestLci, equalTo(false));
        collector.checkThat("entry 0: lcr", rttConfig.mustRequestLcr, equalTo(false));

        verifyNoMoreInteractions(mIWifiRttControllerMock);
    }

    /**
     * Validate ranging cancel flow.
     */
    @Test
    public void testRangeCancel() throws Exception {
        int cmdId = 66;
        ArrayList<MacAddress> macAddresses = new ArrayList<>();
        MacAddress mac1 = MacAddress.fromString("00:01:02:03:04:05");
        MacAddress mac2 = MacAddress.fromString("0A:0B:0C:0D:0E:0F");
        macAddresses.add(mac1);
        macAddresses.add(mac2);

        // (1) issue cancel request
        mDut.rangeCancel(cmdId, macAddresses);

        // (2) verify HAL call and parameters
        verify(mIWifiRttControllerMock).rangeCancel(eq(cmdId), mMacAddressCaptor.capture());
        assertArrayEquals(mac1.toByteArray(), mMacAddressCaptor.getValue()[0].data);
        assertArrayEquals(mac2.toByteArray(), mMacAddressCaptor.getValue()[1].data);

        verifyNoMoreInteractions(mIWifiRttControllerMock);
    }

    /**
     * Validate correct result conversion from HAL to framework.
     */
    @Test
    public void testRangeResults() throws Exception {
        int cmdId = 55;
        RttResult[] results = new RttResult[1];
        RttResult res = createRttResult();
        res.addr = MacAddress.byteAddrFromStringAddr("05:06:07:08:09:0A");
        res.status = RttStatus.SUCCESS;
        res.distanceInMm = 1500;
        res.timeStampInUs = 6000;
        res.packetBw = RttBw.BW_80MHZ;
        results[0] = res;

        // (1) have the HAL call us with results
        mEventCallbackCaptor.getValue().onResults(cmdId, results);

        // (2) verify call to framework
        verify(mRangingResultsCallbackMock).onRangingResults(eq(cmdId), mRttResultCaptor.capture());

        // verify contents of the framework results
        List<RangingResult> rttR = mRttResultCaptor.getValue();

        collector.checkThat("number of entries", rttR.size(), equalTo(1));

        RangingResult rttResult = rttR.get(0);
        collector.checkThat("status", rttResult.getStatus(),
                equalTo(WifiRttController.FRAMEWORK_RTT_STATUS_SUCCESS));
        collector.checkThat("mac", rttResult.getMacAddress().toByteArray(),
                equalTo(MacAddress.fromString("05:06:07:08:09:0A").toByteArray()));
        collector.checkThat("distanceCm", rttResult.getDistanceMm(), equalTo(1500));
        collector.checkThat("timestamp", rttResult.getRangingTimestampMillis(), equalTo(6L));
        collector.checkThat("channelBw", rttResult.getMeasurementBandwidth(),
                equalTo(ScanResult.CHANNEL_WIDTH_80MHZ));
        verifyNoMoreInteractions(mIWifiRttControllerMock);
    }

    /**
     * Validate correct 11az NTB result conversion from HAL to framework.
     */
    @Test
    public void test11azNtbRangeResults() throws Exception {
        int cmdId = 55;
        RttResult[] results = new RttResult[1];
        RttResult res = createRttResult();
        res.type = RttType.TWO_SIDED_11AZ_NTB;
        res.addr = MacAddress.byteAddrFromStringAddr("05:06:07:08:09:0A");
        res.ntbMaxMeasurementTime = 10; // 10 * 10000 us = 100000 us
        res.ntbMinMeasurementTime = 100; // 100 * 100 us = 10000 us
        res.numRxSpatialStreams = 2;
        res.numTxSpatialStreams = 3;
        res.i2rTxLtfRepetitionCount = 3;
        res.r2iTxLtfRepetitionCount = 2;
        res.status = RttStatus.SUCCESS;
        res.distanceInMm = 1500;
        res.timeStampInUs = 6000;
        res.packetBw = RttBw.BW_80MHZ;
        results[0] = res;

        // (1) have the HAL call us with results
        mEventCallbackCaptor.getValue().onResults(cmdId, results);

        // (2) verify call to framework
        verify(mRangingResultsCallbackMock).onRangingResults(eq(cmdId), mRttResultCaptor.capture());

        // verify contents of the framework results
        List<RangingResult> rttR = mRttResultCaptor.getValue();

        collector.checkThat("number of entries", rttR.size(), equalTo(1));

        RangingResult rttResult = rttR.get(0);
        collector.checkThat("Type", rttResult.is80211azNtbMeasurement(), equalTo(true));
        collector.checkThat("status", rttResult.getStatus(),
                equalTo(WifiRttController.FRAMEWORK_RTT_STATUS_SUCCESS));
        collector.checkThat("mac", rttResult.getMacAddress().toByteArray(),
                equalTo(MacAddress.fromString("05:06:07:08:09:0A").toByteArray()));
        collector.checkThat("ntbMaxMeasurementTime",
                rttResult.getMaxTimeBetweenNtbMeasurementsMicros(), equalTo(100000L));
        collector.checkThat("ntbMinMeasurementTime",
                rttResult.getMinTimeBetweenNtbMeasurementsMicros(), equalTo(10000L));
        collector.checkThat("numRxSpatialStreams", rttResult.get80211azNumberOfRxSpatialStreams(),
                equalTo(2));
        collector.checkThat("numTxSpatialStreams", rttResult.get80211azNumberOfTxSpatialStreams(),
                equalTo(3));
        collector.checkThat("i2rTxLtfRepetitionCount",
                rttResult.get80211azInitiatorTxLtfRepetitionsCount(), equalTo(3));
        collector.checkThat("r2iTxLtfRepetitionCount",
                rttResult.get80211azResponderTxLtfRepetitionsCount(), equalTo(2));
        collector.checkThat("distanceCm", rttResult.getDistanceMm(), equalTo(1500));
        collector.checkThat("timestamp", rttResult.getRangingTimestampMillis(), equalTo(6L));
        collector.checkThat("channelBw", rttResult.getMeasurementBandwidth(),
                equalTo(ScanResult.CHANNEL_WIDTH_80MHZ));
        verifyNoMoreInteractions(mIWifiRttControllerMock);
    }

    /**
     * Validate correct 11az NTB secure result conversion from HAL to framework.
     */
    @Test
    public void test11azNtbSecureRangeResults() throws Exception {
        int cmdId = 55;
        RttResult[] results = new RttResult[1];
        RttResult res = createRttResult();
        res.type = RttType.TWO_SIDED_11AZ_NTB_SECURE;
        res.addr = MacAddress.byteAddrFromStringAddr("05:06:07:08:09:0A");
        res.ntbMaxMeasurementTime = 10; // 10 * 10000 us = 100000 us
        res.ntbMinMeasurementTime = 100; // 100 * 100 us = 10000 us
        res.numRxSpatialStreams = 2;
        res.numTxSpatialStreams = 3;
        res.i2rTxLtfRepetitionCount = 3;
        res.r2iTxLtfRepetitionCount = 2;
        res.status = RttStatus.SUCCESS;
        res.distanceInMm = 1500;
        res.timeStampInUs = 6000;
        res.packetBw = RttBw.BW_80MHZ;
        // Fill in secure ranging results
        res.pasnComebackAfterMillis = 1000;
        res.baseAkm  = Akm.PASN | Akm.SAE;
        res.isRangingFrameProtectionEnabled = true;
        res.isSecureLtfEnabled = true;
        res.secureHeLtfProtocolVersion = 1;
        res.pasnComebackCookie = new byte[] {1, 2, 3};
        results[0] = res;

        // (1) have the HAL call us with results
        mEventCallbackCaptor.getValue().onResults(cmdId, results);

        // (2) verify call to framework
        verify(mRangingResultsCallbackMock).onRangingResults(eq(cmdId), mRttResultCaptor.capture());

        // verify contents of the framework results
        List<RangingResult> rttR = mRttResultCaptor.getValue();

        collector.checkThat("number of entries", rttR.size(), equalTo(1));

        RangingResult rttResult = rttR.get(0);
        collector.checkThat("Type", rttResult.is80211azNtbMeasurement(), equalTo(true));
        collector.checkThat("status", rttResult.getStatus(),
                equalTo(WifiRttController.FRAMEWORK_RTT_STATUS_SUCCESS));
        collector.checkThat("mac", rttResult.getMacAddress().toByteArray(),
                equalTo(MacAddress.fromString("05:06:07:08:09:0A").toByteArray()));
        collector.checkThat("ntbMaxMeasurementTime",
                rttResult.getMaxTimeBetweenNtbMeasurementsMicros(), equalTo(100000L));
        collector.checkThat("ntbMinMeasurementTime",
                rttResult.getMinTimeBetweenNtbMeasurementsMicros(), equalTo(10000L));
        collector.checkThat("numRxSpatialStreams", rttResult.get80211azNumberOfRxSpatialStreams(),
                equalTo(2));
        collector.checkThat("numTxSpatialStreams", rttResult.get80211azNumberOfTxSpatialStreams(),
                equalTo(3));
        collector.checkThat("i2rTxLtfRepetitionCount",
                rttResult.get80211azInitiatorTxLtfRepetitionsCount(), equalTo(3));
        collector.checkThat("r2iTxLtfRepetitionCount",
                rttResult.get80211azResponderTxLtfRepetitionsCount(), equalTo(2));
        collector.checkThat("distanceCm", rttResult.getDistanceMm(), equalTo(1500));
        collector.checkThat("timestamp", rttResult.getRangingTimestampMillis(), equalTo(6L));
        collector.checkThat("channelBw", rttResult.getMeasurementBandwidth(),
                equalTo(ScanResult.CHANNEL_WIDTH_80MHZ));
        // check secure ranging parameters
        collector.checkThat("pasnComebackAfterMillis", rttResult.getPasnComebackAfterMillis(),
                equalTo(1000L));
        collector.checkThat("baseAkm", rttResult.isRangingAuthenticated(), equalTo(true));
        collector.checkThat("isRangingFrameProtectionEnabled", rttResult.isRangingFrameProtected(),
                equalTo(true));
        collector.checkThat("isSecureLtfEnabled", rttResult.isSecureHeLtfEnabled(), equalTo(true));
        collector.checkThat("secureHeLtfProtocolVersion", rttResult.getSecureHeLtfProtocolVersion(),
                equalTo(1));
        collector.checkThat("pasnComebackCookie", rttResult.getPasnComebackCookie(),
                equalTo(new byte[]{1, 2, 3}));
        verifyNoMoreInteractions(mIWifiRttControllerMock);
    }


    /**
     * Validate correct cleanup when a null array of results is provided by HAL.
     */
    @Test
    public void testRangeResultsNullArray() throws Exception {
        int cmdId = 66;

        mEventCallbackCaptor.getValue().onResults(cmdId, null);
        verify(mRangingResultsCallbackMock).onRangingResults(eq(cmdId), mRttResultCaptor.capture());

        collector.checkThat("number of entries", mRttResultCaptor.getValue().size(), equalTo(0));
    }

    /**
     * Validate correct cleanup when an array of results containing null entries is provided by HAL.
     */
    @Test
    public void testRangeResultsSomeNulls() throws Exception {
        int cmdId = 77;

        RttResult[] results = new RttResult[]
                {null, createRttResult(), null, null, createRttResult(), null};

        mEventCallbackCaptor.getValue().onResults(cmdId, results);
        verify(mRangingResultsCallbackMock).onRangingResults(eq(cmdId), mRttResultCaptor.capture());

        List<RttResult> rttR = mRttResultCaptor.getValue();
        collector.checkThat("number of entries", rttR.size(), equalTo(2));
        for (int i = 0; i < rttR.size(); ++i) {
            collector.checkThat("entry", rttR.get(i), IsNull.notNullValue());
        }
    }

    /**
     * Validation ranging with invalid bw and preamble combination will be ignored.
     */
    @Test
    public void testRangingWithInvalidParameterCombination() throws Exception {
        int cmdId = 88;
        RangingRequest request = new RangingRequest.Builder().build();
        ResponderConfig invalidConfig = new ResponderConfig.Builder()
                .setMacAddress(MacAddress.fromString("08:09:08:07:06:88"))
                .setResponderType(ResponderConfig.RESPONDER_AP)
                .set80211mcSupported(true)
                .setChannelWidth(ScanResult.CHANNEL_WIDTH_80MHZ)
                .setPreamble(ScanResult.PREAMBLE_HT)
                .build();
        ResponderConfig config = new ResponderConfig.Builder()
                .setMacAddress(MacAddress.fromString("08:09:08:07:06:89"))
                .setResponderType(ResponderConfig.RESPONDER_AP)
                .set80211mcSupported(true)
                .setChannelWidth(ScanResult.CHANNEL_WIDTH_80MHZ)
                .setPreamble(ScanResult.PREAMBLE_VHT)
                .build();

        // Add a ResponderConfig with invalid parameter, should be ignored.
        request.mRttPeers.add(invalidConfig);
        request.mRttPeers.add(config);
        mDut.rangeRequest(cmdId, request);
        verify(mIWifiRttControllerMock).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());
        assertEquals(request.mRttPeers.size() - 1, mRttConfigCaptor.getValue().length);
    }


    // Utilities

    /**
     * Return an RttCapabilities structure with all features enabled and support for all
     * preambles and bandwidths. The purpose is to enable any request. The returned structure can
     * then be modified to disable specific features.
     */
    RttCapabilities getFullRttCapabilities() {
        RttCapabilities cap = new RttCapabilities();

        cap.rttOneSidedSupported = true;
        cap.rttFtmSupported = true;
        cap.lciSupported = true;
        cap.lcrSupported = true;
        cap.responderSupported = true; // unused
        cap.ntbInitiatorSupported = true;
        cap.preambleSupport = RttPreamble.LEGACY | RttPreamble.HT | RttPreamble.VHT
                | RttPreamble.HE;
        cap.azPreambleSupport = cap.preambleSupport;
        cap.bwSupport =
                RttBw.BW_5MHZ | RttBw.BW_10MHZ | RttBw.BW_20MHZ | RttBw.BW_40MHZ | RttBw.BW_80MHZ
                        | RttBw.BW_160MHZ;
        cap.azBwSupport = cap.bwSupport;
        cap.mcVersion = 1; // unused
        cap.akmsSupported = Akm.PASN | Akm.SAE;
        cap.cipherSuitesSupported =
                CipherSuite.GCMP_256 | CipherSuite.GCMP_128 | CipherSuite.CCMP_128
                        | CipherSuite.CCMP_256;
        cap.secureHeLtfSupported = true;
        cap.rangingFrameProtectionSupported = true;
        cap.maxSupportedSecureHeLtfProtocolVersion = 0;

        return cap;
    }

    /**
     * Returns an RttResult with default values for any non-primitive fields.
     */
    RttResult createRttResult() {
        RttResult res = new RttResult();
        res.lci = new WifiInformationElement();
        res.lcr = new WifiInformationElement();
        res.addr = MacAddress.byteAddrFromStringAddr("aa:bb:cc:dd:ee:ff");
        return res;
    }
}
