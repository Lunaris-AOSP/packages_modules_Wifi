/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wifi.p2p;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.hardware.wifi.V1_0.IWifiP2pIface;
import android.net.MacAddress;
import android.net.wifi.WifiMigration;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDirInfo;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pUsdBasedLocalServiceAdvertisementConfig;
import android.net.wifi.p2p.WifiP2pUsdBasedServiceDiscoveryConfig;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pUsdBasedServiceConfig;
import android.net.wifi.util.Environment;
import android.os.Handler;
import android.os.WorkSource;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.wifi.DeviceConfigFacade;
import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.PropertyService;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiMetrics;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiSettingsConfigStore;
import com.android.server.wifi.WifiVendorHal;
import com.android.server.wifi.hal.WifiHal;
import com.android.wifi.flags.FeatureFlags;
import com.android.wifi.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.WifiP2pMonitor}.
 */
@SmallTest
public class WifiP2pNativeTest extends WifiBaseTest {

    private static final String TEST_DEVICE_NAME = "Android_HelloWorld";
    private static final String TEST_IFACE = "p2p-p2p0-1";
    private static final String TEST_BSSID = "de:ad:be:ef:01:02";
    private static final String TEST_PIN = "12345678";
    private static final String TEST_DEVICE_TYPE = "12-1234abcd-08";
    private static final String TEST_WPS_CONFIG = "usba label display push_button keypad";
    private static final String TEST_SSID_POSTFIX = "NiceBoat";
    private static final int TEST_IDLE_TIME = 10;
    private static final String TEST_NETWORK_NAME = "DIRECT-xy-NiceBoat";
    private static final String TEST_PASSPHRASE = "DeadEnd!";
    private static final int TEST_GROUP_FREQ = 5400;
    private static final String TEST_WFD_DEVICE_INFO = "deadbeef";
    private static final int TEST_P2P_FIND_TIMEOUT = 120;
    private static final String TEST_SERVICE_DISCOVERY_IDENTIFIER = "identifier";
    private static final String TEST_SERVICE_DISCOVERY_QUERY = "query";
    private static final String TEST_NFC_REQUEST_MSG = "request";
    private static final String TEST_NFC_SELECT_MSG = "select";
    private static final String TEST_CLIENT_LIST = "aa:bb:cc:dd:ee:ff 11:22:33:44:55:66";
    private static final String TEST_R2_DEVICE_INFO_HEX = "00020064";
    private static final String TEST_USD_SERVICE_NAME = "test_service_name";
    private static final int TEST_USD_PROTOCOL_TYPE = 4;
    private static final byte[] TEST_USD_SERVICE_SPECIFIC_INFO = {10, 20, 30, 40, 50, 60};
    private static final int TEST_USD_DISCOVERY_CHANNEL_FREQUENCY_MHZ = 2437;
    private static final int[] TEST_USD_DISCOVERY_CHANNEL_FREQUENCIES_MHZ = {2412, 2437, 2462};
    private static final int TEST_USD_TIMEOUT_SEC = 30;
    private static final int TEST_USD_SESSION_ID = 2;
    private static final byte[] TEST_NONCE = {10, 20, 30, 40, 50, 60, 70, 80};
    private static final byte[] TEST_DIR_TAG = {11, 22, 33, 44, 55, 66, 77, 88};
    private static final WifiP2pDirInfo TEST_DIR_INFO = new WifiP2pDirInfo(
            MacAddress.fromString(TEST_BSSID), TEST_NONCE, TEST_DIR_TAG);

    @Mock private WifiNl80211Manager mWifiCondManager;
    @Mock private WifiNative mWifiNative;
    @Mock private WifiMetrics mWifiMetrics;
    @Mock private WifiVendorHal mWifiVendorHalMock;
    @Mock private SupplicantP2pIfaceHal mSupplicantP2pIfaceHalMock;
    @Mock private HalDeviceManager mHalDeviceManagerMock;
    @Mock private HalDeviceManager.InterfaceDestroyedListener mDestroyedListenerMock;
    @Mock private PropertyService mPropertyServiceMock;
    @Mock private Handler mHandlerMock;
    @Mock private WorkSource mWorkSourceMock;
    @Mock private IWifiP2pIface mIWifiP2pIfaceMock;
    @Mock private WifiNative.Iface mMockP2pIface;
    @Mock private WifiInjector mWifiInjector;
    @Mock private DeviceConfigFacade mDeviceConfigFacade;
    @Mock private FeatureFlags mFeatureFlags;
    private @Mock WifiSettingsConfigStore mWifiSettingsConfigStore;

    private MockitoSession mSession;
    private WifiP2pNative mWifiP2pNative;
    private WifiP2pGroupList mWifiP2pGroupList = new WifiP2pGroupList();
    private Set<String> mWifiClientInterfaceNames = new HashSet<String>();

