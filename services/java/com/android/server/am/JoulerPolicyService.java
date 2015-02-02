package com.android.server.am;

import static com.android.server.NetworkManagementSocketTagger.PROP_QTAGUID_ENABLED;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;
import java.util.Calendar;
import java.util.Collections;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;
import com.android.server.INativeDaemonConnectorCallbacks;
import com.android.server.NativeDaemonConnector;
import com.android.server.NativeDaemonConnectorException;

import android.os.JoulerStats;
import android.os.JoulerStats.UidStats;
import android.os.JoulerPolicy;
import android.os.IJoulerPolicy;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.BatteryStats.Uid;
import android.os.UserHandle;
import android.telephony.SignalStrength;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import static android.os.BatteryStats.NETWORK_MOBILE_RX_BYTES;
import static android.os.BatteryStats.NETWORK_MOBILE_TX_BYTES;
import static android.os.BatteryStats.NETWORK_WIFI_RX_BYTES;
import static android.os.BatteryStats.NETWORK_WIFI_TX_BYTES;

public class JoulerPolicyService extends IJoulerPolicy.Stub {

    //static IJoulerPolicy mService;
    final JoulerStats joulerStats;
    final Context mContext;
    AlarmManager alarmMgr;
    final String TAG = "JoulerPolicyService";
    private static final String NETD_TAG = "NetdConnectorJouler";
    final int which = BatteryStats.STATS_SINCE_CHARGED;

    private static ArrayList<Integer> cpuFrequency = new ArrayList<Integer>();

    private NativeDaemonConnector mConnector;
    private volatile boolean mBandwidthControlEnabled;
    private final Handler mMainHandler = new Handler();

    private Thread mThread;
    private CountDownLatch mConnectedSignal = new CountDownLatch(1);
    private static long initialRx = 0;
    private static long initialTx = 0;


    private ArrayList<String> badPkgs;
    private ArrayList<String> okayPkgs;
    private ArrayList<String> goodPkgs;
    private static List<Integer> rateLimitUids;

    private final static int GOOD_DELAY_TIME = 0;
    private final static int OKAY_DELAY_RATIO = 2;
    private final static int BAD_DELAY_RATIO = 1;
    private final static long MAX_QUOTA = 4611686018427387904L;

    class NetdResponseCode {
        /* Keep in sync with system/netd/ResponseCode.h */
        public static final int InterfaceListResult       = 110;
        public static final int TetherInterfaceListResult = 111;
        public static final int TetherDnsFwdTgtListResult = 112;
        public static final int TtyListResult             = 113;

        public static final int TetherStatusResult        = 210;
        public static final int IpFwdStatusResult         = 211;
        public static final int InterfaceGetCfgResult     = 213;
        public static final int SoftapStatusResult        = 214;
        public static final int InterfaceRxCounterResult  = 216;
        public static final int InterfaceTxCounterResult  = 217;
        public static final int InterfaceRxThrottleResult = 218;
        public static final int InterfaceTxThrottleResult = 219;
        public static final int QuotaCounterResult        = 220;
        public static final int TetheringStatsResult      = 221;
        public static final int DnsProxyQueryResult       = 222;

        public static final int InterfaceChange           = 600;
        public static final int BandwidthControl          = 601;
        public static final int InterfaceClassActivity    = 613;
    }



