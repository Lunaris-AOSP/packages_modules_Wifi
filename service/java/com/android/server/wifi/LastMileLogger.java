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


import android.os.Handler;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Provides a facility for capturing kernel trace events related to Wifi control and data paths.
 */
public class LastMileLogger {
    private final Handler mBackgroundHandler;
    public LastMileLogger(WifiInjector injector, Handler handler) {
        mBackgroundHandler = handler;
        File tracefsEnablePath = new File(WIFI_EVENT_ENABLE_PATH);
        if (tracefsEnablePath.exists()) {
            initLastMileLogger(injector, WIFI_EVENT_BUFFER_PATH, WIFI_EVENT_ENABLE_PATH,
                    WIFI_EVENT_RELEASE_PATH);
        } else {
            initLastMileLogger(injector, WIFI_EVENT_BUFFER_PATH_DEBUGFS,
                    WIFI_EVENT_ENABLE_PATH_DEBUGFS, WIFI_EVENT_RELEASE_PATH_DEBUGFS);
        }
    }

    @VisibleForTesting
    public LastMileLogger(WifiInjector injector, String bufferPath, String enablePath,
                          String releasePath, Handler handler) {
        mBackgroundHandler = handler;
        initLastMileLogger(injector, bufferPath, enablePath, releasePath);
    }

    /**
     * Informs LastMileLogger that a connection event has occurred.
     * @param event an event defined in WifiDiagnostics
     */
    public void reportConnectionEvent(String ifaceName, byte event) {
        boolean wasTracingEnabled = anyConnectionInProgress();

        mIfaceToConnectionStatus.put(ifaceName, event);

        boolean shouldTracingBeEnabled = anyConnectionInProgress();

        mBackgroundHandler.post(() -> {
            if (!wasTracingEnabled && shouldTracingBeEnabled) {
                enableTracing();
            } else if (wasTracingEnabled && !shouldTracingBeEnabled) {
                disableTracing();
            }
            if (event == WifiDiagnostics.CONNECTION_EVENT_FAILED
                    || event == WifiDiagnostics.CONNECTION_EVENT_TIMEOUT) {
                mLastMileLogForLastFailure = readTrace();
            }
        });
    }

    private boolean anyConnectionInProgress() {
        for (byte status : mIfaceToConnectionStatus.values()) {
            if (status == WifiDiagnostics.CONNECTION_EVENT_STARTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dumps the contents of the log.
     * @param pw the PrintWriter that will receive the dump
     */
    public void dump(PrintWriter pw) {
        dumpInternal(pw, "Last failed last-mile log", mLastMileLogForLastFailure);
        dumpInternal(pw, "Latest last-mile log", readTrace());
    }

    private static final String TAG = "LastMileLogger";
    private static final String WIFI_EVENT_BUFFER_PATH =
            "/sys/kernel/tracing/instances/wifi/trace";
    private static final String WIFI_EVENT_ENABLE_PATH =
            "/sys/kernel/tracing/instances/wifi/tracing_on";
    private static final String WIFI_EVENT_RELEASE_PATH =
            "/sys/kernel/tracing/instances/wifi/free_buffer";
    private static final String WIFI_EVENT_BUFFER_PATH_DEBUGFS =
            "/sys/kernel/debug/tracing/instances/wifi/trace";
    private static final String WIFI_EVENT_ENABLE_PATH_DEBUGFS =
            "/sys/kernel/debug/tracing/instances/wifi/tracing_on";
    private static final String WIFI_EVENT_RELEASE_PATH_DEBUGFS =
            "/sys/kernel/debug/tracing/instances/wifi/free_buffer";

    private String mEventBufferPath;
    private String mEventEnablePath;
    private String mEventReleasePath;
    private WifiLog mLog;
    private byte[] mLastMileLogForLastFailure;
    private FileInputStream mLastMileTraceHandle;
    /**
     * String key: iface name
     * byte value: Connection status, one of WifiDiagnostics.CONNECTION_EVENT_*
     */
    private final Map<String, Byte> mIfaceToConnectionStatus = new ArrayMap<>();

    private void initLastMileLogger(WifiInjector injector, String bufferPath, String enablePath,
                          String releasePath) {
        mLog = injector.makeLog(TAG);
        mEventBufferPath = bufferPath;
        mEventEnablePath = enablePath;
        mEventReleasePath = releasePath;
    }

    private void enableTracing() {
        if (!ensureFailSafeIsArmed()) {
            mLog.wC("Failed to arm fail-safe.");
            return;
        }

        try {
            FileUtils.stringToFile(mEventEnablePath, "1");
        } catch (IOException e) {
            mLog.warn("Failed to start event tracing: %").r(e.getMessage()).flush();
        }
    }

    private void disableTracing() {
        try {
            FileUtils.stringToFile(mEventEnablePath, "0");
        } catch (IOException e) {
            mLog.warn("Failed to stop event tracing: %").r(e.getMessage()).flush();
        }
    }

    private byte[] readTrace() {
        try {
            return Files.readAllBytes(Paths.get(mEventBufferPath));
        } catch (IOException e) {
            mLog.warn("Failed to read event trace: %").r(e.getMessage()).flush();
            return new byte[0];
        }
    }

    private boolean ensureFailSafeIsArmed() {
        if (mLastMileTraceHandle != null) {
            return true;
        }

        try {
            // This file provides fail-safe behavior for Last-Mile logging. Given that we:
            // 1. Set the disable_on_free option in the trace_options pseudo-file
            //    (see wifi-events.rc), and
            // 2. Hold the WIFI_EVENT_RELEASE_PATH open,
            //
            // Then, when this process dies, the kernel will automatically disable any
            // tracing in the wifi trace instance.
            //
            // Note that, despite Studio's suggestion that |mLastMileTraceHandle| could be demoted
            // to a local variable, we need to stick with a field. Otherwise, the handle could be
            // garbage collected.
            mLastMileTraceHandle = new FileInputStream(mEventReleasePath);
            return true;
        } catch (IOException e) {
            mLog.warn("Failed to open free_buffer pseudo-file: %").r(e.getMessage()).flush();
            return false;
        }
    }

    private static void dumpInternal(PrintWriter pw, String description, byte[] lastMileLog) {
        if (lastMileLog == null || lastMileLog.length < 1) {
            pw.format("No last mile log for \"%s\"\n", description);
            return;
        }

        pw.format("-------------------------- %s ---------------------------\n", description);
        pw.print(new String(lastMileLog));
        pw.println("--------------------------------------------------------------------");
    }
}
