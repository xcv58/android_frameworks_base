package android.os;

import java.util.ArrayList;
import java.util.List;
import android.os.RemoteException;
import android.content.Context;
import android.telephony.SignalStrength;
import android.util.Log;
import android.util.SparseArray;
import android.app.PendingIntent;

/**
 * This class provides access to jouler policy services. This allow you to 
 * use knobs that can affect battery life of the device. You can change max
 * cpu frequecy, change bandwidth of any uid, change priority of any pid,
 * change screen brightness, ask system to broadcast intent that contains 
 * information about which apps are good, moderate and bad in terms of their
 * energy behavior.
 */

public final class JoulerPolicy
{
    private static final String TAG = "JoulerPolicy";
    private final IJoulerPolicy mService;

     /**
     * {@hide}
     */

    public JoulerPolicy(IJoulerPolicy service) {
	mService = service;
    }

    public byte[] getStatistics() throws RemoteException {
	return mService.getStatistics();
    }

    public void controlCpuMaxFrequency(int freq) {
	try {
		mService.controlCpuMaxFrequency(freq);
	} catch (RemoteException ex) {
        }

    }
    
    
    public int[] getAllCpuFrequencies() {
    	try{
    		int[] cpu = mService.getAllCpuFrequencies();
		return cpu;
    	}catch(RemoteException e){
    		return null;
    	}
    }

     public void broadcastAlertIntent(List<String> badPackages, List<String> okayPackages, List<String> goodPackages) {
	try {
                mService.broadcastAlertIntent(badPackages, okayPackages, goodPackages);
        } catch (RemoteException ex) {
        }

    }

     public void resetPriority(int uid, int priority) {
        try {
                mService.resetPriority(uid, priority);
        } catch (RemoteException ex) {
        }

    }
/*
    public void setScreenBrightness(int brightness) {
        try {
                mService.setScreenBrightness(brightness);
        } catch (RemoteException ex) {
        }

    }
*/
    public void rateLimitForUid(int uid) {
        try {
                mService.rateLimitForUid(uid);
        } catch (RemoteException ex) {
        }

    }

    public void setDelayedTask(PendingIntent pendingIntent, int maxDelayedTime) {
        try {
            mService.setDelayedTask(pendingIntent, maxDelayedTime);
        } catch (RemoteException ex) {
        }

    }


}
