/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.wifi.ScanResult.WIFI_BAND_24_GHZ;
import static android.net.wifi.ScanResult.WIFI_BAND_5_GHZ;
import static android.net.wifi.ScanResult.WIFI_BAND_6_GHZ;
import static android.net.wifi.WifiManager.CHANNEL_DATA_KEY_FREQUENCY_MHZ;
import static android.net.wifi.WifiManager.CHANNEL_DATA_KEY_NUM_AP;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL;
import static android.net.wifi.WifiManager.NOT_OVERRIDE_EXISTING_NETWORKS_ON_RESTORE;
import static android.net.wifi.WifiManager.PnoScanResultsCallback.REGISTER_PNO_CALLBACK_PNO_NOT_SUPPORTED;
import static android.net.wifi.WifiManager.ROAMING_MODE_AGGRESSIVE;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_GENERAL;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_NO_CHANNEL;
import static android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
import static android.net.wifi.WifiManager.VERBOSE_LOGGING_LEVEL_WIFI_AWARE_ENABLED_ONLY;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;
import static android.net.wifi.WifiManager.WIFI_INTERFACE_TYPE_AP;
import static android.net.wifi.WifiManager.WIFI_INTERFACE_TYPE_AWARE;
import static android.net.wifi.WifiManager.WIFI_INTERFACE_TYPE_DIRECT;
import static android.net.wifi.WifiManager.WIFI_INTERFACE_TYPE_STA;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WifiStateChangedListener;
import static android.os.Process.WIFI_UID;

import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_LOCAL_ONLY;
import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_SECONDARY_LONG_LIVED;
import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_SECONDARY_TRANSIENT;
import static com.android.server.wifi.ClientModeImpl.RESET_SIM_REASON_DEFAULT_DATA_SIM_CHANGED;
import static com.android.server.wifi.ClientModeImpl.RESET_SIM_REASON_SIM_INSERTED;
import static com.android.server.wifi.ClientModeImpl.RESET_SIM_REASON_SIM_REMOVED;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_AP;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_AP_BRIDGE;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_NAN;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_P2P;
import static com.android.server.wifi.HalDeviceManager.HDM_CREATE_IFACE_STA;
import static com.android.server.wifi.ScanRequestProxy.createBroadcastOptionsForScanResultsAvailable;
import static com.android.server.wifi.SelfRecovery.REASON_API_CALL;
import static com.android.server.wifi.WifiSettingsConfigStore.D2D_ALLOWED_WHEN_INFRA_STA_DISABLED;
import static com.android.server.wifi.WifiSettingsConfigStore.SHOW_DIALOG_WHEN_THIRD_PARTY_APPS_ENABLE_WIFI;
import static com.android.server.wifi.WifiSettingsConfigStore.SHOW_DIALOG_WHEN_THIRD_PARTY_APPS_ENABLE_WIFI_SET_BY_API;
import static com.android.server.wifi.WifiSettingsConfigStore.WIFI_AWARE_VERBOSE_LOGGING_ENABLED;
import static com.android.server.wifi.WifiSettingsConfigStore.WIFI_STA_FACTORY_MAC_ADDRESS;
import static com.android.server.wifi.WifiSettingsConfigStore.WIFI_VERBOSE_LOGGING_ENABLED;
import static com.android.server.wifi.WifiSettingsConfigStore.WIFI_WEP_ALLOWED;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.WifiSsidPolicy;
import android.app.compat.CompatChanges;
import android.bluetooth.BluetoothAdapter;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.wifi.WifiStatusCode;
import android.location.LocationManager;
import android.net.DhcpInfo;
import android.net.DhcpOption;
import android.net.DhcpResultsParcelable;
import android.net.InetAddresses;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkStack;
import android.net.TetheringManager;
import android.net.Uri;
import android.net.ip.IpClientUtil;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkManager;
import android.net.wifi.BlockingOption;
import android.net.wifi.CoexUnsafeChannel;
import android.net.wifi.IActionListener;
import android.net.wifi.IBooleanListener;
import android.net.wifi.IByteArrayListener;
import android.net.wifi.ICoexCallback;
import android.net.wifi.IDppCallback;
import android.net.wifi.IIntegerListener;
import android.net.wifi.IInterfaceCreationInfoCallback;
import android.net.wifi.ILastCallerListener;
import android.net.wifi.IListListener;
import android.net.wifi.ILocalOnlyConnectionStatusListener;
import android.net.wifi.ILocalOnlyHotspotCallback;
import android.net.wifi.IMacAddressListListener;
import android.net.wifi.IMapListener;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.IOnWifiActivityEnergyInfoListener;
import android.net.wifi.IOnWifiDriverCountryCodeChangedListener;
import android.net.wifi.IOnWifiUsabilityStatsListener;
import android.net.wifi.IPnoScanResultsCallback;
import android.net.wifi.IScanResultsCallback;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.IStringListener;
import android.net.wifi.ISubsystemRestartCallback;
import android.net.wifi.ISuggestionConnectionStatusListener;
import android.net.wifi.ISuggestionUserApprovalStatusListener;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.ITwtCallback;
import android.net.wifi.ITwtCapabilitiesListener;
import android.net.wifi.ITwtStatsListener;
import android.net.wifi.IWifiBandsListener;
import android.net.wifi.IWifiConnectedNetworkScorer;
import android.net.wifi.IWifiLowLatencyLockListener;
import android.net.wifi.IWifiManager;
import android.net.wifi.IWifiNetworkSelectionConfigListener;
import android.net.wifi.IWifiNetworkStateChangedListener;
import android.net.wifi.IWifiStateChangedListener;
import android.net.wifi.IWifiVerboseLoggingStatusChangedListener;
import android.net.wifi.MscsParams;
import android.net.wifi.QosPolicyParams;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.SoftApState;
import android.net.wifi.WifiAnnotations.WifiStandard;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiBands;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.AddNetworkResult;
import android.net.wifi.WifiManager.CoexRestriction;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.RoamingMode;
import android.net.wifi.WifiManager.SapClientBlockedReason;
import android.net.wifi.WifiManager.SuggestionConnectionStatusListener;
import android.net.wifi.WifiNetworkSelectionConfig;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.twt.TwtRequest;
import android.net.wifi.twt.TwtSession;
import android.net.wifi.twt.TwtSessionCallback;
import android.net.wifi.util.Environment;
import android.net.wifi.util.ScanResultUtil;
import android.net.wifi.util.WifiResourceCache;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.uwb.UwbManager;

import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.HandlerExecutor;
import com.android.modules.utils.ParceledListSlice;
import com.android.modules.utils.StringParceledListSlice;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.Inet4AddressUtils;
import com.android.server.wifi.coex.CoexManager;
import com.android.server.wifi.entitlement.PseudonymInfo;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointProvider;
import com.android.server.wifi.proto.WifiStatsLog;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserActionEvent;
import com.android.server.wifi.util.ActionListenerWrapper;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.FeatureBitsetUtils;
import com.android.server.wifi.util.GeneralUtil.Mutable;
import com.android.server.wifi.util.LastCallerInfoManager;
import com.android.server.wifi.util.RssiUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WorkSourceHelper;
import com.android.wifi.flags.FeatureFlags;
import com.android.wifi.resources.R;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * WifiService handles remote WiFi operation requests by implementing
 * the IWifiManager interface.
 */
public class WifiServiceImpl extends IWifiManager.Stub {
    private static final String TAG = "WifiService";
    private static final boolean VDBG = false;

    /** Max wait time for posting blocking runnables */
    private static final int RUN_WITH_SCISSORS_TIMEOUT_MILLIS = 4000;
    @VisibleForTesting
    static final int AUTO_DISABLE_SHOW_KEY_COUNTDOWN_MILLIS = 24 * 60 * 60 * 1000;
    private static final int CHANNEL_USAGE_WEAK_SCAN_RSSI_DBM = -80;

    private static final int SCORER_BINDING_STATE_INVALID = -1;
    // The system is brining up the scorer service.
    private static final int SCORER_BINDING_STATE_BRINGING_UP = 0;
    private static final int SCORER_BINDING_STATE_CONNECTED = 1;
    private static final int SCORER_BINDING_STATE_DISCONNECTED = 2;
    private static final int SCORER_BINDING_STATE_BINDING_DIED = 3;
    // A null binder is received.
    private static final int SCORER_BINDING_STATE_NULL_BINDING = 4;
    // The system couldn't find the service to bind to.
    private static final int SCORER_BINDING_STATE_NO_SERVICE = 5;
    // The app has cleared the Wi-Fi scorer. So the service is unbound.
    private static final int SCORER_BINDING_STATE_CLEARED = 6;
    // The app has set a new Wi-Fi scorer. Rebind to the service.
    public static final int SCORER_BINDING_STATE_REBINDING = 7;

    @IntDef(prefix = { "SCORER_BINDING_STATE_" }, value = {
        SCORER_BINDING_STATE_INVALID,
        SCORER_BINDING_STATE_BRINGING_UP,
        SCORER_BINDING_STATE_CONNECTED,
        SCORER_BINDING_STATE_DISCONNECTED,
        SCORER_BINDING_STATE_BINDING_DIED,
        SCORER_BINDING_STATE_NULL_BINDING,
        SCORER_BINDING_STATE_NO_SERVICE,
        SCORER_BINDING_STATE_CLEARED,
        SCORER_BINDING_STATE_REBINDING

    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface ScorerBindingState {}

    private @ScorerBindingState int mLastScorerBindingState = SCORER_BINDING_STATE_INVALID;
    @VisibleForTesting
    ScorerServiceConnection mScorerServiceConnection;

    // Settings.Global.WIFI_OFF_TIMEOUT
    private static final String WIFI_OFF_TIMEOUT = "wifi_off_timeout";

    private final ActiveModeWarden mActiveModeWarden;
    private final ScanRequestProxy mScanRequestProxy;

    private final WifiContext mContext;
    private final FrameworkFacade mFacade;
    private final Clock mClock;
    private final PowerManager mPowerManager;
    private final AppOpsManager mAppOps;
    private final UserManager mUserManager;
    private final WifiCountryCode mCountryCode;

    /** Polls traffic stats and notifies clients */
    private final WifiTrafficPoller mWifiTrafficPoller;
    /** Tracks the persisted states for wi-fi & airplane mode */
    private final WifiSettingsStore mSettingsStore;
    /** Logs connection events and some general router and scan stats */
    private final WifiMetrics mWifiMetrics;

    private final WifiInjector mWifiInjector;
    /** Backup/Restore Module */
    private final WifiBackupRestore mWifiBackupRestore;
    private final SoftApBackupRestore mSoftApBackupRestore;
    private final WifiSettingsBackupRestore mWifiSettingsBackupRestore;
    private final BackupRestoreController mBackupRestoreController;
    private final CoexManager mCoexManager;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiConfigManager mWifiConfigManager;
    private final HalDeviceManager mHalDeviceManager;
    private final WifiBlocklistMonitor mWifiBlocklistMonitor;
    private final PasspointManager mPasspointManager;
    private final WifiLog mLog;
    private final WifiConnectivityManager mWifiConnectivityManager;
    private final ConnectHelper mConnectHelper;
    private final WifiGlobals mWifiGlobals;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;
    private final WifiPseudonymManager mWifiPseudonymManager;
    private final WifiNetworkFactory mWifiNetworkFactory;
    private @WifiManager.VerboseLoggingLevel int mVerboseLoggingLevel =
            WifiManager.VERBOSE_LOGGING_LEVEL_DISABLED;
    private boolean mVerboseLoggingEnabled = false;
    private final RemoteCallbackList<IWifiVerboseLoggingStatusChangedListener>
            mRegisteredWifiLoggingStatusListeners = new RemoteCallbackList<>();

    private final FrameworkFacade mFrameworkFacade;

    private final WifiPermissionsUtil mWifiPermissionsUtil;

    private final TetheredSoftApTracker mTetheredSoftApTracker;

    private final LohsSoftApTracker mLohsSoftApTracker;

    private final BuildProperties mBuildProperties;

    private final DefaultClientModeManager mDefaultClientModeManager;

    private final WepNetworkUsageController mWepNetworkUsageController;

    private final FeatureFlags mFeatureFlags;

    private final OnAlarmListener mWifiTimeoutListener = new OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (getWifiEnabledState() == WifiManager.WIFI_STATE_ENABLED
                    && getCurrentNetwork() == null) {
                setWifiEnabled(mContext.getPackageName(), false);
            }
        }
    };

    @VisibleForTesting
    public final CountryCodeTracker mCountryCodeTracker;
    private final MultiInternetManager mMultiInternetManager;
    private final DeviceConfigFacade mDeviceConfigFacade;
    private boolean mIsWifiServiceStarted = false;
    private static final String PACKAGE_NAME_NOT_AVAILABLE = "Not Available";
    private static final String CERT_INSTALLER_PKG = "com.android.certinstaller";

    private final WifiSettingsConfigStore mSettingsConfigStore;
    private final WifiResourceCache mResourceCache;
    private boolean mIsUsdSupported = false;

    /**
     * Callback for use with LocalOnlyHotspot to unregister requesting applications upon death.
     */
    public final class LocalOnlyRequestorCallback
            implements LocalOnlyHotspotRequestInfo.RequestingApplicationDeathCallback {
        /**
         * Called with requesting app has died.
         */
        @Override
        public void onLocalOnlyHotspotRequestorDeath(LocalOnlyHotspotRequestInfo requestor) {
            mLog.trace("onLocalOnlyHotspotRequestorDeath pid=%")
                    .c(requestor.getPid()).flush();
            mLohsSoftApTracker.stopByRequest(requestor);
        }
    }

    /**
     * Listen for phone call state events to get active data subcription id.
     */
    private class WifiPhoneStateListener extends PhoneStateListener {
        WifiPhoneStateListener(Looper looper) {
            super(new HandlerExecutor(new Handler(looper)));
        }

        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            // post operation to handler thread
            mWifiThreadRunner.post(() -> {
                Log.d(TAG, "OBSERVED active data subscription change, subId: " + subId);
                mTetheredSoftApTracker.updateSoftApCapabilityWhenCarrierConfigChanged(subId);
                mActiveModeWarden.updateSoftApCapability(
                        mTetheredSoftApTracker.getSoftApCapability(),
                        WifiManager.IFACE_IP_MODE_TETHERED);
            }, this.getClass().getSimpleName() + "#onActiveDataSubscriptionIdChanged");
        }
    }

    private class ScorerServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "ScorerServiceConnection.onServiceConnected(" + name + ", " + binder + ")");
            if (binder == null) {
                mWifiThreadRunner.post(() -> unbindScorerService(SCORER_BINDING_STATE_NULL_BINDING),
                        "ScorerServiceConnection#onServiceConnected");
            } else {
                mWifiThreadRunner.post(() -> {
                    mLastScorerBindingState = SCORER_BINDING_STATE_CONNECTED;
                }, "ScorerServiceConnection#onServiceConnected");
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "ScorerServiceConnection.onServiceDisconnected(" + name + ")");
            mWifiThreadRunner.post(() -> unbindScorerService(SCORER_BINDING_STATE_DISCONNECTED),
                    "ScorerServiceConnection#onServiceDisconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "ScorerServiceConnection.onBindingDied(" + name + ")");
            mWifiThreadRunner.post(() -> unbindScorerService(SCORER_BINDING_STATE_BINDING_DIED),
                    "ScorerServiceConnection#onBindingDied");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.i(TAG, "Wifi scorer service " + name + " - onNullBinding");
            mWifiThreadRunner.post(() -> unbindScorerService(SCORER_BINDING_STATE_NULL_BINDING),
                    "ScorerServiceConnection#onNullBinding");
        }
    }

    /**
     * Bind to the scorer service to prevent the process of the external scorer from freezing.
     */
    private void bindScorerService(int scorerUid) {
        if (mScorerServiceConnection != null) {
            Log.i(TAG, "bindScorerService() service is already bound. Unbind it now.");
            unbindScorerService(SCORER_BINDING_STATE_REBINDING);
        }

        mLastScorerBindingState = SCORER_BINDING_STATE_NO_SERVICE;
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Log.e(TAG, "PackageManager is null!");
            return;
        } else {
            String[] packageNames = pm.getPackagesForUid(scorerUid);
            for (String packageName : packageNames) {
                if (!TextUtils.isEmpty(packageName)) {
                    mScorerServiceConnection = new ScorerServiceConnection();
                    if (bindScorerServiceAsSystemUser(packageName, mScorerServiceConnection)) {
                        mLastScorerBindingState = SCORER_BINDING_STATE_BRINGING_UP;
                        return;
                    } else {
                        unbindScorerService(SCORER_BINDING_STATE_NO_SERVICE);
                    }
                } else {
                    Log.d(TAG, "bindScorerService() packageName is empty");
                }
            }
        }
    }

    private boolean bindScorerServiceAsSystemUser(@NonNull String packageName,
            @NonNull ServiceConnection serviceConnection) {
        Intent intent = new Intent("android.wifi.ScorerService");
        intent.setPackage(packageName);
        try {
            if (mContext.bindServiceAsUser(intent, serviceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                    UserHandle.SYSTEM)) {
                Log.i(TAG, "bindScorerServiceAsSystemUser(): Bringing up service: " + packageName);
                return true;
            }
        } catch (SecurityException ex) {
            Log.e(TAG, "Fail to bindServiceAsSystemUser(): " + ex);
        } catch (Exception ex) {
            // Just make sure that it won't crash WiFi process
            Log.e(TAG, "Fail to bindServiceAsSystemUser(): " + ex);
        }
        return false;
    }

    /**
     * Unbind scorer service to let the system freeze the process of the external scorer freely.
     */
    private void unbindScorerService(@ScorerBindingState int state) {
        mLastScorerBindingState = state;
        if (mScorerServiceConnection != null) {
            Log.i(TAG, "unbindScorerService(" + state + ")");
            try {
                mContext.unbindService(mScorerServiceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Fail to unbindService! " + e);
            }
            mScorerServiceConnection = null;
        } else {
            Log.i(TAG, "unbindScorerService(" + state
                    + ") mScorerServiceConnection is already null.");
        }
    }

    private final WifiLockManager mWifiLockManager;
    private final WifiMulticastLockManager mWifiMulticastLockManager;
    private final DppManager mDppManager;
    private final WifiApConfigStore mWifiApConfigStore;
    private final WifiThreadRunner mWifiThreadRunner;
    private final HandlerThread mWifiHandlerThread;
    private final MemoryStoreImpl mMemoryStoreImpl;
    private final WifiScoreCard mWifiScoreCard;
    private final WifiHealthMonitor mWifiHealthMonitor;
    private final WifiDataStall mWifiDataStall;
    private final WifiNative mWifiNative;
    private final SimRequiredNotifier mSimRequiredNotifier;
    private final MakeBeforeBreakManager mMakeBeforeBreakManager;
    private final LastCallerInfoManager mLastCallerInfoManager;
    private final @NonNull WifiDialogManager mWifiDialogManager;
    private final WifiPulledAtomLogger mWifiPulledAtomLogger;

    private final SparseArray<WifiDialogManager.DialogHandle> mWifiEnableRequestDialogHandles =
            new SparseArray<>();

    private boolean mWifiTetheringDisallowed;
    private boolean mIsBootComplete;
    private boolean mIsLocationModeEnabled;

    private WifiNetworkSelectionConfig mNetworkSelectionConfig;
    private ApplicationQosPolicyRequestHandler mApplicationQosPolicyRequestHandler;
    private final AfcManager mAfcManager;
    private final TwtManager mTwtManager;

    /**
     * The wrapper of SoftApCallback is used in WifiService internally.
     * see: {@code WifiManager.SoftApCallback}
     */
    public abstract class SoftApCallbackInternal {
        /**
         * see: {@code WifiManager.SoftApCallback#onStateChanged(int, int)}
         */
        void onStateChanged(SoftApState state) {}

        /**
         * The callback which only is used in service internally and pass to WifiManager.
         * It will base on the change to send corresponding callback as below:
         * 1. onInfoChanged(SoftApInfo)
         * 2. onInfoChanged(List<SoftApInfo>)
         * 3. onConnectedClientsChanged(SoftApInfo, List<WifiClient>)
         * 4. onConnectedClientsChanged(List<WifiClient>)
         */
        void onConnectedClientsOrInfoChanged(Map<String, SoftApInfo> infos,
                Map<String, List<WifiClient>> clients, boolean isBridged) {}

        /**
         * see:
         * {@code WifiManager.SoftApCallback#onClientsDisconnected(SoftApInfo,
         * List<WifiClient>)}
         */
        void onClientsDisconnected(@NonNull SoftApInfo info,
                @NonNull List<WifiClient> clients) {}

        /**
         * see: {@code WifiManager.SoftApCallback#onCapabilityChanged(SoftApCapability)}
         */
        void onCapabilityChanged(@NonNull SoftApCapability softApCapability) {}

        /**
         * see: {@code WifiManager.SoftApCallback#onBlockedClientConnecting(WifiClient, int)}
         */
        void onBlockedClientConnecting(@NonNull WifiClient client,
                @SapClientBlockedReason int blockedReason) {}

        /**
         * Checks the AttributionSource of a callback and returns true if the AttributionSource
         * has the correct permissions. This should be extended by child classes.
         */
        boolean checkCallbackPermission(@Nullable Object broadcastCookie) {
            return true;
        }

        /**
         * Notify register the state of soft AP changed.
         */
        public void notifyRegisterOnStateChanged(RemoteCallbackList<ISoftApCallback> callbacks,
                SoftApState state) {
            int itemCount = callbacks.beginBroadcast();
            for (int i = 0; i < itemCount; i++) {
                try {
                    if (!checkCallbackPermission(callbacks.getBroadcastCookie(i))) {
                        continue;
                    }
                    callbacks.getBroadcastItem(i).onStateChanged(state);
                } catch (RemoteException e) {
                    Log.e(TAG, "onStateChanged: remote exception -- " + e);
                }
            }
            callbacks.finishBroadcast();
        }

       /**
        * Notify register the connected clients to soft AP changed.
        *
        * @param clients connected clients to soft AP
        */
        public void notifyRegisterOnConnectedClientsOrInfoChanged(
                RemoteCallbackList<ISoftApCallback> callbacks, Map<String, SoftApInfo> infos,
                Map<String, List<WifiClient>> clients, boolean isBridged) {
            int itemCount = callbacks.beginBroadcast();
            for (int i = 0; i < itemCount; i++) {
                try {
                    if (!checkCallbackPermission(callbacks.getBroadcastCookie(i))) {
                        continue;
                    }
                    callbacks.getBroadcastItem(i).onConnectedClientsOrInfoChanged(
                            ApConfigUtil.deepCopyForSoftApInfoMap(infos),
                            ApConfigUtil.deepCopyForWifiClientListMap(
                                    clients), isBridged, false);
                } catch (RemoteException e) {
                    Log.e(TAG, "onConnectedClientsOrInfoChanged: remote exception -- " + e);
                }
            }
            callbacks.finishBroadcast();
        }

        /**
         * Notify register capability of softap changed.
         *
         * @param capability is the softap capability. {@link SoftApCapability}
         */
        public void notifyRegisterOnCapabilityChanged(RemoteCallbackList<ISoftApCallback> callbacks,
                SoftApCapability capability) {
            int itemCount = callbacks.beginBroadcast();
            for (int i = 0; i < itemCount; i++) {
                try {
                    if (!checkCallbackPermission(callbacks.getBroadcastCookie(i))) {
                        continue;
                    }
                    callbacks.getBroadcastItem(i).onCapabilityChanged(capability);
                } catch (RemoteException e) {
                    Log.e(TAG, "onCapabilityChanged: remote exception -- " + e);
                }
            }
            callbacks.finishBroadcast();
        }

        /**
         * Notify register there was a client trying to connect but device blocked the client with
         * specific reason.
         *
         * @param client        the currently blocked client.
         * @param blockedReason one of blocked reason from
         *                      {@link SapClientBlockedReason}
         */
        public void notifyRegisterOnBlockedClientConnecting(
                RemoteCallbackList<ISoftApCallback> callbacks, WifiClient client,
                int blockedReason) {
            int itemCount = callbacks.beginBroadcast();
            for (int i = 0; i < itemCount; i++) {
                try {
                    if (!checkCallbackPermission(callbacks.getBroadcastCookie(i))) {
                        continue;
                    }
                    callbacks.getBroadcastItem(i).onBlockedClientConnecting(client, blockedReason);
                } catch (RemoteException e) {
                    Log.e(TAG, "onBlockedClientConnecting: remote exception -- " + e);
                }
            }
            callbacks.finishBroadcast();
        }

        /**
         * Notify register that clients have disconnected from a soft AP instance.
         *
         * @param info The {@link SoftApInfo} of the AP.
         * @param clients The clients that have disconnected from the AP instance specified by
         *                {@code info}.
         */
        public void notifyRegisterOnClientsDisconnected(
                RemoteCallbackList<ISoftApCallback> callbacks, SoftApInfo info,
                List<WifiClient> clients) {
            int itemCount = callbacks.beginBroadcast();
            for (int i = 0; i < itemCount; i++) {
                try {
                    if (!checkCallbackPermission(callbacks.getBroadcastCookie(i))) {
                        continue;
                    }
                    callbacks.getBroadcastItem(i).onClientsDisconnected(new SoftApInfo(info),
                            clients);
                } catch (RemoteException e) {
                    Log.e(TAG, "onClientsDisconnected: remote exception -- " + e);
                }
            }
            callbacks.finishBroadcast();
        }
    }

    public WifiServiceImpl(WifiContext context, WifiInjector wifiInjector) {
        mContext = context;
        mResourceCache = mContext.getResourceCache();
        mWifiInjector = wifiInjector;
        mClock = wifiInjector.getClock();

        mSettingsConfigStore = mWifiInjector.getSettingsConfigStore();
        mFacade = mWifiInjector.getFrameworkFacade();
        mWifiMetrics = mWifiInjector.getWifiMetrics();
        mWifiTrafficPoller = mWifiInjector.getWifiTrafficPoller();
        mUserManager = mWifiInjector.getUserManager();
        mCountryCode = mWifiInjector.getWifiCountryCode();
        mActiveModeWarden = mWifiInjector.getActiveModeWarden();
        mScanRequestProxy = mWifiInjector.getScanRequestProxy();
        mSettingsStore = mWifiInjector.getWifiSettingsStore();
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mWifiLockManager = mWifiInjector.getWifiLockManager();
        mWifiMulticastLockManager = mWifiInjector.getWifiMulticastLockManager();
        mWifiBackupRestore = mWifiInjector.getWifiBackupRestore();
        mSoftApBackupRestore = mWifiInjector.getSoftApBackupRestore();
        mWifiSettingsBackupRestore = mWifiInjector.getWifiSettingsBackupRestore();
        mBackupRestoreController = mWifiInjector.getBackupRestoreController();
        mWifiApConfigStore = mWifiInjector.getWifiApConfigStore();
        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        mLog = mWifiInjector.makeLog(TAG);
        mFrameworkFacade = wifiInjector.getFrameworkFacade();
        mTetheredSoftApTracker = new TetheredSoftApTracker();
        mActiveModeWarden.registerSoftApCallback(mTetheredSoftApTracker);
        mLohsSoftApTracker = new LohsSoftApTracker();
        mActiveModeWarden.registerLohsCallback(mLohsSoftApTracker);
        mWifiNetworkSuggestionsManager = mWifiInjector.getWifiNetworkSuggestionsManager();
        mWifiNetworkFactory = mWifiInjector.getWifiNetworkFactory();
        mDppManager = mWifiInjector.getDppManager();
        mWifiThreadRunner = mWifiInjector.getWifiThreadRunner();
        mWifiHandlerThread = mWifiInjector.getWifiHandlerThread();
        mWifiConfigManager = mWifiInjector.getWifiConfigManager();
        mHalDeviceManager = mWifiInjector.getHalDeviceManager();
        mWifiBlocklistMonitor = mWifiInjector.getWifiBlocklistMonitor();
        mPasspointManager = mWifiInjector.getPasspointManager();
        mWifiScoreCard = mWifiInjector.getWifiScoreCard();
        mWifiHealthMonitor = wifiInjector.getWifiHealthMonitor();
        mMemoryStoreImpl = new MemoryStoreImpl(mContext, mWifiInjector,
                mWifiScoreCard,  mWifiHealthMonitor);
        mWifiConnectivityManager = wifiInjector.getWifiConnectivityManager();
        mWifiDataStall = wifiInjector.getWifiDataStall();
        mWifiNative = wifiInjector.getWifiNative();
        mCoexManager = wifiInjector.getCoexManager();
        mConnectHelper = wifiInjector.getConnectHelper();
        mWifiGlobals = wifiInjector.getWifiGlobals();
        mSimRequiredNotifier = wifiInjector.getSimRequiredNotifier();
        mWifiCarrierInfoManager = wifiInjector.getWifiCarrierInfoManager();
        mWifiPseudonymManager = wifiInjector.getWifiPseudonymManager();
        mMakeBeforeBreakManager = mWifiInjector.getMakeBeforeBreakManager();
        mLastCallerInfoManager = mWifiInjector.getLastCallerInfoManager();
        mWifiDialogManager = mWifiInjector.getWifiDialogManager();
        mBuildProperties = mWifiInjector.getBuildProperties();
        mDefaultClientModeManager = mWifiInjector.getDefaultClientModeManager();
        mCountryCodeTracker = new CountryCodeTracker();
        mWifiTetheringDisallowed = false;
        mMultiInternetManager = mWifiInjector.getMultiInternetManager();
        mDeviceConfigFacade = mWifiInjector.getDeviceConfigFacade();
        mFeatureFlags = mDeviceConfigFacade.getFeatureFlags();
        mApplicationQosPolicyRequestHandler = mWifiInjector.getApplicationQosPolicyRequestHandler();
        mWifiPulledAtomLogger = mWifiInjector.getWifiPulledAtomLogger();
        mAfcManager = mWifiInjector.getAfcManager();
        mTwtManager = mWifiInjector.getTwtManager();
        mWepNetworkUsageController = mWifiInjector.getWepNetworkUsageController();
        if (Environment.isSdkAtLeastB()) {
            mIsUsdSupported = mContext.getResources().getBoolean(
                    mContext.getResources().getIdentifier("config_deviceSupportsWifiUsd", "bool",
                            "android"));
        }
    }

    /**
     * Check if Wi-Fi needs to be enabled and start it if needed.
     *
     * This function is used only at boot time.
     */
    public void checkAndStartWifi() {
        mWifiThreadRunner.post(() -> {
            if (!mWifiConfigManager.loadFromStore()) {
                Log.e(TAG, "Failed to load from config store");
            }
            mWifiConfigManager.incrementNumRebootsSinceLastUse();
            // config store is read, check if verbose logging is enabled.
            enableVerboseLoggingInternal(
                    mSettingsConfigStore.get(WIFI_VERBOSE_LOGGING_ENABLED)
                            ? WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED
                            : WifiManager.VERBOSE_LOGGING_LEVEL_DISABLED);
            // Check if wi-fi needs to be enabled
            boolean wifiEnabled = mSettingsStore.isWifiToggleEnabled();
            Log.i(TAG,
                    "WifiService starting up with Wi-Fi " + (wifiEnabled ? "enabled" : "disabled"));

            mWifiInjector.getWifiScanAlwaysAvailableSettingsCompatibility().initialize();
            mWifiInjector.getWifiNotificationManager().createNotificationChannels();
            // Old design, flag is disabled.
            if (!mFeatureFlags.wepDisabledInApm()) {
                mWifiGlobals.setWepAllowed(mSettingsConfigStore.get(WIFI_WEP_ALLOWED));
                // Align the value between config store (i.e.WifiConfigStore.xml) and WifiGlobals.
                mSettingsConfigStore.registerChangeListener(WIFI_WEP_ALLOWED,
                        (key, value) -> {
                            if (mWifiGlobals.isWepAllowed() != value) {
                                handleWepAllowedChanged(value);
                                Log.i(TAG, "Wep allowed is changed to "
                                        + value);
                            }
                        },
                        new Handler(mWifiHandlerThread.getLooper()));
            }
            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            int state = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                                    TelephonyManager.SIM_STATE_UNKNOWN);
                            if (TelephonyManager.SIM_STATE_ABSENT == state) {
                                Log.d(TAG, "resetting networks because SIM was removed");
                                resetCarrierNetworks(RESET_SIM_REASON_SIM_REMOVED);
                            }
                        }
                    },
                    new IntentFilter(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED),
                    null,
                    new Handler(mWifiHandlerThread.getLooper()));

            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            int state = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                                    TelephonyManager.SIM_STATE_UNKNOWN);
                            if (TelephonyManager.SIM_STATE_LOADED == state) {
                                Log.d(TAG, "resetting networks because SIM was loaded");
                                resetCarrierNetworks(RESET_SIM_REASON_SIM_INSERTED);
                            }
                        }
                    },
                    new IntentFilter(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED),
                    null,
                    new Handler(mWifiHandlerThread.getLooper()));

            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        private int mLastSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            final int subId = intent.getIntExtra("subscription",
                                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                            if (subId != mLastSubId) {
                                Log.d(TAG, "resetting networks as default data SIM is changed");
                                resetCarrierNetworks(RESET_SIM_REASON_DEFAULT_DATA_SIM_CHANGED);
                                mLastSubId = subId;
                                mWifiDataStall.resetPhoneStateListener();
                            }
                        }
                    },
                    new IntentFilter(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED),
                    null,
                    new Handler(mWifiHandlerThread.getLooper()));

            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            String countryCode = intent.getStringExtra(
                                    TelephonyManager.EXTRA_NETWORK_COUNTRY);
                            Log.d(TAG, "Country code changed to :" + countryCode);
                            mCountryCode.setTelephonyCountryCodeAndUpdate(countryCode);
                        }
                    },
                    new IntentFilter(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED),
                    null,
                    new Handler(mWifiHandlerThread.getLooper()));

            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            Log.d(TAG, "locale changed");
                            resetNotificationManager();
                            mResourceCache.handleLocaleChange();
                        }
                    },
                    new IntentFilter(Intent.ACTION_LOCALE_CHANGED),
                    null,
                    new Handler(mWifiHandlerThread.getLooper()));

            if (SdkLevel.isAtLeastT()) {
                mContext.registerReceiver(
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                Log.d(TAG, "user restrictions changed");
                                onUserRestrictionsChanged();
                            }
                        },
                        new IntentFilter(UserManager.ACTION_USER_RESTRICTIONS_CHANGED),
                        null,
                        new Handler(mWifiHandlerThread.getLooper()));
                mWifiTetheringDisallowed = mUserManager.getUserRestrictions()
                        .getBoolean(UserManager.DISALLOW_WIFI_TETHERING);
            }

            // Adding optimizations of only receiving broadcasts when wifi is enabled
            // can result in race conditions when apps toggle wifi in the background
            // without active user involvement. Always receive broadcasts.
            registerForBroadcasts();
            mInIdleMode = mPowerManager.isDeviceIdleMode();

            mActiveModeWarden.start();
            registerForCarrierConfigChange();
            mWifiInjector.getAdaptiveConnectivityEnabledSettingObserver().initialize();
            mIsWifiServiceStarted = true;
        }, TAG + "#checkAndStartWifi");
    }

    private void setPulledAtomCallbacks() {
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_MODULE_INFO);
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_SETTING_INFO);
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_COMPLEX_SETTING_INFO);
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_CONFIGURED_NETWORK_INFO);
    }

    private void updateLocationMode() {
        mIsLocationModeEnabled = mWifiPermissionsUtil.isLocationModeEnabled();
        mWifiConnectivityManager.setLocationModeEnabled(mIsLocationModeEnabled);
        mWifiNative.setLocationModeEnabled(mIsLocationModeEnabled);
    }

    /**
     * Find which user restrictions have changed and take corresponding actions
     */
    @VisibleForTesting
    public void onUserRestrictionsChanged() {
        final Bundle restrictions = mUserManager.getUserRestrictions();
        final boolean newWifiTetheringDisallowed =
                restrictions.getBoolean(UserManager.DISALLOW_WIFI_TETHERING);

        if (newWifiTetheringDisallowed != mWifiTetheringDisallowed) {
            if (newWifiTetheringDisallowed) {
                mLog.info("stopSoftAp DISALLOW_WIFI_TETHERING set").flush();
                stopSoftApInternal(WifiManager.IFACE_IP_MODE_TETHERED);
            }
            mWifiTetheringDisallowed = newWifiTetheringDisallowed;
        }
    }

    private void resetCarrierNetworks(@ClientModeImpl.ResetSimReason int resetReason) {
        mResourceCache.reset();
        Log.d(TAG, "resetting carrier networks since SIM was changed");
        if (resetReason == RESET_SIM_REASON_SIM_INSERTED) {
            // clear all SIM related notifications since some action was taken to address
            // "missing" SIM issue
            mSimRequiredNotifier.dismissSimRequiredNotification();
        } else {
            mWifiConfigManager.resetSimNetworks();
            mWifiNetworkSuggestionsManager.resetSimNetworkSuggestions();
            mPasspointManager.resetSimPasspointNetwork();
            mWifiConfigManager.stopRestrictingAutoJoinToSubscriptionId();
        }

        // do additional handling if we are current connected to a sim auth network
        for (ClientModeManager cmm : mActiveModeWarden.getClientModeManagers()) {
            cmm.resetSimAuthNetworks(resetReason);
        }
        mWifiThreadRunner.post(mWifiNetworkSuggestionsManager::updateCarrierPrivilegedApps,
                TAG + "#resetCarrierNetworks$1");
        if (resetReason == RESET_SIM_REASON_SIM_INSERTED) {
            // clear the blocklists in case any SIM based network were disabled due to the SIM
            // not being available.
            mWifiConfigManager.enableTemporaryDisabledNetworks();
            mWifiConnectivityManager.forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
        } else {
            // Remove ephemeral carrier networks from Carrier unprivileged Apps, which will lead to
            // a disconnection. Privileged App will handle by the
            // mWifiNetworkSuggestionsManager#updateCarrierPrivilegedApps
            mWifiThreadRunner.post(() -> mWifiConfigManager
                    .removeEphemeralCarrierNetworks(mWifiCarrierInfoManager
                            .getCurrentCarrierPrivilegedPackages()),
                    TAG + "#resetCarrierNetworks$2");
        }
    }

    public void handleBootCompleted() {
        mWifiThreadRunner.post(() -> {
            Log.d(TAG, "Handle boot completed");
            mIsBootComplete = true;
            // Register for system broadcasts.
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_USER_REMOVED);
            intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            intentFilter.addAction(Intent.ACTION_SHUTDOWN);
            intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            String action = intent.getAction();
                            if (Intent.ACTION_USER_REMOVED.equals(action)) {
                                UserHandle userHandle =
                                        intent.getParcelableExtra(Intent.EXTRA_USER);
                                if (userHandle == null) {
                                    Log.e(TAG,
                                            "User removed broadcast received with no user handle");
                                    return;
                                }
                                mWifiConfigManager
                                        .removeNetworksForUser(userHandle.getIdentifier());
                            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED
                                    .equals(action)) {
                                int state = intent.getIntExtra(
                                        BluetoothAdapter.EXTRA_CONNECTION_STATE,
                                        BluetoothAdapter.STATE_DISCONNECTED);
                                boolean isConnected =
                                        state != BluetoothAdapter.STATE_DISCONNECTED;
                                mWifiGlobals.setBluetoothConnected(isConnected);
                                for (ClientModeManager cmm :
                                        mActiveModeWarden.getClientModeManagers()) {
                                    cmm.onBluetoothConnectionStateChanged();
                                }
                            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                        BluetoothAdapter.STATE_OFF);
                                boolean isEnabled = state != BluetoothAdapter.STATE_OFF;
                                mWifiGlobals.setBluetoothEnabled(isEnabled);
                                for (ClientModeManager cmm :
                                        mActiveModeWarden.getClientModeManagers()) {
                                    cmm.onBluetoothConnectionStateChanged();
                                }
                            } else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED
                                    .equals(action)) {
                                handleIdleModeChanged();
                            } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
                                handleShutDown();
                            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION) ||
                                    action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                                setWifiTimeout();
                            }
                        }
                    },
                    intentFilter,
                    null,
                    new Handler(mWifiHandlerThread.getLooper()));
            mMemoryStoreImpl.start();
            mPasspointManager.initializeProvisioner(
                    mWifiInjector.getPasspointProvisionerHandlerThread().getLooper());
            mWifiInjector.getWifiNetworkFactory().register();
            mWifiInjector.getUntrustedWifiNetworkFactory().register();
            mWifiInjector.getRestrictedWifiNetworkFactory().register();
            mWifiInjector.getOemWifiNetworkFactory().register();
            mWifiInjector.getMultiInternetWifiNetworkFactory().register();
            mWifiInjector.getWifiP2pConnection().handleBootCompleted();
            // Start to listen country code change to avoid query supported channels causes boot
            // time increased.
            mCountryCode.registerListener(mCountryCodeTracker);
            mWifiInjector.getSarManager().handleBootCompleted();
            mWifiInjector.getSsidTranslator().handleBootCompleted();
            mWifiInjector.getPasspointManager().handleBootCompleted();
            mWifiInjector.getInterfaceConflictManager().handleBootCompleted();
            mWifiInjector.getHalDeviceManager().handleBootCompleted();
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(WIFI_OFF_TIMEOUT),
                    false,
                    new ContentObserver(new Handler(mContext.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            super.onChange(selfChange);
                            setWifiTimeout();
                        }
                    }
            );
            // HW capabilities is ready after boot completion.
            if (!mWifiGlobals.isInsecureEnterpriseConfigurationAllowed()) {
                mWifiConfigManager.updateTrustOnFirstUseFlag(isTrustOnFirstUseSupported());
            }
            updateVerboseLoggingEnabled();
            mWifiInjector.getWifiDeviceStateChangeManager().handleBootCompleted();
            if (mFeatureFlags.wepDisabledInApm()
                    && mWepNetworkUsageController != null) {
                mWepNetworkUsageController.handleBootCompleted();
            }
            setPulledAtomCallbacks();
            mTwtManager.registerWifiNativeTwtEvents();
            mContext.registerReceiverForAllUsers(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (mVerboseLoggingEnabled) {
                                Log.v(TAG, "onReceive: MODE_CHANGED_ACTION: intent=" + intent);
                            }
                            updateLocationMode();
                        }
                    },
                    new IntentFilter(LocationManager.MODE_CHANGED_ACTION),
                    null,
                    new Handler(mWifiHandlerThread.getLooper()));
            updateLocationMode();

            if (SdkLevel.isAtLeastT()) {
                UwbManager uwbManager =
                        mContext.getSystemService(UwbManager.class);
                if (uwbManager != null) {
                    uwbManager.registerAdapterStateCallback(new HandlerExecutor(new Handler(
                            mWifiHandlerThread.getLooper())), new UwbAdapterStateListener());
                }
            }

            if (SdkLevel.isAtLeastV()) {
                ThreadNetworkManager threadManager =
                        mContext.getSystemService(ThreadNetworkManager.class);
                if (threadManager != null) {
                    List<ThreadNetworkController> threadNetworkControllers =
                            threadManager.getAllThreadNetworkControllers();
                    if (threadNetworkControllers.size() > 0) {
                        ThreadNetworkController threadNetworkController =
                                threadNetworkControllers.get(0);
                        if (threadNetworkController != null) {
                            threadNetworkController.registerStateCallback(
                                new HandlerExecutor(new Handler(mWifiHandlerThread.getLooper())),
                                new ThreadStateListener());
                        }
                    }
                }
            }
        }, TAG + "#handleBootCompleted");
    }

    public void handleUserSwitch(int userId) {
        Log.d(TAG, "Handle user switch " + userId);

        mWifiThreadRunner.post(() -> {
            mWifiConfigManager.handleUserSwitch(userId);
            resetNotificationManager();
        }, TAG + "#handleUserSwitch");
    }

    public void handleUserUnlock(int userId) {
        Log.d(TAG, "Handle user unlock " + userId);
        mWifiThreadRunner.post(() -> mWifiConfigManager.handleUserUnlock(userId),
                TAG + "#handleUserUnlock");
    }

    public void handleUserStop(int userId) {
        Log.d(TAG, "Handle user stop " + userId);
        mWifiThreadRunner.post(() -> mWifiConfigManager.handleUserStop(userId),
                TAG + "#handleUserStop");
    }

    private void setWifiTimeout() {
        long wifiTimeoutMillis = Settings.Global.getLong(mContext.getContentResolver(),
                WIFI_OFF_TIMEOUT, 0);
        AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
        alarmManager.cancel(mWifiTimeoutListener);
        if (wifiTimeoutMillis != 0) {
            final long timeout = SystemClock.elapsedRealtime() + wifiTimeoutMillis;
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeout,
                    TAG, new HandlerExecutor(new Handler(mContext.getMainLooper())), null,
                    mWifiTimeoutListener);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#startScan}
     *
     * @param packageName Package name of the app that requests wifi scan.
     * @param featureId The feature in the package
     */
    @Override
    public boolean startScan(String packageName, String featureId) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);

        long ident = Binder.clearCallingIdentity();
        mLog.info("startScan uid=%").c(callingUid).flush();
        synchronized (this) {
            if (mInIdleMode) {
                // Need to send an immediate scan result broadcast in case the
                // caller is waiting for a result ..

                // TODO: investigate if the logic to cancel scans when idle can move to
                // WifiScanningServiceImpl.  This will 1 - clean up WifiServiceImpl and 2 -
                // avoid plumbing an awkward path to report a cancelled/failed scan.  This will
                // be sent directly until b/31398592 is fixed.
                sendFailedScanBroadcast();
                mScanPending = true;
                return false;
            }
        }
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(packageName, featureId, callingUid,
                    null);
            mLastCallerInfoManager.put(WifiManager.API_START_SCAN, Process.myTid(),
                    callingUid, Binder.getCallingPid(), packageName, true);
            Boolean scanSuccess = mWifiThreadRunner.call(() ->
                    mScanRequestProxy.startScan(callingUid, packageName), null,
                    TAG + "#startScan");
            if (scanSuccess == null) {
                sendFailedScanBroadcast();
                return false;
            }
            if (!scanSuccess) {
                Log.e(TAG, "Failed to start scan");
                return false;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Permission violation - startScan not allowed for"
                    + " uid=" + callingUid + ", packageName=" + packageName + ", reason=" + e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return true;
    }

    // Send a failed scan broadcast to indicate the current scan request failed.
    private void sendFailedScanBroadcast() {
        // clear calling identity to send broadcast
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            final boolean scanSucceeded = false;
            Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_RESULTS_UPDATED, scanSucceeded);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL, null,
                    createBroadcastOptionsForScanResultsAvailable(scanSucceeded));
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }

    }

    /**
     * WPS support in Client mode is deprecated.  Return null.
     */
    @Override
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        // while CLs are in flight, return null here, will be removed (b/72423090)
        enforceNetworkStackPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getCurrentNetworkWpsNfcConfigurationToken uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        return null;
    }

    private boolean mInIdleMode;
    private boolean mScanPending;

    private void handleIdleModeChanged() {
        boolean doScan = false;
        synchronized (this) {
            boolean idle = mPowerManager.isDeviceIdleMode();
            if (mInIdleMode != idle) {
                mInIdleMode = idle;
                if (!idle) { // exiting doze mode
                    if (mScanPending) {
                        mScanPending = false;
                        doScan = true;
                    }
                }
                mActiveModeWarden.onIdleModeChanged(idle);
            }
        }
        if (doScan) {
            // Someone requested a scan while we were idle; do a full scan now.
            // A security check of the caller's identity was made when the request arrived via
            // Binder. Now we'll pass the current process's identity to startScan().
            startScan(mContext.getOpPackageName(), mContext.getAttributionTag());
        }
    }

    private void handleShutDown() {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleShutDown");
        }
        // Direct call to notify ActiveModeWarden as soon as possible with the assumption that
        // notifyShuttingDown() doesn't have codes that may cause concurrentModificationException,
        // e.g., access to a collection.
        mActiveModeWarden.notifyShuttingDown();

        // There is no explicit disconnection event in clientModeImpl during shutdown.
        // Call resetConnectionState() so that connection duration is calculated
        // before memory store write triggered by mMemoryStoreImpl.stop().
        mWifiScoreCard.resetAllConnectionStates();
        mMemoryStoreImpl.stop();
        mWifiConfigManager.writeDataToStorage();
        mWifiNetworkSuggestionsManager.handleShutDown();
    }

    private boolean checkNetworkSettingsPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_SETTINGS, pid, uid)
                == PERMISSION_GRANTED;
    }

    private boolean checkNetworkSetupWizardPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_SETUP_WIZARD, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkMainlineNetworkStackPermission(int pid, int uid) {
        return mContext.checkPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkNetworkStackPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_STACK, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkNetworkManagedProvisioningPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_MANAGED_PROVISIONING,
                pid, uid) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkManageDeviceAdminsPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.MANAGE_DEVICE_ADMINS,
                pid, uid) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Helper method to check if the entity initiating the binder call has any of the signature only
     * permissions. Not to be confused with the concept of privileged apps, which are system apps
     * with allow-listed "privileged" permissions.
     */
    private boolean isPrivileged(int pid, int uid) {
        return checkNetworkSettingsPermission(pid, uid)
                || checkNetworkSetupWizardPermission(pid, uid)
                || checkNetworkStackPermission(pid, uid)
                || checkNetworkManagedProvisioningPermission(pid, uid)
                || mWifiPermissionsUtil.isSignedWithPlatformKey(uid);
    }

    /**
     * Helper method to check if the entity initiating the binder call has setup wizard or settings
     * permissions.
     */
    private boolean isSettingsOrSuw(int pid, int uid) {
        return checkNetworkSettingsPermission(pid, uid)
                || checkNetworkSetupWizardPermission(pid, uid);
    }

    /** Helper method to check if the entity initiating the binder call is a DO/PO app. */
    private boolean isDeviceOrProfileOwner(int uid, String packageName) {
        return mWifiPermissionsUtil.isDeviceOwner(uid, packageName)
                || mWifiPermissionsUtil.isProfileOwner(uid, packageName);
    }

    private void enforceNetworkSettingsPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.NETWORK_SETTINGS,
                "WifiService");
    }

    private boolean checkAnyPermissionOf(String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private void enforceAnyPermissionOf(String... permissions) {
        if (!checkAnyPermissionOf(permissions)) {
            throw new SecurityException("Requires one of the following permissions: "
                    + String.join(", ", permissions) + ".");
        }
    }

    private void enforceNetworkStackPermission() {
        // TODO(b/142554155): Only check for MAINLINE_NETWORK_STACK permission
        boolean granted = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_STACK)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            return;
        }
        mContext.enforceCallingOrSelfPermission(
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, "WifiService");
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "WifiService");
    }

    private void enforceRestartWifiSubsystemPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.RESTART_WIFI_SUBSYSTEM,
                "WifiService");
    }

    /**
     * Checks whether the caller can change the wifi state.
     * Possible results:
     * 1. Operation is allowed. No exception thrown, and AppOpsManager.MODE_ALLOWED returned.
     * 2. Operation is not allowed, and caller must be told about this. SecurityException is thrown.
     * 3. Operation is not allowed, and caller must not be told about this (i.e. must silently
     * ignore the operation). No exception is thrown, and AppOpsManager.MODE_IGNORED returned.
     */
    @CheckResult
    private int enforceChangePermission(String callingPackage) {
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (checkNetworkSettingsPermission(Binder.getCallingPid(), Binder.getCallingUid())) {
            return MODE_ALLOWED;
        }
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiService");

        return mAppOps.noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Binder.getCallingUid(), callingPackage);
    }

    private void enforceReadCredentialPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_WIFI_CREDENTIAL,
                                                "WifiService");
    }

    private void enforceMulticastChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                "WifiService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    private void enforceLocationPermission(String pkgName, @Nullable String featureId, int uid) {
        mWifiPermissionsUtil.enforceLocationPermission(pkgName, featureId, uid);
    }

    private void enforceCoarseLocationPermission(@Nullable String pkgName,
            @Nullable String featureId, int uid) {
        mWifiPermissionsUtil.enforceCoarseLocationPermission(pkgName, featureId, uid);
    }

    private void enforceLocationPermissionInManifest(int uid, boolean isCoarseOnly) {
        mWifiPermissionsUtil.enforceLocationPermissionInManifest(uid, isCoarseOnly);
    }

    /**
     * Validates if the calling user is valid.
     *
     * @throws a {@link SecurityException} if the calling user is not valid.
     */
    private void enforceValidCallingUser() {
        if (!isValidCallingUser()) {
            throw new SecurityException(
                    "Calling user " + Binder.getCallingUserHandle() + " is not the SYSTEM user, "
                            + "the current user, or a profile of the current user, "
                            + "thus not allowed to make changes to WIFI.");
        }
    }

    /**
     * Checks if the calling user is valid on Automotive devices..
     *
     * @return true if any of the following conditions are true:
     *     <li>The device is not an Automotive device.
     *     <li>the calling user is the system user.
     *     <li>the calling user is the current user.
     *     <li>the calling user belongs to the same profile group as the current user.
     */
    private boolean isValidCallingUser() {
        // TODO(b/360488316): Ideally UserManager#isVisibleBackgroundUsersEnabled() should be used
        // but it is a hidden API. We rely on FEATURE_AUTOMOTIVE only, because we cannot access
        // the RRO config for R.bool.config_multiuserVisibleBackgroundUsers.
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return true;
        }
        UserHandle callingUser = Binder.getCallingUserHandle();

        final long ident = Binder.clearCallingIdentity();
        try {
            UserHandle currentUser =
                    UserHandle.of(mWifiInjector.getWifiPermissionsWrapper().getCurrentUser());
            if (UserHandle.SYSTEM.equals(callingUser)
                    || callingUser.equals(currentUser)
                    || mUserManager.isSameProfileGroup(callingUser, currentUser)) {
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return false;
    }

    /**
     * Helper method to check if the app is allowed to access public API's deprecated in
     * {@link Build.VERSION_CODES#Q}.
     * Note: Invoke mAppOps.checkPackage(uid, packageName) before to ensure correct package name.
     */
    private boolean isTargetSdkLessThanQOrPrivileged(String packageName, int pid, int uid) {
        return (mWifiPermissionsUtil.isTargetSdkLessThan(packageName, Build.VERSION_CODES.Q, uid)
                && !isGuestUser())
                || isPrivileged(pid, uid)
                || mWifiPermissionsUtil.isAdmin(uid, packageName)
                || mWifiPermissionsUtil.isSystem(packageName, uid);
    }

    private boolean isGuestUser() {
        long ident = Binder.clearCallingIdentity();
        try {
            return mWifiPermissionsUtil.isGuestUser();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Helper method to check if the app is allowed to access public API's deprecated in
     * {@link Build.VERSION_CODES#R}.
     * Note: Invoke mAppOps.checkPackage(uid, packageName) before to ensure correct package name.
     */
    private boolean isTargetSdkLessThanROrPrivileged(String packageName, int pid, int uid) {
        return (mWifiPermissionsUtil.isTargetSdkLessThan(packageName, Build.VERSION_CODES.R, uid)
                && !isGuestUser())
                || isPrivileged(pid, uid)
                || mWifiPermissionsUtil.isAdmin(uid, packageName)
                || mWifiPermissionsUtil.isSystem(packageName, uid);
    }

    private boolean isPlatformOrTargetSdkLessThanT(String packageName, int uid) {
        if (!SdkLevel.isAtLeastT()) {
            return true;
        }
        return mWifiPermissionsUtil.isTargetSdkLessThan(packageName, Build.VERSION_CODES.TIRAMISU,
                uid);
    }

    private boolean isPlatformOrTargetSdkLessThanU(String packageName, int uid) {
        if (!SdkLevel.isAtLeastU()) {
            return true;
        }
        return mWifiPermissionsUtil.isTargetSdkLessThan(packageName,
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE, uid);
    }

    /**
     * Get the current primary ClientModeManager in a thread safe manner, but blocks on the main
     * Wifi thread.
     */
    private ClientModeManager getPrimaryClientModeManagerBlockingThreadSafe() {
        return mWifiThreadRunner.call(
                () -> mActiveModeWarden.getPrimaryClientModeManager(),
                mDefaultClientModeManager, TAG + "#getPrimaryClientModeManagerBlockingThreadSafe");
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    @Override
    public synchronized boolean setWifiEnabled(String packageName, boolean enable) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        enforceValidCallingUser();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        boolean isPrivileged = isPrivileged(callingPid, callingUid);
        boolean isThirdParty = !isPrivileged
                && !isDeviceOrProfileOwner(callingUid, packageName)
                && !mWifiPermissionsUtil.isSystem(packageName, callingUid);
        boolean isTargetSdkLessThanQ = mWifiPermissionsUtil.isTargetSdkLessThan(packageName,
                Build.VERSION_CODES.Q, callingUid) && !isGuestUser();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (isThirdParty && !isTargetSdkLessThanQ) {
            mLog.info("setWifiEnabled not allowed for uid=%").c(callingUid).flush();
            return false;
        }

        // If Satellite mode is enabled, Wifi can not be turned on/off
        if (mSettingsStore.isSatelliteModeOn()) {
            mLog.info("setWifiEnabled not allowed as satellite mode is on.").flush();
            return false;
        }

        // If Airplane mode is enabled, only privileged apps are allowed to toggle Wifi
        if (mSettingsStore.isAirplaneModeOn() && !isPrivileged) {
            mLog.err("setWifiEnabled in Airplane mode: only Settings can toggle wifi").flush();
            return false;
        }

        // Pre-S interface priority is solely based on interface type, which allows STA to delete AP
        // for any requester. To prevent non-privileged apps from deleting a tethering AP by
        // enabling Wi-Fi, only allow privileged apps to toggle Wi-Fi if tethering AP is up.
        if (!SdkLevel.isAtLeastS() && !isPrivileged
                && mTetheredSoftApTracker.getState().getState() == WIFI_AP_STATE_ENABLED) {
            mLog.err("setWifiEnabled with SoftAp enabled: only Settings can toggle wifi").flush();
            return false;
        }

        long ident = Binder.clearCallingIdentity();
        try {
            // If user restriction is set, only DO/PO is allowed to toggle wifi
            if (SdkLevel.isAtLeastT() && mUserManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_CHANGE_WIFI_STATE,
                    UserHandle.getUserHandleForUid(callingUid))
                    && !isDeviceOrProfileOwner(callingUid, packageName)) {
                mLog.err(
                        "setWifiEnabled with user restriction: only DO/PO can toggle wifi").flush();
                return false;
            }
        }  finally {
            Binder.restoreCallingIdentity(ident);
        }

        // Show a user-confirmation dialog for legacy third-party apps targeting less than Q.
        if (enable && isTargetSdkLessThanQ && isThirdParty
                && showDialogWhenThirdPartyAppsEnableWifi()) {
            mLog.info("setWifiEnabled must show user confirmation dialog for uid=%").c(callingUid)
                    .flush();
            mWifiThreadRunner.post(() -> {
                if (mActiveModeWarden.getWifiState()
                        == WIFI_STATE_ENABLED) {
                    // Wi-Fi already enabled; don't need to show dialog.
                    return;
                }
                showWifiEnableRequestDialog(callingUid, callingPid, packageName);
            }, TAG + "#setWifiEnabled");
            return true;
        }
        setWifiEnabledInternal(packageName, enable, callingUid, callingPid, isPrivileged);
        return true;
    }

    @AnyThread
    private void setWifiEnabledInternal(String packageName, boolean enable,
            int callingUid, int callingPid, boolean isPrivileged) {
        mLog.info("setWifiEnabled package=% uid=% enable=% isPrivileged=%").c(packageName)
                .c(callingUid).c(enable).c(isPrivileged).flush();
        long ident = Binder.clearCallingIdentity();
        try {
            if (!mSettingsStore.handleWifiToggled(enable)) {
                // Nothing to do if wifi cannot be toggled
                return;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (enable) {
            // Clear out all outstanding wifi enable request dialogs.
            mWifiThreadRunner.post(() -> {
                for (int i = 0; i < mWifiEnableRequestDialogHandles.size(); i++) {
                    mWifiEnableRequestDialogHandles.valueAt(i).dismissDialog();
                }
                mWifiEnableRequestDialogHandles.clear();
            }, TAG + "#setWifiEnabledInternal$1");
        }
        if (mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)) {
            if (enable) {
                mWifiThreadRunner.post(
                        () -> mWifiConnectivityManager.setAutoJoinEnabledExternal(true, false),
                        TAG + "#setWifiEnabledInternal$2");
                mWifiMetrics.logUserActionEvent(UserActionEvent.EVENT_TOGGLE_WIFI_ON);
            } else {
                WifiInfo wifiInfo = mActiveModeWarden.getConnectionInfo();
                mWifiMetrics.logUserActionEvent(UserActionEvent.EVENT_TOGGLE_WIFI_OFF,
                        wifiInfo == null ? -1 : wifiInfo.getNetworkId());
            }
        }
        if (!enable) {
            mWifiInjector.getInterfaceConflictManager().reset();
        }
        mWifiMetrics.incrementNumWifiToggles(isPrivileged, enable);
        mWifiMetrics.reportWifiStateChanged(enable, mWifiInjector.getWakeupController().isUsable(),
                false);
        mActiveModeWarden.wifiToggled(new WorkSource(callingUid, packageName));
        mLastCallerInfoManager.put(WifiManager.API_WIFI_ENABLED, Process.myTid(),
                callingUid, callingPid, packageName, enable);
    }

    private void showWifiEnableRequestDialog(int uid, int pid, @NonNull String packageName) {
        String appName;
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            appName = appInfo.loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            appName = packageName;
        }
        WifiDialogManager.SimpleDialogCallback dialogCallback =
                new WifiDialogManager.SimpleDialogCallback() {
                    @Override
                    public void onPositiveButtonClicked() {
                        mLog.info("setWifiEnabled dialog accepted for package=% uid=%")
                                .c(packageName).c(uid).flush();
                        mWifiEnableRequestDialogHandles.delete(uid);
                        setWifiEnabledInternal(packageName, true, uid, pid, false);
                    }

                    @Override
                    public void onNegativeButtonClicked() {
                        mLog.info("setWifiEnabled dialog declined for package=% uid=%")
                                .c(packageName).c(uid).flush();
                        mWifiEnableRequestDialogHandles.delete(uid);
                    }

                    @Override
                    public void onNeutralButtonClicked() {
                        // Not used.
                    }

                    @Override
                    public void onCancelled() {
                        mLog.info("setWifiEnabled dialog cancelled for package=% uid=%")
                                .c(packageName).c(uid).flush();
                        mWifiEnableRequestDialogHandles.delete(uid);
                    }
                };
        Resources res = mContext.getResources();
        if (mWifiEnableRequestDialogHandles.get(uid) != null) {
            mLog.info("setWifiEnabled dialog already launched for package=% uid=%").c(packageName)
                    .c(uid).flush();
            return;
        }
        WifiDialogManager.DialogHandle dialogHandle = mWifiDialogManager.createSimpleDialog(
                res.getString(R.string.wifi_enable_request_dialog_title, appName),
                res.getString(R.string.wifi_enable_request_dialog_message),
                res.getString(R.string.wifi_enable_request_dialog_positive_button),
                res.getString(R.string.wifi_enable_request_dialog_negative_button),
                null /* neutralButtonText */,
                dialogCallback,
                mWifiThreadRunner);
        mWifiEnableRequestDialogHandles.put(uid, dialogHandle);
        dialogHandle.launchDialog();
        mLog.info("setWifiEnabled dialog launched for package=% uid=%").c(packageName)
                .c(uid).flush();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void registerSubsystemRestartCallback(ISubsystemRestartCallback callback) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback is null");
        }
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("registerSubsystemRestartCallback uid=%").c(Binder.getCallingUid()).flush();
        }

        mWifiThreadRunner.post(() -> {
            if (!mActiveModeWarden.registerSubsystemRestartCallback(callback)) {
                Log.e(TAG, "registerSubsystemRestartCallback: Failed to register callback");
            }
        }, TAG + "#registerSubsystemRestartCallback");
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void unregisterSubsystemRestartCallback(ISubsystemRestartCallback callback) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback is null");
        }
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterSubsystemRestartCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> {
            if (!mActiveModeWarden.unregisterSubsystemRestartCallback(callback)) {
                Log.e(TAG, "unregisterSubsystemRestartCallback: Failed to register callback");
            }
        }, TAG + "#unregisterSubsystemRestartCallback");
    }

    /**
     * See {@link WifiManager#addWifiNetworkStateChangedListener(
     * Executor, WifiManager.WifiNetworkStateChangedListener)}
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public void addWifiNetworkStateChangedListener(IWifiNetworkStateChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException();
        }
        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("addWifiNetworkStateChangedListener uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> {
            mActiveModeWarden.addWifiNetworkStateChangedListener(listener);
        }, TAG + "#addWifiNetworkStateChangedListener");
    }

    /**
     * See {@link WifiManager#removeWifiNetworkStateChangedListener(
     * WifiManager.WifiNetworkStateChangedListener)}
     * @param listener
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public void removeWifiNetworkStateChangedListener(IWifiNetworkStateChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException();
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("removeWifiNetworkStateChangedListener uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> {
            mActiveModeWarden.removeWifiNetworkStateChangedListener(listener);
        }, TAG + "#removeWifiNetworkStateChangedListener");
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void restartWifiSubsystem() {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        enforceRestartWifiSubsystemPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("restartWifiSubsystem uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> {
            WifiInfo wifiInfo = mActiveModeWarden.getConnectionInfo();
            mWifiMetrics.logUserActionEvent(UserActionEvent.EVENT_RESTART_WIFI_SUB_SYSTEM,
                    wifiInfo == null ? -1 : wifiInfo.getNetworkId());
            mWifiInjector.getSelfRecovery().trigger(REASON_API_CALL);
        }, TAG + "#restartWifiSubsystem");
    }

    /**
     * see {@link WifiManager#getWifiState()}
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    @Override
    public int getWifiEnabledState() {
        enforceAccessPermission();
        int state = mActiveModeWarden.getWifiState();
        // If the wifi is enabling, call it on the wifi handler to get the state after wifi enabled.
        // This is only needed for pre-T.
        if (state == WifiManager.WIFI_STATE_ENABLING && !SdkLevel.isAtLeastT()) {
            state = mWifiThreadRunner.call(() -> mActiveModeWarden.getWifiState(),
                    WifiManager.WIFI_STATE_ENABLING, TAG + "#getWifiEnabledState");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiEnabledState uid=% state=%").c(Binder.getCallingUid()).c(
                    state).flush();
        }
        return state;
    }

    /**
     * See {@link WifiManager#addWifiStateChangedListener(Executor, WifiStateChangedListener)}
     */
    public void addWifiStateChangedListener(@NonNull IWifiStateChangedListener listener) {
        enforceAccessPermission();
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("addWifiStateChangedListener uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> mActiveModeWarden.addWifiStateChangedListener(listener),
                TAG + "#addWifiStateChangedListener");
    }

    /**
     * See {@link WifiManager#removeWifiStateChangedListener(WifiStateChangedListener)}
     */
    public void removeWifiStateChangedListener(@NonNull IWifiStateChangedListener listener) {
        enforceAccessPermission();
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("removeWifiStateChangedListener uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> mActiveModeWarden.removeWifiStateChangedListener(listener),
                TAG + "#removeWifiStateChangedListener");
    }

    /**
     * see {@link WifiManager#getWifiApState()}
     * @return One of {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     */
    @Override
    public int getWifiApEnabledState() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiApEnabledState uid=%").c(Binder.getCallingUid()).flush();
        }
        return mTetheredSoftApTracker.getState().getState();
    }

    /**
     * see {@link android.net.wifi.WifiManager#updateInterfaceIpState(String, int)}
     *
     * The possible modes include: {@link WifiManager#IFACE_IP_MODE_TETHERED},
     *                             {@link WifiManager#IFACE_IP_MODE_LOCAL_ONLY},
     *                             {@link WifiManager#IFACE_IP_MODE_CONFIGURATION_ERROR}
     *
     * @param ifaceName String name of the updated interface
     * @param mode new operating mode of the interface
     *
     * @throws SecurityException if the caller does not have permission to call update
     */
    @Override
    public void updateInterfaceIpState(String ifaceName, int mode) {
        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();
        mLog.info("updateInterfaceIpState uid=%").c(Binder.getCallingUid()).flush();
        // hand off the work to our handler thread
        mWifiThreadRunner.post(() -> mLohsSoftApTracker.updateInterfaceIpState(ifaceName, mode),
                TAG + "#updateInterfaceIpState");
    }

    /**
     * see {@link WifiManager#isDefaultCoexAlgorithmEnabled()}
     * @return {@code true} if the default coex algorithm is enabled. {@code false} otherwise.
     */
    @Override
    public boolean isDefaultCoexAlgorithmEnabled() {
        return mResourceCache.getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled);
    }

    /**
     * see {@link android.net.wifi.WifiManager#setCoexUnsafeChannels(List, int)}
     * @param unsafeChannels List of {@link CoexUnsafeChannel} to avoid.
     * @param restrictions Bitmap of {@link CoexRestriction} specifying the mandatory
     *                     uses of the specified channels.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.S)
    public void setCoexUnsafeChannels(
            @NonNull List<CoexUnsafeChannel> unsafeChannels, int restrictions) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.WIFI_UPDATE_COEX_UNSAFE_CHANNELS, "WifiService");
        if (unsafeChannels == null) {
            throw new IllegalArgumentException("unsafeChannels cannot be null");
        }
        if (mResourceCache.getBoolean(R.bool.config_wifiDefaultCoexAlgorithmEnabled)) {
            Log.e(TAG, "setCoexUnsafeChannels called but default coex algorithm is enabled");
            return;
        }
        mWifiThreadRunner.post(() ->
                mCoexManager.setCoexUnsafeChannels(unsafeChannels, restrictions),
                TAG + "#setCoexUnsafeChannels");
    }

    /**
     * See {@link WifiManager#registerCoexCallback(WifiManager.CoexCallback)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.S)
    public void registerCoexCallback(@NonNull ICoexCallback callback) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.WIFI_ACCESS_COEX_UNSAFE_CHANNELS, "WifiService");
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("registerCoexCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> mCoexManager.registerRemoteCoexCallback(callback),
                TAG + "#registerCoexCallback");
    }

    /**
     * Check if input configuration is valid.
     *
     * Call this before calling {@link startTetheredHotspot(SoftApConfiguration)} or
     * {@link #setSoftApConfiguration(softApConfiguration)} to avoid unexpected error duo to
     * configuration is invalid.
     *
     * @param config a configuration would like to be checked.
     * @return true if config is valid, otherwise false.
     */
    @Override
    public boolean validateSoftApConfiguration(SoftApConfiguration config) {
        int uid = Binder.getCallingUid();
        boolean privileged = isSettingsOrSuw(Binder.getCallingPid(), uid);
        return WifiApConfigStore.validateApWifiConfiguration(
                config, privileged, mContext, mWifiNative);
    }

    /**
     * See {@link WifiManager#unregisterCoexCallback(WifiManager.CoexCallback)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.S)
    public void unregisterCoexCallback(@NonNull ICoexCallback callback) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.WIFI_ACCESS_COEX_UNSAFE_CHANNELS, "WifiService");
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterCoexCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> mCoexManager.unregisterRemoteCoexCallback(callback),
                TAG + "#unregisterCoexCallback");
    }

    /**
     * see {@link android.net.wifi.WifiManager#startSoftAp(WifiConfiguration)}
     * @param wifiConfig SSID, security and channel details as part of WifiConfiguration
     * @return {@code true} if softap start was triggered
     * @throws SecurityException if the caller does not have permission to start softap
     */
    @Override
    public boolean startSoftAp(WifiConfiguration wifiConfig, String packageName) {
        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);

        mLog.info("startSoftAp uid=%").c(callingUid).flush();

        SoftApConfiguration softApConfig = null;
        if (wifiConfig != null) {
            softApConfig = ApConfigUtil.fromWifiConfiguration(wifiConfig);
            if (softApConfig == null) {
                return false;
            }
        }

        if (!mTetheredSoftApTracker.setEnablingIfAllowed()) {
            mLog.err("Tethering is already active or activating.").flush();
            return false;
        }

        WorkSource requestorWs = new WorkSource(callingUid, packageName);
        long id = Binder.clearCallingIdentity();
        try {
            if (!mActiveModeWarden.canRequestMoreSoftApManagers(requestorWs)) {
                // Take down LOHS if it is up.
                mLohsSoftApTracker.stopAll();
            }
        } finally {
            Binder.restoreCallingIdentity(id);
        }

        if (!startSoftApInternal(new SoftApModeConfiguration(
                WifiManager.IFACE_IP_MODE_TETHERED, softApConfig,
                mTetheredSoftApTracker.getSoftApCapability(),
                mCountryCode.getCountryCode(), null), requestorWs, null)) {
            mTetheredSoftApTracker.setFailedWhileEnabling();
            return false;
        }
        mLastCallerInfoManager.put(WifiManager.API_SOFT_AP, Process.myTid(),
                callingUid, Binder.getCallingPid(), packageName, true);
        return true;
    }

    /**
     * see {@link android.net.wifi.WifiManager#startTetheredHotspot(SoftApConfiguration)}
     * @param softApConfig SSID, security and channel details as part of SoftApConfiguration
     * @return {@code true} if softap start was triggered
     * @throws SecurityException if the caller does not have permission to start softap
     */
    @Override
    public boolean startTetheredHotspot(@Nullable SoftApConfiguration softApConfig,
            @NonNull String packageName) {
        enforceValidCallingUser();

        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);

        // If user restriction is set, cannot start softap
        if (mWifiTetheringDisallowed) {
            mLog.err("startTetheredHotspot with user restriction: not permitted").flush();
            return false;
        }

        mLog.info("startTetheredHotspot uid=%").c(callingUid).flush();
        return startTetheredHotspotInternal(new SoftApModeConfiguration(
                WifiManager.IFACE_IP_MODE_TETHERED, softApConfig,
                mTetheredSoftApTracker.getSoftApCapability(),
                mCountryCode.getCountryCode(), null /* request */), callingUid, packageName, null);
    }

    /**
     * see {@link WifiManager#startTetheredHotspot(TetheringManager.TetheringRequest, Executor, WifiManager.SoftApCallback)}
     * @param request TetheringRequest details of the Soft AP.
     * @return {@code true} if softap start was triggered
     * @throws SecurityException if the caller does not have permission to start softap
     */
    @Override
    public void startTetheredHotspotRequest(@NonNull TetheringManager.TetheringRequest request,
            @NonNull ISoftApCallback callback,
            @NonNull String packageName) {
        if (request == null) {
            throw new IllegalArgumentException("TetheringRequest must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        enforceValidCallingUser();
        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);

        // If user restriction is set, cannot start softap
        if (mWifiTetheringDisallowed) {
            mLog.err("startTetheredHotspotRequest with user restriction: not permitted").flush();
            try {
                callback.onStateChanged(new SoftApState(WIFI_AP_STATE_FAILED,
                        SAP_START_FAILURE_GENERAL, request, null));
            } catch (RemoteException e) {
                Log.e(TAG, "ISoftApCallback.onStateChanged: remote exception -- " + e);
            }
            return;
        }

        mLog.info("startTetheredHotspot uid=%").c(callingUid).flush();
        startTetheredHotspotInternal(new SoftApModeConfiguration(
                WifiManager.IFACE_IP_MODE_TETHERED,
                com.android.net.flags.Flags.tetheringWithSoftApConfig()
                        ? request.getSoftApConfiguration() : null,
                mTetheredSoftApTracker.getSoftApCapability(),
                mCountryCode.getCountryCode(), request), callingUid, packageName, callback);
    }

    /**
     * Internal method to start tethered hotspot. Callers of this method should have already checked
     * proper permissions beyond the NetworkStack permission.
     */
    private boolean startTetheredHotspotInternal(@NonNull SoftApModeConfiguration modeConfig,
            int callingUid, String packageName, @Nullable ISoftApCallback callback) {
        if (!mTetheredSoftApTracker.setEnablingIfAllowed()) {
            mLog.err("Tethering is already active or activating.").flush();
            if (callback != null) {
                try {
                    callback.onStateChanged(new SoftApState(WIFI_AP_STATE_FAILED,
                            SAP_START_FAILURE_GENERAL, modeConfig.getTetheringRequest(), null));
                } catch (RemoteException e) {
                    Log.e(TAG, "ISoftApCallback.onStateChanged: remote exception -- " + e);
                }
            }
            return false;
        }

        WorkSource requestorWs = new WorkSource(callingUid, packageName);
        long id = Binder.clearCallingIdentity();
        try {
            if (!mActiveModeWarden.canRequestMoreSoftApManagers(requestorWs)) {
                // Take down LOHS if it is up.
                mLohsSoftApTracker.stopAll();
            }
        } finally {
            Binder.restoreCallingIdentity(id);
        }

        if (!startSoftApInternal(modeConfig, requestorWs, callback)) {
            mTetheredSoftApTracker.setFailedWhileEnabling();
            return false;
        }
        mLastCallerInfoManager.put(WifiManager.API_TETHERED_HOTSPOT, Process.myTid(),
                callingUid, Binder.getCallingPid(), packageName, true);
        return true;
    }

    /**
     * Internal method to start softap mode. Callers of this method should have already checked
     * proper permissions beyond the NetworkStack permission.
     */
    private boolean startSoftApInternal(SoftApModeConfiguration apConfig, WorkSource requestorWs,
            @Nullable ISoftApCallback callback) {
        int uid = Binder.getCallingUid();
        boolean privileged = isSettingsOrSuw(Binder.getCallingPid(), uid);
        mLog.trace("startSoftApInternal uid=% mode=%")
                .c(uid).c(apConfig.getTargetMode()).flush();

        // null wifiConfig is a meaningful input for CMD_SET_AP; it means to use the persistent
        // AP config.
        SoftApConfiguration softApConfig = apConfig.getSoftApConfiguration();
        if (softApConfig != null
                && (!WifiApConfigStore.validateApWifiConfiguration(
                    softApConfig, privileged, mContext, mWifiNative))) {
            Log.e(TAG, "Invalid SoftApConfiguration");
            if (callback != null) {
                try {
                    callback.onStateChanged(new SoftApState(WIFI_AP_STATE_FAILED,
                            SAP_START_FAILURE_GENERAL,
                            apConfig.getTetheringRequest(), null));
                } catch (RemoteException e) {
                    Log.e(TAG, "ISoftApCallback.onStateChanged: remote exception -- " + e);
                }
            }
            return false;
        }
        if (apConfig.getTargetMode() == IFACE_IP_MODE_TETHERED) {
            mTetheredSoftApTracker.setRequestCallback(callback);
        }
        mActiveModeWarden.startSoftAp(apConfig, requestorWs);
        return true;
    }

    /**
     * see {@link android.net.wifi.WifiManager#stopSoftAp()}
     * @return {@code true} if softap stop was triggered
     * @throws SecurityException if the caller does not have permission to stop softap
     */
    @Override
    public boolean stopSoftAp() {
        enforceValidCallingUser();

        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();

        // only permitted callers are allowed to this point - they must have gone through
        // connectivity service since this method is protected with the NETWORK_STACK PERMISSION

        mLog.info("stopSoftAp uid=%").c(Binder.getCallingUid()).flush();

        stopSoftApInternal(WifiManager.IFACE_IP_MODE_TETHERED);
        mLastCallerInfoManager.put(WifiManager.API_SOFT_AP, Process.myTid(),
                Binder.getCallingUid(), Binder.getCallingPid(), "<unknown>", false);
        return true;
    }

    /**
     * Internal method to stop softap mode.
     *
     * Callers of this method should have already checked
     * proper permissions beyond the NetworkStack permission.
     *
     * @param mode the operating mode of APs to bring down (ex,
     *             {@link WifiManager.IFACE_IP_MODE_TETHERED} or
     *             {@link WifiManager.IFACE_IP_MODE_LOCAL_ONLY}).
     *             Use {@link WifiManager.IFACE_IP_MODE_UNSPECIFIED} to stop all APs.
     */
    private void stopSoftApInternal(int mode) {
        mLog.trace("stopSoftApInternal uid=% mode=%").c(Binder.getCallingUid()).c(mode).flush();

        mActiveModeWarden.stopSoftAp(mode);
    }

    /**
     * Internal class for tracking country code changed event.
     */
    @VisibleForTesting
    public final class CountryCodeTracker implements WifiCountryCode.ChangeListener {
        private final RemoteCallbackList<IOnWifiDriverCountryCodeChangedListener>
                mRegisteredDriverCountryCodeListeners = new RemoteCallbackList<>();

        /**
        * Register Driver Country code changed listener.
        * Note: Calling API only in handler thread.
        *
        * @param listener listener for the driver country code changed events.
        */
        public void registerDriverCountryCodeChangedListener(
                @NonNull IOnWifiDriverCountryCodeChangedListener listener,
                @NonNull WifiPermissionsUtil.CallerIdentity identity) {
            boolean result = mRegisteredDriverCountryCodeListeners.register(listener, identity);
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "registerDriverCountryCodeChangedListener, listener:" + listener
                        + ", CallerIdentity=" + identity.toString() + ", result: " + result);
            }
        }


        /**
         * Unregister Driver Country code changed listener.
         * Note: Calling API only in handler thread.
         *
         * @param listener listener to remove.
         */
        public void unregisterDriverCountryCodeChangedListener(
                @NonNull IOnWifiDriverCountryCodeChangedListener listener) {
            boolean result = mRegisteredDriverCountryCodeListeners.unregister(listener);
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "unregisterDriverCountryCodeChangedListener, listener:" + listener
                        + ", result:" + result);
            }
        }

        @Override
        public void onCountryCodeChangePending(@NonNull String countryCode) {
            // post operation to handler thread
            mWifiThreadRunner.post(() -> {
                if (mTetheredSoftApTracker != null) {
                    mTetheredSoftApTracker.notifyNewCountryCodeChangePending(countryCode);
                }
                if (mLohsSoftApTracker != null) {
                    mLohsSoftApTracker.notifyNewCountryCodeChangePending(countryCode);
                }
            }, this.getClass().getSimpleName() + "#onCountryCodeChangePending");
        }

        @Override
        public void onDriverCountryCodeChanged(@Nullable String countryCode) {
            // post operation to handler thread
            mWifiThreadRunner.post(() -> {
                Log.i(TAG, "Receive onDriverCountryCodeChanged to " + countryCode
                        + ", update available channel list");
                // Update channel capability when country code is not null.
                // Because the driver country code will reset to null when driver is non-active.
                if (countryCode != null) {
                    if (!TextUtils.equals(countryCode,
                            mCountryCode.getCurrentDriverCountryCode())) {
                        Log.e(TAG, "Country code not consistent! expect " + countryCode + " actual "
                                + mCountryCode.getCurrentDriverCountryCode());
                    }
                    List<Integer> freqs = new ArrayList<>();
                    SparseArray<int[]> channelMap = new SparseArray<>(
                            SoftApConfiguration.BAND_TYPES.length);
                    for (int band : SoftApConfiguration.BAND_TYPES) {
                        if (!ApConfigUtil.isSoftApBandSupported(mContext, band)) {
                            continue;
                        }
                        List<Integer> freqsForBand = ApConfigUtil.getAvailableChannelFreqsForBand(
                                band, mWifiNative, null, true);
                        if (freqsForBand != null) {
                            freqs.addAll(freqsForBand);
                            int[] channel = new int[freqsForBand.size()];
                            for (int i = 0; i < freqsForBand.size(); i++) {
                                channel[i] = ScanResult.convertFrequencyMhzToChannelIfSupported(
                                        freqsForBand.get(i));
                            }
                            channelMap.put(band, channel);
                        }
                    }
                    if (!mCountryCode.isDriverCountryCodeWorldMode()
                            || TextUtils.isEmpty(mSettingsConfigStore.get(
                                    WifiSettingsConfigStore.WIFI_SOFT_AP_COUNTRY_CODE))) {
                        // Store Soft AP channels (non-world mode CC or no save before) for
                        // reference after a reboot before the driver is up.
                        mSettingsConfigStore.put(WifiSettingsConfigStore.WIFI_SOFT_AP_COUNTRY_CODE,
                                countryCode);
                        mSettingsConfigStore.put(
                                WifiSettingsConfigStore.WIFI_AVAILABLE_SOFT_AP_FREQS_MHZ,
                                new JSONArray(freqs).toString());
                    }
                    mTetheredSoftApTracker.updateAvailChannelListInSoftApCapability(countryCode,
                            channelMap);
                    mLohsSoftApTracker.updateAvailChannelListInSoftApCapability(countryCode,
                            channelMap);
                    mActiveModeWarden.updateSoftApCapability(
                            mTetheredSoftApTracker.getSoftApCapability(),
                            WifiManager.IFACE_IP_MODE_TETHERED);
                    // TODO: b/197529327 trigger Lohs capability callback & update available
                    // channels
                    mActiveModeWarden.updateSoftApCapability(
                            mLohsSoftApTracker.getSoftApCapability(),
                            WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
                }
                if (SdkLevel.isAtLeastT()) {
                    int itemCount = mRegisteredDriverCountryCodeListeners.beginBroadcast();
                    for (int i = 0; i < itemCount; i++) {
                        try {
                            WifiPermissionsUtil.CallerIdentity identity =
                                    (WifiPermissionsUtil.CallerIdentity)
                                    mRegisteredDriverCountryCodeListeners.getBroadcastCookie(i);
                            if (!mWifiPermissionsUtil.checkCallersCoarseLocationPermission(
                                    identity.getPackageName(), identity.getFeatureId(),
                                    identity.getUid(), null)) {
                                Log.i(TAG, "ReceiverIdentity=" + identity.toString()
                                        + " doesn't have ACCESS_COARSE_LOCATION permission now");
                                continue;
                            }
                            if (mVerboseLoggingEnabled) {
                                Log.i(TAG, "onDriverCountryCodeChanged, ReceiverIdentity="
                                        + identity.toString());
                            }
                            mRegisteredDriverCountryCodeListeners.getBroadcastItem(i)
                                    .onDriverCountryCodeChanged(countryCode);
                        } catch (RemoteException e) {
                            Log.e(TAG, "onDriverCountryCodeChanged: remote exception -- " + e);
                        }
                    }
                    mRegisteredDriverCountryCodeListeners.finishBroadcast();
                }
                mAfcManager.onCountryCodeChange(countryCode);
            }, this.getClass().getSimpleName() + "#onCountryCodeChangePending");
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class UwbAdapterStateListener implements UwbManager.AdapterStateCallback {
        @Override
        public void onStateChanged(int state, int reason) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "UwbManager.AdapterState=" + state);
            }
            mWifiMetrics.setLastUwbState(state);
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    class ThreadStateListener implements ThreadNetworkController.StateCallback {
        @Override
        public void onDeviceRoleChanged(int mDeviceRole) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "ThreadNetworkController.DeviceRole=" + mDeviceRole);
            }
            mWifiMetrics.setLastThreadDeviceRole(mDeviceRole);
        }

        @Override
        public void onPartitionIdChanged(long mPartitionId) {}
    }

    /**
     * SoftAp callback
     */
    private class BaseSoftApTracker extends SoftApCallbackInternal {
        /**
         * State of tethered SoftAP
         * One of:  {@link WifiManager#WIFI_AP_STATE_DISABLED},
         *          {@link WifiManager#WIFI_AP_STATE_DISABLING},
         *          {@link WifiManager#WIFI_AP_STATE_ENABLED},
         *          {@link WifiManager#WIFI_AP_STATE_ENABLING},
         *          {@link WifiManager#WIFI_AP_STATE_FAILED}
         */
        private final Object mLock = new Object();
        @NonNull
        private SoftApState mSoftApState =
                new SoftApState(WIFI_AP_STATE_DISABLED, 0, null, null);
        private Map<String, List<WifiClient>> mSoftApConnectedClientsMap = new HashMap();
        private Map<String, SoftApInfo> mSoftApInfoMap = new HashMap();
        private boolean mIsBridgedMode = false;
        // TODO: We need to maintain two capability. One for LTE + SAP and one for WIFI + SAP
        protected SoftApCapability mSoftApCapability = null;
        protected final RemoteCallbackList<ISoftApCallback> mRegisteredSoftApCallbacks =
                new RemoteCallbackList<>();
        // Callback tied to the current SoftAp request.
        protected ISoftApCallback mRequestCallback = null;

        public SoftApState getState() {
            synchronized (mLock) {
                return mSoftApState;
            }
        }

        public void setState(SoftApState softApState) {
            synchronized (mLock) {
                mSoftApState = softApState;
            }
        }

        public boolean setEnablingIfAllowed() {
            synchronized (mLock) {
                int state = mSoftApState.getState();
                if (state != WIFI_AP_STATE_DISABLED
                        && state != WIFI_AP_STATE_FAILED) {
                    return false;
                }
                mSoftApState = new SoftApState(
                        WIFI_AP_STATE_ENABLING, 0, null, null);
                return true;
            }
        }

        public void setFailedWhileEnabling() {
            synchronized (mLock) {
                int state = mSoftApState.getState();
                if (state == WIFI_AP_STATE_ENABLING) {
                    mSoftApState = new SoftApState(
                            WIFI_AP_STATE_FAILED, 0, null, null);
                }
            }
        }

        public Map<String, List<WifiClient>> getConnectedClients() {
            synchronized (mLock) {
                return mSoftApConnectedClientsMap;
            }
        }

        public Map<String, SoftApInfo> getSoftApInfos() {
            synchronized (mLock) {
                return mSoftApInfoMap;
            }
        }

        public boolean getIsBridgedMode() {
            synchronized (mLock) {
                return mIsBridgedMode;
            }
        }

        public void notifyNewCountryCodeChangePending(@NonNull String countryCode) {
            // If country code not changed, no need to update.
            if (mSoftApCapability != null && !TextUtils.equals(mSoftApCapability.getCountryCode(),
                    countryCode)) {
                // Country code changed when we can't update channels from HAL, invalidate the soft
                // ap capability for supported channels.
                SoftApCapability newSoftApCapability = new SoftApCapability(
                        mSoftApCapability);
                for (int b : SoftApConfiguration.BAND_TYPES) {
                    newSoftApCapability.setSupportedChannelList(b, new int[0]);
                }
                // Notify the capability change
                onCapabilityChanged(newSoftApCapability);
            }
        }

        public SoftApCapability getSoftApCapability() {
            synchronized (mLock) {
                if (mSoftApCapability == null) {
                    mSoftApCapability = ApConfigUtil.updateCapabilityFromResource(mContext);
                    mSoftApCapability = ApConfigUtil.updateCapabilityFromConfigStore(
                            mSoftApCapability, mWifiInjector.getSettingsConfigStore());
                    // Default country code
                    mSoftApCapability = updateSoftApCapabilityWithAvailableChannelList(
                            mSoftApCapability, mCountryCode.getCountryCode(), null);
                    if (mWifiNative.isMLDApSupportMLO()) {
                        mSoftApCapability.setSupportedFeatures(
                                true, SoftApCapability.SOFTAP_FEATURE_MLO);
                    }
                }
                return mSoftApCapability;
            }
        }

        private SoftApCapability updateSoftApCapabilityWithAvailableChannelList(
                @NonNull SoftApCapability softApCapability, @Nullable String countryCode,
                @Nullable SparseArray<int[]> channelMap) {
            if (!mIsBootComplete) {
                // The available channel list is from wificond or HAL.
                // It might be a failure or stuck during wificond or HAL init.
                return softApCapability;
            }
            if (mCountryCode.getCurrentDriverCountryCode() != null) {
                mSoftApCapability.setCountryCode(countryCode);
            }
            return ApConfigUtil.updateSoftApCapabilityWithAvailableChannelList(
                    softApCapability, mContext, mWifiNative, channelMap);
        }

        public void updateAvailChannelListInSoftApCapability(@Nullable String countryCode,
                @Nullable SparseArray<int[]> channelMap) {
            onCapabilityChanged(updateSoftApCapabilityWithAvailableChannelList(
                    getSoftApCapability(), countryCode, channelMap));
        }

        public boolean registerSoftApCallback(ISoftApCallback callback, Object cookie) {
            if (!mRegisteredSoftApCallbacks.register(callback, cookie)) {
                return false;
            }

            // Update the client about the current state immediately after registering the callback
            try {
                callback.onStateChanged(getState());
                callback.onConnectedClientsOrInfoChanged(getSoftApInfos(),
                        getConnectedClients(), getIsBridgedMode(), true);
                callback.onCapabilityChanged(getSoftApCapability());
            } catch (RemoteException e) {
                Log.e(TAG, "registerSoftApCallback: remote exception -- " + e);
            }
            return true;
        }

        public void unregisterSoftApCallback(ISoftApCallback callback) {
            mRegisteredSoftApCallbacks.unregister(callback);
        }

        /**
         * Set the callback to track the state of the current SoftAP request.
         */
        public void setRequestCallback(@Nullable ISoftApCallback callback) {
            if (mRequestCallback != null) {
                mRegisteredSoftApCallbacks.unregister(mRequestCallback);
            }
            mRequestCallback = callback;
            if (callback != null) {
                mRegisteredSoftApCallbacks.register(callback);
            }
        }

        /**
         * Called when soft AP state changes.
         */
        @Override
        public void onStateChanged(SoftApState softApState) {
            setState(softApState);
            notifyRegisterOnStateChanged(mRegisteredSoftApCallbacks, softApState);
        }

        /**
         * Called when the connected clients to soft AP changes.
         *
         * @param clients connected clients to soft AP
         */
        @Override
        public void onConnectedClientsOrInfoChanged(Map<String, SoftApInfo> infos,
                Map<String, List<WifiClient>> clients, boolean isBridged) {
            synchronized (mLock) {
                mIsBridgedMode = isBridged;
                if (infos.size() == 0 && isBridged) {
                    Log.d(TAG, "ShutDown bridged mode, clear isBridged cache in Service");
                    mIsBridgedMode = false;
                }
                mSoftApConnectedClientsMap =
                        ApConfigUtil.deepCopyForWifiClientListMap(clients);
                mSoftApInfoMap = ApConfigUtil.deepCopyForSoftApInfoMap(infos);
            }
            notifyRegisterOnConnectedClientsOrInfoChanged(mRegisteredSoftApCallbacks,
                    infos, clients, isBridged);
        }

        /**
         * Called when capability of softap changes.
         *
         * @param capability is the softap capability. {@link SoftApCapability}
         */
        @Override
        public void onCapabilityChanged(SoftApCapability capability) {
            synchronized (mLock) {
                if (Objects.equals(capability, mSoftApCapability)) {
                    return;
                }
                mSoftApCapability = new SoftApCapability(capability);
            }
            notifyRegisterOnCapabilityChanged(mRegisteredSoftApCallbacks,
                    mSoftApCapability);
        }

        /**
         * Called when client trying to connect but device blocked the client with specific reason.
         *
         * @param client the currently blocked client.
         * @param blockedReason one of blocked reason from
         * {@link WifiManager.SapClientBlockedReason}
         */
        @Override
        public void onBlockedClientConnecting(WifiClient client, int blockedReason) {
            notifyRegisterOnBlockedClientConnecting(mRegisteredSoftApCallbacks, client,
                    blockedReason);
        }

        /**
         * Called when clients disconnect from a soft AP instance.
         *
         * @param info The {@link SoftApInfo} of the AP.
         * @param clients The clients that have disconnected from the AP instance specified by
         *                {@code info}.
         */
        @Override
        public void onClientsDisconnected(@NonNull SoftApInfo info,
                @NonNull List<WifiClient> clients) {
            notifyRegisterOnClientsDisconnected(mRegisteredSoftApCallbacks, info, clients);
        }
    }

    private final class TetheredSoftApTracker extends BaseSoftApTracker {
        public void updateSoftApCapabilityWhenCarrierConfigChanged(int subId) {
            CarrierConfigManager carrierConfigManager =
                    mContext.getSystemService(CarrierConfigManager.class);
            if (carrierConfigManager == null) return;
            PersistableBundle carrierConfig = carrierConfigManager.getConfigForSubId(subId);
            if (carrierConfig == null) return;
            int carrierMaxClient = carrierConfig.getInt(
                    CarrierConfigManager.Wifi.KEY_HOTSPOT_MAX_CLIENT_COUNT);
            int finalSupportedClientNumber = mResourceCache.getInteger(
                    R.integer.config_wifiHardwareSoftapMaxClientCount);
            if (carrierMaxClient > 0) {
                finalSupportedClientNumber = Math.min(finalSupportedClientNumber,
                        carrierMaxClient);
            }
            if (finalSupportedClientNumber == getSoftApCapability().getMaxSupportedClients()) {
                return;
            }
            SoftApCapability newSoftApCapability = new SoftApCapability(mSoftApCapability);
            newSoftApCapability.setMaxSupportedClients(
                    finalSupportedClientNumber);
            onCapabilityChanged(newSoftApCapability);
        }

    }

    /**
     * Implements LOHS behavior on top of the existing SoftAp API.
     */
    private final class LohsSoftApTracker extends BaseSoftApTracker {
        private static final int UNSPECIFIED_PID = -1;

        @GuardedBy("mLocalOnlyHotspotRequests")
        private final HashMap<Integer, LocalOnlyHotspotRequestInfo>
                mLocalOnlyHotspotRequests = new HashMap<>();

        /** Currently-active config, to be sent to shared clients registering later. */
        @GuardedBy("mLocalOnlyHotspotRequests")
        private SoftApModeConfiguration mActiveConfig = null;

        /**
         * Whether we are currently operating in exclusive mode (i.e. whether a custom config is
         * active).
         */
        @GuardedBy("mLocalOnlyHotspotRequests")
        private boolean mIsExclusive = false;

        @GuardedBy("mLocalOnlyHotspotRequests")
        private WorkSource mCurrentWs = null;

        @GuardedBy("mLocalOnlyHotspotRequests")
        private String mLohsInterfaceName;

        @GuardedBy("mLocalOnlyHotspotRequests")
        private int mLohsInterfaceMode = WifiManager.IFACE_IP_MODE_UNSPECIFIED;

        @GuardedBy("mLocalOnlyHotspotRequests")
        private int mPidRestartingLohsFor = UNSPECIFIED_PID;
        @Override
        public boolean checkCallbackPermission(@Nullable Object broadcastCookie) {
            if (!SdkLevel.isAtLeastS()) {
                // AttributionSource requires at least S.
                return false;
            }
            if (!(broadcastCookie instanceof AttributionSource)) {
                return false;
            }
            return mWifiPermissionsUtil.checkNearbyDevicesPermission(
                    (AttributionSource) broadcastCookie,
                    false /* checkForLocation */,
                    TAG + " " + this.getClass().getSimpleName() + "#checkCallbackPermission");
        }
        public void updateInterfaceIpState(String ifaceName, int mode) {
            // update interface IP state related to local-only hotspot
            synchronized (mLocalOnlyHotspotRequests) {
                Log.d(TAG, "updateInterfaceIpState: ifaceName=" + ifaceName + " mode=" + mode
                        + " previous LOHS mode= " + mLohsInterfaceMode);

                switch (mode) {
                    case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                        // first make sure we have registered requests.
                        if (mLocalOnlyHotspotRequests.isEmpty()) {
                            // we don't have requests...  stop the hotspot
                            Log.wtf(TAG, "Starting LOHS without any requests?");
                            stopSoftApInternal(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
                            return;
                        }
                        // LOHS is ready to go!  Call our registered requestors!
                        mLohsInterfaceName = ifaceName;
                        mLohsInterfaceMode = mode;
                        sendHotspotStartedMessageToAllLOHSRequestInfoEntriesLocked();
                        break;
                    case WifiManager.IFACE_IP_MODE_TETHERED:
                        if (TextUtils.equals(mLohsInterfaceName, ifaceName)) {
                            /* This shouldn't happen except in a race, but if it does, tear down
                             * the LOHS and let tethering win.
                             *
                             * If concurrent SAPs are allowed, the interface names will differ,
                             * so we don't have to check the config here.
                             */
                            Log.e(TAG, "Unexpected IP mode change on " + ifaceName);
                            mLohsInterfaceName = null;
                            mLohsInterfaceMode = WifiManager.IFACE_IP_MODE_UNSPECIFIED;
                            sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                                    LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE);
                        }
                        break;
                    case WifiManager.IFACE_IP_MODE_CONFIGURATION_ERROR:
                        if (ifaceName == null) {
                            // All softAps
                            mLohsInterfaceName = null;
                            mLohsInterfaceMode = mode;
                            sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                                    LocalOnlyHotspotCallback.ERROR_GENERIC);
                            stopSoftApInternal(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                        } else if (TextUtils.equals(mLohsInterfaceName, ifaceName)) {
                            mLohsInterfaceName = null;
                            mLohsInterfaceMode = mode;
                            sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                                    LocalOnlyHotspotCallback.ERROR_GENERIC);
                            stopSoftApInternal(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
                        } else {
                            // Not for LOHS. This is the wrong place to do this, but...
                            stopSoftApInternal(WifiManager.IFACE_IP_MODE_TETHERED);
                        }
                        break;
                    case WifiManager.IFACE_IP_MODE_UNSPECIFIED:
                        if (ifaceName == null || ifaceName.equals(mLohsInterfaceName)) {
                            mLohsInterfaceName = null;
                            mLohsInterfaceMode = mode;
                        }
                        break;
                    default:
                        mLog.warn("updateInterfaceIpState: unknown mode %").c(mode).flush();
                }
            }
        }

        /**
         * Helper method to send a HOTSPOT_FAILED message to all registered LocalOnlyHotspotRequest
         * callers and clear the registrations.
         *
         * Callers should already hold the mLocalOnlyHotspotRequests lock.
         */
        @GuardedBy("mLocalOnlyHotspotRequests")
        private void sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(int reason) {
            for (LocalOnlyHotspotRequestInfo requestor : mLocalOnlyHotspotRequests.values()) {
                try {
                    requestor.sendHotspotFailedMessage(reason);
                    requestor.unlinkDeathRecipient();
                } catch (RemoteException e) {
                    // This will be cleaned up by binder death handling
                }
            }

            // Since all callers were notified, now clear the registrations.
            mLocalOnlyHotspotRequests.clear();
            mPidRestartingLohsFor = UNSPECIFIED_PID;
        }

        /**
         * Helper method to send a HOTSPOT_STOPPED message to all registered LocalOnlyHotspotRequest
         * callers and clear the registrations.
         *
         * Callers should already hold the mLocalOnlyHotspotRequests lock.
         */
        @GuardedBy("mLocalOnlyHotspotRequests")
        private void sendHotspotStoppedMessageToAllLOHSRequestInfoEntriesLocked() {
            for (LocalOnlyHotspotRequestInfo requestor : mLocalOnlyHotspotRequests.values()) {
                try {
                    requestor.sendHotspotStoppedMessage();
                    requestor.unlinkDeathRecipient();
                } catch (RemoteException e) {
                    // This will be cleaned up by binder death handling
                }
            }

            // Since all callers were notified, now clear the registrations.
            mLocalOnlyHotspotRequests.clear();
        }

        /**
         * Add a new LOHS client
         */
        private int start(int pid, LocalOnlyHotspotRequestInfo request) {
            synchronized (mLocalOnlyHotspotRequests) {
                // does this caller already have a request?
                if (mLocalOnlyHotspotRequests.get(pid) != null) {
                    mLog.trace("caller already has an active request").flush();
                    throw new IllegalStateException(
                            "Caller already has an active LocalOnlyHotspot request");
                }

                // Never accept exclusive requests (with custom configuration) at the same time as
                // shared requests.
                if (!mLocalOnlyHotspotRequests.isEmpty()) {
                    final boolean requestIsExclusive = request.getCustomConfig() != null;
                    final int newRequestorWsPriority = mWifiInjector
                            .makeWsHelper(request.getWorkSource()).getRequestorWsPriority();
                    final int currentWsPriority = mWifiInjector
                            .makeWsHelper(mCurrentWs).getRequestorWsPriority();
                    final boolean isCurrentLohsWorksOnNonDefaultBand = mActiveConfig != null
                            && mActiveConfig.getSoftApConfiguration().getBand()
                                    != SoftApConfiguration.BAND_2GHZ;
                    Log.d(TAG, "Receive multiple LOHS requestors,"
                                + ", requestIsExclusive: " + requestIsExclusive
                                + ", mIsExclusive: " + mIsExclusive
                                + ", currentWsPriority: " + currentWsPriority
                                + ", newRequestorWsPriority: " + newRequestorWsPriority);
                    if (requestIsExclusive || mIsExclusive
                            || (currentWsPriority >= newRequestorWsPriority
                                    && isCurrentLohsWorksOnNonDefaultBand)) {
                        mLog.trace("Cannot share with existing LOHS request due to custom config")
                                .flush();
                        return LocalOnlyHotspotCallback.ERROR_GENERIC;
                    }
                    if (mFeatureFlags.publicBandsForLohs() && isCurrentLohsWorksOnNonDefaultBand) {
                        // Current LOHS is not using 2.4 band, and new caller priority is higher.
                        // stop all and logging the new requestor pid.
                        mLog.trace("Restarting LOHS to default band for new requestor")
                                .flush();
                        mLohsSoftApTracker.stopAll();
                        mPidRestartingLohsFor = pid;
                    }
                }
                // At this point, the request is accepted.
                if (mLocalOnlyHotspotRequests.isEmpty()) {
                    mWifiThreadRunner.post(() -> {
                        startForFirstRequestLocked(request);
                    }, "LohsSoftApTracker#start");

                } else if (mLohsInterfaceMode == WifiManager.IFACE_IP_MODE_LOCAL_ONLY) {
                    // LOHS has already started up for an earlier request, so we can send the
                    // current config to the incoming request right away.
                    try {
                        mLog.trace("LOHS already up, trigger onStarted callback").flush();
                        request.sendHotspotStartedMessage(mActiveConfig.getSoftApConfiguration());
                    } catch (RemoteException e) {
                        return LocalOnlyHotspotCallback.ERROR_GENERIC;
                    }
                }
                mLocalOnlyHotspotRequests.put(pid, request);
                return LocalOnlyHotspotCallback.REQUEST_REGISTERED;
            }
        }

        @GuardedBy("mLocalOnlyHotspotRequests")
        private void startForFirstRequestLocked(LocalOnlyHotspotRequestInfo request) {
            mCurrentWs = request.getWorkSource();
            final int currentWsPriority = mWifiInjector.makeWsHelper(mCurrentWs)
                    .getRequestorWsPriority();
            if (mFeatureFlags.publicBandsForLohs() && Environment.isSdkAtLeastB()) {
                mIsExclusive = (request.getCustomConfig() != null)
                        && currentWsPriority >= WorkSourceHelper.PRIORITY_SYSTEM;
            } else {
                mIsExclusive = (request.getCustomConfig() != null);
            }
            final SoftApCapability lohsCapability = mLohsSoftApTracker.getSoftApCapability();
            SoftApConfiguration softApConfig = mWifiApConfigStore.generateLocalOnlyHotspotConfig(
                    mContext, request.getCustomConfig(), lohsCapability, mIsExclusive);

            mActiveConfig = new SoftApModeConfiguration(
                    WifiManager.IFACE_IP_MODE_LOCAL_ONLY,
                    softApConfig, lohsCapability, mCountryCode.getCountryCode(), null);
            // Report the error if we got failure in startSoftApInternal
            if (!startSoftApInternal(mActiveConfig, request.getWorkSource(), null)) {
                onStateChanged(new SoftApState(
                        WIFI_AP_STATE_FAILED, SAP_START_FAILURE_GENERAL,
                        mActiveConfig.getTetheringRequest(), null /* iface */));
            }
        }

        /**
         * Requests that any local-only hotspot be stopped.
         */
        public void stopAll() {
            synchronized (mLocalOnlyHotspotRequests) {
                if (!mLocalOnlyHotspotRequests.isEmpty()) {
                    // This is used to take down LOHS when tethering starts, and in that
                    // case we send failed instead of stopped.
                    // TODO check if that is right. Calling onFailed instead of onStopped when the
                    // hotspot is already started does not seem to match the documentation
                    sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                            LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE);
                    stopIfEmptyLocked();
                }
            }
        }

        /**
         * Unregisters the LOHS request from the given process and stops LOHS if no other clients.
         */
        public void stopByPid(int pid) {
            synchronized (mLocalOnlyHotspotRequests) {
                LocalOnlyHotspotRequestInfo requestInfo = mLocalOnlyHotspotRequests.remove(pid);
                if (requestInfo == null) return;
                requestInfo.unlinkDeathRecipient();
                stopIfEmptyLocked();
            }
        }

        /**
         * Unregisters LocalOnlyHotspot request and stops the hotspot if needed.
         */
        public void stopByRequest(LocalOnlyHotspotRequestInfo request) {
            synchronized (mLocalOnlyHotspotRequests) {
                if (mLocalOnlyHotspotRequests.remove(request.getPid()) == null) {
                    mLog.trace("LocalOnlyHotspotRequestInfo not found to remove").flush();
                    return;
                }
                stopIfEmptyLocked();
            }
        }

        @GuardedBy("mLocalOnlyHotspotRequests")
        private void stopIfEmptyLocked() {
            if (mLocalOnlyHotspotRequests.isEmpty()) {
                mActiveConfig = null;
                mIsExclusive = false;
                mCurrentWs = null;
                mLohsInterfaceName = null;
                mPidRestartingLohsFor = UNSPECIFIED_PID;
                mLohsInterfaceMode = WifiManager.IFACE_IP_MODE_UNSPECIFIED;
                stopSoftApInternal(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
            }
        }

        /**
         * Helper method to send a HOTSPOT_STARTED message to all registered LocalOnlyHotspotRequest
         * callers.
         *
         * Callers should already hold the mLocalOnlyHotspotRequests lock.
         */
        @GuardedBy("mLocalOnlyHotspotRequests")
        private void sendHotspotStartedMessageToAllLOHSRequestInfoEntriesLocked() {
            if (mActiveConfig == null) {
                Log.e(TAG, "lohs.sendHotspotStartedMessageToAllLOHSRequestInfoEntriesLocked "
                        + "mActiveConfig is null");
                return;
            }
            for (LocalOnlyHotspotRequestInfo requestor : mLocalOnlyHotspotRequests.values()) {
                try {
                    requestor.sendHotspotStartedMessage(mActiveConfig.getSoftApConfiguration());
                } catch (RemoteException e) {
                    // This will be cleaned up by binder death handling
                }
            }
        }

        @Override
        public void onStateChanged(SoftApState softApState) {
            // The AP state update from ClientModeImpl for softap
            synchronized (mLocalOnlyHotspotRequests) {
                Log.d(TAG, "lohs.onStateChanged: " + softApState);
                int state = softApState.getState();
                int failureReason = softApState.getFailureReasonInternal();

                // check if we have a failure - since it is possible (worst case scenario where
                // WifiController and ClientModeImpl are out of sync wrt modes) to get two FAILED
                // notifications in a row, we need to handle this first.
                if (state == WIFI_AP_STATE_FAILED) {
                    // update registered LOHS callbacks if we see a failure
                    int errorToReport = ERROR_GENERIC;
                    if (failureReason == SAP_START_FAILURE_NO_CHANNEL) {
                        errorToReport = ERROR_NO_CHANNEL;
                    }
                    // holding the required lock: send message to requestors and clear the list
                    sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(errorToReport);
                    // also need to clear interface ip state
                    updateInterfaceIpState(mLohsInterfaceName,
                            WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                    // failed, reset restarting pid info
                    mPidRestartingLohsFor = UNSPECIFIED_PID;
                } else if (state == WIFI_AP_STATE_DISABLING || state == WIFI_AP_STATE_DISABLED) {
                    // softap is shutting down or is down...  let requestors know via the
                    // onStopped call
                    // if we are currently in hotspot mode, then trigger onStopped for registered
                    // requestors, otherwise something odd happened and we should clear state
                    if (mLohsInterfaceName != null
                            && mLohsInterfaceMode == WifiManager.IFACE_IP_MODE_LOCAL_ONLY) {
                        // holding the required lock: send message to requestors and clear the list
                        sendHotspotStoppedMessageToAllLOHSRequestInfoEntriesLocked();
                    } else {
                        // The LOHS is being restarted since there is a mPidRestartingLohsFor,
                        // We should expect there will be DISABLING and DISABLED.
                        // No need to report error.
                        if (mPidRestartingLohsFor == UNSPECIFIED_PID) {
                            // LOHS not active: report an error.
                            sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                                    ERROR_GENERIC);
                        }
                    }
                    // also clear interface ip state
                    updateInterfaceIpState(mLohsInterfaceName,
                            WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                } else if (state ==  WIFI_AP_STATE_ENABLING
                        && mPidRestartingLohsFor != UNSPECIFIED_PID) {
                    // restarting, reset pid info
                    mPidRestartingLohsFor = UNSPECIFIED_PID;
                }
                // For enabling and enabled, just record the new state
                setState(softApState);
                notifyRegisterOnStateChanged(mRegisteredSoftApCallbacks, softApState);
            }
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#registerSoftApCallback(Executor,
     * WifiManager.SoftApCallback)}
     *
     * @param callback Soft AP callback to register
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void registerSoftApCallback(ISoftApCallback callback) {
        // verify arguments
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)
                && !checkNetworkSettingsPermission(pid, uid)
                && !checkMainlineNetworkStackPermission(pid, uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read  WiFi Ap information "
                    + "(uid/pid = " + uid + "/" + pid + ")");
        }

        if (mVerboseLoggingEnabled) {
            mLog.info("registerSoftApCallback uid=%").c(Binder.getCallingUid()).flush();
        }

        // post operation to handler thread
        mWifiThreadRunner.post(() -> {
            if (!mTetheredSoftApTracker.registerSoftApCallback(callback, null)) {
                Log.e(TAG, "registerSoftApCallback: Failed to add callback");
                return;
            }
        }, TAG + "#registerSoftApCallback");
    }

    /**
     * see {@link android.net.wifi.WifiManager#unregisterSoftApCallback(WifiManager.SoftApCallback)}
     *
     * @param callback Soft AP callback to unregister
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     */
    @Override
    public void unregisterSoftApCallback(ISoftApCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)
                && !checkNetworkSettingsPermission(pid, uid)
                && !checkMainlineNetworkStackPermission(pid, uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read  WiFi Ap information "
                    + "(uid/pid = " + uid + "/" + pid + ")");
        }

        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterSoftApCallback uid=%").c(Binder.getCallingUid()).flush();
        }

        // post operation to handler thread
        mWifiThreadRunner.post(() ->
                mTetheredSoftApTracker.unregisterSoftApCallback(callback),
                TAG + "#unregisterSoftApCallback");
    }

    /**
     * Temporary method used for testing while start is not fully implemented.  This
     * method allows unit tests to register callbacks directly for testing mechanisms triggered by
     * softap mode changes.
     */
    @VisibleForTesting
    int registerLOHSForTest(int pid, LocalOnlyHotspotRequestInfo request) {
        return mLohsSoftApTracker.start(pid, request);
    }

    /**
     * Method to start LocalOnlyHotspot.  In this method, permissions, settings and modes are
     * checked to verify that we can enter softapmode.  This method returns
     * {@link LocalOnlyHotspotCallback#REQUEST_REGISTERED} if we will attempt to start, otherwise,
     * possible startup erros may include tethering being disallowed failure reason {@link
     * LocalOnlyHotspotCallback#ERROR_TETHERING_DISALLOWED} or an incompatible mode failure reason
     * {@link LocalOnlyHotspotCallback#ERROR_INCOMPATIBLE_MODE}.
     *
     * see {@link WifiManager#startLocalOnlyHotspot(LocalOnlyHotspotCallback)}
     *
     * @param callback Callback to communicate with WifiManager and allow cleanup if the app dies.
     * @param packageName String name of the calling package.
     * @param featureId The feature in the package
     * @param customConfig Custom configuration to be applied to the hotspot, or null for a shared
     *                     hotspot with framework-generated config.
     * @param extras Bundle of extra information
     *
     * @return int return code for attempt to start LocalOnlyHotspot.
     *
     * @throws SecurityException if the caller does not have permission to start a Local Only
     * Hotspot.
     * @throws IllegalStateException if the caller attempts to start the LocalOnlyHotspot while they
     * have an outstanding request.
     */
    @Override
    public int startLocalOnlyHotspot(ILocalOnlyHotspotCallback callback, String packageName,
            String featureId, SoftApConfiguration customConfig, Bundle extras,
            boolean isCalledFromSystemApi) {
        // first check if the caller has permission to start a local only hotspot
        // need to check for WIFI_STATE_CHANGE and location permission
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);

        mLog.info("start lohs uid=% pid=%").c(uid).c(pid).flush();

        // Permission requirements are different with/without custom config.
        // From B, the custom config may from public API, check isCalledFromSystemApi
        if (customConfig == null
                || (Environment.isSdkAtLeastB() && !isCalledFromSystemApi)) {
            if (enforceChangePermission(packageName) != MODE_ALLOWED) {
                return LocalOnlyHotspotCallback.ERROR_GENERIC;
            }
            if (isPlatformOrTargetSdkLessThanT(packageName, uid)) {
                enforceLocationPermission(packageName, featureId, uid);
                long ident = Binder.clearCallingIdentity();
                try {
                    // also need to verify that Locations services are enabled.
                    if (!mWifiPermissionsUtil.isLocationModeEnabled()) {
                        throw new SecurityException("Location mode is not enabled.");
                    }

                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                mWifiPermissionsUtil.enforceNearbyDevicesPermission(
                        extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE),
                        false, TAG + " startLocalOnlyHotspot");
            }
        } else {
            if (isPlatformOrTargetSdkLessThanT(packageName, uid)) {
                if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
                    throw new SecurityException(TAG + ": Permission denied");
                }
            } else {
                mWifiPermissionsUtil.enforceNearbyDevicesPermission(
                        extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE),
                        false, TAG + " startLocalOnlyHotspot");
            }
        }

        final WorkSource requestorWs = new WorkSource(uid, packageName);
        // the app should be in the foreground
        long ident = Binder.clearCallingIdentity();
        try {
            // verify that tethering is not disabled
            if (mUserManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_CONFIG_TETHERING, UserHandle.getUserHandleForUid(uid))) {
                return LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
            }

            mLastCallerInfoManager.put(WifiManager.API_START_LOCAL_ONLY_HOTSPOT, Process.myTid(),
                    uid, Binder.getCallingPid(), packageName, true);
            // also need to verify that Locations services are enabled.
            // bypass shell with root uid
            if (uid != Process.ROOT_UID
                    && !mFrameworkFacade.isAppForeground(mContext, uid)) {
                return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
            }
            // check if we are currently tethering
            if (!mActiveModeWarden.canRequestMoreSoftApManagers(requestorWs)
                    && mTetheredSoftApTracker.getState().getState() == WIFI_AP_STATE_ENABLED) {
                // Tethering is enabled, cannot start LocalOnlyHotspot
                mLog.info("Cannot start localOnlyHotspot when WiFi Tethering is active.")
                        .flush();
                return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        // now create the new LOHS request info object
        LocalOnlyHotspotRequestInfo request = new LocalOnlyHotspotRequestInfo(
                mWifiHandlerThread.getLooper(), requestorWs, callback,
                new LocalOnlyRequestorCallback(), customConfig);

        return mLohsSoftApTracker.start(pid, request);
    }

    /**
     * see {@link WifiManager#stopLocalOnlyHotspot()}
     *
     * @throws SecurityException if the caller does not have permission to stop a Local Only
     * Hotspot.
     */
    @Override
    public void stopLocalOnlyHotspot() {
        // don't do a permission check here. if the app's permission to change the wifi state is
        // revoked, we still want them to be able to stop a previously created hotspot (otherwise
        // it could cost the user money). When the app created the hotspot, its permission was
        // checked.
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();

        mLog.info("stopLocalOnlyHotspot uid=% pid=%").c(uid).c(pid).flush();
        // Force to disable lohs when caller is shell with root permission
        if (uid == Process.ROOT_UID) {
            mLohsSoftApTracker.stopAll();
        } else {
            mLohsSoftApTracker.stopByPid(pid);
        }
    }

    @Override
    public void registerLocalOnlyHotspotSoftApCallback(ISoftApCallback callback, Bundle extras) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }

        // verify arguments
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        AttributionSource attributionSource =
                extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE);
        mWifiPermissionsUtil.enforceNearbyDevicesPermission(attributionSource,
                false, TAG + " registerLocalOnlyHotspotSoftApCallback");

        if (mVerboseLoggingEnabled) {
            mLog.info("registerLocalOnlyHotspotSoftApCallback uid=%")
                    .c(Binder.getCallingUid()).flush();
        }

        // post operation to handler thread
        mWifiThreadRunner.post(() -> {
            if (!mLohsSoftApTracker.registerSoftApCallback(callback, attributionSource)) {
                Log.e(TAG, "registerLocalOnlyHotspotSoftApCallback: Failed to add callback");
            }
        }, TAG + "#registerLocalOnlyHotspotSoftApCallback");
    }

    @Override
    public void unregisterLocalOnlyHotspotSoftApCallback(ISoftApCallback callback, Bundle extras) {
        // verify arguments
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        mWifiPermissionsUtil.enforceNearbyDevicesPermission(
                extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE),
                false, TAG + " registerLocalOnlyHotspotSoftApCallback");

        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterLocalOnlyHotspotSoftApCallback uid=%")
                    .c(Binder.getCallingUid()).flush();
        }

        // post operation to handler thread
        mWifiThreadRunner.post(() ->
                mLohsSoftApTracker.unregisterSoftApCallback(callback),
                TAG + "#unregisterLocalOnlyHotspotSoftApCallback");
    }

    /**
     * see {@link WifiManager#watchLocalOnlyHotspot(LocalOnlyHotspotObserver)}
     *
     * This call requires the android.permission.NETWORK_SETTINGS permission.
     *
     * @param callback Callback to communicate with WifiManager and allow cleanup if the app dies.
     *
     * @throws SecurityException if the caller does not have permission to watch Local Only Hotspot
     * status updates.
     * @throws IllegalStateException if the caller attempts to watch LocalOnlyHotspot updates with
     * an existing subscription.
     */
    @Override
    public void startWatchLocalOnlyHotspot(ILocalOnlyHotspotCallback callback) {
        // NETWORK_SETTINGS is a signature only permission.
        enforceNetworkSettingsPermission();

        throw new UnsupportedOperationException("LocalOnlyHotspot is still in development");
    }

    /**
     * see {@link WifiManager#unregisterLocalOnlyHotspotObserver()}
     */
    @Override
    public void stopWatchLocalOnlyHotspot() {
        // NETWORK_STACK is a signature only permission.
        enforceNetworkSettingsPermission();
        throw new UnsupportedOperationException("LocalOnlyHotspot is still in development");
    }

    /**
     * see {@link WifiManager#getWifiApConfiguration()}
     * @return soft access point configuration
     * @throws SecurityException if the caller does not have permission to retrieve the softap
     * config
     */
    @Nullable
    @Override
    public WifiConfiguration getWifiApConfiguration() {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        // only allow Settings UI to get the saved SoftApConfig
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read or update stored WiFi Ap config "
                    + "(uid = " + uid + ")");
        }

        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiApConfiguration uid=%").c(uid).flush();
        }

        final SoftApConfiguration config = mWifiApConfigStore.getApConfiguration();
        return config == null ? new SoftApConfiguration.Builder().build().toWifiConfiguration()
                : config.toWifiConfiguration();
    }

    /**
     * see {@link WifiManager#getSoftApConfiguration()}
     * @return soft access point configuration {@link SoftApConfiguration}
     * @throws SecurityException if the caller does not have permission to retrieve the softap
     * config
     */
    @NonNull
    @Override
    public SoftApConfiguration getSoftApConfiguration() {
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read or update stored WiFi Ap config "
                    + "(uid = " + uid + ")");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getSoftApConfiguration uid=%").c(uid).flush();
        }

        final SoftApConfiguration config = mWifiApConfigStore.getApConfiguration();
        return config == null ? new SoftApConfiguration.Builder().build() : config;
    }

    /**
     * See {@code WifiManager#queryLastConfiguredTetheredApPassphraseSinceBoot(Executor, Consumer)}
     */
    @Override
    public void queryLastConfiguredTetheredApPassphraseSinceBoot(IStringListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException("App not allowed to read last WiFi AP passphrase "
                    + "(uid = " + uid + ")");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiApConfigStore
                        .getLastConfiguredTetheredApPassphraseSinceBoot());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#queryLastConfiguredTetheredApPassphraseSinceBoot");
    }

    /**
     * see {@link WifiManager#setWifiApConfiguration(WifiConfiguration)}
     * @param wifiConfig WifiConfiguration details for soft access point
     * @return boolean indicating success or failure of the operation
     * @throws SecurityException if the caller does not have permission to write the softap config
     */
    @Override
    public boolean setWifiApConfiguration(WifiConfiguration wifiConfig, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        // only allow Settings UI to write the stored SoftApConfig
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read or update stored WiFi AP config "
                    + "(uid = " + uid + ")");
        }
        mLog.info("setWifiApConfiguration uid=%").c(uid).flush();
        if (wifiConfig == null)
            return false;
        SoftApConfiguration softApConfig = ApConfigUtil.fromWifiConfiguration(wifiConfig);
        if (softApConfig == null) return false;
        if (!WifiApConfigStore.validateApWifiConfiguration(
                softApConfig, false, mContext, mWifiNative)) {
            Log.e(TAG, "Invalid WifiConfiguration");
            return false;
        }
        mWifiApConfigStore.setApConfiguration(softApConfig);
        return true;
    }

    /**
     * see {@link WifiManager#setSoftApConfiguration(SoftApConfiguration)}
     * @param softApConfig {@link SoftApConfiguration} details for soft access point
     * @return boolean indicating success or failure of the operation
     * @throws SecurityException if the caller does not have permission to write the softap config
     */
    @Override
    public boolean setSoftApConfiguration(
            @NonNull SoftApConfiguration softApConfig, @NonNull String packageName) {
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        boolean privileged = mWifiPermissionsUtil.checkNetworkSettingsPermission(uid);
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)
                && !privileged) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read or update stored WiFi Ap config "
                    + "(uid = " + uid + ")");
        }
        mLog.info("setSoftApConfiguration uid=%").c(uid).flush();
        if (softApConfig == null) return false;
        if (WifiApConfigStore.validateApWifiConfiguration(softApConfig, privileged, mContext,
                mWifiNative)) {
            mWifiApConfigStore.setApConfiguration(softApConfig);
            // Send the message for AP config update after the save is done.
            mActiveModeWarden.updateSoftApConfiguration(softApConfig);
            return true;
        } else {
            Log.e(TAG, "Invalid SoftAp Configuration");
            return false;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#setScanAlwaysAvailable(boolean)}
     */
    @Override
    public void setScanAlwaysAvailable(boolean isAvailable, String packageName) {
        enforceNetworkSettingsPermission();
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        mLog.info("setScanAlwaysAvailable uid=% package=% isAvailable=%")
                .c(callingUid)
                .c(packageName)
                .c(isAvailable)
                .flush();
        mSettingsStore.handleWifiScanAlwaysAvailableToggled(isAvailable);
        long ident = Binder.clearCallingIdentity();
        try {
            mWifiInjector.getWifiScanAlwaysAvailableSettingsCompatibility()
                    .handleWifiScanAlwaysAvailableToggled(isAvailable);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        mActiveModeWarden.scanAlwaysModeChanged();
    }

    /**
     * see {@link android.net.wifi.WifiManager#isScanAlwaysAvailable()}
     */
    @Override
    public boolean isScanAlwaysAvailable() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("isScanAlwaysAvailable uid=%").c(Binder.getCallingUid()).flush();
        }
        return mSettingsStore.isScanAlwaysAvailableToggleEnabled();
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     */
    @Override
    public boolean disconnect(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), callingUid)) {
            mLog.info("disconnect not allowed for uid=%").c(callingUid).flush();
            return false;
        }
        mLog.info("disconnect uid=%").c(callingUid).flush();
        mWifiThreadRunner.post(() -> mActiveModeWarden.getPrimaryClientModeManager().disconnect(),
                TAG + "#disconnect");
        return true;
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     */
    @Override
    public boolean reconnect(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isTargetSdkLessThanQOrPrivileged(packageName, Binder.getCallingPid(), callingUid)) {
            mLog.info("reconnect not allowed for uid=%").c(callingUid).flush();
            return false;
        }
        mLog.info("reconnect uid=%").c(callingUid).flush();

        mWifiThreadRunner.post(() -> {
            mActiveModeWarden.getPrimaryClientModeManager().reconnect(new WorkSource(callingUid));
        }, TAG + "#reconnect");
        return true;
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     */
    @Override
    public boolean reassociate(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), callingUid)) {
            mLog.info("reassociate not allowed for uid=%").c(callingUid).flush();
            return false;
        }
        mLog.info("reassociate uid=%").c(callingUid).flush();
        mWifiThreadRunner.post(() -> mActiveModeWarden.getPrimaryClientModeManager().reassociate(),
                TAG + "#reassociate");
        return true;
    }

    /**
     * Returns true if we should log the call to isFeatureSupported.
     *
     * Because of the way isFeatureSupported is used in WifiManager, there are
     * often clusters of several back-to-back calls; avoid repeated logging if
     * the feature set has not changed and the time interval is short.
     */
    private boolean needToLogSupportedFeatures(BitSet features) {
        if (mVerboseLoggingEnabled) {
            long now = mClock.getElapsedSinceBootMillis();
            synchronized (this) {
                if (now > mLastLoggedSupportedFeaturesTimestamp + A_FEW_MILLISECONDS
                        || !features.equals(mLastLoggedSupportedFeatures)) {
                    mLastLoggedSupportedFeaturesTimestamp = now;
                    mLastLoggedSupportedFeatures = features;
                    return true;
                }
            }
        }
        return false;
    }
    private static final int A_FEW_MILLISECONDS = 250;
    private BitSet mLastLoggedSupportedFeatures = new BitSet();
    private long mLastLoggedSupportedFeaturesTimestamp = 0;

    @Override
    public boolean isFeatureSupported(int feature) {
        enforceAccessPermission();
        BitSet supportedFeatures = getSupportedFeaturesInternal();
        if (needToLogSupportedFeatures(supportedFeatures)) {
            mLog.info("isFeatureSupported uid=% returns %")
                    .c(Binder.getCallingUid())
                    .c(FeatureBitsetUtils.formatSupportedFeatures(supportedFeatures))
                    .flush();
        }
        return supportedFeatures.get(feature);
    }

    @Override
    public void getWifiActivityEnergyInfoAsync(@NonNull IOnWifiActivityEnergyInfoListener
            listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiActivityEnergyInfoAsync uid=%")
                    .c(Binder.getCallingUid())
                    .flush();
        }
        if (!isFeatureSupported(WifiManager.WIFI_FEATURE_LINK_LAYER_STATS)) {
            try {
                listener.onWifiActivityEnergyInfo(null);
            } catch (RemoteException e) {
                Log.e(TAG, "onWifiActivityEnergyInfo: RemoteException -- ", e);
            }
            return;
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onWifiActivityEnergyInfo(getWifiActivityEnergyInfo());
            } catch (RemoteException e) {
                Log.e(TAG, "onWifiActivityEnergyInfo: RemoteException -- ", e);
            }
        }, TAG + "#getWifiActivityEnergyInfoAsync");
    }

    private WifiActivityEnergyInfo getWifiActivityEnergyInfo() {
        WifiLinkLayerStats stats = mActiveModeWarden
                .getPrimaryClientModeManager().getWifiLinkLayerStats();
        if (stats == null) {
            return null;
        }
        final long rxIdleTimeMillis = stats.on_time - stats.tx_time - stats.rx_time;
        if (VDBG || rxIdleTimeMillis < 0 || stats.on_time < 0 || stats.tx_time < 0
                || stats.rx_time < 0 || stats.on_time_scan < 0) {
            Log.d(TAG, " getWifiActivityEnergyInfo: "
                    + " on_time_millis=" + stats.on_time
                    + " tx_time_millis=" + stats.tx_time
                    + " rx_time_millis=" + stats.rx_time
                    + " rxIdleTimeMillis=" + rxIdleTimeMillis
                    + " scan_time_millis=" + stats.on_time_scan);
        }
        // Convert the LinkLayerStats into WifiActivityEnergyInfo
        return new WifiActivityEnergyInfo(
                mClock.getElapsedSinceBootMillis(),
                WifiActivityEnergyInfo.STACK_STATE_STATE_IDLE,
                stats.tx_time,
                stats.rx_time,
                stats.on_time_scan,
                rxIdleTimeMillis);
    }

    /**
     * see {@link android.net.wifi.WifiManager#getConfiguredNetworks()}
     *
     * @param packageName String name of the calling package
     * @param featureId The feature in the package
     * @param callerNetworksOnly Whether to only return networks created by the caller
     * @return the list of configured networks
     */
    @Override
    public ParceledListSlice<WifiConfiguration> getConfiguredNetworks(String packageName,
            String featureId, boolean callerNetworksOnly) {
        enforceAccessPermission();
        int callingUid = Binder.getCallingUid();
        // bypass shell: can get various pkg name
        // also bypass if caller is only retrieving networks added by itself
        if (callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID) {
            mWifiPermissionsUtil.checkPackage(callingUid, packageName);
            if (!callerNetworksOnly) {
                long ident = Binder.clearCallingIdentity();
                try {
                    mWifiPermissionsUtil.enforceCanAccessScanResults(packageName, featureId,
                            callingUid, null);
                } catch (SecurityException e) {
                    Log.w(TAG, "Permission violation - getConfiguredNetworks not allowed for uid="
                            + callingUid + ", packageName=" + packageName + ", reason=" + e);
                    return new ParceledListSlice<>(new ArrayList<>());
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
        boolean isDeviceOrProfileOwner = isDeviceOrProfileOwner(callingUid, packageName);
        boolean isCarrierApp = mWifiInjector.makeTelephonyManager()
                .checkCarrierPrivilegesForPackageAnyPhone(packageName)
                == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
        boolean isPrivileged = isPrivileged(getCallingPid(), callingUid);
        // Only DO, PO, carrier app or system app can use callerNetworksOnly argument
        if (callerNetworksOnly) {
            if (!isDeviceOrProfileOwner && !isCarrierApp && !isPrivileged) {
                throw new SecurityException(
                        "Not a DO, PO, carrier or privileged app");
            }
        }
        boolean isTargetSdkLessThanQOrPrivileged = isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), callingUid);
        if (!isTargetSdkLessThanQOrPrivileged && !isCarrierApp) {
            mLog.info("getConfiguredNetworks not allowed for uid=%")
                    .c(callingUid).flush();
            return new ParceledListSlice<>(new ArrayList<>());
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getConfiguredNetworks uid=%").c(callingUid).flush();
        }

        int targetConfigUid = Process.INVALID_UID; // don't expose any MAC addresses
        if (isPrivileged) {
            targetConfigUid = WIFI_UID; // expose all MAC addresses
        } else if (isCarrierApp || isDeviceOrProfileOwner) {
            targetConfigUid = callingUid; // expose only those configs created by the calling App
        }
        int finalTargetConfigUid = targetConfigUid;
        List<WifiConfiguration> configs = mWifiThreadRunner.call(
                () -> mWifiConfigManager.getSavedNetworks(finalTargetConfigUid),
                Collections.emptyList(), TAG + "#getConfiguredNetworks");
        if (isTargetSdkLessThanQOrPrivileged && !callerNetworksOnly) {
            return new ParceledListSlice<>(
                    WifiConfigurationUtil.convertMultiTypeConfigsToLegacyConfigs(configs, false));
        }
        // Should only get its own configs
        List<WifiConfiguration> creatorConfigs = new ArrayList<>();
        for (WifiConfiguration config : configs) {
            if (config.creatorUid == callingUid) {
                creatorConfigs.add(config);
            }
        }
        return new ParceledListSlice<>(
                WifiConfigurationUtil.convertMultiTypeConfigsToLegacyConfigs(
                        creatorConfigs, true));
    }

    /**
     * see {@link android.net.wifi.WifiManager#getPrivilegedConfiguredNetworks()}
     *
     * @param packageName String name of the calling package
     * @param featureId The feature in the package
     * @param extras - Bundle of extra information
     * @return the list of configured networks with real preSharedKey
     */
    @Override
    public ParceledListSlice<WifiConfiguration> getPrivilegedConfiguredNetworks(
            String packageName, String featureId, Bundle extras) {
        enforceReadCredentialPermission();
        enforceAccessPermission();
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (isPlatformOrTargetSdkLessThanT(packageName, callingUid)) {
            // For backward compatibility, do not check for nearby devices permission on pre-T
            // SDK version or if the app targets pre-T.
            long ident = Binder.clearCallingIdentity();
            try {
                mWifiPermissionsUtil.enforceCanAccessScanResults(packageName, featureId, callingUid,
                        null);
            } catch (SecurityException e) {
                Log.w(TAG, "Permission violation - getPrivilegedConfiguredNetworks not allowed"
                        + " for uid=" + callingUid + ", packageName=" + packageName + ", reason="
                        + e);
                return null;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        } else {
            try {
                mWifiPermissionsUtil.enforceNearbyDevicesPermission(
                        extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE),
                        false, TAG + " getPrivilegedConfiguredNetworks");
            } catch (SecurityException e) {
                Log.w(TAG, "Permission violation - getPrivilegedConfiguredNetworks not allowed"
                        + " for uid=" + callingUid + ", packageName=" + packageName + ", reason="
                        + e);
                return null;
            }
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getPrivilegedConfiguredNetworks uid=%").c(callingUid).flush();
        }
        List<WifiConfiguration> configs = mWifiThreadRunner.call(
                () -> mWifiConfigManager.getConfiguredNetworksWithPasswords(),
                Collections.emptyList(), TAG + "#getPrivilegedConfiguredNetworks");
        return new ParceledListSlice<>(
                WifiConfigurationUtil.convertMultiTypeConfigsToLegacyConfigs(configs, false));
    }

    /**
     * See {@link WifiManager#getPrivilegedConnectedNetwork()}
     */
    @Override
    public WifiConfiguration getPrivilegedConnectedNetwork(String packageName, String featureId,
            Bundle extras) {
        enforceReadCredentialPermission();
        enforceAccessPermission();
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (isPlatformOrTargetSdkLessThanT(packageName, callingUid)) {
            mWifiPermissionsUtil.enforceCanAccessScanResults(packageName, featureId, callingUid,
                    null);
        } else {
            mWifiPermissionsUtil.enforceNearbyDevicesPermission(
                    extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE),
                    true, TAG + " getPrivilegedConnectedNetwork");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getPrivilegedConnectedNetwork uid=%").c(callingUid).flush();
        }

        WifiInfo wifiInfo = mActiveModeWarden.getConnectionInfo();
        int networkId = wifiInfo.getNetworkId();
        if (networkId < 0) {
            if (mVerboseLoggingEnabled) {
                mLog.info("getPrivilegedConnectedNetwork primary wifi not connected")
                        .flush();
            }
            return null;
        }
        WifiConfiguration config = mWifiThreadRunner.call(
                () -> mWifiConfigManager.getConfiguredNetworkWithPassword(networkId), null,
                TAG + "#getPrivilegedConnectedNetwork");
        if (config == null) {
            if (mVerboseLoggingEnabled) {
                mLog.info("getPrivilegedConnectedNetwork failed to get config").flush();
            }
            return null;
        }
        // mask out the randomized MAC address
        config.setRandomizedMacAddress(MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS));
        return config;
    }

    /**
     * See {@link WifiManager#setNetworkSelectionConfig(WifiNetworkSelectionConfig)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setNetworkSelectionConfig(@NonNull WifiNetworkSelectionConfig nsConfig) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        if (nsConfig == null) {
            throw new IllegalArgumentException("Config can not be null");
        }
        if (!nsConfig.isValid()) {
            throw new IllegalArgumentException("Config is invalid.");
        }
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException("Uid=" + uid + ", is not allowed to set network selection "
                    + "config");
        }
        mLog.info("uid=% WifiNetworkSelectionConfig=%")
                .c(uid).c(nsConfig.toString()).flush();

        mWifiThreadRunner.post(() -> {
            mNetworkSelectionConfig = nsConfig;
            mWifiConnectivityManager.setNetworkSelectionConfig(nsConfig);
        }, TAG + "#setNetworkSelectionConfig");
        mLastCallerInfoManager.put(
                WifiManager.API_SET_NETWORK_SELECTION_CONFIG,
                Process.myTid(),
                uid, Binder.getCallingPid(), "<unknown>", true);
    }

    /**
     * See {@link WifiManager#getNetworkSelectionConfig(Executor, Consumer)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void getNetworkSelectionConfig(@NonNull IWifiNetworkSelectionConfigListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException("Uid=" + uid + ", is not allowed to get network selection "
                    + "config");
        }
        mWifiThreadRunner.post(() -> {
            try {
                if (mNetworkSelectionConfig == null) {
                    mNetworkSelectionConfig = new WifiNetworkSelectionConfig.Builder().build();
                }
                WifiNetworkSelectionConfig.Builder builder =
                        new WifiNetworkSelectionConfig.Builder(mNetworkSelectionConfig);
                ScoringParams scoringParams = mWifiInjector.getScoringParams();
                if (WifiNetworkSelectionConfig.isRssiThresholdResetArray(
                        mNetworkSelectionConfig.getRssiThresholds(WIFI_BAND_24_GHZ))) {
                    builder = builder.setRssiThresholds(WIFI_BAND_24_GHZ,
                            scoringParams.getRssiArray(ScanResult.BAND_24_GHZ_START_FREQ_MHZ));
                }
                if (WifiNetworkSelectionConfig.isRssiThresholdResetArray(
                        mNetworkSelectionConfig.getRssiThresholds(WIFI_BAND_5_GHZ))) {
                    builder = builder.setRssiThresholds(WIFI_BAND_5_GHZ,
                            scoringParams.getRssiArray(ScanResult.BAND_5_GHZ_START_FREQ_MHZ));
                }
                if (WifiNetworkSelectionConfig.isRssiThresholdResetArray(
                        mNetworkSelectionConfig.getRssiThresholds(WIFI_BAND_6_GHZ))) {
                    builder = builder.setRssiThresholds(WIFI_BAND_6_GHZ,
                            scoringParams.getRssiArray(ScanResult.BAND_6_GHZ_START_FREQ_MHZ));
                }
                mNetworkSelectionConfig = builder.build();
                listener.onResult(mNetworkSelectionConfig);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getNetworkSelectionConfig");
    }

    /**
     * See {@link WifiManager#setThirdPartyAppEnablingWifiConfirmationDialogEnabled(boolean)}
     */
    @Override
    public void setThirdPartyAppEnablingWifiConfirmationDialogEnabled(boolean enable) {
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid)) {
            throw new SecurityException("Uid=" + uid + ", is not allowed to enable warning dialog "
                    + "to display when third party apps start wifi");
        }
        mLog.info("uid=% enableWarningDialog=%").c(uid).c(enable).flush();
        mSettingsConfigStore.put(SHOW_DIALOG_WHEN_THIRD_PARTY_APPS_ENABLE_WIFI, enable);
        mSettingsConfigStore.put(SHOW_DIALOG_WHEN_THIRD_PARTY_APPS_ENABLE_WIFI_SET_BY_API, true);
        mLastCallerInfoManager.put(
                WifiManager.API_SET_THIRD_PARTY_APPS_ENABLING_WIFI_CONFIRMATION_DIALOG,
                Process.myTid(),
                uid, Binder.getCallingPid(), "<unknown>", enable);
    }

    private boolean showDialogWhenThirdPartyAppsEnableWifi() {
        if (mSettingsConfigStore.get(SHOW_DIALOG_WHEN_THIRD_PARTY_APPS_ENABLE_WIFI_SET_BY_API)) {
            // API was called to override the overlay value.
            return mSettingsConfigStore.get(SHOW_DIALOG_WHEN_THIRD_PARTY_APPS_ENABLE_WIFI);
        } else {
            return mResourceCache.getBoolean(
                    R.bool.config_showConfirmationDialogForThirdPartyAppsEnablingWifi);
        }
    }

    /**
     * See {@link WifiManager#isThirdPartyAppEnablingWifiConfirmationDialogEnabled()}
     */
    @Override
    public boolean isThirdPartyAppEnablingWifiConfirmationDialogEnabled() {
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid)) {
            throw new SecurityException("Uid=" + uid + ", is not allowed to check if warning "
                    + "dialog is enabled when third party apps start wifi");
        }
        return showDialogWhenThirdPartyAppsEnableWifi();
    }

    /**
     * See {@link WifiManager#setScreenOnScanSchedule(List)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setScreenOnScanSchedule(int[] scanScheduleSeconds, int[] scanType) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        if ((scanScheduleSeconds == null && scanType != null)
                || (scanScheduleSeconds != null && scanType == null)) {
            throw new IllegalArgumentException("scanSchedule and scanType should be either both"
                    + " non-null or both null");
        }
        if (scanScheduleSeconds != null && scanScheduleSeconds.length < 1) {
            throw new IllegalArgumentException("scanSchedule should have length > 0, or be null");
        }
        if (scanType != null) {
            if (scanType.length < 1) {
                throw new IllegalArgumentException("scanType should have length > 0, or be null");
            }
            for (int type : scanType) {
                if (type < 0 || type > WifiScanner.SCAN_TYPE_MAX) {
                    throw new IllegalArgumentException("scanType=" + type
                            + " is not a valid value");
                }
            }
        }
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException("Uid=" + uid + ", is not allowed to set scan schedule");
        }
        mLog.info("scanSchedule=% scanType=% uid=%").c(Arrays.toString(scanScheduleSeconds))
                .c(Arrays.toString(scanType)).c(uid).flush();
        mWifiThreadRunner.post(() -> mWifiConnectivityManager.setExternalScreenOnScanSchedule(
                scanScheduleSeconds, scanType), TAG + "#setScreenOnScanSchedule");
        mLastCallerInfoManager.put(WifiManager.API_SET_SCAN_SCHEDULE, Process.myTid(),
                uid, Binder.getCallingPid(), "<unknown>",
                scanScheduleSeconds != null);
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setOneShotScreenOnConnectivityScanDelayMillis(int delayMs) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs should not be negative");
        }
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException("Uid=" + uid + ", is not allowed to set screen-on scan "
                    + "delay");
        }
        mLog.info("delayMs=% uid=%").c(delayMs).c(uid).flush();
        mWifiThreadRunner.post(() ->
                mWifiConnectivityManager.setOneShotScreenOnConnectivityScanDelayMillis(delayMs),
                TAG + "#setOneShotScreenOnConnectivityScanDelayMillis");
        mLastCallerInfoManager.put(WifiManager.API_SET_ONE_SHOT_SCREEN_ON_CONNECTIVITY_SCAN_DELAY,
                Process.myTid(), uid, Binder.getCallingPid(), "<unknown>",
                delayMs > 0);
    }

    /**
     * Return a map of all matching configurations keys with corresponding scanResults (or an empty
     * map if none).
     *
     * @param scanResults The list of scan results
     * @return Map that consists of FQDN (Fully Qualified Domain Name) and corresponding
     * scanResults per network type({@link WifiManager#PASSPOINT_HOME_NETWORK} and {@link
     * WifiManager#PASSPOINT_ROAMING_NETWORK}).
     */
    @Override
    public Map<String, Map<Integer, List<ScanResult>>>
            getAllMatchingPasspointProfilesForScanResults(
                    ParceledListSlice<ScanResult> scanResults) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingPasspointConfigurations uid=%").c(Binder.getCallingUid()).flush();
        }
        if (scanResults == null || !ScanResultUtil.validateScanResultList(scanResults.getList())) {
            Log.e(TAG, "Attempt to retrieve passpoint with invalid scanResult List");
            return Collections.emptyMap();
        }
        return mWifiThreadRunner.call(
            () -> mPasspointManager.getAllMatchingPasspointProfilesForScanResults(
                    scanResults.getList()), Collections.emptyMap(),
                TAG + "#getAllMatchingPasspointProfilesForScanResults");
    }

    /**
     * See {@link WifiManager#setSsidsAllowlist(Set)}
     */
    @Override
    public void setSsidsAllowlist(@NonNull String packageName,
            @NonNull ParceledListSlice<WifiSsid> ssids) {
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        boolean hasPermission = mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                || isDeviceOrProfileOwner(uid, packageName);
        if (!hasPermission && SdkLevel.isAtLeastT()) {
            // MANAGE_WIFI_NETWORK_SELECTION is a new permission added in T.
            hasPermission = mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid);
        }
        if (!hasPermission) {
            throw new SecurityException(TAG + "Uid " + uid + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("setSsidsAllowlist uid=%").c(uid).flush();
        }
        List<WifiSsid> ssidList = ssids == null ? null : ssids.getList();
        mWifiThreadRunner.post(() -> mWifiBlocklistMonitor.setSsidsAllowlist(ssidList),
                TAG + "#setSsidsAllowlist");
    }

    /**
     * See {@link WifiManager#getSsidsAllowlist()}
     */
    @Override
    public @NonNull ParceledListSlice<WifiSsid> getSsidsAllowlist(String packageName) {
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        boolean hasPermission = mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                || isDeviceOrProfileOwner(uid, packageName);
        if (!hasPermission && SdkLevel.isAtLeastT()) {
            // MANAGE_WIFI_NETWORK_SELECTION is a new permission added in T.
            hasPermission = mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid);
        }
        if (!hasPermission) {
            throw new SecurityException(TAG + " Uid " + uid + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getSsidsAllowlist uid=%").c(uid).flush();
        }
        return new ParceledListSlice<>(mWifiThreadRunner.call(
                () -> mWifiBlocklistMonitor.getSsidsAllowlist(), Collections.EMPTY_LIST,
                TAG + "#getSsidsAllowlist"));
    }

    /**
     * Returns list of OSU (Online Sign-Up) providers associated with the given list of ScanResult.
     *
     * @param scanResults a list of ScanResult that has Passpoint APs.
     * @return Map that consists of {@link OsuProvider} and a matching list of {@link ScanResult}.
     */
    @Override
    public Map<OsuProvider, List<ScanResult>> getMatchingOsuProviders(
            ParceledListSlice<ScanResult> scanResults) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingOsuProviders uid=%").c(Binder.getCallingUid()).flush();
        }
        if (scanResults == null || !ScanResultUtil.validateScanResultList(scanResults.getList())) {
            Log.w(TAG, "Attempt to retrieve OsuProviders with invalid scanResult List");
            return Collections.emptyMap();
        }
        return mWifiThreadRunner.call(
            () -> mPasspointManager.getMatchingOsuProviders(scanResults.getList()),
                Collections.emptyMap(), TAG + "#getMatchingOsuProviders");
    }

    /**
     * Returns the matching Passpoint configurations for given OSU(Online Sign-Up) providers.
     *
     * @param osuProviders a list of {@link OsuProvider}
     * @return Map that consists of {@link OsuProvider} and matching {@link PasspointConfiguration}.
     */
    @Override
    public Map<OsuProvider, PasspointConfiguration> getMatchingPasspointConfigsForOsuProviders(
            ParceledListSlice<OsuProvider> osuProviders) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingPasspointConfigsForOsuProviders uid=%").c(
                    Binder.getCallingUid()).flush();
        }
        if (osuProviders == null || osuProviders.getList() == null) {
            Log.e(TAG, "Attempt to retrieve Passpoint configuration with null osuProviders");
            return new HashMap<>();
        }
        return mWifiThreadRunner.call(
            () -> mPasspointManager.getMatchingPasspointConfigsForOsuProviders(
                    osuProviders.getList()), Collections.emptyMap(),
                TAG + "#getMatchingPasspointConfigsForOsuProviders");
    }

    /**
     * Returns the corresponding wifi configurations for given FQDN (Fully Qualified Domain Name)
     * list.
     *
     * An empty list will be returned when no match is found.
     *
     * @param fqdnList a list of FQDN
     * @return List of {@link WifiConfiguration} converted from {@link PasspointProvider}
     */
    @Override
    public ParceledListSlice<WifiConfiguration> getWifiConfigsForPasspointProfiles(
            StringParceledListSlice fqdnList) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiConfigsForPasspointProfiles uid=%").c(
                    Binder.getCallingUid()).flush();
        }
        if (fqdnList == null || fqdnList.getList() == null || fqdnList.getList().isEmpty()) {
            Log.e(TAG, "Attempt to retrieve WifiConfiguration with null fqdn List");
            return new ParceledListSlice<>(Collections.emptyList());
        }
        return new ParceledListSlice<>(mWifiThreadRunner.call(
            () -> mPasspointManager.getWifiConfigsForPasspointProfiles(fqdnList.getList()),
                Collections.emptyList(), TAG + "#getWifiConfigsForPasspointProfiles"));
    }

    /**
     * Returns a list of Wifi configurations for matched available WifiNetworkSuggestion
     * corresponding to the given scan results.
     *
     * An empty list will be returned when no match is found or all matched suggestions is not
     * available(not allow user manually connect, user not approved or open network).
     *
     * @param scanResults a list of {@link ScanResult}.
     * @return a list of {@link WifiConfiguration} from matched {@link WifiNetworkSuggestion}.
     */
    @Override
    public ParceledListSlice<WifiConfiguration>
            getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(
                    ParceledListSlice<ScanResult> scanResults) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiConfigsForMatchedNetworkSuggestions uid=%").c(
                    Binder.getCallingUid()).flush();
        }
        if (scanResults == null || !ScanResultUtil.validateScanResultList(scanResults.getList())) {
            Log.w(TAG, "Attempt to retrieve WifiConfiguration with invalid scanResult List");
            return new ParceledListSlice<>(Collections.emptyList());
        }
        return new ParceledListSlice<>(WifiConfigurationUtil.convertMultiTypeConfigsToLegacyConfigs(
                mWifiThreadRunner.call(
                () -> mWifiNetworkSuggestionsManager
                        .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(
                                scanResults.getList()), Collections.emptyList(),
                TAG + "#getWifiConfigForMatchedNetworkSuggestionsSharedWithUser"), true));
    }

    /**
     * see {@link WifiManager#addNetworkPrivileged(WifiConfiguration)}
     * @return WifiManager.AddNetworkResult Object.
     */
    @Override
    public @NonNull WifiManager.AddNetworkResult addOrUpdateNetworkPrivileged(
            WifiConfiguration config, String packageName) {
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        boolean hasPermission = isPrivileged(pid, uid)
                || mWifiPermissionsUtil.isAdmin(uid, packageName)
                || mWifiPermissionsUtil.isSystem(packageName, uid);
        if (!hasPermission) {
            throw new SecurityException("Caller is not a device owner, profile owner, system app,"
                    + " or privileged app");
        }
        return addOrUpdateNetworkInternal(config, packageName, uid, packageName, false);
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOrUpdateNetwork(WifiConfiguration)}
     * @return the supplicant-assigned identifier for the new or updated
     * network if the operation succeeds, or {@code -1} if it fails
     */
    @Override
    public int addOrUpdateNetwork(WifiConfiguration config, String packageName, Bundle extras) {
        int uidToUse = getMockableCallingUid();
        String packageNameToUse = packageName;
        boolean overrideCreator = false;

        // if we're being called from the SYSTEM_UID then allow usage of the AttributionSource to
        // reassign the WifiConfiguration to another app (reassignment == creatorUid)
        if (SdkLevel.isAtLeastS() && UserHandle.getAppId(uidToUse) == Process.SYSTEM_UID) {
            if (extras == null) {
                throw new SecurityException("extras bundle is null");
            }
            AttributionSource as = extras.getParcelable(
                    WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE);
            if (as == null) {
                throw new SecurityException("addOrUpdateNetwork attributionSource is null");
            }

            if (!as.checkCallingUid()) {
                throw new SecurityException(
                        "addOrUpdateNetwork invalid (checkCallingUid fails) attribution source="
                                + as);
            }

            // an attribution chain is either of size 1: unregistered (valid by definition) or
            // size >1: in which case all are validated.
            if (as.getNext() != null) {
                AttributionSource asIt = as;
                AttributionSource asLast = as;
                do {
                    if (!asIt.isTrusted(mContext)) {
                        throw new SecurityException(
                                "addOrUpdateNetwork invalid (isTrusted fails) attribution source="
                                        + asIt);
                    }
                    asIt = asIt.getNext();
                    if (asIt != null) asLast = asIt;
                } while (asIt != null);

                // use the last AttributionSource in the chain - i.e. the original caller
                uidToUse = asLast.getUid();
                packageNameToUse = asLast.getPackageName();
                if (config.networkId >= 0) {
                    /**
                     * only allow to override the creator by calling the
                     * {@link WifiManager#updateNetwork(WifiConfiguration)}
                     */
                    overrideCreator = true;
                }
            }
        }

        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return -1;
        }

        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        boolean isAdmin = mWifiPermissionsUtil.isAdmin(callingUid, packageName);
        boolean isCamera = mWifiPermissionsUtil.checkCameraPermission(callingUid);
        boolean isSystem = mWifiPermissionsUtil.isSystem(packageName, callingUid);
        boolean isPrivileged = isPrivileged(callingPid, callingUid);
        if (!isPrivileged && !isSystem && !isAdmin && config.getBssidAllowlistInternal() != null) {
            mLog.info("addOrUpdateNetwork with allow bssid list is not allowed for uid=%")
                    .c(callingUid).flush();
            return -1;
        }

        if (!isTargetSdkLessThanQOrPrivileged(packageName, callingPid, callingUid)) {
            mLog.info("addOrUpdateNetwork not allowed for uid=%").c(callingUid).flush();
            return -1;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            if (mUserManager.hasUserRestrictionForUser(UserManager.DISALLOW_CONFIG_WIFI,
                    UserHandle.of(mWifiPermissionsUtil.getCurrentUser()))
                    && isCamera && !isAdmin) {
                mLog.info(
                        "addOrUpdateNetwork not allowed for the camera apps and therefore the "
                                + "user when DISALLOW_CONFIG_WIFI user restriction is set").flush();
                return -1;
            }
            if (SdkLevel.isAtLeastT() && mUserManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_ADD_WIFI_CONFIG,
                    UserHandle.getUserHandleForUid(callingUid))) {
                if (mWifiPermissionsUtil.isTargetSdkLessThan(
                        packageName, Build.VERSION_CODES.Q, callingUid)
                        && !(isPrivileged || isAdmin || isSystem)) {
                    mLog.info(
                            "addOrUpdateNetwork not allowed for normal apps targeting "
                                    + "SDK less than Q when the DISALLOW_ADD_WIFI_CONFIG "
                                    + "user restriction is set").flush();
                    return -1;
                }
                if (isCamera && !isAdmin) {
                    mLog.info(
                            "addOrUpdateNetwork not allowed for camera apps and therefore the "
                                    + "user when the DISALLOW_ADD_WIFI_CONFIG "
                                    + "user restriction is set").flush();
                    return -1;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        mLog.info("addOrUpdateNetwork uid=%").c(callingUid).flush();
        return addOrUpdateNetworkInternal(config, packageName, uidToUse,
                packageNameToUse, overrideCreator).networkId;
    }

    private @NonNull AddNetworkResult addOrUpdateNetworkInternal(WifiConfiguration config,
            String packageName, int attributedCreatorUid, String attributedCreatorPackage,
            boolean overrideCreator) {
        if (config == null) {
            Log.e(TAG, "bad network configuration");
            return new AddNetworkResult(
                    AddNetworkResult.STATUS_INVALID_CONFIGURATION, -1);
        }
        mWifiMetrics.incrementNumAddOrUpdateNetworkCalls();

        // Previously, this API is overloaded for installing Passpoint profiles.  Now
        // that we have a dedicated API for doing it, redirect the call to the dedicated API.
        if (config.isPasspoint()) {
            PasspointConfiguration passpointConfig =
                    PasspointProvider.convertFromWifiConfig(config);
            if (passpointConfig == null || passpointConfig.getCredential() == null) {
                Log.e(TAG, "Missing credential for Passpoint profile");
                return new AddNetworkResult(
                        AddNetworkResult.STATUS_ADD_PASSPOINT_FAILURE, -1);
            }

            // Copy over certificates and keys.
            X509Certificate[] x509Certificates = null;
            if (config.enterpriseConfig.getCaCertificate() != null) {
                x509Certificates =
                        new X509Certificate[]{config.enterpriseConfig.getCaCertificate()};
            }
            passpointConfig.getCredential().setCaCertificates(x509Certificates);
            passpointConfig.getCredential().setClientCertificateChain(
                    config.enterpriseConfig.getClientCertificateChain());
            passpointConfig.getCredential().setClientPrivateKey(
                    config.enterpriseConfig.getClientPrivateKey());
            if (!addOrUpdatePasspointConfiguration(passpointConfig, packageName)) {
                Log.e(TAG, "Failed to add Passpoint profile");
                return new AddNetworkResult(
                        AddNetworkResult.STATUS_ADD_PASSPOINT_FAILURE, -1);
            }
            // There is no network ID associated with a Passpoint profile.
            return new AddNetworkResult(AddNetworkResult.STATUS_SUCCESS, 0);
        }

        mLastCallerInfoManager.put(config.networkId < 0
                        ? WifiManager.API_ADD_NETWORK : WifiManager.API_UPDATE_NETWORK,
                Process.myTid(), Binder.getCallingUid(),
                Binder.getCallingPid(), packageName, true);
        Log.i("addOrUpdateNetworkInternal", " uid = " + Binder.getCallingUid()
                + " SSID " + config.SSID
                + " nid=" + config.networkId);
        NetworkUpdateResult result = mWifiThreadRunner.call(
                () -> mWifiConfigManager.addOrUpdateNetwork(config, attributedCreatorUid,
                        attributedCreatorPackage, overrideCreator),
                new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID),
                TAG + "#addOrUpdateNetworkInternal");
        return new AddNetworkResult(result.getStatusCode(), result.getNetworkId());
    }

    public static void verifyCert(X509Certificate caCert)
            throws GeneralSecurityException, IOException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        CertPathValidator validator =
                CertPathValidator.getInstance(CertPathValidator.getDefaultType());
        CertPath path = factory.generateCertPath(
                Arrays.asList(caCert));
        KeyStore ks = KeyStore.getInstance("AndroidCAStore");
        ks.load(null, null);
        PKIXParameters params = new PKIXParameters(ks);
        params.setRevocationEnabled(false);
        validator.validate(path, params);
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    @Override
    public boolean removeNetwork(int netId, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), callingUid)) {
            mLog.info("removeNetwork not allowed for uid=%").c(callingUid).flush();
            return false;
        }
        mLog.info("removeNetwork uid=%").c(callingUid).flush();
        return mWifiThreadRunner.call(
                () -> mWifiConfigManager.removeNetwork(netId, callingUid, packageName), false,
                TAG + "#removeNetwork");
    }

    @Override
    public boolean removeNonCallerConfiguredNetworks(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            throw new SecurityException("Caller does not hold CHANGE_WIFI_STATE permission");
        }
        final int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!mWifiPermissionsUtil.isOrganizationOwnedDeviceAdmin(callingUid, packageName)) {
            throw new SecurityException("Caller is not device owner or profile owner "
                    + "of an organization owned device");
        }
        return mWifiThreadRunner.call(
                () -> mWifiConfigManager.removeNonCallerConfiguredNetwork(callingUid), false,
                TAG + "#removeNonCallerConfiguredNetworks");
    }

    /**
     * Trigger a connect request and wait for the callback to return status.
     * This preserves the legacy connect API behavior, i.e. {@link WifiManager#enableNetwork(
     * int, true)}
     * @return
     */
    private boolean triggerConnectAndReturnStatus(int netId, int callingUid,
            @NonNull String packageName) {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final Mutable<Boolean> success = new Mutable<>(false);
        IActionListener.Stub connectListener = new IActionListener.Stub() {
            @Override
            public void onSuccess() {
                success.value = true;
                countDownLatch.countDown();
            }
            @Override
            public void onFailure(int reason) {
                success.value = false;
                countDownLatch.countDown();
            }
        };
        mWifiThreadRunner.post(() ->
                mMakeBeforeBreakManager.stopAllSecondaryTransientClientModeManagers(() ->
                        mConnectHelper.connectToNetwork(
                                new NetworkUpdateResult(netId),
                                new ActionListenerWrapper(connectListener),
                                callingUid, packageName, null)
                ), TAG + "#triggerConnectAndReturnStatus"
        );
        // now wait for response.
        try {
            countDownLatch.await(RUN_WITH_SCISSORS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Failed to retrieve connect status");
        }
        return success.value;
    }

    /**
     * See {@link android.net.wifi.WifiManager#enableNetwork(int, boolean)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @param disableOthers if true, disable all other networks.
     * @return {@code true} if the operation succeeded
     */
    @Override
    public boolean enableNetwork(int netId, boolean disableOthers, @NonNull String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        if (packageName == null) {
            throw new IllegalArgumentException("packageName must not be null");
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), callingUid)) {
            mLog.info("enableNetwork not allowed for uid=%").c(callingUid).flush();
            return false;
        }
        WifiConfiguration configuration = mWifiConfigManager.getConfiguredNetwork(netId);
        if (mWifiPermissionsUtil.isAdminRestrictedNetwork(configuration)) {
            mLog.info("enableNetwork not allowed for admin restricted network Id=%")
                    .c(netId).flush();
            return false;
        }
        if (mWifiGlobals.isDeprecatedSecurityTypeNetwork(configuration)) {
            mLog.info("enableNetwork not allowed for deprecated security type network Id=%")
                    .c(netId).flush();
            return false;
        }

        mLastCallerInfoManager.put(WifiManager.API_ENABLE_NETWORK, Process.myTid(),
                callingUid, Binder.getCallingPid(), packageName, disableOthers);
        // TODO b/33807876 Log netId
        mLog.info("enableNetwork uid=% disableOthers=%")
                .c(callingUid)
                .c(disableOthers).flush();

        mWifiMetrics.incrementNumEnableNetworkCalls();
        if (disableOthers) {
            return triggerConnectAndReturnStatus(netId, callingUid, packageName);
        } else {
            return mWifiThreadRunner.call(
                    () -> mWifiConfigManager.enableNetwork(netId, false, callingUid, packageName),
                    false,
                    TAG + "#enableNetwork");
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#disableNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    @Override
    public boolean disableNetwork(int netId, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), callingUid)) {
            mLog.info("disableNetwork not allowed for uid=%").c(callingUid).flush();
            return false;
        }
        mLastCallerInfoManager.put(WifiManager.API_DISABLE_NETWORK, Process.myTid(),
                callingUid, Binder.getCallingPid(), packageName, true);
        mLog.info("disableNetwork uid=%").c(callingUid).flush();
        return mWifiThreadRunner.call(
                () -> mWifiConfigManager.disableNetwork(netId, callingUid, packageName), false,
                TAG + "#disableNetwork");
    }

    /**
     * See
     * {@link android.net.wifi.WifiManager#startRestrictingAutoJoinToSubscriptionId(int)}
     * @param subscriptionId the subscription ID of the carrier whose merged wifi networks won't be
     *                       disabled.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.S)
    public void startRestrictingAutoJoinToSubscriptionId(int subscriptionId) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        mLog.info("startRestrictingAutoJoinToSubscriptionId=% uid=%").c(subscriptionId)
                .c(Binder.getCallingUid()).flush();
        mWifiThreadRunner.post(() -> {
            mWifiConfigManager
                    .startRestrictingAutoJoinToSubscriptionId(subscriptionId);
            // Clear all cached candidates to avoid the imminent disconnect connecting back to a
            // cached candidate that's likely no longer valid after
            // startRestrictingAutoJoinToSubscriptionId is called. Let the disconnection trigger
            // a new scan to ensure proper network selection is done.
            mWifiConnectivityManager.clearCachedCandidates();
            // always disconnect here and rely on auto-join to find the appropriate carrier network
            // to join. Even if we are currently connected to the carrier-merged wifi, it's still
            // better to disconnect here because it's possible that carrier wifi offload is
            // disabled.
            for (ClientModeManager clientModeManager : mActiveModeWarden.getClientModeManagers()) {
                if (!(clientModeManager instanceof ConcreteClientModeManager)) {
                    continue;
                }
                ConcreteClientModeManager cmm = (ConcreteClientModeManager) clientModeManager;
                if ((cmm.getRole() == ROLE_CLIENT_SECONDARY_LONG_LIVED && cmm.isSecondaryInternet())
                        || cmm.getRole() == ROLE_CLIENT_SECONDARY_TRANSIENT) {
                    clientModeManager.disconnect();
                }
            }
            // Disconnect the primary CMM last to avoid STA+STA features handling the
            // primary STA disconnecting (such as promoting the secondary to primary), potentially
            // resulting in messy and unexpected state transitions.
            mActiveModeWarden.getPrimaryClientModeManager().disconnect();
        }, TAG + "#startRestrictingAutoJoinToSubscriptionId");
    }

    /**
     * See {@link android.net.wifi.WifiManager#stopRestrictingAutoJoinToSubscriptionId()}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.S)
    public void stopRestrictingAutoJoinToSubscriptionId() {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        mLog.info("stopRestrictingAutoJoinToSubscriptionId uid=%")
                .c(Binder.getCallingUid()).flush();
        mWifiThreadRunner.post(() ->
                mWifiConfigManager.stopRestrictingAutoJoinToSubscriptionId(),
                TAG + "#stopRestrictingAutoJoinToSubscriptionId");
    }

    /**
     * See {@link android.net.wifi.WifiManager#allowAutojoinGlobal(boolean)}
     * @param choice the OEM's choice to allow auto-join
     */
    @Override
    public void allowAutojoinGlobal(boolean choice, String packageName, Bundle extras) {
        int callingUid = Binder.getCallingUid();
        boolean isDeviceAdmin = mWifiPermissionsUtil.isAdmin(callingUid, packageName);
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(callingUid)
                && !isDeviceAdmin) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to set wifi global autojoin");
        }
        mLog.info("allowAutojoinGlobal=% uid=%").c(choice).c(callingUid).flush();
        if (!isDeviceAdmin && SdkLevel.isAtLeastS()) {
            // direct caller is not device admin but there exists and attribution chain. Check
            // if the original caller is device admin.
            AttributionSource as = extras.getParcelable(
                    WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE);
            if (as != null) {
                AttributionSource asLast = as;
                while (asLast.getNext() != null) {
                    asLast = asLast.getNext();
                }
                isDeviceAdmin = mWifiPermissionsUtil.isAdmin(asLast.getUid(),
                        asLast.getPackageName()) || mWifiPermissionsUtil.isLegacyDeviceAdmin(
                                asLast.getUid(), asLast.getPackageName());
            }
        }
        boolean finalIsDeviceAdmin = isDeviceAdmin;
        mWifiThreadRunner.post(() -> mWifiConnectivityManager.setAutoJoinEnabledExternal(choice,
                finalIsDeviceAdmin), TAG + "#allowAutojoinGlobal");
        mLastCallerInfoManager.put(WifiManager.API_AUTOJOIN_GLOBAL, Process.myTid(),
                callingUid, Binder.getCallingPid(), "<unknown>", choice);
    }

    /**
     * See {@link WifiManager#queryAutojoinGlobal(Executor, Consumer)}
     */
    @Override
    public void queryAutojoinGlobal(@NonNull IBooleanListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        int callingUid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(callingUid)
                && !isDeviceOrProfileOwner(callingUid, mContext.getOpPackageName())) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to get wifi global autojoin");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiConnectivityManager.getAutoJoinEnabledExternal());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#queryAutojoinGlobal");
    }

    /**
     * See {@link android.net.wifi.WifiManager#allowAutojoin(int, boolean)}
     * @param netId the integer that identifies the network configuration
     * @param choice the user's choice to allow auto-join
     */
    @Override
    public void allowAutojoin(int netId, boolean choice) {
        enforceNetworkSettingsPermission();

        int callingUid = Binder.getCallingUid();
        mLog.info("allowAutojoin=% uid=%").c(choice).c(callingUid).flush();
        mLastCallerInfoManager.put(WifiManager.API_ALLOW_AUTOJOIN, Process.myTid(),
                callingUid, Binder.getCallingPid(), "<unknown>", choice);
        mWifiThreadRunner.post(() -> {
            WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(netId);
            if (config == null) {
                return;
            }
            if (config.fromWifiNetworkSpecifier) {
                Log.e(TAG, "Auto-join configuration is not permitted for NetworkSpecifier "
                        + "connections: " + config);
                return;
            }
            if (config.isPasspoint() && !config.isEphemeral()) {
                Log.e(TAG,
                        "Auto-join configuration for a non-ephemeral Passpoint network should be "
                                + "configured using FQDN: "
                                + config);
                return;
            }
            // If the network is a suggestion, store the auto-join configure to the
            // WifiNetWorkSuggestionsManager.
            if (config.fromWifiNetworkSuggestion) {
                if (!mWifiNetworkSuggestionsManager
                        .allowNetworkSuggestionAutojoin(config, choice)) {
                    return;
                }
            }
            // even for Suggestion, modify the current ephemeral configuration so that
            // existing configuration auto-connection is updated correctly
            if (choice != config.allowAutojoin) {
                mWifiConfigManager.allowAutojoin(netId, choice);
                // do not log this metrics for passpoint networks again here since it's already
                // logged in PasspointManager.
                if (!config.isPasspoint()) {
                    mWifiMetrics.logUserActionEvent(choice
                            ? UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_ON
                            : UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_OFF, netId);
                }
            }
        }, TAG + "#allowAutojoin");
    }

    /**
     * See {@link android.net.wifi.WifiManager#allowAutojoinPasspoint(String, boolean)}
     * @param fqdn the FQDN that identifies the passpoint configuration
     * @param enableAutojoin true to enable auto-join, false to disable
     */
    @Override
    public void allowAutojoinPasspoint(String fqdn, boolean enableAutojoin) {
        enforceNetworkSettingsPermission();
        if (fqdn == null) {
            throw new IllegalArgumentException("FQDN cannot be null");
        }

        int callingUid = Binder.getCallingUid();
        mLog.info("allowAutojoinPasspoint=% uid=%").c(enableAutojoin).c(callingUid).flush();
        mWifiThreadRunner.post(
                () -> mPasspointManager.enableAutojoin(null, fqdn, enableAutojoin),
                TAG + "#allowAutojoinPasspoint");
    }

    /**
     * See {@link WifiManager#getBssidBlocklist(List, Executor, Consumer)}
     * @param ssids the list of ssids to get BSSID blocklist for.
     * @param listener returns the results
     */
    @Override
    public void getBssidBlocklist(@NonNull ParceledListSlice<WifiSsid> ssids,
            @NonNull IMacAddressListListener listener) {
        if (ssids == null || ssids.getList() == null) {
            throw new IllegalArgumentException("Null ssids");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Null listener");
        }
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid)) {
            throw new SecurityException("No permission to call getBssidBlocklist");
        }
        Set<String> ssidSet;
        if (!ssids.getList().isEmpty()) {
            ssidSet = new ArraySet<>();
            for (WifiSsid ssid : ssids.getList()) {
                ssidSet.add(ssid.toString());
            }
        } else {
            ssidSet = null;
        }
        mWifiThreadRunner.post(() -> {
            try {
                List<String> bssids = mWifiBlocklistMonitor.getBssidBlocklistForSsids(ssidSet);
                List<MacAddress> macAddresses = new ArrayList<>();
                for (String bssid : bssids) {
                    try {
                        macAddresses.add(MacAddress.fromString(bssid));
                    } catch (Exception e) {
                        Log.e(TAG, "getBssidBlocklist failed to convert MAC address: " + bssid);
                    }
                }
                listener.onResult(new ParceledListSlice(macAddresses));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getBssidBlocklist");
    }

    /**
     * See {@link android.net.wifi.WifiManager
     * #setMacRandomizationSettingPasspointEnabled(String, boolean)}
     * @param fqdn the FQDN that identifies the passpoint configuration
     * @param enable true to enable mac randomization, false to disable
     */
    @Override
    public void setMacRandomizationSettingPasspointEnabled(String fqdn, boolean enable) {
        enforceNetworkSettingsPermission();
        if (fqdn == null) {
            throw new IllegalArgumentException("FQDN cannot be null");
        }

        int callingUid = Binder.getCallingUid();
        mLog.info("setMacRandomizationSettingPasspointEnabled=% uid=%")
                .c(enable).c(callingUid).flush();
        mWifiThreadRunner.post(
                () -> mPasspointManager.enableMacRandomization(fqdn, enable),
                TAG + "#setMacRandomizationSettingPasspointEnabled");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setPasspointMeteredOverride(String, boolean)}
     * @param fqdn the FQDN that identifies the passpoint configuration
     * @param meteredOverride One of the values in {@link MeteredOverride}
     */
    @Override
    public void setPasspointMeteredOverride(String fqdn, int meteredOverride) {
        enforceNetworkSettingsPermission();
        if (fqdn == null) {
            throw new IllegalArgumentException("FQDN cannot be null");
        }

        int callingUid = Binder.getCallingUid();
        mLog.info("setPasspointMeteredOverride=% uid=%")
                .c(meteredOverride).c(callingUid).flush();
        mWifiThreadRunner.post(
                () -> mPasspointManager.setMeteredOverride(fqdn, meteredOverride),
                TAG + "#setPasspointMeteredOverride");
    }

    /**
     * Provides backward compatibility for apps using
     * {@link WifiManager#getConnectionInfo()}, {@link WifiManager#getDhcpInfo()} when a
     * secondary STA is created as a result of a request from their app (peer to peer
     * WifiNetworkSpecifier request or oem paid/private suggestion).
     */
    private ClientModeManager getClientModeManagerIfSecondaryCmmRequestedByCallerPresent(
            int callingUid, @NonNull String callingPackageName) {
        List<ConcreteClientModeManager> secondaryCmms = null;
        ActiveModeManager.ClientConnectivityRole roleSecondaryLocalOnly =
                ROLE_CLIENT_LOCAL_ONLY;
        ActiveModeManager.ClientInternetConnectivityRole roleSecondaryLongLived =
                ROLE_CLIENT_SECONDARY_LONG_LIVED;
        try {
            secondaryCmms = mActiveModeWarden.getClientModeManagersInRoles(
                    roleSecondaryLocalOnly, roleSecondaryLongLived);
        } catch (Exception e) {
            // print debug info and then rethrow the exception
            Log.e(TAG, "Failed to call getClientModeManagersInRoles on "
                    + roleSecondaryLocalOnly + ", and " + roleSecondaryLongLived);
            throw e;
        }

        for (ConcreteClientModeManager cmm : secondaryCmms) {
            WorkSource reqWs = new WorkSource(cmm.getRequestorWs());
            if (reqWs.size() > 1 && cmm.getRole() == roleSecondaryLocalOnly) {
                // Remove promoted settings WorkSource if present
                reqWs.remove(mFrameworkFacade.getSettingsWorkSource(mContext));
            }
            WorkSource withCaller = new WorkSource(reqWs);
            withCaller.add(new WorkSource(callingUid, callingPackageName));
            // If there are more than 1 secondary CMM for same app, return any one (should not
            // happen currently since we don't support 3 STA's concurrently).
            if (reqWs.equals(withCaller)) {
                mLog.info("getConnectionInfo providing secondary CMM info").flush();
                return cmm;
            }
        }
        // No secondary CMM's created for the app, return primary CMM.
        return mActiveModeWarden.getPrimaryClientModeManager();
    }

    /**
     * See {@link android.net.wifi.WifiManager#getConnectionInfo()}
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    @Override
    public WifiInfo getConnectionInfo(@NonNull String callingPackage,
            @Nullable String callingFeatureId) {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        if (mVerboseLoggingEnabled) {
            mLog.info("getConnectionInfo uid=%").c(uid).flush();
        }
        mWifiPermissionsUtil.checkPackage(uid, callingPackage);
        if (mActiveModeWarden.getWifiState() != WIFI_STATE_ENABLED) {
            return new WifiInfo();
        }
        WifiInfo wifiInfo;
        if (isCurrentRequestWsContainsCaller(uid, callingPackage)) {
            wifiInfo =
                    mWifiThreadRunner.call(
                            () ->
                                    getClientModeManagerIfSecondaryCmmRequestedByCallerPresent(
                                                    uid, callingPackage)
                                            .getConnectionInfo(),
                            new WifiInfo(), TAG + "#getConnectionInfo");
        } else {
            // If no caller
            wifiInfo = mActiveModeWarden.getConnectionInfo();
        }
        long ident = Binder.clearCallingIdentity();
        try {
            long redactions = wifiInfo.getApplicableRedactions();
            if (mWifiPermissionsUtil.checkLocalMacAddressPermission(uid)) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Clearing REDACT_FOR_LOCAL_MAC_ADDRESS for " + callingPackage
                            + "(uid=" + uid + ")");
                }
                redactions &= ~NetworkCapabilities.REDACT_FOR_LOCAL_MAC_ADDRESS;
            }
            if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                    || mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid)) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Clearing REDACT_FOR_NETWORK_SETTINGS for " + callingPackage
                            + "(uid=" + uid + ")");
                }
                redactions &= ~NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;
            }
            try {
                mWifiPermissionsUtil.enforceCanAccessScanResults(callingPackage, callingFeatureId,
                        uid, null);
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Clearing REDACT_FOR_ACCESS_FINE_LOCATION for " + callingPackage
                            + "(uid=" + uid + ")");
                }
                redactions &= ~NetworkCapabilities.REDACT_FOR_ACCESS_FINE_LOCATION;
            } catch (SecurityException ignored) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Keeping REDACT_FOR_ACCESS_FINE_LOCATION:" + ignored);
                }
            }
            return wifiInfo.makeCopy(redactions);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isCurrentRequestWsContainsCaller(int uid, String callingPackage) {
        Set<WorkSource> requestWs = mActiveModeWarden.getSecondaryRequestWs();
        for (WorkSource ws : requestWs) {
            WorkSource reqWs = new WorkSource(ws);
            if (reqWs.size() > 1) {
                // Remove promoted settings WorkSource if present
                reqWs.remove(mFrameworkFacade.getSettingsWorkSource(mContext));
            }
            WorkSource withCaller = new WorkSource(reqWs);
            withCaller.add(new WorkSource(uid, callingPackage));
            if (reqWs.equals(withCaller)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return the results of the most recent access point scan, in the form of
     * a list of {@link ScanResult} objects.
     * @return the list of results
     */
    @Override
    @Nullable public ParceledListSlice<ScanResult> getScanResults(String callingPackage,
            String callingFeatureId) {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        if (mVerboseLoggingEnabled) {
            mLog.info("getScanResults uid=%").c(uid).flush();
        }
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(callingPackage, callingFeatureId,
                    uid, null);
            List<ScanResult> scanResults = mWifiThreadRunner.call(
                    mScanRequestProxy::getScanResults, Collections.emptyList(),
                    TAG + "#getScanResults");
            if (scanResults.size() > 200) {
                Log.i(TAG, "too many scan results, may break binder transaction");
            }
            return new ParceledListSlice<>(scanResults);
        } catch (SecurityException e) {
            Log.w(TAG, "Permission violation - getScanResults not allowed for uid="
                    + uid + ", packageName=" + callingPackage + ", reason=" + e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * See {@link WifiManager#getChannelData(Executor, Consumer)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void getChannelData(@NonNull IListListener listener, String packageName,
            Bundle extras) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        mWifiPermissionsUtil.enforceNearbyDevicesPermission(
                extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE), false,
                TAG + " getChannelData");
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(getChannelDataInternal());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getChannelData");
    }

    private List<Bundle> getChannelDataInternal() {
        SparseIntArray dataSparseIntArray = new SparseIntArray();
        List<ScanResult> scanResults = mScanRequestProxy.getScanResults();
        if (scanResults != null) {
            for (ScanResult scanResultItem : scanResults) {
                if (scanResultItem.level >= CHANNEL_USAGE_WEAK_SCAN_RSSI_DBM) {
                    dataSparseIntArray.put(scanResultItem.frequency,
                            dataSparseIntArray.get(scanResultItem.frequency, 0) + 1);
                }
            }
        }

        List<Bundle> dataArray = new ArrayList<>();
        for (int i = 0; i < dataSparseIntArray.size(); i++) {
            Bundle dataBundle = new Bundle();
            dataBundle.putInt(CHANNEL_DATA_KEY_FREQUENCY_MHZ, dataSparseIntArray.keyAt(i));
            dataBundle.putInt(CHANNEL_DATA_KEY_NUM_AP, dataSparseIntArray.valueAt(i));
            dataArray.add(dataBundle);
        }
        return dataArray;
    }

    /**
     * Return the filtered ScanResults which may be authenticated by the suggested network
     * configurations.
     * @return The map of {@link WifiNetworkSuggestion} and the list of {@link ScanResult} which
     * may be authenticated by the corresponding network configuration.
     */
    @Override
    @NonNull
    public Map<WifiNetworkSuggestion, List<ScanResult>> getMatchingScanResults(
            @NonNull ParceledListSlice<WifiNetworkSuggestion> networkSuggestions,
            @Nullable ParceledListSlice<ScanResult> scanResults,
            String callingPackage, String callingFeatureId) {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        if (networkSuggestions == null || networkSuggestions.getList() == null) {
            throw new IllegalArgumentException("networkSuggestions must not be null.");
        }
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(callingPackage, callingFeatureId,
                    uid, null);

            return mWifiThreadRunner.call(
                    () -> {
                        if (scanResults == null
                                || !ScanResultUtil.validateScanResultList(scanResults.getList())) {
                            return mWifiNetworkSuggestionsManager.getMatchingScanResults(
                                    networkSuggestions.getList(),
                                    mScanRequestProxy.getScanResults());
                        } else {
                            return mWifiNetworkSuggestionsManager.getMatchingScanResults(
                                    networkSuggestions.getList(), scanResults.getList());
                        }
                    },
                    Collections.emptyMap(), TAG + "#getMatchingScanResults");
        } catch (SecurityException e) {
            Log.w(TAG, "Permission violation - getMatchingScanResults not allowed for uid="
                    + uid + ", packageName=" + callingPackage + ", reason + e");
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return Collections.emptyMap();
    }

    /**
     * Add or update a Passpoint configuration.
     *
     * @param config The Passpoint configuration to be added
     * @return true on success or false on failure
     */
    @Override
    public boolean addOrUpdatePasspointConfiguration(
            PasspointConfiguration config, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isTargetSdkLessThanROrPrivileged(
                packageName, Binder.getCallingPid(), callingUid)) {
            mLog.info("addOrUpdatePasspointConfiguration not allowed for uid=%")
                    .c(callingUid).flush();
            return false;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            if (SdkLevel.isAtLeastT() && mUserManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_ADD_WIFI_CONFIG,
                    UserHandle.getUserHandleForUid(callingUid))
                    && !mWifiPermissionsUtil.isAdmin(callingUid, packageName)) {
                mLog.info("addOrUpdatePasspointConfiguration only allowed for admin"
                        + "when the DISALLOW_ADD_WIFI_CONFIG user restriction is set").flush();
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        mLog.info("addorUpdatePasspointConfiguration uid=%").c(callingUid).flush();
        return mWifiThreadRunner.call(
                () -> {
                    boolean success = mPasspointManager.addOrUpdateProvider(config, callingUid,
                            packageName, false, true, false);
                    if (success && TextUtils.equals(CERT_INSTALLER_PKG, packageName)) {
                        int networkId = mActiveModeWarden.getConnectionInfo().getNetworkId();
                        WifiConfiguration currentConfig =
                                mWifiConfigManager.getConfiguredNetworkWithPassword(networkId);
                        if (currentConfig != null
                                && !currentConfig.getNetworkSelectionStatus()
                                    .hasNeverDetectedCaptivePortal()) {
                            mActiveModeWarden.getPrimaryClientModeManager().disconnect();
                        }
                    }
                    return success;
                }, false, TAG + "#addOrUpdatePasspointConfiguration");
    }

    /**
     * Remove the Passpoint configuration identified by its FQDN (Fully Qualified Domain Name).
     *
     * @param fqdn The FQDN of the Passpoint configuration to be removed
     * @return true on success or false on failure
     */
    @Override
    public boolean removePasspointConfiguration(String fqdn, String packageName) {
        mWifiPermissionsUtil.checkPackage(Binder.getCallingUid(), packageName);
        return removePasspointConfigurationInternal(fqdn, null);
    }

    /**
     * Remove a Passpoint profile based on either FQDN (multiple matching profiles) or a unique
     * identifier (one matching profile).
     *
     * @param fqdn The FQDN of the Passpoint configuration to be removed
     * @param uniqueId The unique identifier of the Passpoint configuration to be removed
     * @return true on success or false on failure
     */
    private boolean removePasspointConfigurationInternal(String fqdn, String uniqueId) {
        final int uid = Binder.getCallingUid();
        boolean privileged = false;
        if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                || mWifiPermissionsUtil.checkNetworkCarrierProvisioningPermission(uid)) {
            privileged = true;
        }
        mLog.info("removePasspointConfigurationInternal uid=%").c(Binder.getCallingUid()).flush();
        final boolean privilegedFinal = privileged;
        return mWifiThreadRunner.call(
                () -> mPasspointManager.removeProvider(uid, privilegedFinal, uniqueId, fqdn),
                false, TAG + "#removePasspointConfigurationInternal");
    }

    /**
     * Return the list of the installed Passpoint configurations.
     *
     * An empty list will be returned when no configuration is installed.
     * @param packageName String name of the calling package
     * @return A list of {@link PasspointConfiguration}.
     */
    @Override
    public ParceledListSlice<PasspointConfiguration> getPasspointConfigurations(
            String packageName) {
        final int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        boolean privileged = false;
        if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                || mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid)) {
            privileged = true;
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getPasspointConfigurations uid=%").c(Binder.getCallingUid()).flush();
        }
        final boolean privilegedFinal = privileged;
        return new ParceledListSlice<>(mWifiThreadRunner.call(
            () -> mPasspointManager.getProviderConfigs(uid, privilegedFinal),
            Collections.emptyList(), TAG + "#getPasspointConfigurations"));
    }

    /**
     * Query for a Hotspot 2.0 release 2 OSU icon
     * @param bssid The BSSID of the AP
     * @param fileName Icon file name
     */
    @Override
    public void queryPasspointIcon(long bssid, String fileName) {
        enforceAccessPermission();
        mLog.info("queryPasspointIcon uid=%").c(Binder.getCallingUid()).flush();
        mWifiThreadRunner.post(() -> {
            mActiveModeWarden.getPrimaryClientModeManager().syncQueryPasspointIcon(bssid, fileName);
        }, TAG + "#queryPasspointIcon");
    }

    /**
     * Match the currently associated network against the SP matching the given FQDN
     * @param fqdn FQDN of the SP
     * @return ordinal [HomeProvider, RoamingProvider, Incomplete, None, Declined]
     */
    @Override
    public int matchProviderWithCurrentNetwork(String fqdn) {
        mLog.info("matchProviderWithCurrentNetwork uid=%").c(Binder.getCallingUid()).flush();
        return 0;
    }

    /**
     * see {@link android.net.wifi.WifiManager#addDriverCountryCodeChangedListener(
     * WifiManager.OnDriverCountryCodeChangedListener)}
     *
     * @param listener country code listener to register
     * @param packageName Package name of the calling app
     * @param featureId The feature in the package
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void registerDriverCountryCodeChangedListener(@NonNull
            IOnWifiDriverCountryCodeChangedListener listener, @Nullable String packageName,
            @Nullable String featureId) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        // verify arguments
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        // Allow to register if caller owns location permission in the manifest.
        enforceLocationPermissionInManifest(uid, true /* isCoarseOnly */);
        if (mVerboseLoggingEnabled) {
            mLog.info("registerDriverCountryCodeChangedListener uid=%")
                    .c(Binder.getCallingUid()).flush();
        }

        // post operation to handler thread
        mWifiThreadRunner.post(() -> {
            mCountryCodeTracker.registerDriverCountryCodeChangedListener(listener,
                    new WifiPermissionsUtil.CallerIdentity(uid, pid, packageName, featureId));
            // Update the client about the current driver country code immediately
            // after registering if the client owns location permission and global location setting
            // is on.
            try {
                if (mWifiPermissionsUtil.checkCallersCoarseLocationPermission(
                        packageName, featureId, uid, null)) {
                    listener.onDriverCountryCodeChanged(mCountryCode.getCurrentDriverCountryCode());
                } else {
                    Log.i(TAG, "drop to notify to listener (maybe location off?) for"
                            + " DriverCountryCodeChangedListener, uid=" + uid);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "registerDriverCountryCodeChangedListener: remote exception -- " + e);
            }
        }, TAG + "#registerDriverCountryCodeChangedListener");
    }

    /**
     * see {@link android.net.wifi.WifiManager#removeDriverCountryCodeChangedListener(Executor,
     * WifiManager.OnDriverCountryCodeChangedListener)}
     *
     * @param listener country code listener to register
     *
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void unregisterDriverCountryCodeChangedListener(@NonNull
            IOnWifiDriverCountryCodeChangedListener listener) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        // verify arguments
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        int uid = Binder.getCallingUid();
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterDriverCountryCodeChangedListener uid=%")
                    .c(Binder.getCallingUid()).flush();
        }

        // post operation to handler thread
        mWifiThreadRunner.post(() ->
                mCountryCodeTracker.unregisterDriverCountryCodeChangedListener(listener),
                TAG + "#unregisterDriverCountryCodeChangedListener");
    }

     /**
     * Get the country code
     * @return Get the best choice country code for wifi, regardless of if it was set or
     * not.
     * Returns null when there is no country code available.
     */
    @Override
    public String getCountryCode(String packageName, String featureId) {
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkCallersCoarseLocationPermission(
                        packageName, featureId, uid, "getCountryCode")) {
            throw new SecurityException("Caller has no permission to get country code.");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getCountryCode uid=%").c(Binder.getCallingUid()).flush();
        }
        return mCountryCode.getCountryCode();
    }

    /**
     * Set the Wifi country code. This call will override the country code set by telephony.
     * @param countryCode A 2-Character alphanumeric country code.
     *
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void setOverrideCountryCode(@NonNull String countryCode) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_WIFI_COUNTRY_CODE, "WifiService");
        if (!WifiCountryCode.isValid(countryCode)) {
            throw new IllegalArgumentException("Country code must be a 2-Character alphanumeric"
                    + " code. But got countryCode " + countryCode
                    + " instead");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("setOverrideCountryCode uid=% countryCode=%")
                    .c(Binder.getCallingUid()).c(countryCode).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> mCountryCode.setOverrideCountryCode(countryCode),
                TAG + "#setOverrideCountryCode");
    }

    /**
     * Clear the country code previously set through setOverrideCountryCode method.
     *
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void clearOverrideCountryCode() {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_WIFI_COUNTRY_CODE, "WifiService");
        if (mVerboseLoggingEnabled) {
            mLog.info("clearCountryCode uid=%").c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> mCountryCode.clearOverrideCountryCode(),
                TAG + "#clearOverrideCountryCode");
    }

    /**
     * Change the default country code previously set from ro.boot.wificountrycode.
     * @param countryCode A 2-Character alphanumeric country code.
     *
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Override
    public void setDefaultCountryCode(@NonNull String countryCode) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_WIFI_COUNTRY_CODE, "WifiService");
        if (!WifiCountryCode.isValid(countryCode)) {
            throw new IllegalArgumentException("Country code must be a 2-Character alphanumeric"
                    + " code. But got countryCode " + countryCode
                    + " instead");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("setDefaultCountryCode uid=% countryCode=%")
                    .c(Binder.getCallingUid()).c(countryCode).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> mCountryCode.setDefaultCountryCode(countryCode),
                TAG + "#setDefaultCountryCode");
    }

    @Override
    public boolean is24GHzBandSupported() {
        if (mVerboseLoggingEnabled) {
            mLog.info("is24GHzBandSupported uid=%").c(Binder.getCallingUid()).flush();
        }

        return is24GhzBandSupportedInternal();
    }

    private boolean is24GhzBandSupportedInternal() {
        if (mResourceCache.getBoolean(R.bool.config_wifi24ghzSupport)) {
            return true;
        }
        return mActiveModeWarden.isBandSupportedForSta(WifiScanner.WIFI_BAND_24_GHZ);
    }


    @Override
    public boolean is5GHzBandSupported() {
        if (mVerboseLoggingEnabled) {
            mLog.info("is5GHzBandSupported uid=%").c(Binder.getCallingUid()).flush();
        }

        return is5GhzBandSupportedInternal();
    }

    private boolean is5GhzBandSupportedInternal() {
        if (mResourceCache.getBoolean(R.bool.config_wifi5ghzSupport)) {
            return true;
        }
        return mActiveModeWarden.isBandSupportedForSta(WifiScanner.WIFI_BAND_5_GHZ);
    }

    @Override
    public boolean is6GHzBandSupported() {
        if (mVerboseLoggingEnabled) {
            mLog.info("is6GHzBandSupported uid=%").c(Binder.getCallingUid()).flush();
        }

        return is6GhzBandSupportedInternal();
    }

    private boolean is6GhzBandSupportedInternal() {
        if (mResourceCache.getBoolean(R.bool.config_wifi6ghzSupport)) {
            return true;
        }
        return mActiveModeWarden.isBandSupportedForSta(WifiScanner.WIFI_BAND_6_GHZ);
    }

    @Override
    public boolean is60GHzBandSupported() {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }

        if (mVerboseLoggingEnabled) {
            mLog.info("is60GHzBandSupported uid=%").c(Binder.getCallingUid()).flush();
        }

        return is60GhzBandSupportedInternal();
    }

    private boolean is60GhzBandSupportedInternal() {
        if (mResourceCache.getBoolean(R.bool.config_wifi60ghzSupport)) {
            return true;
        }
        return mActiveModeWarden.isBandSupportedForSta(WifiScanner.WIFI_BAND_60_GHZ);
    }

    @Override
    public boolean isWifiStandardSupported(@WifiStandard int standard) {
        return mWifiThreadRunner.call(
                () -> mActiveModeWarden.getPrimaryClientModeManager().isWifiStandardSupported(
                        standard), false, TAG + "#isWifiStandardSupported");
    }

    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     * @deprecated
     */
    @Override
    public DhcpInfo getDhcpInfo(@NonNull String packageName) {
        enforceAccessPermission();
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (mVerboseLoggingEnabled) {
            mLog.info("getDhcpInfo uid=%").c(callingUid).flush();
        }
        DhcpResultsParcelable dhcpResults = mWifiThreadRunner.call(
                () -> getClientModeManagerIfSecondaryCmmRequestedByCallerPresent(
                        callingUid, packageName)
                        .syncGetDhcpResultsParcelable(), new DhcpResultsParcelable(),
                TAG + "#getDhcpInfo");

        DhcpInfo info = new DhcpInfo();

        if (dhcpResults.baseConfiguration != null) {
            if (dhcpResults.baseConfiguration.getIpAddress() != null
                    && dhcpResults.baseConfiguration.getIpAddress().getAddress()
                    instanceof Inet4Address) {
                info.ipAddress = Inet4AddressUtils.inet4AddressToIntHTL(
                        (Inet4Address) dhcpResults.baseConfiguration.getIpAddress().getAddress());
            }

            if (dhcpResults.baseConfiguration.getGateway() != null) {
                info.gateway = Inet4AddressUtils.inet4AddressToIntHTL(
                        (Inet4Address) dhcpResults.baseConfiguration.getGateway());
            }

            int dnsFound = 0;
            for (InetAddress dns : dhcpResults.baseConfiguration.getDnsServers()) {
                if (dns instanceof Inet4Address) {
                    if (dnsFound == 0) {
                        info.dns1 = Inet4AddressUtils.inet4AddressToIntHTL((Inet4Address) dns);
                    } else {
                        info.dns2 = Inet4AddressUtils.inet4AddressToIntHTL((Inet4Address) dns);
                    }
                    if (++dnsFound > 1) break;
                }
            }
        }
        String serverAddress = dhcpResults.serverAddress;
        if (serverAddress != null) {
            InetAddress serverInetAddress = InetAddresses.parseNumericAddress(serverAddress);
            info.serverAddress =
                    Inet4AddressUtils.inet4AddressToIntHTL((Inet4Address) serverInetAddress);
        }
        info.leaseDuration = dhcpResults.leaseDuration;

        return info;
    }

    /**
     * enable TDLS for the local NIC to remote NIC
     * The APPs don't know the remote MAC address to identify NIC though,
     * so we need to do additional work to find it from remote IP address
     */

    private static class TdlsTaskParams {
        String mRemoteIpAddress;
        boolean mEnable;
    }

    private class TdlsTask extends AsyncTask<TdlsTaskParams, Integer, Integer> {
        @Override
        protected Integer doInBackground(TdlsTaskParams... params) {

            // Retrieve parameters for the call
            TdlsTaskParams param = params[0];
            String remoteIpAddress = param.mRemoteIpAddress.trim();
            boolean enable = param.mEnable;

            // Get MAC address of Remote IP
            String macAddress = null;

            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
                // Skip over the line bearing column titles
                reader.readLine();

                String line;
                while ((line = reader.readLine()) != null) {
                    String[] tokens = line.split("[ ]+");
                    if (tokens.length < 6) {
                        continue;
                    }

                    // ARP column format is
                    // Address HWType HWAddress Flags Mask IFace
                    String ip = tokens[0];
                    String mac = tokens[3];

                    if (TextUtils.equals(remoteIpAddress, ip)) {
                        macAddress = mac;
                        break;
                    }
                }

                if (macAddress == null) {
                    Log.w(TAG, "Did not find remoteAddress {" + remoteIpAddress + "} in "
                            + "/proc/net/arp");
                } else {
                    enableTdlsWithMacAddress(macAddress, enable);
                }

            } catch (FileNotFoundException e) {
                Log.e(TAG, "Could not open /proc/net/arp to lookup mac address");
            } catch (IOException e) {
                Log.e(TAG, "Could not read /proc/net/arp to lookup mac address");
            }
            return 0;
        }
    }

    @Override
    public void enableTdls(String remoteAddress, boolean enable) {
        if (remoteAddress == null) {
          throw new IllegalArgumentException("remoteAddress cannot be null");
        }
        mLog.info("enableTdls uid=% enable=%").c(Binder.getCallingUid()).c(enable).flush();
        TdlsTaskParams params = new TdlsTaskParams();
        params.mRemoteIpAddress = remoteAddress;
        params.mEnable = enable;
        mLastCallerInfoManager.put(WifiManager.API_SET_TDLS_ENABLED, Process.myTid(),
                Binder.getCallingUid(), Binder.getCallingPid(), "<unknown>", enable);
        new TdlsTask().execute(params);
    }

    /**
     * See {@link WifiManager#setTdlsEnabled(InetAddress, boolean, Executor, Consumer)}
     */
    @Override
    public void enableTdlsWithRemoteIpAddress(String remoteAddress, boolean enable,
            @NonNull IBooleanListener listener) {
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress cannot be null");
        }
        if (listener == null) {
            throw new NullPointerException("listener should not be null");
        }

        mLog.info("enableTdlsWithRemoteIpAddress uid=% enable=%")
                .c(Binder.getCallingUid()).c(enable).flush();
        mLastCallerInfoManager.put(WifiManager.API_SET_TDLS_ENABLED,
                Process.myTid(), Binder.getCallingUid(), Binder.getCallingPid(), "<unknown>",
                enable);

        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(
                        mActiveModeWarden.getPrimaryClientModeManager()
                                .enableTdlsWithRemoteIpAddress(remoteAddress, enable));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#enableTdlsWithRemoteIpAddress");
    }

    @Override
    public void enableTdlsWithMacAddress(String remoteMacAddress, boolean enable) {
        mLog.info("enableTdlsWithMacAddress uid=% enable=%")
                .c(Binder.getCallingUid())
                .c(enable)
                .flush();
        if (remoteMacAddress == null) {
          throw new IllegalArgumentException("remoteMacAddress cannot be null");
        }
        mLastCallerInfoManager.put(WifiManager.API_SET_TDLS_ENABLED_WITH_MAC_ADDRESS,
                Process.myTid(), Binder.getCallingUid(), Binder.getCallingPid(), "<unknown>",
                enable);
        mWifiThreadRunner.post(() ->
                mActiveModeWarden.getPrimaryClientModeManager().enableTdls(
                        remoteMacAddress, enable), TAG + "#enableTdlsWithMacAddress");
    }

    /**
     * See {@link WifiManager#setTdlsEnabledWithMacAddress(String, boolean, Executor, Consumer)}
     */
    @Override
    public void enableTdlsWithRemoteMacAddress(String remoteMacAddress, boolean enable,
            @NonNull IBooleanListener listener) {
        if (remoteMacAddress == null) {
            throw new NullPointerException("remoteAddress cannot be null");
        }
        if (listener == null) {
            throw new NullPointerException("listener should not be null");
        }

        mLog.info("enableTdlsWithRemoteMacAddress uid=% enable=%")
                .c(Binder.getCallingUid()).c(enable).flush();
        mLastCallerInfoManager.put(
                WifiManager.API_SET_TDLS_ENABLED_WITH_MAC_ADDRESS,
                Process.myTid(), Binder.getCallingUid(), Binder.getCallingPid(), "<unknown>",
                enable);

        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(
                        mActiveModeWarden.getPrimaryClientModeManager()
                                .enableTdls(remoteMacAddress, enable));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#enableTdlsWithRemoteMacAddress");
    }

    /**
     * See {@link WifiManager#isTdlsOperationCurrentlyAvailable(Executor, Consumer)}
     */
    @Override
    public void isTdlsOperationCurrentlyAvailable(@NonNull IBooleanListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener should not be null");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(
                        mActiveModeWarden.getPrimaryClientModeManager()
                                .isTdlsOperationCurrentlyAvailable());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#isTdlsOperationCurrentlyAvailable");
    }

    /**
     *  See {@link WifiManager#getMaxSupportedConcurrentTdlsSessions(Executor, Consumer)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void getMaxSupportedConcurrentTdlsSessions(@NonNull IIntegerListener listener) {
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        if (listener == null) {
            throw new NullPointerException("listener should not be null");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(
                        mActiveModeWarden.getPrimaryClientModeManager()
                                .getMaxSupportedConcurrentTdlsSessions());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getMaxSupportedConcurrentTdlsSessions");
    }

    /**
     *  See {@link WifiManager#getNumberOfEnabledTdlsSessions(Executor, Consumer)}
     */
    @Override
    public void getNumberOfEnabledTdlsSessions(@NonNull IIntegerListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener should not be null");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(
                        mActiveModeWarden.getPrimaryClientModeManager()
                                .getNumberOfEnabledTdlsSessions());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getNumberOfEnabledTdlsSessions");
    }

    /**
     * Temporarily disable a network, should be trigger when user disconnect a network
     */
    @Override
    public void disableEphemeralNetwork(String network, String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiService");
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (!isPrivileged(Binder.getCallingPid(), callingUid)) {
            mLog.info("disableEphemeralNetwork not allowed for uid=%").c(callingUid).flush();
            return;
        }
        mLog.info("disableEphemeralNetwork uid=%").c(callingUid).flush();
        mWifiThreadRunner.post(() -> mWifiConfigManager.userTemporarilyDisabledNetwork(network,
                callingUid), TAG + "#disableEphemeralNetwork");
    }

    private void removeAppStateInternal(int uid, @NonNull String pkgName) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkgName;
        ai.uid = uid;
        mWifiConfigManager.removeNetworksForApp(ai);
        mScanRequestProxy.clearScanRequestTimestampsForApp(pkgName, uid);

        // Remove all suggestions from the package.
        mWifiNetworkSuggestionsManager.removeApp(pkgName);
        mWifiInjector.getWifiNetworkFactory().removeApp(pkgName);

        // Remove all Passpoint profiles from package.
        mWifiInjector.getPasspointManager().removePasspointProviderWithPackage(
                pkgName);
    }

    private void registerForBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final String action = intent.getAction();
                        if (action == null) {
                            return;
                        }
                        int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        Uri uri = intent.getData();
                        if (uid == -1 || uri == null) {
                            Log.e(TAG, "Uid or Uri is missing for action:" + action);
                            return;
                        }
                        String pkgName = uri.getSchemeSpecificPart();
                        PackageManager pm = context.getPackageManager();
                        PackageInfo packageInfo = null;
                        try {
                            packageInfo = pm.getPackageInfo(pkgName, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.w(TAG, "Couldn't get PackageInfo for package:" + pkgName);
                        }
                        // If app is updating or replacing, just ignore
                        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            return;
                        }
                        // If package is not removed or disabled, just ignore.
                        if (packageInfo != null
                                && packageInfo.applicationInfo != null
                                && packageInfo.applicationInfo.enabled) {
                            return;
                        }
                        Log.d(TAG, "Remove settings for package:" + pkgName);
                        removeAppStateInternal(uid, pkgName);
                    }
                },
                intentFilter,
                null,
                new Handler(mWifiHandlerThread.getLooper()));
    }

    private void registerForCarrierConfigChange() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final int subId = SubscriptionManager.getActiveDataSubscriptionId();
                        Log.d(TAG, "ACTION_CARRIER_CONFIG_CHANGED, active subId: " + subId);
                        // Tether mode only since carrier requirement only for tethered SoftAp.
                        mTetheredSoftApTracker
                                .updateSoftApCapabilityWhenCarrierConfigChanged(subId);
                        mActiveModeWarden.updateSoftApCapability(
                                mTetheredSoftApTracker.getSoftApCapability(),
                                WifiManager.IFACE_IP_MODE_TETHERED);
                    }
                },
                filter,
                null,
                new Handler(mWifiHandlerThread.getLooper()));

        WifiPhoneStateListener phoneStateListener = new WifiPhoneStateListener(
                mWifiHandlerThread.getLooper());

        mContext.getSystemService(TelephonyManager.class).listen(
                phoneStateListener, PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE);
    }

    @Override
    public int handleShellCommand(@NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        if (!mIsBootComplete) {
            Log.w(TAG, "Received shell command when boot is not complete!");
            return -1;
        }

        WifiShellCommand shellCommand =  mWifiInjector.makeWifiShellCommand(this);
        return shellCommand.exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                err.getFileDescriptor(), args);
    }


    private void updateWifiMetrics() {
        mWifiMetrics.updateSavedNetworks(mWifiConfigManager.getSavedNetworks(WIFI_UID));
        mActiveModeWarden.updateMetrics();
        mPasspointManager.updateMetrics();
        boolean isNonPersistentMacRandEnabled = mFrameworkFacade.getIntegerSetting(mContext,
                WifiConfigManager.NON_PERSISTENT_MAC_RANDOMIZATION_FEATURE_FORCE_ENABLE_FLAG, 0)
                == 1 ? true : false;
        mWifiMetrics.setNonPersistentMacRandomizationForceEnabled(isNonPersistentMacRandEnabled);
        mWifiMetrics.setIsScanningAlwaysEnabled(
                mSettingsStore.isScanAlwaysAvailableToggleEnabled());
        mWifiMetrics.setVerboseLoggingEnabled(mVerboseLoggingEnabled);
        mWifiMetrics.setWifiWakeEnabled(mWifiInjector.getWakeupController().isEnabled());
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        if (!mIsWifiServiceStarted) {
            pw.println("Wifi Service is not started. no dump available");
            return;
        }
        mWifiThreadRunner.run(() -> {
            String arg0 = args != null && args.length > 0 ? args[0] : null;
            if (WifiMetrics.PROTO_DUMP_ARG.equals(arg0)) {
                // WifiMetrics proto bytes were requested. Dump only these.
                updateWifiMetrics();
                mWifiMetrics.dump(fd, pw, args);
            } else if (IpClientUtil.DUMP_ARG.equals(arg0)) {
                // IpClient dump was requested. Pass it along and take no further action.
                String[] ipClientArgs = new String[args.length - 1];
                System.arraycopy(args, 1, ipClientArgs, 0, ipClientArgs.length);
                mActiveModeWarden.getPrimaryClientModeManager().dumpIpClient(fd, pw, ipClientArgs);
            } else if (WifiScoreReport.DUMP_ARG.equals(arg0)) {
                mActiveModeWarden.getPrimaryClientModeManager().dumpWifiScoreReport(fd, pw, args);
            } else if (WifiScoreCard.DUMP_ARG.equals(arg0)) {
                WifiScoreCard wifiScoreCard = mWifiInjector.getWifiScoreCard();
                String networkListBase64 = wifiScoreCard.getNetworkListBase64(true);
                pw.println(networkListBase64);
            } else {
                pw.println("Verbose logging is " + (mVerboseLoggingEnabled ? "on" : "off"));
                pw.println("mVerboseLoggingLevel " + mVerboseLoggingLevel);
                pw.println("Stay-awake conditions: " + mFacade.getIntegerSetting(
                        mContext, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0));
                pw.println("mInIdleMode " + mInIdleMode);
                pw.println("mScanPending " + mScanPending);
                pw.println("SupportedFeatures: " + getSupportedFeaturesString());
                pw.println("SettingsStore:");
                mSettingsStore.dump(fd, pw, args);
                mActiveModeWarden.dump(fd, pw, args);
                mMakeBeforeBreakManager.dump(fd, pw, args);
                pw.println();
                mWifiInjector.getInterfaceConflictManager().dump(fd, pw, args);
                pw.println();
                mWifiTrafficPoller.dump(fd, pw, args);
                pw.println();
                pw.println("Locks held:");
                mWifiLockManager.dump(pw);
                pw.println();
                mWifiMulticastLockManager.dump(pw);
                pw.println();
                WifiScoreCard wifiScoreCard = mWifiInjector.getWifiScoreCard();
                String networkListBase64 = wifiScoreCard.getNetworkListBase64(true);
                pw.println("WifiScoreCard:");
                pw.println(networkListBase64);

                updateWifiMetrics();
                mWifiMetrics.dump(fd, pw, args);

                pw.println();
                mWifiNetworkSuggestionsManager.dump(fd, pw, args);
                pw.println();
                mWifiBackupRestore.dump(fd, pw, args);
                pw.println();
                mBackupRestoreController.dump(fd, pw, args);
                pw.println();
                pw.println("ScoringParams: " + mWifiInjector.getScoringParams());
                pw.println();
                mSettingsConfigStore.dump(fd, pw, args);
                pw.println();
                mCountryCode.dump(fd, pw, args);
                mWifiInjector.getWifiNetworkFactory().dump(fd, pw, args);
                mWifiInjector.getUntrustedWifiNetworkFactory().dump(fd, pw, args);
                mWifiInjector.getOemWifiNetworkFactory().dump(fd, pw, args);
                mWifiInjector.getRestrictedWifiNetworkFactory().dump(fd, pw, args);
                mWifiInjector.getMultiInternetWifiNetworkFactory().dump(fd, pw, args);
                mWifiInjector.getSsidTranslator().dump(pw);
                pw.println("Wlan Wake Reasons:" + mWifiNative.getWlanWakeReasonCount());
                pw.println();
                mWifiConfigManager.dump(fd, pw, args);
                pw.println();
                pw.println("WifiApConfigStore config: " + mWifiApConfigStore.getApConfiguration());
                pw.println();
                mPasspointManager.dump(pw);
                mWifiInjector.getPasspointNetworkNominateHelper().dump(pw);
                pw.println();
                mWifiInjector.getWifiDiagnostics().captureBugReportData(
                        WifiDiagnostics.REPORT_REASON_USER_ACTION);
                mWifiInjector.getWifiDiagnostics().dump(fd, pw, args);
                mWifiConnectivityManager.dump(fd, pw, args);
                mWifiHealthMonitor.dump(fd, pw, args);
                mWifiScoreCard.dump(fd, pw, args);
                mWifiInjector.getWakeupController().dump(fd, pw, args);
                mWifiInjector.getWifiLastResortWatchdog().dump(fd, pw, args);
                mWifiInjector.getAdaptiveConnectivityEnabledSettingObserver().dump(fd, pw, args);
                mWifiInjector.getWifiGlobals().dump(fd, pw, args);
                mWifiInjector.getSarManager().dump(fd, pw, args);
                pw.println();
                mLastCallerInfoManager.dump(pw);
                pw.println();
                mWifiNative.dump(pw);
                pw.println();
                mWifiInjector.getWifiRoamingModeManager().dump(fd, pw, args);
                if (SdkLevel.isAtLeastV() && mWifiInjector.getWifiVoipDetector() != null) {
                    pw.println();
                    mWifiInjector.getWifiVoipDetector().dump(fd, pw, args);
                }
                pw.println();
                mResourceCache.dump(pw);
                if (mFeatureFlags.wepDisabledInApm()
                        && mWepNetworkUsageController != null) {
                    pw.println();
                    mWepNetworkUsageController.dump(fd, pw, args);
                }
                if (mScorerServiceConnection != null) {
                    pw.println("boundToExternalScorer successfully");
                } else {
                    pw.println("boundToExternalScorer=failure, lastScorerBindingState="
                            + mLastScorerBindingState);
                }
            }
        }, TAG + "#dump");
    }

    @Override
    public boolean acquireWifiLock(IBinder binder, int lockMode, String tag, WorkSource ws,
            @NonNull String packageName, Bundle extras) {
        mLog.info("acquireWifiLock uid=% lockMode=% packageName=%")
                .c(Binder.getCallingUid())
                .c(lockMode).c(getPackageName(extras)).flush();

        if (packageName == null) {
            throw new NullPointerException("Package name should not be null");
        }

        // Check on permission to make this call
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

        // If no UID is provided in worksource, use the calling UID
        WorkSource updatedWs = (ws == null || ws.isEmpty())
                ? new WorkSource(Binder.getCallingUid(), packageName) : ws;

        if (!WifiLockManager.isValidLockMode(lockMode)) {
            throw new IllegalArgumentException("lockMode =" + lockMode);
        }

        return mWifiThreadRunner.call(() ->
                mWifiLockManager.acquireWifiLock(lockMode, tag, binder, updatedWs), false,
                TAG + "#acquireWifiLock");
    }

    @Override
    public void updateWifiLockWorkSource(IBinder binder, WorkSource ws, String packageName,
            Bundle extras) {
        mLog.info("updateWifiLockWorkSource uid=% package name=%")
                .c(Binder.getCallingUid())
                .c(getPackageName(extras)).flush();

        // Check on permission to make this call
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.UPDATE_DEVICE_STATS, null);

        // If no UID is provided in worksource, use the calling UID
        WorkSource updatedWs = (ws == null || ws.isEmpty())
                ? new WorkSource(Binder.getCallingUid(), packageName) : ws;

        mWifiThreadRunner.run(() ->
                mWifiLockManager.updateWifiLockWorkSource(binder, updatedWs),
                TAG + "#updateWifiLockWorkSource");
    }

    @Override
    public boolean releaseWifiLock(IBinder binder) {
        mLog.info("releaseWifiLock uid=%").c(Binder.getCallingUid()).flush();

        // Check on permission to make this call
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

        return mWifiThreadRunner.call(() ->
                mWifiLockManager.releaseWifiLock(binder), false,
                TAG + "#releaseWifiLock");
    }

    @Override
    public void initializeMulticastFiltering() {
        enforceMulticastChangePermission();
        mLog.info("initializeMulticastFiltering uid=%").c(Binder.getCallingUid()).flush();
        mWifiMulticastLockManager.startFilteringMulticastPackets();
    }

    @Override
    public void acquireMulticastLock(IBinder binder, String lockTag, String attributionTag,
            String packageName) {
        enforceMulticastChangePermission();
        int uid = Binder.getCallingUid();
        mLog.info("acquireMulticastLock uid=% lockTag=%").c(uid).c(lockTag).flush();
        mWifiMulticastLockManager.acquireLock(uid, binder, lockTag, attributionTag, packageName);
    }

    @Override
    public void releaseMulticastLock(IBinder binder, String lockTag) {
        enforceMulticastChangePermission();
        int uid = Binder.getCallingUid();
        mLog.info("releaseMulticastLock uid=% lockTag=%").c(uid).c(lockTag).flush();
        mWifiMulticastLockManager.releaseLock(uid, binder, lockTag);
    }

    @Override
    public boolean isMulticastEnabled() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("isMulticastEnabled uid=%").c(Binder.getCallingUid()).flush();
        }
        return mWifiMulticastLockManager.isMulticastEnabled();
    }

    @Override
    public void enableVerboseLogging(int verbose) {
        enforceAccessPermission();
        if (!checkNetworkSettingsPermission(Binder.getCallingPid(), Binder.getCallingUid())
                && mContext.checkPermission(android.Manifest.permission.DUMP,
                Binder.getCallingPid(), Binder.getCallingUid()) != PERMISSION_GRANTED) {
            throw new SecurityException("Caller has neither NETWORK_SETTING nor dump permissions");
        }
        mLog.info("enableVerboseLogging uid=% verbose=%")
                .c(Binder.getCallingUid())
                .c(verbose).flush();
        boolean enabled = verbose == WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED
                || verbose == WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED_SHOW_KEY;
        mSettingsConfigStore.put(WIFI_VERBOSE_LOGGING_ENABLED, enabled);
        mSettingsConfigStore.put(
                WIFI_AWARE_VERBOSE_LOGGING_ENABLED,
                enabled || verbose == VERBOSE_LOGGING_LEVEL_WIFI_AWARE_ENABLED_ONLY);
        onVerboseLoggingStatusChanged(enabled);
        enableVerboseLoggingInternal(verbose);
    }

    private void onVerboseLoggingStatusChanged(boolean enabled) {
        synchronized (mRegisteredWifiLoggingStatusListeners) {
            int itemCount = mRegisteredWifiLoggingStatusListeners.beginBroadcast();
            try {
                for (int i = 0; i < itemCount; i++) {
                    try {
                        mRegisteredWifiLoggingStatusListeners
                                .getBroadcastItem(i)
                                .onStatusChanged(enabled);
                    } catch (RemoteException e) {
                        Log.e(TAG, "onVerboseLoggingStatusChanged: RemoteException -- ", e);
                    }
                }
            } finally {
                mRegisteredWifiLoggingStatusListeners.finishBroadcast();
            }
        }
    }

    private void updateVerboseLoggingEnabled() {
        final int verboseAlwaysOnLevel = mResourceCache.getInteger(
                R.integer.config_wifiVerboseLoggingAlwaysOnLevel);
        mVerboseLoggingEnabled = WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED == mVerboseLoggingLevel
                || WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED_SHOW_KEY == mVerboseLoggingLevel
                || mFrameworkFacade.isVerboseLoggingAlwaysOn(verboseAlwaysOnLevel,
                mBuildProperties);
    }

    private void enableVerboseLoggingInternal(int verboseLoggingLevel) {
        if (verboseLoggingLevel == WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED_SHOW_KEY
                && mBuildProperties.isUserBuild()) {
            throw new SecurityException(TAG + ": Not allowed for the user build.");
        }
        mVerboseLoggingLevel = verboseLoggingLevel;

        // Update wifi globals before sending the verbose logging change.
        mWifiThreadRunner.removeCallbacks(mAutoDisableShowKeyVerboseLoggingModeRunnable);
        mWifiGlobals.setVerboseLoggingLevel(verboseLoggingLevel);
        if (WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED_SHOW_KEY == mVerboseLoggingLevel) {
            mWifiThreadRunner.postDelayed(mAutoDisableShowKeyVerboseLoggingModeRunnable,
                    AUTO_DISABLE_SHOW_KEY_COUNTDOWN_MILLIS,
                    TAG + "#AutoDisableShowKeyVerboseLoggingMode");
        }
        updateVerboseLoggingEnabled();
        final boolean halVerboseEnabled =
                WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED == mVerboseLoggingLevel
                        || WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED_SHOW_KEY
                        == mVerboseLoggingLevel;
        mAfcManager.enableVerboseLogging(mVerboseLoggingEnabled);
        mActiveModeWarden.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiLockManager.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiMulticastLockManager.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiInjector.enableVerboseLogging(mVerboseLoggingEnabled, halVerboseEnabled);
        mWifiInjector.getSarManager().enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiThreadRunner.mVerboseLoggingEnabled = mVerboseLoggingEnabled;
        WifiScanner wifiScanner = mWifiInjector.getWifiScanner();
        if (wifiScanner != null) {
            wifiScanner.enableVerboseLogging(mVerboseLoggingEnabled);
        }
        ApConfigUtil.enableVerboseLogging(mVerboseLoggingEnabled);
        mApplicationQosPolicyRequestHandler.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiSettingsBackupRestore.enableVerboseLogging(mVerboseLoggingEnabled);
        mBackupRestoreController.enableVerboseLogging(mVerboseLoggingEnabled);
        if (SdkLevel.isAtLeastV() && mWifiInjector.getWifiVoipDetector() != null) {
            mWifiInjector.getWifiVoipDetector().enableVerboseLogging(mVerboseLoggingEnabled);
        }
        if (mFeatureFlags.wepDisabledInApm()
                && mWepNetworkUsageController != null) {
            mWepNetworkUsageController.enableVerboseLogging(mVerboseLoggingEnabled);
        }
    }

    @Override
    public int getVerboseLoggingLevel() {
        if (mVerboseLoggingEnabled) {
            mLog.info("getVerboseLoggingLevel uid=%").c(Binder.getCallingUid()).flush();
        }
        return mVerboseLoggingLevel;
    }

    private Runnable mAutoDisableShowKeyVerboseLoggingModeRunnable = new Runnable() {
        @Override
        public void run() {
            // If still enabled, fallback to the regular verbose logging mode.
            if (mVerboseLoggingEnabled) {
                enableVerboseLoggingInternal(WifiManager.VERBOSE_LOGGING_LEVEL_ENABLED);
            }
        }
    };

    @Override
    public void factoryReset(String packageName) {
        enforceNetworkSettingsPermission();
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return;
        }
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        mLog.info("factoryReset uid=%").c(callingUid).flush();
        long ident = Binder.clearCallingIdentity();
        try {
            if (mUserManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_NETWORK_RESET,
                    UserHandle.getUserHandleForUid(callingUid))) {
                return;
            }
            if (!mUserManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_CONFIG_TETHERING,
                    UserHandle.getUserHandleForUid(callingUid))) {
                // Turn mobile hotspot off
                stopSoftApInternal(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
            }
            if (mUserManager.hasUserRestrictionForUser(
                    UserManager.DISALLOW_CONFIG_WIFI,
                    UserHandle.getUserHandleForUid(callingUid))) {
                return;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        // Delete all Wifi SSIDs
        mWifiThreadRunner.run(() -> {
            List<WifiConfiguration> networks = mWifiConfigManager
                    .getSavedNetworks(WIFI_UID);
            EventLog.writeEvent(0x534e4554, "231985227", -1,
                    "Remove certs for factory reset");
            for (WifiConfiguration network : networks) {
                if (network.isEnterprise()) {
                    mWifiInjector.getWifiKeyStore().removeKeys(network.enterpriseConfig, true);
                }
                mWifiConfigManager.removeNetwork(network.networkId, callingUid, packageName);
            }
        }, TAG + "#factoryReset1");
        // Delete all Passpoint configurations
        List<PasspointConfiguration> configs = mWifiThreadRunner.call(
                () -> mPasspointManager.getProviderConfigs(WIFI_UID /* ignored */, true),
                Collections.emptyList(), TAG + "#factoryReset2");
        for (PasspointConfiguration config : configs) {
            removePasspointConfigurationInternal(null, config.getUniqueId());
        }
        mWifiThreadRunner.post(
                () -> {
                    // Reset SoftApConfiguration to default configuration
                    mWifiApConfigStore.setApConfiguration(null);
                    mPasspointManager.clearAnqpRequestsAndFlushCache();
                    mWifiConfigManager.clearUserTemporarilyDisabledList();
                    mWifiConfigManager.removeAllEphemeralOrPasspointConfiguredNetworks();
                    mWifiInjector.getWifiNetworkFactory().clear();
                    mWifiNetworkSuggestionsManager.clear();
                    mWifiInjector.getWifiScoreCard().clear();
                    mWifiHealthMonitor.clear();
                    mWifiCarrierInfoManager.clear();
                    notifyFactoryReset();
                    mContext.resetResourceCache();
                }, TAG + "#factoryReset3");
    }

    /**
     * Notify the Factory Reset Event to application who may installed wifi configurations.
     */
    private void notifyFactoryReset() {
        Intent intent = new Intent(WifiManager.ACTION_NETWORK_SETTINGS_RESET);

        // Retrieve list of broadcast receivers for this broadcast & send them directed broadcasts
        // to wake them up (if they're in background).
        List<ResolveInfo> resolveInfos =
                mContext.getPackageManager().queryBroadcastReceiversAsUser(
                        intent, 0,
                        UserHandle.of(mWifiInjector.getWifiPermissionsWrapper().getCurrentUser()));
        if (resolveInfos == null || resolveInfos.isEmpty()) return; // No need to send broadcast.

        for (ResolveInfo resolveInfo : resolveInfos) {
            Intent intentToSend = new Intent(intent);
            intentToSend.setComponent(new ComponentName(
                    resolveInfo.activityInfo.applicationInfo.packageName,
                    resolveInfo.activityInfo.name));
            mContext.sendBroadcastAsUser(intentToSend, UserHandle.CURRENT,
                    android.Manifest.permission.NETWORK_CARRIER_PROVISIONING);
        }
    }

    @Override
    public Network getCurrentNetwork() {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getCurrentNetwork uid=%").c(Binder.getCallingUid()).flush();
        }
        return mActiveModeWarden.getCurrentNetwork();
    }

    public static String toHexString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\'').append(s).append('\'');
        for (int n = 0; n < s.length(); n++) {
            sb.append(String.format(" %02x", s.charAt(n) & 0xffff));
        }
        return sb.toString();
    }

    /**
     * Retrieve the data to be backed to save the current state.
     *
     * The data includes:
     * 1. Wifi Settings (WifiSettingsConfigStore)
     * 2. WifiConfiguration/IpConfiguration (original backup by retrieveBackupData)
     * 3. SoftApConfiguration (original backup by retrieveSoftApBackupData)
     *
     * @param listener the listener to be used to receive backup data.
     */
    @Override
    public void retrieveWifiBackupData(IByteArrayListener listener) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        enforceNetworkSettingsPermission();
        mLog.info("retrieveWifiBackupData uid=%").c(Binder.getCallingUid()).flush();
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mBackupRestoreController.retrieveBackupData());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#retrieveWifiBackupData");
    }

    /**
     * Restore state from the backed up data.
     *
     * The api will guarantee the device would not damage when adding new XML tag.
     *
     * @param data Raw byte stream of the backed up data.
     */
    @Override
    public void restoreWifiBackupData(byte[] data) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        enforceNetworkSettingsPermission();
        mWifiThreadRunner.post(() -> {
            mLog.info("restoreWifiBackupData uid=%").c(Binder.getCallingUid()).flush();
            mBackupRestoreController.parserBackupDataAndDispatch(data);
        }, TAG + "#restoreWifiBackupData");
    }

    /**
     * Retrieve the data to be backed to save the current state.
     *
     * @return  Raw byte stream of the data to be backed up.
     */
    @Override
    public byte[] retrieveBackupData() {
        enforceNetworkSettingsPermission();
        mLog.info("retrieveBackupData uid=%").c(Binder.getCallingUid()).flush();
        Log.d(TAG, "Retrieving backup data");
        List<WifiConfiguration> wifiConfigurations = mWifiThreadRunner.call(
                () -> mWifiConfigManager.getConfiguredNetworksWithPasswords(), null,
                TAG + "#retrieveBackupData");
        byte[] backupData =
                mWifiBackupRestore.retrieveBackupDataFromConfigurations(wifiConfigurations);
        Log.d(TAG, "Retrieved backup data");
        return backupData;
    }

    /**
     * Helper method to restore networks retrieved from backup data.
     *
     * @param configurations list of WifiConfiguration objects parsed from the backup data.
     */
    @VisibleForTesting
    void restoreNetworks(List<WifiConfiguration> configurations) {
        if (configurations == null) {
            Log.w(TAG, "No wifi configuration to restore.");
            return;
        }
        int callingUid = Binder.getCallingUid();
        if (configurations.isEmpty()) return;
        mWifiThreadRunner.post(() -> {
            boolean notOverrideExisting = CompatChanges
                    .isChangeEnabled(NOT_OVERRIDE_EXISTING_NETWORKS_ON_RESTORE, callingUid);
            int networkId;
            for (WifiConfiguration configuration : configurations) {
                if (notOverrideExisting) {
                    networkId = mWifiConfigManager.addNetwork(configuration, callingUid)
                            .getNetworkId();
                } else {
                    networkId = mWifiConfigManager.addOrUpdateNetwork(configuration, callingUid)
                            .getNetworkId();
                }
                if (networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                    Log.e(TAG, "Restore network failed: "
                            + configuration.getProfileKey() + ", network might already exist in the"
                            + " database");
                } else {
                    // Enable all networks restored.
                    mWifiConfigManager.enableNetwork(networkId, false, callingUid, null);
                    // Restore auto-join param.
                    mWifiConfigManager.allowAutojoin(networkId, configuration.allowAutojoin);
                }
            }
        }, TAG + "#restoreNetworks");
    }

    /**
     * Restore state from the backed up data.
     *
     * @param data Raw byte stream of the backed up data.
     */
    @Override
    public void restoreBackupData(byte[] data) {
        enforceNetworkSettingsPermission();
        mLog.info("restoreBackupData uid=%").c(Binder.getCallingUid()).flush();
        Log.d(TAG, "Restoring backup data");
        restoreNetworks(mWifiBackupRestore.retrieveConfigurationsFromBackupData(data));
    }

    /**
     * Retrieve the soft ap config data to be backed to save current config data.
     *
     * @return  Raw byte stream of the data to be backed up.
     */
    @Override
    public byte[] retrieveSoftApBackupData() {
        enforceNetworkSettingsPermission();
        mLog.info("retrieveSoftApBackupData uid=%").c(Binder.getCallingUid()).flush();
        SoftApConfiguration config = mWifiThreadRunner.call(mWifiApConfigStore::getApConfiguration,
                new SoftApConfiguration.Builder().build(), TAG + "#retrieveSoftApBackupData");
        byte[] backupData =
                mSoftApBackupRestore.retrieveBackupDataFromSoftApConfiguration(config);
        Log.d(TAG, "Retrieved soft ap backup data");
        return backupData;
    }

    /**
     * Restore soft ap config from the backed up data.
     *
     * @param data Raw byte stream of the backed up data.
     * @return restored SoftApConfiguration or Null if data is invalid.
     */
    @Override
    public SoftApConfiguration restoreSoftApBackupData(byte[] data) {
        enforceNetworkSettingsPermission();
        mLog.info("restoreSoftApBackupData uid=%").c(Binder.getCallingUid()).flush();
        SoftApConfiguration softApConfig =
                mSoftApBackupRestore.retrieveSoftApConfigurationFromBackupData(data);
        if (softApConfig != null) {
            mWifiApConfigStore.setApConfiguration(
                    mWifiApConfigStore.resetToDefaultForUnsupportedConfig(
                            mWifiApConfigStore.upgradeSoftApConfiguration(softApConfig)));
            Log.d(TAG, "Restored soft ap backup data");
        }
        return softApConfig;
    }

    /**
     * Restore state from the older supplicant back up data. The old backup data was essentially a
     * backup of wpa_supplicant.conf & ipconfig.txt file.
     *
     * @param supplicantData Raw byte stream of wpa_supplicant.conf
     * @param ipConfigData Raw byte stream of ipconfig.txt
     */
    @Override
    public void restoreSupplicantBackupData(byte[] supplicantData, byte[] ipConfigData) {
        enforceNetworkSettingsPermission();
        mLog.trace("restoreSupplicantBackupData uid=%").c(Binder.getCallingUid()).flush();
        Log.d(TAG, "Restoring supplicant backup data");
        restoreNetworks(mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                supplicantData, ipConfigData));
    }

    /**
     * Starts subscription provisioning with a provider.
     *
     * @param provider {@link OsuProvider} the provider to provision with
     * @param callback {@link IProvisioningCallback} the callback object to inform status
     */
    @Override
    public void startSubscriptionProvisioning(OsuProvider provider,
            IProvisioningCallback callback) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        final int uid = Binder.getCallingUid();
        mLog.trace("startSubscriptionProvisioning uid=%").c(uid).flush();
        if (getPrimaryClientModeManagerBlockingThreadSafe()
                .syncStartSubscriptionProvisioning(uid, provider, callback)) {
            mLog.trace("Subscription provisioning started with %")
                    .c(provider.toString()).flush();
        }
    }

    /**
     * See
     * {@link WifiManager#registerTrafficStateCallback(Executor, WifiManager.TrafficStateCallback)}
     *
     * @param callback Traffic State callback to register
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void registerTrafficStateCallback(ITrafficStateCallback callback) {
        // verify arguments
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("registerTrafficStateCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> mWifiTrafficPoller.addCallback(callback),
                TAG + "#registerTrafficStateCallback");
    }

    /**
     * see {@link android.net.wifi.WifiManager#unregisterTrafficStateCallback(
     * WifiManager.TrafficStateCallback)}
     *
     * @param callback Traffic State callback to unregister
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     */
    @Override
    public void unregisterTrafficStateCallback(ITrafficStateCallback callback) {
        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterTrafficStateCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> mWifiTrafficPoller.removeCallback(callback),
                TAG + "#unregisterTrafficStateCallback");
    }

    protected String getSupportedFeaturesString() {
        return FeatureBitsetUtils.formatSupportedFeatures(getSupportedFeaturesInternal());
    }

    private BitSet getSupportedFeaturesInternal() {
        return mActiveModeWarden.getSupportedFeatureSet();
    }

    /**
     * See
     * {@link WifiManager#registerNetworkRequestMatchCallback(
     * Executor, WifiManager.NetworkRequestMatchCallback)}
     *
     * @param callback Network Request Match callback to register
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void registerNetworkRequestMatchCallback(INetworkRequestMatchCallback callback) {
        // verify arguments
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("registerNetworkRequestMatchCallback uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() ->
                mWifiInjector.getWifiNetworkFactory().addCallback(callback),
                TAG + "#registerNetworkRequestMatchCallback");
    }

    /**
     * see {@link android.net.wifi.WifiManager#unregisterNetworkRequestMatchCallback(
     * WifiManager.NetworkRequestMatchCallback)}
     *
     * @param callback Network Request Match callback to unregister
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     */
    @Override
    public void unregisterNetworkRequestMatchCallback(INetworkRequestMatchCallback callback) {
        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterNetworkRequestMatchCallback uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() ->
                mWifiInjector.getWifiNetworkFactory().removeCallback(callback),
                TAG + "#unregisterNetworkRequestMatchCallback");
    }

    /**
     * See {@link android.net.wifi.WifiManager#addNetworkSuggestions(List)}
     *
     * @param networkSuggestions List of network suggestions to be added.
     * @param callingPackageName Package Name of the app adding the suggestions.
     * @param callingFeatureId Feature in the calling package
     * @throws SecurityException if the caller does not have permission.
     * @return One of status codes from {@link WifiManager.NetworkSuggestionsStatusCode}.
     */
    @Override
    public int addNetworkSuggestions(
            ParceledListSlice<WifiNetworkSuggestion> networkSuggestions, String callingPackageName,
            String callingFeatureId) {
        if (enforceChangePermission(callingPackageName) != MODE_ALLOWED) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED;
        }
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        long ident = Binder.clearCallingIdentity();
        try {
            if (SdkLevel.isAtLeastT()) {
                boolean isUserRestrictionSet = mUserManager.hasUserRestrictionForUser(
                        UserManager.DISALLOW_ADD_WIFI_CONFIG,
                        UserHandle.getUserHandleForUid(callingUid));
                boolean isCarrierApp = mWifiInjector.makeTelephonyManager()
                        .checkCarrierPrivilegesForPackageAnyPhone(callingPackageName)
                        == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
                boolean hasPermission = !isUserRestrictionSet
                        || isCarrierApp
                        || isPrivileged(callingPid, callingUid)
                        || mWifiPermissionsUtil.isSystem(callingPackageName, callingUid)
                        || mWifiPermissionsUtil.isAdmin(callingUid, callingPackageName);
                if (!hasPermission) {
                    return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (mVerboseLoggingEnabled) {
            mLog.info("addNetworkSuggestions uid=%").c(callingUid).flush();
        }
        if (networkSuggestions == null) {
            return STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        }

        int success = mWifiThreadRunner.call(() -> mWifiNetworkSuggestionsManager.add(
                networkSuggestions.getList(), callingUid, callingPackageName, callingFeatureId),
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL,
                TAG + "#addNetworkSuggestions");
        if (success != STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.e(TAG, "Failed to add network suggestions");
        }
        return success;
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeNetworkSuggestions(List)}
     *
     * @param networkSuggestions List of network suggestions to be removed.
     * @param callingPackageName Package Name of the app removing the suggestions.
     * @throws SecurityException if the caller does not have permission.
     * @return One of status codes from {@link WifiManager.NetworkSuggestionsStatusCode}.
     */
    @Override
    public int removeNetworkSuggestions(
            ParceledListSlice<WifiNetworkSuggestion> networkSuggestions, String callingPackageName,
            @WifiManager.ActionAfterRemovingSuggestion int action) {
        if (enforceChangePermission(callingPackageName) != MODE_ALLOWED) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED;
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("removeNetworkSuggestions uid=%").c(Binder.getCallingUid()).flush();
        }
        if (action != WifiManager.ACTION_REMOVE_SUGGESTION_DISCONNECT
                && action != WifiManager.ACTION_REMOVE_SUGGESTION_LINGER) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }
        int callingUid = Binder.getCallingUid();

        if (networkSuggestions == null) {
            return STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        }
        int success = mWifiThreadRunner.call(() -> mWifiNetworkSuggestionsManager.remove(
                networkSuggestions.getList(), callingUid, callingPackageName,
                action), WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL,
                TAG + "#removeNetworkSuggestions");
        if (success != STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.e(TAG, "Failed to remove network suggestions");
        }
        return success;
    }

    /**
     * See {@link android.net.wifi.WifiManager#getNetworkSuggestions()}
     * @param callingPackageName Package Name of the app getting the suggestions.
     * @return a list of network suggestions suggested by this app
     */
    @Override
    public ParceledListSlice<WifiNetworkSuggestion> getNetworkSuggestions(
            String callingPackageName) {
        int callingUid = Binder.getCallingUid();
        mAppOps.checkPackage(callingUid, callingPackageName);
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getNetworkSuggestionList uid=%").c(Binder.getCallingUid()).flush();
        }
        return new ParceledListSlice<>(mWifiThreadRunner.call(() ->
                mWifiNetworkSuggestionsManager.get(callingPackageName, callingUid),
                Collections.emptyList(), TAG + "#getNetworkSuggestions"));
    }

    /**
     * Gets the factory Wi-Fi MAC addresses.
     * @throws SecurityException if the caller does not have permission.
     * @return Array of String representing Wi-Fi MAC addresses, or empty array if failed.
     */
    @Override
    public String[] getFactoryMacAddresses() {
        final int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException("App not allowed to get Wi-Fi factory MAC address "
                    + "(uid = " + uid + ")");
        }
        // Check the ConfigStore cache first
        if (mWifiGlobals.isSaveFactoryMacToConfigStoreEnabled()) {
            String factoryMacAddressStr = mSettingsConfigStore.get(WIFI_STA_FACTORY_MAC_ADDRESS);
            if (factoryMacAddressStr != null) return new String[] {factoryMacAddressStr};
        }
        String result = mWifiThreadRunner.call(
                () -> mActiveModeWarden.getPrimaryClientModeManager().getFactoryMacAddress(),
                null, TAG + "#getFactoryMacAddresses");
        // result can be empty array if either: WifiThreadRunner.call() timed out, or
        // ClientModeImpl.getFactoryMacAddress() returned null.
        // In this particular instance, we don't differentiate the two types of nulls.
        if (result == null) {
            return new String[0];
        }
        return new String[]{result};
    }

    /**
     * Sets the current device mobility state.
     * @param state the new device mobility state
     */
    @Override
    public void setDeviceMobilityState(@DeviceMobilityState int state) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WIFI_SET_DEVICE_MOBILITY_STATE, "WifiService");

        if (mVerboseLoggingEnabled) {
            mLog.info("setDeviceMobilityState uid=% state=%")
                    .c(Binder.getCallingUid())
                    .c(state)
                    .flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> {
            mWifiConnectivityManager.setDeviceMobilityState(state);
            mWifiHealthMonitor.setDeviceMobilityState(state);
            mWifiDataStall.setDeviceMobilityState(state);
            mActiveModeWarden.setDeviceMobilityState(state);
        }, TAG + "#setDeviceMobilityState");
    }

    /**
     * Proxy for the final native call of the parent class. Enables mocking of
     * the function.
     */
    public int getMockableCallingUid() {
        return getCallingUid();
    }

    /**
     * Start DPP in Configurator-Initiator role. The current device will initiate DPP bootstrapping
     * with a peer, and send the SSID and password of the selected network.
     *
     * @param binder Caller's binder context
     * @param packageName Package name of the calling app
     * @param enrolleeUri URI of the Enrollee obtained externally (e.g. QR code scanning)
     * @param selectedNetworkId Selected network ID to be sent to the peer
     * @param netRole The network role of the enrollee
     * @param callback Callback for status updates
     */
    @Override
    public void startDppAsConfiguratorInitiator(IBinder binder, @NonNull String packageName,
            String enrolleeUri, int selectedNetworkId, int netRole, IDppCallback callback) {
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (TextUtils.isEmpty(enrolleeUri)) {
            throw new IllegalArgumentException("Enrollee URI must not be null or empty");
        }
        if (selectedNetworkId < 0) {
            throw new IllegalArgumentException("Selected network ID invalid");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        final int uid = getMockableCallingUid();

        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        mAppOps.checkPackage(callingUid, packageName);
        if (!isSettingsOrSuw(Binder.getCallingPid(), callingUid)) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        // Stop MBB (if in progress) when DPP is initiated. Otherwise, DPP operation will fail
        // when the previous primary iface is removed after MBB completion.
        mWifiThreadRunner.post(() ->
                mMakeBeforeBreakManager.stopAllSecondaryTransientClientModeManagers(() ->
                        mDppManager.startDppAsConfiguratorInitiator(
                                uid, packageName,
                                mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName(),
                                binder, enrolleeUri, selectedNetworkId, netRole, callback)),
                TAG + "#startDppAsConfiguratorInitiator");
    }

    /**
     * Start DPP in Enrollee-Initiator role. The current device will initiate DPP bootstrapping
     * with a peer, and receive the SSID and password from the peer configurator.
     *
     * @param binder Caller's binder context
     * @param configuratorUri URI of the Configurator obtained externally (e.g. QR code scanning)
     * @param callback Callback for status updates
     */
    @Override
    public void startDppAsEnrolleeInitiator(IBinder binder, String configuratorUri,
            IDppCallback callback) {
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (TextUtils.isEmpty(configuratorUri)) {
            throw new IllegalArgumentException("Enrollee URI must not be null or empty");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        final int uid = getMockableCallingUid();

        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        // Stop MBB (if in progress) when DPP is initiated. Otherwise, DPP operation will fail
        // when the previous primary iface is removed after MBB completion.
        mWifiThreadRunner.post(() ->
                mMakeBeforeBreakManager.stopAllSecondaryTransientClientModeManagers(() ->
                        mDppManager.startDppAsEnrolleeInitiator(uid,
                                mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName(),
                                binder, configuratorUri, callback)),
                TAG + "#startDppAsEnrolleeInitiator");
    }

    /**
     * Start DPP in Enrollee-Responder role. The current device will generate the
     * bootstrap code and wait for the peer device to start the DPP authentication process.
     *
     * @param binder Caller's binder context
     * @param deviceInfo Device specific info to display in QR code(e.g. Easy_connect_demo)
     * @param curve Elliptic curve cryptography type used to generate DPP public/private key pair.
     * @param callback Callback for status updates
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.S)
    public void startDppAsEnrolleeResponder(IBinder binder, @Nullable String deviceInfo,
            @WifiManager.EasyConnectCryptographyCurve int curve, IDppCallback callback) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException();
        }
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        final int uid = getMockableCallingUid();

        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        if (deviceInfo != null) {
            int deviceInfoLen = deviceInfo.length();
            if (deviceInfoLen > WifiManager.getEasyConnectMaxAllowedResponderDeviceInfoLength()) {
                throw new IllegalArgumentException("Device info length: " + deviceInfoLen
                        + " must be less than "
                        + WifiManager.getEasyConnectMaxAllowedResponderDeviceInfoLength());
            }
            char c;
            for (int i = 0; i < deviceInfoLen; i++) {
                c = deviceInfo.charAt(i);
                if (c < '!' || c > '~' || c == ';') {
                    throw new IllegalArgumentException("Allowed Range of ASCII characters in"
                            + "deviceInfo - %x20-7E; semicolon and space are not allowed!"
                            + "Found c: " + c);
                }
            }
        }

        // Stop MBB (if in progress) when DPP is initiated. Otherwise, DPP operation will fail
        // when the previous primary iface is removed after MBB completion.
        mWifiThreadRunner.post(() ->
                mMakeBeforeBreakManager.stopAllSecondaryTransientClientModeManagers(() ->
                        mDppManager.startDppAsEnrolleeResponder(uid,
                                mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName(),
                                binder, deviceInfo, curve, callback)),
                TAG + "#startDppAsEnrolleeResponder");
    }

    /**
     * Stop or abort a current DPP session.
     */
    @Override
    public void stopDppSession() throws RemoteException {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        final int uid = getMockableCallingUid();

        mWifiThreadRunner.post(() -> mDppManager.stopDppSession(uid),
                TAG + "#stopDppSession");
    }

    /**
     * see {@link android.net.wifi.WifiManager#addWifiVerboseLoggingStatusChangedListener(Executor,
     * WifiManager.WifiVerboseLoggingStatusChangedListener)}
     *
     * @param listener IWifiVerboseLoggingStatusChangedListener listener to add
     *
     * @throws SecurityException if the caller does not have permission to add a listener.
     * @throws IllegalArgumentException if the argument is null.
     */
    @Override
    public void addWifiVerboseLoggingStatusChangedListener(
            IWifiVerboseLoggingStatusChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        enforceAccessPermission();
        mRegisteredWifiLoggingStatusListeners.register(listener);
    }

    /**
     * see {@link android.net.wifi.WifiManager#unregisterWifiVerboseLoggingStatusCallback
     * (WifiManager.WifiVerboseLoggingStatusCallback)}
     *
     * @param listener the listener to be removed.
     *
     * @throws SecurityException if the caller does not have permission to add a listener.
     * @throws IllegalArgumentException if the argument is null.
     */
    @Override
    public void removeWifiVerboseLoggingStatusChangedListener(
            IWifiVerboseLoggingStatusChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        enforceAccessPermission();
        mRegisteredWifiLoggingStatusListeners.unregister(listener);
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOnWifiUsabilityStatsListener(Executor,
     * WifiManager.OnWifiUsabilityStatsListener)}
     *
     * @param listener WifiUsabilityStatsEntry listener to add
     *
     * @throws SecurityException if the caller does not have permission to add a listener
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void addOnWifiUsabilityStatsListener(IOnWifiUsabilityStatsListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");
        if (mVerboseLoggingEnabled) {
            mLog.info("addOnWifiUsabilityStatsListener uid=%")
                .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() ->
                mWifiMetrics.addOnWifiUsabilityListener(listener),
                TAG + "#addOnWifiUsabilityStatsListener");
    }

    /**
     * see {@link android.net.wifi.WifiManager#removeOnWifiUsabilityStatsListener
     * (WifiManager.OnWifiUsabilityStatsListener)}
     *
     * @param listener listener to be removed.
     *
     * @throws SecurityException if the caller does not have permission to add a listener
     */
    @Override
    public void removeOnWifiUsabilityStatsListener(IOnWifiUsabilityStatsListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");
        if (mVerboseLoggingEnabled) {
            mLog.info("removeOnWifiUsabilityStatsListener uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() ->
                mWifiMetrics.removeOnWifiUsabilityListener(listener),
                TAG + "#removeOnWifiUsabilityStatsListener");
    }

    /**
     * Updates the Wi-Fi usability score.
     * @param seqNum Sequence number of the Wi-Fi usability score.
     * @param score The Wi-Fi usability score.
     * @param predictionHorizonSec Prediction horizon of the Wi-Fi usability score in second.
     */
    @Override
    public void updateWifiUsabilityScore(int seqNum, int score, int predictionHorizonSec) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");

        if (mVerboseLoggingEnabled) {
            mLog.info("updateWifiUsabilityScore uid=% seqNum=% score=% predictionHorizonSec=%")
                    .c(Binder.getCallingUid())
                    .c(seqNum)
                    .c(score)
                    .c(predictionHorizonSec)
                    .flush();
        }
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> {
            String ifaceName = mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName();
            mWifiMetrics.incrementWifiUsabilityScoreCount(
                    ifaceName, seqNum, score, predictionHorizonSec);
        }, TAG + "#updateWifiUsabilityScore");
    }

    /**
     * Notify interested parties if a wifi config has been changed.
     *
     * @param wifiCredentialEventType WIFI_CREDENTIAL_SAVED or WIFI_CREDENTIAL_FORGOT
     * @param config Must have a WifiConfiguration object to succeed
     */
    private void broadcastWifiCredentialChanged(int wifiCredentialEventType,
            WifiConfiguration config) {
        Intent intent = new Intent(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
        if (config != null && config.SSID != null && mWifiPermissionsUtil.isLocationModeEnabled()) {
            intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID, config.SSID);
        }
        intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE,
                wifiCredentialEventType);
        mContext.createContextAsUser(UserHandle.CURRENT, 0)
                .sendBroadcastWithMultiplePermissions(
                        intent,
                        new String[]{
                                android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE,
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                        });
    }

    /**
     * Connects to a network.
     *
     * If the supplied config is not null, then the netId argument will be ignored and the config
     * will be saved (or updated if its networkId or profile key already exist) and connected to.
     *
     * If the supplied config is null, then the netId argument will be matched to a saved config to
     * be connected to.
     *
     * @param config New or existing config to add/update and connect to
     * @param netId Network ID of existing config to connect to if the supplied config is null
     * @param callback Listener to notify action result
     * @param packageName Package name of the requesting App
     * @param extras Bundle of extras
     *
     * see: {@link WifiManager#connect(WifiConfiguration, WifiManager.ActionListener)}
     *      {@link WifiManager#connect(int, WifiManager.ActionListener)}
     */
    @Override
    public void connect(WifiConfiguration config, int netId, @Nullable IActionListener callback,
            @NonNull String packageName, Bundle extras) {
        int uid = getMockableCallingUid();
        if (!isPrivileged(Binder.getCallingPid(), uid)
                // TODO(b/343881335): Longer term, we need a specific permission
                // for NFC.
                && UserHandle.getAppId(uid) != Process.NFC_UID) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (packageName == null) {
            throw new IllegalArgumentException("packageName must not be null");
        }
        final String attributionTagToUse;
        final int uidToUse;
        final String packageNameToUse;
        if (SdkLevel.isAtLeastS() && UserHandle.getAppId(uid) == Process.SYSTEM_UID) {
            AttributionSource as = extras.getParcelable(
                    WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE);
            if (as == null) {
                throw new SecurityException("connect attributionSource is null");
            }
            if (!as.checkCallingUid()) {
                throw new SecurityException(
                        "connect invalid (checkCallingUid fails) attribution source="
                                + as);
            }
            // an attribution chain is either of size 1: unregistered (valid by definition) or
            // size >1: in which case all are validated.
            AttributionSource asIt = as;
            AttributionSource asLast = as;
            if (as.getNext() != null) {
                do {
                    if (!asIt.isTrusted(mContext)) {
                        throw new SecurityException(
                                "connect invalid (isTrusted fails) attribution source="
                                        + asIt);
                    }
                    asIt = asIt.getNext();
                    if (asIt != null) asLast = asIt;
                } while (asIt != null);
            }
            // use the last AttributionSource in the chain - i.e. the original caller
            attributionTagToUse = asLast.getAttributionTag();
            uidToUse = asLast.getUid();
            packageNameToUse = asLast.getPackageName();
        } else {
            attributionTagToUse = mContext.getAttributionTag();
            uidToUse = uid;
            packageNameToUse = packageName;
        }
        mLog.info("connect uid=% uidToUse=% packageNameToUse=% attributionTagToUse=%")
                .c(uid).c(uidToUse).c(packageNameToUse).c(attributionTagToUse).flush();
        mLastCallerInfoManager.put(config != null
                        ? WifiManager.API_CONNECT_CONFIG : WifiManager.API_CONNECT_NETWORK_ID,
                Process.myTid(), uid, Binder.getCallingPid(), packageName, true);
        mWifiThreadRunner.post(
                () -> {
                    ActionListenerWrapper wrapper = new ActionListenerWrapper(callback);
                    final NetworkUpdateResult result;
                    // if connecting using WifiConfiguration, save the network first
                    if (config != null) {
                        if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
                            mWifiMetrics.logUserActionEvent(
                                    UserActionEvent.EVENT_ADD_OR_UPDATE_NETWORK, config.networkId);
                        }
                        result = mWifiConfigManager.addOrUpdateNetwork(config, uid);
                        if (!result.isSuccess()) {
                            Log.e(TAG, "connect adding/updating config=" + config + " failed");
                            wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
                            return;
                        }
                        broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_SAVED, config);
                    } else {
                        if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
                            mWifiMetrics.logUserActionEvent(
                                    UserActionEvent.EVENT_MANUAL_CONNECT, netId);
                        }
                        result = new NetworkUpdateResult(netId);
                    }
                    WifiConfiguration configuration =
                            mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
                    if (configuration == null) {
                        Log.e(TAG, "connect to Invalid network Id=" + netId);
                        wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
                        return;
                    }
                    if (mWifiPermissionsUtil.isAdminRestrictedNetwork(configuration)) {
                        Log.e(TAG, "connect to network Id=" + netId + "restricted by admin");
                        wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
                        return;
                    }
                    if (mWifiGlobals.isDeprecatedSecurityTypeNetwork(configuration)) {
                        Log.e(TAG, "connect to network Id=" + netId + " security type deprecated.");
                        wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
                        return;
                    }
                    if (configuration.enterpriseConfig != null
                            && configuration.enterpriseConfig.isAuthenticationSimBased()) {
                        int subId =
                                mWifiCarrierInfoManager.getBestMatchSubscriptionId(configuration);
                        if (!mWifiCarrierInfoManager.isSimReady(subId)) {
                            Log.e(
                                    TAG,
                                    "connect to SIM-based config="
                                            + configuration
                                            + "while SIM is absent");
                            wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
                            return;
                        }
                        if (mWifiCarrierInfoManager.requiresImsiEncryption(subId)
                                && !mWifiCarrierInfoManager.isImsiEncryptionInfoAvailable(subId)) {
                            Log.e(
                                    TAG,
                                    "Imsi protection required but not available for Network="
                                            + configuration);
                            wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
                            return;
                        }
                        if (mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(
                                configuration.carrierId)) {
                            Optional<PseudonymInfo> pseudonymInfo =
                                    mWifiPseudonymManager.getValidPseudonymInfo(
                                            configuration.carrierId);
                            if (pseudonymInfo.isEmpty()) {
                                Log.e(
                                        TAG,
                                        "There isn't any valid pseudonym to update the Network="
                                                + configuration);
                                mWifiPseudonymManager.retrievePseudonymOnFailureTimeoutExpired(
                                        configuration);
                                // TODO(b/274148786): new error code and UX for this failure.
                                wrapper.sendFailure(
                                        WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
                                return;
                            } else {
                                mWifiPseudonymManager.updateWifiConfiguration(configuration);
                            }
                        }
                    }

                    // Tear down already connected secondary internet CMMs to avoid MCC.
                    // Also tear down secondary CMMs that are already connected to the same network
                    // to make sure the user's manual connection succeeds.
                    ScanResultMatchInfo targetMatchInfo =
                            ScanResultMatchInfo.fromWifiConfiguration(configuration);
                    for (ClientModeManager cmm : mActiveModeWarden.getClientModeManagers()) {
                        if (!cmm.isConnected()) {
                            continue;
                        }
                        ActiveModeManager.ClientRole role = cmm.getRole();
                        if (role == ROLE_CLIENT_LOCAL_ONLY
                                || role == ROLE_CLIENT_SECONDARY_LONG_LIVED) {
                            WifiConfiguration connectedConfig = cmm.getConnectedWifiConfiguration();
                            if (connectedConfig == null) {
                                continue;
                            }
                            ScanResultMatchInfo connectedMatchInfo =
                                    ScanResultMatchInfo.fromWifiConfiguration(connectedConfig);
                            ConcreteClientModeManager concreteCmm = (ConcreteClientModeManager) cmm;
                            if (concreteCmm.isSecondaryInternet()
                                    || targetMatchInfo.matchForNetworkSelection(connectedMatchInfo)
                                    != null) {
                                if (mVerboseLoggingEnabled) {
                                    Log.v(
                                            TAG,
                                            "Shutting down client mode manager to satisfy user "
                                                    + "connection: "
                                                    + cmm);
                                }
                                cmm.stop();
                            }
                        }
                    }

                    mMakeBeforeBreakManager.stopAllSecondaryTransientClientModeManagers(
                            () ->
                                    mConnectHelper.connectToNetwork(
                                            result, wrapper, uidToUse, packageNameToUse,
                                            attributionTagToUse));
                }, TAG + "#connect");
    }

    /**
     * see {@link android.net.wifi.WifiManager#save(WifiConfiguration,
     * WifiManager.ActionListener)}
     */
    @Override
    public void save(WifiConfiguration config, @Nullable IActionListener callback,
            @NonNull String packageName) {
        int uid = Binder.getCallingUid();
        if (!isPrivileged(Binder.getCallingPid(), uid)) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (packageName == null) {
            throw new IllegalArgumentException("packageName must not be null");
        }
        mLog.info("save uid=%").c(uid).flush();
        mLastCallerInfoManager.put(WifiManager.API_SAVE, Process.myTid(),
                uid, Binder.getCallingPid(), packageName, true);
        mWifiThreadRunner.post(() -> {
            ActionListenerWrapper wrapper = new ActionListenerWrapper(callback);
            NetworkUpdateResult result =
                    mWifiConfigManager.updateBeforeSaveNetwork(config, uid, packageName);
            if (result.isSuccess()) {
                broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_SAVED, config);
                mMakeBeforeBreakManager.stopAllSecondaryTransientClientModeManagers(() ->
                        mActiveModeWarden.getPrimaryClientModeManager()
                                .saveNetwork(result, wrapper, uid, packageName));
                if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
                    mWifiMetrics.logUserActionEvent(
                            UserActionEvent.EVENT_ADD_OR_UPDATE_NETWORK, config.networkId);
                }
            } else {
                wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
            }
        }, TAG + "#save");
    }

    /**
     * see {@link android.net.wifi.WifiManager#forget(int, WifiManager.ActionListener)}
     */
    @Override
    public void forget(int netId, @Nullable IActionListener callback) {
        int uid = Binder.getCallingUid();
        if (!isPrivileged(Binder.getCallingPid(), uid)) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        mLog.info("forget uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            // It's important to log this metric before the actual forget executes because
            // the netId becomes invalid after the forget operation.
            mWifiMetrics.logUserActionEvent(UserActionEvent.EVENT_FORGET_WIFI, netId);
        }
        mLastCallerInfoManager.put(WifiManager.API_FORGET, Process.myTid(),
                uid, Binder.getCallingPid(), "<unknown>", true);
        mWifiThreadRunner.post(() -> {
            WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(netId);
            boolean success = mWifiConfigManager.removeNetwork(netId, uid, null);
            ActionListenerWrapper wrapper = new ActionListenerWrapper(callback);
            if (success) {
                wrapper.sendSuccess();
                broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_FORGOT, config);
            } else {
                Log.e(TAG, "Failed to remove network");
                wrapper.sendFailure(WifiManager.ActionListener.FAILURE_INTERNAL_ERROR);
            }
        }, TAG + "#forget");
    }

    /**
     * See {@link WifiManager#registerScanResultsCallback(WifiManager.ScanResultsCallback)}
     */
    @Override
    public void registerScanResultsCallback(@NonNull IScanResultsCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        enforceAccessPermission();

        if (mVerboseLoggingEnabled) {
            mLog.info("registerScanResultsCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> {
            if (!mWifiInjector.getScanRequestProxy().registerScanResultsCallback(callback)) {
                Log.e(TAG, "registerScanResultsCallback: Failed to register callback");
            }
        }, TAG + "#registerScanResultsCallback");
    }

    /**
     * See {@link WifiManager#registerScanResultsCallback(WifiManager.ScanResultsCallback)}
     */
    public void unregisterScanResultsCallback(@NonNull IScanResultsCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        enforceAccessPermission();

        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterScanResultCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        // post operation to handler thread
        mWifiThreadRunner.post(() -> mWifiInjector.getScanRequestProxy()
                        .unregisterScanResultsCallback(callback),
                TAG + "#unregisterScanResultsCallback");

    }

    /**
     * See {@link WifiManager#addSuggestionConnectionStatusListener(Executor,
     * SuggestionConnectionStatusListener)}
     */
    public void registerSuggestionConnectionStatusListener(
            @NonNull ISuggestionConnectionStatusListener listener, String packageName,
            @Nullable String featureId) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        final int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        enforceAccessPermission();
        if (SdkLevel.isAtLeastT()) {
            enforceLocationPermissionInManifest(uid, false /* isCoarseOnly */);
        } else {
            enforceLocationPermission(packageName, featureId, uid);
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("registerSuggestionConnectionStatusListener uid=%").c(uid).flush();
        }
        mWifiThreadRunner.post(() ->
                mWifiNetworkSuggestionsManager
                        .registerSuggestionConnectionStatusListener(listener, packageName, uid),
                TAG + "#registerSuggestionConnectionStatusListener");
    }

    /**
     * See {@link WifiManager#removeSuggestionConnectionStatusListener(
     * SuggestionConnectionStatusListener)}
     */
    public void unregisterSuggestionConnectionStatusListener(
            @NonNull ISuggestionConnectionStatusListener listener, String packageName) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterSuggestionConnectionStatusListener uid=%")
                    .c(uid).flush();
        }
        mWifiThreadRunner.post(() ->
                mWifiNetworkSuggestionsManager
                        .unregisterSuggestionConnectionStatusListener(listener, packageName, uid),
                TAG + "#unregisterSuggestionConnectionStatusListener");
    }

    /**
     * {@link WifiManager#addLocalOnlyConnectionFailureListener(Executor, WifiManager.LocalOnlyConnectionFailureListener)}
     */
    @Override
    public void addLocalOnlyConnectionStatusListener(ILocalOnlyConnectionStatusListener listener,
            String packageName, String featureId) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        final int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        enforceAccessPermission();
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            if (!mWifiPermissionsUtil.doesUidBelongToCurrentUserOrDeviceOwner(uid)) {
                Log.e(TAG, "UID " + uid + " not visible to the current user");
                throw new SecurityException("UID " + uid + " not visible to the current user");
            }
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("addLocalOnlyConnectionFailureListener uid=%").c(uid).flush();
        }
        mWifiThreadRunner.post(() ->
                mWifiNetworkFactory.addLocalOnlyConnectionStatusListener(listener, packageName,
                        featureId), TAG + "#addLocalOnlyConnectionStatusListener");
    }

    /**
     * {@link WifiManager#removeLocalOnlyConnectionFailureListener(WifiManager.LocalOnlyConnectionFailureListener)}
     */
    @Override
    public void removeLocalOnlyConnectionStatusListener(ILocalOnlyConnectionStatusListener listener,
            String packageName) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            if (!mWifiPermissionsUtil.doesUidBelongToCurrentUserOrDeviceOwner(uid)) {
                Log.e(TAG, "UID " + uid + " not visible to the current user");
                throw new SecurityException("UID " + uid + " not visible to the current user");
            }
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("removeLocalOnlyConnectionFailureListener uid=%")
                    .c(uid).flush();
        }
        mWifiThreadRunner.post(() ->
                mWifiNetworkFactory.removeLocalOnlyConnectionStatusListener(listener, packageName),
                TAG + "#removeLocalOnlyConnectionStatusListener");
    }

    @Override
    public int calculateSignalLevel(int rssi) {
        return RssiUtil.calculateSignalLevel(mContext, rssi);
    }

    /**
     * See {@link WifiManager#setPnoScanState(int)}
     */
    @Override
    public void setPnoScanEnabled(boolean enabled, boolean enablePnoScanAfterWifiToggle,
            String packageName) {
        int callingUid = Binder.getCallingUid();
        boolean hasPermission = mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)
                || mWifiPermissionsUtil.checkNetworkSetupWizardPermission(callingUid);
        if (!hasPermission && SdkLevel.isAtLeastT()) {
            // MANAGE_WIFI_NETWORK_SELECTION is a new permission added in T.
            hasPermission = mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(
                    callingUid);
        }
        if (!hasPermission) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to set PNO scan state");
        }

        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "setPnoScanEnabled " + Binder.getCallingUid() + " enabled=" + enabled
                    + ", enablePnoScanAfterWifiToggle=" + enablePnoScanAfterWifiToggle);
        }
        mLastCallerInfoManager.put(
                WifiManager.API_SET_PNO_SCAN_ENABLED,
                Process.myTid(), Binder.getCallingUid(), Binder.getCallingPid(), packageName,
                enabled);
        mWifiThreadRunner.post(() -> {
            mWifiConnectivityManager.setPnoScanEnabledByFramework(enabled,
                    enablePnoScanAfterWifiToggle);
        }, TAG + "#setPnoScanEnabled");
    }

    /**
     * See {@link WifiManager#setExternalPnoScanRequest(List, int[], Executor,
     * WifiManager.PnoScanResultsCallback)}.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setExternalPnoScanRequest(@NonNull IBinder binder,
            @NonNull IPnoScanResultsCallback callback,
            @NonNull List<WifiSsid> ssids, @NonNull int[] frequencies,
            @NonNull String packageName, @NonNull String featureId) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        if (binder == null) throw new IllegalArgumentException("binder cannot be null");
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        if (ssids == null || ssids.isEmpty()) throw new IllegalStateException(
                "Ssids can't be null or empty");
        if (ssids.size() > 2) {
            throw new IllegalArgumentException("Ssid list can't be greater than 2");
        }
        if (frequencies == null) {
            throw new IllegalArgumentException("frequencies should not be null");
        }
        if (frequencies.length > 10) {
            throw new IllegalArgumentException("Length of frequencies must be smaller than 10");
        }
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        if (!mWifiPermissionsUtil.checkRequestCompanionProfileAutomotiveProjectionPermission(uid)
                || !mWifiPermissionsUtil.checkCallersLocationPermissionInManifest(uid, false)) {
            throw new SecurityException(TAG + " Caller uid " + uid + " has no permission");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("setExternalPnoScanRequest uid=%").c(uid).flush();
        }
        mWifiThreadRunner.post(() -> {
            try {
                if (!isPnoSupported()) {
                    callback.onRegisterFailed(REGISTER_PNO_CALLBACK_PNO_NOT_SUPPORTED);
                    return;
                }
                mWifiConnectivityManager.setExternalPnoScanRequest(
                        uid, packageName, binder, callback, ssids, frequencies);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#setExternalPnoScanRequest");
    }

    /**
     * See {@link WifiManager#clearExternalPnoScanRequest()}
     */
    @Override
    public void clearExternalPnoScanRequest() {
        int uid = Binder.getCallingUid();
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("setExternalPnoScanRequest uid=%").c(uid).flush();
        }
        mWifiThreadRunner.post(() -> {
            mWifiConnectivityManager.clearExternalPnoScanRequest(uid);
        }, TAG + "#clearExternalPnoScanRequest");
    }

    /**
     * See {@link WifiManager#getLastCallerInfoForApi(int, Executor, BiConsumer)}.
     */
    @Override
    public void getLastCallerInfoForApi(int apiType, @NonNull ILastCallerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        if (apiType < WifiManager.API_SCANNING_ENABLED || apiType > WifiManager.API_MAX) {
            throw new IllegalArgumentException("Invalid apiType " + apiType);
        }
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkStackPermission(uid)
                && !mWifiPermissionsUtil.checkMainlineNetworkStackPermission(uid)) {
            throw new SecurityException("Caller uid " + uid + " has no permission");
        }

        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "getLastCallerInfoForApi " + Binder.getCallingUid());
        }
        mWifiThreadRunner.post(() -> {
            LastCallerInfoManager.LastCallerInfo lastCallerInfo =
                    mLastCallerInfoManager.get(apiType);
            try {
                if (lastCallerInfo == null) {
                    listener.onResult(null, false);
                    return;
                }
                listener.onResult(lastCallerInfo.getPackageName(), lastCallerInfo.getToggleState());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getLastCallerInfoForApi");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setWifiConnectedNetworkScorer(Executor,
     * WifiManager.WifiConnectedNetworkScorer)}
     *
     * @param binder IBinder instance to allow cleanup if the app dies.
     * @param scorer Wifi connected network scorer to set.
     * @return true Scorer is set successfully.
     *
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public boolean setWifiConnectedNetworkScorer(IBinder binder,
            IWifiConnectedNetworkScorer scorer) {
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (scorer == null) {
            throw new IllegalArgumentException("Scorer must not be null");
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");
        int callingUid = Binder.getCallingUid();
        if (mVerboseLoggingEnabled) {
            mLog.info("setWifiConnectedNetworkScorer uid=%").c(callingUid).flush();
        }
        mWifiThreadRunner.post(() -> bindScorerService(callingUid));
        // Post operation to handler thread
        return mWifiThreadRunner.call(
                () -> mActiveModeWarden.setWifiConnectedNetworkScorer(binder, scorer, callingUid),
                false, TAG + "#setWifiConnectedNetworkScorer");
    }

    /**
     * See {@link WifiManager#clearWifiConnectedNetworkScorer()}
     */
    @Override
    public void clearWifiConnectedNetworkScorer() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");
        if (mVerboseLoggingEnabled) {
            mLog.info("clearWifiConnectedNetworkScorer uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> unbindScorerService(SCORER_BINDING_STATE_CLEARED));
        // Post operation to handler thread
        mWifiThreadRunner.post(() -> mActiveModeWarden.clearWifiConnectedNetworkScorer(),
                TAG + "#clearWifiConnectedNetworkScorer");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setScanThrottleEnabled(boolean)}
     */
    @Override
    public void setScanThrottleEnabled(boolean enable) {
        enforceNetworkSettingsPermission();
        mLog.info("setScanThrottleEnabled uid=% enable=%")
                .c(Binder.getCallingUid())
                .c(enable).flush();
        mScanRequestProxy.setScanThrottleEnabled(enable);
    }

    /**
     * See {@link android.net.wifi.WifiManager#isScanThrottleEnabled()}
     */
    @Override
    public boolean isScanThrottleEnabled() {
        enforceAccessPermission();
        final boolean enable = mScanRequestProxy.isScanThrottleEnabled();
        if (mVerboseLoggingEnabled) {
            mLog.info("isScanThrottleEnabled uid=% enable=%")
                    .c(Binder.getCallingUid()).c(enable).flush();
        }
        return enable;
    }

    /**
     * See {@link android.net.wifi.WifiManager#setAutoWakeupEnabled(boolean)}
     */
    @Override
    public void setAutoWakeupEnabled(boolean enable) {
        enforceNetworkSettingsPermission();
        mLog.info("setWalkeupEnabled uid=% verbose=%")
                .c(Binder.getCallingUid())
                .c(enable).flush();
        long ident = Binder.clearCallingIdentity();
        try {
            mWifiInjector.getWakeupController().setEnabled(enable);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#isAutoWakeupEnabled()}
     */
    @Override
    public boolean isAutoWakeupEnabled() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("isAutoWakeupEnabled uid=%").c(Binder.getCallingUid()).flush();
        }
        long ident = Binder.clearCallingIdentity();
        try {
            return mWifiInjector.getWakeupController().isEnabled();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#setCarrierNetworkOffloadEnabled(int, boolean, boolean)}
     */
    @Override
    public void setCarrierNetworkOffloadEnabled(int subscriptionId, boolean merged,
            boolean enabled) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("setCarrierNetworkOffloadEnabled uid=%").c(Binder.getCallingUid()).flush();
        }
        long ident = Binder.clearCallingIdentity();
        try {
            mWifiCarrierInfoManager.setCarrierNetworkOffloadEnabled(subscriptionId, merged,
                    enabled);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#isCarrierNetworkOffloadEnabled(int, boolean)}
     */
    @Override
    public boolean isCarrierNetworkOffloadEnabled(int subId, boolean merged) {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("isCarrierNetworkOffload uid=%").c(Binder.getCallingUid()).flush();
        }
        long ident = Binder.clearCallingIdentity();
        try {
            return mWifiCarrierInfoManager.isCarrierNetworkOffloadEnabled(subId, merged);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#addSuggestionUserApprovalStatusListener(Executor,
     * WifiManager.SuggestionUserApprovalStatusListener)}
     */
    @Override
    public void addSuggestionUserApprovalStatusListener(
            ISuggestionUserApprovalStatusListener listener, String packageName) {
        if (listener == null) {
            throw new NullPointerException("listener must not be null");
        }
        final int uid = Binder.getCallingUid();
        enforceAccessPermission();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            if (!mWifiPermissionsUtil.doesUidBelongToCurrentUserOrDeviceOwner(uid)) {
                Log.e(TAG, "UID " + uid + " not visible to the current user");
                throw new SecurityException("UID " + uid + " not visible to the current user");
            }
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("addSuggestionUserApprovalStatusListener uid=%").c(uid).flush();
        }
        mWifiThreadRunner.post(() -> mWifiNetworkSuggestionsManager
                .addSuggestionUserApprovalStatusListener(listener, packageName, uid),
                TAG + "#addSuggestionUserApprovalStatusListener");
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeSuggestionUserApprovalStatusListener(
     * WifiManager.SuggestionUserApprovalStatusListener)}
     */
    @Override
    public void removeSuggestionUserApprovalStatusListener(
            ISuggestionUserApprovalStatusListener listener, String packageName) {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            if (!mWifiPermissionsUtil.doesUidBelongToCurrentUserOrDeviceOwner(uid)) {
                Log.e(TAG, "UID " + uid + " not visible to the current user");
                throw new SecurityException("UID " + uid + " not visible to the current user");
            }
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("removeSuggestionUserApprovalStatusListener uid=%")
                    .c(uid).flush();
        }
        mWifiThreadRunner.post(() ->
                mWifiNetworkSuggestionsManager
                        .removeSuggestionUserApprovalStatusListener(listener, packageName, uid),
                TAG + "#removeSuggestionUserApprovalStatusListener");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setEmergencyScanRequestInProgress(boolean)}.
     */
    @Override
    public void setEmergencyScanRequestInProgress(boolean inProgress) {
        enforceNetworkStackPermission();
        int uid = Binder.getCallingUid();
        mLog.info("setEmergencyScanRequestInProgress uid=%").c(uid).flush();
        mActiveModeWarden.setEmergencyScanRequestInProgress(inProgress);
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeAppState(int, String)}.
     */
    @Override
    public void removeAppState(int targetAppUid, @NonNull String targetAppPackageName) {
        enforceNetworkSettingsPermission();
        mLog.info("removeAppState uid=%").c(Binder.getCallingUid()).flush();

        mWifiThreadRunner.post(() -> {
            removeAppStateInternal(targetAppUid, targetAppPackageName);
        }, TAG + "#removeAppState");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setWifiScoringEnabled(boolean)}.
     */
    @Override
    public boolean setWifiScoringEnabled(boolean enabled) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_SETTINGS, "WifiService");
        // Post operation to handler thread
        return mSettingsStore.handleWifiScoringEnabled(enabled);
    }

    /**
     * See {@link android.net.wifi.WifiManager#storeCapturedData(Executor, IntConsumer, int,
     * booloan, long, long)}.
     */
    @Override
    public void storeCapturedData(int triggerType, boolean isFullCapture,
            long triggerStartTimeMillis, long triggerStopTimeMillis,
            @NonNull IIntegerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiMetrics.storeCapturedData(triggerType, isFullCapture,
                        triggerStartTimeMillis, triggerStopTimeMillis));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#storeCapturedData");
    }

    @VisibleForTesting
    static boolean isValidBandForGetUsableChannels(@WifiScanner.WifiBand int band) {
        switch (band) {
            case WifiScanner.WIFI_BAND_UNSPECIFIED:
            case WifiScanner.WIFI_BAND_24_GHZ:
            case WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS:
            case WifiScanner.WIFI_BAND_BOTH_WITH_DFS:
            case WifiScanner.WIFI_BAND_6_GHZ:
            case WifiScanner.WIFI_BAND_24_5_WITH_DFS_6_GHZ:
            case WifiScanner.WIFI_BAND_60_GHZ:
            case WifiScanner.WIFI_BAND_24_5_WITH_DFS_6_60_GHZ:
                return true;
            default:
                return false;
        }
    }

    private List<WifiAvailableChannel> getStoredSoftApAvailableChannels(
            @WifiScanner.WifiBand int band) {
        List<Integer> freqs = new ArrayList<>();
        try {
            JSONArray json =
                    new JSONArray(
                            mSettingsConfigStore.get(
                                    WifiSettingsConfigStore.WIFI_AVAILABLE_SOFT_AP_FREQS_MHZ));
            for (int i = 0; i < json.length(); i++) {
                freqs.add(json.getInt(i));
            }
        } catch (JSONException e) {
            Log.i(TAG, "Failed to read stored JSON for available Soft AP channels: " + e);
        }
        List<WifiAvailableChannel> channels = new ArrayList<>();
        for (int freq : freqs) {
            if ((band & ScanResult.toBand(freq)) == 0) {
                continue;
            }
            // TODO b/340956906: Save and retrieve channel width in config store along with
            //  frequency.
            channels.add(new WifiAvailableChannel(freq, WifiAvailableChannel.OP_MODE_SAP,
                    ScanResult.CHANNEL_WIDTH_20MHZ));
        }
        return channels;
    }

    /**
     * See {@link android.net.wifi.WifiManager#getUsableChannels(int, int) and
     * See {@link android.net.wifi.WifiManager#getAllowedChannels(int, int).
     *
     * @throws SecurityException if the caller does not have permission
     * or IllegalArgumentException if the band is invalid for this method.
     */
    @Override
    public List<WifiAvailableChannel> getUsableChannels(@WifiScanner.WifiBand int band,
            @WifiAvailableChannel.OpMode int mode, @WifiAvailableChannel.Filter int filter,
            String packageName, Bundle extras) {
        final int uid = Binder.getCallingUid();
        if (isPlatformOrTargetSdkLessThanU(packageName, uid)) {
            // Location mode must be enabled
            long ident = Binder.clearCallingIdentity();
            try {
                if (!mWifiPermissionsUtil.isLocationModeEnabled()) {
                    throw new SecurityException("Location mode is disabled for the device");
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            if (!mWifiPermissionsUtil.checkCallersHardwareLocationPermission(uid)) {
                throw new SecurityException(
                        "UID " + uid + " does not have location h/w permission");
            }
        } else {
            mWifiPermissionsUtil.enforceNearbyDevicesPermission(
                    extras.getParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE),
                    true, TAG + " getUsableChannels");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getUsableChannels uid=% band=% mode=% filter=%").c(Binder.getCallingUid()).c(
                    band).c(mode).c(filter).flush();
        }
        if (!isValidBandForGetUsableChannels(band)) {
            throw new IllegalArgumentException("Unsupported band: " + band);
        }
        // Use stored values if the HAL isn't started and the stored country code matches.
        // This is to show the band availability based on the regulatory domain.
        if (mWifiNative.isHalSupported() && !mWifiNative.isHalStarted()
                && mode == WifiAvailableChannel.OP_MODE_SAP
                && filter == WifiAvailableChannel.FILTER_REGULATORY
                && TextUtils.equals(
                        mSettingsConfigStore.get(WifiSettingsConfigStore.WIFI_SOFT_AP_COUNTRY_CODE),
                        mCountryCode.getCountryCode())) {
            List<WifiAvailableChannel> storedChannels = getStoredSoftApAvailableChannels(band);
            if (!storedChannels.isEmpty()) {
                return storedChannels;
            }
        }
        List<WifiAvailableChannel> channels = mWifiThreadRunner.call(
                () -> mWifiNative.getUsableChannels(band, mode, filter), null,
                TAG + "#getUsableChannels");
        if (channels == null) {
            throw new UnsupportedOperationException();
        }
        return channels;
    }

    private void resetNotificationManager() {
        mWifiInjector.getWifiNotificationManager().createNotificationChannels();
        mWifiInjector.getOpenNetworkNotifier().clearPendingNotification(false);
        mWifiCarrierInfoManager.resetNotification();
        mWifiNetworkSuggestionsManager.resetNotification();
        mWifiInjector.getWakeupController().resetNotification();
    }

    /**
     * See {@link android.net.wifi.WifiManager#flushPasspointAnqpCache()}.
     */
    @Override
    public void flushPasspointAnqpCache(@NonNull String packageName) {
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);

        if (!isDeviceOrProfileOwner(callingUid, packageName)) {
            enforceAnyPermissionOf(android.Manifest.permission.NETWORK_SETTINGS,
                    android.Manifest.permission.NETWORK_MANAGED_PROVISIONING,
                    android.Manifest.permission.NETWORK_CARRIER_PROVISIONING);
        }
        mWifiThreadRunner.post(mPasspointManager::clearAnqpRequestsAndFlushCache,
                TAG + "#flushPasspointAnqpCache");
    }

    /**
     * See {@link android.net.wifi.WifiManager#isWifiPasspointEnabled()}.
     */
    @Override
    public boolean isWifiPasspointEnabled() {
        enforceAccessPermission();

        if (mVerboseLoggingEnabled) {
            mLog.info("isWifiPasspointEnabled uid=%").c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        return mWifiThreadRunner.call(() -> mPasspointManager.isWifiPasspointEnabled(), false,
                TAG + "#isWifiPasspointEnabled");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setWifiPasspointEnabled()}.
     */
    @Override
    public void setWifiPasspointEnabled(boolean enabled) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (!isSettingsOrSuw(pid, uid)) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        if (mVerboseLoggingEnabled) {
            mLog.info("setWifiPasspointEnabled uid=% pid=% enable=%")
                .c(uid).c(pid).c(enabled)
                .flush();
        }

        // Post operation to handler thread
        mWifiThreadRunner.post(() -> mPasspointManager.setWifiPasspointEnabled(enabled),
                TAG + "#setWifiPasspointEnabled"
        );
    }

    /**
     * See {@link android.net.wifi.WifiManager#isPreferredNetworkOffloadSupported()}.
     */
    @Override
    public boolean isPnoSupported() {
        boolean featureSetSupportsPno = isFeatureSupported(WifiManager.WIFI_FEATURE_PNO);
        return mWifiGlobals.isSwPnoEnabled()
                || (mWifiGlobals.isBackgroundScanSupported() && featureSetSupportsPno);
    }

    private boolean isAggressiveRoamingModeSupported() {
        return isFeatureSupported(WifiManager.WIFI_FEATURE_AGGRESSIVE_ROAMING_MODE_SUPPORT);
    }

    /**
     * @return true if this device supports Trust On First Use
     */
    private boolean isTrustOnFirstUseSupported() {
        return isFeatureSupported(WifiManager.WIFI_FEATURE_TRUST_ON_FIRST_USE);
    }

    /**
     * See {@link android.net.wifi.WifiManager#getStaConcurrencyForMultiInternetMode()}.
     */
    @Override
    public @WifiManager.WifiMultiInternetMode int getStaConcurrencyForMultiInternetMode() {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        enforceAccessPermission();

        if (mVerboseLoggingEnabled) {
            mLog.info("getStaConcurrencyForMultiInternetMode uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        return mWifiThreadRunner.call(
                () -> mMultiInternetManager.getStaConcurrencyForMultiInternetMode(),
                WifiManager.WIFI_MULTI_INTERNET_MODE_DISABLED,
                TAG + "#getStaConcurrencyForMultiInternetMode");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setStaConcurrencyForMultiInternetMode()}.
     */
    @Override
    public boolean setStaConcurrencyForMultiInternetMode(
            @WifiManager.WifiMultiInternetMode int mode) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (!isSettingsOrSuw(pid, uid)) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        if (mVerboseLoggingEnabled) {
            mLog.info("setStaConcurrencyForMultiInternetMode uid=% pid=% mode=%")
                .c(uid).c(pid).c(mode)
                .flush();
        }
        // Post operation to handler thread
        return mWifiThreadRunner.call(() ->
                mMultiInternetManager.setStaConcurrencyForMultiInternetMode(mode), false,
                TAG + "#setStaConcurrencyForMultiInternetMode");
    }

    /**
     * See {@link android.net.wifi.WifiManager#notifyMinimumRequiredWifiSecurityLevelChanged(int)}.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void notifyMinimumRequiredWifiSecurityLevelChanged(int adminMinimumSecurityLevel) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        if (!Arrays.asList(DevicePolicyManager.WIFI_SECURITY_OPEN,
                DevicePolicyManager.WIFI_SECURITY_PERSONAL,
                DevicePolicyManager.WIFI_SECURITY_ENTERPRISE_EAP,
                DevicePolicyManager.WIFI_SECURITY_ENTERPRISE_192)
                .contains(adminMinimumSecurityLevel)) {
            throw new IllegalArgumentException("Input security level is invalid");
        }
        if (!checkManageDeviceAdminsPermission(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException("Caller does not have MANAGE_DEVICE_ADMINS permission");
        }
        mWifiThreadRunner.post(() -> {
            for (ClientModeManager cmm : mActiveModeWarden.getClientModeManagers()) {
                WifiInfo wifiInfo = cmm.getConnectionInfo();
                if (wifiInfo == null) continue;

                //check minimum security level restriction
                int currentSecurityLevel = WifiInfo.convertSecurityTypeToDpmWifiSecurity(
                        wifiInfo.getCurrentSecurityType());

                // Unknown security type is permitted when security type restriction is not set
                if (adminMinimumSecurityLevel == DevicePolicyManager.WIFI_SECURITY_OPEN
                        && currentSecurityLevel == WifiInfo.DPM_SECURITY_TYPE_UNKNOWN) {
                    continue;
                }
                if (adminMinimumSecurityLevel > currentSecurityLevel) {
                    cmm.disconnect();
                    mLog.info("disconnect admin restricted network").flush();
                    continue;
                }
            }
        }, TAG + "#notifyMinimumRequiredWifiSecurityLevelChanged");
    }

    /**
     * See {@link android.net.wifi.WifiManager#notifyWifiSsidPolicyChanged(WifiSsidPolicy)}.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void notifyWifiSsidPolicyChanged(int policyType, ParceledListSlice<WifiSsid> ssids) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException();
        }
        if (ssids == null || ssids.getList() == null) {
            throw new IllegalArgumentException("SSID list may not be null");
        }
        if (!checkManageDeviceAdminsPermission(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException("Caller does not have MANAGE_DEVICE_ADMINS permission");
        }
        mWifiThreadRunner.post(() -> {
            for (ClientModeManager cmm : mActiveModeWarden.getClientModeManagers()) {
                WifiInfo wifiInfo = cmm.getConnectionInfo();
                if (wifiInfo == null) continue;

                //skip SSID restriction check for Osu and Passpoint networks
                if (wifiInfo.isOsuAp() || wifiInfo.isPasspointAp()) continue;

                WifiSsid ssid = wifiInfo.getWifiSsid();

                if (policyType == WifiSsidPolicy.WIFI_SSID_POLICY_TYPE_ALLOWLIST
                        && !ssids.getList().contains(ssid)) {
                    cmm.disconnect();
                    mLog.info("disconnect admin restricted network").flush();
                    continue;
                }
                if (policyType == WifiSsidPolicy.WIFI_SSID_POLICY_TYPE_DENYLIST
                        && ssids.getList().contains(ssid)) {
                    cmm.disconnect();
                    mLog.info("disconnect admin restricted network").flush();
                    continue;
                }
            }
        }, TAG + "#notifyWifiSsidPolicyChanged");
    }

    /**
     * See {@link WifiManager#replyToSimpleDialog(int, int)}
     */
    public void replyToSimpleDialog(int dialogId, @WifiManager.DialogReply int reply) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mWifiPermissionsUtil.checkPackage(uid, mContext.getWifiDialogApkPkgName());
        if (mVerboseLoggingEnabled) {
            mLog.info("replyToSimpleDialog uid=% pid=%"
                            + " dialogId=% reply=%")
                    .c(uid).c(pid).c(dialogId).c(reply)
                    .flush();
        }
        mWifiThreadRunner.post(() -> mWifiDialogManager.replyToSimpleDialog(dialogId, reply),
                TAG + "#replyToSimpleDialog");
    }

    /**
     * See {@link WifiManager#replyToP2pInvitationReceivedDialog(int, boolean, String)}
     */
    @Override
    public void replyToP2pInvitationReceivedDialog(
            int dialogId, boolean accepted, @Nullable String optionalPin) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        mWifiPermissionsUtil.checkPackage(uid, mContext.getWifiDialogApkPkgName());
        if (mVerboseLoggingEnabled) {
            mLog.info("replyToP2pInvitationReceivedDialog uid=% pid=%"
                            + " dialogId=% accepted=% optionalPin=%")
                    .c(uid).c(pid).c(dialogId).c(accepted).c(optionalPin)
                    .flush();
        }
        mWifiThreadRunner.post(() -> mWifiDialogManager.replyToP2pInvitationReceivedDialog(
                dialogId, accepted, optionalPin), TAG + "#replyToP2pInvitationReceivedDialog"
        );
    }

    /**
     * See {@link android.net.wifi.WifiManager#addCustomDhcpOptions}.
     */
    @Override
    public void addCustomDhcpOptions(@NonNull WifiSsid ssid, @NonNull byte[] oui,
            @NonNull ParceledListSlice<DhcpOption> options) {
        enforceAnyPermissionOf(android.Manifest.permission.NETWORK_SETTINGS,
                android.Manifest.permission.OVERRIDE_WIFI_CONFIG);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "addCustomDhcpOptions: ssid="
                    + ssid + ", oui=" + Arrays.toString(oui) + ", options=" + options);
        }
        List<DhcpOption> dhcpOptionList = options == null ? null : options.getList();
        mWifiThreadRunner.post(() -> mWifiConfigManager.addCustomDhcpOptions(ssid, oui,
                        dhcpOptionList),
                TAG + "#addCustomDhcpOptions");
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeCustomDhcpOptions}.
     */
    @Override
    public void removeCustomDhcpOptions(@NonNull WifiSsid ssid, @NonNull byte[] oui) {
        enforceAnyPermissionOf(android.Manifest.permission.NETWORK_SETTINGS,
                android.Manifest.permission.OVERRIDE_WIFI_CONFIG);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "removeCustomDhcpOptions: ssid=" + ssid + ", oui=" + Arrays.toString(oui));
        }
        mWifiThreadRunner.post(() -> mWifiConfigManager.removeCustomDhcpOptions(ssid, oui),
                TAG + "#removeCustomDhcpOptions");
    }

    /**
     * See {@link android.net.wifi.WifiManager#getOemPrivilegedWifiAdminPackages
     */
    @Override
    public String[] getOemPrivilegedWifiAdminPackages() {
        return mResourceCache
                .getStringArray(R.array.config_oemPrivilegedWifiAdminPackages);
    }

    /**
     * See {@link WifiManager#reportImpactToCreateIfaceRequest(int, boolean, Executor, BiConsumer)}.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void reportCreateInterfaceImpact(String packageName, int interfaceType,
            boolean requireNewInterface, IInterfaceCreationInfoCallback callback) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException("SDK level too old");
        }

        final SparseIntArray hdmIfaceToWifiIfaceMap = new SparseIntArray() {{
                put(HDM_CREATE_IFACE_STA, WIFI_INTERFACE_TYPE_STA);
                put(HDM_CREATE_IFACE_AP, WIFI_INTERFACE_TYPE_AP);
                put(HDM_CREATE_IFACE_AP_BRIDGE, WIFI_INTERFACE_TYPE_AP);
                put(HDM_CREATE_IFACE_P2P, WIFI_INTERFACE_TYPE_DIRECT);
                put(HDM_CREATE_IFACE_NAN, WIFI_INTERFACE_TYPE_AWARE);
            }};
        final SparseIntArray wifiIfaceToHdmIfaceMap = new SparseIntArray() {{
                put(WIFI_INTERFACE_TYPE_STA, HDM_CREATE_IFACE_STA);
                put(WIFI_INTERFACE_TYPE_AP, HDM_CREATE_IFACE_AP);
                put(WIFI_INTERFACE_TYPE_AWARE, HDM_CREATE_IFACE_NAN);
                put(WIFI_INTERFACE_TYPE_DIRECT, HDM_CREATE_IFACE_P2P);
            }};

        if (packageName == null) throw new IllegalArgumentException("Null packageName");
        if (callback == null) throw new IllegalArgumentException("Null callback");
        if (interfaceType != WIFI_INTERFACE_TYPE_STA && interfaceType != WIFI_INTERFACE_TYPE_AP
                && interfaceType != WIFI_INTERFACE_TYPE_AWARE
                && interfaceType != WIFI_INTERFACE_TYPE_DIRECT) {
            throw new IllegalArgumentException("Invalid interfaceType");
        }
        enforceAccessPermission();
        int callingUid = getMockableCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiInterfacesPermission(callingUid)) {
            throw new SecurityException(
                    TAG + " Uid " + callingUid + " Missing MANAGE_WIFI_INTERFACES permission");
        }
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        mWifiThreadRunner.post(() -> {
            List<Pair<Integer, WorkSource>> details =
                    mHalDeviceManager.reportImpactToCreateIface(
                            wifiIfaceToHdmIfaceMap.get(interfaceType), requireNewInterface,
                            new WorkSource(callingUid, packageName));
            try {
                if (details == null) {
                    callback.onResults(false, null, null);
                } else {
                    int[] interfaces = new int[details.size()];
                    String[] packagesForInterfaces = new String[details.size()];
                    int i = 0;
                    for (Pair<Integer, WorkSource> detail: details) {
                        interfaces[i] = hdmIfaceToWifiIfaceMap.get(detail.first);
                        if (detail.second.size() == 1
                                && detail.second.getUid(0) == WIFI_UID) {
                            i++;
                            continue;
                        }
                        StringBuilder packages = new StringBuilder();
                        for (int j = 0; j < detail.second.size(); ++j) {
                            if (j != 0) packages.append(",");
                            packages.append(detail.second.getPackageName(j));
                            mContext.getPackageManager().makeUidVisible(callingUid,
                                    detail.second.getUid(j));
                        }
                        packagesForInterfaces[i] = packages.toString();
                        ++i;
                    }
                    callback.onResults(true, interfaces, packagesForInterfaces);
                }
            } catch (RemoteException e) {
                Log.e(TAG,
                        "Failed calling back with results of isItPossibleToCreateInterface - " + e);
            }
        }, TAG + "#reportCreateInterfaceImpact");
    }
    @Override
    public int getMaxNumberOfChannelsPerRequest() {
        return mResourceCache
                .getInteger(R.integer.config_wifiNetworkSpecifierMaxPreferredChannels);
    }

    private boolean policyIdsAreUnique(List<QosPolicyParams> policies) {
        Set<Integer> policyIdSet = new HashSet<>();
        for (QosPolicyParams policy : policies) {
            policyIdSet.add(policy.getPolicyId());
        }
        return policyIdSet.size() == policies.size();
    }

    private boolean policyIdsAreUnique(int[] policyIds) {
        Set<Integer> policyIdSet = new HashSet<>();
        for (int policyId : policyIds) {
            policyIdSet.add(policyId);
        }
        return policyIdSet.size() == policyIds.length;
    }

    private boolean policiesHaveSameDirection(List<QosPolicyParams> policyList) {
        int direction = policyList.get(0).getDirection();
        for (QosPolicyParams policy : policyList) {
            if (policy.getDirection() != direction) {
                return false;
            }
        }
        return true;
    }

    private void rejectAllQosPolicies(
            List<QosPolicyParams> policyParamsList, IListListener listener) {
        try {
            List<Integer> statusList = new ArrayList<>();
            for (QosPolicyParams policy : policyParamsList) {
                statusList.add(WifiManager.QOS_REQUEST_STATUS_FAILURE_UNKNOWN);
            }
            listener.onResult(statusList);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    /**
     * See {@link WifiManager#addQosPolicies(List, Executor, Consumer)}.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void addQosPolicies(@NonNull ParceledListSlice<QosPolicyParams> policyParamsList,
            @NonNull IBinder binder, @NonNull String packageName,
            @NonNull IListListener listener) {
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        }

        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed to add QoS policies");
        }

        Objects.requireNonNull(policyParamsList, "policyParamsList cannot be null");
        Objects.requireNonNull(policyParamsList.getList(),
            "policyParamsList contents cannot be null");
        Objects.requireNonNull(binder, "binder cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");

        if (policyParamsList.getList().size() == 0
                || policyParamsList.getList().size()
                > WifiManager.getMaxNumberOfPoliciesPerQosRequest()
                || !policyIdsAreUnique(policyParamsList.getList())
                || !policiesHaveSameDirection(policyParamsList.getList())) {
            throw new IllegalArgumentException("policyParamsList is invalid");
        }

        if (!mApplicationQosPolicyRequestHandler.isFeatureEnabled()) {
            Log.i(TAG, "addQosPolicies is disabled on this device");
            rejectAllQosPolicies(policyParamsList.getList(), listener);
            return;
        }



        if (!(SdkLevel.isAtLeastV() && isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX))
                && policyParamsList.getList().get(0).getDirection()
                == QosPolicyParams.DIRECTION_UPLINK) {
            Log.e(TAG, "Uplink QoS policies are only supported on devices with SDK >= V"
                    + " and 11ax support");
            rejectAllQosPolicies(policyParamsList.getList(), listener);
            return;
        }

        mWifiThreadRunner.post(() -> {
            mApplicationQosPolicyRequestHandler.queueAddRequest(
                    policyParamsList.getList(), listener, binder, uid);
        }, TAG + "#addQosPolicies");
    }

    /**
     * See {@link WifiManager#removeQosPolicies(int[])}.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void removeQosPolicies(@NonNull int[] policyIds, @NonNull String packageName) {
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        } else if (!mApplicationQosPolicyRequestHandler.isFeatureEnabled()) {
            Log.i(TAG, "removeQosPolicies is disabled on this device");
            return;
        }
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed to remove QoS policies");
        }
        Objects.requireNonNull(policyIds, "policyIdList cannot be null");
        if (policyIds.length == 0
                || policyIds.length > WifiManager.getMaxNumberOfPoliciesPerQosRequest()
                || !policyIdsAreUnique(policyIds)) {
            throw new IllegalArgumentException("policyIdList is invalid");
        }

        List<Integer> policyIdList = Arrays.stream(policyIds).boxed().toList();
        mWifiThreadRunner.post(() -> {
            mApplicationQosPolicyRequestHandler.queueRemoveRequest(policyIdList, uid);
        }, TAG + "#removeQosPolicies");
    }

    /**
     * See {@link WifiManager#removeAllQosPolicies()}.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void removeAllQosPolicies(@NonNull String packageName) {
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        } else if (!mApplicationQosPolicyRequestHandler.isFeatureEnabled()) {
            Log.i(TAG, "removeAllQosPolicies is disabled on this device");
            return;
        }
        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed to remove QoS policies");
        }

        mWifiThreadRunner.post(() -> {
            mApplicationQosPolicyRequestHandler.queueRemoveAllRequest(uid);
        }, TAG + "#removeAllQosPolicies");
    }

    /**
     * See {@link WifiManager#setLinkLayerStatsPollingInterval(int)}.
     */
    @Override
    public void setLinkLayerStatsPollingInterval(int intervalMs) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        enforceAnyPermissionOf(android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION);
        if (intervalMs < 0) {
            throw new IllegalArgumentException("intervalMs should not be smaller than 0");
        }
        mWifiThreadRunner.post(() -> mActiveModeWarden.getPrimaryClientModeManager()
                    .setLinkLayerStatsPollingInterval(intervalMs),
                TAG + "#setLinkLayerStatsPollingInterval");
    }

    /**
     * See {@link WifiManager#getLinkLayerStatsPollingInterval(Executor, Consumer)}.
     */
    @Override
    public void getLinkLayerStatsPollingInterval(@NonNull IIntegerListener listener) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        if (listener == null) {
            throw new NullPointerException("listener should not be null");
        }
        enforceAnyPermissionOf(android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION);
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiGlobals.getPollRssiIntervalMillis());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getLinkLayerStatsPollingInterval");
    }

    /**
     * See {@link WifiManager#setMloMode(int, Executor, Consumer)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setMloMode(@WifiManager.MloMode int mode, @NonNull IBooleanListener listener) {
        // SDK check.
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        // Permission check.
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed to set MLO mode");
        }
        // Argument check
        Objects.requireNonNull(listener, "listener cannot be null");
        if (mode < WifiManager.MLO_MODE_DEFAULT || mode > WifiManager.MLO_MODE_LOW_POWER) {
            throw new IllegalArgumentException("invalid mode: " + mode);
        }
        // Set MLO mode and process error status.
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiNative.setMloMode(mode) == WifiStatusCode.SUCCESS);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#setMloMode");
    }

    /**
     * See {@link WifiManager#getMloMode(Executor, Consumer)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void getMloMode(IIntegerListener listener) {
        // SDK check.
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        // Permission check.
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed to get MLO mode");
        }
        // Argument check
        Objects.requireNonNull(listener, "listener cannot be null");
        // Get MLO mode.
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiNative.getMloMode());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getMloMode");
    }

    /**
     * See {@link WifiManager#addWifiLowLatencyLockListener(Executor,
     * WifiManager.WifiLowLatencyLockListener)}
     */
    public void addWifiLowLatencyLockListener(IWifiLowLatencyLockListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException();
        }
        int callingUid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(callingUid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)) {
            throw new SecurityException(TAG + " Uid " + callingUid
                    + " Missing MANAGE_WIFI_NETWORK_SELECTION permission");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("addWifiLowLatencyLockListener uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> {
            mWifiLockManager.addWifiLowLatencyLockListener(listener);
        }, TAG + "#addWifiLowLatencyLockListener");
    }

    /**
     * See {@link WifiManager#removeWifiLowLatencyLockListener(
     * WifiManager.WifiLowLatencyLockListener)}
     */
    @Override
    public void removeWifiLowLatencyLockListener(IWifiLowLatencyLockListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException();
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("removeWifiLowLatencyLockListener uid=%").c(Binder.getCallingUid()).flush();
        }
        mWifiThreadRunner.post(() -> {
            mWifiLockManager.removeWifiLowLatencyLockListener(listener);
        }, TAG + "#removeWifiLowLatencyLockListener");
    }

    private String getPackageName(Bundle extras) {
        // AttributionSource#getPackageName() is added in API level 31.
        if (!SdkLevel.isAtLeastS() || extras == null) {
            return PACKAGE_NAME_NOT_AVAILABLE;
        }
        AttributionSource attributionSource = extras.getParcelable(
                WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE);
        if (attributionSource == null) return PACKAGE_NAME_NOT_AVAILABLE;
        String packageName = attributionSource.getPackageName();
        if (packageName == null) return PACKAGE_NAME_NOT_AVAILABLE;
        return packageName;
    }

    /**
     * See {@link WifiManager#getMaxMloAssociationLinkCount(Executor, Consumer)}
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void getMaxMloAssociationLinkCount(@NonNull IIntegerListener listener, Bundle extras) {
        // SDK check.
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        // Permission check.
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException(
                    "Caller does not have MANAGE_WIFI_NETWORK_SELECTION permission");
        }

        Objects.requireNonNull(listener, "listener cannot be null");
        if (mVerboseLoggingEnabled) {
            mLog.info("getMaxMloAssociationLinkCount: Uid=% Package Name=%").c(
                    Binder.getCallingUid()).c(getPackageName(extras)).flush();
        }

        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiNative.getMaxMloAssociationLinkCount(
                        mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName()));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getMaxMloAssociationLinkCount");
    }

    /**
     * See {@link WifiManager#getMaxMloStrLinkCount(Executor, Consumer)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void getMaxMloStrLinkCount(@NonNull IIntegerListener listener, Bundle extras) {
        // SDK check.
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        // Permission check.
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException(
                    "Caller does not have MANAGE_WIFI_NETWORK_SELECTION permission");
        }

        Objects.requireNonNull(listener, "listener cannot be null");
        if (mVerboseLoggingEnabled) {
            mLog.info("getMaxMloStrLinkCount:  Uid=% Package Name=%").c(
                    Binder.getCallingUid()).c(getPackageName(extras)).flush();
        }

        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiNative.getMaxMloStrLinkCount(
                        mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName()));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getMaxMloStrLinkCount");
    }

    /**
     * See {@link WifiManager#getSupportedSimultaneousBandCombinations(Executor, Consumer)}.
     */
    @Override
    public void getSupportedSimultaneousBandCombinations(@NonNull IWifiBandsListener listener,
            Bundle extras) {
        // SDK check.
        if (!SdkLevel.isAtLeastU()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        // Permission check.
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException(
                    "Caller does not have MANAGE_WIFI_NETWORK_SELECTION permission");
        }
        // Argument check.
        Objects.requireNonNull(listener, "listener cannot be null");
        if (mVerboseLoggingEnabled) {
            mLog.info("getSupportedSimultaneousBandCombinations:  Uid=% Package Name=%").c(
                    Binder.getCallingUid()).c(getPackageName(extras)).flush();
        }
        // Get supported band combinations from chip.
        mWifiThreadRunner.post(() -> {
            try {
                Set<List<Integer>> bandsSet = mWifiNative.getSupportedBandCombinations(
                        mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName());
                if (bandsSet == null) {
                    listener.onResult(new WifiBands[0]);
                    return;
                }
                WifiBands[] supportedBands = new WifiBands[bandsSet.size()];
                int i = 0;
                for (List<Integer> bands : bandsSet) {
                    supportedBands[i] = new WifiBands();
                    supportedBands[i].bands = bands.stream().mapToInt(
                            Integer::intValue).toArray();
                    i++;
                }
                listener.onResult(supportedBands);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getSupportedSimultaneousBandCombinations");
    }

    /**
     * Set the mock wifi service for testing
     */
    public void setMockWifiService(String serviceName) {
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException(TAG + " Uid " + uid
                    + " Missing NETWORK_SETTINGS permission");
        }
        mWifiNative.setMockWifiService(serviceName);
    }

    /**
     * Set the mock wifi methods for testing
     */
    public boolean setMockWifiMethods(String methods) {
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException(TAG + " Uid " + uid
                    + " Missing NETWORK_SETTINGS permission");
        }
        return mWifiNative.setMockWifiMethods(methods);
    }

    /**
     * See {@link WifiManager#setWepAllowed(boolean)}.
     */
    @Override
    public void setWepAllowed(boolean isAllowed) {
        int callingUid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to set wifi web allowed by user");
        }
        mLog.info("setWepAllowed=% uid=%").c(isAllowed).c(callingUid).flush();
        mSettingsConfigStore.put(WIFI_WEP_ALLOWED, isAllowed);
    }

    /**
     * @deprecated Use mWepNetworkUsageController.handleWepAllowedChanged() instead.
     */
    private void handleWepAllowedChanged(boolean isAllowed) {
        mWifiGlobals.setWepAllowed(isAllowed);
        if (!isAllowed) {
            for (ClientModeManager clientModeManager
                    : mActiveModeWarden.getClientModeManagers()) {
                if (!(clientModeManager instanceof ConcreteClientModeManager)) {
                    continue;
                }
                ConcreteClientModeManager cmm = (ConcreteClientModeManager) clientModeManager;
                WifiInfo info = cmm.getConnectionInfo();
                if (info != null
                        && info.getCurrentSecurityType() == WifiInfo.SECURITY_TYPE_WEP) {
                    clientModeManager.disconnect();
                }
            }
        }
    }

    /**
     * See {@link WifiManager#queryWepAllowed(Executor, Consumer)}
     */
    @Override
    public void queryWepAllowed(@NonNull IBooleanListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        int callingUid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to get wifi web allowed by user");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mSettingsConfigStore.get(WIFI_WEP_ALLOWED));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#setWepAllowed");
    }

    /**
     * See {@link WifiManager#enableMscs(MscsParams)}
     */
    @Override
    public void enableMscs(@NonNull MscsParams mscsParams) {
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException(
                    "UID=" + uid + " is not allowed to set network selection config");
        }
        Objects.requireNonNull(mscsParams);
        mWifiThreadRunner.post(() -> {
            List<ClientModeManager> clientModeManagers =
                    mActiveModeWarden.getInternetConnectivityClientModeManagers();
            for (ClientModeManager cmm : clientModeManagers) {
                mWifiNative.enableMscs(mscsParams, cmm.getInterfaceName());
            }
        }, TAG + "#enableMscs");
    }

    /**
     * See {@link WifiManager#disableMscs()}
     */
    @Override
    public void disableMscs() {
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException(
                    "UID=" + uid + " is not allowed to set network selection config");
        }
        mWifiThreadRunner.post(() -> {
            List<ClientModeManager> clientModeManagers =
                    mActiveModeWarden.getInternetConnectivityClientModeManagers();
            for (ClientModeManager cmm : clientModeManagers) {
                mWifiNative.disableMscs(cmm.getInterfaceName());
            }
        }, TAG + "#disableMscs");
    }

    /**
     * See {@link android.net.wifi.WifiManager#setSendDhcpHostnameRestriction(int)}.
     */
    public void setSendDhcpHostnameRestriction(@NonNull String packageName,
            @WifiManager.SendDhcpHostnameRestriction int restriction) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        if (mVerboseLoggingEnabled) {
            mLog.info("setSendDhcpHostnameRestriction:% uid=% package=%").c(restriction)
                    .c(callingUid).c(packageName).flush();
        }
        if ((restriction
                & ~WifiManager.FLAG_SEND_DHCP_HOSTNAME_RESTRICTION_OPEN
                & ~WifiManager.FLAG_SEND_DHCP_HOSTNAME_RESTRICTION_SECURE) != 0) {
            throw new IllegalArgumentException("Unknown dhcp hostname restriction flags: "
                    + restriction);
        }
        if (!isSettingsOrSuw(callingPid, callingUid)
                && !mWifiPermissionsUtil.isDeviceOwner(callingUid, packageName)) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to query the global dhcp hostname restriction");
        }
        mWifiThreadRunner.post(() -> mWifiGlobals.setSendDhcpHostnameRestriction(restriction),
                TAG + "#setSendDhcpHostnameRestriction");
    }

    /**
     * See {@link WifiManager#querySendDhcpHostnameRestriction(Executor, IntConsumer)}
     */
    @Override
    public void querySendDhcpHostnameRestriction(@NonNull String packageName,
            @NonNull IIntegerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        if (mVerboseLoggingEnabled) {
            mLog.info("querySendDhcpHostnameRestriction: uid=% package=%")
                    .c(callingUid).c(packageName).flush();
        }
        if (!isSettingsOrSuw(callingPid, callingUid)
                && !mWifiPermissionsUtil.isDeviceOwner(callingUid, packageName)) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to query the global dhcp hostname restriction");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiGlobals.getSendDhcpHostnameRestriction());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#querySendDhcpHostnameRestriction");
    }

    /**
     * See {@link WifiManager#setPerSsidRoamingMode(WifiSsid, int)}
     */
    @Override
    public void setPerSsidRoamingMode(WifiSsid ssid, @RoamingMode int roamingMode,
            @NonNull String packageName) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        if (!isAggressiveRoamingModeSupported() && roamingMode == ROAMING_MODE_AGGRESSIVE) {
            throw new UnsupportedOperationException("Aggressive roaming mode not supported");
        }
        Objects.requireNonNull(ssid, "ssid cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");

        if (roamingMode < WifiManager.ROAMING_MODE_NONE
                || roamingMode > WifiManager.ROAMING_MODE_AGGRESSIVE) {
            throw new IllegalArgumentException("invalid roaming mode: " + roamingMode);
        }

        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        boolean isDeviceOwner = mWifiPermissionsUtil.isOrganizationOwnedDeviceAdmin(
                uid, packageName);
        if (!isDeviceOwner && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed to add roaming policies");
        }

        //Store Roaming Mode per ssid
        mWifiThreadRunner.post(() -> {
            mWifiInjector.getWifiRoamingModeManager().setPerSsidRoamingMode(ssid,
                    roamingMode, isDeviceOwner);
        }, TAG + "#setPerSsidRoamingMode");
    }

    /**
     * See {@link WifiManager#removePerSsidRoamingMode(WifiSsid)}
     */
    @Override
    public void removePerSsidRoamingMode(WifiSsid ssid, @NonNull String packageName) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        Objects.requireNonNull(ssid, "ssid cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");

        int uid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        boolean isDeviceOwner = mWifiPermissionsUtil.isOrganizationOwnedDeviceAdmin(
                uid, packageName);
        if (!isDeviceOwner && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed "
                    + "to remove roaming policies");
        }

        // Remove Roaming Mode per ssid
        mWifiThreadRunner.post(() -> {
            mWifiInjector.getWifiRoamingModeManager().removePerSsidRoamingMode(
                    ssid, isDeviceOwner);
        }, TAG + "#removePerSsidRoamingMode");
    }

    /**
     * See {@link WifiManager#getPerSsidRoamingModes(Executor, Consumer)}
     */
    @Override
    public void getPerSsidRoamingModes(@NonNull String packageName,
            @NonNull IMapListener listener) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        Objects.requireNonNull(packageName, "packageName cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");

        int uid = Binder.getCallingUid();
        boolean isDeviceOwner = mWifiPermissionsUtil.isOrganizationOwnedDeviceAdmin(
                uid, packageName);
        mWifiPermissionsUtil.checkPackage(uid, packageName);
        if (!isDeviceOwner && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("Uid=" + uid + " is not allowed to get roaming policies");
        }

        // Get Roaming Modes per ssid
        mWifiThreadRunner.post(() -> {
            try {
                Map<String, Integer> roamingPolicies =
                        mWifiInjector.getWifiRoamingModeManager().getPerSsidRoamingModes(
                                isDeviceOwner);
                listener.onResult(roamingPolicies);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getPerSsidRoamingModes");
    }

    /**
     * See {@link WifiManager#getTwtCapabilities(Executor, Consumer)}
     */
    @Override
    public void getTwtCapabilities(ITwtCapabilitiesListener listener, Bundle extras) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        enforceAnyPermissionOf(android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION);
        if (mVerboseLoggingEnabled) {
            mLog.info("getTwtCapabilities:  Uid=% Package Name=%").c(Binder.getCallingUid()).c(
                    getPackageName(extras)).flush();
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        mWifiThreadRunner.post(() -> {
            mTwtManager.getTwtCapabilities(
                    mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName(), listener);
        }, TAG + "#getTwtCapabilities");
    }

    /**
     * See {@link WifiManager#setupTwtSession(TwtRequest, Executor, TwtSessionCallback)}
     */
    @Override
    public void setupTwtSession(TwtRequest twtRequest, ITwtCallback iTwtCallback, Bundle extras) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        if (iTwtCallback == null) {
            throw new IllegalArgumentException("Callback should not be null");
        }
        if (twtRequest == null) {
            throw new IllegalArgumentException("twtRequest should not be null");
        }
        enforceAnyPermissionOf(android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION);
        int callingUid = Binder.getCallingUid();
        if (mVerboseLoggingEnabled) {
            mLog.info("setupTwtSession:  Uid=% Package Name=%").c(callingUid).c(
                    getPackageName(extras)).flush();
        }
        mWifiThreadRunner.post(() -> {
            try {
                String bssid = mActiveModeWarden.getPrimaryClientModeManager().getConnectedBssid();
                if (!mActiveModeWarden.getPrimaryClientModeManager().isConnected()
                        || bssid == null) {
                    iTwtCallback.onFailure(TwtSessionCallback.TWT_ERROR_CODE_NOT_AVAILABLE);
                    return;
                }
                mTwtManager.setupTwtSession(
                        mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName(),
                        twtRequest, iTwtCallback, callingUid, bssid);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#setupTwtSession");
    }

    /**
    /**
     * See {@link TwtSession#getStats(Executor, Consumer)}}
     */
    @Override
    public void getStatsTwtSession(int sessionId, ITwtStatsListener iTwtStatsListener,
            Bundle extras) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        if (iTwtStatsListener == null) {
            throw new IllegalArgumentException("Callback should not be null");
        }
        enforceAnyPermissionOf(android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION);
        if (mVerboseLoggingEnabled) {
            mLog.info("getStatsTwtSession:  Uid=% Package Name=%").c(Binder.getCallingUid()).c(
                    getPackageName(extras)).flush();
        }
        mWifiThreadRunner.post(() -> {
            mTwtManager.getStatsTwtSession(
                    mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName(),
                    iTwtStatsListener, sessionId);
        }, TAG + "#getStatsTwtSession");
    }

    /**
     * See {@link TwtSession#teardown()}
     */
    @Override
    public void teardownTwtSession(int sessionId, Bundle extras) {
        if (!SdkLevel.isAtLeastV()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        enforceAnyPermissionOf(android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION);
        if (mVerboseLoggingEnabled) {
            mLog.info("teardownTwtSession:  Uid=% Package Name=%").c(Binder.getCallingUid()).c(
                    getPackageName(extras)).flush();
        }
        mWifiThreadRunner.post(() -> {
            mTwtManager.tearDownTwtSession(
                    mActiveModeWarden.getPrimaryClientModeManager().getInterfaceName(), sessionId);
        }, TAG + "#teardownTwtSession");
    }

    /**
     * See {@link WifiManager#setD2dAllowedWhenInfraStaDisabled(boolean)}.
     */
    @Override
    public void setD2dAllowedWhenInfraStaDisabled(boolean isAllowed) {
        int callingUid = Binder.getCallingUid();
        if (!isSettingsOrSuw(Binder.getCallingPid(), callingUid)) {
            throw new SecurityException("Uid " + callingUid
                    + " is not allowed to set d2d allowed when infra Sta is disabled");
        }
        mLog.info("setD2dAllowedWhenInfraStaDisabled=% uid=%").c(isAllowed).c(callingUid).flush();
        mWifiThreadRunner.post(
                () -> mSettingsConfigStore.put(D2D_ALLOWED_WHEN_INFRA_STA_DISABLED, isAllowed),
                TAG + "#setD2dAllowedWhenInfraStaDisabled");
    }

    /**
     * See {@link WifiManager#queryD2dAllowedWhenInfraStaDisabled(Executor, Consumer)}
     */
    @Override
    public void queryD2dAllowedWhenInfraStaDisabled(@NonNull IBooleanListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener should not be null");
        }
        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mSettingsConfigStore.get(D2D_ALLOWED_WHEN_INFRA_STA_DISABLED));
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#queryD2dAllowedWhenInfraStaDisabled");
    }

    /**
     * See {@link WifiManager#setAutojoinDisallowedSecurityTypes(int)}
     * @param restrictions The autojoin restriction security types to be set.
     * @throws SecurityException if the caller does not have permission.
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void setAutojoinDisallowedSecurityTypes(int restrictions, @NonNull Bundle extras) {
        // SDK check.
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        // Check null argument
        if (extras == null) {
            throw new IllegalArgumentException("extras cannot be null");
        }
        // Check invalid argument
        if ((restrictions & (0x1 << WifiInfo.SECURITY_TYPE_OPEN)) == 0
                && (restrictions & (0x1 << WifiInfo.SECURITY_TYPE_OWE)) != 0) {
            throw new IllegalArgumentException("Restricting OWE but not OPEN is not allowed");
        }
        if ((restrictions & (0x1 << WifiInfo.SECURITY_TYPE_PSK)) == 0
                && (restrictions & (0x1 << WifiInfo.SECURITY_TYPE_SAE)) != 0) {
            throw new IllegalArgumentException("Restricting SAE but not PSK is not allowed");
        }
        if ((restrictions & (0x1 << WifiInfo.SECURITY_TYPE_EAP)) == 0
                && (restrictions & (0x1 << WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE)) != 0) {
            throw new IllegalArgumentException(
                    "Restricting EAP_WPA3_ENTERPRISE but not EAP is not allowed");
        }
        // Permission check.
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException(
                    "Uid=" + uid + " is not allowed to set AutoJoinRestrictionSecurityTypes");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("setAutojoinDisallowedSecurityTypes uid=% Package Name=% restrictions=%")
                    .c(uid).c(getPackageName(extras)).c(restrictions).flush();
        }
        mWifiThreadRunner.post(() -> {
            mWifiConnectivityManager.setAutojoinDisallowedSecurityTypes(restrictions);
        }, TAG + "#setAutojoinDisallowedSecurityTypes");
    }

    /**
     * See {@link WifiManager#getAutojoinDisallowedSecurityTypes(Executor, Consumer)}
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void getAutojoinDisallowedSecurityTypes(@NonNull IIntegerListener listener,
            @NonNull Bundle extras) {
        // SDK check.
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        // Argument check
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (extras == null) {
            throw new IllegalArgumentException("extras cannot be null");
        }
        // Permission check.
        int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException(
                    "Uid=" + uid + " is not allowed to get AutoJoinRestrictionSecurityTypes");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getAutojoinDisallowedSecurityTypes:  Uid=% Package Name=%").c(
                    Binder.getCallingUid()).c(getPackageName(extras)).flush();
        }

        mWifiThreadRunner.post(() -> {
            try {
                listener.onResult(mWifiConnectivityManager.getAutojoinDisallowedSecurityTypes());
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }, TAG + "#getAutojoinDisallowedSecurityTypes");
    }

    @Override
    public void disallowCurrentSuggestedNetwork(@NonNull BlockingOption option,
            @NonNull String packageName) {
        Objects.requireNonNull(option, "blockingOption cannot be null");
        int callingUid = Binder.getCallingUid();
        mWifiPermissionsUtil.checkPackage(callingUid, packageName);
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            throw new SecurityException("Caller does not hold CHANGE_WIFI_STATE permission");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("disallowCurrentSuggestedNetwork:  Uid=% Package Name=%").c(
                    callingUid).c(option.toString()).flush();
        }
        if (mActiveModeWarden.getWifiState() != WIFI_STATE_ENABLED) {
            return;
        }
        WifiInfo info = mActiveModeWarden.getConnectionInfo();
        if (!packageName.equals(info.getRequestingPackageName())) {
            return;
        }
        mWifiThreadRunner.post(
                () -> mActiveModeWarden.getPrimaryClientModeManager().blockNetwork(option),
                "disallowCurrentSuggestedNetwork");
    }

    /**
     * See {@link WifiManager#isUsdSubscriberSupported()}
     */
    @Override
    public boolean isUsdSubscriberSupported() {
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        int uid = getMockableCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("App not allowed to use USD (uid = " + uid + ")");
        }
        if (!mIsUsdSupported) {
            return false;
        }
        return mWifiNative.isUsdSubscriberSupported();
    }

    /**
     * See {@link WifiManager#isUsdPublisherSupported()}
     */
    @Override
    public boolean isUsdPublisherSupported() {
        if (!Environment.isSdkAtLeastB()) {
            throw new UnsupportedOperationException("SDK level too old");
        }
        int uid = getMockableCallingUid();
        if (!mWifiPermissionsUtil.checkManageWifiNetworkSelectionPermission(uid)) {
            throw new SecurityException("App not allowed to use USD (uid = " + uid + ")");
        }
        if (!mIsUsdSupported) {
            return false;
        }
        return mWifiNative.isUsdPublisherSupported();
    }
}
