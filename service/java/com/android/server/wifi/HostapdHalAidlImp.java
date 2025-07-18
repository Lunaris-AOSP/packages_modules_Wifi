/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.hardware.wifi.hostapd.ApInfo;
import android.hardware.wifi.hostapd.BandMask;
import android.hardware.wifi.hostapd.ChannelBandwidth;
import android.hardware.wifi.hostapd.ChannelParams;
import android.hardware.wifi.hostapd.ClientInfo;
import android.hardware.wifi.hostapd.DebugLevel;
import android.hardware.wifi.hostapd.EncryptionType;
import android.hardware.wifi.hostapd.FrequencyRange;
import android.hardware.wifi.hostapd.Generation;
import android.hardware.wifi.hostapd.HwModeParams;
import android.hardware.wifi.hostapd.IHostapd;
import android.hardware.wifi.hostapd.IHostapdCallback;
import android.hardware.wifi.hostapd.Ieee80211ReasonCode;
import android.hardware.wifi.hostapd.IfaceParams;
import android.hardware.wifi.hostapd.NetworkParams;
import android.net.MacAddress;
import android.net.wifi.DeauthenticationReasonCode;
import android.net.wifi.OuiKeyedData;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApConfiguration.BandType;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiManager;
import android.net.wifi.util.Environment;
import android.net.wifi.util.WifiResourceCache;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.WifiNative.HostapdDeathEventHandler;
import com.android.server.wifi.WifiNative.SoftApHalCallback;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.HalAidlUtil;
import com.android.server.wifi.util.NativeUtil;
import com.android.wifi.flags.Flags;
import com.android.wifi.resources.R;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.ThreadSafe;

/**
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
/** The implementation of IHostapdHal which based on Stable AIDL interface */
public class HostapdHalAidlImp implements IHostapdHal {
    private static final String TAG = "HostapdHalAidlImp";
    private static final String HAL_INSTANCE_NAME = IHostapd.DESCRIPTOR + "/default";
    @VisibleForTesting
    public static final long WAIT_FOR_DEATH_TIMEOUT_MS = 50L;

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;
    private boolean mVerboseHalLoggingEnabled = false;
    private final WifiContext mContext;
    private final Handler mEventHandler;

    // Hostapd HAL interface objects
    private IHostapd mIHostapd;
    private HashMap<String, Runnable> mSoftApFailureListeners = new HashMap<>();
    private HashMap<String, SoftApHalCallback> mSoftApHalCallbacks = new HashMap<>();
    private Set<String> mActiveInstances = new HashSet<>();
    private HostapdDeathEventHandler mDeathEventHandler;
    private boolean mServiceDeclared = false;
    private int mServiceVersion;
    private CountDownLatch mWaitForDeathLatch;
    private final WifiResourceCache mResourceCache;

    /**
     * Default death recipient. Called any time the service dies.
     */
    private class HostapdDeathRecipient implements DeathRecipient {
        private final IBinder mWho;
        @Override
        /* Do nothing as we override the default function binderDied(IBinder who). */
        public void binderDied() {
            synchronized (mLock) {
                Log.w(TAG, "IHostapd/IHostapd died. who " + mWho + " service "
                        + getServiceBinderMockable());
                if (mWho == getServiceBinderMockable()) {
                    if (mWaitForDeathLatch != null) {
                        mWaitForDeathLatch.countDown();
                    }
                    mEventHandler.post(() -> {
                        synchronized (mLock) {
                            Log.w(TAG, "Handle IHostapd/IHostapd died.");
                            hostapdServiceDiedHandler(mWho);
                        }
                    });
                }
            }
        }

        HostapdDeathRecipient(IBinder who) {
            mWho = who;
        }
    }

    public HostapdHalAidlImp(@NonNull WifiContext context, @NonNull Handler handler) {
        mContext = context;
        mEventHandler = handler;
        mResourceCache = mContext.getResourceCache();
        Log.d(TAG, "init HostapdHalAidlImp");
    }

