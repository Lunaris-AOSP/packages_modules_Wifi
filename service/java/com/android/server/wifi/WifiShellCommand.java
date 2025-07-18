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

package com.android.server.wifi;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PAID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_METERED;
import static android.net.wifi.WifiManager.ACTION_REMOVE_SUGGESTION_DISCONNECT;
import static android.net.wifi.WifiManager.ACTION_REMOVE_SUGGESTION_LINGER;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.REQUEST_REGISTERED;
import static android.net.wifi.WifiManager.ROAMING_MODE_AGGRESSIVE;
import static android.net.wifi.WifiManager.ROAMING_MODE_NONE;
import static android.net.wifi.WifiManager.ROAMING_MODE_NORMAL;
import static android.net.wifi.WifiManager.VERBOSE_LOGGING_LEVEL_DISABLED;
import static android.net.wifi.WifiManager.VERBOSE_LOGGING_LEVEL_WIFI_AWARE_ENABLED_ONLY;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;

import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_AP;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_AP_BRIDGE;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_NAN;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_P2P;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_STA;
import static com.android.server.wifi.SelfRecovery.REASON_API_CALL;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.IActionListener;
import android.net.wifi.IDppCallback;
import android.net.wifi.ILastCallerListener;
import android.net.wifi.ILocalOnlyHotspotCallback;
import android.net.wifi.IPnoScanResultsCallback;
import android.net.wifi.IScoreUpdateObserver;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.IWifiConnectedNetworkScorer;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.SoftApState;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConnectedSessionInfo;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSelectionConfig;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.net.wifi.util.ScanResultUtil;
import android.net.wifi.util.WifiResourceCache;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import android.telephony.Annotation;
import android.telephony.PhysicalChannelConfig;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BasicShellCommandHandler;
import com.android.modules.utils.ParceledListSlice;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.ClientMode.LinkProbeCallback;
import com.android.server.wifi.coex.CoexManager;
import com.android.server.wifi.coex.CoexUtils;
import com.android.server.wifi.hal.WifiChip;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.ArrayUtils;

import libcore.util.HexEncoding;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Interprets and executes 'adb shell cmd wifi [args]'.
 *
 * To add new commands:
 * - onCommand: Add a case "<command>" execute. Return a 0
 *   if command executed successfully.
 * - onHelp: add a description string.
 *
 * Permissions: currently root permission is required for some commands. Others will
 * enforce the corresponding API permissions.
 */
public class WifiShellCommand extends BasicShellCommandHandler {
    @VisibleForTesting
    public static String SHELL_PACKAGE_NAME = "com.android.shell";

    // These don't require root access.
    // However, these do perform permission checks in the corresponding WifiService methods.
    private static final String[] NON_PRIVILEGED_COMMANDS = {
            "add-suggestion",
            "forget-network",
            "connect-network",
            "add-network",
            "get-country-code",
            "help",
            "-h",
            "is-verbose-logging",
            "list-scan-results",
            "list-networks",
            "list-suggestions",
            "remove-suggestion",
            "remove-all-suggestions",
            "reset-connected-score",
            "set-connected-score",
            "set-scan-always-available",
            "set-verbose-logging",
            "set-wifi-enabled",
            "set-passpoint-enabled",
            "set-multi-internet-state",
            "start-scan",
            "status",
            "query-interface",
            "interface-priority-interactive-mode",
            "set-one-shot-screen-on-delay-ms",
            "set-network-selection-config",
            "set-ipreach-disconnect",
            "get-ipreach-disconnect",
            "take-bugreport",
            "get-allowed-channel",
            "set-mock-wifimodem-service",
            "get-mock-wifimodem-service",
            "set-mock-wifimodem-methods",
            "force-overlay-config-value",
            "get-softap-supported-features",
            "get-wifi-supported-features",
            "get-overlay-config-values"
    };

    private static final Map<String, Pair<NetworkRequest, ConnectivityManager.NetworkCallback>>
            sActiveRequests = new ConcurrentHashMap<>();

    private final ActiveModeWarden mActiveModeWarden;
    private final WifiGlobals mWifiGlobals;
    private final WifiLockManager mWifiLockManager;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiNative mWifiNative;
    private final CoexManager mCoexManager;
    private final WifiCountryCode mWifiCountryCode;
    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final WifiServiceImpl mWifiService;
    private final WifiContext mContext;
    private final ConnectivityManager mConnectivityManager;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;
    private final WifiNetworkFactory mWifiNetworkFactory;
    private final SelfRecovery mSelfRecovery;
    private final WifiThreadRunner mWifiThreadRunner;
    private final WifiApConfigStore mWifiApConfigStore;
    private int mSapState = WifiManager.WIFI_STATE_UNKNOWN;
    private final ScanRequestProxy mScanRequestProxy;
    private final @NonNull WifiDialogManager mWifiDialogManager;
    private final HalDeviceManager mHalDeviceManager;
    private final InterfaceConflictManager mInterfaceConflictManager;
    private final SsidTranslator mSsidTranslator;
    private final WifiDiagnostics mWifiDiagnostics;
    private final DeviceConfigFacade mDeviceConfig;
    private final AfcManager mAfcManager;
    private final WifiInjector mWifiInjector;
    private static final int[] OP_MODE_LIST = {
            WifiAvailableChannel.OP_MODE_STA,
            WifiAvailableChannel.OP_MODE_SAP,
            WifiAvailableChannel.OP_MODE_WIFI_DIRECT_CLI,
            WifiAvailableChannel.OP_MODE_WIFI_DIRECT_GO,
            WifiAvailableChannel.OP_MODE_WIFI_AWARE,
            WifiAvailableChannel.OP_MODE_TDLS,
    };

    private class SoftApCallbackProxy extends ISoftApCallback.Stub {
        private final PrintWriter mPrintWriter;
        private final CountDownLatch mCountDownLatch;

        SoftApCallbackProxy(PrintWriter printWriter, CountDownLatch countDownLatch) {
            mPrintWriter = printWriter;
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onStateChanged(SoftApState state) {
            mPrintWriter.println("onStateChanged with state: " + state);

            mSapState = state.getState();
            if (mSapState == WifiManager.WIFI_AP_STATE_ENABLED) {
                mPrintWriter.println(" SAP is enabled successfully");
                // Skip countDown() and wait for onInfoChanged() which has
                // the confirmed softAp channel information
            } else if (mSapState == WifiManager.WIFI_AP_STATE_DISABLED) {
                mPrintWriter.println(" SAP is disabled");
            } else if (mSapState == WifiManager.WIFI_AP_STATE_FAILED) {
                mPrintWriter.println(" SAP failed to start");
                mCountDownLatch.countDown();
            }
        }

        @Override
        public void onConnectedClientsOrInfoChanged(Map<String, SoftApInfo> infos,
                Map<String, List<WifiClient>> clients, boolean isBridged,
                boolean isRegistration) {
            mPrintWriter.println("onConnectedClientsOrInfoChanged, infos: " + infos
                    + ", clients: " + clients + ", isBridged: " + isBridged);
            if (mSapState == WifiManager.WIFI_AP_STATE_ENABLED && infos.size() != 0) {
                mCountDownLatch.countDown();
            }
        }

        @Override
        public void onCapabilityChanged(SoftApCapability capability) {
            mPrintWriter.println("onCapabilityChanged " + capability);
        }

        @Override
        public void onBlockedClientConnecting(WifiClient client, int reason) {
        }

        @Override
        public void onClientsDisconnected(SoftApInfo info, List<WifiClient> clients) {
            mPrintWriter.println("onClientsDisconnected, info: " + info + ", clients: " + clients);
        }
    }

    /**
     * Used for shell command testing of DPP feature.
     */
    public static class DppCallbackProxy extends IDppCallback.Stub {
        private final PrintWriter mPrintWriter;
        private final CountDownLatch mCountDownLatch;
        private static final int STATUS_SUCCESS = 0;
        private static final int STATUS_PROGRESS = 1;
        private static final int STATUS_FAILURE = 2;

        DppCallbackProxy(PrintWriter printWriter, CountDownLatch countDownLatch) {
            mPrintWriter = printWriter;
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onSuccessConfigReceived(int networkId) {
            mPrintWriter.println("onSuccessConfigReceived. netId=" + networkId);
            mCountDownLatch.countDown();
        }

        @Override
        public void onSuccess(int status) {
            mPrintWriter.println("onSuccess status=" + statusToString(STATUS_SUCCESS, status));
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(int status, String ssid, String channelList, int[] bandArray) {
            mPrintWriter.println("onFailure. status=" + statusToString(STATUS_FAILURE, status)
                    + "ssid=" + ssid + "channelList=" + channelList);
            mCountDownLatch.countDown();
        }

        @Override
        public void onProgress(int status) {
            mPrintWriter.println("onProgress status=" + statusToString(STATUS_PROGRESS, status));
        }

        @Override
        public void onBootstrapUriGenerated(String uri) {
            mPrintWriter.println("onBootstrapUriGenerated URI = " + uri);
        }

        private String statusToString(int type, int status) {
            switch (type) {
                case STATUS_SUCCESS: {
                    switch (status) {
                        case 0:
                            return "CONFIGURATION_SENT";
                        case 1:
                            return "CONFIGURATION_APPLIED";
                        default:
                            return "Unknown success code";
                    }
                }
                case STATUS_PROGRESS: {
                    switch (status) {
                        case 0:
                            return "AUTHENTICATION_SUCCESS";
                        case 1:
                            return "RESPONSE_PENDING";
                        case 2:
                            return "CONFIGURATION_SENT_WAITING_RESPONSE";
                        case 3:
                            return "CONFIGURATION_ACCEPTED";
                        default:
                            return "Unknown progress code";
                    }
                }
                case STATUS_FAILURE: {
                    switch (status) {
                        case -1:
                            return "INVALID_URI";
                        case -2:
                            return "AUTHENTICATION";
                        case -3:
                            return "NOT_COMPATIBLE";
                        case -4:
                            return "CONFIGURATION";
                        case -5:
                            return "BUSY";
                        case -6:
                            return "TIMEOUT";
                        case -7:
                            return "GENERIC";
                        case -8:
                            return "NOT_SUPPORTED";
                        case -9:
                            return "INVALID_NETWORK";
                        case -10:
                            return "CANNOT_FIND_NETWORK";
                        case -11:
                            return "ENROLLEE_AUTHENTICATION";
                        case -12:
                            return "ENROLLEE_REJECTED_CONFIGURATION";
                        case -13:
                            return "URI_GENERATION";
                        case -14:
                            return "ENROLLEE_FAILED_TO_SCAN_NETWORK_CHANNEL";
                        default:
                            return "Unknown failure code";
                    }
                }
                default :
                    return "Unknown status type";
            }
        }
    }

    /**
     * Used for shell command testing of scorer.
     */
    public static class WifiScorer extends IWifiConnectedNetworkScorer.Stub {
        private final WifiServiceImpl mWifiService;
        private final CountDownLatch mCountDownLatch;
        private Integer mSessionId;
        private IScoreUpdateObserver mScoreUpdateObserver;

        public WifiScorer(WifiServiceImpl wifiService, CountDownLatch countDownLatch) {
            mWifiService = wifiService;
            mCountDownLatch  = countDownLatch;
        }

        @Override
        public void onStart(WifiConnectedSessionInfo sessionInfo) {
            mSessionId = sessionInfo.getSessionId();
            mCountDownLatch.countDown();
        }
        @Override
        public void onStop(int sessionId) {
            // clear the external scorer on disconnect.
            mWifiService.clearWifiConnectedNetworkScorer();
        }
        @Override
        public void onSetScoreUpdateObserver(IScoreUpdateObserver observerImpl) {
            mScoreUpdateObserver = observerImpl;
            mCountDownLatch.countDown();
        }
        @Override
        public void onNetworkSwitchAccepted(
                int sessionId, int targetNetworkId, String targetBssid) {
            Log.i(TAG, "onNetworkSwitchAccepted:"
                    + " sessionId=" + sessionId
                    + " targetNetworkId=" + targetNetworkId
                    + " targetBssid=" + targetBssid);
        }

        @Override
        public void onNetworkSwitchRejected(
                int sessionId, int targetNetworkId, String targetBssid) {
            Log.i(TAG, "onNetworkSwitchRejected:"
                    + " sessionId=" + sessionId
                    + " targetNetworkId=" + targetNetworkId
                    + " targetBssid=" + targetBssid);
        }

        public Integer getSessionId() {
            return mSessionId;
        }

        public IScoreUpdateObserver getScoreUpdateObserver() {
            return mScoreUpdateObserver;
        }
    }

    WifiShellCommand(WifiInjector wifiInjector, WifiServiceImpl wifiService, WifiContext context,
            WifiGlobals wifiGlobals, WifiThreadRunner wifiThreadRunner) {
        mWifiInjector = wifiInjector;
        mWifiGlobals = wifiGlobals;
        mWifiThreadRunner = wifiThreadRunner;
        mActiveModeWarden = wifiInjector.getActiveModeWarden();
        mWifiLockManager = wifiInjector.getWifiLockManager();
        mWifiNetworkSuggestionsManager = wifiInjector.getWifiNetworkSuggestionsManager();
        mWifiConfigManager = wifiInjector.getWifiConfigManager();
        mWifiNative = wifiInjector.getWifiNative();
        mCoexManager = wifiInjector.getCoexManager();
        mWifiCountryCode = wifiInjector.getWifiCountryCode();
        mWifiLastResortWatchdog = wifiInjector.getWifiLastResortWatchdog();
        mWifiService = wifiService;
        mContext = context;
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mWifiCarrierInfoManager = wifiInjector.getWifiCarrierInfoManager();
        mWifiNetworkFactory = wifiInjector.getWifiNetworkFactory();
        mSelfRecovery = wifiInjector.getSelfRecovery();
        mWifiApConfigStore = wifiInjector.getWifiApConfigStore();
        mScanRequestProxy = wifiInjector.getScanRequestProxy();
        mWifiDialogManager = wifiInjector.getWifiDialogManager();
        mHalDeviceManager = wifiInjector.getHalDeviceManager();
        mInterfaceConflictManager = wifiInjector.getInterfaceConflictManager();
        mSsidTranslator = wifiInjector.getSsidTranslator();
        mWifiDiagnostics = wifiInjector.getWifiDiagnostics();
        mDeviceConfig = wifiInjector.getDeviceConfigFacade();
        mAfcManager = wifiInjector.getAfcManager();
    }

    private String getOpModeName(@WifiAvailableChannel.OpMode int mode) {
        switch (mode) {
            case WifiAvailableChannel.OP_MODE_STA:
                return "STA";
            case WifiAvailableChannel.OP_MODE_SAP:
                return "SAP";
            case WifiAvailableChannel.OP_MODE_WIFI_DIRECT_CLI:
                return "WiFi-Direct GC";
            case WifiAvailableChannel.OP_MODE_WIFI_DIRECT_GO:
                return "WiFi-Direct GO";
            case WifiAvailableChannel.OP_MODE_WIFI_AWARE:
                return "WiFi-Aware";
            case WifiAvailableChannel.OP_MODE_TDLS:
                return "TDLS";
            default:
                return "";
        }
    }

    @Override
    public int onCommand(String cmd) {
        // Treat no command as help command.
        if (TextUtils.isEmpty(cmd)) {
            cmd = "help";
        }
        // Explicit exclusion from root permission
        if (ArrayUtils.indexOf(NON_PRIVILEGED_COMMANDS, cmd) == -1) {
            final int uid = Binder.getCallingUid();
            if (uid != Process.ROOT_UID) {
                throw new SecurityException(
                        "Uid " + uid + " does not have access to " + cmd + " wifi command "
                                + "(or such command doesn't exist)");
            }
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "set-ipreach-disconnect": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mWifiGlobals.setIpReachabilityDisconnectEnabled(enabled);
                    return 0;
                }
                case "get-ipreach-disconnect":
                    pw.println("IPREACH_DISCONNECT state is "
                            + mWifiGlobals.getIpReachabilityDisconnectEnabled());
                    return 0;
                case "set-poll-rssi-interval-msecs":
                    List<Integer> newPollIntervals = new ArrayList<>();
                    while (getRemainingArgsCount() > 0) {
                        int newPollIntervalMsecs;
                        try {
                            newPollIntervalMsecs = Integer.parseInt(getNextArgRequired());
                        } catch (NumberFormatException e) {
                            pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                    + "- must be a positive integer");
                            return -1;
                        }

                        if (newPollIntervalMsecs < 1) {
                            pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                    + "- must be a positive integer");
                            return -1;
                        }

                        newPollIntervals.add(newPollIntervalMsecs);
                    }