    private WifiP2pGroup createP2pGroup(
            int networkId, String networkName, String passphrase, boolean isGo, String goAddr) {
        WifiP2pGroup group = new WifiP2pGroup();
        group.setNetworkId(networkId);
        group.setNetworkName(networkName);
        group.setPassphrase(passphrase);
        group.setIsGroupOwner(isGo);
        group.setOwner(new WifiP2pDevice(goAddr));
        return group;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mSession = ExtendedMockito.mockitoSession()
                .mockStatic(HalDeviceManager.class, withSettings().lenient())
                .strictness(Strictness.LENIENT)
                .mockStatic(Flags.class, withSettings().lenient())
                .mockStatic(WifiMigration.class, withSettings().lenient())
                .startMocking();
        when(Flags.wifiDirectR2()).thenReturn(false);
        mWifiClientInterfaceNames.add("wlan0");
        mWifiClientInterfaceNames.add("wlan1");
        when(mWifiInjector.getDeviceConfigFacade()).thenReturn(mDeviceConfigFacade);
        when(mDeviceConfigFacade.getFeatureFlags()).thenReturn(mFeatureFlags);
        when(mWifiInjector.getSettingsConfigStore()).thenReturn(mWifiSettingsConfigStore);
        when(mWifiSettingsConfigStore
                .get(eq(WifiSettingsConfigStore.SUPPLICANT_HAL_AIDL_SERVICE_VERSION)))
                .thenReturn(2);
        if (Environment.isSdkAtLeastB()) {
            when(mWifiSettingsConfigStore
                    .get(eq(WifiSettingsConfigStore.WIFI_P2P_SUPPORTED_FEATURES)))
                    .thenReturn(WifiP2pManager.FEATURE_WIFI_DIRECT_R2
                            | WifiP2pManager.FEATURE_PCC_MODE_ALLOW_LEGACY_AND_R2_CONNECTION);
        } else {
            when(mWifiSettingsConfigStore
                    .get(eq(WifiSettingsConfigStore.WIFI_P2P_SUPPORTED_FEATURES)))
                    .thenReturn(0L);
        }
        mWifiP2pNative = new WifiP2pNative(mWifiCondManager, mWifiNative, mWifiMetrics,
                mWifiVendorHalMock, mSupplicantP2pIfaceHalMock, mHalDeviceManagerMock,
                mPropertyServiceMock, mWifiInjector);
        if (Environment.isSdkAtLeastB()) {
            assertEquals(WifiP2pManager.FEATURE_SET_VENDOR_ELEMENTS
                            | WifiP2pManager.FEATURE_FLEXIBLE_DISCOVERY
                            | WifiP2pManager.FEATURE_GROUP_CLIENT_REMOVAL
                            | WifiP2pManager.FEATURE_GROUP_OWNER_IPV6_LINK_LOCAL_ADDRESS_PROVIDED
                            | WifiP2pManager.FEATURE_WIFI_DIRECT_R2
                            | WifiP2pManager.FEATURE_PCC_MODE_ALLOW_LEGACY_AND_R2_CONNECTION,
                    mWifiP2pNative.getSupportedFeatures());
        } else {
            assertEquals(WifiP2pManager.FEATURE_SET_VENDOR_ELEMENTS
                            | WifiP2pManager.FEATURE_FLEXIBLE_DISCOVERY
                            | WifiP2pManager.FEATURE_GROUP_CLIENT_REMOVAL
                            | WifiP2pManager.FEATURE_GROUP_OWNER_IPV6_LINK_LOCAL_ADDRESS_PROVIDED,
                    mWifiP2pNative.getSupportedFeatures());
        }

        when(mWifiNative.getClientInterfaceNames()).thenReturn(mWifiClientInterfaceNames);

        mWifiP2pGroupList.add(
                createP2pGroup(1, "testGroup1", "passphrase", true, "aa:bb:cc:dd:ee:f0"));
        mWifiP2pGroupList.add(
                createP2pGroup(2, "testGroup2", "passphrase", false, "aa:bb:cc:dd:ee:f0"));
        mWifiP2pGroupList.add(
                createP2pGroup(3, "testGroup3", "passphrase", true, "aa:bb:cc:dd:ee:aa"));
        mWifiP2pGroupList.add(
                createP2pGroup(4, "testGroup4", "passphrase", true, "aa:bb:cc:dd:ee:bb"));

        // setup default mock behaviors
        when(mHalDeviceManagerMock.isSupported()).thenReturn(true);
        mMockP2pIface.name = TEST_IFACE;
        when(mWifiNative.createP2pIface(any(HalDeviceManager.InterfaceDestroyedListener.class),
                any(Handler.class), any(WorkSource.class))).thenReturn(mMockP2pIface);
        doAnswer(new AnswerWithArguments() {
                public boolean answer(WifiP2pGroupList groupList) {
                    for (WifiP2pGroup g : mWifiP2pGroupList.getGroupList()) {
                        groupList.add(g);
                    }
                    return true;
                }
        }).when(mSupplicantP2pIfaceHalMock).loadGroups(any());

    }

    @After
    public void tearDown() {
        mSession.finishMocking();
    }

    /**
     * Verifies that isHalInterfaceSupported returns correct values.
     */
    @Test
    public void testIsHalInterfaceSupported() {
        assertTrue(mWifiP2pNative.isHalInterfaceSupported());

        when(mHalDeviceManagerMock.isSupported()).thenReturn(false);
        assertFalse(mWifiP2pNative.isHalInterfaceSupported());
    }

