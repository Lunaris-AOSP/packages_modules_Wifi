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

import android.content.Context;
import android.net.wifi.WifiContext;
import android.net.wifi.aware.WifiAwareManager;
import android.os.HandlerThread;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.util.WifiPermissionsUtil;

/**
 * TBD.
 */
public class RttService extends SystemService {
    private static final String TAG = "RttService";
    private RttServiceImpl mImpl;

    public RttService(Context contextBase) {
        super(new WifiContext(contextBase));
        mImpl = new RttServiceImpl(getContext());
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering " + Context.WIFI_RTT_RANGING_SERVICE);
        publishBinderService(Context.WIFI_RTT_RANGING_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.i(TAG, "Starting " + Context.WIFI_RTT_RANGING_SERVICE);

            WifiInjector wifiInjector = WifiInjector.getInstance();
            if (wifiInjector == null) {
                Log.e(TAG, "onBootPhase(PHASE_SYSTEM_SERVICES_READY): NULL injector!");
                return;
            }

            HandlerThread handlerThread = wifiInjector.getWifiHandlerThread();
            WifiPermissionsUtil wifiPermissionsUtil = wifiInjector.getWifiPermissionsUtil();
            RttMetrics rttMetrics = wifiInjector.getWifiMetrics().getRttMetrics();
            WifiAwareManager awareManager = getContext().getSystemService(WifiAwareManager.class);

            mImpl.start(handlerThread.getLooper(), wifiInjector.getClock(), awareManager,
                    rttMetrics, wifiPermissionsUtil, wifiInjector.getSettingsConfigStore(),
                    wifiInjector.getHalDeviceManager(), wifiInjector.getWifiConfigManager(),
                    wifiInjector.getSsidTranslator());
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mImpl.handleBootCompleted();
        }
    }
}