                    switch (newPollIntervals.size()) {
                        case 0:
                            throw new IllegalArgumentException(
                                "Need at least one valid rssi polling interval");
                        case 1:
                            mActiveModeWarden.getPrimaryClientModeManager()
                                    .setLinkLayerStatsPollingInterval(newPollIntervals.get(0));
                            break;
                        case 2:
                            int newShortIntervalMsecs = newPollIntervals.get(0);
                            int newLongIntervalMsecs = newPollIntervals.get(1);
                            if (newShortIntervalMsecs >= newLongIntervalMsecs) {
                                pw.println(
                                        "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                                + "- the long polling interval must be greater "
                                                + "than the short polling interval");
                                return -1;
                            }
                            mWifiGlobals.setPollRssiShortIntervalMillis(newShortIntervalMsecs);
                            mWifiGlobals.setPollRssiLongIntervalMillis(newLongIntervalMsecs);
                            mWifiGlobals.setPollRssiIntervalMillis(newShortIntervalMsecs);
                            mActiveModeWarden.getPrimaryClientModeManager()
                                    .setLinkLayerStatsPollingInterval(0);
                            break;
                        default:
                            pw.println("Too many arguments, need at most two valid rssi polling "
                                    + "intervals");
                            return -1;
                    }
                    return 0;
                case "get-poll-rssi-interval-msecs":
                    pw.println("Current interval between RSSI polls (milliseconds) = "
                            + mWifiGlobals.getPollRssiIntervalMillis());
                    if (mWifiGlobals.isAdjustPollRssiIntervalEnabled()
                            && mDeviceConfig.isAdjustPollRssiIntervalEnabled()
                            && !mWifiGlobals.isPollRssiIntervalOverridden()) {
                        pw.println("Auto adjustment of poll rssi is enabled");
                        pw.println("Regular (short) interval between RSSI polls (milliseconds) = "
                                + mWifiGlobals.getPollRssiShortIntervalMillis());
                        pw.println("Long interval between RSSI polls (milliseconds) = "
                                + mWifiGlobals.getPollRssiLongIntervalMillis());
                    }
                    return 0;
                case "force-hi-perf-mode": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    if (!mWifiLockManager.forceHiPerfMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "force-low-latency-mode": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    if (!mWifiLockManager.forceLowLatencyMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "network-suggestions-set-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean approved = getNextArgRequiredTrueOrFalse("yes", "no");
                    mWifiThreadRunner.post(() -> mWifiNetworkSuggestionsManager
                            .setHasUserApprovedForApp(approved,
                                    Binder.getCallingUid(), packageName),
                            "shell#setHasUserApprovedForApp");
                    return 0;
                }
                case "network-suggestions-has-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean hasUserApproved =
                            mWifiNetworkSuggestionsManager.hasUserApprovedForApp(packageName);
                    pw.println(hasUserApproved ? "yes" : "no");
                    return 0;
                }
                case "imsi-protection-exemption-set-user-approved-for-carrier": {
                    String arg1 = getNextArgRequired();
                    int carrierId = -1;
                    try {
                        carrierId = Integer.parseInt(arg1);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid argument to "
                                + "'imsi-protection-exemption-set-user-approved-for-carrier' "
                                + "- carrierId must be an Integer");
                        return -1;
                    }
                    boolean approved = getNextArgRequiredTrueOrFalse("yes", "no");
                    mWifiCarrierInfoManager
                            .setHasUserApprovedImsiPrivacyExemptionForCarrier(approved, carrierId);
                    return 0;
                }
                case "imsi-protection-exemption-has-user-approved-for-carrier": {
                    String arg1 = getNextArgRequired();
                    int carrierId = -1;
                    try {
                        carrierId = Integer.parseInt(arg1);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid argument to "
                                + "'imsi-protection-exemption-has-user-approved-for-carrier' "
                                + "- 'carrierId' must be an Integer");
                        return -1;
                    }
                    boolean hasUserApproved = mWifiCarrierInfoManager
                            .hasUserApprovedImsiPrivacyExemptionForCarrier(carrierId);
                    pw.println(hasUserApproved ? "yes" : "no");
                    return 0;
                }
                case "imsi-protection-exemption-clear-user-approved-for-carrier": {
                    String arg1 = getNextArgRequired();
                    try {
                        final int carrierId = Integer.parseInt(arg1);
                        mWifiThreadRunner.post(() ->
                                mWifiCarrierInfoManager.clearImsiPrivacyExemptionForCarrier(
                                        carrierId), TAG + "#" + cmd);
                    } catch (NumberFormatException e) {
                        pw.println("Invalid argument to "
                                + "'imsi-protection-exemption-clear-user-approved-for-carrier' "
                                + "- 'carrierId' must be an Integer");
                        return -1;
                    }
                    return 0;
                }
                case "network-requests-remove-user-approved-access-points": {
                    String packageName = getNextArgRequired();
                    mWifiThreadRunner.post(() -> mWifiNetworkFactory.removeApp(packageName),
                            TAG + "#" + cmd);
                    return 0;
                }
                case "clear-user-disabled-networks": {
                    mWifiConfigManager.clearUserTemporarilyDisabledList();
                    return 0;
                }
                case "send-link-probe": {
                    return sendLinkProbe(pw);
                }
                case "get-last-caller-info": {
                    int apiType = Integer.parseInt(getNextArgRequired());
                    mWifiService.getLastCallerInfoForApi(apiType,
                            new ILastCallerListener.Stub() {
                                @Override
                                public void onResult(String packageName, boolean enabled) {
                                    Log.i(TAG, "getLastCallerInfoForApi " + apiType
                                            + ": packageName=" + packageName
                                            + ", enabled=" + enabled);
                                }
                            });
                    return 0;
                }
                case "force-softap-band": {
                    boolean forceBandEnabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    if (forceBandEnabled) {
                        String forcedBand = getNextArgRequired();
                        if (forcedBand.equals("2")) {
                            mWifiApConfigStore.enableForceSoftApBandOrChannel(
                                    SoftApConfiguration.BAND_2GHZ, 0,
                                    SoftApInfo.CHANNEL_WIDTH_AUTO);
                        } else if (forcedBand.equals("5")) {
                            mWifiApConfigStore.enableForceSoftApBandOrChannel(
                                    SoftApConfiguration.BAND_5GHZ, 0,
                                    SoftApInfo.CHANNEL_WIDTH_AUTO);
                        } else if (forcedBand.equals("6")) {
                            mWifiApConfigStore.enableForceSoftApBandOrChannel(
                                    SoftApConfiguration.BAND_6GHZ, 0,
                                    SoftApInfo.CHANNEL_WIDTH_AUTO);
                        } else {
                            pw.println("Invalid argument to 'force-softap-band enabled' "
                                    + "- must be a valid band integer (2|5|6)");
                            return -1;
                        }
                        return 0;
                    } else {
                        mWifiApConfigStore.disableForceSoftApBandOrChannel();
                        return 0;
                    }

                }
                case "force-softap-channel": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    if (enabled) {
                        int apChannelMHz;
                        int apMaxBandWidthMHz = 0;
                        try {
                            apChannelMHz = Integer.parseInt(getNextArgRequired());
                            String option = getNextOption();
                            if (option != null && option.equals("-w")) {
                                if (!SdkLevel.isAtLeastT()) {
                                    pw.println("Maximum channel bandwidth can be set only on"
                                            + " SdkLevel T or later.");
                                    return -1;
                                }
                                String bandwidthStr = getNextArgRequired();
                                try {
                                    apMaxBandWidthMHz = Integer.parseInt(bandwidthStr);
                                } catch (NumberFormatException e) {
                                    pw.println("Invalid maximum channel bandwidth arg: "
                                            + bandwidthStr);
                                    return -1;
                                }
                            }
                        } catch (NumberFormatException e) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- must be a positive integer");
                            return -1;
                        }
                        int apChannel = ScanResult.convertFrequencyMhzToChannelIfSupported(
                                apChannelMHz);
                        int band = ApConfigUtil.convertFrequencyToBand(apChannelMHz);
                        int apMaxBandWidth;
                        switch (apMaxBandWidthMHz) {
                            case 0:
                                apMaxBandWidth = SoftApInfo.CHANNEL_WIDTH_AUTO;
                                break;
                            case 20:
                                apMaxBandWidth = SoftApInfo.CHANNEL_WIDTH_20MHZ;
                                break;
                            case 40:
                                apMaxBandWidth = SoftApInfo.CHANNEL_WIDTH_40MHZ;
                                break;
                            case 80:
                                apMaxBandWidth = SoftApInfo.CHANNEL_WIDTH_80MHZ;
                                break;
                            case 160:
                                apMaxBandWidth = SoftApInfo.CHANNEL_WIDTH_160MHZ;
                                break;
                            case 320:
                                apMaxBandWidth = SoftApInfo.CHANNEL_WIDTH_320MHZ;
                                break;
                            default:
                                pw.println("Invalid max channel bandwidth " + apMaxBandWidthMHz);
                                return -1;
                        }
                        pw.println("channel: " + apChannel + " band: " + band
                                + " maximum channel bandwidth: " + apMaxBandWidthMHz);
                        if (apChannel == -1 || band == -1) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- must be a valid WLAN channel");
                            return -1;
                        }
                        boolean isTemporarilyEnablingWifiNeeded = mWifiService.getWifiEnabledState()
                                != WIFI_STATE_ENABLED;
                        if (isTemporarilyEnablingWifiNeeded) {
                            waitForWifiEnabled(true);
                        }
                        // Following calls will fail if wifi is not enabled
                        boolean isValidChannel = isApChannelMHzValid(pw, apChannelMHz);
                        if (isTemporarilyEnablingWifiNeeded) {
                            waitForWifiEnabled(false);
                        }
                        if (!isValidChannel
                                || (band == SoftApConfiguration.BAND_5GHZ
                                && !mWifiService.is5GHzBandSupported())
                                || (band == SoftApConfiguration.BAND_6GHZ
                                && !mWifiService.is6GHzBandSupported())
                                || (band == SoftApConfiguration.BAND_60GHZ
                                && !mWifiService.is60GHzBandSupported())) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- must be a valid WLAN channel"
                                    + " in a band supported by the device");
                            return -1;
                        }
                        mWifiApConfigStore.enableForceSoftApBandOrChannel(band, apChannel,
                                apMaxBandWidth);
                        return 0;
                    } else {
                        mWifiApConfigStore.disableForceSoftApBandOrChannel();
                        return 0;
                    }
                }
                case "set-pno-request": {
                    if (!SdkLevel.isAtLeastT()) {
                        pw.println("This feature is only supported on SdkLevel T or later.");
                        return -1;
                    }
                    String ssid = getNextArgRequired();
                    int frequency = -1;
                    WifiSsid wifiSsid = WifiSsid.fromString("\"" + ssid + "\"");
                    String option = getNextOption();
                    if (option != null) {
                        if (option.equals("-f")) {
                            frequency = Integer.parseInt(getNextArgRequired());
                        } else {
                            pw.println("Invalid argument to 'set-pno-request' "
                                    + "- only allowed option is '-f'");
                            return -1;
                        }
                    }
                    int[] frequencies = frequency == -1 ? new int[0] : new int[] {frequency};
                    IPnoScanResultsCallback.Stub callback = new IPnoScanResultsCallback.Stub() {
                        @Override
                        public void onScanResultsAvailable(List<ScanResult> scanResults) {
                            Log.v(TAG, "PNO scan results available:");
                            for (ScanResult result : scanResults) {
                                Log.v(TAG, result.getWifiSsid().toString());
                            }
                        }
                        @Override
                        public void onRegisterSuccess() {
                            Log.v(TAG, "PNO scan request register success");
                        }

                        @Override
                        public void onRegisterFailed(int reason) {
                            Log.v(TAG, "PNO scan request register failed reason=" + reason);
                        }

                        @Override
                        public void onRemoved(int reason) {
                            Log.v(TAG, "PNO scan request callback removed reason=" + reason);
                        }
                    };
                    pw.println("requesting PNO scan for: " + wifiSsid);
                    mWifiService.setExternalPnoScanRequest(new Binder(), callback,
                            Arrays.asList(wifiSsid), frequencies, mContext.getOpPackageName(),
                            mContext.getAttributionTag());
                    return 0;
                }
                case "clear-pno-request": {
                    if (!SdkLevel.isAtLeastT()) {
                        pw.println("This feature is only supported on SdkLevel T or later.");
                        return -1;
                    }
                    mWifiService.clearExternalPnoScanRequest();
                    return 0;
                }
                case "set-pno-scan": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mWifiService.setPnoScanEnabled(enabled, true /*enablePnoScanAfterWifiToggle*/,
                            mContext.getOpPackageName());
                    return 0;
                }
                case "start-lohs": {
                    CountDownLatch countDownLatch = new CountDownLatch(2);
                    SoftApConfiguration config = buildSoftApConfiguration(pw);
                    ILocalOnlyHotspotCallback.Stub lohsCallback =
                            new ILocalOnlyHotspotCallback.Stub() {
                        @Override
                        public void onHotspotStarted(SoftApConfiguration config) {
                            pw.println("Lohs onStarted, config = " + config);
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onHotspotStopped() {
                            pw.println("Lohs onStopped");
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onHotspotFailed(int reason) {
                            pw.println("Lohs onFailed: " + reason);
                            countDownLatch.countDown();
                        }
                    };
                    SoftApCallbackProxy softApCallback =
                            new SoftApCallbackProxy(pw, countDownLatch);
                    Bundle extras = new Bundle();
                    if (SdkLevel.isAtLeastS()) {
                        extras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE,
                                mContext.getAttributionSource());
                    }
                    mWifiService.registerLocalOnlyHotspotSoftApCallback(softApCallback, extras);
                    if (REQUEST_REGISTERED != mWifiService.startLocalOnlyHotspot(
                              lohsCallback, SHELL_PACKAGE_NAME, null /* featureId */,
                              config, extras, false)) {
                        pw.println("Lohs failed to start. Please check config parameters");
                    }
                    // Wait for lohs to start and complete callback
                    countDownLatch.await(10000, TimeUnit.MILLISECONDS);
                    mWifiService.unregisterLocalOnlyHotspotSoftApCallback(softApCallback, extras);
                    return 0;
                }
                case "start-softap": {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    SoftApConfiguration config = buildSoftApConfiguration(pw);
                    SoftApCallbackProxy softApCallback =
                            new SoftApCallbackProxy(pw, countDownLatch);
                    mWifiService.registerSoftApCallback(softApCallback);
                    if (!mWifiService.startTetheredHotspot(config, SHELL_PACKAGE_NAME)) {
                        pw.println("Soft AP failed to start. Please check config parameters");
                    }
                    // Wait for softap to start and complete callback
                    countDownLatch.await(10000, TimeUnit.MILLISECONDS);
                    mWifiService.unregisterSoftApCallback(softApCallback);
                    return 0;
                }
                case "stop-lohs": {
                    mWifiService.stopLocalOnlyHotspot();
                    pw.println("Lohs stopped successfully");
                    return 0;
                }
                case "stop-softap": {
                    if (mWifiService.stopSoftAp()) {
                        pw.println("Soft AP stopped successfully");
                    } else {
                        pw.println("Soft AP failed to stop");
                    }
                    return 0;
                }
                case "reload-resources": {
                    mContext.resetResourceCache();
                    return 0;
                }
                case "force-country-code": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    if (enabled) {
                        String countryCode = getNextArgRequired();
                        if (!WifiCountryCode.isValid(countryCode)) {
                            pw.println("Invalid argument: Country code must be a 2-Character"
                                    + " alphanumeric code. But got countryCode " + countryCode
                                    + " instead");
                            return -1;
                        }
                        mWifiCountryCode.setOverrideCountryCode(countryCode);
                        return 0;
                    } else {
                        mWifiCountryCode.clearOverrideCountryCode();
                        return 0;
                    }
                }
                case "get-country-code": {
                    pw.println("Wifi Country Code = "
                            + mWifiCountryCode.getCountryCode());
                    return 0;
                }
                case "set-wifi-watchdog": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mWifiLastResortWatchdog.setWifiWatchdogFeature(enabled);
                    return 0;
                }
                case "get-wifi-watchdog": {
                    pw.println("wifi watchdog state is "
                            + mWifiLastResortWatchdog.getWifiWatchdogFeature());
                    return 0;
                }
                case "set-wifi-enabled": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mWifiService.setWifiEnabled(SHELL_PACKAGE_NAME, enabled);
                    return 0;
                }
                case "set-passpoint-enabled": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mWifiService.setWifiPasspointEnabled(enabled);
                    return 0;
                }
                case "set-multi-internet-mode": {
                    int mode = Integer.parseInt(getNextArgRequired());
                    mWifiService.setStaConcurrencyForMultiInternetMode(mode);
                    return 0;
                }
                case "set-scan-always-available": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mWifiService.setScanAlwaysAvailable(enabled, SHELL_PACKAGE_NAME);
                    return 0;
                }
                case "get-softap-supported-features":
                    // This command is used for vts to check softap supported features.
                    if (ApConfigUtil.isAcsSupported(mContext)) {
                        pw.println("wifi_softap_acs_supported");
                    }
                    if (ApConfigUtil.isWpa3SaeSupported(mContext)) {
                        pw.println("wifi_softap_wpa3_sae_supported");
                    }
                    if (mWifiService.isFeatureSupported(WifiManager.WIFI_FEATURE_BRIDGED_AP)) {
                        pw.println("wifi_softap_bridged_ap_supported");
                    }
                    if (mWifiService.isFeatureSupported(WifiManager.WIFI_FEATURE_STA_BRIDGED_AP)) {
                        pw.println("wifi_softap_bridged_ap_with_sta_supported");
                    }
                    if (mWifiNative.isMLDApSupportMLO()) {
                        pw.println("wifi_softap_mlo_supported");
                    }
                    return 0;
                case "get-wifi-supported-features": {
                    pw.println(mWifiService.getSupportedFeaturesString());
                    return 0;
                }
                case "settings-reset":
                    mWifiNative.stopFakingScanDetails();
                    mWifiNative.resetFakeScanDetails();
                    mWifiService.factoryReset(SHELL_PACKAGE_NAME);
                    return 0;
                case "list-scan-results":
                    List<ScanResult> scanResults =
                            mScanRequestProxy.getScanResults();
                    if (scanResults.isEmpty()) {
                        pw.println("No scan results");
                    } else {
                        ScanResultUtil.dumpScanResults(pw, scanResults,
                                SystemClock.elapsedRealtime());
                    }
                    return 0;
                case "start-scan":
                    mWifiService.startScan(SHELL_PACKAGE_NAME, null);
                    return 0;
                case "list-networks":
                    ParceledListSlice<WifiConfiguration> networks =
                            mWifiService.getConfiguredNetworks(SHELL_PACKAGE_NAME, null, false);
                    if (networks == null || networks.getList().isEmpty()) {
                        pw.println("No networks");
                    } else {
                        pw.println("Network Id      SSID                         Security type");
                        for (WifiConfiguration network : networks.getList()) {
                            String securityType = network.getSecurityParamsList().stream()
                                    .map(p -> WifiConfiguration.getSecurityTypeName(
                                                    p.getSecurityType())
                                            + (p.isAddedByAutoUpgrade() ? "^" : ""))
                                    .collect(Collectors.joining("/"));
                            pw.println(String.format("%-12d %-32s %-4s",
                                    network.networkId, WifiInfo.sanitizeSsid(network.SSID),
                                    securityType));
                        }
                    }
                    return 0;
                case "connect-network": {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    IActionListener.Stub actionListener = new IActionListener.Stub() {
                        @Override
                        public void onSuccess() throws RemoteException {
                            pw.println("Connection initiated ");
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onFailure(int i) throws RemoteException {
                            pw.println("Connection failed");
                            countDownLatch.countDown();
                        }
                    };
                    WifiConfiguration config = buildWifiConfiguration(pw);
                    mWifiService.connect(config, -1, actionListener, SHELL_PACKAGE_NAME,
                            new Bundle());
                    return 0;
                }
                case "add-network": {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    IActionListener.Stub actionListener = new IActionListener.Stub() {
                        @Override
                        public void onSuccess() throws RemoteException {
                            pw.println("Save successful");
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onFailure(int i) throws RemoteException {
                            pw.println("Save failed");
                            countDownLatch.countDown();
                        }
                    };
                    WifiConfiguration config = buildWifiConfiguration(pw);
                    mWifiService.save(config, actionListener, SHELL_PACKAGE_NAME);
                    return 0;
                }
                case "forget-network": {
                    String networkId = getNextArgRequired();
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    IActionListener.Stub actionListener = new IActionListener.Stub() {
                        @Override
                        public void onSuccess() throws RemoteException {
                            pw.println("Forget successful");
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onFailure(int i) throws RemoteException {
                            pw.println("Forget failed");
                            countDownLatch.countDown();
                        }
                    };
                    mWifiService.forget(Integer.parseInt(networkId), actionListener);
                    // wait for status.
                    countDownLatch.await(500, TimeUnit.MILLISECONDS);
                    return 0;
                }
                case "pmksa-flush": {
                    String networkId = getNextArgRequired();
                    int netId = Integer.parseInt(networkId);
                    WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(netId);
                    if (config == null) {
                        pw.println("No Wifi config corresponding to networkId: " + netId);
                        return -1;
                    }
                    mWifiNative.removeNetworkCachedData(netId);
                    return 0;
                }
                case "status":
                    printStatus(pw);
                    return 0;
                case "set-verbose-logging": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    String levelOption = getNextOption();
                    int level = enabled ? 1 : 0;
                    if (enabled && levelOption != null && levelOption.equals("-l")) {
                        String levelStr = getNextArgRequired();
                        try {
                            level = Integer.parseInt(levelStr);
                            if (level < VERBOSE_LOGGING_LEVEL_DISABLED
                                    || level > VERBOSE_LOGGING_LEVEL_WIFI_AWARE_ENABLED_ONLY) {
                                pw.println("Not a valid log level: " + level);
                                return -1;
                            }
                        } catch (NumberFormatException e) {
                            pw.println("Invalid verbose-logging level : " + levelStr);
                            return -1;
                        }
                    }
                    mWifiService.enableVerboseLogging(level);
                    return 0;
                }
                case "is-verbose-logging": {
                    int enabled = mWifiService.getVerboseLoggingLevel();
                    pw.println(enabled > 0 ? "enabled" : "disabled");
                    return 0;
                }
                case "start-restricting-auto-join-to-subscription-id": {
                    if (!SdkLevel.isAtLeastS()) {
                        pw.println("This feature is only supported on SdkLevel S or later.");
                        return -1;
                    }
                    int subId = Integer.parseInt(getNextArgRequired());
                    mWifiService.startRestrictingAutoJoinToSubscriptionId(subId);
                    return 0;
                }
                case "stop-restricting-auto-join-to-subscription-id": {
                    if (!SdkLevel.isAtLeastS()) {
                        pw.println("This feature is only supported on SdkLevel S or later.");
                        return -1;
                    }
                    mWifiService.stopRestrictingAutoJoinToSubscriptionId();
                    return 0;
                }
                case "add-suggestion": {
                    WifiNetworkSuggestion suggestion = buildSuggestion(pw);
                    if (suggestion  == null) {
                        pw.println("Invalid network suggestion parameter");
                        return -1;
                    }
                    int errorCode = mWifiService.addNetworkSuggestions(
                            new ParceledListSlice(List.of(suggestion)), SHELL_PACKAGE_NAME, null);
                    if (errorCode != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                        pw.println("Add network suggestion failed with error code: " + errorCode);
                        return -1;
                    }
                    // untrusted/oem-paid networks need a corresponding NetworkRequest.
                    if (suggestion.isUntrusted()
                            || (SdkLevel.isAtLeastS()
                            && (suggestion.isOemPaid() || suggestion.isOemPrivate()))) {
                        NetworkRequest.Builder networkRequestBuilder =
                                new NetworkRequest.Builder()
                                        .addTransportType(TRANSPORT_WIFI);
                        if (suggestion.isUntrusted()) {
                            networkRequestBuilder.removeCapability(NET_CAPABILITY_TRUSTED);
                        }
                        if (SdkLevel.isAtLeastS()) {
                            if (suggestion.isOemPaid()) {
                                networkRequestBuilder.addCapability(NET_CAPABILITY_OEM_PAID);
                            }
                            if (suggestion.isOemPrivate()) {
                                networkRequestBuilder.addCapability(NET_CAPABILITY_OEM_PRIVATE);
                            }
                        }
                        NetworkRequest networkRequest = networkRequestBuilder.build();
                        ConnectivityManager.NetworkCallback networkCallback =
                                new ConnectivityManager.NetworkCallback();
                        pw.println("Adding request: " + networkRequest);
                        mConnectivityManager.requestNetwork(networkRequest, networkCallback);
                        sActiveRequests.put(
                                suggestion.getSsid(), Pair.create(networkRequest, networkCallback));
                    }
                    return 0;
                }
                case "remove-suggestion": {
                    String ssid = getNextArgRequired();
                    String action = getNextArg();
                    int actionCode = ACTION_REMOVE_SUGGESTION_DISCONNECT;
                    if (action != null && action.equals("lingering")) {
                        actionCode = ACTION_REMOVE_SUGGESTION_LINGER;
                    }
                    List<WifiNetworkSuggestion> suggestions =
                            mWifiService.getNetworkSuggestions(SHELL_PACKAGE_NAME).getList();
                    WifiNetworkSuggestion suggestion = suggestions.stream()
                            .filter(s -> s.getSsid().equals(ssid))
                            .findAny()
                            .orElse(null);
                    if (suggestion == null) {
                        pw.println("No matching suggestion to remove");
                        return -1;
                    }
                    mWifiService.removeNetworkSuggestions(
                            new ParceledListSlice<>(List.of(suggestion)),
                            SHELL_PACKAGE_NAME, actionCode);
                    // untrusted/oem-paid networks need a corresponding NetworkRequest.
                    if (suggestion.isUntrusted()
                            || (SdkLevel.isAtLeastS()
                            && (suggestion.isOemPaid() || suggestion.isOemPrivate()))) {
                        Pair<NetworkRequest, ConnectivityManager.NetworkCallback> nrAndNc =
                                sActiveRequests.remove(suggestion.getSsid());
                        if (nrAndNc == null) {
                            pw.println("No matching request to remove");
                            return -1;
                        }
                        pw.println("Removing request: " + nrAndNc.first);
                        mConnectivityManager.unregisterNetworkCallback(nrAndNc.second);
                    }
                    return 0;
                }
                case "remove-all-suggestions":
                    mWifiService.removeNetworkSuggestions(
                            new ParceledListSlice<>(Collections.emptyList()), SHELL_PACKAGE_NAME,
                            WifiManager.ACTION_REMOVE_SUGGESTION_DISCONNECT);
                    return 0;
                case "clear-all-suggestions":
                    mWifiThreadRunner.post(() -> mWifiNetworkSuggestionsManager.clear(),
                            "shell#clear-all-suggestions");
                    return 0;
                case "list-suggestions": {
                    List<WifiNetworkSuggestion> suggestions =
                            mWifiService.getNetworkSuggestions(SHELL_PACKAGE_NAME).getList();
                    printWifiNetworkSuggestions(pw, suggestions);
                    return 0;
                }
                case "list-all-suggestions": {
                    Set<WifiNetworkSuggestion> suggestions =
                            mWifiNetworkSuggestionsManager.getAllNetworkSuggestions();
                    printWifiNetworkSuggestions(pw, suggestions);
                    return 0;
                }
                case "list-suggestions-from-app": {
                    String packageName = getNextArgRequired();
                    List<WifiNetworkSuggestion> suggestions =
                            mWifiService.getNetworkSuggestions(packageName).getList();
                    printWifiNetworkSuggestions(pw, suggestions);
                    return 0;
                }
                case "allow-root-to-get-local-only-cmm": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mActiveModeWarden.allowRootToGetLocalOnlyCmm(enabled);
                    return 0;
                }
                case "add-request": {
                    Pair<String, NetworkRequest> result = buildNetworkRequest(pw);
                    String ssid = result.first;
                    NetworkRequest networkRequest = result.second;
                    ConnectivityManager.NetworkCallback networkCallback =
                            new ConnectivityManager.NetworkCallback();
                    pw.println("Adding request: " + networkRequest);
                    mWifiThreadRunner.post(() -> mConnectivityManager
                                    .requestNetwork(networkRequest, networkCallback),
                            "shell#add-request");

                    sActiveRequests.put(ssid, Pair.create(networkRequest, networkCallback));
                    return 0;
                }
                case "remove-request": {
                    String ssid = getNextArgRequired();
                    Pair<NetworkRequest, ConnectivityManager.NetworkCallback> nrAndNc =
                            sActiveRequests.remove(ssid);
                    if (nrAndNc == null) {
                        pw.println("No matching request to remove");
                        return -1;
                    }
                    pw.println("Removing request: " + nrAndNc.first);
                    mWifiThreadRunner.post(() -> mConnectivityManager
                                    .unregisterNetworkCallback(nrAndNc.second),
                            "shell#remove-request");
                    return 0;
                }
                case "remove-all-requests":
                    if (sActiveRequests.isEmpty()) {
                        pw.println("No active requests");
                        return -1;
                    }
                    for (Pair<NetworkRequest, ConnectivityManager.NetworkCallback> nrAndNc
                            : sActiveRequests.values()) {
                        pw.println("Removing request: " + nrAndNc.first);
                        mWifiThreadRunner.post(() ->
                                mConnectivityManager.unregisterNetworkCallback(nrAndNc.second),
                                "shell#remove-request");
                    }
                    sActiveRequests.clear();
                    return 0;
                case "list-requests":
                    if (sActiveRequests.isEmpty()) {
                        pw.println("No active requests");
                    } else {
                        pw.println("SSID                         NetworkRequest");
                        for (Map.Entry<String,
                                Pair<NetworkRequest, ConnectivityManager.NetworkCallback>> entry :
                                sActiveRequests.entrySet()) {
                            pw.println(String.format("%-32s %-4s",
                                    entry.getKey(), entry.getValue().first));
                        }
                    }
                    return 0;
                case "network-requests-set-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean approved = getNextArgRequiredTrueOrFalse("yes", "no");
                    mWifiNetworkFactory.setUserApprovedApp(packageName, approved);
                    return 0;
                }
                case "network-requests-has-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean hasUserApproved = mWifiNetworkFactory.hasUserApprovedApp(packageName);
                    pw.println(hasUserApproved ? "yes" : "no");
                    return 0;
                }
                case "set-coex-cell-channels": {
                    if (!SdkLevel.isAtLeastS()) {
                        return handleDefaultCommands(cmd);
                    }
                    mCoexManager.setMockCellChannels(buildCoexCellChannels());
                    return 0;
                }
                case "reset-coex-cell-channels": {
                    if (!SdkLevel.isAtLeastS()) {
                        return handleDefaultCommands(cmd);
                    }
                    mCoexManager.resetMockCellChannels();
                    return 0;
                }
                case "get-coex-cell-channels": {
                    if (!SdkLevel.isAtLeastS()) {
                        return handleDefaultCommands(cmd);
                    }
                    pw.println("Cell channels: " + mCoexManager.getCellChannels());
                    return 0;
                }
                case "set-connected-score": {
                    int score = Integer.parseInt(getNextArgRequired());
                    CountDownLatch countDownLatch = new CountDownLatch(2);
                    mWifiService.clearWifiConnectedNetworkScorer(); // clear any previous scorer
                    WifiScorer connectedScorer = new WifiScorer(mWifiService, countDownLatch);
                    if (mWifiService.setWifiConnectedNetworkScorer(new Binder(), connectedScorer)) {
                        // wait for retrieving the session id & score observer.
                        countDownLatch.await(1000, TimeUnit.MILLISECONDS);
                    }
                    if (connectedScorer.getSessionId() == null
                            || connectedScorer.getScoreUpdateObserver() == null) {
                        pw.println("Did not receive session id and/or the score update observer. "
                                + "Is the device connected to a wifi network?");
                        mWifiService.clearWifiConnectedNetworkScorer();
                        return -1;
                    }
                    pw.println("Updating score: " + score + " for session id: "
                            + connectedScorer.getSessionId());
                    try {
                        connectedScorer.getScoreUpdateObserver().notifyScoreUpdate(
                                connectedScorer.getSessionId(), score);
                    } catch (RemoteException e) {
                        pw.println("Failed to send the score update");
                        mWifiService.clearWifiConnectedNetworkScorer();
                        return -1;
                    }
                    return 0;
                }
                case "reset-connected-score": {
                    mWifiService.clearWifiConnectedNetworkScorer(); // clear any previous scorer
                    return 0;
                }
                case "network-suggestions-set-as-carrier-provider": {
                    String packageName = getNextArgRequired();
                    boolean enabled = getNextArgRequiredTrueOrFalse("yes", "no");
                    mWifiNetworkSuggestionsManager
                            .setAppWorkingAsCrossCarrierProvider(packageName, enabled);
                    return 0;
                }
                case "is-network-suggestions-set-as-carrier-provider": {
                    String packageName = getNextArgRequired();
                    pw.println(mWifiNetworkSuggestionsManager
                            .isAppWorkingAsCrossCarrierProvider(packageName) ? "yes" : "no");
                    return 0;
                }
                case "remove-shell-app-from-suggestion_database <packageName>": {
                    String packageName = getNextArgRequired();
                    mWifiNetworkSuggestionsManager.removeApp(packageName);
                    return 0;
                }
                case "set-emergency-callback-mode": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mActiveModeWarden.emergencyCallbackModeChanged(enabled);
                    return 0;
                }
                case "set-emergency-call-state": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mActiveModeWarden.emergencyCallStateChanged(enabled);
                    return 0;
                }
                case "set-emergency-scan-request": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    mWifiService.setEmergencyScanRequestInProgress(enabled);
                    return 0;
                }
                case "trigger-recovery": {
                    mSelfRecovery.trigger(REASON_API_CALL);
                    return 0;
                }
                case "add-fake-scan": {
                    String option = getNextOption();
                    boolean isHex = (option != null && option.equals("-x"));
                    WifiSsid wifiSsid = WifiSsid.fromBytes(isHex
                            ? HexEncoding.decode(getNextArgRequired())
                            : getNextArgRequired().getBytes(StandardCharsets.UTF_8));
                    String bssid = getNextArgRequired();
                    String capabilities = getNextArgRequired();
                    int frequency;
                    int dbm;
                    String freqStr = getNextArgRequired();
                    try {
                        frequency = Integer.parseInt(freqStr);
                    } catch (NumberFormatException e) {
                        pw.println(
                                "Invalid frequency argument to 'add-fake-scan' "
                                        + "- must be an integer: " + freqStr);
                        return -1;
                    }
                    if (frequency <= 0) {
                        pw.println("Invalid frequency argument to 'add-fake-scan' - must be a "
                                + "positive integer: " + freqStr);
                    }
                    String dbmString = getNextArgRequired();
                    try {
                        dbm = Integer.parseInt(dbmString);
                    } catch (NumberFormatException e) {
                        pw.println(
                                "Invalid dbm argument to 'add-fake-scan' "
                                        + "- must be an integer: " + dbmString);
                        return -1;
                    }
                    ScanResult.InformationElement ieSSid = new ScanResult.InformationElement(
                            ScanResult.InformationElement.EID_SSID,
                            0,
                            wifiSsid.getBytes());
                    ScanResult.InformationElement[] ies =
                            new ScanResult.InformationElement[]{ieSSid};
                    ScanDetail sd = new ScanDetail(new NetworkDetail(bssid, ies, null, frequency),
                            wifiSsid, bssid, capabilities, dbm,
                            frequency, SystemClock.elapsedRealtime() * 1000, ies, null, null);
                    mWifiNative.addFakeScanDetail(sd);
                    return 0;
                }
                case "reset-fake-scans":
                    mWifiNative.resetFakeScanDetails();
                    return 0;
                case "start-faking-scans":
                    mWifiNative.startFakingScanDetails();
                    mWifiService.startScan(SHELL_PACKAGE_NAME, null); // to trigger update
                    return 0;
                case "stop-faking-scans":
                    mWifiNative.stopFakingScanDetails();
                    return 0;
                case "enable-scanning": {
                    boolean enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    boolean hiddenEnabled = false;
                    String option = getNextOption();
                    if (option != null) {
                        if (option.equals("-h")) {
                            hiddenEnabled = true;
                        } else {
                            pw.println("Invalid argument to 'enable-scanning' "
                                    + "- only allowed option is '-h'");
                            return -1;
                        }
                    }
                    mScanRequestProxy.enableScanning(enabled, hiddenEnabled);
                    return 0;
                }
                case "launch-dialog-simple":
                    String title = null;
                    String message = null;
                    String messageUrl = null;
                    int messageUrlStart = 0;
                    int messageUrlEnd = 0;
                    String positiveButtonText = null;
                    String negativeButtonText = null;
                    String neutralButtonText = null;
                    String dialogOption = getNextOption();
                    boolean simpleTimeoutSpecified = false;
                    long simpleTimeoutMs = 15 * 1000;
                    boolean useLegacy = false;
                    while (dialogOption != null) {
                        switch (dialogOption) {
                            case "-t":
                                title = getNextArgRequired();
                                break;
                            case "-m":
                                message = getNextArgRequired();
                                break;
                            case "-l":
                                messageUrl = getNextArgRequired();
                                messageUrlStart = Integer.valueOf(getNextArgRequired());
                                messageUrlEnd = Integer.valueOf(getNextArgRequired());
                                break;
                            case "-y":
                                positiveButtonText = getNextArgRequired();
                                break;
                            case "-n":
                                negativeButtonText = getNextArgRequired();
                                break;
                            case "-x":
                                neutralButtonText = getNextArgRequired();
                                break;
                            case "-c":
                                simpleTimeoutMs = Integer.parseInt(getNextArgRequired());
                                simpleTimeoutSpecified = true;
                                break;
                            case "-s":
                                useLegacy = true;
                                break;
                            default:
                                pw.println("Ignoring unknown option " + dialogOption);
                                break;
                        }
                        dialogOption = getNextOption();
                    }
                    ArrayBlockingQueue<String> simpleQueue = new ArrayBlockingQueue<>(1);
                    WifiDialogManager.SimpleDialogCallback wifiEnableRequestCallback =
                            new WifiDialogManager.SimpleDialogCallback() {
                                @Override
                                public void onPositiveButtonClicked() {
                                    simpleQueue.offer("Positive button was clicked.");
                                }

                                @Override
                                public void onNegativeButtonClicked() {
                                    simpleQueue.offer("Negative button was clicked.");
                                }

                                @Override
                                public void onNeutralButtonClicked() {
                                    simpleQueue.offer("Neutral button was clicked.");
                                }

                                @Override
                                public void onCancelled() {
                                    simpleQueue.offer("Dialog was cancelled.");
                                }
                            };
                    WifiDialogManager.DialogHandle simpleDialogHandle;
                    if (useLegacy) {
                        simpleDialogHandle = mWifiDialogManager.createLegacySimpleDialogWithUrl(
                                title,
                                message,
                                messageUrl,
                                messageUrlStart,
                                messageUrlEnd,
                                positiveButtonText,
                                negativeButtonText,
                                neutralButtonText,
                                wifiEnableRequestCallback,
                                mWifiThreadRunner);
                    } else {
                        simpleDialogHandle = mWifiDialogManager.createSimpleDialogWithUrl(
                                title,
                                message,
                                messageUrl,
                                messageUrlStart,
                                messageUrlEnd,
                                positiveButtonText,
                                negativeButtonText,
                                neutralButtonText,
                                wifiEnableRequestCallback,
                                mWifiThreadRunner);
                    }
                    simpleDialogHandle.launchDialog();
                    pw.println("Launched dialog. Waiting up to " + simpleTimeoutMs + " ms for"
                            + " user response before dismissing...");
                    String simpleDialogResponse = simpleQueue.poll(simpleTimeoutMs,
                            TimeUnit.MILLISECONDS);
                    if (simpleDialogResponse == null) {
                        pw.println("No response received. Dismissing dialog.");
                        simpleDialogHandle.dismissDialog();
                    } else {
                        pw.println(simpleDialogResponse);
                    }
                    return 0;
                case "launch-dialog-p2p-invitation-sent": {
                    int displayId = Display.DEFAULT_DISPLAY;
                    String deviceName = getNextArgRequired();
                    String cmdOption = getNextOption();
                    String displayPin = null;
                    while (cmdOption != null) {
                        if (cmdOption.equals("-d")) {
                            displayPin = getNextArgRequired();
                        } else if (cmdOption.equals("-i")) {
                            String displayIdStr = getNextArgRequired();
                            try {
                                displayId = Integer.parseInt(displayIdStr);
                            } catch (NumberFormatException e) {
                                pw.println("Invalid <display-id> argument to "
                                        + "'launch-dialog-p2p-invitation-sent' "
                                        + "- must be an integer: "
                                        + displayIdStr);
                                return -1;
                            }
                            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
                            Display[] displays = dm.getDisplays();
                            for (Display display : displays) {
                                pw.println("Display: id=" + display.getDisplayId() + ", info="
                                        + display.getDeviceProductInfo());
                            }
                        } else {
                            pw.println("Ignoring unknown option " + cmdOption);
                        }
                        cmdOption = getNextOption();
                    }
                    mWifiDialogManager.createP2pInvitationSentDialog(deviceName, displayPin,
                            displayId).launchDialog();
                    pw.println("Launched dialog.");
                    return 0;
                }
                case "launch-dialog-p2p-invitation-received": {
                    String deviceName = getNextArgRequired();
                    boolean isPinRequested = false;
                    String displayPin = null;
                    String pinOption = getNextOption();
                    int displayId = Display.DEFAULT_DISPLAY;
                    boolean p2pInvRecTimeoutSpecified = false;
                    int p2pInvRecTimeout = 0;
                    while (pinOption != null) {
                        if (pinOption.equals("-p")) {
                            isPinRequested = true;
                        } else if (pinOption.equals("-d")) {
                            displayPin = getNextArgRequired();
                        } else if (pinOption.equals("-i")) {
                            String displayIdStr = getNextArgRequired();
                            try {
                                displayId = Integer.parseInt(displayIdStr);
                            } catch (NumberFormatException e) {
                                pw.println("Invalid <display-id> argument to "
                                        + "'launch-dialog-p2p-invitation-received' "
                                        + "- must be an integer: "
                                        + displayIdStr);
                                return -1;
                            }
                            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
                            Display[] displays = dm.getDisplays();
                            for (Display display : displays) {
                                pw.println("Display: id=" + display.getDisplayId() + ", info="
                                        + display.getDeviceProductInfo());
                            }
                        } else if (pinOption.equals("-c")) {
                            p2pInvRecTimeout = Integer.parseInt(getNextArgRequired());
                            p2pInvRecTimeoutSpecified = true;
                        } else {
                            pw.println("Ignoring unknown option " + pinOption);
                        }
                        pinOption = getNextOption();
                    }
                    ArrayBlockingQueue<String> p2pInvRecQueue = new ArrayBlockingQueue<>(1);
                    WifiDialogManager.P2pInvitationReceivedDialogCallback callback =
                            new WifiDialogManager.P2pInvitationReceivedDialogCallback() {
                        @Override
                        public void onAccepted(@Nullable String optionalPin) {
                            p2pInvRecQueue.offer("Invitation accepted with optionalPin="
                                    + optionalPin);
                        }

                        @Override
                        public void onDeclined() {
                            p2pInvRecQueue.offer("Invitation declined");
                        }
                    };
                    WifiDialogManager.DialogHandle p2pInvitationReceivedDialogHandle =
                            mWifiDialogManager.createP2pInvitationReceivedDialog(
                                    deviceName,
                                    isPinRequested,
                                    displayPin,
                                    p2pInvRecTimeout,
                                    displayId,
                                    callback,
                                    mWifiThreadRunner);
                    p2pInvitationReceivedDialogHandle.launchDialog();
                    if (p2pInvRecTimeoutSpecified) {
                        pw.println("Launched dialog with " + p2pInvRecTimeout + " millisecond"
                                + " timeout. Waiting for user response...");
                        pw.flush();
                        String dialogResponse = p2pInvRecQueue.take();
                        if (dialogResponse == null) {
                            pw.println("No response received.");
                        } else {
                            pw.println(dialogResponse);
                        }
                    } else {
                        pw.println("Launched dialog. Waiting up to 15 seconds for user response"
                                + " before dismissing...");
                        pw.flush();
                        String dialogResponse = p2pInvRecQueue.poll(15, TimeUnit.SECONDS);
                        if (dialogResponse == null) {
                            pw.println("No response received. Dismissing dialog.");
                            p2pInvitationReceivedDialogHandle.dismissDialog();
                        } else {
                            pw.println(dialogResponse);
                        }
                    }
                    return 0;
                }
                case "query-interface": {
                    String uidArg = getNextArgRequired();
                    int uid = 0;
                    try {
                        uid = Integer.parseInt(uidArg);
                    } catch (NumberFormatException e) {
                        pw.println(
                                "Invalid UID specified, can't convert to an integer - " + uidArg);
                        return -1;
                    }
                    String packageName = getNextArgRequired();

                    String interfaceTypeArg = getNextArgRequired();
                    int interfaceType;
                    switch (interfaceTypeArg) {
                        case "STA":
                            interfaceType = HDM_CREATE_IFACE_STA;
                            break;
                        case "AP":
                            interfaceType = HDM_CREATE_IFACE_AP;
                            break;
                        case "AWARE":
                            interfaceType = HDM_CREATE_IFACE_NAN;
                            break;
                        case "DIRECT":
                            interfaceType = HDM_CREATE_IFACE_P2P;
                            break;
                        default:
                            pw.println("Invalid interface type - expected STA|AP|AWARE|DIRECT: "
                                    + interfaceTypeArg);
                            return -1;
                    }
                    boolean queryForNewInterface = false;
                    String optArg = getNextArg();
                    if (optArg != null) {
                        if (TextUtils.equals("-new", optArg)) {
                            queryForNewInterface = true;
                        } else {
                            pw.println("Unknown extra arg --- " + optArg);
                            return -1;
                        }
                    }
                    List<Pair<Integer, WorkSource>> details =
                            mHalDeviceManager.reportImpactToCreateIface(interfaceType,
                                    queryForNewInterface, new WorkSource(uid, packageName));
                    final SparseArray<String> ifaceMap = new SparseArray<String>() {{
                            put(HDM_CREATE_IFACE_STA, "STA");
                            put(HDM_CREATE_IFACE_AP, "AP");
                            put(HDM_CREATE_IFACE_AP_BRIDGE, "AP");
                            put(HDM_CREATE_IFACE_P2P, "DIRECT");
                            put(HDM_CREATE_IFACE_NAN, "AWARE");
                        }};
                    if (details == null) {
                        pw.println("Can't create interface: " + interfaceTypeArg);
                    } else if (details.size() == 0) {
                        pw.println("Interface " + interfaceTypeArg
                                + " can be created without destroying any other interfaces");
                    } else {
                        pw.println("Interface " + interfaceTypeArg
                                + " can be created. Following interfaces will be destroyed:");
                        for (Pair<Integer, WorkSource> detail : details) {
                            pw.println("    Type=" + ifaceMap.get(detail.first) + ", WS="
                                    + detail.second);
                        }
                    }
                    return 0;
                }
                case "interface-priority-interactive-mode": {
                    String flag = getNextArgRequired(); // enable|disable|default
                    switch (flag) {
                        case "enable":
                            mInterfaceConflictManager.setUserApprovalNeededOverride(true, true);
                            break;
                        case "disable":
                            mInterfaceConflictManager.setUserApprovalNeededOverride(true, false);
                            break;
                        case "default":
                            mInterfaceConflictManager.setUserApprovalNeededOverride(
                                    false, /* don't care */ false);
                            break;
                        default:
                            pw.println(
                                    "Invalid argument to `interface-priority-interactive-mode` - "
                                            + flag);
                            return -1;
                    }
                    return 0;
                }
                case "set-one-shot-screen-on-delay-ms": {
                    if (!SdkLevel.isAtLeastT()) {
                        pw.println("This feature is only supported on SdkLevel T or later.");
                        return -1;
                    }
                    int delay = Integer.parseInt(getNextArgRequired());
                    mWifiService.setOneShotScreenOnConnectivityScanDelayMillis(delay);
                    return 0;
                }
                case "set-network-selection-config": {
                    if (!SdkLevel.isAtLeastT()) {
                        pw.println("This feature is only supported on SdkLevel T or later.");
                        return -1;
                    }
                    WifiNetworkSelectionConfig.Builder builder =
                            new WifiNetworkSelectionConfig.Builder();
                    builder.setSufficiencyCheckEnabledWhenScreenOff(getNextArgRequiredTrueOrFalse(
                            "enabled", "disabled"));
                    builder.setSufficiencyCheckEnabledWhenScreenOn(getNextArgRequiredTrueOrFalse(
                            "enabled", "disabled"));

                    String option = getNextOption();
                    while (option != null) {
                        if (option.equals("-a")) {
                            String associatedNetworkSelectionOverride = getNextArgRequired();
                            int override = Integer.parseInt(associatedNetworkSelectionOverride);
                            builder.setAssociatedNetworkSelectionOverride(override);
                        } else {
                            pw.println("Ignoring unknown option " + option);
                        }
                        option = getNextOption();
                    }
                    WifiNetworkSelectionConfig nsConfig;
                    try {
                        nsConfig = builder.build();
                    } catch (Exception e) {
                        pw.println("Failed to build wifi network selection config.");
                        return -1;
                    }
                    mWifiService.setNetworkSelectionConfig(nsConfig);
                    return 0;
                }
                case "start-dpp-enrollee-responder": {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    String option = getNextOption();
                    String info = null;
                    int curve = 0;
                    while (option != null) {
                        if (option.equals("-i")) {
                            info = getNextArgRequired();
                        } else if (option.equals("-c")) {
                            curve = Integer.parseInt(getNextArgRequired());
                        } else {
                            pw.println("Ignoring unknown option " + option);
                        }
                        option = getNextOption();
                    }
                    mWifiService.startDppAsEnrolleeResponder(new Binder(), info, curve,
                            new DppCallbackProxy(pw, countDownLatch));
                    // Wait for DPP callback
                    countDownLatch.await(10000, TimeUnit.MILLISECONDS);
                    return 0;
                }
                case "start-dpp-configurator-initiator": {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    int netId = Integer.parseInt(getNextArgRequired());
                    int role = Integer.parseInt(getNextArgRequired());
                    String enrolleeUri = getNextArgRequired();
                    mWifiService.startDppAsConfiguratorInitiator(new Binder(), SHELL_PACKAGE_NAME,
                            enrolleeUri, netId, role, new DppCallbackProxy(pw, countDownLatch));
                    // Wait for DPP callback
                    countDownLatch.await(10000, TimeUnit.MILLISECONDS);
                    return 0;
                }
                case "stop-dpp":
                    mWifiService.stopDppSession();
                    return 0;
                case "set-ssid-charset":
                    String lang = getNextArgRequired();
                    Charset charset = Charset.forName(getNextArgRequired());
                    mSsidTranslator.setMockLocaleCharset(lang, charset);
                    return 0;
                case "clear-ssid-charsets":
                    mSsidTranslator.clearMockLocaleCharsets();
                    return 0;
                case "take-bugreport": {
                    if (mDeviceConfig.isInterfaceFailureBugreportEnabled()) {
                        mWifiDiagnostics.takeBugReport("Wifi bugreport test", "");
                    }
                    return 0;
                }
                case "get-allowed-channel": {
                    StringBuilder allowedChannel = new StringBuilder();
                    int band = WifiScanner.WIFI_BAND_24_5_WITH_DFS_6_GHZ;

                    String option = getNextOption();
                    while (option != null) {
                        if (option.equals("-b")) {
                            band = Integer.parseInt(getNextArgRequired());
                        } else {
                            pw.println("Ignoring unknown option: " + option);
                            return -1;
                        }
                        option = getNextOption();
                    }

                    try {
                        Bundle extras = new Bundle();
                        if (SdkLevel.isAtLeastS()) {
                            extras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE,
                                    mContext.getAttributionSource());
                        } else {
                            throw new UnsupportedOperationException();
                        }
                        // The option "-b 2" (getting band 5ghz active channels) is valid for old
                        // devices, but invalid for new devices. So we first check if device
                        // supports getUsableChannels() API.
                        mWifiService.getUsableChannels(WifiScanner.WIFI_BAND_24_5_WITH_DFS_6_GHZ,
                                WifiAvailableChannel.OP_MODE_STA,
                                WifiAvailableChannel.FILTER_REGULATORY, SHELL_PACKAGE_NAME, extras);
                        try {
                            for (int opMode : OP_MODE_LIST) {
                                List<WifiAvailableChannel> usableChannels =
                                        mWifiService.getUsableChannels(band, opMode,
                                                WifiAvailableChannel.FILTER_REGULATORY,
                                                SHELL_PACKAGE_NAME, extras);
                                allowedChannel = new StringBuilder();
                                for (WifiAvailableChannel channel : usableChannels) {
                                    allowedChannel.append(channel.getFrequencyMhz()).append(" ");
                                }
                                pw.println("Allowed ch in " + getOpModeName(opMode) + " mode:\n"
                                        + allowedChannel);
                            }
                        } catch (IllegalArgumentException e) {
                            pw.println("Invalid band: " + band);
                            return -1;
                        }
                    } catch (UnsupportedOperationException e) {
                        WifiScanner wifiScanner = mContext.getSystemService(WifiScanner.class);
                        try {
                            List<Integer> availableChannels = wifiScanner.getAvailableChannels(
                                    band);
                            for (Integer channel : availableChannels) {
                                allowedChannel.append(channel).append(" ");
                            }
                            pw.println("Allowed ch in all modes:\n" + allowedChannel);
                        } catch (SecurityException securityException) {
                            pw.println("Permission is required.");
                            return -1;
                        }
                    } catch (SecurityException e) {
                        pw.println("Permission is required.");
                        return -1;
                    }
                    return 0;
                }
                case "trigger-afc-location-update":
                    Double longitude = Double.parseDouble(getNextArgRequired());
                    Double latitude = Double.parseDouble(getNextArgRequired());
                    Double height = Double.parseDouble(getNextArgRequired());
                    Location location = new Location(LocationManager.FUSED_PROVIDER);
                    location.setLongitude(longitude);
                    location.setLatitude(latitude);
                    location.setAltitude(height);
                    mWifiThreadRunner.post(() -> mAfcManager.onLocationChange(location, true),
                            TAG + "#" + cmd);
                    pw.println("The updated location with longitude of " + longitude + " degrees, "
                            + "latitude of " + latitude + " degrees, and height of " + height
                            + " meters was passed into the Afc Manager onLocationChange method.");
                    return 0;
                case "set-afc-channel-allowance": {
                    WifiChip.AfcChannelAllowance afcChannelAllowance =
                            new WifiChip.AfcChannelAllowance();
                    boolean expiryTimeArgumentIncluded = false;
                    boolean frequencyOrChannelArgumentIncluded = false;
                    afcChannelAllowance.availableAfcFrequencyInfos = new ArrayList<>();
                    afcChannelAllowance.availableAfcChannelInfos = new ArrayList<>();

                    String option;
                    while ((option = getNextOption()) != null) {
                        switch (option) {
                            case "-e": {
                                int secondsUntilExpiry = Integer.parseInt(getNextArgRequired());
                                // AfcChannelAllowance requires this field to be a UNIX timestamp
                                // in milliseconds.
                                afcChannelAllowance.availabilityExpireTimeMs =
                                        System.currentTimeMillis() + secondsUntilExpiry * 1000;
                                expiryTimeArgumentIncluded = true;

                                break;
                            }
                            case "-f": {
                                frequencyOrChannelArgumentIncluded = true;
                                String frequenciesInput = getNextArgRequired();

                                if (frequenciesInput.equals(("none"))) {
                                    break;
                                }

                                // parse frequency list, and add it to the AfcChannelAllowance
                                String[] unparsedFrequencies = frequenciesInput.split(":");
                                afcChannelAllowance.availableAfcFrequencyInfos = new ArrayList<>();

                                for (int i = 0; i < unparsedFrequencies.length; ++i) {
                                    String[] frequencyPieces = unparsedFrequencies[i].split(",");

                                    if (frequencyPieces.length != 3) {
                                        throw new IllegalArgumentException("Each frequency in the "
                                                + "available frequency list should have 3 values, "
                                                + "but found one with " + frequencyPieces.length);
                                    }

                                    WifiChip.AvailableAfcFrequencyInfo frequencyInfo = new
                                            WifiChip.AvailableAfcFrequencyInfo();

                                    frequencyInfo.startFrequencyMhz =
                                            Integer.parseInt(frequencyPieces[0]);
                                    frequencyInfo.endFrequencyMhz =
                                            Integer.parseInt(frequencyPieces[1]);
                                    frequencyInfo.maxPsdDbmPerMhz =
                                            Integer.parseInt(frequencyPieces[2]);

                                    afcChannelAllowance.availableAfcFrequencyInfos
                                                    .add(frequencyInfo);
                                }

                                break;
                            }
                            case "-c": {
                                frequencyOrChannelArgumentIncluded = true;
                                String channelsInput = getNextArgRequired();

                                if (channelsInput.equals("none")) {
                                    break;
                                }

                                // parse channel list, and add it to the AfcChannelAllowance
                                String[] unparsedChannels = channelsInput.split(":");
                                afcChannelAllowance.availableAfcChannelInfos = new ArrayList<>();

                                for (int i = 0; i < unparsedChannels.length; ++i) {
                                    String[] channelPieces = unparsedChannels[i].split(",");

                                    if (channelPieces.length != 3) {
                                        throw new IllegalArgumentException("Each channel in the "
                                                + "available channel list should have 3 values, "
                                                + "but found one with " + channelPieces.length);
                                    }

                                    WifiChip.AvailableAfcChannelInfo channelInfo = new
                                            WifiChip.AvailableAfcChannelInfo();

                                    channelInfo.globalOperatingClass =
                                            Integer.parseInt(channelPieces[0]);
                                    channelInfo.channelCfi = Integer.parseInt(channelPieces[1]);
                                    channelInfo.maxEirpDbm = Integer.parseInt(channelPieces[2]);

                                    afcChannelAllowance.availableAfcChannelInfos.add(channelInfo);
                                }

                                break;
                            }
                            default: {
                                pw.println("Unrecognized command line argument.");
                                return -1;
                            }
                        }
                    }
                    if (!expiryTimeArgumentIncluded) {
                        pw.println("Please include the -e flag to set the seconds until the "
                                + "availability expires.");
                        return -1;
                    }
                    if (!frequencyOrChannelArgumentIncluded) {
                        pw.println("Please include at least one of the -f or -c flags to set the "
                                + "frequency or channel availability.");
                        return -1;
                    }

                    ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
                    mWifiThreadRunner.post(() -> {
                        if (mWifiNative.setAfcChannelAllowance(afcChannelAllowance)) {
                            queue.offer("Successfully set the allowed AFC channels and "
                                    + "frequencies.");
                        } else {
                            queue.offer("Setting the allowed AFC channels and frequencies "
                                    + "failed.");
                        }
                    }, TAG + "#" + cmd);

                    // block until msg is received, or timed out
                    String msg = queue.poll(3000, TimeUnit.MILLISECONDS);
                    if (msg == null) {
                        pw.println("Setting the allowed AFC channels and frequencies timed out.");
                    } else {
                        pw.println(msg);
                    }

                    return 0;
                }
                case "get-cached-scan-data":
                    WifiScanner.ScanData scanData =
                            mWifiNative.getCachedScanResultsFromAllClientIfaces();

                    if (scanData.getResults().length > 0) {
                        pw.println("Successfully get cached scan data: ");
                        for (ScanResult scanResult : scanData.getResults()) {
                            pw.println(scanResult);
                        }
                    } else {
                        pw.println("Cached scan data is empty");
                    }
                    return 0;
                case "configure-afc-server":
                    final String url = getNextArgRequired();

                    if (!url.startsWith("http")) {
                        pw.println("The required URL first argument is not a valid server URL for"
                                + " a HTTP request.");
                        return -1;
                    }

                    String secondOption = getNextOption();
                    Map<String, String> requestProperties = new HashMap<>();
                    if (secondOption != null && secondOption.equals("-r")) {

                        String key = getNextArg();
                        while (key != null) {

                            String value = getNextArg();
                            // Check if there is a next value
                            if (value != null) {
                                requestProperties.put(key, value);
                            } else {
                                // Fail to proceed as there is no value given for the corresponding
                                // key
                                pw.println(
                                        "No value provided for the corresponding key " + key
                                                + ". There must be an even number of request"
                                                + " property arguments provided after the -r "
                                                + "option.");
                                return -1;
                            }
                            key = getNextArg();
                        }

                    } else {
                        pw.println("No -r option was provided as second argument so the HTTP "
                                + "request will have no request properties.");
                    }

                    mWifiThreadRunner.post(() -> {
                        mAfcManager.setServerUrlAndRequestPropertyPairs(url, requestProperties);
                    }, TAG + "#" + cmd);

                    pw.println("The URL is set to " + url);
                    pw.println("The request properties are set to: ");

                    for (Map.Entry<String, String> requestProperty : requestProperties.entrySet()) {
                        pw.println("Key: " + requestProperty.getKey() + ", Value: "
                                + requestProperty.getValue());
                    }
                    return 0;
                case "set-mock-wifimodem-service":
                    String opt = null;
                    String serviceName = null;
                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-s": {
                                serviceName = getNextArgRequired();
                                break;
                            }
                            default:
                                pw.println("set-mock-wifimodem-service requires '-s' option");
                                return -1;
                        }
                    }
                    mWifiService.setMockWifiService(serviceName);
                    // The result will be checked, must print result "true"
                    pw.print("true");
                    return 0;
                case "get-mock-wifimodem-service":
                    pw.print(mWifiNative.getMockWifiServiceName());
                    return 0;
                case "set-mock-wifimodem-methods":
                    String methods = getNextArgRequired();
                    if (mWifiService.setMockWifiMethods(methods)) {
                        pw.print("true");
                    } else {
                        pw.print("fail to set mock method: " + methods);
                        return -1;
                    }
                    return 0;
                case "force-overlay-config-value":
                    int uid = Binder.getCallingUid();
                    if (!mWifiInjector.getWifiPermissionsUtil()
                            .checkNetworkSettingsPermission(Binder.getCallingUid())) {
                        pw.println("current shell caller Uid " + uid
                                + " Missing NETWORK_SETTINGS permission");
                        return -1;
                    }
                    WifiResourceCache resourceCache = mContext.getResourceCache();
                    String type = getNextArgRequired();
                    String overlayName = getNextArgRequired();
                    boolean isEnabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                    switch (type) {
                        case "bool" -> {
                            boolean value = false;
                            if (isEnabled) {
                                value = getNextArgRequiredTrueOrFalse("true", "false");
                                resourceCache.overrideBooleanValue(overlayName, value);
                            } else {
                                resourceCache.restoreBooleanValue(overlayName);
                            }
                        }
                        case "integer" -> {
                            int value = 0;
                            if (isEnabled) {
                                value = Integer.parseInt(getNextArgRequired());
                                resourceCache.overrideIntegerValue(overlayName, value);
                            } else {
                                resourceCache.restoreIntegerValue(overlayName);
                            }
                        }
                        case "string" -> {
                            String value;
                            if (isEnabled) {
                                value = getNextArgRequired();
                                resourceCache.overrideStringValue(overlayName, value);
                            } else {
                                resourceCache.restoreStringValue(overlayName);
                            }
                        }
                        case "string-array" -> {
                            String[] value;
                            if (isEnabled) {
                                value = peekRemainingArgs();
                                resourceCache.overrideStringArrayValue(overlayName, value);
                            } else {
                                resourceCache.restoreStringArrayValue(overlayName);
                            }
                        }
                        case "integer-array" -> {
                            String[] input;
                            if (isEnabled) {
                                input = peekRemainingArgs();
                                int[] value = new int[input.length];
                                for (int i = 0; i < input.length; i++) {
                                    value[i] = Integer.parseInt(input[i]);
                                }

                                resourceCache.overrideIntArrayValue(overlayName, value);
                            } else {
                                resourceCache.restoreIntArrayValue(overlayName);
                            }
                        }
                        default -> {
                            pw.print("require a valid type of the overlay");
                            return -1;
                        }
                    }
                    pw.println("true");
                    return 0;
                case "get-overlay-config-values":
                    mContext.getResourceCache().dump(pw);
                    return 0;
                case "set-ssid-roaming-mode":
                    String ssid = getNextArgRequired();
                    String roamingMode = getNextArgRequired();
                    String option = getNextOption();

                    WifiSsid wifiSsid;
                    if (option != null && option.equals("-x")) {
                        wifiSsid = WifiSsid.fromString(ssid);
                    } else {
                        wifiSsid = WifiSsid.fromString("\"" + ssid + "\"");
                    }

                    int mode;
                    if (roamingMode.equals("none")) {
                        mode = ROAMING_MODE_NONE;
                    } else if (roamingMode.equals("normal")) {
                        mode = ROAMING_MODE_NORMAL;
                    } else if (roamingMode.equals("aggressive")) {
                        mode = ROAMING_MODE_AGGRESSIVE;
                    } else {
                        pw.println("Unsupported roaming mode");
                        return -1;
                    }

                    mWifiService.setPerSsidRoamingMode(wifiSsid, mode, SHELL_PACKAGE_NAME);
                    return 0;
                case "set-scan-throttling-enabled":
                    mWifiService.setScanThrottleEnabled(
                            getNextArgRequiredTrueOrFalse("enabled", "disabled"));
                    return 0;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            pw.println("Invalid args for " + cmd + ": " + e);
            return -1;
        } catch (Exception e) {
            pw.println("Exception while executing WifiShellCommand: ");
            e.printStackTrace(pw);
            return -1;
        }
    }

    private boolean getNextArgRequiredTrueOrFalse(String trueString, String falseString)
            throws IllegalArgumentException {
        String nextArg = getNextArgRequired();
        if (trueString.equals(nextArg)) {
            return true;
        } else if (falseString.equals(nextArg)) {
            return false;
        } else {
            throw new IllegalArgumentException("Expected '" + trueString + "' or '" + falseString
                    + "' as next arg but got '" + nextArg + "'");
        }
    }

    private WifiConfiguration buildWifiConfiguration(PrintWriter pw) {
        String ssid = getNextArgRequired();
        String type = getNextArgRequired();
        WifiConfiguration configuration = new WifiConfiguration();
        // Wrap the SSID in double quotes for UTF-8. The quotes may be removed if the SSID is in
        // hexadecimal digits, specified by the [-x] option below.
        configuration.SSID = "\"" + ssid + "\"";
        if (TextUtils.equals(type, "wpa3")) {
            configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
            configuration.preSharedKey = "\"" + getNextArgRequired() + "\"";
        } else if (TextUtils.equals(type, "wpa2")) {
            configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
            configuration.preSharedKey = "\"" + getNextArgRequired() + "\"";
        } else if (TextUtils.equals(type, "owe")) {
            configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);
        } else if (TextUtils.equals(type, "open")) {
            configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
        } else if (TextUtils.equals(type, "dpp")) {
            configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_DPP);
        } else if (TextUtils.equals(type, "wep")) {
            configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_WEP);
            String password = getNextArgRequired();
            // WEP-40, WEP-104, and WEP-256
            if ((password.length() == 10 || password.length() == 26 || password.length() == 58)
                    && password.matches("[0-9A-Fa-f]*")) {
                configuration.wepKeys[0] = password;
            } else {
                configuration.wepKeys[0] = '"' + password + '"';
            }
        } else {
            throw new IllegalArgumentException("Unknown network type " + type);
        }
        String option = getNextOption();
        while (option != null) {
            if (option.equals("-x")) {
                configuration.SSID = ssid;
            } else if (option.equals("-m")) {
                configuration.meteredOverride = METERED_OVERRIDE_METERED;
            } else if (option.equals("-d")) {
                configuration.allowAutojoin = false;
            } else if (option.equals("-b")) {
                configuration.BSSID = getNextArgRequired();
            } else if (option.equals("-r")) {
                String macRandomizationScheme = getNextArgRequired();
                if (macRandomizationScheme.equals("auto")) {
                    configuration.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;
                } else if (macRandomizationScheme.equals("none")) {
                    configuration.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NONE;
                } else if (macRandomizationScheme.equals("persistent")) {
                    configuration.macRandomizationSetting =
                            WifiConfiguration.RANDOMIZATION_PERSISTENT;
                } else if (macRandomizationScheme.equals("non_persistent")) {
                    if (SdkLevel.isAtLeastS()) {
                        configuration.macRandomizationSetting =
                                WifiConfiguration.RANDOMIZATION_NON_PERSISTENT;
                    } else {
                        throw new IllegalArgumentException(
                                "-r non_persistent MAC randomization not supported before S");
                    }
                }
            } else if (option.equals("-h")) {
                configuration.hiddenSSID = true;
            } else if (option.equals("-p")) {
                configuration.shared = false;
            } else {
                pw.println("Ignoring unknown option " + option);
            }
            option = getNextOption();
        }
        return configuration;
    }

    private SoftApConfiguration buildSoftApConfiguration(PrintWriter pw) {
        String ssidStr = getNextArgRequired();
        String type = getNextArgRequired();
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid(ssidStr);
        if (TextUtils.equals(type, "wpa2")) {
            configBuilder.setPassphrase(getNextArgRequired(),
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        } else if (TextUtils.equals(type, "wpa3")) {
            configBuilder.setPassphrase(getNextArgRequired(),
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE);
        } else if (TextUtils.equals(type, "wpa3_transition")) {
            configBuilder.setPassphrase(getNextArgRequired(),
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION);
        } else if (TextUtils.equals(type, "open")) {
            configBuilder.setPassphrase(null, SoftApConfiguration.SECURITY_TYPE_OPEN);
        } else if (TextUtils.equals(type, "owe_transition")) {
            configBuilder.setPassphrase(null,
                    SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION);
        } else if (TextUtils.equals(type, "owe")) {
            configBuilder.setPassphrase(null,
                    SoftApConfiguration.SECURITY_TYPE_WPA3_OWE);
        } else {
            throw new IllegalArgumentException("Unknown network type " + type);
        }
        String option = getNextOption();
        while (option != null) {
            if (option.equals("-b")) {
                String preferredBand = getNextArgRequired();
                if (preferredBand.equals("2")) {
                    configBuilder.setBand(SoftApConfiguration.BAND_2GHZ);
                } else if (preferredBand.equals("5")) {
                    configBuilder.setBand(SoftApConfiguration.BAND_5GHZ);
                } else if (preferredBand.equals("6")) {
                    configBuilder.setBand(SoftApConfiguration.BAND_6GHZ);
                } else if (preferredBand.equals("any")) {
                    configBuilder.setBand(SoftApConfiguration.BAND_2GHZ
                            | SoftApConfiguration.BAND_5GHZ | SoftApConfiguration.BAND_6GHZ);
                } else if (preferredBand.startsWith("bridged")) {
                    if (!SdkLevel.isAtLeastS()) {
                        throw new IllegalArgumentException(
                               "-b bridged* option is not supported before S");
                    }
                    switch (preferredBand) {
                        case "bridged":
                            // fall through
                        case "bridged_2_5":
                            configBuilder.setBands(new int[] {
                                    SoftApConfiguration.BAND_2GHZ, SoftApConfiguration.BAND_5GHZ});
                            break;
                        case "bridged_2_6":
                            configBuilder.setBands(new int[] {
                                    SoftApConfiguration.BAND_2GHZ, SoftApConfiguration.BAND_6GHZ});
                            break;
                        case "bridged_5_6":
                            configBuilder.setBands(new int[] {
                                    SoftApConfiguration.BAND_5GHZ, SoftApConfiguration.BAND_6GHZ});
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid bridged band option "
                                    + preferredBand);
                    }
                } else {
                    throw new IllegalArgumentException("Invalid band option " + preferredBand);
                }
            } else if (SdkLevel.isAtLeastT() && option.equals("-x")) {
                configBuilder.setWifiSsid(WifiSsid.fromString(ssidStr));
            } else if (option.equals("-f")) {
                SparseIntArray channels = new SparseIntArray();
                while (getRemainingArgsCount() > 0) {
                    int apChannelMHz;
                    try {
                        apChannelMHz = Integer.parseInt(getNextArgRequired());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "-f option requires a valid channel frequency");
                    }
                    channels.put(ApConfigUtil.convertFrequencyToBand(apChannelMHz),
                            ScanResult.convertFrequencyMhzToChannelIfSupported(apChannelMHz));
                }
                if (channels.size() == 0) {
                    throw new IllegalArgumentException(
                            "-f option requires a valid channel frequency atleast");
                }
                if (SdkLevel.isAtLeastS()) {
                    configBuilder.setChannels(channels);
                } else {
                    if (channels.size() > 1) {
                        throw new IllegalArgumentException(
                                "dual channels are not supported before S");
                    }
                    configBuilder.setChannel(channels.valueAt(0), channels.keyAt(0));
                }
            } else if (option.equals("-w")) {
                String bandwidth = getNextArgRequired();
                if (bandwidth.equals("20")) {
                    configBuilder.setMaxChannelBandwidth(SoftApInfo.CHANNEL_WIDTH_20MHZ);
                } else if (bandwidth.equals("40")) {
                    configBuilder.setMaxChannelBandwidth(SoftApInfo.CHANNEL_WIDTH_40MHZ);
                } else if (bandwidth.equals("80")) {
                    configBuilder.setMaxChannelBandwidth(SoftApInfo.CHANNEL_WIDTH_80MHZ);
                } else if (bandwidth.equals("160")) {
                    configBuilder.setMaxChannelBandwidth(SoftApInfo.CHANNEL_WIDTH_160MHZ);
                } else if (bandwidth.equals("320")) {
                    configBuilder.setMaxChannelBandwidth(SoftApInfo.CHANNEL_WIDTH_320MHZ);
                } else {
                    throw new IllegalArgumentException("Invalid bandwidth option " + bandwidth);
                }
            } else {
                pw.println("Ignoring unknown option " + option);
            }
            option = getNextOption();
        }
        return configBuilder.build();
    }

    private WifiNetworkSuggestion buildSuggestion(PrintWriter pw) {
        String ssid = getNextArgRequired();
        String type = getNextArgRequired();
        WifiNetworkSuggestion.Builder suggestionBuilder =
                new WifiNetworkSuggestion.Builder();
        suggestionBuilder.setSsid(ssid);
        if (TextUtils.equals(type, "wpa3")) {
            suggestionBuilder.setWpa3Passphrase(getNextArgRequired());
        } else if (TextUtils.equals(type, "wpa2")) {
            suggestionBuilder.setWpa2Passphrase(getNextArgRequired());
        } else if (TextUtils.equals(type, "owe")) {
            suggestionBuilder.setIsEnhancedOpen(true);
        } else if (TextUtils.equals(type, "open")) {
            // nothing to do.
        } else {
            throw new IllegalArgumentException("Unknown network type " + type);
        }
        boolean isCarrierMerged = false;
        String option = getNextOption();
        while (option != null) {
            if (option.equals("-u")) {
                suggestionBuilder.setUntrusted(true);
            } else if (option.equals("-o")) {
                if (SdkLevel.isAtLeastS()) {
                    suggestionBuilder.setOemPaid(true);
                } else {
                    throw new IllegalArgumentException(
                            "-o OEM paid suggestions not supported before S");
                }
            } else if (option.equals("-p")) {
                if (SdkLevel.isAtLeastS()) {
                    suggestionBuilder.setOemPrivate(true);
                } else {
                    throw new IllegalArgumentException(
                            "-p OEM private suggestions not supported before S");
                }
            } else if (option.equals("-m")) {
                suggestionBuilder.setIsMetered(true);
            } else if (option.equals("-s")) {
                suggestionBuilder.setCredentialSharedWithUser(true);
            } else if (option.equals("-d")) {
                suggestionBuilder.setIsInitialAutojoinEnabled(false);
            } else if (option.equals("-b")) {
                suggestionBuilder.setBssid(MacAddress.fromString(getNextArgRequired()));
            } else if (option.equals("-r")) {
                if (SdkLevel.isAtLeastS()) {
                    suggestionBuilder.setMacRandomizationSetting(
                            WifiNetworkSuggestion.RANDOMIZATION_NON_PERSISTENT);
                } else {
                    throw new IllegalArgumentException(
                            "-r non_persistent MAC randomization not supported before S");
                }
            } else if (option.equals("-a")) {
                if (SdkLevel.isAtLeastS()) {
                    isCarrierMerged = true;
                } else {
                    throw new IllegalArgumentException("-a option is not supported before S");
                }
            } else if (option.equals("-i")) {
                if (SdkLevel.isAtLeastS()) {
                    int subId = Integer.parseInt(getNextArgRequired());
                    suggestionBuilder.setSubscriptionId(subId);
                } else {
                    throw new IllegalArgumentException(
                            "-i subscription ID option is not supported before S");
                }
            } else if (option.equals("-c")) {
                int carrierId = Integer.parseInt(getNextArgRequired());
                suggestionBuilder.setCarrierId(carrierId);
            } else if (option.equals("-h")) {
                suggestionBuilder.setIsHiddenSsid(true);
            } else {
                pw.println("Ignoring unknown option " + option);
            }
            option = getNextOption();
        }
        WifiNetworkSuggestion suggestion = suggestionBuilder.build();
        if (isCarrierMerged) {
            if (suggestion.wifiConfiguration.subscriptionId
                    == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                pw.println("Carrier merged network must have valid subscription Id");
                return null;
            }
            suggestion.wifiConfiguration.carrierMerged = true;
        }
        return suggestion;
    }

    private Pair<String, NetworkRequest> buildNetworkRequest(PrintWriter pw) {
        String firstOpt = getNextOption();
        boolean isGlob = "-g".equals(firstOpt);
        boolean noSsid = "-s".equals(firstOpt);
        String ssid = noSsid ? "NoSsid" : getNextArgRequired();
        String type = noSsid ? null : getNextArgRequired();
        WifiNetworkSpecifier.Builder specifierBuilder =
                new WifiNetworkSpecifier.Builder();
        if (isGlob) {
            specifierBuilder.setSsidPattern(
                    new PatternMatcher(ssid, PatternMatcher.PATTERN_ADVANCED_GLOB));
        } else {
            if (ssid != null && !noSsid) specifierBuilder.setSsid(ssid);
        }
        if (type != null) {
            if (TextUtils.equals(type, "wpa3")) {
                specifierBuilder.setWpa3Passphrase(getNextArgRequired());
            } else if (TextUtils.equals(type, "wpa3_transition")) {
                specifierBuilder.setWpa3Passphrase(getNextArgRequired());
            } else if (TextUtils.equals(type, "wpa2")) {
                specifierBuilder.setWpa2Passphrase(getNextArgRequired());
            } else if (TextUtils.equals(type, "owe")) {
                specifierBuilder.setIsEnhancedOpen(true);
            } else if (TextUtils.equals(type, "open")) {
                // nothing to do.
            } else {
                throw new IllegalArgumentException("Unknown network type " + type);
            }
        }
        String bssid = null;
        String option = getNextOption();
        String ssidKey = ssid;
        boolean nullBssid = false;
        boolean hasInternet = false;
        while (option != null) {
            if (option.equals("-b")) {
                bssid = getNextArgRequired();
            } else if (option.equals("-n")) {
                nullBssid = true;
            } else if (option.equals("-d")) {
                String band = getNextArgRequired();
                ssidKey = ssidKey + "_" + band + "g";
                if (band.equals("2")) {
                    specifierBuilder.setBand(ScanResult.WIFI_BAND_24_GHZ);
                } else if (band.equals("5")) {
                    specifierBuilder.setBand(ScanResult.WIFI_BAND_5_GHZ);
                } else if (band.equals("6")) {
                    specifierBuilder.setBand(ScanResult.WIFI_BAND_6_GHZ);
                } else if (band.equals("60")) {
                    specifierBuilder.setBand(ScanResult.WIFI_BAND_60_GHZ);
                } else {
                    throw new IllegalArgumentException("Unknown band " + band);
                }
            } else if (option.equals("-i")) {
                ssidKey = ssidKey + "_internet";
                hasInternet = true;
            } else {
                pw.println("Ignoring unknown option " + option);
            }
            option = getNextOption();
        }
        if (bssid != null && nullBssid) {
            throw new IllegalArgumentException("Invalid option combination: "
                    + "Should not use both -b and -n at the same time.");
        }

        // Permission approval bypass is only available to requests with both ssid & bssid set.
        // So, find scan result with the best rssi level to set in the request.
        if (bssid == null && !nullBssid && !noSsid) {
            ScanResult matchingScanResult =
                    mScanRequestProxy.getScanResults()
                            .stream()
                            .filter(s -> s.SSID.equals(ssid))
                            .max(Comparator.comparingInt(s -> s.level))
                            .orElse(null);
            if (matchingScanResult != null) {
                bssid = matchingScanResult.BSSID;
            } else {
                pw.println("No matching bssid found, request will need UI approval");
            }
        }
        if (bssid != null && !nullBssid) specifierBuilder.setBssid(MacAddress.fromString(bssid));
        NetworkRequest.Builder builder = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI);
        if (hasInternet) {
            builder.addCapability(NET_CAPABILITY_INTERNET);
        } else {
            builder.removeCapability(NET_CAPABILITY_INTERNET);
        }
        return new Pair<String, NetworkRequest>(ssidKey,
                builder.setNetworkSpecifier(specifierBuilder.build()).build());
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @NonNull
    private List<CoexUtils.CoexCellChannel> buildCoexCellChannels() {
        List<CoexUtils.CoexCellChannel> cellChannels = new ArrayList<>();
        while (getRemainingArgsCount() > 0) {
            final @Annotation.NetworkType int rat;
            final String ratArg = getNextArgRequired();
            if (TextUtils.equals(ratArg, "lte")) {
                rat = TelephonyManager.NETWORK_TYPE_LTE;
            } else if (TextUtils.equals(ratArg, "nr")) {
                rat = TelephonyManager.NETWORK_TYPE_NR;
            } else {
                throw new IllegalArgumentException("Unknown rat type " + ratArg);
            }
            final int band = Integer.parseInt(getNextArgRequired());
            if (band < 1 || band > 261) {
                throw new IllegalArgumentException("Band is " + band
                        + " but should be a value from 1 to 261");
            }
            final int downlinkFreqKhz = Integer.parseInt(getNextArgRequired());
            if (downlinkFreqKhz < 0 && downlinkFreqKhz != PhysicalChannelConfig.FREQUENCY_UNKNOWN) {
                throw new IllegalArgumentException("Downlink frequency is " + downlinkFreqKhz
                        + " but should be >= 0 or UNKNOWN: "
                        + PhysicalChannelConfig.FREQUENCY_UNKNOWN);
            }
            final int downlinkBandwidthKhz = Integer.parseInt(getNextArgRequired());
            if (downlinkBandwidthKhz <= 0
                    && downlinkBandwidthKhz != PhysicalChannelConfig.CELL_BANDWIDTH_UNKNOWN) {
                throw new IllegalArgumentException("Downlink bandwidth is " + downlinkBandwidthKhz
                        + " but should be > 0 or UNKNOWN: "
                        + PhysicalChannelConfig.CELL_BANDWIDTH_UNKNOWN);
            }
            final int uplinkFreqKhz = Integer.parseInt(getNextArgRequired());
            if (uplinkFreqKhz < 0 && uplinkFreqKhz != PhysicalChannelConfig.FREQUENCY_UNKNOWN) {
                throw new IllegalArgumentException("Uplink frequency is " + uplinkFreqKhz
                        + " but should be >= 0 or UNKNOWN: "
                        + PhysicalChannelConfig.FREQUENCY_UNKNOWN);
            }
            final int uplinkBandwidthKhz = Integer.parseInt(getNextArgRequired());
            if (uplinkBandwidthKhz <= 0
                    && uplinkBandwidthKhz != PhysicalChannelConfig.CELL_BANDWIDTH_UNKNOWN) {
                throw new IllegalArgumentException("Uplink bandwidth is " + uplinkBandwidthKhz
                        + " but should be > 0 or UNKNOWN: "
                        + PhysicalChannelConfig.CELL_BANDWIDTH_UNKNOWN);
            }
            cellChannels.add(new CoexUtils.CoexCellChannel(rat, band,
                    downlinkFreqKhz, downlinkBandwidthKhz, uplinkFreqKhz, uplinkBandwidthKhz,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID));
        }
        return cellChannels;
    }

    private int sendLinkProbe(PrintWriter pw) throws InterruptedException {
        // Note: should match WifiNl80211Manager#SEND_MGMT_FRAME_TIMEOUT_MS
        final int sendMgmtFrameTimeoutMs = 1000;

        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        mWifiThreadRunner.post(() ->
                mActiveModeWarden.getPrimaryClientModeManager().probeLink(new LinkProbeCallback() {
                    @Override
                    public void onAck(int elapsedTimeMs) {
                        queue.offer("Link probe succeeded after " + elapsedTimeMs + " ms");
                    }

                    @Override
                    public void onFailure(int reason) {
                        queue.offer("Link probe failed with reason "
                                + LinkProbeCallback.failureReasonToString(reason));
                    }
                }, -1), TAG + "#sendLinkProbe");

        // block until msg is received, or timed out
        String msg = queue.poll(sendMgmtFrameTimeoutMs + 1000, TimeUnit.MILLISECONDS);
        if (msg == null) {
            pw.println("Link probe timed out");
        } else {
            pw.println(msg);
        }
        return 0;
    }

    private boolean isApChannelMHzValid(PrintWriter pw, int apChannelMHz) {
        int[] allowed2gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
        int[] allowed5gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
        int[] allowed5gDfsFreq =
            mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY);
        int[] allowed6gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ);
        int[] allowed60gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_60_GHZ);
        if (allowed2gFreq == null) {
            allowed2gFreq = new int[0];
        }
        if (allowed5gFreq == null) {
            allowed5gFreq = new int[0];
        }
        if (allowed5gDfsFreq == null) {
            allowed5gDfsFreq = new int[0];
        }
        if (allowed6gFreq == null) {
            allowed6gFreq = new int[0];
        }
        if (allowed60gFreq == null) {
            allowed60gFreq = new int[0];
        }
        pw.println("2G freq: " + Arrays.toString(allowed2gFreq));
        pw.println("5G freq: " + Arrays.toString(allowed5gFreq));
        pw.println("5G DFS: " + Arrays.toString(allowed5gDfsFreq));
        pw.println("6G freq: " + Arrays.toString(allowed6gFreq));
        pw.println("60G freq: " + Arrays.toString(allowed60gFreq));
        return (Arrays.binarySearch(allowed2gFreq, apChannelMHz) >= 0
                || Arrays.binarySearch(allowed5gFreq, apChannelMHz) >= 0
                || Arrays.binarySearch(allowed5gDfsFreq, apChannelMHz) >= 0)
                || Arrays.binarySearch(allowed6gFreq, apChannelMHz) >= 0
                || Arrays.binarySearch(allowed60gFreq, apChannelMHz) >= 0;
    }

    private void waitForWifiEnabled(boolean enabled) throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    int state = mWifiService.getWifiEnabledState();
                    if ((enabled && state == WIFI_STATE_ENABLED)
                            || (!enabled && state == WIFI_STATE_DISABLED)) {
                        countDownLatch.countDown();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mContext.registerReceiver(broadcastReceiver, filter);
        mWifiService.setWifiEnabled(SHELL_PACKAGE_NAME, enabled);
        countDownLatch.await(5000, TimeUnit.MILLISECONDS);
        mContext.unregisterReceiver(broadcastReceiver);
    }

    private void printWifiInfo(PrintWriter pw, WifiInfo info) {
        if (info.getSupplicantState() != SupplicantState.COMPLETED) {
            pw.println("Wifi is not connected");
            return;
        }
        pw.println("Wifi is connected to " + info.getSSID());
        pw.println("WifiInfo: " + info);
        // additional diagnostics not printed by WifiInfo.toString()
        pw.println("successfulTxPackets: " + info.txSuccess);
        pw.println("successfulTxPacketsPerSecond: " + info.getSuccessfulTxPacketsPerSecond());
        pw.println("retriedTxPackets: " + info.txRetries);
        pw.println("retriedTxPacketsPerSecond: " + info.getRetriedTxPacketsPerSecond());
        pw.println("lostTxPackets: " + info.txBad);
        pw.println("lostTxPacketsPerSecond: " + info.getLostTxPacketsPerSecond());
        pw.println("successfulRxPackets: " + info.rxSuccess);
        pw.println("successfulRxPacketsPerSecond: " + info.getSuccessfulRxPacketsPerSecond());
    }

    private void printStatus(PrintWriter pw) {
        boolean wifiEnabled = mWifiService.getWifiEnabledState() == WIFI_STATE_ENABLED;
        pw.println("Wifi is " + (wifiEnabled ? "enabled" : "disabled"));
        pw.println("Wifi scanning is "
                + (mWifiService.isScanAlwaysAvailable()
                ? "always available" : "only available when wifi is enabled"));
        if (!wifiEnabled) {
            return;
        }
        if (Binder.getCallingUid() != Process.ROOT_UID) {
            // not privileged, just dump the primary client mode manager manager status
            // (public API contents).
            pw.println("==== Primary ClientModeManager instance ====");
            printWifiInfo(pw, mWifiService.getConnectionInfo(SHELL_PACKAGE_NAME, null));
        } else {
            // privileged, dump out all the client mode manager manager statuses
            for (ClientModeManager cm : mActiveModeWarden.getClientModeManagers()) {
                pw.println("==== ClientModeManager instance: " + cm + " ====");
                WifiInfo info = cm.getConnectionInfo();
                printWifiInfo(pw, info);
                if (info.getSupplicantState() != SupplicantState.COMPLETED) {
                    continue;
                }
                Network network = cm.getCurrentNetwork();
                NetworkCapabilities capabilities =
                        mConnectivityManager.getNetworkCapabilities(network);
                pw.println("NetworkCapabilities: " + capabilities);
            }
        }
    }

    private void onHelpNonPrivileged(PrintWriter pw) {
        pw.println("  get-country-code");
        pw.println("    Gets country code as a two-letter string");
        pw.println("  set-wifi-enabled enabled|disabled");
        pw.println("    Enables/disables Wifi on this device.");
        pw.println("  set-scan-always-available enabled|disabled");
        pw.println("    Sets whether scanning should be available even when wifi is off.");
        pw.println("  connect-network <ssid> open|owe|wpa2|wpa3|wep [<passphrase>] [-x] [-m] "
                + "[-d] [-b <bssid>] [-r auto|none|persistent|non_persistent]");
        pw.println("    Connect to a network with provided params and add to saved networks list");
        pw.println("    <ssid> - SSID of the network");
        pw.println("    open|owe|wpa2|wpa3|wep - Security type of the network.");
        pw.println("        - Use 'open' or 'owe' for networks with no passphrase");
        pw.println("           - 'open' - Open networks (Most prevalent)");
        pw.println("           - 'owe' - Enhanced open networks");
        pw.println("        - Use 'wpa2' or 'wpa3' or 'wep' for networks with passphrase");
        pw.println("           - 'wpa2' - WPA-2 PSK networks (Most prevalent)");
        pw.println("           - 'wpa3' - WPA-3 PSK networks");
        pw.println("           - 'wep'  - WEP network. Passphrase should be bytes in hex or encoded"
                + " into String using UTF-8");
        pw.println("    -x - Specifies the SSID as hex digits instead of plain text");
        pw.println("    -m - Mark the network metered.");
        pw.println("    -d - Mark the network autojoin disabled.");
        pw.println("    -h - Mark the network hidden.");
        pw.println("    -p - Mark the network private (not shared).");
        pw.println("    -b <bssid> - Set specific BSSID.");
        pw.println("    -r auto|none|persistent|non_persistent - MAC randomization scheme for the"
                + " network");
        pw.println("  add-network <ssid> open|owe|wpa2|wpa3|wep [<passphrase>] [-x] [-m] [-d] "
                + "[-b <bssid>] [-r auto|none|persistent|non_persistent]");
        pw.println("    Add/update saved network with provided params");
        pw.println("    <ssid> - SSID of the network");
        pw.println("    open|owe|wpa2|wpa3|wep - Security type of the network.");
        pw.println("        - Use 'open' or 'owe' for networks with no passphrase");
        pw.println("           - 'open' - Open networks (Most prevalent)");
        pw.println("           - 'owe' - Enhanced open networks");
        pw.println("        - Use 'wpa2' or 'wpa3' for networks with passphrase");
        pw.println("           - 'wpa2' - WPA-2 PSK networks (Most prevalent)");
        pw.println("           - 'wpa3' - WPA-3 PSK networks");
        pw.println("           - 'wep'  - WEP network. Passphrase should be bytes in hex or encoded"
                + " into String using UTF-8");
        pw.println("    -x - Specifies the SSID as hex digits instead of plain text");
        pw.println("    -m - Mark the network metered.");
        pw.println("    -d - Mark the network autojoin disabled.");
        pw.println("    -h - Mark the network hidden.");
        pw.println("    -p - Mark the network private (not shared).");
        pw.println("    -b <bssid> - Set specific BSSID.");
        pw.println("    -r auto|none|persistent|non_persistent - MAC randomization scheme for the"
                + " network");
        pw.println("  list-scan-results");
        pw.println("    Lists the latest scan results");
        pw.println("  start-scan");
        pw.println("    Start a new scan");
        pw.println("  list-networks");
        pw.println("    Lists the saved networks");
        pw.println("  forget-network <networkId>");
        pw.println("    Remove the network mentioned by <networkId>");
        pw.println("        - Use list-networks to retrieve <networkId> for the network");
        pw.println("  status");
        pw.println("    Current wifi status");
        pw.println("  set-verbose-logging enabled|disabled [-l <verbose log level>]");
        pw.println("    Set the verbose logging enabled or disabled with log level");
        pw.println("      -l - verbose logging level");
        pw.println("           0 - verbose logging disabled");
        pw.println("           1 - verbose logging enabled");
        pw.println("           2 - verbose logging Show key mode");
        pw.println("           3 - verbose logging only for Wi-Fi Aware feature");
        pw.println("  is-verbose-logging");
        pw.println("    Check whether verbose logging enabled or disabled");
        pw.println("  start-restricting-auto-join-to-subscription-id subId");
        pw.println("    temporarily disable all wifi networks except merged carrier networks with"
                + " the given subId");
        pw.println("  stop-restricting-auto-join-to-subscription-id");
        pw.println("    Undo the effects of "
                + "start-restricting-auto-join-to-subscription-id");
        pw.println("  add-suggestion <ssid> open|owe|wpa2|wpa3 [<passphrase>] [-u] [-o] [-p] [-m] "
                + " [-s] [-d] [-b <bssid>] [-e] [-i] [-a <carrierId>] [-c <subscriptionId>]");
        pw.println("    Add a network suggestion with provided params");
        pw.println("    Use 'network-suggestions-set-user-approved " + SHELL_PACKAGE_NAME + " yes'"
                +  " to approve suggestions added via shell (Needs root access)");
        pw.println("    <ssid> - SSID of the network");
        pw.println("    open|owe|wpa2|wpa3 - Security type of the network.");
        pw.println("        - Use 'open' or 'owe' for networks with no passphrase");
        pw.println("           - 'open' - Open networks (Most prevalent)");
        pw.println("           - 'owe' - Enhanced open networks");
        pw.println("        - Use 'wpa2' or 'wpa3' for networks with passphrase");
        pw.println("           - 'wpa2' - WPA-2 PSK networks (Most prevalent)");
        pw.println("           - 'wpa3' - WPA-3 PSK networks");
        pw.println("    -u - Mark the suggestion untrusted.");
        pw.println("    -o - Mark the suggestion oem paid.");
        pw.println("    -p - Mark the suggestion oem private.");
        pw.println("    -m - Mark the suggestion metered.");
        pw.println("    -h - Mark the network hidden.");
        pw.println("    -s - Share the suggestion with user.");
        pw.println("    -d - Mark the suggestion autojoin disabled.");
        pw.println("    -b <bssid> - Set specific BSSID.");
        pw.println("    -r - Enable non_persistent randomization (disabled by default)");
        pw.println("    -a - Mark the suggestion carrier merged");
        pw.println("    -c <carrierId> - set carrier Id");
        pw.println("    -i <subscriptionId> - set subscription Id, if -a is used, "
                + "this must be set");
        pw.println("  remove-suggestion <ssid> [-l]");
        pw.println("    Remove a network suggestion with provided SSID of the network");
        pw.println("    -l - Remove suggestion with lingering, if not set will disconnect "
                + "immediately ");
        pw.println("  remove-all-suggestions");
        pw.println("    Removes all suggestions added via shell");
        pw.println("  list-suggestions");
        pw.println("    Lists the suggested networks added via shell");
        if (SdkLevel.isAtLeastS()) {
            pw.println("  set-coex-cell-channels [lte|nr <bandNumber 1-261> "
                    + "<downlinkFreqKhz or UNKNOWN: "
                    + PhysicalChannelConfig.FREQUENCY_UNKNOWN + "> "
                    + "<downlinkBandwidthKhz or UNKNOWN: "
                    + PhysicalChannelConfig.CELL_BANDWIDTH_UNKNOWN + "> "
                    + "<uplinkFreqKhz or UNKNOWN: "
                    + PhysicalChannelConfig.FREQUENCY_UNKNOWN + "> "
                    + "<uplinkBandwidthKhz or UNKNOWN: "
                    + PhysicalChannelConfig.CELL_BANDWIDTH_UNKNOWN + ">] ...");
            pw.println("    Sets a list of zero or more cell channels to use for coex calculations."
                    + " Actual device reported cell channels will be ignored until"
                    + " reset-coex-cell-channels is called.");
            pw.println("  reset-coex-cell-channels");
            pw.println("    Removes all cell channels set in set-coex-cell-channels and returns to "
                    + "listening on actual device reported cell channels");
            pw.println("  get-coex-cell-channels");
            pw.println("    Prints the cell channels being used for coex.");
        }
        pw.println("  set-connected-score <score>");
        pw.println("    Set connected wifi network score (to choose between LTE & Wifi for "
                + "default route).");
        pw.println("    This turns off the active connected scorer (default or external).");
        pw.println("    Only works while connected to a wifi network. This score will stay in "
                + "effect until you call reset-connected-score or the device disconnects from the "
                + "current network.");
        pw.println("    <score> - Integer score should be in the range of 0 - 60");
        pw.println("  reset-connected-score");
        pw.println("    Turns on the default connected scorer.");
        pw.println("    Note: Will clear any external scorer set.");
        pw.println("  pmksa-flush <networkId>");
        pw.println("        - Flush the local PMKSA cache associated with the network id."
                + " Use list-networks to retrieve <networkId> for the network");
        pw.println("  reload-resources");
        pw.println(
                "    Reset the WiFi resources cache which will cause them to be reloaded next "
                        + "time they are accessed. Necessary if overlays are manually modified.");
        pw.println("  launch-dialog-simple [-t <title>] [-m <message>]"
                + " [-l <url> <url_start> <url_end>] [-y <positive_button_text>]"
                + " [-n <negative_button_text>] [-x <neutral_button_text>] [-c <timeout_millis>]");
        pw.println("    Launches a simple dialog and waits up to 15 seconds to"
                + " print the response.");
        pw.println("    -t - Title");
        pw.println("    -m - Message");
        pw.println("    -l - URL of the message, with the start and end index inside the message");
        pw.println("    -y - Positive Button Text");
        pw.println("    -n - Negative Button Text");
        pw.println("    -x - Neutral Button Text");
        pw.println("    -c - Optional timeout in milliseconds");
        pw.println("    -s - Use the legacy dialog implementation on the system process");
        pw.println("  launch-dialog-p2p-invitation-sent <device_name> [-d <pin>]"
                + " [-i <display_id>]");
        pw.println("    Launches a P2P Invitation Sent dialog.");
        pw.println("    <device_name> - Name of the device the invitation was sent to");
        pw.println("    <pin> - PIN for the invited device to input");
        pw.println("  launch-dialog-p2p-invitation-received <device_name> [-p] [-d <pin>] "
                + "[-i <display_id>] [-c <timeout_millis>]");
        pw.println("    Launches a P2P Invitation Received dialog and waits up to 15 seconds to"
                + " print the response.");
        pw.println("    <device_name> - Name of the device sending the invitation");
        pw.println("    -p - Show PIN input");
        pw.println("    -d - Display PIN <pin>");
        pw.println("    -i - Display ID");
        pw.println("    -c - Optional timeout in milliseconds");
        pw.println("  query-interface <uid> <package_name> STA|AP|AWARE|DIRECT [-new]");
        pw.println(
                "    Query whether the specified could be created for the specified UID and "
                        + "package name, and if so - what other interfaces would be destroyed");
        pw.println("    -new - query for a new interfaces (otherwise an existing interface is ok");
        pw.println("  interface-priority-interactive-mode enable|disable|default");
        pw.println("    Enable or disable asking the user when there's an interface priority "
                + "conflict, |default| implies using the device default behavior.");
        pw.println("  set-one-shot-screen-on-delay-ms <delayMs>");
        pw.println("    set the delay for the next screen-on connectivity scan in milliseconds.");
        pw.println("  set-network-selection-config <enabled|disabled> <enabled|disabled> "
                + "-a <associated_network_selection_override>");
        pw.println("    set whether sufficiency check is enabled for screen off case "
                + "(first arg), and screen on case (second arg)");
        pw.println("    -a - set as one of the int "
                + "WifiNetworkSelectionConfig.ASSOCIATED_NETWORK_SELECTION_OVERRIDE_ values:");
        pw.println("      0 - no override");
        pw.println("      1 - override to enabled");
        pw.println("      2 - override to disabled");
        pw.println("  set-ipreach-disconnect enabled|disabled");
        pw.println("    Sets whether CMD_IP_REACHABILITY_LOST events should trigger disconnects.");
        pw.println("  get-ipreach-disconnect");
        pw.println("    Gets setting of CMD_IP_REACHABILITY_LOST events triggering disconnects.");
        pw.println("  take-bugreport");
        pw.println(
                "    take bugreport through betterBug. "
                        + "If it failed, take bugreport through bugreport manager.");
        pw.println("  get-allowed-channel [-b 1|6|7|8|15|16|31]");
        pw.println(
                "    get allowed channels in each operation mode from wifiManager if available. "
                        + "Otherwise, it returns from wifiScanner.");
        pw.println("    -b - set the band in which channels are allowed");
        pw.println("       '1'  - band 2.4 GHz");
        pw.println("       '6'  - band 5 GHz with DFS channels");
        pw.println("       '7'  - band 2.4 and 5 GHz with DFS channels");
        pw.println("       '8'  - band 6 GHz");
        pw.println("       '15' - band 2.4, 5, and 6 GHz with DFS channels");
        pw.println("       '16' - band 60 GHz");
        pw.println("       '31' - band 2.4, 5, 6 and 60 GHz with DFS channels");
        pw.println("  get-cached-scan-data");
        pw.println("    Gets scan data cached by the firmware");
        pw.println("  force-overlay-config-value bool|integer|string|integer-array|string-array "
                + "<overlayName> enabled|disabled <configValue>");
        pw.println("    Force overlay to a specified value.");
        pw.println("    bool|integer|string|integer-array|string-array - specified the type of the "
                + "overlay");
        pw.println("    <overlayName> - name of the overlay whose value is overridden.");
        pw.println("    enabled|disabled: enable the override or disable it and revert to using "
                + "the built-in value.");
        pw.println("    <configValue> - override value of the overlay."
                + "Must match the overlay type");
        pw.println("  get-overlay-config-values");
        pw.println("    Get current overlay value in resource cache.");
        pw.println("  get-softap-supported-features");
        pw.println("    Gets softap supported features. Will print 'wifi_softap_acs_supported'");
        pw.println("    and/or 'wifi_softap_wpa3_sae_supported',");
        pw.println("    and/or 'wifi_softap_bridged_ap_supported',");
        pw.println("    and/or 'wifi_softap_bridged_ap_with_sta_supported',");
        pw.println("    and/or 'wifi_softap_mlo_supported',");
        pw.println("    each on a separate line.");
        pw.println("  get-wifi-supported-features");
        pw.println("    Gets the features supported by WifiManager");
    }

    private void onHelpPrivileged(PrintWriter pw) {
        pw.println("  set-poll-rssi-interval-msecs <int> [<int>]");
        pw.println("    Sets the interval between RSSI polls to the specified value(s), in "
                + "milliseconds.");
        pw.println("    When only one value is specified, set the interval to that value. "
                + "When two values are specified, set the regular (short) interval to the first "
                + "value, and set the long interval to the second value. Note that the "
                + "enabling/disabling of auto adjustment between the two intervals is handled by "
                + "the respective flags. If the auto adjustment is disabled, it is equivalent to "
                + "only specifying the first value, and then setting the interval to that value");
        pw.println("  get-poll-rssi-interval-msecs");
        pw.println("    Gets current interval between RSSI polls, in milliseconds.");
        pw.println("  force-hi-perf-mode enabled|disabled");
        pw.println("    Sets whether hi-perf mode is forced or left for normal operation.");
        pw.println("  force-low-latency-mode enabled|disabled");
        pw.println("    Sets whether low latency mode is forced or left for normal operation.");
        pw.println("  network-suggestions-set-user-approved <package name> yes|no");
        pw.println("    Sets whether network suggestions from the app is approved or not.");
        pw.println("  network-suggestions-has-user-approved <package name>");
        pw.println("    Queries whether network suggestions from the app is approved or not.");
        pw.println("  imsi-protection-exemption-set-user-approved-for-carrier <carrier id> yes|no");
        pw.println("    Sets whether Imsi protection exemption for carrier is approved or not");
        pw.println("  imsi-protection-exemption-has-user-approved-for-carrier <carrier id>");
        pw.println("    Queries whether Imsi protection exemption for carrier is approved or not");
        pw.println("  imsi-protection-exemption-clear-user-approved-for-carrier <carrier id>");
        pw.println("    Clear the user choice on Imsi protection exemption for carrier");
        pw.println("  network-requests-remove-user-approved-access-points <package name>");
        pw.println("    Removes all user approved network requests for the app.");
        pw.println("  clear-user-disabled-networks");
        pw.println("    Clears the user disabled networks list.");
        pw.println("  send-link-probe");
        pw.println("    Manually triggers a link probe.");
        pw.println(
                "  start-softap <ssid> (open|wpa2|wpa3|wpa3_transition|owe|owe_transition)"
                        + " <passphrase> [-b 2|5|6|any|bridged|bridged_2_5|bridged_2_6|bridged_5_6]"
                        + " [-x] [-w 20|40|80|160|320] [-f <int> [<int>]]");
        pw.println("    Start softap with provided params");
        pw.println("    Note that the shell command doesn't activate internet tethering. In some "
                + "devices, internet sharing is possible when Wi-Fi STA is also enabled and is"
                + "associated to another AP with internet access.");
        pw.println("    <ssid> - SSID of the network");
        pw.println("    open|wpa2|wpa3|wpa3_transition|owe|owe_transition - Security type of the "
                + "network.");
        pw.println("        - Use 'open', 'owe', 'owe_transition' for networks with no passphrase");
        pw.println("        - Use 'wpa2', 'wpa3', 'wpa3_transition' for networks with passphrase");
        pw.println("    -b 2|5|6|any|bridged|bridged_2_5|bridged_2_6|bridged_5_6 - select the"
                + " preferred bands.");
        pw.println("        - Use '2' to select 2.4GHz band as the preferred band");
        pw.println("        - Use '5' to select 5GHz band as the preferred band");
        pw.println("        - Use '6' to select 6GHz band as the preferred band");
        pw.println("        - Use 'any' to indicate no band preference");
        pw.println("        - Use 'bridged' to indicate bridged AP which enables APs on both "
                + "2.4G + 5G");
        pw.println("        - Use 'bridged_2_5' to indicate bridged AP which enables APs on both "
                + "2.4G + 5G");
        pw.println("        - Use 'bridged_2_6' to indicate bridged AP which enables APs on both "
                + "2.4G + 6G");
        pw.println("        - Use 'bridged_5_6' to indicate bridged AP which enables APs on both "
                + "5G + 6G");
        pw.println("    Note: If the band option is not provided, 2.4GHz is the preferred band.");
        pw.println("          The exact channel is auto-selected by FW unless overridden by "
                + "force-softap-channel command or '-f <int> <int>' option");
        pw.println("    -f <int> <int> - force exact channel frequency for operation channel");
        pw.println("    Note: -f <int> <int> - must be the last option");
        pw.println("          For example:");
        pw.println("          Use '-f 2412' to enable single Soft Ap on 2412");
        pw.println("          Use '-f 2412 5745' to enable bridged dual Soft Ap on 2412 and 5745");
        pw.println("    -x - Specifies the SSID as hex digits instead of plain text (T and above)");
        pw.println("    -w 20|40|80|160|320 - select the maximum channel bandwidth (MHz)");
        pw.println("  stop-softap");
        pw.println("    Stop softap (hotspot)");
        pw.println("  force-softap-band enabled <int> | disabled");
        pw.println("    Forces soft AP band to 2|5|6");
        pw.println("  force-softap-channel enabled <int> | disabled [-w <maxBandwidth>]");
        pw.println("    Sets whether soft AP channel is forced to <int> MHz [-w <maxBandwidth>]");
        pw.println("        -w 0|20|40|80|160|320 - select the maximum channel bandwidth (MHz)");
        pw.println("         Note: If the bandwidth option is not provided or set to 0, framework"
                + " will set the maximum bandwidth to auto, allowing HAL to select the bandwidth");
        pw.println("    or left for normal   operation.");
        pw.println("  force-country-code enabled <two-letter code> | disabled ");
        pw.println("    Sets country code to <two-letter code> or left for normal value");
        pw.println("    or '00' for forcing to world mode country code");
        pw.println("  set-wifi-watchdog enabled|disabled");
        pw.println("    Sets whether wifi watchdog should trigger recovery");
        pw.println("  get-wifi-watchdog");
        pw.println("    Gets setting of wifi watchdog trigger recovery.");
        pw.println("  settings-reset");
        pw.println("    Initiates wifi settings reset");
        pw.println("  allow-root-to-get-local-only-cmm enabled|disabled");
        pw.println("    sets whether the shell running as root could use the local-only secondary "
                + "STA");
        pw.println("  add-request [-g] [-i] [-n] [-s] <ssid> open|owe|wpa2|wpa3 [<passphrase>]"
                + " [-b <bssid>] [-d <band=2|5|6|60>]");
        pw.println("    Add a network request with provided params");
        pw.println("    Use 'network-requests-set-user-approved android yes'"
                +  " to pre-approve requests added via rooted shell (Not persisted)");
        pw.println("    -g - Marks the following SSID as a glob pattern");
        pw.println("    <ssid> - SSID of the network, or glob pattern if -g is present");
        pw.println("    open|owe|wpa2|wpa3 - Security type of the network.");
        pw.println("        - Use 'open' or 'owe' for networks with no passphrase");
        pw.println("           - 'open' - Open networks (Most prevalent)");
        pw.println("           - 'owe' - Enhanced open networks");
        pw.println("        - Use 'wpa2' or 'wpa3' for networks with passphrase");
        pw.println("           - 'wpa2' - WPA-2 PSK networks (Most prevalent)");
        pw.println("           - 'wpa3' - WPA-3 PSK networks");
        pw.println("    -b <bssid> - Set specific BSSID.");
        pw.println("    -i Set internet capability.");
        pw.println("    -d Specify the band of access point: 2, 5, 6, or 60");
        pw.println("    -s No SSID provided, to be chosen by network selection.");
        pw.println("    -n - Prevent auto-selection of BSSID and force it to be null so that the "
                + "request matches all BSSIDs.");
        pw.println("  remove-request <ssid>");
        pw.println("    Remove a network request with provided SSID of the network");
        pw.println("  remove-all-requests");
        pw.println("    Removes all active requests added via shell");
        pw.println("  list-requests");
        pw.println("    Lists the requested networks added via shell");
        pw.println("  network-requests-set-user-approved <package name> yes|no");
        pw.println("    Sets whether network requests from the app is approved or not.");
        pw.println("    Note: Only 1 such app can be approved from the shell at a time");
        pw.println("  network-requests-has-user-approved <package name>");
        pw.println("    Queries whether network requests from the app is approved or not.");
        pw.println("    Note: This only returns whether the app was set via the "
                + "'network-requests-set-user-approved' shell command");
        pw.println("  list-all-suggestions");
        pw.println("    Lists all suggested networks on this device");
        pw.println("  list-suggestions-from-app <package name>");
        pw.println("    Lists the suggested networks from the app");
        pw.println("  clear-all-suggestions");
        pw.println("    Clear all suggestions added into this device");
        pw.println("  set-emergency-callback-mode enabled|disabled");
        pw.println("    Sets whether Emergency Callback Mode (ECBM) is enabled.");
        pw.println("    Equivalent to receiving the "
                + "TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED broadcast.");
        pw.println("  set-emergency-call-state enabled|disabled");
        pw.println("    Sets whether we are in the middle of an emergency call.");
        pw.println("Equivalent to receiving the "
                + "TelephonyManager.ACTION_EMERGENCY_CALL_STATE_CHANGED broadcast.");
        pw.println("  set-emergency-scan-request enabled|disabled");
        pw.println("    Sets whether there is a emergency scan request in progress.");
        pw.println("  network-suggestions-set-as-carrier-provider <packageName> yes|no");
        pw.println("    Set the <packageName> work as carrier provider or not.");
        pw.println("  is-network-suggestions-set-as-carrier-provider <packageName>");
        pw.println("    Queries whether the <packageName> is working as carrier provider or not.");
        pw.println("  remove-app-from-suggestion_database <packageName>");
        pw.println("    Remove <packageName> from the suggestion database, all suggestions and user"
                + " approval will be deleted, it is the same as uninstalling this app.");
        pw.println("  trigger-recovery");
        pw.println("    Trigger Wi-Fi subsystem restart.");
        pw.println("  start-faking-scans");
        pw.println("    Start faking scan results into the framework (configured with "
                + "'add-fake-scan'), stop with 'stop-faking-scans'.");
        pw.println("  stop-faking-scans");
        pw.println("    Stop faking scan results - started with 'start-faking-scans'.");
        pw.println("  add-fake-scan [-x] <ssid> <bssid> <capabilities> <frequency> <dbm>");
        pw.println("    Add a fake scan result to be used when enabled via `start-faking-scans'.");
        pw.println("    Example WPA2: add-fake-scan fakeWpa2 80:01:02:03:04:05 "
                + "\"[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]\" 2412 -55");
        pw.println("    Example WPA3: add-fake-scan fakeWpa3 80:01:02:03:04:06 "
                + "\"[RSN-SAE+FT/SAE-CCMP][ESS]\" 2412 -55");
        pw.println(
                "    Example Open: add-fake-scan fakeOpen 80:01:02:03:04:07 \"[ESS]\" 2412 -55");
        pw.println("    Example OWE: add-fake-scan fakeOwe 80:01:02:03:04:08 \"[RSN-OWE-CCMP]\" "
                + "2412 -55");
        pw.println(
                "    Example WPA2/WPA3 transition mode: add-fake-scan fakeWpa2t3 80:01:02:03:04:09 "
                        + "\"[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS][MFPC]\" 2412 -55");
        pw.println(
                "    Example Open/OWE transition mode: add-fake-scan fakeOpenOwe 80:01:02:03:04:0A "
                        + "\"[RSN-OWE_TRANSITION-CCMP][ESS]\" 2412 -55");
        pw.println(
                "    Example Passpoint: add-fake-scan fakePasspoint 80:01:02:03:04:0B "
                        + "\"[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS][MFPR][MFPC]"
                        + "[PASSPOINT]\" 2412 -55");
        pw.println("    -x - Specifies the SSID as hex digits instead of plain text");
        pw.println("  reset-fake-scans");
        pw.println("    Resets all fake scan results added by 'add-fake-scan'.");
        pw.println("  enable-scanning enabled|disabled [-h]");
        pw.println("    Sets whether all scanning should be enabled or disabled");
        pw.println("    -h - Enable scanning for hidden networks.");
        pw.println("  set-passpoint-enabled enabled|disabled");
        pw.println("    Sets whether Passpoint should be enabled or disabled");
        pw.println(
                "  start-lohs <ssid> (open|wpa2|wpa3|wpa3_transition|owe|owe_transition)"
                        + " <passphrase> [-b 2|5|6|any|bridged|bridged_2_5|bridged_2_6|bridged_5_6]"
                        + " [-x] [-w 20|40|80|160|320] [-f <int> [<int>]])");
        pw.println("    Start local only softap (hotspot) with provided params");
        pw.println("    <ssid> - SSID of the network");
        pw.println("    open|wpa2|wpa3|wpa3_transition|owe|owe_transition - Security type of the "
                + "network.");
        pw.println("        - Use 'open', 'owe', 'owe_transition' for networks with no passphrase");
        pw.println("        - Use 'wpa2', 'wpa3', 'wpa3_transition' for networks with passphrase");
        pw.println("    -b 2|5|6|any|bridged|bridged_2_5|bridged_2_6|bridged_5_6 - select the "
                + "preferred bands.");
        pw.println("        - Use '2' to select 2.4GHz band as the preferred band");
        pw.println("        - Use '5' to select 5GHz band as the preferred band");
        pw.println("        - Use '6' to select 6GHz band as the preferred band");
        pw.println("        - Use 'any' to indicate no band preference");
        pw.println("        - Use 'bridged' to indicate bridged AP which enables APs on both "
                + "2.4G + 5G");
        pw.println("        - Use 'bridged_2_5' to indicate bridged AP which enables APs on both "
                + "2.4G + 5G");
        pw.println("        - Use 'bridged_2_6' to indicate bridged AP which enables APs on both "
                + "2.4G + 6G");
        pw.println("        - Use 'bridged_5_6' to indicate bridged AP which enables APs on both "
                + "5G + 6G");
        pw.println("    Note: If the band option is not provided, 2.4GHz is the preferred band.");
        pw.println("          The exact channel is auto-selected by FW unless overridden by "
                + "force-softap-channel command or '-f <int> <int>' option");
        pw.println("    -f <int> <int> - force exact channel frequency for operation channel");
        pw.println("    Note: -f <int> <int> - must be the last option");
        pw.println("          For example:");
        pw.println("          Use '-f 2412' to enable single Soft Ap on 2412");
        pw.println("          Use '-f 2412 5745' to enable bridged dual lohs on 2412 and 5745");
        pw.println("    -x - Specifies the SSID as hex digits instead of plain text (T and above)");
        pw.println("    -w 20|40|80|160|320 - select the maximum bandwidth (MHz)");
        pw.println("  stop-softap");
        pw.println("    Stop softap (hotspot)");
        pw.println("    Note: If the band option is not provided, 2.4GHz is the preferred band.");
        pw.println("  stop-lohs");
        pw.println("    Stop local only softap (hotspot)");
        pw.println("  set-multi-internet-mode 0|1|2");
        pw.println("    Sets Multi Internet use case mode. 0-disabled 1-dbs 2-multi ap");
        pw.println("  set-pno-request <ssid> [-f <frequency>]");
        pw.println("    Requests to include a non-quoted UTF-8 SSID in PNO scans");
        pw.println("  clear-pno-request");
        pw.println("    Clear the PNO scan request.");
        pw.println("  set-pno-scan enabled|disabled");
        pw.println("    Set the PNO scan enabled or disabled.");
        pw.println("  start-dpp-enrollee-responder [-i <info>] [-c <curve>]");
        pw.println("    Start DPP Enrollee responder mode.");
        pw.println("    -i - Device Info to be used in DPP Bootstrapping URI");
        pw.println("    -c - Cryptography Curve integer 1:p256v1, 2:s384r1, etc");
        pw.println("  start-dpp-configurator-initiator <networkId> <netRole> <enrolleeURI>");
        pw.println("    Start DPP Configurator Initiator mode.");
        pw.println("    netRole - 0: STA, 1: AP");
        pw.println("    enrolleeURI - Bootstrapping URI received from Enrollee");
        pw.println("  stop-dpp");
        pw.println("    Stop DPP session.");
        pw.println("  set-ssid-charset <locale_language> <charset_name>");
        pw.println("    Sets the SSID translation charset for the given locale language.");
        pw.println("    Example: set-ssid-charset zh GBK");
        pw.println("  clear-ssid-charsets");
        pw.println("    Clears the SSID translation charsets set in set-ssid-charset.");
        pw.println("  get-last-caller-info api_type");
        pw.println("    Get the last caller information for a WifiManager.ApiType");
        pw.println("  trigger-afc-location-update <longitude> <latitude> <height>");
        pw.println("    Passes in longitude, latitude, and height values as arguments of type "
                + "double for a fake location update to trigger framework logic to query the AFC "
                + "server. The longitude and latitude pair is in decimal degrees and the height"
                + " is the altitude in meters. The server URL needs to have been previously set "
                + "with the configure-afc-server shell command.");
        pw.println("    Example: trigger-afc-location-update 37.425056 -122.984157 3.043");
        pw.println("  set-afc-channel-allowance -e <secs_until_expiry> [-f <low_freq>,<high_freq>,"
                + "<psd>:...|none] [-c <operating_class>,<channel_cfi>,<max_eirp>:...|none]");
        pw.println("    Sets the allowed AFC channels and frequencies.");
        pw.println("    -e - Seconds until the availability expires.");
        pw.println("    -f - Colon-separated list of available frequency info.");
        pw.println("      Note: each frequency should contain 3 comma separated values, where "
                + "the first is the low frequency (MHz), the second the high frequency (MHz), the "
                + "third the max PSD (dBm per MHz). To set an empty frequency list, enter \"none\" "
                + "in place of the list of allowed frequencies.");
        pw.println("    -c - Colon-separated list of available channel info.");
        pw.println("      Note: each channel should contain 3 comma separated values, where "
                + "the first is the global operating class, the second the channel CFI, "
                + "the third the max EIRP in dBm. To set an empty channel list, enter \"none\" in "
                + "place of the list of allowed channels.");
        pw.println("    Example: set-afc-channel-allowance -e 30 -c none -f "
                + "5925,6020,23:6020,6050,1");
        pw.println("  configure-afc-server <url> [-r [<request property key> <request property "
                + "value>] ...]");
        pw.println("    Sets the server URL and request properties for the HTTP request which the "
                + "AFC Client will query.");
        pw.println("    -r - HTTP header fields that come in pairs of key and value which are added"
                + " to the HTTP request. Must be an even number of arguments. If there is no -r "
                + "option provided or no arguments provided after the -r option, then set the "
                + "request properties to none in the request.");
        pw.println("    Example: configure-afc-server https://testURL -r key1 value1 key2 value2");
        pw.println("  set-ssid-roaming-mode <ssid> none|normal|aggressive [-x]");
        pw.println("    Sets the roaming mode for the given SSID.");
        pw.println("    -x - Specifies the SSID as hex digits instead of plain text.");
        pw.println("    Example: set-ssid-roaming-mode test_ssid aggressive");
        pw.println("  set-scan-throttling-enabled enabled|disabled");
        pw.println("    Set wifi scan throttling for 3P apps enabled or disabled.");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Wi-Fi (wifi) commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        onHelpNonPrivileged(pw);
        if (Binder.getCallingUid() == Process.ROOT_UID) {
            onHelpPrivileged(pw);
        }
        pw.println();
    }

    private void printWifiNetworkSuggestions(PrintWriter pw,
            Collection<WifiNetworkSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            pw.println("No suggestions on this device");
        } else {
            if (SdkLevel.isAtLeastS()) {
                /*
                 * Print out SubId on S and above because WifiNetworkSuggestion.getSubscriptionId()
                 * is supported from Android S and above.
                 */
                String format = "%-24s %-24s %-12s %-12s";
                pw.println(String.format(format, "SSID", "Security type(s)", "CarrierId", "SubId"));
                for (WifiNetworkSuggestion suggestion : suggestions) {
                    pw.println(String.format(format,
                            WifiInfo.sanitizeSsid(suggestion.getWifiConfiguration().SSID),
                            suggestion.getWifiConfiguration().getSecurityParamsList().stream()
                                    .map(p -> WifiConfiguration.getSecurityTypeName(
                                            p.getSecurityType())
                                            + (p.isAddedByAutoUpgrade() ? "^" : ""))
                                    .collect(Collectors.joining("/")),
                            suggestion.getCarrierId(), suggestion.getSubscriptionId()));
                }
            } else {
                String format = "%-24s %-24s %-12s";
                pw.println(String.format(format, "SSID", "Security type(s)", "CarrierId"));
                for (WifiNetworkSuggestion suggestion : suggestions) {
                    pw.println(String.format(format,
                            WifiInfo.sanitizeSsid(suggestion.getWifiConfiguration().SSID),
                            suggestion.getWifiConfiguration().getSecurityParamsList().stream()
                                    .map(p -> WifiConfiguration.getSecurityTypeName(
                                            p.getSecurityType())
                                            + (p.isAddedByAutoUpgrade() ? "^" : ""))
                                    .collect(Collectors.joining("/")),
                            suggestion.getCarrierId()));
                }
            }
        }
    }
}
