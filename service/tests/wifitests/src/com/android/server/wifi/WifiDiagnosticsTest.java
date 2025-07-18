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

package com.android.server.wifi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.gt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BugreportManager;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Unit tests for {@link WifiDiagnostics}.
 */
@SmallTest
public class WifiDiagnosticsTest extends WifiBaseTest {
    @Mock WifiNative mWifiNative;
    @Mock BuildProperties mBuildProperties;
    @Mock Context mContext;
    @Mock WifiInjector mWifiInjector;
    @Mock DeviceConfigFacade mDeviceConfigFacade;
    @Spy FakeWifiLog mLog;
    @Mock LastMileLogger mLastMileLogger;
    @Mock Runtime mJavaRuntime;
    @Mock Process mExternalProcess;
    @Mock WifiMetrics mWifiMetrics;
    @Mock Clock mClock;
    @Mock BugreportManager mBugreportManager;
    @Mock WifiScoreCard mWifiScoreCard;
    @Mock ActiveModeWarden mActiveModeWarden;
    @Mock ClientModeManager mClientModeManager;
    @Mock ClientModeManager mClientModeManager2;
    @Mock PackageManager mPackageManager;
    @Mock List<ResolveInfo>  mResolveInfoList;
    private long mBootTimeMs = 0L;
    MockResources mResources;
    WifiDiagnostics mWifiDiagnostics;
    TestLooper mTestLooper;

    private static final String FAKE_RING_BUFFER_NAME = "fake-ring-buffer";
    private static final String STA_IF_NAME = "wlan0";
    private static final String AP_IF_NAME = "wlan1";
    private static final int SMALL_RING_BUFFER_SIZE_KB = 32;
    private static final int LARGE_RING_BUFFER_SIZE_KB = 1024;
    private static final int BYTES_PER_KBYTE = 1024;
    private static final int ALERT_REASON_CODE = 1;
    private static final byte[] ALERT_DATA = {0 , 4, 5};
    /** Mock resource for fatal firmware alert list */
    private static final int[] FATAL_FW_ALERT_LIST = {256, 257, 258};
    /** Mock a non fatal firmware alert */
    private static final int NON_FATAL_FW_ALERT = 0;
    private static final int BUG_REPORT_MIN_WINDOW_MS = 3600_000;

    private WifiNative.RingBufferStatus mFakeRbs;
    /**
     * Returns the data that we would dump in a bug report, for our ring buffer.
     * @return a 2-D byte array, where the first dimension is the record number, and the second
     * dimension is the byte index within that record.
     */
    private final byte[][] getLoggerRingBufferData() throws Exception {
        return mWifiDiagnostics.getBugReports().get(0).ringBuffers.get(FAKE_RING_BUFFER_NAME);
    }

    /**
     * Initializes common state (e.g. mocks) needed by test cases.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestLooper = new TestLooper();

        mFakeRbs = new WifiNative.RingBufferStatus();
        mFakeRbs.name = FAKE_RING_BUFFER_NAME;
        WifiNative.RingBufferStatus[] ringBufferStatuses = new WifiNative.RingBufferStatus[] {
                mFakeRbs
        };

        when(mWifiNative.getRingBufferStatus()).thenReturn(ringBufferStatuses);
        when(mBuildProperties.isEngBuild()).thenReturn(false);
        when(mBuildProperties.isUserdebugBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(true);
        when(mExternalProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mExternalProcess.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mJavaRuntime.exec(anyString())).thenReturn(mExternalProcess);

        List<ClientModeManager> clientModeManagerList = List.of(mClientModeManager,
                mClientModeManager2);
        when(mClientModeManager.getInterfaceName()).thenReturn(STA_IF_NAME);

        mResources = new MockResources();
        mResources.setInteger(R.integer.config_wifi_logger_ring_buffer_default_size_limit_kb,
                SMALL_RING_BUFFER_SIZE_KB);
        mResources.setInteger(R.integer.config_wifi_logger_ring_buffer_verbose_size_limit_kb,
                LARGE_RING_BUFFER_SIZE_KB);
        mResources.setIntArray(R.array.config_wifi_fatal_firmware_alert_error_code_list,
                FATAL_FW_ALERT_LIST);
        mResources.setBoolean(R.bool.config_wifi_diagnostics_bugreport_enabled, true);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getSystemService(BugreportManager.class)).thenReturn(mBugreportManager);
        when(mWifiInjector.makeLog(anyString())).thenReturn(mLog);
        when(mWifiInjector.getJavaRuntime()).thenReturn(mJavaRuntime);
        when(mWifiInjector.getWifiMetrics()).thenReturn(mWifiMetrics);
        when(mWifiInjector.getDeviceConfigFacade()).thenReturn(mDeviceConfigFacade);
        when(mWifiInjector.getWifiScoreCard()).thenReturn(mWifiScoreCard);
        when(mWifiInjector.getActiveModeWarden()).thenReturn(mActiveModeWarden);
        when(mActiveModeWarden.getPrimaryClientModeManager()).thenReturn(mClientModeManager);
        when(mActiveModeWarden.getClientModeManagers()).thenReturn(clientModeManagerList);
        when(mDeviceConfigFacade.getBugReportMinWindowMs()).thenReturn(BUG_REPORT_MIN_WINDOW_MS);
        when(mDeviceConfigFacade.getDisabledAutoBugreportTitleAndDetails()).thenReturn(
                Collections.EMPTY_SET);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(mResolveInfoList);
        // needed to for the loop in WifiDiagnostics.readLogcatStreamLinesWithTimeout().
        doAnswer(new AnswerWithArguments() {
            public long answer() throws Exception {
                mBootTimeMs += WifiDiagnostics.LOGCAT_READ_TIMEOUT_MILLIS / 2;
                return mBootTimeMs;
            }
        }).when(mClock).getElapsedSinceBootMillis();
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, mWifiInjector, mWifiNative, mBuildProperties, mLastMileLogger, mClock,
                mTestLooper.getLooper());
        mWifiNative.enableVerboseLogging(false, false);
    }

    /** Verifies that startLogging() registers a logging event handler. */
    @Test
    public void startLoggingRegistersLogEventHandler() throws Exception {
        mWifiDiagnostics.enableVerboseLogging(false, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative).setLoggingEventHandler(any());
    }

