/*
 * Copyright 2019 The Android Open Source Project
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

import static com.android.server.wifi.util.InformationElementUtil.BssLoad.INVALID;
import static com.android.server.wifi.util.InformationElementUtil.BssLoad.MAX_CHANNEL_UTILIZATION;
import static com.android.server.wifi.util.InformationElementUtil.BssLoad.MIN_CHANNEL_UTILIZATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations.WifiStandard;
import android.net.wifi.WifiInfo;
import android.net.wifi.nl80211.DeviceWiphyCapabilities;
import android.util.Log;

import com.android.wifi.resources.R;

/**
 * A class that predicts network throughput based on RSSI, channel utilization, channel width,
 * WiFi standard (PHY/MAC mode), Nss and other radio information.
 */
public class ThroughputPredictor {
    private static final String TAG = "WifiThroughputPredictor";
    private boolean mVerboseLoggingEnabled = false;

    // Default value of channel utilization at 2G when channel utilization is not available from
    // BssLoad IE or from link layer stats
    public static final int CHANNEL_UTILIZATION_DEFAULT_2G = MAX_CHANNEL_UTILIZATION * 6 / 16;
    // Default value of channel utilization at 5G when channel utilization is not available from
    // BssLoad IE or from link layer stats
    public static final int CHANNEL_UTILIZATION_DEFAULT_ABOVE_2G = MAX_CHANNEL_UTILIZATION / 16;
    // Channel utilization boost when bluetooth is in the connected mode
    public static final int CHANNEL_UTILIZATION_BOOST_BT_CONNECTED_2G = MAX_CHANNEL_UTILIZATION / 4;
    //TODO: b/145133625 Need to consider 6GHz

    // Number of data tones per OFDM symbol
    private static final int NUM_TONE_PER_SYM_LEGACY = 48;
    private static final int NUM_TONE_PER_SYM_11N_20MHZ = 52;
    private static final int NUM_TONE_PER_SYM_11N_40MHZ = 108;
    private static final int NUM_TONE_PER_SYM_11AC_20MHZ = 52;
    private static final int NUM_TONE_PER_SYM_11AC_40MHZ = 108;
    private static final int NUM_TONE_PER_SYM_11AC_80MHZ = 234;
    private static final int NUM_TONE_PER_SYM_11AC_160MHZ = 468;
    private static final int NUM_TONE_PER_SYM_11AX_BE_20MHZ = 234;
    private static final int NUM_TONE_PER_SYM_11AX_BE_40MHZ = 468;
    private static final int NUM_TONE_PER_SYM_11AX_BE_80MHZ = 980;
    private static final int NUM_TONE_PER_SYM_11AX_BE_160MHZ = 1960;
    private static final int NUM_TONE_PER_SYM_11BE_320MHZ = 1960 * 2;

    // 11ag OFDM symbol duration in ns
    private static final int SYM_DURATION_LEGACY_NS = 4000;
    // 11n OFDM symbol duration in ns with 0.4us guard interval
    private static final int SYM_DURATION_11N_NS = 3600;
    // 11ac OFDM symbol duration in ns with 0.4us guard interval
    private static final int SYM_DURATION_11AC_NS = 3600;
    // 11ax/be OFDM symbol duration in ns with 0.8us guard interval
    private static final int SYM_DURATION_11AX_BE_NS = 13600;
    private static final int MICRO_TO_NANO_RATIO = 1000;

    // The scaling factor for integer representation of bitPerTone and MAX_BITS_PER_TONE_XXX
    private static final int BIT_PER_TONE_SCALE = 1000;
    private static final int MAX_BITS_PER_TONE_LEGACY =
            (int) Math.round((6 * 3.0 * BIT_PER_TONE_SCALE) / 4.0);
    private static final int MAX_BITS_PER_TONE_11N =
            (int) Math.round((6 * 5.0 * BIT_PER_TONE_SCALE) / 6.0);
    private static final int MAX_BITS_PER_TONE_11AC =
            (int) Math.round((8 * 5.0 * BIT_PER_TONE_SCALE) / 6.0);
    private static final int MAX_BITS_PER_TONE_11AX =
            (int) Math.round((10 * 5.0 * BIT_PER_TONE_SCALE) / 6.0);
    private static final int MAX_BITS_PER_TONE_11BE =
            (int) Math.round((12 * 5.0 * BIT_PER_TONE_SCALE) / 6.0);