    /**
     * Enable/Disable verbose logging.
     *
     */
    @Override
    public void enableVerboseLogging(boolean verboseEnabled, boolean halVerboseEnabled) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = verboseEnabled;
            mVerboseHalLoggingEnabled = halVerboseEnabled;
            setDebugParams();
        }
    }

    /**
     * Returns whether or not the hostapd supports getting the AP info from the callback.
     */
    @Override
    public boolean isApInfoCallbackSupported() {
        // Supported in the AIDL implementation
        return true;
    }

    /**
     * Checks whether the IHostapd service is declared, and therefore should be available.
     * @return true if the IHostapd service is declared
     */
    @Override
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Checking if IHostapd service is declared.");
            }
            mServiceDeclared = serviceDeclared();
            return mServiceDeclared;
        }
    }

    /**
     * Register for callbacks with the hostapd service. On service-side event,
     * the hostapd service will trigger our IHostapdCallback implementation, which
     * in turn calls the proper SoftApHalCallback registered with us by WifiNative.
     */
    private boolean registerCallback(IHostapdCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerCallback";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return false;
            }
            try {
                mIHostapd.registerCallback(callback);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Register the provided callback handler for SoftAp events on the specified iface.
     * <p>
     * Note that only one callback can be registered per iface at a time - any registration on the
     * same iface overrides previous registrations.
     *
     * @param ifaceName Name of the interface.
     * @param listener Callback listener for AP events.
     * @return true on success, false on failure.
     */
    @Override
    public boolean registerApCallback(@NonNull String ifaceName,
            @NonNull SoftApHalCallback callback) {
        // TODO(b/195980798) : Create a hashmap to associate the listener with the ifaceName
        synchronized (mLock) {
            if (callback == null) {
                Log.e(TAG, "registerApCallback called with a null callback");
                return false;
            }
            mSoftApHalCallbacks.put(ifaceName, callback);
            Log.i(TAG, "registerApCallback Successful in " + ifaceName);
            return true;
        }
    }

    /**
     * Add and start a new access point.
     *
     * @param ifaceName Name of the interface.
     * @param config Configuration to use for the AP.
     * @param isMetered Indicates if the network is metered or not.
     * @param onFailureListener A runnable to be triggered on failure.
     * @return true on success, false otherwise.
     */
    @Override
    public boolean addAccessPoint(@NonNull String ifaceName, @NonNull SoftApConfiguration config,
            boolean isMetered, boolean isUsingMultiLinkOperation, List<String> instanceIdentities,
            Runnable onFailureListener) {
        synchronized (mLock) {
            final String methodStr = "addAccessPoint";
            Log.d(TAG, methodStr + ": " + ifaceName);
            if (!checkHostapdAndLogFailure(methodStr)) {
                return false;
            }
            try {
                IfaceParams ifaceParams = prepareIfaceParams(ifaceName, config,
                        isUsingMultiLinkOperation, instanceIdentities);
                NetworkParams nwParams = prepareNetworkParams(isMetered, config);
                if (ifaceParams == null || nwParams == null) {
                    Log.e(TAG, "addAccessPoint parameters could not be prepared.");
                    return false;
                }
                mIHostapd.addAccessPoint(ifaceParams, nwParams);
                mSoftApFailureListeners.put(ifaceName, onFailureListener);
                return true;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Unrecognized apBand: " + config.getBand());
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Remove a previously started access point.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    @Override
    public boolean removeAccessPoint(@NonNull String ifaceName) {
        synchronized (mLock) {
            final String methodStr = "removeAccessPoint";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return false;
            }
            try {
                mSoftApFailureListeners.remove(ifaceName);
                mSoftApHalCallbacks.remove(ifaceName);
                mIHostapd.removeAccessPoint(ifaceName);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Remove a previously connected client.
     *
     * @param ifaceName Name of the interface.
     * @param client Mac Address of the client.
     * @param reasonCode One of disconnect reason code which defined in {@link WifiManager}.
     * @return true on success, false otherwise.
     */
    @Override
    public boolean forceClientDisconnect(@NonNull String ifaceName,
            @NonNull MacAddress client, int reasonCode) {
        synchronized (mLock) {
            final String methodStr = "forceClientDisconnect";
            try {
                if (!checkHostapdAndLogFailure(methodStr)) {
                    return false;
                }
                byte[] clientMacByteArray = client.toByteArray();
                int disconnectReason;
                switch (reasonCode) {
                    case WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_PREV_AUTH_NOT_VALID;
                        break;
                    case WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_DISASSOC_AP_BUSY;
                        break;
                    case WifiManager.SAP_CLIENT_DISCONNECT_REASON_CODE_UNSPECIFIED:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_UNSPECIFIED;
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown disconnect reason code:" + reasonCode);
                }
                mIHostapd.forceClientDisconnect(ifaceName, clientMacByteArray, disconnectReason);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Registers a death notification for hostapd.
     * @return Returns true on success.
     */
    @Override
    public boolean registerDeathHandler(@NonNull HostapdDeathEventHandler handler) {
        synchronized (mLock) {
            if (mDeathEventHandler != null) {
                Log.e(TAG, "Death handler already present");
            }
            mDeathEventHandler = handler;
            return true;
        }
    }

    /**
     * Deregisters a death notification for hostapd.
     * @return Returns true on success.
     */
    @Override
    public boolean deregisterDeathHandler() {
        synchronized (mLock) {
            if (mDeathEventHandler == null) {
                Log.e(TAG, "No Death handler present");
                return false;
            }
            mDeathEventHandler = null;
            return true;
        }
    }

    /**
     * Handle hostapd death.
     */
    private void hostapdServiceDiedHandler(IBinder who) {
        synchronized (mLock) {
            if (who != getServiceBinderMockable()) {
                Log.w(TAG, "Ignoring stale death recipient notification");
                return;
            }
            mIHostapd = null;
            if (mDeathEventHandler != null) {
                mDeathEventHandler.onDeath();
            }
        }
    }

    private class HostapdCallback extends IHostapdCallback.Stub {
        @Override
        public void onFailure(String ifaceName, String instanceName) {
            Log.w(TAG, "Failure on iface " + ifaceName + ", instance: " + instanceName);
            Runnable onFailureListener = mSoftApFailureListeners.get(ifaceName);
            if (onFailureListener != null && ifaceName != null) {
                if (ifaceName.equals(instanceName)) {
                    // Single AP
                    onFailureListener.run();
                } else {
                    // Bridged AP
                    if (mActiveInstances.contains(instanceName)) {
                        SoftApHalCallback callback = mSoftApHalCallbacks.get(ifaceName);
                        if (callback != null) {
                            callback.onInstanceFailure(instanceName);
                        }
                    } else {
                        Log.w(TAG, "Ignore error for inactive instances");

                    }
                }
                mActiveInstances.remove(instanceName);
            }
        }

        @Override
        public void onApInstanceInfoChanged(ApInfo info) {
            Log.v(TAG, "onApInstanceInfoChanged on " + info.ifaceName + " / "
                    + info.apIfaceInstance);
            try {
                SoftApHalCallback callback = mSoftApHalCallbacks.get(info.ifaceName);
                if (callback != null) {
                    List<OuiKeyedData> vendorData = isServiceVersionAtLeast(2)
                            ? HalAidlUtil.halToFrameworkOuiKeyedDataList(info.vendorData)
                            : Collections.emptyList();
                    callback.onInfoChanged(info.apIfaceInstance, info.freqMhz,
                            mapHalChannelBandwidthToSoftApInfo(info.channelBandwidth),
                            mapHalGenerationToWifiStandard(info.generation),
                            MacAddress.fromBytes(info.apIfaceInstanceMacAddress),
                            (Flags.mloSap() && info.mldMacAddress != null)
                                    ? MacAddress.fromBytes(info.mldMacAddress) : null,
                            vendorData);
                }
                mActiveInstances.add(info.apIfaceInstance);
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, " Invalid apIfaceInstanceMacAddress, " + iae);
            }
        }

        @Override
        public void onConnectedClientsChanged(ClientInfo info) {
            try {
                Log.d(TAG, "onConnectedClientsChanged on " + info.ifaceName
                        + " / " + info.apIfaceInstance
                        + " and Mac is " + MacAddress.fromBytes(info.clientAddress).toString()
                        + " isConnected: " + info.isConnected);
                SoftApHalCallback callback = mSoftApHalCallbacks.get(info.ifaceName);
                if (callback != null) {
                    int disconnectReasonCode = isServiceVersionAtLeast(3) && !info.isConnected
                            ? mapHalToFrameworkDeauthenticationReasonCode(info.disconnectReasonCode)
                            : DeauthenticationReasonCode.REASON_UNKNOWN;
                    callback.onConnectedClientsChanged(info.apIfaceInstance,
                            MacAddress.fromBytes(info.clientAddress), info.isConnected,
                            disconnectReasonCode);
                }
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, " Invalid clientAddress, " + iae);
            }
        }

        @Override
        public String getInterfaceHash() {
            return IHostapdCallback.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return IHostapdCallback.VERSION;
        }
    }

    /**
     * Signals whether Initialization started and found the declared service
     */
    @Override
    public boolean isInitializationStarted() {
        synchronized (mLock) {
            return mServiceDeclared;
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    @Override
    public boolean isInitializationComplete() {
        synchronized (mLock) {
            return mIHostapd != null;
        }
    }

    /**
     * Indicates whether the AIDL service is declared
     */
    public static boolean serviceDeclared() {
        // Service Manager API ServiceManager#isDeclared supported after T.
        if (!SdkLevel.isAtLeastT()) {
            return false;
        }
        return ServiceManager.isDeclared(HAL_INSTANCE_NAME);
    }

    /**
     * Check that the service is running at least the expected version. Use to avoid the case where
     * the framework is using a newer interface version than the service.
     */
    private boolean isServiceVersionAtLeast(int expectedVersion) {
        return expectedVersion <= mServiceVersion;
    }

    /**
     * Wrapper functions created to be mockable in unit tests
     */
    @VisibleForTesting
    protected IBinder getServiceBinderMockable() {
        synchronized (mLock) {
            if (mIHostapd == null) return null;
            return mIHostapd.asBinder();
        }
    }

    @VisibleForTesting
    protected IHostapd getHostapdMockable() {
        synchronized (mLock) {
            if (SdkLevel.isAtLeastT()) {
                return IHostapd.Stub.asInterface(
                        ServiceManager.waitForDeclaredService(HAL_INSTANCE_NAME));
            }
            return null;
        }
    }

    /**
     * Start hostapd daemon
     *
     * @return true when succeed, otherwise false.
     */
    @Override
    public boolean startDaemon() {
        synchronized (mLock) {
            final String methodStr = "startDaemon";
            mIHostapd = getHostapdMockable();
            if (mIHostapd == null) {
                Log.e(TAG, "Service hostapd wasn't found.");
                return false;
            }
            Log.i(TAG, "Obtained IHostApd binder.");
            Log.i(TAG, "Local Version: " + IHostapd.VERSION);

            try {
                mServiceVersion = mIHostapd.getInterfaceVersion();
                Log.i(TAG, "Remote Version: " + mServiceVersion);
                IBinder serviceBinder = getServiceBinderMockable();
                if (serviceBinder == null) return false;
                mWaitForDeathLatch = null;
                serviceBinder.linkToDeath(new HostapdDeathRecipient(serviceBinder), /* flags= */ 0);
                if (!setDebugParams()) return false;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
            if (!registerCallback(new HostapdCallback())) {
                Log.e(TAG, "Failed to register callback, stopping hostapd AIDL startup");
                mIHostapd = null;
                return false;
            }
            return true;
        }
    }

    /**
     * Terminate the hostapd daemon & wait for it's death.
     */
    @Override
    public void terminate() {
        synchronized (mLock) {
            final String methodStr = "terminate";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return;
            }
            Log.i(TAG, "Terminate HostApd Service.");
            try {
                mWaitForDeathLatch = new CountDownLatch(1);
                mIHostapd.terminate();
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
        }

        // Now wait for death listener callback to confirm that it's dead.
        try {
            if (!mWaitForDeathLatch.await(WAIT_FOR_DEATH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for confirmation of hostapd death");
            } else {
                Log.d(TAG, "Got service death confirmation");
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "Failed to wait for hostapd death");
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            hostapdServiceDiedHandler(getServiceBinderMockable());
            Log.e(TAG, "IHostapd." + methodStr + " failed with exception", e);
        }
    }

    /**
     * Set the debug log level for hostapd.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean setDebugParams() {
        synchronized (mLock) {
            final String methodStr = "setDebugParams";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return false;
            }
            try {
                mIHostapd.setDebugParams(mVerboseHalLoggingEnabled
                        ? DebugLevel.DEBUG : DebugLevel.INFO);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    private static int getEncryptionType(SoftApConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getSecurityType()) {
            case SoftApConfiguration.SECURITY_TYPE_OPEN:
                encryptionType = EncryptionType.NONE;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                encryptionType = EncryptionType.WPA2;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION:
                encryptionType = EncryptionType.WPA3_SAE_TRANSITION;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE:
                encryptionType = EncryptionType.WPA3_SAE;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION:
                encryptionType = EncryptionType.WPA3_OWE_TRANSITION;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_OWE:
                encryptionType = EncryptionType.WPA3_OWE;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = EncryptionType.NONE;
                break;
        }
        return encryptionType;
    }

    private static int getHalBandMask(int apBand) throws IllegalArgumentException {
        int bandMask = 0;

        if (!ApConfigUtil.isBandValid(apBand)) {
            throw new IllegalArgumentException();
        }

        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_2GHZ)) {
            bandMask |= BandMask.BAND_2_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_5GHZ)) {
            bandMask |= BandMask.BAND_5_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_6GHZ)) {
            bandMask |= BandMask.BAND_6_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_60GHZ)) {
            bandMask |= BandMask.BAND_60_GHZ;
        }

        return bandMask;
    }

   /**
     * Prepare the acsChannelFreqRangesMhz in ChannelParams.
     */
    private void prepareAcsChannelFreqRangesMhz(ChannelParams channelParams,
            @BandType int band, SoftApConfiguration config) {
        List<FrequencyRange> ranges = new ArrayList<>();
        if ((band & SoftApConfiguration.BAND_2GHZ) != 0) {
            ranges.addAll(toAcsFreqRanges(SoftApConfiguration.BAND_2GHZ, config));
        }
        if ((band & SoftApConfiguration.BAND_5GHZ) != 0) {
            ranges.addAll(toAcsFreqRanges(SoftApConfiguration.BAND_5GHZ, config));
        }
        if ((band & SoftApConfiguration.BAND_6GHZ) != 0) {
            ranges.addAll(toAcsFreqRanges(SoftApConfiguration.BAND_6GHZ, config));
        }
        channelParams.acsChannelFreqRangesMhz = ranges.toArray(
                new FrequencyRange[ranges.size()]);
    }

    /**
     * Convert OEM and SoftApConfiguration channel restrictions to a list of FreqRanges
     */
    private List<FrequencyRange> toAcsFreqRanges(@BandType int band, SoftApConfiguration config) {
        List<Integer> allowedChannelList;
        List<FrequencyRange> frequencyRanges = new ArrayList<>();

        if (!ApConfigUtil.isBandValid(band) || ApConfigUtil.isMultiband(band)) {
            Log.e(TAG, "Invalid band : " + band);
            return frequencyRanges;
        }

        String oemConfig;
        switch (band) {
            case SoftApConfiguration.BAND_2GHZ:
                oemConfig = mResourceCache.getString(
                        R.string.config_wifiSoftap2gChannelList);
                break;
            case SoftApConfiguration.BAND_5GHZ:
                oemConfig = mResourceCache.getString(
                        R.string.config_wifiSoftap5gChannelList);
                break;
            case SoftApConfiguration.BAND_6GHZ:
                oemConfig = mResourceCache.getString(
                        R.string.config_wifiSoftap6gChannelList);
                break;
            default:
                return frequencyRanges;
        }

        allowedChannelList = ApConfigUtil.collectAllowedAcsChannels(band, oemConfig,
                SdkLevel.isAtLeastT()
                        ? config.getAllowedAcsChannels(band) : new int[] {});
        if (allowedChannelList.isEmpty()) {
            Log.e(TAG, "Empty list of allowed channels");
            return frequencyRanges;
        }
        Collections.sort(allowedChannelList);

        // Convert the sorted list to a set of frequency ranges
        boolean rangeStarted = false;
        int prevChannel = -1;
        FrequencyRange freqRange = null;
        for (int channel : allowedChannelList) {
            // Continuation of an existing frequency range
            if (rangeStarted) {
                if (channel == prevChannel + 1) {
                    prevChannel = channel;
                    continue;
                }

                // End of the existing frequency range
                freqRange.endMhz = ApConfigUtil.convertChannelToFrequency(prevChannel, band);
                frequencyRanges.add(freqRange);
                // We will continue to start a new frequency range
            }

            // Beginning of a new frequency range
            freqRange = new FrequencyRange();
            freqRange.startMhz = ApConfigUtil.convertChannelToFrequency(channel, band);
            rangeStarted = true;
            prevChannel = channel;
        }

        // End the last range
        freqRange.endMhz = ApConfigUtil.convertChannelToFrequency(prevChannel, band);
        frequencyRanges.add(freqRange);

        return frequencyRanges;
    }

    /**
     * Map hal bandwidth to SoftApInfo.
     *
     * @param bandwidth The channel bandwidth of the AP which is defined in the HAL.
     * @return The channel bandwidth in the SoftApinfo.
     */
    @VisibleForTesting
    public int mapHalChannelBandwidthToSoftApInfo(int channelBandwidth) {
        switch (channelBandwidth) {
            case ChannelBandwidth.BANDWIDTH_20_NOHT:
                return SoftApInfo.CHANNEL_WIDTH_20MHZ_NOHT;
            case ChannelBandwidth.BANDWIDTH_20:
                return SoftApInfo.CHANNEL_WIDTH_20MHZ;
            case ChannelBandwidth.BANDWIDTH_40:
                return SoftApInfo.CHANNEL_WIDTH_40MHZ;
            case ChannelBandwidth.BANDWIDTH_80:
                return SoftApInfo.CHANNEL_WIDTH_80MHZ;
            case ChannelBandwidth.BANDWIDTH_80P80:
                return SoftApInfo.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            case ChannelBandwidth.BANDWIDTH_160:
                return SoftApInfo.CHANNEL_WIDTH_160MHZ;
            case ChannelBandwidth.BANDWIDTH_320:
                return SoftApInfo.CHANNEL_WIDTH_320MHZ;
            case ChannelBandwidth.BANDWIDTH_2160:
                return SoftApInfo.CHANNEL_WIDTH_2160MHZ;
            case ChannelBandwidth.BANDWIDTH_4320:
                return SoftApInfo.CHANNEL_WIDTH_4320MHZ;
            case ChannelBandwidth.BANDWIDTH_6480:
                return SoftApInfo.CHANNEL_WIDTH_6480MHZ;
            case ChannelBandwidth.BANDWIDTH_8640:
                return SoftApInfo.CHANNEL_WIDTH_8640MHZ;
            default:
                return SoftApInfo.CHANNEL_WIDTH_INVALID;
        }
    }

    /**
     * Map SoftApInfo bandwidth to hal.
     *
     * @param channelBandwidth The channel bandwidth as defined in SoftApInfo
     * @return The channel bandwidth as defined in hal
     */
    @VisibleForTesting
    public int mapSoftApInfoBandwidthToHal(@WifiAnnotations.Bandwidth int channelBandwidth) {
        switch (channelBandwidth) {
            case SoftApInfo.CHANNEL_WIDTH_AUTO:
                return ChannelBandwidth.BANDWIDTH_AUTO;
            case SoftApInfo.CHANNEL_WIDTH_20MHZ_NOHT:
                return ChannelBandwidth.BANDWIDTH_20_NOHT;
            case SoftApInfo.CHANNEL_WIDTH_20MHZ:
                return ChannelBandwidth.BANDWIDTH_20;
            case SoftApInfo.CHANNEL_WIDTH_40MHZ:
                return ChannelBandwidth.BANDWIDTH_40;
            case SoftApInfo.CHANNEL_WIDTH_80MHZ:
                return ChannelBandwidth.BANDWIDTH_80;
            case SoftApInfo.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return ChannelBandwidth.BANDWIDTH_80P80;
            case SoftApInfo.CHANNEL_WIDTH_160MHZ:
                return ChannelBandwidth.BANDWIDTH_160;
            case SoftApInfo.CHANNEL_WIDTH_320MHZ:
                return ChannelBandwidth.BANDWIDTH_320;
            case SoftApInfo.CHANNEL_WIDTH_2160MHZ:
                return ChannelBandwidth.BANDWIDTH_2160;
            case SoftApInfo.CHANNEL_WIDTH_4320MHZ:
                return ChannelBandwidth.BANDWIDTH_4320;
            case SoftApInfo.CHANNEL_WIDTH_6480MHZ:
                return ChannelBandwidth.BANDWIDTH_6480;
            case SoftApInfo.CHANNEL_WIDTH_8640MHZ:
                return ChannelBandwidth.BANDWIDTH_8640;
            default:
                return ChannelBandwidth.BANDWIDTH_INVALID;
        }
    }

    /**
     * Map hal generation to wifi standard.
     *
     * @param generation The operation mode of the AP which is defined in HAL.
     * @return The wifi standard in the ScanResult.
     */
    @VisibleForTesting
    public int mapHalGenerationToWifiStandard(int generation) {
        switch (generation) {
            case Generation.WIFI_STANDARD_LEGACY:
                return ScanResult.WIFI_STANDARD_LEGACY;
            case Generation.WIFI_STANDARD_11N:
                return ScanResult.WIFI_STANDARD_11N;
            case Generation.WIFI_STANDARD_11AC:
                return ScanResult.WIFI_STANDARD_11AC;
            case Generation.WIFI_STANDARD_11AX:
                return ScanResult.WIFI_STANDARD_11AX;
            case Generation.WIFI_STANDARD_11BE:
                return ScanResult.WIFI_STANDARD_11BE;
            case Generation.WIFI_STANDARD_11AD:
                return ScanResult.WIFI_STANDARD_11AD;
            default:
                return ScanResult.WIFI_STANDARD_UNKNOWN;
        }
    }

    /**
     * Convert from a HAL DeauthenticationReasonCode to its framework equivalent.
     *
     * @param deauthenticationReasonCode The deauthentication reason code defined in HAL.
     * @return The corresponding {@link DeauthenticationReasonCode}.
     */
    @VisibleForTesting
    @WifiAnnotations.SoftApDisconnectReason int mapHalToFrameworkDeauthenticationReasonCode(
            int deauthenticationReasonCode) {
        return switch (deauthenticationReasonCode) {
            case android.hardware.wifi.common.DeauthenticationReasonCode.HOSTAPD_NO_REASON ->
                    DeauthenticationReasonCode.REASON_UNKNOWN;
            case android.hardware.wifi.common.DeauthenticationReasonCode.UNSPECIFIED ->
                    DeauthenticationReasonCode.REASON_UNSPECIFIED;
            case android.hardware.wifi.common.DeauthenticationReasonCode.PREV_AUTH_NOT_VALID ->
                    DeauthenticationReasonCode.REASON_PREV_AUTH_NOT_VALID;
            case android.hardware.wifi.common.DeauthenticationReasonCode.DEAUTH_LEAVING ->
                    DeauthenticationReasonCode.REASON_DEAUTH_LEAVING;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.DISASSOC_DUE_TO_INACTIVITY ->
                    DeauthenticationReasonCode.REASON_DISASSOC_DUE_TO_INACTIVITY;
            case android.hardware.wifi.common.DeauthenticationReasonCode.DISASSOC_AP_BUSY ->
                    DeauthenticationReasonCode.REASON_DISASSOC_AP_BUSY;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.CLASS2_FRAME_FROM_NONAUTH_STA ->
                    DeauthenticationReasonCode.REASON_CLASS2_FRAME_FROM_NONAUTH_STA;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.CLASS3_FRAME_FROM_NONASSOC_STA ->
                    DeauthenticationReasonCode.REASON_CLASS3_FRAME_FROM_NONASSOC_STA;
            case android.hardware.wifi.common.DeauthenticationReasonCode.DISASSOC_STA_HAS_LEFT ->
                    DeauthenticationReasonCode.REASON_DISASSOC_STA_HAS_LEFT;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.STA_REQ_ASSOC_WITHOUT_AUTH ->
                    DeauthenticationReasonCode.REASON_STA_REQ_ASSOC_WITHOUT_AUTH;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.PWR_CAPABILITY_NOT_VALID ->
                    DeauthenticationReasonCode.REASON_PWR_CAPABILITY_NOT_VALID;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.SUPPORTED_CHANNEL_NOT_VALID ->
                    DeauthenticationReasonCode.REASON_SUPPORTED_CHANNEL_NOT_VALID;
            case android.hardware.wifi.common.DeauthenticationReasonCode.BSS_TRANSITION_DISASSOC ->
                    DeauthenticationReasonCode.REASON_BSS_TRANSITION_DISASSOC;
            case android.hardware.wifi.common.DeauthenticationReasonCode.INVALID_IE ->
                    DeauthenticationReasonCode.REASON_INVALID_IE;
            case android.hardware.wifi.common.DeauthenticationReasonCode.MICHAEL_MIC_FAILURE ->
                    DeauthenticationReasonCode.REASON_MICHAEL_MIC_FAILURE;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.FOURWAY_HANDSHAKE_TIMEOUT ->
                    DeauthenticationReasonCode.REASON_FOURWAY_HANDSHAKE_TIMEOUT;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.GROUP_KEY_UPDATE_TIMEOUT ->
                    DeauthenticationReasonCode.REASON_GROUP_KEY_UPDATE_TIMEOUT;
            case android.hardware.wifi.common.DeauthenticationReasonCode.IE_IN_4WAY_DIFFERS ->
                    DeauthenticationReasonCode.REASON_IE_IN_4WAY_DIFFERS;
            case android.hardware.wifi.common.DeauthenticationReasonCode.GROUP_CIPHER_NOT_VALID ->
                    DeauthenticationReasonCode.REASON_GROUP_CIPHER_NOT_VALID;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.PAIRWISE_CIPHER_NOT_VALID ->
                    DeauthenticationReasonCode.REASON_PAIRWISE_CIPHER_NOT_VALID;
            case android.hardware.wifi.common.DeauthenticationReasonCode.AKMP_NOT_VALID ->
                    DeauthenticationReasonCode.REASON_AKMP_NOT_VALID;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.UNSUPPORTED_RSN_IE_VERSION ->
                    DeauthenticationReasonCode.REASON_UNSUPPORTED_RSN_IE_VERSION;
            case android.hardware.wifi.common.DeauthenticationReasonCode.INVALID_RSN_IE_CAPAB ->
                    DeauthenticationReasonCode.REASON_INVALID_RSN_IE_CAPAB;
            case android.hardware.wifi.common.DeauthenticationReasonCode.IEEE_802_1X_AUTH_FAILED ->
                    DeauthenticationReasonCode.REASON_IEEE_802_1X_AUTH_FAILED;
            case android.hardware.wifi.common.DeauthenticationReasonCode.CIPHER_SUITE_REJECTED ->
                    DeauthenticationReasonCode.REASON_CIPHER_SUITE_REJECTED;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.TDLS_TEARDOWN_UNREACHABLE ->
                    DeauthenticationReasonCode.REASON_TDLS_TEARDOWN_UNREACHABLE;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.TDLS_TEARDOWN_UNSPECIFIED ->
                    DeauthenticationReasonCode.REASON_TDLS_TEARDOWN_UNSPECIFIED;
            case android.hardware.wifi.common.DeauthenticationReasonCode.SSP_REQUESTED_DISASSOC ->
                    DeauthenticationReasonCode.REASON_SSP_REQUESTED_DISASSOC;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.NO_SSP_ROAMING_AGREEMENT ->
                    DeauthenticationReasonCode.REASON_NO_SSP_ROAMING_AGREEMENT;
            case android.hardware.wifi.common.DeauthenticationReasonCode.BAD_CIPHER_OR_AKM ->
                    DeauthenticationReasonCode.REASON_BAD_CIPHER_OR_AKM;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.NOT_AUTHORIZED_THIS_LOCATION ->
                    DeauthenticationReasonCode.REASON_NOT_AUTHORIZED_THIS_LOCATION;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.SERVICE_CHANGE_PRECLUDES_TS ->
                    DeauthenticationReasonCode.REASON_SERVICE_CHANGE_PRECLUDES_TS;
            case android.hardware.wifi.common.DeauthenticationReasonCode.UNSPECIFIED_QOS_REASON ->
                    DeauthenticationReasonCode.REASON_UNSPECIFIED_QOS_REASON;
            case android.hardware.wifi.common.DeauthenticationReasonCode.NOT_ENOUGH_BANDWIDTH ->
                    DeauthenticationReasonCode.REASON_NOT_ENOUGH_BANDWIDTH;
            case android.hardware.wifi.common.DeauthenticationReasonCode.DISASSOC_LOW_ACK ->
                    DeauthenticationReasonCode.REASON_DISASSOC_LOW_ACK;
            case android.hardware.wifi.common.DeauthenticationReasonCode.EXCEEDED_TXOP ->
                    DeauthenticationReasonCode.REASON_EXCEEDED_TXOP;
            case android.hardware.wifi.common.DeauthenticationReasonCode.STA_LEAVING ->
                    DeauthenticationReasonCode.REASON_STA_LEAVING;
            case android.hardware.wifi.common.DeauthenticationReasonCode.END_TS_BA_DLS ->
                    DeauthenticationReasonCode.REASON_END_TS_BA_DLS;
            case android.hardware.wifi.common.DeauthenticationReasonCode.UNKNOWN_TS_BA ->
                    DeauthenticationReasonCode.REASON_UNKNOWN_TS_BA;
            case android.hardware.wifi.common.DeauthenticationReasonCode.TIMEOUT ->
                    DeauthenticationReasonCode.REASON_TIMEOUT;
            case android.hardware.wifi.common.DeauthenticationReasonCode.PEERKEY_MISMATCH ->
                    DeauthenticationReasonCode.REASON_PEERKEY_MISMATCH;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.AUTHORIZED_ACCESS_LIMIT_REACHED ->
                    DeauthenticationReasonCode.REASON_AUTHORIZED_ACCESS_LIMIT_REACHED;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.EXTERNAL_SERVICE_REQUIREMENTS ->
                    DeauthenticationReasonCode.REASON_EXTERNAL_SERVICE_REQUIREMENTS;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.INVALID_FT_ACTION_FRAME_COUNT ->
                    DeauthenticationReasonCode.REASON_INVALID_FT_ACTION_FRAME_COUNT;
            case android.hardware.wifi.common.DeauthenticationReasonCode.INVALID_PMKID ->
                    DeauthenticationReasonCode.REASON_INVALID_PMKID;
            case android.hardware.wifi.common.DeauthenticationReasonCode.INVALID_MDE ->
                    DeauthenticationReasonCode.REASON_INVALID_MDE;
            case android.hardware.wifi.common.DeauthenticationReasonCode.INVALID_FTE ->
                    DeauthenticationReasonCode.REASON_INVALID_FTE;
            case android.hardware.wifi.common.DeauthenticationReasonCode.MESH_PEERING_CANCELLED ->
                    DeauthenticationReasonCode.REASON_MESH_PEERING_CANCELLED;
            case android.hardware.wifi.common.DeauthenticationReasonCode.MESH_MAX_PEERS ->
                    DeauthenticationReasonCode.REASON_MESH_MAX_PEERS;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_CONFIG_POLICY_VIOLATION ->
                    DeauthenticationReasonCode.REASON_MESH_CONFIG_POLICY_VIOLATION;
            case android.hardware.wifi.common.DeauthenticationReasonCode.MESH_CLOSE_RCVD ->
                    DeauthenticationReasonCode.REASON_MESH_CLOSE_RCVD;
            case android.hardware.wifi.common.DeauthenticationReasonCode.MESH_MAX_RETRIES ->
                    DeauthenticationReasonCode.REASON_MESH_MAX_RETRIES;
            case android.hardware.wifi.common.DeauthenticationReasonCode.MESH_CONFIRM_TIMEOUT ->
                    DeauthenticationReasonCode.REASON_MESH_CONFIRM_TIMEOUT;
            case android.hardware.wifi.common.DeauthenticationReasonCode.MESH_INVALID_GTK ->
                    DeauthenticationReasonCode.REASON_MESH_INVALID_GTK;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_INCONSISTENT_PARAMS ->
                    DeauthenticationReasonCode.REASON_MESH_INCONSISTENT_PARAMS;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_INVALID_SECURITY_CAP ->
                    DeauthenticationReasonCode.REASON_MESH_INVALID_SECURITY_CAP;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_PATH_ERROR_NO_PROXY_INFO ->
                    DeauthenticationReasonCode.REASON_MESH_PATH_ERROR_NO_PROXY_INFO;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_PATH_ERROR_NO_FORWARDING_INFO ->
                    DeauthenticationReasonCode.REASON_MESH_PATH_ERROR_NO_FORWARDING_INFO;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_PATH_ERROR_DEST_UNREACHABLE ->
                    DeauthenticationReasonCode.REASON_MESH_PATH_ERROR_DEST_UNREACHABLE;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MAC_ADDRESS_ALREADY_EXISTS_IN_MBSS ->
                    DeauthenticationReasonCode.REASON_MAC_ADDRESS_ALREADY_EXISTS_IN_MBSS;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_CHANNEL_SWITCH_REGULATORY_REQ ->
                    DeauthenticationReasonCode.REASON_MESH_CHANNEL_SWITCH_REGULATORY_REQ;
            case android.hardware.wifi.common
                         .DeauthenticationReasonCode.MESH_CHANNEL_SWITCH_UNSPECIFIED ->
                    DeauthenticationReasonCode.REASON_MESH_CHANNEL_SWITCH_UNSPECIFIED;
            default -> {
                Log.e(TAG, "Invalid DeauthenticationReasonCode: "
                        + deauthenticationReasonCode);
                yield DeauthenticationReasonCode.REASON_UNKNOWN;
            }
        };
    }

    @SuppressLint("NewApi")
    private NetworkParams prepareNetworkParams(boolean isMetered,
            SoftApConfiguration config) {
        NetworkParams nwParams = new NetworkParams();
        ArrayList<Byte> ssid = NativeUtil.byteArrayToArrayList(config.getWifiSsid().getBytes());
        nwParams.ssid = new byte[ssid.size()];
        for (int i = 0; i < ssid.size(); i++) {
            nwParams.ssid[i] = ssid.get(i);
        }

        final List<ScanResult.InformationElement> elements = config.getVendorElementsInternal();
        int totalLen = 0;
        for (ScanResult.InformationElement e : elements) {
            totalLen += 2 + e.bytes.length; // 1 byte ID + 1 byte payload len + payload
        }
        nwParams.vendorElements = new byte[totalLen];
        int i = 0;
        for (ScanResult.InformationElement e : elements) {
            nwParams.vendorElements[i++] = (byte) e.id;
            nwParams.vendorElements[i++] = (byte) e.bytes.length;
            for (int j = 0; j < e.bytes.length; j++) {
                nwParams.vendorElements[i++] = e.bytes[j];
            }
        }

        nwParams.isMetered = isMetered;
        nwParams.isHidden = config.isHiddenSsid();
        nwParams.encryptionType = getEncryptionType(config);
        nwParams.passphrase = (config.getPassphrase() != null)
                    ? config.getPassphrase() : "";
        if (Flags.apIsolate() && isServiceVersionAtLeast(3) && Environment.isSdkAtLeastB()) {
            nwParams.isClientIsolationEnabled = config.isClientIsolationEnabled();
        }

        if (nwParams.ssid == null || nwParams.passphrase == null) {
            return null;
        }
        return nwParams;
    }

    private IfaceParams prepareIfaceParams(String ifaceName, SoftApConfiguration config,
            boolean isUsingMultiLinkOperation, List<String> instanceIdentities)
            throws IllegalArgumentException {
        IfaceParams ifaceParams = new IfaceParams();
        ifaceParams.name = ifaceName;
        ifaceParams.hwModeParams = prepareHwModeParams(config);
        ifaceParams.channelParams = prepareChannelParamsList(config);
        ifaceParams.usesMlo = isUsingMultiLinkOperation;
        if (instanceIdentities != null) {
            ifaceParams.instanceIdentities =
                    instanceIdentities.toArray(new String[instanceIdentities.size()]);
        }
        if (ifaceParams.name == null || ifaceParams.hwModeParams == null
                || ifaceParams.channelParams == null) {
            return null;
        }
        if (isServiceVersionAtLeast(2) && SdkLevel.isAtLeastV()
                && !config.getVendorData().isEmpty()) {
            ifaceParams.vendorData =
                    HalAidlUtil.frameworkToHalOuiKeyedDataList(config.getVendorData());
        }
        return ifaceParams;
    }

    private HwModeParams prepareHwModeParams(SoftApConfiguration config) {
        HwModeParams hwModeParams = new HwModeParams();
        hwModeParams.enable80211N = true;
        hwModeParams.enable80211AC = mResourceCache.getBoolean(
                R.bool.config_wifi_softap_ieee80211ac_supported);
        hwModeParams.enable80211AX = ApConfigUtil.isIeee80211axSupported(mContext);
        //Update 80211ax support with the configuration.
        hwModeParams.enable80211AX &= config.isIeee80211axEnabledInternal();
        hwModeParams.enable6GhzBand = ApConfigUtil.isBandSupported(
                SoftApConfiguration.BAND_6GHZ, mContext);
        hwModeParams.enableHeSingleUserBeamformer = mResourceCache.getBoolean(
                R.bool.config_wifiSoftapHeSuBeamformerSupported);
        hwModeParams.enableHeSingleUserBeamformee = mResourceCache.getBoolean(
                R.bool.config_wifiSoftapHeSuBeamformeeSupported);
        hwModeParams.enableHeMultiUserBeamformer = mResourceCache.getBoolean(
                R.bool.config_wifiSoftapHeMuBeamformerSupported);
        hwModeParams.enableHeTargetWakeTime = mResourceCache.getBoolean(
                R.bool.config_wifiSoftapHeTwtSupported);

        if (SdkLevel.isAtLeastT()) {
            hwModeParams.enable80211BE = config.isIeee80211beEnabled();
            hwModeParams.maximumChannelBandwidth =
                    mapSoftApInfoBandwidthToHal(config.getMaxChannelBandwidth());
        } else {
            hwModeParams.maximumChannelBandwidth = ChannelBandwidth.BANDWIDTH_AUTO;
        }
        return hwModeParams;
    }

    private ChannelParams[] prepareChannelParamsList(SoftApConfiguration config)
            throws IllegalArgumentException {
        int nChannels = 1;
        boolean repeatBand = false;
        if (SdkLevel.isAtLeastS()) {
            nChannels = config.getChannels().size();
        }
        if (config.getSecurityType()
                == SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION) {
            nChannels = 2;
            repeatBand = true;
        }
        ChannelParams[] channelParamsList = new ChannelParams[nChannels];
        for (int i = 0; i < nChannels; i++) {
            int band = config.getBand();
            int channel = config.getChannel();
            if (SdkLevel.isAtLeastS() && !repeatBand) {
                band = config.getChannels().keyAt(i);
                channel = config.getChannels().valueAt(i);
            }
            channelParamsList[i] = new ChannelParams();
            channelParamsList[i].channel = channel;
            channelParamsList[i].enableAcs = ApConfigUtil.isAcsSupported(mContext)
                    && channel == 0;
            channelParamsList[i].bandMask = getHalBandMask(band);
            channelParamsList[i].acsChannelFreqRangesMhz = new FrequencyRange[0];
            if (channelParamsList[i].enableAcs) {
                channelParamsList[i].acsShouldExcludeDfs = !mResourceCache
                        .getBoolean(R.bool.config_wifiSoftapAcsIncludeDfs);
                if (ApConfigUtil.isSendFreqRangesNeeded(band, mContext, config)) {
                    prepareAcsChannelFreqRangesMhz(channelParamsList[i], band, config);
                }
            }
            if (channelParamsList[i].acsChannelFreqRangesMhz == null) {
                return null;
            }
        }
        return channelParamsList;
    }

    /**
     * Returns false if Hostapd is null, and logs failure to call methodStr
     */
    private boolean checkHostapdAndLogFailure(String methodStr) {
        synchronized (mLock) {
            if (mIHostapd == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IHostapd is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Logs failure for a service specific exception. Error codes are defined in HostapdStatusCode
     */
    private void handleServiceSpecificException(
            ServiceSpecificException exception, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "IHostapd." + methodStr + " failed: " + exception.toString());
        }
    }

    /**
     * Dump information about the AIDL implementation.
     *
     */
    public void dump(PrintWriter pw) {
        pw.println("AIDL interface version: " + mServiceVersion);
    }

    /**
     * See comments for
     * {@link IHostapdHal#removeLinkFromMultipleLinkBridgedApIface(String, String)}.
     */
    public void removeLinkFromMultipleLinkBridgedApIface(@NonNull String ifaceName,
            @NonNull String apIfaceInstance) {
        if (!isServiceVersionAtLeast(3)) {
            return;
        }
        synchronized (mLock) {
            final String methodStr = "removeLinkFromMultipleLinkBridgedApIface";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return;
            }
            Log.i(TAG, "Remove link: " + apIfaceInstance + " from AP iface: " + ifaceName);
            try {
                mIHostapd.removeLinkFromMultipleLinkBridgedApIface(ifaceName, apIfaceInstance);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
        }
    }
}