    /**
     * Verifies that setupInterface by WifiNative returns correct values
     * when successfully creating P2P Iface. (The default behavior)
     */
    @Test
    public void testSetupInterfaceByWifiNativeSuccessInCreatingP2pIface() {
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.isInitializationComplete()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.setupIface(eq(TEST_IFACE))).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.registerDeathHandler(any())).thenReturn(true);

        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                TEST_IFACE);
    }

    /**
     * Verifies that setupInterface returns correct values when vendor Hal doesn't support.
     */
    @Test
    public void testSetupInterfaceSuccessWhenVendorHalDoesNotSupport() {
        when(mHalDeviceManagerMock.isSupported()).thenReturn(false);
        when(mPropertyServiceMock.getString(anyString(), anyString())).thenReturn(TEST_IFACE);
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.isInitializationComplete()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.setupIface(eq(TEST_IFACE))).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.registerDeathHandler(any())).thenReturn(true);

        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                TEST_IFACE);
    }

    /**
     * Verifies that setupInterface returns correct values when failing in creating P2P Iface
     * by WifiNative.
     */
    @Test
    public void testSetupInterfaceFailureInCreatingP2pIfaceByWifiNative() {
        when(mWifiNative.createP2pIface(
                any(HalDeviceManager.InterfaceDestroyedListener.class),
                eq(mHandlerMock), eq(mWorkSourceMock))).thenReturn(null);
        when(mHalDeviceManagerMock.isItPossibleToCreateIface(
                eq(HalDeviceManager.HDM_CREATE_IFACE_P2P), eq(mWorkSourceMock))).thenReturn(true);

        mWifiP2pNative.setupInterface(mDestroyedListenerMock, mHandlerMock, mWorkSourceMock);
        verify(mWifiMetrics).incrementNumSetupP2pInterfaceFailureDueToHal();
        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                null);
    }

    /**
     * Verifies that Wi-Fi metrics do correct action when setting up p2p interface failed and
     * HalDevMgr not possibly creating it.
     */
    @Test
    public void testSetupInterfaceFailureInCreatingP2pByWifiNativeAndHalDevMgrNotPossiblyCreate() {
        when(mWifiNative.createP2pIface(
                any(HalDeviceManager.InterfaceDestroyedListener.class),
                eq(mHandlerMock), eq(mWorkSourceMock))).thenReturn(null);
        when(mHalDeviceManagerMock.isItPossibleToCreateIface(
                eq(HalDeviceManager.HDM_CREATE_IFACE_P2P), eq(mWorkSourceMock))).thenReturn(false);

        mWifiP2pNative.setupInterface(mDestroyedListenerMock, mHandlerMock, mWorkSourceMock);
        verify(mWifiMetrics, never()).incrementNumSetupP2pInterfaceFailureDueToHal();
    }

    /**
     * Verifies that setupInterface returns correct values when supplicant connection
     * initialization fails.
     */
    @Test
    public void testSetupInterfaceByWifiNativeAndFailureInSupplicantConnectionInitialization() {
        when(mSupplicantP2pIfaceHalMock.isInitializationStarted()).thenReturn(false);
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(false);
        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                null);
        verify(mWifiMetrics).incrementNumSetupP2pInterfaceFailureDueToSupplicant();
    }

    /**
     * Verifies that setupInterface returns correct values when supplicant connection
     * initialization never completes.
     */
    @Test
    public void testSetupInterfaceByWifiNativeAndFailureInSupplicantConnectionInitNotCompleted() {
        when(mSupplicantP2pIfaceHalMock.setupIface(eq(TEST_IFACE))).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.isInitializationComplete()).thenReturn(false);

        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                null);
        verify(mWifiMetrics).incrementNumSetupP2pInterfaceFailureDueToSupplicant();
    }

    /**
     * Verifies that setupInterface returns correct values when failing in setting up P2P Iface
     * for supplicant.
     */
    @Test
    public void testSetupInterfaceByWifiNativeAndFailureInSettingUpP2pIfaceInSupplicant() {
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.isInitializationComplete()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.setupIface(eq(TEST_IFACE))).thenReturn(false);

        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                null);
        verify(mWifiMetrics).incrementNumSetupP2pInterfaceFailureDueToSupplicant();
    }

    /**
     * Verifies that setupInterface returns correct values when failing in setting up
     * P2P supplicant handler.
     */
    @Test
    public void testSetupInterfaceFailureInSettingUpP2pIfaceInSupplicantRegisterDeathHandler() {
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.isInitializationComplete()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.setupIface(eq(TEST_IFACE))).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.registerDeathHandler(any())).thenReturn(false);
        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                null);
        verify(mWifiMetrics).incrementNumSetupP2pInterfaceFailureDueToSupplicant();
    }

    /**
     * Verifies that setupInterface returns correct values when mIWifiP2pIface already exists,
     * and HalDeviceManager does support.
     */
    @Test
    public void testSetupInterfaceSuccessWhenHalDeviceMgrDoesSupport() throws Exception {
        prepareDbsMock(true);
        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                TEST_IFACE);
    }

    /**
     * Verifies that setupInterface returns correct values when mIWifiP2pIface already exists,
     * and HalDeviceManager doesn't support.
     */
    @Test
    public void testSetupInterfaceSuccessWhenNoHalDeviceMgrSupport() throws Exception {
        prepareDbsMock(true);

        when(mHalDeviceManagerMock.isSupported()).thenReturn(false);
        when(mPropertyServiceMock.getString(anyString(), anyString())).thenReturn(TEST_IFACE);

        assertEquals(
                mWifiP2pNative.setupInterface(
                        mDestroyedListenerMock, mHandlerMock, mWorkSourceMock),
                TEST_IFACE);
    }

    /**
     * Verifies that teardownInterface works properly when HalDeviceManager does support,
     * and P2P Iface already exists.
     */
    @Test
    public void testTeardownInterfaceSuccessWhenP2pIfaceExists() throws Exception {
        prepareDbsMock(true);

        mWifiP2pNative.teardownInterface();
    }

    /**
     * Verifies that teardownInterface works properly when HalDeviceManager doesn't support,
     * and there's no P2P Iface.
     */
    @Test
    public void testTeardownInterfaceSuccessWhenNoP2pIface() {
        mWifiP2pNative.teardownInterface();
    }

    /**
     * Verifies that teardownInterface works properly when HalDeviceManager doesn't support.
     */
    @Test
    public void testTeardownInterfaceSuccessWhenNoHalDeviceMgrSupport() throws Exception {
        prepareDbsMock(true);
        when(mHalDeviceManagerMock.isSupported()).thenReturn(false);
        when(mPropertyServiceMock.getString(anyString(), anyString())).thenReturn(TEST_IFACE);

        mWifiP2pNative.teardownInterface();
    }

    /**
     * Verifies that replaceRequestorWs returns correct values when
     * HalDeviceManager doesn't support.
     */
    @Test
    public void testReplaceRequestorWsSuccessWhenNoHalDeviceMgrSupport() {
        when(mHalDeviceManagerMock.isSupported()).thenReturn(false);
        assertTrue(mWifiP2pNative.replaceRequestorWs(mWorkSourceMock));
    }

    /**
     * Verifies that replaceRequestorWs returns correct values when HalDeviceManager doesn't
     * support, and there's no P2P Iface.
     */
    @Test
    public void testReplaceRequestorWsSuccessWhenHalDeviceMgrDoesSupportAndNoP2pIface() {
        assertFalse(mWifiP2pNative.replaceRequestorWs(mWorkSourceMock));
    }

    /**
     * Verifies that replaceRequestorWs returns correct values when HalDeviceManager supports,
     * mIWifiP2pIface is set up successfully, and HalDeviceManager succeeds in replacing.
     */
    @Test
    public void testReplaceRequestorWsSuccessWhenHalDeviceMgrSucceedInReplace() throws Exception {
        prepareDbsMock(true);

        when(mHalDeviceManagerMock.replaceRequestorWsForP2pIface(anyString(),
                any(WorkSource.class))).thenReturn(true);
        assertTrue(mWifiP2pNative.replaceRequestorWs(mWorkSourceMock));
    }

    /**
     * Verifies that replaceRequestorWs returns correct values when HalDeviceManager supports,
     * mIWifiP2pIface is set up successfully, and HalDeviceManager fails in replacing.
     */
    @Test
    public void testReplaceRequestorWsSuccessWhenHalDeviceMgrFailInReplace() throws Exception {
        prepareDbsMock(true);

        when(mHalDeviceManagerMock.replaceRequestorWs(any(WifiHal.WifiInterface.class),
                any(WorkSource.class))).thenReturn(false);
        assertFalse(mWifiP2pNative.replaceRequestorWs(mWorkSourceMock));
    }

    /**
     * Verifies that the device name can be set.
     */
    @Test
    public void testSetDeviceName() {
        when(mSupplicantP2pIfaceHalMock.setWpsDeviceName(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.setDeviceName(TEST_DEVICE_NAME));
        verify(mSupplicantP2pIfaceHalMock).setWpsDeviceName(eq(TEST_DEVICE_NAME));
    }

    /**
     * Verifies that networks could be listed.
     */
    @Test
    public void testP2pListNetworks() {
        WifiP2pGroupList groupList = new WifiP2pGroupList();
        assertTrue(mWifiP2pNative.p2pListNetworks(groupList));

        verify(mSupplicantP2pIfaceHalMock).loadGroups(any(WifiP2pGroupList.class));
        assertEquals(mWifiP2pGroupList.toString(), groupList.toString());
    }

    /**
     * Verifies that WPS PBC starts without errors.
     */
    @Test
    public void testStartWpsPbc() {
        when(mSupplicantP2pIfaceHalMock.startWpsPbc(anyString(), anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.startWpsPbc(TEST_IFACE, TEST_BSSID));
        verify(mSupplicantP2pIfaceHalMock).startWpsPbc(eq(TEST_IFACE), eq(TEST_BSSID));
    }

    /**
     * Verifies that WPS Pin/Keypad starts without errors.
     */
    @Test
    public void testStartWpsPinKeypad() {
        when(mSupplicantP2pIfaceHalMock.startWpsPinKeypad(anyString(), anyString()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.startWpsPinKeypad(TEST_IFACE, TEST_PIN));
        verify(mSupplicantP2pIfaceHalMock).startWpsPinKeypad(eq(TEST_IFACE), eq(TEST_PIN));
    }

    /**
     * Verifies that WPS Pin/Display starts without errors.
     */
    @Test
    public void testStartWpsPinDisplay() {
        when(mSupplicantP2pIfaceHalMock.startWpsPinDisplay(anyString(), anyString()))
                .thenReturn(TEST_PIN);
        assertEquals(TEST_PIN, mWifiP2pNative.startWpsPinDisplay(TEST_IFACE, TEST_BSSID));
        verify(mSupplicantP2pIfaceHalMock).startWpsPinDisplay(eq(TEST_IFACE), eq(TEST_BSSID));
    }

    /**
     * Verifies removing a network.
     */
    @Test
    public void testP2pRemoveNetwork() {
        when(mSupplicantP2pIfaceHalMock.removeNetwork(anyInt())).thenReturn(true);
        assertTrue(mWifiP2pNative.removeP2pNetwork(1));
        verify(mSupplicantP2pIfaceHalMock).removeNetwork(eq(1));
    }

    /**
     * Verifies setting the device type.
     */
    @Test
    public void testSetP2pDeviceType() {
        when(mSupplicantP2pIfaceHalMock.setWpsDeviceType(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.setP2pDeviceType(TEST_DEVICE_TYPE));
        verify(mSupplicantP2pIfaceHalMock).setWpsDeviceType(eq(TEST_DEVICE_TYPE));
    }

    /**
     * Verifies setting WPS config method.
     */
    @Test
    public void testSetConfigMethods() {
        when(mSupplicantP2pIfaceHalMock.setWpsConfigMethods(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.setConfigMethods(TEST_WPS_CONFIG));
        verify(mSupplicantP2pIfaceHalMock).setWpsConfigMethods(eq(TEST_WPS_CONFIG));
    }

    /**
     * Verifies setting SSID postfix.
     */
    @Test
    public void testSetP2pSsidPostfix() {
        when(mSupplicantP2pIfaceHalMock.setSsidPostfix(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.setP2pSsidPostfix(TEST_SSID_POSTFIX));
        verify(mSupplicantP2pIfaceHalMock).setSsidPostfix(eq(TEST_SSID_POSTFIX));
    }

    /**
     * Verifies setting group idle time.
     */
    @Test
    public void testSetP2pGroupIdle() {
        when(mSupplicantP2pIfaceHalMock.setGroupIdle(anyString(), anyInt())).thenReturn(true);
        assertTrue(mWifiP2pNative.setP2pGroupIdle(TEST_IFACE, TEST_IDLE_TIME));
        verify(mSupplicantP2pIfaceHalMock).setGroupIdle(eq(TEST_IFACE), eq(TEST_IDLE_TIME));
    }

    /**
     * Verifies setting power save mode.
     */
    @Test
    public void testSetP2pPowerSave() {
        when(mSupplicantP2pIfaceHalMock.setPowerSave(anyString(), anyBoolean())).thenReturn(true);
        assertTrue(mWifiP2pNative.setP2pPowerSave(TEST_IFACE, true));
        verify(mSupplicantP2pIfaceHalMock).setPowerSave(eq(TEST_IFACE), eq(true));
    }

    /**
     * Verifies enabling Wifi Display.
     */
    @Test
    public void testSetWfdEnable() {
        when(mSupplicantP2pIfaceHalMock.enableWfd(anyBoolean())).thenReturn(true);
        assertTrue(mWifiP2pNative.setWfdEnable(true));
        verify(mSupplicantP2pIfaceHalMock).enableWfd(eq(true));
    }

    /**
     * Verifies setting WFD info.
     */
    @Test
    public void testSetWfdDeviceInfo() {
        when(mSupplicantP2pIfaceHalMock.setWfdDeviceInfo(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.setWfdDeviceInfo(TEST_WFD_DEVICE_INFO));
        verify(mSupplicantP2pIfaceHalMock).setWfdDeviceInfo(eq(TEST_WFD_DEVICE_INFO));
    }

    /**
     * Verifies initiating a P2P service discovery indefinitely.
     */
    @Test
    public void testP2pFindIndefinitely() {
        when(mSupplicantP2pIfaceHalMock.find(anyInt())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pFind());
        verify(mSupplicantP2pIfaceHalMock).find(eq(0));
    }

    /**
     * Verifies initiating a P2P service discovery with timeout.
     */
    @Test
    public void testP2pFindWithTimeout() {
        when(mSupplicantP2pIfaceHalMock.find(anyInt())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pFind(TEST_P2P_FIND_TIMEOUT));
        verify(mSupplicantP2pIfaceHalMock).find(eq(TEST_P2P_FIND_TIMEOUT));
    }

    /**
     * Verifies initiating a P2P service discovery on social channels.
     */
    @Test
    public void testP2pFindOnSocialChannels() {
        when(mSupplicantP2pIfaceHalMock.find(anyInt(), anyInt(), anyInt())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pFind(
                WifiP2pManager.WIFI_P2P_SCAN_SOCIAL,
                WifiP2pManager.WIFI_P2P_SCAN_FREQ_UNSPECIFIED, TEST_P2P_FIND_TIMEOUT));
        verify(mSupplicantP2pIfaceHalMock).find(
                eq(WifiP2pManager.WIFI_P2P_SCAN_SOCIAL),
                eq(WifiP2pManager.WIFI_P2P_SCAN_FREQ_UNSPECIFIED),
                eq(TEST_P2P_FIND_TIMEOUT));
    }

    /**
     * Verifies initiating a P2P service discovery on specific frequency.
     */
    @Test
    public void testP2pFindOnSpecificFrequency() {
        int freq = 2412;
        when(mSupplicantP2pIfaceHalMock.find(anyInt(), anyInt(), anyInt())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pFind(
                WifiP2pManager.WIFI_P2P_SCAN_SINGLE_FREQ, freq, TEST_P2P_FIND_TIMEOUT));
        verify(mSupplicantP2pIfaceHalMock).find(
                eq(WifiP2pManager.WIFI_P2P_SCAN_SINGLE_FREQ),
                eq(freq), eq(TEST_P2P_FIND_TIMEOUT));
    }
    /**
     * Verifies stopping a P2P service discovery.
     */
    @Test
    public void testP2pStopFind() {
        when(mSupplicantP2pIfaceHalMock.stopFind()).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pStopFind());
        verify(mSupplicantP2pIfaceHalMock).stopFind();
    }

    /**
     * Verifies configuring extended listen timing.
     */
    @Test
    public void testP2pExtListen() {
        when(mSupplicantP2pIfaceHalMock.configureExtListen(anyBoolean(), anyInt(), anyInt(), any()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.p2pExtListen(true, 10000, 20000, null));
        verify(mSupplicantP2pIfaceHalMock).configureExtListen(
                eq(true), eq(10000), eq(20000), eq(null));
    }

    /**
     * Verifies setting p2p listen channel.
     */
    @Test
    public void testP2pSetListenChannel() {
        when(mSupplicantP2pIfaceHalMock.setListenChannel(anyInt()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.p2pSetListenChannel(1));
        verify(mSupplicantP2pIfaceHalMock).setListenChannel(eq(1));
    }

    /**
     * Verifies setting p2p operating channel.
     */
    @Test
    public void testP2pSetOperatingChannel() {
        when(mSupplicantP2pIfaceHalMock.setOperatingChannel(anyInt(), any()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.p2pSetOperatingChannel(65, Collections.emptyList()));
        verify(mSupplicantP2pIfaceHalMock).setOperatingChannel(eq(65), any());
    }

    /**
     * Verifies flushing P2P peer table and state.
     */
    @Test
    public void testP2pFlush() {
        when(mSupplicantP2pIfaceHalMock.flush()).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pFlush());
        verify(mSupplicantP2pIfaceHalMock).flush();
    }

    /**
     * Verifies starting p2p group formation.
     */
    @Test
    public void testP2pConnect() {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = TEST_BSSID;
        mWifiP2pNative.p2pConnect(config, true);
        ArgumentCaptor<WifiP2pConfig> configCaptor = ArgumentCaptor.forClass(WifiP2pConfig.class);
        verify(mSupplicantP2pIfaceHalMock).connect(configCaptor.capture(), eq(true));
        // there is no equals operator for WifiP2pConfig.
        assertEquals(config.toString(), configCaptor.getValue().toString());
    }

    /**
     * Verifies cancelling an ongoing P2P group formation and joining-a-group related operation.
     */
    @Test
    public void testP2pCancelConnect() {
        when(mSupplicantP2pIfaceHalMock.cancelConnect()).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pCancelConnect());
        verify(mSupplicantP2pIfaceHalMock).cancelConnect();
    }

    /**
     * Verifies sending P2P provision discovery request to the specified peer.
     */
    @Test
    public void testP2pProvisionDiscovery() {
        when(mSupplicantP2pIfaceHalMock.provisionDiscovery(any(WifiP2pConfig.class)))
                .thenReturn(true);
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = TEST_BSSID;
        assertTrue(mWifiP2pNative.p2pProvisionDiscovery(config));
        ArgumentCaptor<WifiP2pConfig> configCaptor =
                ArgumentCaptor.forClass(WifiP2pConfig.class);
        verify(mSupplicantP2pIfaceHalMock).provisionDiscovery(configCaptor.capture());
        // there is no equals operator for WifiP2pConfig.
        assertEquals(config.toString(), configCaptor.getValue().toString());
    }

    /**
     * Verifies joining p2p group.
     */
    @Test
    public void testJoinGroup() {
        when(mSupplicantP2pIfaceHalMock.groupAdd(anyBoolean(), anyBoolean())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pGroupAdd(true, false));
        verify(mSupplicantP2pIfaceHalMock).groupAdd(eq(true), eq(false));
    }

    /**
     * Verifies joining p2p group with network id.
     */
    @Test
    public void testJoinGroupWithNetworkId() {
        when(mSupplicantP2pIfaceHalMock.groupAdd(anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.p2pGroupAdd(5, false));
        verify(mSupplicantP2pIfaceHalMock).groupAdd(eq(5), eq(true), eq(false));
    }

    /**
     * Verifies joining p2p group with config.
     */
    @Test
    public void testJoinGroupWithConfig() {
        when(Flags.wifiDirectR2()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.groupAdd(
                anyString(), anyString(), anyInt(), anyBoolean(),
                anyInt(), anyString(), anyBoolean())).thenReturn(true);
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(TEST_NETWORK_NAME)
                .setPassphrase(TEST_PASSPHRASE)
                .enablePersistentMode(true)
                .setGroupOperatingFrequency(TEST_GROUP_FREQ)
                .build();
        assertTrue(mWifiP2pNative.p2pGroupAdd(config, true));

        for (String intf: mWifiClientInterfaceNames) {
            verify(mWifiCondManager).abortScan(eq(intf));
        }

        if (!Environment.isSdkAtLeastB()) {
            verify(mSupplicantP2pIfaceHalMock).groupAdd(
                    eq(TEST_NETWORK_NAME),
                    eq(TEST_PASSPHRASE),
                    eq(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_ONLY),
                    eq(true),
                    eq(TEST_GROUP_FREQ),
                    eq(config.deviceAddress),
                    eq(true));
        } else {
            verify(mSupplicantP2pIfaceHalMock).groupAdd(
                    eq(TEST_NETWORK_NAME),
                    eq(TEST_PASSPHRASE),
                    eq(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_OR_R2),
                    eq(true),
                    eq(TEST_GROUP_FREQ),
                    eq(config.deviceAddress),
                    eq(true));
        }
    }

    /**
     * Verifies joining p2p group with Pcc Mode config.
     */
    @Test
    public void testJoinGroupWithPccModeConfig() {
        assumeTrue(Environment.isSdkAtLeastB());
        when(Flags.wifiDirectR2()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.groupAdd(
                anyString(), anyString(), anyInt(), anyBoolean(),
                anyInt(), anyString(), anyBoolean())).thenReturn(true);

        /* Check if we are upgrading LEGACY to R1/R2 compatible mode */
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(TEST_NETWORK_NAME)
                .setPassphrase(TEST_PASSPHRASE)
                .setPccModeConnectionType(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_ONLY)
                .setGroupOperatingFrequency(TEST_GROUP_FREQ)
                .build();
        assertTrue(mWifiP2pNative.p2pGroupAdd(config, true));

        verify(mSupplicantP2pIfaceHalMock).groupAdd(
                eq(TEST_NETWORK_NAME),
                eq(TEST_PASSPHRASE),
                eq(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_OR_R2),
                eq(false),
                eq(TEST_GROUP_FREQ),
                eq(config.deviceAddress),
                eq(true));

        /* Check the 6GHz configuration success case */
        config = new WifiP2pConfig.Builder()
                .setNetworkName(TEST_NETWORK_NAME)
                .setPassphrase(TEST_PASSPHRASE)
                .setPccModeConnectionType(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_R2_ONLY)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_6GHZ)
                .build();
        assertTrue(mWifiP2pNative.p2pGroupAdd(config, true));
        verify(mSupplicantP2pIfaceHalMock).groupAdd(
                eq(TEST_NETWORK_NAME),
                eq(TEST_PASSPHRASE),
                eq(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_R2_ONLY),
                eq(false),
                eq(6),
                eq(config.deviceAddress),
                eq(true));

        /* Check the 6GHz request fails in legacy mode */
        config = new WifiP2pConfig.Builder()
                .setNetworkName(TEST_NETWORK_NAME)
                .setPassphrase(TEST_PASSPHRASE)
                .setPccModeConnectionType(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_ONLY)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_6GHZ)
                .build();
        assertFalse(mWifiP2pNative.p2pGroupAdd(config, true));

        /* Check the 6GHz request fails in R1/R2 compatible mode */
        config = new WifiP2pConfig.Builder()
                .setNetworkName(TEST_NETWORK_NAME)
                .setPassphrase(TEST_PASSPHRASE)
                .setPccModeConnectionType(WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_OR_R2)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_6GHZ)
                .build();
        assertFalse(mWifiP2pNative.p2pGroupAdd(config, true));
    }

    /**
     * Verifies removing p2p group.
     */
    @Test
    public void testP2pGroupRemove() {
        when(mSupplicantP2pIfaceHalMock.groupRemove(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pGroupRemove(TEST_IFACE));
        verify(mSupplicantP2pIfaceHalMock).groupRemove(eq(TEST_IFACE));
    }

    /**
     * Verifies rejecting a connection attemp.
     */
    @Test
    public void testP2pReject() {
        when(mSupplicantP2pIfaceHalMock.reject(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pReject(TEST_BSSID));
        verify(mSupplicantP2pIfaceHalMock).reject(eq(TEST_BSSID));
    }

    /**
     * Verifies inviting a peer to a group.
     */
    @Test
    public void testP2pInvite() {
        when(mSupplicantP2pIfaceHalMock.invite(any(WifiP2pGroup.class), anyString()))
                .thenReturn(true);
        WifiP2pGroup group = new WifiP2pGroup();
        assertTrue(mWifiP2pNative.p2pInvite(group, TEST_BSSID));
        ArgumentCaptor<WifiP2pGroup> groupCaptor = ArgumentCaptor.forClass(WifiP2pGroup.class);
        verify(mSupplicantP2pIfaceHalMock).invite(groupCaptor.capture(), eq(TEST_BSSID));
        // no equals operator for WifiP2pGroup.
        assertEquals(group.toString(), groupCaptor.getValue().toString());
    }

    /**
     * Verifies reinvoking a device from a persistent group.
     */
    @Test
    public void testP2pReinvoke() {
        when(mSupplicantP2pIfaceHalMock.reinvoke(anyInt(), anyString(), anyInt()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.p2pReinvoke(5, TEST_BSSID, -1));
        verify(mSupplicantP2pIfaceHalMock).reinvoke(eq(5), eq(TEST_BSSID), eq(-1));
    }

    /**
     * Verifies getting the operational SSID of the device.
     */
    @Test
    public void testP2pGetSsid() {
        when(mSupplicantP2pIfaceHalMock.getSsid(anyString())).thenReturn(TEST_NETWORK_NAME);
        assertEquals(TEST_NETWORK_NAME, mWifiP2pNative.p2pGetSsid(TEST_BSSID));
        verify(mSupplicantP2pIfaceHalMock).getSsid(eq(TEST_BSSID));
    }

    /**
     * Verifies getting the MAC address of the device.
     */
    @Test
    public void testP2pGetDeviceAddress() {
        when(mSupplicantP2pIfaceHalMock.getDeviceAddress()).thenReturn(TEST_BSSID);
        assertEquals(TEST_BSSID, mWifiP2pNative.p2pGetDeviceAddress());
        verify(mSupplicantP2pIfaceHalMock).getDeviceAddress();
    }

    /**
     * Verifies getting the group capabilities.
     */
    @Test
    public void testGetGroupCapability() {
        when(mSupplicantP2pIfaceHalMock.getGroupCapability(anyString())).thenReturn(0x156);
        assertEquals(0x156, mWifiP2pNative.getGroupCapability(TEST_BSSID));
        verify(mSupplicantP2pIfaceHalMock).getGroupCapability(eq(TEST_BSSID));
    }

    /**
     * Verifies adding a service..
     */
    @Test
    public void testP2pServiceAdd() {
        when(mSupplicantP2pIfaceHalMock.serviceAdd(any(WifiP2pServiceInfo.class)))
                .thenReturn(true);
        WifiP2pServiceInfo info =
                WifiP2pDnsSdServiceInfo.newInstance("MyPrinter", "_ipp._tcp", null);
        assertTrue(mWifiP2pNative.p2pServiceAdd(info));
        verify(mSupplicantP2pIfaceHalMock).serviceAdd(eq(info));
    }

    /**
     * Verifies deleting a service..
     */
    @Test
    public void testP2pServiceDel() {
        when(mSupplicantP2pIfaceHalMock.serviceRemove(any(WifiP2pServiceInfo.class)))
                .thenReturn(true);
        WifiP2pServiceInfo info =
                WifiP2pDnsSdServiceInfo.newInstance("MyPrinter", "_ipp._tcp", null);
        assertTrue(mWifiP2pNative.p2pServiceDel(info));
        verify(mSupplicantP2pIfaceHalMock).serviceRemove(eq(info));
    }

    /**
     * Verifies flushing p2p services in this device.
     */
    @Test
    public void testP2pServiceFlush() {
        when(mSupplicantP2pIfaceHalMock.serviceFlush()).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pServiceFlush());
        verify(mSupplicantP2pIfaceHalMock).serviceFlush();
    }

    /**
     * Verifies scheduling a P2P service discovery request.
     */
    @Test
    public void testP2pServDiscReq() {
        when(mSupplicantP2pIfaceHalMock.requestServiceDiscovery(anyString(), anyString()))
                .thenReturn(TEST_SERVICE_DISCOVERY_IDENTIFIER);
        assertEquals(TEST_SERVICE_DISCOVERY_IDENTIFIER,
                mWifiP2pNative.p2pServDiscReq(TEST_BSSID, TEST_SERVICE_DISCOVERY_QUERY));
        verify(mSupplicantP2pIfaceHalMock)
                .requestServiceDiscovery(eq(TEST_BSSID), eq(TEST_SERVICE_DISCOVERY_QUERY));
    }

    /**
     * Verifies canceling a p2p service discovery request.
     */
    @Test
    public void testP2pServDiscCancelReq() {
        when(mSupplicantP2pIfaceHalMock.cancelServiceDiscovery(anyString())).thenReturn(true);
        assertTrue(mWifiP2pNative.p2pServDiscCancelReq(
                TEST_SERVICE_DISCOVERY_IDENTIFIER));
        verify(mSupplicantP2pIfaceHalMock).cancelServiceDiscovery(
                TEST_SERVICE_DISCOVERY_IDENTIFIER);
    }

    /**
     * Verifies setting miracast mode.
     */
    @Test
    public void testSetMiracastMode() {
        mWifiP2pNative.setMiracastMode(WifiP2pManager.MIRACAST_SOURCE);
        verify(mSupplicantP2pIfaceHalMock).setMiracastMode(eq(WifiP2pManager.MIRACAST_SOURCE));
    }

    /**
     * Verifies getting NFC handover request message.
     */
    @Test
    public void testGetNfcHandoverRequest() {
        when(mSupplicantP2pIfaceHalMock.getNfcHandoverRequest())
                .thenReturn(TEST_NFC_REQUEST_MSG);
        assertEquals(TEST_NFC_REQUEST_MSG, mWifiP2pNative.getNfcHandoverRequest());
        verify(mSupplicantP2pIfaceHalMock).getNfcHandoverRequest();
    }

    /**
     * Verifies getting NFC handover select message.
     */
    @Test
    public void testGetNfcHandoverSelect() {
        when(mSupplicantP2pIfaceHalMock.getNfcHandoverSelect())
                .thenReturn(TEST_NFC_SELECT_MSG);
        assertEquals(TEST_NFC_SELECT_MSG, mWifiP2pNative.getNfcHandoverSelect());
        verify(mSupplicantP2pIfaceHalMock).getNfcHandoverSelect();
    }

    /**
     * Verifies reporting NFC handover select message.
     */
    @Test
    public void testInitiatorReportNfcHandover() {
        when(mSupplicantP2pIfaceHalMock.initiatorReportNfcHandover(anyString()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.initiatorReportNfcHandover(TEST_NFC_SELECT_MSG));
        verify(mSupplicantP2pIfaceHalMock).initiatorReportNfcHandover(eq(TEST_NFC_SELECT_MSG));
    }

    /**
     * Verifies reporting NFC handover request message.
     */
    @Test
    public void testResponderReportNfcHandover() {
        when(mSupplicantP2pIfaceHalMock.responderReportNfcHandover(anyString()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.responderReportNfcHandover(TEST_NFC_REQUEST_MSG));
        verify(mSupplicantP2pIfaceHalMock).responderReportNfcHandover(eq(TEST_NFC_REQUEST_MSG));
    }

    /**
     * Verifies getting client list.
     */
    @Test
    public void testGetP2pClientList() {
        when(mSupplicantP2pIfaceHalMock.getClientList(anyInt()))
                .thenReturn(TEST_CLIENT_LIST);
        assertEquals(TEST_CLIENT_LIST, mWifiP2pNative.getP2pClientList(5));
        verify(mSupplicantP2pIfaceHalMock).getClientList(eq(5));
    }

    /**
     * Verifies setting client list.
     */
    @Test
    public void testSetP2pClientList() {
        when(mSupplicantP2pIfaceHalMock.setClientList(anyInt(), anyString()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.setP2pClientList(5, TEST_CLIENT_LIST));
        verify(mSupplicantP2pIfaceHalMock).setClientList(eq(5), eq(TEST_CLIENT_LIST));
    }

    /**
     * Verifies saving p2p config.
     */
    @Test
    public void testSaveConfig() {
        when(mSupplicantP2pIfaceHalMock.saveConfig())
                .thenReturn(true);
        assertTrue(mWifiP2pNative.saveConfig());
        verify(mSupplicantP2pIfaceHalMock).saveConfig();
    }

    /**
     * Verifies enabling MAC randomization.
     */
    @Test
    public void testSetMacRandomization() {
        when(mSupplicantP2pIfaceHalMock.setMacRandomization(anyBoolean()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.setMacRandomization(true));
        verify(mSupplicantP2pIfaceHalMock).setMacRandomization(eq(true));
    }

    /**
     * Verifies setting Wifi Display R2 device info when SupplicantP2pIfaceHal succeeds in setting.
     */
    @Test
    public void testSetWfdR2DeviceInfoSuccess() {
        when(mSupplicantP2pIfaceHalMock.setWfdR2DeviceInfo(anyString()))
                .thenReturn(true);
        assertTrue(mWifiP2pNative.setWfdR2DeviceInfo(TEST_R2_DEVICE_INFO_HEX));
    }

    /**
     * Verifies setting Wifi Display R2 device info when SupplicantP2pIfaceHal fails in setting.
     */
    @Test
    public void testSetWfdR2DeviceInfoFailure() {
        when(mSupplicantP2pIfaceHalMock.setWfdR2DeviceInfo(anyString()))
                .thenReturn(false);
        assertFalse(mWifiP2pNative.setWfdR2DeviceInfo(TEST_R2_DEVICE_INFO_HEX));
    }

    /**
     * Verifies removing client with specified mac address.
     */
    @Test
    public void testRemoveClient() {
        when(mSupplicantP2pIfaceHalMock.removeClient(anyString(), anyBoolean())).thenReturn(true);
        assertTrue(mWifiP2pNative.removeClient(TEST_BSSID));
        verify(mSupplicantP2pIfaceHalMock).removeClient(eq(TEST_BSSID), anyBoolean());
    }

    void prepareDbsMock(boolean isHalDeviceManagerSupported) throws Exception {
        when(mHalDeviceManagerMock.isSupported()).thenReturn(isHalDeviceManagerSupported);
        when(mHalDeviceManagerMock.createP2pIface(
                any(HalDeviceManager.InterfaceDestroyedListener.class),
                eq(mHandlerMock), eq(mWorkSourceMock))).thenReturn(TEST_IFACE);
        when(mSupplicantP2pIfaceHalMock.isInitializationStarted()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.isInitializationComplete()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.setupIface(any())).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.registerDeathHandler(any())).thenReturn(true);
        mWifiP2pNative.setupInterface(mDestroyedListenerMock, mHandlerMock, mWorkSourceMock);
    }

    /**
     * Verify DBS support inquiry.
     */
    @Test
    public void testDbsSupport() throws Exception {
        prepareDbsMock(true);

        when(mHalDeviceManagerMock.is5g6gDbsSupportedOnP2pIface(any())).thenReturn(true);
        assertTrue(mWifiP2pNative.is5g6gDbsSupported());
        when(mHalDeviceManagerMock.is5g6gDbsSupportedOnP2pIface(any())).thenReturn(false);
        assertFalse(mWifiP2pNative.is5g6gDbsSupported());
    }

    /**
     * Verify DBS support inquiry when HalDeviceManager is not supported.
     */
    @Test
    public void testDbsSupportWhenHalDeviceManagerNotSupported() throws Exception {
        prepareDbsMock(false);

        when(mHalDeviceManagerMock.is5g6gDbsSupportedOnP2pIface(any())).thenReturn(true);
        assertFalse(mWifiP2pNative.is5g6gDbsSupported());
        when(mHalDeviceManagerMock.is5g6gDbsSupportedOnP2pIface(any())).thenReturn(false);
        assertFalse(mWifiP2pNative.is5g6gDbsSupported());
    }

    /**
     * Test the EAPOL IpAddress Allocation configuration Parameters
     */
    @Test
    public void testConfigureEapolIpAddressAllocationParamsSuccess() throws Exception {
        when(mSupplicantP2pIfaceHalMock.configureEapolIpAddressAllocationParams(
                anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);

        assertTrue(mWifiP2pNative.configureEapolIpAddressAllocationParams(0x0101A8C0,
                0x00FFFFFF, 0x0501A8C0, 0x0801A8C0));
        verify(mSupplicantP2pIfaceHalMock).configureEapolIpAddressAllocationParams(eq(0x0101A8C0),
                eq(0x00FFFFFF), eq(0x0501A8C0), eq(0x0801A8C0));
    }

    @Test
    public void testStopP2pSupplicantIfNecessary() throws Exception {
        when(mSupplicantP2pIfaceHalMock.isInitializationStarted()).thenReturn(false);
        mWifiP2pNative.stopP2pSupplicantIfNecessary();
        verify(mSupplicantP2pIfaceHalMock, never()).terminate();

        when(mSupplicantP2pIfaceHalMock.isInitializationStarted()).thenReturn(true);
        mWifiP2pNative.stopP2pSupplicantIfNecessary();
        verify(mSupplicantP2pIfaceHalMock).terminate();
    }

    /**
     * Verifies that the supported features retrieved from wpa_supplicant is cached in the
     * config store
     */
    @Test
    public void testSupportedFeatures() throws Exception {
        when(mSupplicantP2pIfaceHalMock.initialize()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.isInitializationComplete()).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.setupIface(eq(TEST_IFACE))).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.registerDeathHandler(any())).thenReturn(true);
        when(mSupplicantP2pIfaceHalMock.getSupportedFeatures())
                .thenReturn(WifiP2pManager.FEATURE_WIFI_DIRECT_R2
                        | WifiP2pManager.FEATURE_PCC_MODE_ALLOW_LEGACY_AND_R2_CONNECTION);
        assertEquals(TEST_IFACE, mWifiP2pNative.setupInterface(
                mDestroyedListenerMock, mHandlerMock, mWorkSourceMock));
        assertEquals(WifiP2pManager.FEATURE_WIFI_DIRECT_R2
                        | WifiP2pManager.FEATURE_PCC_MODE_ALLOW_LEGACY_AND_R2_CONNECTION
                        | WifiP2pManager.FEATURE_SET_VENDOR_ELEMENTS
                        | WifiP2pManager.FEATURE_FLEXIBLE_DISCOVERY
                        | WifiP2pManager.FEATURE_GROUP_CLIENT_REMOVAL
                        | WifiP2pManager.FEATURE_GROUP_OWNER_IPV6_LINK_LOCAL_ADDRESS_PROVIDED,
                mWifiP2pNative.getSupportedFeatures());
    }

    /**
     * Test the start of an Unsynchronized Service Discovery (USD) based P2P service discovery.
     */
    @Test
    public void testStartUsdBasedServiceDiscovery() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        when(mSupplicantP2pIfaceHalMock.startUsdBasedServiceDiscovery(
                any(), any(), anyInt())).thenReturn(TEST_USD_SESSION_ID);
        WifiP2pUsdBasedServiceConfig usdConfig = new WifiP2pUsdBasedServiceConfig.Builder(
                TEST_USD_SERVICE_NAME)
                .setServiceProtocolType(TEST_USD_PROTOCOL_TYPE)
                .setServiceSpecificInfo(TEST_USD_SERVICE_SPECIFIC_INFO).build();
        WifiP2pUsdBasedServiceDiscoveryConfig serviceDiscoveryConfig =
                new WifiP2pUsdBasedServiceDiscoveryConfig.Builder()
                        .setFrequenciesMhz(TEST_USD_DISCOVERY_CHANNEL_FREQUENCIES_MHZ).build();
        ArgumentCaptor<WifiP2pUsdBasedServiceConfig> usdConfigCaptor = ArgumentCaptor.forClass(
                WifiP2pUsdBasedServiceConfig.class);
        ArgumentCaptor<WifiP2pUsdBasedServiceDiscoveryConfig> discoveryConfigCaptor =
                ArgumentCaptor.forClass(WifiP2pUsdBasedServiceDiscoveryConfig.class);
        assertEquals(TEST_USD_SESSION_ID, mWifiP2pNative.startUsdBasedServiceDiscovery(
                usdConfig, serviceDiscoveryConfig, TEST_USD_TIMEOUT_SEC));
        verify(mSupplicantP2pIfaceHalMock).startUsdBasedServiceDiscovery(
                usdConfigCaptor.capture(),
                discoveryConfigCaptor.capture(), eq(TEST_USD_TIMEOUT_SEC));
        assertEquals(usdConfig, usdConfigCaptor.getValue());
        assertEquals(serviceDiscoveryConfig, discoveryConfigCaptor.getValue());
    }

    /**
     * Test the stop of an Unsynchronized Service Discovery (USD) based P2P service discovery.
     */
    @Test
    public void testStopUsdBasedServiceDiscovery() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        doNothing().when(mSupplicantP2pIfaceHalMock).stopUsdBasedServiceDiscovery(anyInt());
        mWifiP2pNative.stopUsdBasedServiceDiscovery(TEST_USD_SESSION_ID);
        verify(mSupplicantP2pIfaceHalMock).stopUsdBasedServiceDiscovery(eq(TEST_USD_SESSION_ID));
    }

    /**
     * Test the start of an Unsynchronized Service Discovery (USD) based service advertisement.
     */
    @Test
    public void testStartUsdBasedServiceAdvertisement() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        when(mSupplicantP2pIfaceHalMock.startUsdBasedServiceAdvertisement(
                any(), any(), anyInt())).thenReturn(TEST_USD_SESSION_ID);
        WifiP2pUsdBasedServiceConfig usdConfig = new WifiP2pUsdBasedServiceConfig.Builder(
                TEST_USD_SERVICE_NAME)
                .setServiceProtocolType(TEST_USD_PROTOCOL_TYPE)
                .setServiceSpecificInfo(TEST_USD_SERVICE_SPECIFIC_INFO).build();
        WifiP2pUsdBasedLocalServiceAdvertisementConfig serviceAdvertisementConfig =
                new WifiP2pUsdBasedLocalServiceAdvertisementConfig.Builder()
                        .setFrequencyMhz(TEST_USD_DISCOVERY_CHANNEL_FREQUENCY_MHZ).build();
        ArgumentCaptor<WifiP2pUsdBasedServiceConfig> usdConfigCaptor = ArgumentCaptor.forClass(
                WifiP2pUsdBasedServiceConfig.class);
        ArgumentCaptor<WifiP2pUsdBasedLocalServiceAdvertisementConfig>
                serviceAdvertisementConfigCaptor = ArgumentCaptor.forClass(
                WifiP2pUsdBasedLocalServiceAdvertisementConfig.class);
        assertEquals(TEST_USD_SESSION_ID, mWifiP2pNative.startUsdBasedServiceAdvertisement(
                usdConfig, serviceAdvertisementConfig,
                TEST_USD_TIMEOUT_SEC));
        verify(mSupplicantP2pIfaceHalMock).startUsdBasedServiceAdvertisement(
                usdConfigCaptor.capture(),
                serviceAdvertisementConfigCaptor.capture(), eq(TEST_USD_TIMEOUT_SEC));
        assertEquals(usdConfig, usdConfigCaptor.getValue());
        assertEquals(serviceAdvertisementConfig, serviceAdvertisementConfigCaptor.getValue());
    }

    /**
     * Test the stop of an Unsynchronized Service Discovery (USD) based service advertisement.
     */
    @Test
    public void testStopUsdBasedServiceAdvertisement() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        doNothing().when(mSupplicantP2pIfaceHalMock).stopUsdBasedServiceAdvertisement(anyInt());
        mWifiP2pNative.stopUsdBasedServiceAdvertisement(TEST_USD_SESSION_ID);
        verify(mSupplicantP2pIfaceHalMock).stopUsdBasedServiceAdvertisement(
                eq(TEST_USD_SESSION_ID));
    }

    /**
     * Test get Device Identity Resolution (DIR) Information.
     */
    @Test
    public void testGetDirInfo() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        when(mSupplicantP2pIfaceHalMock.getDirInfo()).thenReturn(TEST_DIR_INFO);
        assertEquals(TEST_DIR_INFO, mWifiP2pNative.getDirInfo());
        verify(mSupplicantP2pIfaceHalMock).getDirInfo();
    }

    /**
     * Test validate Device Identity Resolution (DIR) Information.
     */
    @Test
    public void testValidateDirInfo() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        when(mSupplicantP2pIfaceHalMock.validateDirInfo(any())).thenReturn(1);
        assertEquals(1, mWifiP2pNative.validateDirInfo(TEST_DIR_INFO));
        verify(mSupplicantP2pIfaceHalMock).validateDirInfo(eq(TEST_DIR_INFO));
    }

    /**
     * Test authorize a connection request to an existing Group Owner.
     */
    @Test
    public void testAuthorizeConnectRequestOnGroupOwner() throws Exception {
        assumeTrue(Environment.isSdkAtLeastB());
        when(mSupplicantP2pIfaceHalMock.authorizeConnectRequestOnGroupOwner(any(), anyString()))
                .thenReturn(true);
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = TEST_BSSID;
        assertTrue(mWifiP2pNative.authorizeConnectRequestOnGroupOwner(config, TEST_IFACE));
        verify(mSupplicantP2pIfaceHalMock).authorizeConnectRequestOnGroupOwner(
                eq(config), eq(TEST_IFACE));
    }
}
