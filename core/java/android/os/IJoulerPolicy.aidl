/* //device/java/android/android/os/IJoulerPolicy.aidl
*/
package android.os;

import java.util.List;
import android.app.PendingIntent;

interface IJoulerPolicy {
    byte[] getStatistics();
    
    void controlCpuMaxFrequency(int freq);
    int[] getAllCpuFrequencies();
    void rateLimitForUid(int uid);
    void broadcastAlertIntent(in List<String> badPackages, in List<String> okayPackages, in List<String> goodPackages);
    int getPriority(int uid);
    void resetPriority(int uid, int priority);
    void setDelayedTask(in PendingIntent pendingIntent, int maxDelayedTime);
}