    /**
     * Verifies that a failure to set the logging event handler does not prevent a future
     * startLogging() from setting the logging event handler.
     */
    @Test
    public void startLoggingRegistersLogEventHandlerIfPriorAttemptFailed()
            throws Exception {
        final boolean verbosityToggle = false;  // even default mode registers handler

        when(mWifiNative.setLoggingEventHandler(any())).thenReturn(false);
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative).setLoggingEventHandler(any());
        mWifiDiagnostics.stopLogging(STA_IF_NAME);
        reset(mWifiNative);

        when(mWifiNative.setLoggingEventHandler(any())).thenReturn(true);
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative).setLoggingEventHandler(any());
    }

    /** Verifies that startLogging() does not make redundant calls to setLoggingEventHandler(). */
    @Test
    public void startLoggingDoesNotRegisterLogEventHandlerIfPriorAttemptSucceeded()
            throws Exception {
        when(mWifiNative.setLoggingEventHandler(any())).thenReturn(true);
        mWifiDiagnostics.enableVerboseLogging(false, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative).setLoggingEventHandler(any());
        reset(mWifiNative);

        mWifiDiagnostics.enableVerboseLogging(false, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative, never()).setLoggingEventHandler(any());
    }

    /**
     * Verifies that startLogging() restarts HAL ringbuffers.
     *
     * Specifically: verifies that startLogging()
     * a) stops any ring buffer logging that might be already running,
     * b) instructs WifiNative to enable ring buffers of the appropriate log level.
     */
    @Test
    public void startLoggingStopsAndRestartsRingBufferLoggingInVerboseMode() throws Exception {
        final boolean verbosityToggle = true;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative).startLoggingRingBuffer(
                eq(WifiDiagnostics.VERBOSE_NO_LOG), anyInt(), anyInt(), anyInt(),
                eq(FAKE_RING_BUFFER_NAME));
        verify(mWifiNative).startLoggingRingBuffer(
                eq(WifiDiagnostics.VERBOSE_LOG_WITH_WAKEUP), anyInt(), anyInt(), anyInt(),
                eq(FAKE_RING_BUFFER_NAME));
    }

    @Test
    public void startLoggingStopsAndThenStartRingBufferLoggingInNormalMode() throws Exception {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative).startLoggingRingBuffer(
                eq(WifiDiagnostics.VERBOSE_NO_LOG), anyInt(), anyInt(), anyInt(),
                eq(FAKE_RING_BUFFER_NAME));
        verify(mWifiNative).startLoggingRingBuffer(
                gt(WifiDiagnostics.VERBOSE_NO_LOG), anyInt(), anyInt(), anyInt(),
                anyString());
    }

    /** Verifies that, if a log handler was registered, then stopLogging() resets it. */
    @Test
    public void stopLoggingResetsLogHandlerIfHandlerWasRegistered() throws Exception {
        final boolean verbosityToggle = false;  // even default mode registers handler

        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        when(mWifiNative.setLoggingEventHandler(any())).thenReturn(true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        reset(mWifiNative);

        mWifiDiagnostics.stopLogging(STA_IF_NAME);
        verify(mWifiNative).resetLogHandler();
    }

    /** Verifies that, if a log handler is not registered, stopLogging() skips resetLogHandler(). */
    @Test
    public void stopLoggingOnlyResetsLogHandlerIfHandlerWasRegistered() throws Exception {
        mWifiDiagnostics.stopLogging(STA_IF_NAME);
        verify(mWifiNative, never()).resetLogHandler();
    }

    /** Verifies that stopLogging() remembers that we've reset the log handler. */
    @Test
    public void multipleStopLoggingCallsOnlyResetLogHandlerOnce() throws Exception {
        final boolean verbosityToggle = false;  // even default mode registers handler

        when(mWifiNative.setLoggingEventHandler(any())).thenReturn(true);
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        reset(mWifiNative);

        when(mWifiNative.resetLogHandler()).thenReturn(true);
        mWifiDiagnostics.stopLogging(STA_IF_NAME);
        verify(mWifiNative).resetLogHandler();
        reset(mWifiNative);

        mWifiDiagnostics.stopLogging(STA_IF_NAME);
        verify(mWifiNative, never()).resetLogHandler();
    }

    /**
     * Verifies that we capture ring-buffer data.
     */
    @Test
    public void canCaptureAndStoreRingBufferData() throws Exception {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);

        final byte[] data = new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE];
        mWifiDiagnostics.onRingBufferData(mFakeRbs, data);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData = getLoggerRingBufferData();
        assertEquals(1, ringBufferData.length);
        assertArrayEquals(data, ringBufferData[0]);
    }

    /**
     * Verifies that we discard extraneous ring-buffer data.
     */
    @Ignore("TODO(b/36811399): re-enabled this @Test")
    @Test
    public void loggerDiscardsExtraneousData() throws Exception {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);

        final byte[] data1 = new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE];
        final byte[] data2 = {1, 2, 3};
        mWifiDiagnostics.onRingBufferData(mFakeRbs, data1);
        mWifiDiagnostics.onRingBufferData(mFakeRbs, data2);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData = getLoggerRingBufferData();
        assertEquals(1, ringBufferData.length);
        assertArrayEquals(data2, ringBufferData[0]);
    }

    // Verifies that startPktFateMonitoring(any()) reports failure to start packet fate
    @Test
    public void startPktFateMonitoringReportsStartFailures() {
        when(mWifiNative.startPktFateMonitoring(any())).thenReturn(false);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        verify(mLog).wC(contains("Failed"));
    }

    /**
     * Verifies that, when verbose mode is not enabled,
     * reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED) still fetches packet fates.
     */
    @Test
    public void reportConnectionFailureIsIgnoredWithoutVerboseMode() {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);
        verify(mClientModeManager).getTxPktFates();
        verify(mClientModeManager).getRxPktFates();
    }

    /**
     * Verifies that, when verbose mode is enabled, reportConnectionFailure() fetches packet fates.
     */
    @Test
    public void reportConnectionFailureFetchesFatesInVerboseMode() {
        final boolean verbosityToggle = true;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);
        verify(mClientModeManager).getTxPktFates();
        verify(mClientModeManager).getRxPktFates();
    }

    @Test
    public void reportConnectionEventPropagatesStartToLastMileLogger() {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_STARTED,
                mClientModeManager);
        verify(mLastMileLogger).reportConnectionEvent(
                STA_IF_NAME, WifiDiagnostics.CONNECTION_EVENT_STARTED);
    }

    @Test
    public void reportConnectionEventPropagatesSuccessToLastMileLogger() {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_SUCCEEDED,
                mClientModeManager);
        verify(mLastMileLogger).reportConnectionEvent(
                STA_IF_NAME, WifiDiagnostics.CONNECTION_EVENT_SUCCEEDED);
    }

    @Test
    public void reportConnectionEventPropagatesFailureToLastMileLogger() {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);
        verify(mLastMileLogger).reportConnectionEvent(
                STA_IF_NAME, WifiDiagnostics.CONNECTION_EVENT_FAILED);
    }

    /**
     * Verifies that we are propagating the CONNECTION_EVENT_TIMEOUT event to LastMileLogger.
     */
    @Test
    public void reportConnectionEventPropagatesTimeoutToLastMileLogger() {
        final boolean verbosityToggle = true;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_TIMEOUT,
                mClientModeManager);
        verify(mLastMileLogger).reportConnectionEvent(
                STA_IF_NAME, WifiDiagnostics.CONNECTION_EVENT_TIMEOUT);
    }

    /**
     * Verifies that we try to fetch TX fates, even if fetching RX fates failed.
     */
    @Test
    public void loggerFetchesTxFatesEvenIfFetchingRxFatesFails() {
        final boolean verbosityToggle = true;
        when(mClientModeManager.getRxPktFates()).thenReturn(new ArrayList<>());
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);
        verify(mClientModeManager).getTxPktFates();
        verify(mClientModeManager).getRxPktFates();
    }

    /**
     * Verifies that we try to fetch RX fates, even if fetching TX fates failed.
     */
    @Test
    public void loggerFetchesRxFatesEvenIfFetchingTxFatesFails() {
        final boolean verbosityToggle = true;
        when(mClientModeManager.getTxPktFates()).thenReturn(new ArrayList<>());
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);
        verify(mClientModeManager).getTxPktFates();
        verify(mClientModeManager).getRxPktFates();
        verify(mClientModeManager2, never()).getTxPktFates();
        verify(mClientModeManager2, never()).getRxPktFates();
    }

    /** Verifies that dump() fetches the latest fates from both ClientModeManager. */
    @Test
    public void dumpFetchesFates() {
        final boolean verbosityToggle = false;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});
        verify(mClientModeManager).getTxPktFates();
        verify(mClientModeManager).getRxPktFates();
        verify(mClientModeManager2).getTxPktFates();
        verify(mClientModeManager2).getRxPktFates();
    }

    /**
     * Verifies that dump() doesn't crash, or generate garbage, in the case where we haven't fetched
     * any fates.
     */
    @Test
    public void dumpSucceedsWhenNoFatesHaveNotBeenFetched() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});

        String fateDumpString = sw.toString();
        assertTrue(fateDumpString.contains("Last failed"));
        // Verify dump terminator is present
        assertTrue(fateDumpString.contains(
                "--------------------------------------------------------------------"));
    }

    /**
     * Verifies that dump() doesn't crash, or generate garbage, in the case where the fates that
     * the HAL-provided fates are empty.
     */
    @Test
    public void dumpSucceedsWhenFatesHaveBeenFetchedButAreEmpty() {
        final boolean verbosityToggle = true;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, verbosityToggle);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);
        verify(mClientModeManager).getTxPktFates();
        verify(mClientModeManager).getRxPktFates();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});

        String fateDumpString = sw.toString();
        assertTrue(fateDumpString.contains("Last failed"));
        // Verify dump terminator is present
        assertTrue(fateDumpString.contains(
                "--------------------------------------------------------------------"));
    }

    private String getDumpString(boolean verbose) {
        mWifiDiagnostics.enableVerboseLogging(verbose, verbose);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiNative.enableVerboseLogging(verbose, verbose);
        when(mClientModeManager.getTxPktFates()).thenReturn(Arrays.asList(
                new WifiNative.TxFateReport(
                        WifiLoggerHal.TX_PKT_FATE_ACKED, 2, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                ),
                new WifiNative.TxFateReport(
                        WifiLoggerHal.TX_PKT_FATE_ACKED, 0, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                )));
        when(mClientModeManager.getRxPktFates()).thenReturn(Arrays.asList(
                new WifiNative.RxFateReport(
                        WifiLoggerHal.RX_PKT_FATE_SUCCESS, 3, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                ),
                new WifiNative.RxFateReport(
                        WifiLoggerHal.RX_PKT_FATE_SUCCESS, 1, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                )));
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});
        return sw.toString();
    }

      /**
     * Verifies that dump() shows both TX, and RX fates in only table form, when verbose
     * logging is not enabled.
     */
    @Test
    public void dumpShowsTxAndRxFates() {
        final boolean verbosityToggle = false;
        String dumpString = getDumpString(verbosityToggle);
        assertTrue(dumpString.contains(WifiNative.FateReport.getTableHeader()));
        assertTrue(Pattern.compile("0 .* TX ").matcher(dumpString).find());
        assertTrue(Pattern.compile("1 .* RX ").matcher(dumpString).find());
        assertTrue(Pattern.compile("2 .* TX ").matcher(dumpString).find());
        assertTrue(Pattern.compile("3 .* RX ").matcher(dumpString).find());
        assertFalse(dumpString.contains("VERBOSE PACKET FATE DUMP"));
        assertFalse(dumpString.contains("Frame bytes"));
    }

    /**
     * Verifies that dump() shows both TX, and RX fates in table and verbose forms, when verbose
     * logging is enabled.
     */
    @Test
    public void dumpShowsTxAndRxFatesVerbose() {
        final boolean verbosityToggle = true;
        String dumpString = getDumpString(verbosityToggle);
        assertTrue(dumpString.contains(WifiNative.FateReport.getTableHeader()));
        assertTrue(Pattern.compile("0 .* TX ").matcher(dumpString).find());
        assertTrue(Pattern.compile("1 .* RX ").matcher(dumpString).find());
        assertTrue(Pattern.compile("2 .* TX ").matcher(dumpString).find());
        assertTrue(Pattern.compile("3 .* RX ").matcher(dumpString).find());
        assertTrue(dumpString.contains("VERBOSE PACKET FATE DUMP"));
        assertTrue(dumpString.contains("Frame bytes"));
    }

    /**
     * Verifies that dump() outputs frames in timestamp order, even though the HAL provided the
     * data out-of-order (order is specified in getDumpString()).
     */
    @Test
    public void dumpIsSortedByTimestamp() {
        final boolean verbosityToggle = true;
        String dumpString = getDumpString(verbosityToggle);
        assertTrue(dumpString.contains(WifiNative.FateReport.getTableHeader()));
        assertTrue(Pattern.compile(
                "0 .* TX .*\n" +
                "1 .* RX .*\n" +
                "2 .* TX .*\n" +
                "3 .* RX "
        ).matcher(dumpString).find());

        int expected_index_of_verbose_frame_0 = dumpString.indexOf(
                "Frame direction: TX\nFrame timestamp: 0\n");
        int expected_index_of_verbose_frame_1 = dumpString.indexOf(
                "Frame direction: RX\nFrame timestamp: 1\n");
        int expected_index_of_verbose_frame_2 = dumpString.indexOf(
                "Frame direction: TX\nFrame timestamp: 2\n");
        int expected_index_of_verbose_frame_3 = dumpString.indexOf(
                "Frame direction: RX\nFrame timestamp: 3\n");
        assertFalse(-1 == expected_index_of_verbose_frame_0);
        assertFalse(-1 == expected_index_of_verbose_frame_1);
        assertFalse(-1 == expected_index_of_verbose_frame_2);
        assertFalse(-1 == expected_index_of_verbose_frame_3);
        assertTrue(expected_index_of_verbose_frame_0 < expected_index_of_verbose_frame_1);
        assertTrue(expected_index_of_verbose_frame_1 < expected_index_of_verbose_frame_2);
        assertTrue(expected_index_of_verbose_frame_2 < expected_index_of_verbose_frame_3);
    }

    /** Verifies that eng builds do not show fate detail outside of verbose mode. */
    @Test
    public void dumpOmitsFateDetailInEngBuildsOutsideOfVerboseMode() throws Exception {
        final boolean verbosityToggle = false;
        when(mBuildProperties.isEngBuild()).thenReturn(true);
        when(mBuildProperties.isUserdebugBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        String dumpString = getDumpString(verbosityToggle);
        assertFalse(dumpString.contains("VERBOSE PACKET FATE DUMP"));
        assertFalse(dumpString.contains("Frame bytes"));
    }

    /** Verifies that userdebug builds do not show fate detail outside of verbose mode. */
    @Test
    public void dumpOmitsFateDetailInUserdebugBuildsOutsideOfVerboseMode() throws Exception {
        final boolean verbosityToggle = false;
        when(mBuildProperties.isUserdebugBuild()).thenReturn(true);
        when(mBuildProperties.isEngBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        String dumpString = getDumpString(verbosityToggle);
        assertFalse(dumpString.contains("VERBOSE PACKET FATE DUMP"));
        assertFalse(dumpString.contains("Frame bytes"));
    }

    /**
     * Verifies that, if verbose is disabled after fetching fates, the dump does not include
     * verbose fate logs.
     */
    @Test
    public void dumpOmitsFatesIfVerboseIsDisabledAfterFetch() {
        final boolean verbosityToggle = true;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startPktFateMonitoring(STA_IF_NAME);
        when(mClientModeManager.getTxPktFates()).thenReturn(Arrays.asList(
                new WifiNative.TxFateReport(
                        WifiLoggerHal.TX_PKT_FATE_ACKED, 0, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                )));
        when(mClientModeManager.getRxPktFates()).thenReturn(Arrays.asList(
                new WifiNative.RxFateReport(
                        WifiLoggerHal.RX_PKT_FATE_SUCCESS, 1, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                )));
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED,
                mClientModeManager);
        verify(mClientModeManager).getTxPktFates();
        verify(mClientModeManager).getRxPktFates();

        final boolean newVerbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(newVerbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});

        String fateDumpString = sw.toString();
        assertFalse(fateDumpString.contains("VERBOSE PACKET FATE DUMP"));
        assertFalse(fateDumpString.contains("Frame bytes"));
    }

    /** Verifies that the default size of our ring buffers is small. */
    @Ignore("TODO(b/36811399): re-enable this @Test")
    @Test
    public void ringBufferSizeIsSmallByDefault() throws Exception {
        final boolean verbosityToggle = false;
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.onRingBufferData(
                mFakeRbs, new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE + 1]);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        assertEquals(0, getLoggerRingBufferData().length);
    }

    /** Verifies that we use small ring buffers by default, on userdebug builds. */
    @Ignore("TODO(b/36811399): re-enable this @Test")
    @Test
    public void ringBufferSizeIsSmallByDefaultOnUserdebugBuilds() throws Exception {
        final boolean verbosityToggle = false;
        when(mBuildProperties.isUserdebugBuild()).thenReturn(true);
        when(mBuildProperties.isEngBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.onRingBufferData(
                mFakeRbs, new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE + 1]);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        assertEquals(0, getLoggerRingBufferData().length);
    }

    /** Verifies that we use small ring buffers by default, on eng builds. */
    @Ignore("TODO(b/36811399): re-enable this @Test")
    @Test
    public void ringBufferSizeIsSmallByDefaultOnEngBuilds() throws Exception {
        final boolean verbosityToggle = false;
        when(mBuildProperties.isEngBuild()).thenReturn(true);
        when(mBuildProperties.isUserdebugBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.onRingBufferData(
                mFakeRbs, new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE + 1]);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        assertEquals(0, getLoggerRingBufferData().length);
    }

    /** Verifies that we use large ring buffers when initially started in verbose mode. */
    @Test
    public void ringBufferSizeIsLargeInVerboseMode() throws Exception {
        final boolean verbosityToggle = true;

        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.onRingBufferData(
                mFakeRbs, new byte[LARGE_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE]);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        assertEquals(1, getLoggerRingBufferData().length);
    }

    /** Verifies that we use large ring buffers when switched from normal to verbose mode. */
    @Test
    public void startLoggingGrowsRingBuffersIfNeeded() throws Exception {
        mWifiDiagnostics.enableVerboseLogging(false /* verbose disabled */, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.onRingBufferData(
                mFakeRbs, new byte[LARGE_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE]);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        assertEquals(1, getLoggerRingBufferData().length);
    }

    /** Verifies that we use small ring buffers when switched from verbose to normal mode. */
    @Ignore("TODO(b/36811399): re-enabled this @Test")
    @Test
    public void startLoggingShrinksRingBuffersIfNeeded() throws Exception {

        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.onRingBufferData(
                mFakeRbs, new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE + 1]);

        // Existing data is nuked (too large).
        mWifiDiagnostics.enableVerboseLogging(false /* verbose disabled */, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        assertEquals(0, getLoggerRingBufferData().length);

        // New data must obey limit as well.
        mWifiDiagnostics.onRingBufferData(
                mFakeRbs, new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE + 1]);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        assertEquals(0, getLoggerRingBufferData().length);
    }

    /**
     * Verifies that we capture a bugreport & store alert data when WifiNative invokes
     * the alert callback.
     */
    @Test
    public void onWifiAlertCapturesBugreportAndLogsMetrics() throws Exception {
        mWifiDiagnostics.onWifiAlert(ALERT_REASON_CODE, ALERT_DATA);
        mTestLooper.dispatchAll();

        assertEquals(1, mWifiDiagnostics.getAlertReports().size());
        WifiDiagnostics.BugReport alertReport = mWifiDiagnostics.getAlertReports().get(0);
        assertEquals(ALERT_REASON_CODE, alertReport.errorCode);
        assertArrayEquals(ALERT_DATA, alertReport.alertData);

        verify(mWifiMetrics).logFirmwareAlert(anyString(), eq(ALERT_REASON_CODE));
        verify(mWifiScoreCard).noteFirmwareAlert(ALERT_REASON_CODE);
    }

    /** Verifies that we skip the firmware and driver dumps if verbose is not enabled. */
    @Test
    public void captureBugReportSkipsFirmwareAndDriverDumpsByDefault() {
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative, never()).getFwMemoryDump();
        verify(mWifiNative, never()).getDriverStateDump();
    }

    /** Verifies that we capture the firmware and driver dumps if verbose is enabled. */
    @Test
    public void captureBugReportTakesFirmwareAndDriverDumpsInVerboseMode() {
        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).getFwMemoryDump();
        verify(mWifiNative).getDriverStateDump();
    }

    /** Verifies that the dump includes driver state, if driver state was provided by HAL. */
    @Test
    public void dumpIncludesDriverStateDumpIfAvailable() {
        when(mWifiNative.getDriverStateDump()).thenReturn(new byte[]{0, 1, 2});

        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).getDriverStateDump();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{});
        assertTrue(sw.toString().contains(WifiDiagnostics.DRIVER_DUMP_SECTION_HEADER));
    }

    /** Verifies that the dump skips driver state, if driver state was not provided by HAL. */
    @Test
    public void dumpOmitsDriverStateDumpIfUnavailable() {
        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).getDriverStateDump();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{});
        assertFalse(sw.toString().contains(WifiDiagnostics.DRIVER_DUMP_SECTION_HEADER));
    }

    /** Verifies that the dump omits driver state, if verbose was disabled after capture. */
    @Test
    public void dumpOmitsDriverStateDumpIfVerboseDisabledAfterCapture() {
        when(mWifiNative.getDriverStateDump()).thenReturn(new byte[]{0, 1, 2});

        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).getDriverStateDump();

        mWifiDiagnostics.enableVerboseLogging(false /* verbose disabled */, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{});
        assertFalse(sw.toString().contains(WifiDiagnostics.DRIVER_DUMP_SECTION_HEADER));
    }

    /** Verifies that the dump includes firmware dump, if firmware dump was provided by HAL. */
    @Test
    public void dumpIncludesFirmwareMemoryDumpIfAvailable() {
        when(mWifiNative.getFwMemoryDump()).thenReturn(new byte[]{0, 1, 2});

        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).getFwMemoryDump();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{});
        assertTrue(sw.toString().contains(WifiDiagnostics.FIRMWARE_DUMP_SECTION_HEADER));
    }

    /** Verifies that the dump skips firmware memory, if firmware memory was not provided by HAL. */
    @Test
    public void dumpOmitsFirmwareMemoryDumpIfUnavailable() {
        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).getFwMemoryDump();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{});
        assertFalse(sw.toString().contains(WifiDiagnostics.FIRMWARE_DUMP_SECTION_HEADER));
    }

    /** Verifies that the dump omits firmware memory, if verbose was disabled after capture. */
    @Test
    public void dumpOmitsFirmwareMemoryDumpIfVerboseDisabledAfterCapture() {
        when(mWifiNative.getFwMemoryDump()).thenReturn(new byte[]{0, 1, 2});

        mWifiDiagnostics.enableVerboseLogging(true /* verbose enabled */, true);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).getFwMemoryDump();

        mWifiDiagnostics.enableVerboseLogging(false /* verbose disabled */, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiDiagnostics.dump(new FileDescriptor(), pw, new String[]{});
        assertFalse(sw.toString().contains(WifiDiagnostics.FIRMWARE_DUMP_SECTION_HEADER));
    }

    @Test
    public void dumpRequestsLastMileLoggerDump() {
        mWifiDiagnostics.dump(
                new FileDescriptor(), new PrintWriter(new StringWriter()), new String[]{});
        verify(mLastMileLogger).dump(any());
    }

    @Test
    public void takeBugReportCallsActivityManagerOnUserDebug() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mWifiDiagnostics.takeBugReport("", "");
        verify(mPackageManager, times(1)).queryIntentActivities(any(), anyInt());
    }

    @Test
    public void takeBugreportIgnoredWhenTitleAndDetailDisabled() {
        // Verify bugreport is disabled for a specific title and detail.
        // Use set of empty String here to match empty title and detail.
        when(mDeviceConfigFacade.getDisabledAutoBugreportTitleAndDetails()).thenReturn(
                new HashSet<>(Arrays.asList("TITLEDETAIL")));
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mWifiDiagnostics.takeBugReport("TITLE", "DETAIL");
        verify(mPackageManager, never()).queryIntentActivities(any(), anyInt());
    }

    @Test
    public void takeBugReportSwallowsExceptions() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        doThrow(new RuntimeException()).when(mBugreportManager).requestBugreport(
                any(), any(), any());
        mWifiDiagnostics.takeBugReport("", "");
        verify(mPackageManager, times(1)).queryIntentActivities(any(), anyInt());
    }

    @Test
    public void takeBugReportDoesNothingOnUserBuild() {
        when(mBuildProperties.isUserBuild()).thenReturn(true);
        mWifiDiagnostics.takeBugReport("", "");
        verify(mBugreportManager, never()).requestBugreport(any(), any(), any());
    }

    @Test
    public void tryTakeBugReportTwiceWithInsufficientTimeGap() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        // 1st attempt should succeed
        when(mClock.getWallClockMillis()).thenReturn(10L);
        mWifiDiagnostics.takeBugReport("", "");
        verify(mPackageManager, times(1)).queryIntentActivities(any(), anyInt());
        // 2nd attempt should fail
        when(mClock.getWallClockMillis()).thenReturn(BUG_REPORT_MIN_WINDOW_MS - 20L);
        mWifiDiagnostics.takeBugReport("", "");
        verify(mPackageManager, times(1)).queryIntentActivities(any(), anyInt());
    }

    @Test
    public void takeBugReportDoesNothingWhenConfigOverlayDisabled() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mResources.setBoolean(R.bool.config_wifi_diagnostics_bugreport_enabled, false);
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, mWifiInjector, mWifiNative, mBuildProperties, mLastMileLogger, mClock,
                mTestLooper.getLooper());

        mWifiDiagnostics.takeBugReport("", "");
        verify(mBugreportManager, never()).requestBugreport(any(), any(), any());
    }

    @Test
    public void takeBugReportDoesNothingWhenSSRConfigOverlayDisabled() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mResources.setBoolean(R.bool.config_wifi_diagnostics_bugreport_enabled, true);
        mResources.setBoolean(R.bool.config_wifi_subsystem_restart_bugreport_enabled, false);
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, mWifiInjector, mWifiNative, mBuildProperties, mLastMileLogger, mClock,
                mTestLooper.getLooper());

        mWifiDiagnostics.takeBugReport("", "Subsystem Restart");
        verify(mPackageManager, never()).queryIntentActivities(any(), anyInt());
    }

    @Test
    public void takeBugReportWhenSSRConfigOverlayEnabled() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mResources.setBoolean(R.bool.config_wifi_diagnostics_bugreport_enabled, true);
        mResources.setBoolean(R.bool.config_wifi_subsystem_restart_bugreport_enabled, true);
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, mWifiInjector, mWifiNative, mBuildProperties, mLastMileLogger, mClock,
                mTestLooper.getLooper());

        mWifiDiagnostics.takeBugReport("", "Subsystem Restart");
        verify(mPackageManager, times(1)).queryIntentActivities(any(), anyInt());
    }

    @Test
    public void takeBugReportWhenSSRConfigOverlayDisabledWhenNonSRREvent() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mResources.setBoolean(R.bool.config_wifi_diagnostics_bugreport_enabled, true);
        mResources.setBoolean(R.bool.config_wifi_subsystem_restart_bugreport_enabled, false);
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, mWifiInjector, mWifiNative, mBuildProperties, mLastMileLogger, mClock,
                mTestLooper.getLooper());

        mWifiDiagnostics.takeBugReport("", "Last Resort Watchdog");
        verify(mPackageManager, times(1)).queryIntentActivities(any(), anyInt());
    }

    @Test
    public void takeBugReportWhenSSRConfigOverlayEnabledWhenNonSRREvent() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mResources.setBoolean(R.bool.config_wifi_diagnostics_bugreport_enabled, true);
        mResources.setBoolean(R.bool.config_wifi_subsystem_restart_bugreport_enabled, true);
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, mWifiInjector, mWifiNative, mBuildProperties, mLastMileLogger, mClock,
                mTestLooper.getLooper());

        mWifiDiagnostics.takeBugReport("", "Last Resort Watchdog");
        verify(mPackageManager, times(1)).queryIntentActivities(any(), anyInt());
    }

    /** Verifies that we flush HAL ringbuffer when capture bugreport. */
    @Test
    public void triggerBugReportFlushRingBufferDataCapture() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        when(mWifiNative.flushRingBufferData()).thenReturn(true);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();
        verify(mWifiNative).flushRingBufferData();
    }

    /** Verifies that we flush HAL ringbuffer when detecting fatal firmware alert. */
    @Test
    public void triggerAlertFlushRingBufferDataCapture() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        when(mWifiNative.flushRingBufferData()).thenReturn(true);
        /** captureAlertData with mock fatal firmware alert*/
        mWifiDiagnostics.onWifiAlert(FATAL_FW_ALERT_LIST[0], ALERT_DATA);
        mTestLooper.dispatchAll();
        verify(mWifiNative).flushRingBufferData();
    }

    /** Verifies that we don't flush HAL ringbuffer when detecting non fatal firmware alert. */
    @Test
    public void triggerNonAlertFlushRingBufferDataCapture() {
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        when(mWifiNative.flushRingBufferData()).thenReturn(true);
        /** captureAlertData with mock non fatal firmware alert*/
        mWifiDiagnostics.onWifiAlert(NON_FATAL_FW_ALERT, ALERT_DATA);
        mTestLooper.dispatchAll();
        verify(mWifiNative, never()).flushRingBufferData();
    }

    /**
     * Verifies that we can capture ring-buffer data in SoftAp mode
     */
    @Test
    public void canCaptureAndStoreRingBufferDataInSoftApMode() throws Exception {
        final boolean verbosityToggle = false;

        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(AP_IF_NAME);

        final byte[] data = new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE];
        mWifiDiagnostics.onRingBufferData(mFakeRbs, data);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData = getLoggerRingBufferData();
        assertEquals(1, ringBufferData.length);
        assertArrayEquals(data, ringBufferData[0]);
    }

    /**
     * Verifies that we capture ring-buffer data in Station + SoftAp
     * Concurrency mode.
     */
    @Test
    public void canCaptureAndStoreRingBufferDataInConcurrencyMode() throws Exception {
        final boolean verbosityToggle = false;

        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.startLogging(AP_IF_NAME);

        final byte[] data = new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE];
        mWifiDiagnostics.onRingBufferData(mFakeRbs, data);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData = getLoggerRingBufferData();
        assertEquals(1, ringBufferData.length);
        assertArrayEquals(data, ringBufferData[0]);
    }

    /**
     * Verifies that we can continue to capture ring-buffer data
     * after WiFi station is turned off in concurrency mode.
     */
    @Test
    public void canCaptureAndStoreRingBufferDataAfterStaIsTurnedOffInConcurrencyMode()
            throws Exception {
        final boolean verbosityToggle = false;
        final byte[] data = new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE];

        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.startLogging(AP_IF_NAME);

        mWifiDiagnostics.onRingBufferData(mFakeRbs, data);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData0 = getLoggerRingBufferData();
        assertEquals(1, ringBufferData0.length);
        assertArrayEquals(data, ringBufferData0[0]);

        mWifiDiagnostics.stopLogging(STA_IF_NAME);

        mWifiDiagnostics.onRingBufferData(mFakeRbs, data);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData1 = getLoggerRingBufferData();
        assertEquals(1, ringBufferData1.length);
        assertArrayEquals(data, ringBufferData1[0]);
    }

    /**
     * Verifies that we can continue to capture ring-buffer data
     * after SoftAp is turned off in concurrency mode.
     */
    @Test
    public void canCaptureAndStoreRingBufferDataAfterSoftApIsTurnedOffInConcurrencyMode()
            throws Exception {
        final boolean verbosityToggle = false;
        final byte[] data = new byte[SMALL_RING_BUFFER_SIZE_KB * BYTES_PER_KBYTE];

        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        mWifiDiagnostics.startLogging(STA_IF_NAME);
        mWifiDiagnostics.startLogging(AP_IF_NAME);

        mWifiDiagnostics.onRingBufferData(mFakeRbs, data);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData0 = getLoggerRingBufferData();
        assertEquals(1, ringBufferData0.length);
        assertArrayEquals(data, ringBufferData0[0]);

        mWifiDiagnostics.stopLogging(AP_IF_NAME);

        mWifiDiagnostics.onRingBufferData(mFakeRbs, data);
        mWifiDiagnostics.triggerBugReportDataCapture(WifiDiagnostics.REPORT_REASON_NONE);
        mTestLooper.dispatchAll();

        byte[][] ringBufferData1 = getLoggerRingBufferData();
        assertEquals(1, ringBufferData1.length);
        assertArrayEquals(data, ringBufferData1[0]);
    }

    /** Verifies that stoplogging on both the interfaces clean up
     *  all the resources.
     */
    @Test
    public void verifyStopLoggingOnAllInterfacesClearTheResources() throws Exception {
        final boolean verbosityToggle = false;

        mWifiDiagnostics.enableVerboseLogging(verbosityToggle, false);
        when(mWifiNative.setLoggingEventHandler(any())).thenReturn(true);
        when(mWifiNative.resetLogHandler()).thenReturn(true);

        mWifiDiagnostics.startLogging(STA_IF_NAME);
        verify(mWifiNative).setLoggingEventHandler(any());

        mWifiDiagnostics.startLogging(AP_IF_NAME);

        mWifiDiagnostics.stopLogging(STA_IF_NAME);
        verify(mWifiNative, never()).resetLogHandler();

        mWifiDiagnostics.stopLogging(AP_IF_NAME);

        verify(mWifiNative).resetLogHandler();
    }
}
