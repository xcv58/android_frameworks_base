/* //device/java/android/android/os/IJoulerPolicy.aidl
*/
package android.os;

import java.util.List;

/**
 * @hide
 */

interface IJoulerPolicy {
    byte[] getStatistics();
    
    void controlCpuMaxFrequency(int freq);
    int[] getAllCpuFrequencies();
    void rateLimitForUid(int uid);
//    void setScreenBrightness(int brightness);
    void broadcastAlertIntent(in List<String> badPackages, in List<String> okayPackages, in List<String> goodPackages);
    void resetPriority(int uid, int priority);
}
