/* Copyright 2022, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.net.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import com.android.modules.utils.ParceledListSlice;

/**
 * @hide
 */
oneway interface IWifiScannerListener {
    void onSuccess();

    void onFailure(int reason, String description);

    /**
     * reports results retrieved from background scan and single shot scans
     */
    void onResults(in WifiScanner.ScanData[] results);

    /**
     * reports full scan result for each access point found in scan
     */
    void onFullResult(in ScanResult fullScanResult);

    void onSingleScanCompleted();

    /**
     * Invoked when one of the PNO networks are found in scan results.
     */
    void onPnoNetworkFound(in ScanResult[] results);
    /**
     * reports full scan result for all access points found in scan
     */
    void onFullResults(in ParceledListSlice<ScanResult> scanResult);
}