    // snrDb-to-bitPerTone lookup table (LUT) used at low SNR
    // snr = Math.pow(10.0, snrDb / 10.0);
    // bitPerTone = (int) (Math.log10(1 + snr) / Math.log10(2.0) * BIT_PER_TONE_SCALE)
    private static final int TWO_IN_DB = 3;
    private static final int SNR_DB_TO_BIT_PER_TONE_HIGH_SNR_SCALE = BIT_PER_TONE_SCALE / TWO_IN_DB;
    private static final int SNR_DB_TO_BIT_PER_TONE_LUT_MIN = -10; // minimum snrDb supported by LUT
    private static final int SNR_DB_TO_BIT_PER_TONE_LUT_MAX = 9; // maximum snrDb supported by LUT
    private static final int[] SNR_DB_TO_BIT_PER_TONE_LUT = {0, 171, 212, 262, 323, 396, 484, 586,
            706, 844, 1000, 1176, 1370, 1583, 1812, 2058, 2317, 2588, 2870, 3161};
    // Thermal noise floor power in dBm integrated over 20MHz with 5.5dB noise figure at 25C
    private static final int NOISE_FLOOR_20MHZ_DBM = -96;
    // A fudge factor to represent HW implementation margin in dB.
    // Predicted throughput matches pretty well with OTA throughput with this fudge factor.
    private static final int SNR_MARGIN_DB = 16;
    private static final int MAX_NUM_SPATIAL_STREAM_11BE = 16;
    private static final int MAX_NUM_SPATIAL_STREAM_11AX = 8;
    private static final int MAX_NUM_SPATIAL_STREAM_11AC = 8;
    private static final int MAX_NUM_SPATIAL_STREAM_11N = 4;
    private static final int MAX_NUM_SPATIAL_STREAM_LEGACY = 1;

    private static final int B_MODE_MAX_MBPS = 11;
    private final Context mContext;