    BroadcastReceiver updateReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                String act = intent.getAction();
                if (act == "ACTION_BATTERY_CHANGED") {
                    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    if (status == BatteryManager.BATTERY_STATUS_FULL && joulerStats != null && joulerStats.mUidArray.size() > 0) {
                        synchronized(joulerStats) {
                            joulerStats.mSystemStats.resetOnCharge();
                            int size = joulerStats.mUidArray.size();
                            for (int i=0;i<size;i++) {
                                UidStats uStats = joulerStats.mUidArray.valueAt(i);
                                int uid = uStats.getUid();
                                uStats.reset();
                                joulerStats.mUidArray.put(uid,uStats);
                            }

                        }
                    }else{
                        updateStats();
                    }
                }
                else {

                    Log.i(TAG,"Updating Stats");
                    updateStats();
                    //printUid();
                }
            }

        };


    public JoulerPolicyService(Context context) {
        super();
        mContext = context;
        joulerStats = new JoulerStats();

    }

    public void systemReady() {
        IntentFilter intent = new IntentFilter();
        rateLimitUids = new ArrayList<Integer>();
        intent.addAction(Intent.ACTION_BATTERY_CHANGED);
        intent.addAction(Intent.ACTION_RESUME_ACTIVITY);
        intent.addAction(Intent.ACTION_PAUSE_ACTIVITY);
        mContext.registerReceiver(updateReceiver, intent);
        mConnector = new NativeDaemonConnector(
            new NetdCallbackReceiver(), "netd", 10, NETD_TAG, 160);
        mThread = new Thread(mConnector, NETD_TAG);
        final CountDownLatch connectedSignal = this.mConnectedSignal;
        this.mThread.start();
        try {
            connectedSignal.await();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            Log.e(TAG,"Daemon Problem "+e.getMessage());
        }

/***********/
        prepareNativeDaemon();

        bandwidthRules();
        initialRx = TrafficStats.getTotalRxBytes();
        initialTx = TrafficStats.getTotalTxBytes();

        if (!cpuFrequency.isEmpty())
            return;

        try {
            java.lang.Process cmd = new ProcessBuilder(new String[]{"sh","-c","/system/bin/cat  sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies"})
                .redirectErrorStream(true).start();
            InputStream in = cmd.getInputStream();
            BufferedReader buf = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line=buf.readLine()) != null) {
                for (String retval : line.split(" ")) {
                    cpuFrequency.add(Integer.parseInt(retval));
                }
            }

            in.close();
            Log.i(TAG, "Did i get all the frequencies, huh? "+ cpuFrequency.toString());
        }catch (Exception e) {
            Log.e(TAG, "Cpu info "+e.getMessage());
        }

    }








    //Compute Uid Energy by Component
    /*******************************/
    double getAudioEnergy(Uid u, long uSecTime, PowerProfile mPowerProfile) {
        double power = 0;
        long audioRunningMs = 0;
        try{
            audioRunningMs = u.getAudioTurnedOnTime(uSecTime, which);
            power = audioRunningMs*mPowerProfile.getAveragePower(PowerProfile.POWER_AUDIO)/1000;
        }catch(Exception e) {
            Log.e(TAG, "Error computing audio energy: "+e.getMessage());
        }
        return power;
    }

    double getVideoEnergy(Uid u, long uSecTime, PowerProfile mPowerProfile) {
        double power = 0;
        long videoRunningMs = 0;
        try{
            videoRunningMs = u.getVideoTurnedOnTime(uSecTime, which);
            power = videoRunningMs*mPowerProfile.getAveragePower(PowerProfile.POWER_VIDEO)/1000;
        }catch(Exception e) {
            Log.e(TAG,"Error computing video energy: "+e.getMessage());
        }
        return power;
    }

    double getMobileTrafficEnergy(BatteryStatsImpl mStats, Uid u, PowerProfile mPowerProfile) {
        double power = 0;
        final long mobileRx = u.getNetworkActivityCount(NETWORK_MOBILE_RX_BYTES, BatteryStats.STATS_SINCE_CHARGED);
        final long mobileTx = u.getNetworkActivityCount(NETWORK_MOBILE_TX_BYTES, BatteryStats.STATS_SINCE_CHARGED);
        final double mobilePowerPerByte = getMobilePowerPerByte(mStats, mPowerProfile);
        power = (mobileRx + mobileTx) * mobilePowerPerByte;
        return power;
    }

    double getWifiTrafficEnergy(BatteryStats.Uid u, PowerProfile mPowerProfile) {
        double power = 0;
        final long wifiRx = u.getNetworkActivityCount(NETWORK_WIFI_RX_BYTES, BatteryStats.STATS_SINCE_CHARGED);
        final long wifiTx = u.getNetworkActivityCount(NETWORK_WIFI_TX_BYTES, BatteryStats.STATS_SINCE_CHARGED);
        final double wifiPowerPerByte = getWifiPowerPerByte(mPowerProfile);
        power = (wifiRx + wifiTx) * wifiPowerPerByte;
        return power;
    }

    /* Wifi energy is cost of wifi running + cost of wifi scans */
    double getWifiEnergy(Uid u, long uSecTime, PowerProfile mPowerProfile) {
        double power = 0;
        long wifiRunningTimeMs = 0;
        long wifiScanTimeMs = 0;
        try{
            wifiRunningTimeMs = u.getWifiRunningTime(uSecTime, which) / 1000;
            power += (wifiRunningTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / 1000;
            wifiScanTimeMs = u.getWifiScanTime(uSecTime, which) / 1000;
            power += (wifiScanTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_SCAN)) / 1000;

        }catch(Exception e) {
            Log.e(TAG, "Error @ computing wifi power: "+e.getMessage());
        }
        return power;
    }

    double getCpuEnergy(Uid u, long uSecTime, PowerProfile mPowerProfile) {
        double power = 0;
        try {
            long cpuTime = 0;
            long cpuFgTime = 0;

            final int speedSteps = mPowerProfile.getNumSpeedSteps();
            final double[] powerCpuNormal = new double[speedSteps];
            final long[] cpuSpeedStepTimes = new long[speedSteps];
            for (int p = 0; p < speedSteps; p++) {
                powerCpuNormal[p] = mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE, p);
            }
            Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            if (processStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent : processStats.entrySet()) {
                    Uid.Proc ps = ent.getValue();
                    final long userTime = ps.getUserTime(which);
                    final long systemTime = ps.getSystemTime(which);
                    final long foregroundTime = ps.getForegroundTime(which);
                    cpuFgTime += foregroundTime * 10; // convert to millis
                    final long tmpCpuTime = (userTime + systemTime) * 10; // convert to millis
                    int totalTimeAtSpeeds = 0;
                    // Get the total first
                    for (int step = 0; step < speedSteps; step++) {
                        cpuSpeedStepTimes[step] = ps.getTimeAtCpuSpeedStep(step, which);
                        totalTimeAtSpeeds += cpuSpeedStepTimes[step];
                    }
                    if (totalTimeAtSpeeds == 0) totalTimeAtSpeeds = 1;

                    // Then compute the ratio of time spent at each speed
                    double processPower = 0;
                    for (int step = 0; step < speedSteps; step++) {
                        double ratio = (double) cpuSpeedStepTimes[step] / totalTimeAtSpeeds;
                        processPower += ratio * tmpCpuTime * powerCpuNormal[step];
                    }
                    cpuTime += tmpCpuTime;
                    power += processPower;
                }

            }
            power /= 1000;
        }catch(Exception e) {
            Log.e(TAG,"Error while computing cpu: "+e.getMessage());
        }
        return power;
    }

    double getWakelockEnergy(Uid u, long uSecTime, PowerProfile mPowerProfile) {
        double power = 0;
        long wakelockTime = 0;
        // Process wake lock usage
        Map<String, ? extends BatteryStats.Uid.Wakelock> wakelockStats = u.getWakelockStats();
        for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> wakelockEntry : wakelockStats.entrySet()) {
            Uid.Wakelock wakelock = wakelockEntry.getValue();
            // Only care about partial wake locks since full wake locks
            // are canceled when the user turns the screen off.
            BatteryStats.Timer timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
            if (timer != null) {
                wakelockTime += timer.getTotalTimeLocked(uSecTime, which);
            }
        }
        wakelockTime /= 1000; // convert to millis
        // Add cost of holding a wake lock
        power = (wakelockTime
                 * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_AWAKE)) / 1000;
        return power;
    }

    double getSensorEnergy(Uid u, long uSecTime, PowerProfile mPowerProfile, Context mContext) {
        double power = 0;
        try{
            long sensorTimeMs = 0;
            long gpsTime = 0;
            List<Integer> sensors = new ArrayList<Integer>();
            SensorManager sensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);

            // Process Sensor usage
            Map<Integer, ? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();

            for (Map.Entry<Integer, ? extends BatteryStats.Uid.Sensor> sensorEntry : sensorStats.entrySet()) {
                Uid.Sensor sensor = sensorEntry.getValue();
                int sensorType = sensor.getHandle();
                BatteryStats.Timer timer = sensor.getSensorTime();
                long sensorTime = timer.getTotalTimeLocked(uSecTime, which) / 1000;
                double multiplier = 0;
                switch (sensorType) {
                case Uid.Sensor.GPS:
                    multiplier = mPowerProfile.getAveragePower(PowerProfile.POWER_GPS_ON);
                    gpsTime = sensorTime;
                    break;
                default:
                    android.hardware.Sensor sensorData = sensorManager.getDefaultSensor(sensorType);
                    if (sensorData != null) {
                        multiplier = sensorData.getPower();
                    }
                }
                power += (multiplier * sensorTime) / 1000;
                sensorTimeMs += sensorTime;
                sensors.add(sensorType);
            }
        }catch(Exception e) {
            Log.e(TAG,"Error calculating sensor energy: "+e.getMessage());
        }

        return power;
    }

    //Compute Energy Consumption by System
    /************************************/
    public double getScreenEnergy(BatteryStatsImpl mStats, long uSecNow, PowerProfile mPowerProfile) {
        double power = 0;
        long screenOnTimeMs = mStats.getScreenOnTime(uSecNow, BatteryStats.STATS_SINCE_CHARGED) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
            mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            double screenBinPower = screenFullPower * (i + 0.5f)
                / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            long brightnessTime = mStats.getScreenBrightnessTime(i, uSecNow, BatteryStats.STATS_SINCE_CHARGED) / 1000;
            power += screenBinPower * brightnessTime;
        }
        power /= 1000;
        return power;
    }

    public double getPhoneEnergy(BatteryStatsImpl mStats, long uSecNow, PowerProfile mPowerProfile) {
        long phoneOnTimeMs = mStats.getPhoneOnTime(uSecNow, BatteryStats.STATS_SINCE_CHARGED) / 1000;
        double phoneEnergy = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
            * phoneOnTimeMs / 1000;
        return phoneEnergy;
    }

    public double getWifiEnergy(BatteryStatsImpl mStats, long uSecNow, PowerProfile mPowerProfile) {
        double power = 0;
        long onTimeMs = mStats.getWifiOnTime(uSecNow, BatteryStats.STATS_SINCE_CHARGED) / 1000;
        long runningTimeMs = mStats.getGlobalWifiRunningTime(uSecNow, BatteryStats.STATS_SINCE_CHARGED) / 1000;
        power = (onTimeMs * 0 /* TODO */
                 * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)
                 + runningTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / 1000;
        return power;
    }

    public double getRadioEnergy(BatteryStatsImpl mStats, long uSecNow, PowerProfile mPowerProfile) {
        double power = 0;
        final int BINS = SignalStrength.NUM_SIGNAL_STRENGTH_BINS;
        for (int i = 0; i < BINS; i++) {
            long strengthTimeMs = mStats.getPhoneSignalStrengthTime(i, uSecNow,BatteryStats.STATS_SINCE_CHARGED) / 1000;
            power += strengthTimeMs / 1000
                * mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ON, i);
        }
        long scanningTimeMs = mStats.getPhoneSignalScanningTime(uSecNow, BatteryStats.STATS_SINCE_CHARGED) / 1000;
        power += scanningTimeMs / 1000 * mPowerProfile.getAveragePower(
            PowerProfile.POWER_RADIO_SCANNING);
        return power;
    }

    public double getIdleEnergy(BatteryStatsImpl mStats, long uSecNow, PowerProfile mPowerProfile) {
        double power = 0;
        long idleTimeMs = (uSecNow - mStats.getScreenOnTime(uSecNow, BatteryStats.STATS_SINCE_CHARGED)) / 1000;
        power = (idleTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE))
            / 1000;
        return power;
    }

    public double getBluetoothEnergy(BatteryStatsImpl mStats, long uSecNow, PowerProfile mPowerProfile) {
        long btOnTimeMs = mStats.getBluetoothOnTime(uSecNow, BatteryStats.STATS_SINCE_CHARGED) / 1000;
        double btPower = btOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_ON)
            / 1000;
        int btPingCount = mStats.getBluetoothPingCount();
        btPower += (btPingCount
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_AT_CMD)) / 1000;
        return btPower;
    }


    /**
     * Return estimated power (in mAs) of sending a byte with the mobile radio.
     */
    private double getMobilePowerPerByte(BatteryStatsImpl mStats, PowerProfile mPowerProfile) {
        final long MOBILE_BPS = 200000; // TODO: Extract average bit rates from system
        final double MOBILE_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)/ 3600;

        final long mobileRx = mStats.getNetworkActivityCount(NETWORK_MOBILE_RX_BYTES, BatteryStats.STATS_SINCE_CHARGED);
        final long mobileTx = mStats.getNetworkActivityCount(NETWORK_MOBILE_TX_BYTES, BatteryStats.STATS_SINCE_CHARGED);
        final long mobileData = mobileRx + mobileTx;

        final long radioDataUptimeMs = mStats.getRadioDataUptime() / 1000;
        final long mobileBps = radioDataUptimeMs != 0
            ? mobileData * 8 * 1000 / radioDataUptimeMs
            : MOBILE_BPS;

        return MOBILE_POWER / (mobileBps / 8);
    }

    /**
     * Return estimated power (in mAs) of sending a byte with the Wi-Fi radio.
     */
    private double getWifiPowerPerByte(PowerProfile mPowerProfile) {
        final long WIFI_BPS = 1000000; // TODO: Extract average bit rates from system
        final double WIFI_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE) / 3600;
        return WIFI_POWER / (WIFI_BPS / 8);
    }

    //Update generic battery discharge info
    public long getUpTime(BatteryStatsImpl mStats) {
        long uSec = mStats.computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, BatteryStats.STATS_SINCE_CHARGED);
        //long uSec = mStats.computeBatteryUptime(SystemClock.uptimeMillis() * 1000,BatteryStats.STATS_SINCE_CHARGED);

        return uSec/1000;
    }

    public int getTotalDischarge(BatteryStatsImpl mStats) {
        int discharge = mStats.getHighDischargeAmountSinceCharge();
        Log.i(TAG, "discharge="+mStats.getHighDischargeAmountSinceCharge());
        return discharge;

    }

    public int getForegroundDischarge(BatteryStatsImpl mStats) {
        int discharge = mStats.getDischargeAmountScreenOnSinceCharge();
        return discharge;
    }

    public int getBackgroundDischarge(BatteryStatsImpl mStats) {
        int discharge = mStats.getDischargeAmountScreenOffSinceCharge();
        return discharge;
    }

    public void printSys() {
        Log.i(TAG, "Total DischargeRate = "+joulerStats.mSystemStats.getCurrentDischargeRate()+" FgDischargeRate = "+joulerStats.mSystemStats.getCurrentFgDischargeRate()
              +" BgDischargeRate = "+joulerStats.mSystemStats.getCurrentFgDischargeRate()+" Screen On = "+joulerStats.mSystemStats.getTotalScreenOnTime());
        Log.i(TAG,"Energy Details: Phone = "+joulerStats.mSystemStats.getPhoneEnergy()+" Wifi = "+joulerStats.mSystemStats.getWifiEnergy()
              +" Screen = "+joulerStats.mSystemStats.getScreenEnergy()+" Radio = "+joulerStats.mSystemStats.getRadioEnergy());
    }

    public void printUid() {
        for (int i=0; i<joulerStats.mUidArray.size(); i++) {
            UidStats u = joulerStats.mUidArray.valueAt(i);
            Log.i(TAG,"Uid: "+u.getUid()+" Pkg: "+u.packageName);
            Log.i(TAG,"Uid: "+u.getUid()+"Fg= "+u.getFgEnergy()+" Bg= "+u.getBgEnergy()+" Cpu= "+u.getCpuEnergy()+" Wakelock= "+u.getWakelockEnergy()+" Wifi= "+u.getWifiEnergy()
                  +" Mobile Data= "+u.getMobileDataEnergy()+" Wifi Data= "+u.getWifiDataEnergy()+" Video= "+u.getVideoEnergy());
            Log.i(TAG,"Uid: "+u.getUid()+" Frames= "+u.getFrame()+" Launches= "+u.getCount()+" Usage= "+u.getUsageTime());
        }
    }

    //for calculating latest energy details and updating the JoulerStats object
    public void updateStats() {
        resetQuota();
        synchronized(joulerStats) {
            BatteryStatsImpl mStats = ActivityManagerService.self().mBatteryStatsService.getActiveStatistics();
            Context mContext =  ActivityManagerService.self().mContext;
            PowerProfile mPowerProfile = new PowerProfile(mContext);

            //double averageCostPerByte = getAverageDataCost(mStats, mPowerProfile);
            long uSecTime = mStats.computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, BatteryStats.STATS_SINCE_CHARGED);
            PackageManager pm = mContext.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

            SparseArray<? extends Uid> uidStats = mStats.getUidStats();

            synchronized(mStats) {

                final int NU = uidStats.size();
                for (int iu = 0; iu < NU; iu++) {
                    BatteryStats.Uid u = uidStats.valueAt(iu);
                    int appId = u.getUid();
                    if (joulerStats.mUidArray.size() == 0 || joulerStats.mUidArray.indexOfKey(appId) < 0) {
                        JoulerStats.UidStats tmp = joulerStats.new UidStats(appId);
                        for (ApplicationInfo packageInfo : packages) {
                            if (packageInfo.uid == appId) {
                                tmp.packageName = packageInfo.packageName;
                                break;
                            }
                        }
                        joulerStats.mUidArray.append(appId, tmp);
                    }
                    JoulerStats.UidStats uStats = joulerStats.mUidArray.get(appId);
                    double audio = getAudioEnergy(u, uSecTime, mPowerProfile);
                    double video = getVideoEnergy(u, uSecTime, mPowerProfile);
                    double cpu = getCpuEnergy(u, uSecTime, mPowerProfile);
                    double wakelock = getWakelockEnergy(u, uSecTime, mPowerProfile);
                    double mobileData = getMobileTrafficEnergy(mStats, u, mPowerProfile);
                    double wifiData = getWifiTrafficEnergy(u, mPowerProfile);
                    double wifi = getWifiEnergy(u, uSecTime, mPowerProfile);
                    double sensor = getSensorEnergy(u, uSecTime, mPowerProfile, mContext);
                    double power = uStats.updateEnergy(cpu, wakelock, mobileData, wifiData, wifi, sensor, video, audio);
                    if (uStats.state == true)
                        uStats.updateFgEnergy(power);
                    else
                        uStats.updateBgEnergy(power);
                    if (u.hasUserActivity())
                        uStats.uiActivity = u.getUserActivityCount(0, BatteryStats.STATS_SINCE_CHARGED)+
                            u.getUserActivityCount(1, BatteryStats.STATS_SINCE_CHARGED)+
                            u.getUserActivityCount(2, BatteryStats.STATS_SINCE_CHARGED);
                    else
                        uStats.uiActivity = -1;
                    uStats.updateCommonEnergy(power);

                    //updating uid details
                    joulerStats.mUidArray.put(appId, uStats);
                }

                //Updating System Details
                double screen = getScreenEnergy(mStats, uSecTime, mPowerProfile);
                double phone = getPhoneEnergy(mStats, uSecTime, mPowerProfile);
                double radio = getRadioEnergy(mStats, uSecTime, mPowerProfile);
                double wifi = getWifiEnergy(mStats, uSecTime, mPowerProfile);
                double idle = getIdleEnergy(mStats, uSecTime, mPowerProfile);
                double bt = getBluetoothEnergy(mStats, uSecTime, mPowerProfile);
                joulerStats.mSystemStats.updateEnergy(screen, phone, radio, wifi, idle, bt);
                joulerStats.mSystemStats.updateUpTime(getUpTime(mStats));
                joulerStats.mSystemStats.updateTotalDischargeRate(mStats.getHighDischargeAmountSinceCharge());
                joulerStats.mSystemStats.updateForegroundDischargeRate(mStats.getDischargeAmountScreenOnSinceCharge());
                joulerStats.mSystemStats.updateBackgroundDischargeRate(mStats.getDischargeAmountScreenOffSinceCharge());

            }
        }

    }

    public void updateLaunchForPkg(String pkg, int count, long uTime) {
        PackageManager pm = mContext.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo packageInfo : packages) {
            if (packageInfo.packageName.equals(pkg)) {
                UidStats u = joulerStats.mUidArray.get(packageInfo.uid);
                u.setCount(count);
                u.setUsageTime(uTime);
                //Log.i(TAG,"Successfully updated "+u.getCount()+" launches and "+u.getUsageTime()+" usage time");
                break;
            }
        }

    }

    //startFgMonitor and stoFgMonitor are functions to update energy details of the uid whose activity
    //came and left screen foreground respectively in order to correctly distribute total energy to
    //foreground and background
    public void startFgMonitor(int appId) {
        BatteryStatsImpl mStats = ActivityManagerService.self().mBatteryStatsService.getActiveStatistics();
        synchronized(mStats) {
            Uid mUid = mStats.getUidStatsLocked(appId);
            PowerProfile mPowerProfile = new PowerProfile(mContext);
            //double averageCostPerByte = getAverageDataCost(mStats, mPowerProfile);
            long uSecTime = mStats.computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, BatteryStats.STATS_SINCE_CHARGED);
            PackageManager pm = mContext.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (joulerStats.mUidArray.indexOfKey(appId) < 0) {
                UidStats u = joulerStats.new UidStats(appId);
                for (ApplicationInfo packageInfo : packages) {
                    if (packageInfo.uid == appId) {
                        u.packageName = packageInfo.packageName;
                        break;
                    }
                }
                joulerStats.mUidArray.append(appId, u);

            }

            UidStats u = joulerStats.mUidArray.get(appId);
            if (!u.getState()) {
                u.setState(true);
                double audio = getAudioEnergy(mUid, uSecTime, mPowerProfile);
                double video = getVideoEnergy(mUid, uSecTime, mPowerProfile);
                double cpu = getCpuEnergy(mUid, uSecTime, mPowerProfile);
                double wakelock = getWakelockEnergy(mUid, uSecTime, mPowerProfile);
                double mobileData = getMobileTrafficEnergy(mStats, mUid, mPowerProfile);
                double wifiData = getWifiTrafficEnergy(mUid, mPowerProfile);
                double wifi = getWifiEnergy(mUid, uSecTime, mPowerProfile);
                double sensor = getSensorEnergy(mUid, uSecTime, mPowerProfile, mContext);
                double power = u.updateEnergy(cpu, wakelock, mobileData, wifiData, wifi, sensor, video, audio);
                u.updateBgEnergy(power);
                u.updateCommonEnergy(power);
                joulerStats.mUidArray.put(appId, u);
                Log.i("JoulerDebug", "startMonitor "+ u.packageName+" Updated: "+u.getFgEnergy());

            }

        }

    }

    public void stopFgMonitor(int appId) {
        //Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        //float refreshRating = display.getRefreshRate();
        //Log.i(TAG, "refreshRate = "+refreshRating);
        BatteryStatsImpl mStats = ActivityManagerService.self().mBatteryStatsService.getActiveStatistics();
        synchronized(mStats) {

            Uid mUid = mStats.getUidStatsLocked(appId);
            PowerProfile mPowerProfile = new PowerProfile(mContext);
            //double averageCostPerByte = getAverageDataCost(mStats, mPowerProfile);
            long uSecTime = mStats.computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, BatteryStats.STATS_SINCE_CHARGED);
            UidStats u = joulerStats.mUidArray.get(appId);
            if (u.getState()) {
                double audio = getAudioEnergy(mUid, uSecTime, mPowerProfile);
                double video = getVideoEnergy(mUid, uSecTime, mPowerProfile);
                double cpu = getCpuEnergy(mUid, uSecTime, mPowerProfile);
                double wakelock = getWakelockEnergy(mUid, uSecTime, mPowerProfile);
                double mobileData = getMobileTrafficEnergy(mStats, mUid, mPowerProfile);
                double wifiData = getWifiTrafficEnergy(mUid, mPowerProfile);
                double wifi = getWifiEnergy(mUid, uSecTime, mPowerProfile);
                double sensor = getSensorEnergy(mUid, uSecTime, mPowerProfile, mContext);
                double power = u.updateEnergy(cpu, wakelock, mobileData, wifiData, wifi, sensor, video, audio);
                u.updateFgEnergy(power);
                Log.i("JoulerDebug", "stopMonitor "+u.packageName+" Updated: "+u.getFgEnergy());
                u.updateCommonEnergy(power);
                u.setState(false);
                joulerStats.mUidArray.put(appId, u);

                try{
                    long frames = getMeMyFrameCount(u.packageName);
                    u.setFrame(frames);
                }catch(Exception e) {
                    Log.e(TAG,"Error getting frameCount "+ e.getMessage());
                }
                //Log.i(TAG,"stop Fg monitor: "+u.packageName+" uid= "+u.getUid()+" frames rendered: "+u.getFrame());
            }
        }
        //printUid();
    }

    public long getMeMyFrameCount(String packageName) {
        long frames = -1;
        if (packageName.contains("launcher") || packageName.equals("android"))
            return frames;
        Log.i("IMDebugging","Getting frames for "+packageName);
        Runtime rt = Runtime.getRuntime();
        try{

            FileOutputStream fOut = new FileOutputStream(new File("/data/data/dumpGfx.txt"));
            OutputStream out = new BufferedOutputStream(fOut);
            FileDescriptor fd = fOut.getFD();
            PrintWriter pw = new PrintWriter(out);
            String[] args = {packageName};
            ActivityManagerService.self().dumpGraphicsHardwareUsage(fd, pw, args);
            pw.flush();
            pw.close();

            FileInputStream fIn = new FileInputStream(new File("/data/data/dumpGfx.txt"));
            InputStreamReader in = new InputStreamReader(fIn);
            BufferedReader buf = new BufferedReader(in);
            String line;
            while ((line=buf.readLine()) != null) {
                if (line.contains("frames rendered")) {
                    String data = (line.split(", ")[2]).split(" ")[0];
                    frames = Integer.parseInt(data);
                    Log.i("IMDebugging","frames = "+data);
                    break;
                }
            }

            in.close();

        }catch(Exception e) {
            Log.e("IMDebugging","Error getting framecount "+e.getMessage());
        }

        return frames;
    }

    //@Override
    public byte[] getStatistics() throws RemoteException {
        enforceCallingPermission();
        Parcel out = Parcel.obtain();
        joulerStats.writeToParcel(out, 0);
        byte[] data = out.marshall();
        out.recycle();
        return data;
    }

    //@Override
    public void controlCpuMaxFrequency(int freq) throws RemoteException {
        enforceCallingPermission();
        try {
            java.lang.Process cmd = new ProcessBuilder(new String[]{"sh","-c","/system/xbin/echo "+freq+" > sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"})
                .redirectErrorStream(true).start();
            InputStream in = cmd.getInputStream();
            BufferedReader buf = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line=buf.readLine()) != null) {
                Log.i(TAG,"writing to cpu0/cpufreq/scaling_available_gov file: " + line );
            }

            in.close();

        }catch (Exception e) {
            Log.e(TAG, "Cpu info "+e.getMessage());
        }

    }

    //@Override
    public int[] getAllCpuFrequencies() {
        enforceCallingPermission();
        int[] cpu = new int[cpuFrequency.size()];
        for (int i=0; i < cpuFrequency.size(); i++)
            cpu[i] = cpuFrequency.get(i);
        return cpu;
    }

    public void bandwidthRules() {
        Log.i(TAG,"About to set bandwidth rules");
        try {
            if (!mBandwidthControlEnabled) return;
            long quotaBytes = MAX_QUOTA;
            Log.i(TAG,"Setting bandwidth quota for wlan0 to: " + quotaBytes);
            mConnector.execute("bandwidth", "setiquota", "wlan0", quotaBytes);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }


    private void resetQuota() {
        long currentBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        if ((currentBytes - (initialTx + initialRx)) > (MAX_QUOTA / 2)) {
            bandwidthRules();
            initialRx = TrafficStats.getTotalRxBytes();
            initialTx = TrafficStats.getTotalTxBytes();
        }
    }

    public void addRateLimitRule(int uid) {
        if (uid == -1) {
            Log.i(TAG,"experiment to set bandwidth rules");
            try {
                if (!mBandwidthControlEnabled) {
                    Log.i(TAG,"Not enabled");
                    return;
                }
                long quotaBytes = MAX_QUOTA;
                Log.i(TAG,"Setting bandwidth quota to: " + quotaBytes);
                mConnector.execute("bandwidth", "setiquota", "wlan0", quotaBytes);
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
            return;
        }

        if (!checkForRateLimit(uid)) {
            return;
        }
        UidStats uStats = joulerStats.mUidArray.get(uid);
        try {
            Log.i(TAG, "add ratelimit for: " + uid);
            mConnector.execute("bandwidth","addnaughtyapps", uid);
            uStats.setThrottle(true);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    public void delRateLimitRule(int uid) {
        if (uid == -1) {
            Log.i(TAG,"experiment to set bandwidth rules");
            try {
                if (!mBandwidthControlEnabled) {
                    Log.i(TAG,"Not enabled");
                    return;
                }
                mConnector.execute("bandwidth", "removeiquota", "wlan0");
            } catch (NativeDaemonConnectorException e) {
                throw e.rethrowAsParcelableException();
            }
        }

        if (!checkForRateLimit(uid)) {
            return;
        }
        UidStats uStats = joulerStats.mUidArray.get(uid);
        try {
            Log.i(TAG, "del ratelimit for: " + uid);
            mConnector.execute("bandwidth","removenaughtyapps", uid);
            uStats.setThrottle(false);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    // enforce permission
    // check joulerStats is not null, the mUidArray is valid, the index is valid.
    private boolean checkForRateLimit(int uid) {
        enforceCallingPermission();
        if (joulerStats == null || joulerStats.mUidArray.size() == 0 || joulerStats.mUidArray.indexOfKey(uid) < 0) {
            return false;
        }
        return true;
    }

    //@Override
    public void broadcastAlertIntent(List<String> badPackages, List<String> okayPackages, List<String> goodPackages)
        throws RemoteException {
        enforceCallingPermission();
        badPkgs = (ArrayList<String>) badPackages;
        okayPkgs = (ArrayList<String>) okayPackages;
        goodPkgs = (ArrayList<String>) goodPackages;
        Intent intent = new Intent(Intent.ACTION_ENERGY_ALERT);
        intent.putExtra("EXTRA_BAD_PACKAGE_LIST", badPkgs);
        intent.putExtra("EXTRA_OKAY_PACKAGE_LIST", okayPkgs);
        intent.putExtra("EXTRA_GOOD_PACKAGE_LIST", goodPkgs);
        intent.putExtra("EXTRA_TIME", SystemClock.elapsedRealtime());

        mContext.sendStickyBroadcastAsUser(intent, UserHandle.OWNER);
    }

    public void setDelayedTask(PendingIntent pendingIntent, int maxDelayedTime) throws RemoteException {
        AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(System.currentTimeMillis());
        String packageName =  pendingIntent.getCreatorPackage();
        int delayedTime = this.getProperDelay(packageName, maxDelayedTime);
        time.add(Calendar.SECOND, delayedTime);
        Log.d(TAG, "Set delayedTime: " + delayedTime + " for " + packageName);
        am.set(AlarmManager.RTC_WAKEUP, time.getTimeInMillis(), pendingIntent);
    }

    private int getProperDelay(String packageName, int maxDelayedTime) {
        if (this.contains(goodPkgs, packageName)) {
            return GOOD_DELAY_TIME;
        }
        if (this.contains(okayPkgs, packageName)) {
            return maxDelayedTime / OKAY_DELAY_RATIO;
        }
        if (this.contains(badPkgs, packageName)) {
            return maxDelayedTime / BAD_DELAY_RATIO;
        }
        return maxDelayedTime / BAD_DELAY_RATIO;
    }

    private boolean contains(ArrayList<String> pkgs, String packageName) {
        if (pkgs == null) {
            return false;
        }
        for (String tmpPkgName : pkgs) {
            if (tmpPkgName.contains(packageName) || packageName.contains(tmpPkgName)) {
                return true;
            }
        }
        return false;
    }

    public int getPriority(int uid) {
        int priority = -100;
        ArrayList<Integer> priorityAll = new ArrayList<Integer>();
        if (joulerStats == null || joulerStats.mUidArray.size() == 0 || joulerStats.mUidArray.indexOfKey(uid) < 0)
            return priority;

        String userName;
        if (uid == 0)
            userName = "root";
        else if (uid <= 10000)
            userName = "system";
        else
            userName = "u0_a"+(uid%10000);
        int pid = -1;
        try {
            java.lang.Process cmd = new ProcessBuilder(new String[]{"sh","-c","/system/bin/ps | grep "+userName})
                .redirectErrorStream(true)
                .start();
            InputStream in = cmd.getInputStream();
            BufferedReader buf = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line=buf.readLine()) != null) {
                Log.i(TAG,"getting matched entry: " + line );
                String token[] = line.split("\\s+");

                pid = Integer.parseInt(token[1]);
                Log.i(TAG, "PID="+pid);
            }

            in.close();
            if (pid == -1)
                return priority;
            ArrayList<Integer>tidAll = getMyThreadId(pid);

            for (int tid: tidAll) {
                if (tid !=-1) {
                    Log.i(TAG,"Priority for "+tid+" = "+android.os.Process.getThreadPriority(tid));
                    priorityAll.add(android.os.Process.getThreadPriority(tid));
                }
            }
            if (priorityAll.size() > 0)
                priority = Collections.min(priorityAll);

        }catch (Exception e) {
            Log.e(TAG, "Process info "+e.getMessage());
        }

        return priority;

    }

    //@Override
    public void resetPriority(int uid, int priority) {
        enforceCallingPermission();
        if (joulerStats == null || joulerStats.mUidArray.size() == 0 || joulerStats.mUidArray.indexOfKey(uid) < 0)
            return;

        String userName;
        if (uid == 0)
            userName = "root";
        else if (uid <= 10000)
            userName = "system";
        else
            userName = "u0_a"+(uid%10000);
        int pid = -1;
        try {
            java.lang.Process cmd = new ProcessBuilder(new String[]{"sh","-c","/system/bin/ps | grep "+userName})
                .redirectErrorStream(true)
                .start();
            InputStream in = cmd.getInputStream();
            BufferedReader buf = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line=buf.readLine()) != null) {
                Log.i(TAG,"getting matched entry: " + line );
                String token[] = line.split("\\s+");

                pid = Integer.parseInt(token[1]);
                Log.i(TAG, "PID="+pid);
            }

            in.close();
            if (pid == -1) {
                return;
            }
            ArrayList<Integer>tidAll = getMyThreadId(pid);
            for (int tid: tidAll) {
                if (tid !=-1 && ( priority < 21 && priority > -20)) {
                    Log.i(TAG,"Priority for "+tid+" = "+android.os.Process.getThreadPriority(tid));
                    android.os.Process.setThreadPriority(tid, priority);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Process info "+e.getMessage());
        }

    }

    public ArrayList<Integer> getMyThreadId(int pid) {
        ArrayList<Integer> threadId = new ArrayList<Integer>();
        try {
            java.lang.Process cmd = new ProcessBuilder(new String[]{"sh","-c","/system/bin/ls /proc/"+pid+"/task/ "})
                .redirectErrorStream(true)
                .start();
            InputStream in = cmd.getInputStream();
            BufferedReader buf = new BufferedReader(new InputStreamReader(in));
            String line;

            while ((line=buf.readLine()) != null) {
                Log.i(TAG,"getting matched entry: " + line );
                if (line.matches("[0-9]+"))
                    threadId.add(Integer.parseInt(line));
                else
                    Log.i(TAG,"Instead of thread id found "+line);
            }
            in.close();
        }catch(Exception e) {
            Log.i(TAG,"My threads abandoned me "+pid+" : "+e.getMessage());
        }

        return threadId;
    }

    public void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        Log.i("AdaptiveTimer", "pid: "+Binder.getCallingPid() +" uid :"+Binder.getCallingUid());
        mContext.enforcePermission(android.Manifest.permission.ACCESS_JOULER,
                                   Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void prepareNativeDaemon() {
        mBandwidthControlEnabled = false;

        // only enable bandwidth control when support exists
        final boolean hasKernelSupport = new File("/proc/net/xt_qtaguid/ctrl").exists();
        Log.i(TAG,"File exists "+hasKernelSupport);
        if (hasKernelSupport) {
            Slog.d(TAG, "enabling bandwidth control");
            try {
                mConnector.execute("bandwidth", "enable");
                mBandwidthControlEnabled = true;
            } catch (NativeDaemonConnectorException e) {
                Log.wtf(TAG, "problem enabling bandwidth controls", e);
            }
        } else {
            Slog.d(TAG, "not enabling bandwidth control");
        }

        SystemProperties.set(PROP_QTAGUID_ENABLED, mBandwidthControlEnabled ? "1" : "0");
    }

    // Netd Callback handling
    private class NetdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        @Override
        public void onDaemonConnected() {
            // event is dispatched from internal NDC thread, so we prepare the
            // daemon back on main thread.
            if (mConnectedSignal != null) {
                mConnectedSignal.countDown();
                mConnectedSignal = null;
            } else {
                mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            prepareNativeDaemon();
                        }
                    });
            }
        }

        @Override
        public boolean onEvent(int code, String raw, String[] cooked) {
            switch (code) {
            case NetdResponseCode.InterfaceChange:
                /*
                 * a network interface change occured
                 * Format: "NNN Iface added <name>"
                 *         "NNN Iface removed <name>"
                 *         "NNN Iface changed <name> <up/down>"
                 *         "NNN Iface linkstatus <name> <up/down>"
                 */
                if (cooked.length < 4 || !cooked[1].equals("Iface")) {
                    throw new IllegalStateException(
                        String.format("Invalid event from daemon (%s)", raw));
                }
                if (cooked[2].equals("added")) {
                    return true;
                } else if (cooked[2].equals("removed")) {
                    return true;
                } else if (cooked[2].equals("changed") && cooked.length == 5) {
                    return true;
                } else if (cooked[2].equals("linkstate") && cooked.length == 5) {
                    return true;
                }
                throw new IllegalStateException(
                    String.format("Invalid event from daemon (%s)", raw));
                // break;
            case NetdResponseCode.BandwidthControl:
                /*
                 * Bandwidth control needs some attention
                 * Format: "NNN limit alert <alertName> <ifaceName>"
                 */
                if (cooked.length < 5 || !cooked[1].equals("limit")) {
                    throw new IllegalStateException(
                        String.format("Invalid event from daemon (%s)", raw));
                }
                if (cooked[2].equals("alert")) {
                    return true;
                }
                throw new IllegalStateException(
                    String.format("Invalid event from daemon (%s)", raw));
                // break;
            case NetdResponseCode.InterfaceClassActivity:
                /*
                 * An network interface class state changed (active/idle)
                 * Format: "NNN IfaceClass <active/idle> <label>"
                 */
                if (cooked.length < 4 || !cooked[1].equals("IfaceClass")) {
                    throw new IllegalStateException(
                        String.format("Invalid event from daemon (%s)", raw));
                }
                boolean isActive = cooked[2].equals("active");
                return true;
                // break;
            default:
                break;
            }
            return false;
        }
    }

}