    ThroughputPredictor(Context context) {
        mContext = context;
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param verbose true to enable and false to disable.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * Predict maximum Tx throughput supported by connected network at the highest RSSI
     * with the lowest channel utilization
     * @return predicted maximum Tx throughput in Mbps
     */
    public int predictMaxTxThroughput(@NonNull WifiNative.ConnectionCapabilities capabilities) {
        return predictThroughputInternal(capabilities.wifiStandard, capabilities.is11bMode,
                capabilities.channelBandwidth, WifiInfo.MAX_RSSI,
                capabilities.maxNumberTxSpatialStreams, MIN_CHANNEL_UTILIZATION, 0, null);
    }

    /**
     * Predict maximum Rx throughput supported by connected network at the highest RSSI
     * with the lowest channel utilization
     * @return predicted maximum Rx throughput in Mbps
     */
    public int predictMaxRxThroughput(@NonNull WifiNative.ConnectionCapabilities capabilities) {
        return predictThroughputInternal(capabilities.wifiStandard, capabilities.is11bMode,
                capabilities.channelBandwidth, WifiInfo.MAX_RSSI,
                capabilities.maxNumberRxSpatialStreams, MIN_CHANNEL_UTILIZATION, 0, null);
    }

    /**
     * Predict Tx throughput with current connection capabilities, RSSI and channel utilization
     * @return predicted Tx throughput in Mbps
     */
    public int predictTxThroughput(@NonNull WifiNative.ConnectionCapabilities capabilities,
            int rssiDbm, int frequency, int channelUtilization) {
        int channelUtilizationFinal = getValidChannelUtilization(frequency,
                channelUtilization, false);
        return predictThroughputInternal(capabilities.wifiStandard, capabilities.is11bMode,
                capabilities.channelBandwidth, rssiDbm, capabilities.maxNumberTxSpatialStreams,
                channelUtilizationFinal, frequency, null);
    }

    /**
     * Predict Rx throughput with current connection capabilities, RSSI and channel utilization
     * @return predicted Rx throughput in Mbps
     */
    public int predictRxThroughput(@NonNull WifiNative.ConnectionCapabilities capabilities,
            int rssiDbm, int frequency, int channelUtilization) {
        int channelUtilizationFinal = getValidChannelUtilization(frequency,
                channelUtilization, false);
        return predictThroughputInternal(capabilities.wifiStandard, capabilities.is11bMode,
                capabilities.channelBandwidth, rssiDbm, capabilities.maxNumberRxSpatialStreams,
                channelUtilizationFinal, frequency, null);
    }

    /**
     * Predict network throughput given by the current channel condition and RSSI
     * @param deviceCapabilities Phy Capabilities of the device
     * @param wifiStandardAp the highest wifi standard supported by AP
     * @param channelWidthAp the channel bandwidth of AP
     * @param rssiDbm the scan RSSI in dBm
     * @param frequency the center frequency of primary 20MHz channel
     * @param maxNumSpatialStreamAp the maximum number of spatial streams supported by AP
     * @param channelUtilizationBssLoad the channel utilization ratio indicated from BssLoad IE
     * @param channelUtilizationLinkLayerStats the channel utilization ratio detected from scan
     * @param isBluetoothConnected whether the bluetooth adaptor is in connected mode
     * @param disabledSubchannelBitmap the disabled Subchannel Bitmap (2 bytes) from EHT
     *                                 Operation IE
     * @return predicted throughput in Mbps
     */
    public int predictThroughput(DeviceWiphyCapabilities deviceCapabilities,
            @WifiStandard int wifiStandardAp,
            int channelWidthAp, int rssiDbm, int frequency, int maxNumSpatialStreamAp,
            int channelUtilizationBssLoad, int channelUtilizationLinkLayerStats,
            boolean isBluetoothConnected, @Nullable byte[] disabledSubchannelBitmap) {

        if (deviceCapabilities == null) {
            Log.e(TAG, "Null device capabilities passed to throughput predictor");
            return 0;
        }

        int maxNumSpatialStreamDevice = Math.min(deviceCapabilities.getMaxNumberTxSpatialStreams(),
                deviceCapabilities.getMaxNumberRxSpatialStreams());

        if (mContext.getResources().getBoolean(
                R.bool.config_wifiFrameworkMaxNumSpatialStreamDeviceOverrideEnable)) {
            maxNumSpatialStreamDevice = mContext.getResources().getInteger(
                    R.integer.config_wifiFrameworkMaxNumSpatialStreamDeviceOverrideValue);
        }

        int maxNumSpatialStream = Math.min(maxNumSpatialStreamDevice, maxNumSpatialStreamAp);

        // Get minimum standard support between device and AP
        int wifiStandard;
        switch (wifiStandardAp) {
            case ScanResult.WIFI_STANDARD_11BE:
                if (deviceCapabilities.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE)) {
                    wifiStandard = ScanResult.WIFI_STANDARD_11BE;
                    break;
                }
                //FALL THROUGH
            case ScanResult.WIFI_STANDARD_11AX:
                if (deviceCapabilities.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX)) {
                    wifiStandard = ScanResult.WIFI_STANDARD_11AX;
                    break;
                }
                //FALL THROUGH
            case ScanResult.WIFI_STANDARD_11AC:
                if (deviceCapabilities.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC)) {
                    wifiStandard = ScanResult.WIFI_STANDARD_11AC;
                    break;
                }
                //FALL THROUGH
            case ScanResult.WIFI_STANDARD_11N:
                if (deviceCapabilities.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11N)) {
                    wifiStandard = ScanResult.WIFI_STANDARD_11N;
                    break;
                }
                //FALL THROUGH
            default:
                wifiStandard = ScanResult.WIFI_STANDARD_LEGACY;
        }

        // Calculate channel width
        int channelWidth;
        switch (channelWidthAp) {
            case ScanResult.CHANNEL_WIDTH_320MHZ:
                if (deviceCapabilities.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_320MHZ)) {
                    channelWidth = ScanResult.CHANNEL_WIDTH_320MHZ;
                    break;
                }
                // FALL THROUGH
            case ScanResult.CHANNEL_WIDTH_160MHZ:
                if (deviceCapabilities.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_160MHZ)) {
                    channelWidth = ScanResult.CHANNEL_WIDTH_160MHZ;
                    break;
                }
                // FALL THROUGH
            case ScanResult.CHANNEL_WIDTH_80MHZ:
                if (deviceCapabilities.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_80MHZ)) {
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ;
                    break;
                }
                // FALL THROUGH
            case ScanResult.CHANNEL_WIDTH_40MHZ:
                if (deviceCapabilities.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_40MHZ)) {
                    channelWidth = ScanResult.CHANNEL_WIDTH_40MHZ;
                    break;
                }
                // FALL THROUGH
            default:
                channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ;
        }

        if (mVerboseLoggingEnabled) {
            StringBuilder sb = new StringBuilder();
            Log.d(TAG, sb.append("AP Nss: ").append(maxNumSpatialStreamAp)
                    .append(", Device Nss: ").append(maxNumSpatialStreamDevice)
                    .append(", freq: ").append(frequency)
                    .toString());
        }

        int channelUtilization = getValidChannelUtilization(frequency,
                channelUtilizationLinkLayerStats,
                isBluetoothConnected);

        return predictThroughputInternal(wifiStandard, false/* is11bMode */, channelWidth,
                rssiDbm, maxNumSpatialStream, channelUtilization, frequency,
                disabledSubchannelBitmap);
    }

    private int predictThroughputInternal(@WifiStandard int wifiStandard, boolean is11bMode,
            int channelWidth, int rssiDbm, int maxNumSpatialStream,  int channelUtilization,
            int frequency, @Nullable byte[] disabledSubchannelBitmap) {

        // channel bandwidth in MHz = 20MHz * (2 ^ channelWidthFactor);
        int channelWidthFactor;
        int numTonePerSym;
        int symDurationNs;
        int maxBitsPerTone;
        if (maxNumSpatialStream < 1) {
            Log.e(TAG, "maxNumSpatialStream < 1 due to wrong implementation. Overridden to 1");
            maxNumSpatialStream = 1;
        }
        if (wifiStandard == ScanResult.WIFI_STANDARD_LEGACY) {
            // For simplicity, use legacy OFDM parameters to predict 11b rate
            numTonePerSym = NUM_TONE_PER_SYM_LEGACY;
            channelWidthFactor = 0;
            maxNumSpatialStream = MAX_NUM_SPATIAL_STREAM_LEGACY;
            maxBitsPerTone = MAX_BITS_PER_TONE_LEGACY;
            symDurationNs = SYM_DURATION_LEGACY_NS;
        } else if (wifiStandard == ScanResult.WIFI_STANDARD_11N) {
            if (channelWidth == ScanResult.CHANNEL_WIDTH_20MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11N_20MHZ;
                channelWidthFactor = 0;
            } else {
                numTonePerSym = NUM_TONE_PER_SYM_11N_40MHZ;
                channelWidthFactor = 1;
            }
            maxNumSpatialStream = Math.min(maxNumSpatialStream, MAX_NUM_SPATIAL_STREAM_11N);
            maxBitsPerTone = MAX_BITS_PER_TONE_11N;
            symDurationNs = SYM_DURATION_11N_NS;
        } else if (wifiStandard == ScanResult.WIFI_STANDARD_11AC) {
            if (channelWidth == ScanResult.CHANNEL_WIDTH_20MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AC_20MHZ;
                channelWidthFactor = 0;
            } else if (channelWidth == ScanResult.CHANNEL_WIDTH_40MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AC_40MHZ;
                channelWidthFactor = 1;
            } else if (channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AC_80MHZ;
                channelWidthFactor = 2;
            } else {
                numTonePerSym = NUM_TONE_PER_SYM_11AC_160MHZ;
                channelWidthFactor = 3;
            }
            maxNumSpatialStream = Math.min(maxNumSpatialStream, MAX_NUM_SPATIAL_STREAM_11AC);
            maxBitsPerTone = MAX_BITS_PER_TONE_11AC;
            symDurationNs = SYM_DURATION_11AC_NS;
        } else if (wifiStandard == ScanResult.WIFI_STANDARD_11AX) {
            if (channelWidth == ScanResult.CHANNEL_WIDTH_20MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_20MHZ;
                channelWidthFactor = 0;
            } else if (channelWidth == ScanResult.CHANNEL_WIDTH_40MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_40MHZ;
                channelWidthFactor = 1;
            } else if (channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_80MHZ;
                channelWidthFactor = 2;
            } else {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_160MHZ;
                channelWidthFactor = 3;
            }
            maxNumSpatialStream = Math.min(maxNumSpatialStream, MAX_NUM_SPATIAL_STREAM_11AX);
            maxBitsPerTone = MAX_BITS_PER_TONE_11AX;
            symDurationNs = SYM_DURATION_11AX_BE_NS;
        } else if (wifiStandard == ScanResult.WIFI_STANDARD_11BE) {
            if (channelWidth == ScanResult.CHANNEL_WIDTH_20MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_20MHZ;
                channelWidthFactor = 0;
            } else if (channelWidth == ScanResult.CHANNEL_WIDTH_40MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_40MHZ;
                channelWidthFactor = 1;
            } else if (channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_80MHZ;
                channelWidthFactor = 2;
            } else if (channelWidth == ScanResult.CHANNEL_WIDTH_160MHZ) {
                numTonePerSym = NUM_TONE_PER_SYM_11AX_BE_160MHZ;
                channelWidthFactor = 3;
            } else {
                numTonePerSym = NUM_TONE_PER_SYM_11BE_320MHZ;
                channelWidthFactor = 4;
            }
            int numPunctured20MhzSubChannel = 0;
            if (disabledSubchannelBitmap != null && disabledSubchannelBitmap.length == 2) {
                numPunctured20MhzSubChannel = Integer.bitCount(
                        (disabledSubchannelBitmap[1] << 8) | disabledSubchannelBitmap[0]);
            }
            if (numPunctured20MhzSubChannel * NUM_TONE_PER_SYM_11AX_BE_20MHZ < numTonePerSym) {
                numTonePerSym -= numPunctured20MhzSubChannel * NUM_TONE_PER_SYM_11AX_BE_20MHZ;
            }
            maxNumSpatialStream = Math.min(maxNumSpatialStream, MAX_NUM_SPATIAL_STREAM_11BE);
            maxBitsPerTone = MAX_BITS_PER_TONE_11BE;
            symDurationNs = SYM_DURATION_11AX_BE_NS;
        } else {
            return WifiInfo.LINK_SPEED_UNKNOWN;
        }

        // 6Ghz RSSI boost
        if (mContext.getResources().getBoolean(R.bool.config_wifiEnable6GhzBeaconRssiBoost)
                && ScanResult.is6GHz(frequency)) {
            switch (channelWidth) {
                case ScanResult.CHANNEL_WIDTH_40MHZ:
                    rssiDbm += 3;
                    break;
                case ScanResult.CHANNEL_WIDTH_80MHZ:
                    rssiDbm += 6;
                    break;
                case ScanResult.CHANNEL_WIDTH_160MHZ:
                    rssiDbm += 9;
                    break;
                case ScanResult.CHANNEL_WIDTH_320MHZ:
                    rssiDbm += 12;
                    break;
                default:
                    // do nothing
            }
        }

        // noiseFloorDbBoost = 10 * log10 * (2 ^ channelWidthFactor)
        int noiseFloorDbBoost = TWO_IN_DB * channelWidthFactor;
        int noiseFloorDbm = NOISE_FLOOR_20MHZ_DBM + noiseFloorDbBoost + SNR_MARGIN_DB;
        int snrDb  = rssiDbm - noiseFloorDbm;

        int bitPerTone = calculateBitPerTone(snrDb);
        bitPerTone = Math.min(bitPerTone, maxBitsPerTone);

        long bitPerToneTotal = bitPerTone * maxNumSpatialStream;
        long numBitPerSym = bitPerToneTotal * numTonePerSym;
        int phyRateMbps =  (int) ((numBitPerSym * MICRO_TO_NANO_RATIO)
                / (symDurationNs * BIT_PER_TONE_SCALE));

        int airTimeFraction = calculateAirTimeFraction(channelUtilization);

        int throughputMbps = (phyRateMbps * airTimeFraction) / MAX_CHANNEL_UTILIZATION;

        if (is11bMode) {
            throughputMbps = Math.min(throughputMbps, B_MODE_MAX_MBPS);
        }
        if (mVerboseLoggingEnabled) {
            StringBuilder sb = new StringBuilder();
            Log.d(TAG, sb.append(" BW: ").append(channelWidth)
                    .append(" RSSI: ").append(rssiDbm)
                    .append(" Nss: ").append(maxNumSpatialStream)
                    .append(" Mode: ").append(wifiStandard)
                    .append(" symDur: ").append(symDurationNs)
                    .append(" snrDb ").append(snrDb)
                    .append(" bitPerTone: ").append(bitPerTone)
                    .append(" rate: ").append(phyRateMbps)
                    .append(" throughput: ").append(throughputMbps)
                    .toString());
        }
        return throughputMbps;
    }

    // Calculate the number of bits per tone based on the input of SNR in dB
    // The output is scaled up by BIT_PER_TONE_SCALE for integer representation
    private static int calculateBitPerTone(int snrDb) {
        int bitPerTone;
        if (snrDb <= SNR_DB_TO_BIT_PER_TONE_LUT_MAX) {
            int lut_in_idx = Math.max(snrDb, SNR_DB_TO_BIT_PER_TONE_LUT_MIN)
                    - SNR_DB_TO_BIT_PER_TONE_LUT_MIN;
            lut_in_idx = Math.min(lut_in_idx, SNR_DB_TO_BIT_PER_TONE_LUT.length - 1);
            bitPerTone = SNR_DB_TO_BIT_PER_TONE_LUT[lut_in_idx];
        } else {
            // bitPerTone = Math.log10(1+snr)/Math.log10(2) can be approximated as
            // Math.log10(snr) / 0.3 = log10(10^(snrDb/10)) / 0.3 = snrDb / 3
            // SNR_DB_TO_BIT_PER_TONE_HIGH_SNR_SCALE = BIT_PER_TONE_SCALE / 3
            bitPerTone = snrDb * SNR_DB_TO_BIT_PER_TONE_HIGH_SNR_SCALE;
        }
        return bitPerTone;
    }

    private int getValidChannelUtilization(int frequency,
            int channelUtilizationLinkLayerStats, boolean isBluetoothConnected) {
        int channelUtilization;
        boolean is2G = ScanResult.is24GHz(frequency);
        if (isValidUtilizationRatio(channelUtilizationLinkLayerStats)) {
            channelUtilization = channelUtilizationLinkLayerStats;
        } else {
            channelUtilization = is2G ? CHANNEL_UTILIZATION_DEFAULT_2G :
                    CHANNEL_UTILIZATION_DEFAULT_ABOVE_2G;
        }

        if (is2G && isBluetoothConnected) {
            channelUtilization += CHANNEL_UTILIZATION_BOOST_BT_CONNECTED_2G;
            channelUtilization = Math.min(channelUtilization, MAX_CHANNEL_UTILIZATION);
        }
        if (mVerboseLoggingEnabled) {
            StringBuilder sb = new StringBuilder();
            Log.d(TAG, sb
                    .append(" utilization (LLS) ").append(channelUtilizationLinkLayerStats)
                    .append(" isBluetoothConnected: ").append(isBluetoothConnected)
                    .append(" final utilization: ").append(channelUtilization)
                    .toString());
        }
        return channelUtilization;
    }

    /**
     * Check if the channel utilization ratio is valid
     */
    private static boolean isValidUtilizationRatio(int utilizationRatio) {
        return (utilizationRatio <= MAX_CHANNEL_UTILIZATION
                && utilizationRatio >= MIN_CHANNEL_UTILIZATION);
    }

    // Calculate the available airtime fraction value which is multiplied by
    // MAX_CHANNEL_UTILIZATION for integer representation. It is calculated as
    // (1 - channelUtilization / MAX_CHANNEL_UTILIZATION) * MAX_CHANNEL_UTILIZATION
    private int calculateAirTimeFraction(int channelUtilization) {
        return MAX_CHANNEL_UTILIZATION - channelUtilization;
    }
}
