/*
 * Modeled after BatteryStatsImpl.java since the requirements or results
 * from this class is similar i.e. having energy related statistics
 * Energy is calculated as per packages/apps/Settings/../fuelgauge
 */
package android.os;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.telephony.SignalStrength;
import android.util.Log;
import android.util.SparseArray;

public final class JoulerStats implements Parcelable{

    public static final String TAG = "JoulerStats";
    public final SparseArray <JoulerStats.UidStats> mUidArray;
    public final JoulerStats.SystemStats mSystemStats;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 60 * 60;
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    public final class UidStats {
        final int mUid; //uid
        public boolean state;   //foreground/background state
        long fgTime;
        long bgTime;
        long mLastTime;

        double fgEnergy;
        double oldFgEnergy;
        double bgEnergy;
        double oldBgEnergy;
        double commonEnergy;
        double cpuEnergy;
        double sensorEnergy;
        double wifiEnergy;
        double wakelockEnergy;
        //double dataEnergy;
        double mobileDataEnergy;
        double wifiDataEnergy;
        double videoEnergy;
        double audioEnergy;
        public String packageName;
        float refreshRate;
        long frameCount;
        boolean countingFrames;
        int fgSessions;
        int launchCount;
        int lastLaunchCount;
        long usageTime;
        long lastUsageTime;
        public long uiActivity;
        boolean throttle;

        List<String> bgServices;
        List<String> fgServices;

        public UidStats(int uid) {
            mUid = uid;
            state = false;
            throttle = false;
            fgTime = 0;
            bgTime = 0;

            fgEnergy = 0;
            oldFgEnergy = 0;
            bgEnergy = 0;
            oldBgEnergy = 0;
            commonEnergy = 0;
            launchCount = 0;
            lastLaunchCount = 0;
            usageTime = 0;
            lastUsageTime = 0;
            frameCount = 0;
            bgServices =  new ArrayList<String>();
            fgServices =  new ArrayList<String>();
        }

        public double getFgEnergy(){
            return fgEnergy;
        }

        public double getBgEnergy(){
            return bgEnergy;
        }

        public double getCpuEnergy(){
            return cpuEnergy;
        }

        public double getWakelockEnergy(){
            return wakelockEnergy;
        }

        public double getWifiEnergy(){
            return wifiEnergy;
        }

        public double getSensorEnergy(){
            return sensorEnergy;
        }

/*              public double getDataEnergy(){
                return dataEnergy;
                }
*/
        public double getMobileDataEnergy(){
            return mobileDataEnergy;
        }

        public double getWifiDataEnergy(){
            return wifiDataEnergy;
        }

        public double getVideoEnergy(){
            return videoEnergy;
        }

        public double getAudioEnergy(){
            return audioEnergy;
        }

        public long getFrame(){
            return frameCount;
        }

        public void setFrame(long frames){
            frameCount = frames;
        }

        public void setState(boolean s) //True for foreground and False for background
            {
                state = s;
            }

        public boolean getState(){
            return state;
        }

        public void setThrottle(boolean s)      //True for foreground and False for background
            {
                throttle = s;
            }

        public boolean getThrottle(){
            return throttle;
        }

        public int getUid(){
            return mUid;
        }

        public void setCount(int count) {
            if(count >= lastLaunchCount)
                launchCount += count - lastLaunchCount;
            else
                launchCount += count;
            lastLaunchCount = count;

        }

        public int getCount() {
            return launchCount;
        }

        public void setUsageTime(long t) {
            if (t >= lastUsageTime)
                usageTime += t - lastUsageTime;
            else
                usageTime += usageTime;
            lastUsageTime = t;
        }

        public long getUsageTime() {
            return usageTime;
        }

        public void addBgServices(String className){
            if (bgServices.isEmpty() || !bgServices.contains(className)) {
                bgServices.add(className);
            }
        }

        public void removeBgServices(String className){
            if(!bgServices.isEmpty() || bgServices.contains(className))
                bgServices.remove(className);
        }

        public void removeFgServices(String className){
            if(!fgServices.isEmpty() || fgServices.contains(className))
                fgServices.remove(className);

        }

        public void addFgServices(String className){
            if (fgServices.isEmpty() || !fgServices.contains(className)) {
                fgServices.add(className);
            }
        }



        public void updateCommonEnergy(double power) {
            commonEnergy = power;
        }

        public void updateFgEnergy(double power) {
            if (power >= commonEnergy)
                fgEnergy += power - commonEnergy;
            else
                fgEnergy += power;
        }

        public void updateBgEnergy(double power) {
            if (power >= commonEnergy)
                bgEnergy += power - commonEnergy;
            else
                bgEnergy += power;

        }
        //1 for appearing in foreground and 0 for going to background
        void updateFgTime(long time) {
            if (state) {
                if(mLastTime != 0) bgTime += time - mLastTime;
                mLastTime = time;
            }
            else if (!state) {
                if(mLastTime != 0) fgTime += time - mLastTime;
                mLastTime = time;
            }
        }

        public double updateEnergy(double cpu, double wakelock, double mobiledata, double wifidata, double wifi, double sensor, double video, double audio) {
            double power = 0;
            try {

                // Process CPU usage
                cpuEnergy = cpu;
                // Process wake lock usage
                wakelockEnergy = wakelock;
                // Add cost of data traffic
                mobileDataEnergy = mobiledata;
                wifiDataEnergy = wifidata;
                // Add cost of keeping WIFI running.
                wifiEnergy = wifi;
                // Process Sensor usage
                sensorEnergy = sensor;
                //Add cost of Video usage
                videoEnergy = video;
                //Add cost of Audio usage
                audioEnergy = audio;

                power = cpuEnergy + wakelockEnergy + wifiEnergy + mobileDataEnergy + wifiDataEnergy +  sensorEnergy + videoEnergy + audioEnergy;

            }catch(Exception e){
                Log.e(TAG, "Error @ computing power");
            }
            return power;
        }

        public void reset(){
            resetEnergy();
            resetTime();
            resetCount();
        }

        public void resetEnergy() {
            fgEnergy = 0.0;
            oldFgEnergy = 0.0;
            bgEnergy = 0.0;
            oldBgEnergy = 0.0;
            commonEnergy = 0.0;
            cpuEnergy = 0.0;
            sensorEnergy = 0.0;
            wifiEnergy = 0.0;
            wakelockEnergy = 0.0;
            //dataEnergy = 0.0;
            wifiDataEnergy = 0.0;
            mobileDataEnergy = 0.0;
            videoEnergy = 0.0;
            audioEnergy = 0.0;
        }

        public void resetTime() {
            fgTime = 0;
            bgTime = 0;
            mLastTime = 0;
            usageTime = 0;
        }

        public void resetCount() {
            launchCount = 0;
            frameCount = 0;
            uiActivity = 0;
        }

        public void writeToParcel(Parcel dest){
            dest.writeString(packageName);

            //Writing all energy values
            dest.writeDouble(fgEnergy);
            dest.writeDouble(oldFgEnergy);
            dest.writeDouble(bgEnergy);
            dest.writeDouble(oldBgEnergy);
            dest.writeDouble(commonEnergy);
            dest.writeDouble(cpuEnergy);
            dest.writeDouble(sensorEnergy);
            dest.writeDouble(wifiEnergy);
            //dest.writeDouble(dataEnergy);
            dest.writeDouble(mobileDataEnergy);
            dest.writeDouble(wifiDataEnergy);
            dest.writeDouble(videoEnergy);
            dest.writeDouble(audioEnergy);

            //Writing all time values
            dest.writeLong(fgTime);
            dest.writeLong(bgTime);
            dest.writeLong(mLastTime);
            dest.writeLong(usageTime);

            //Writing misc data
            dest.writeInt(launchCount);
            dest.writeLong(frameCount);
            dest.writeLong(uiActivity);
        }

        public void readFromParcel(Parcel src){
            packageName = src.readString();

            //Writing all energy values
            fgEnergy = src.readDouble();
            oldFgEnergy = src.readDouble();
            bgEnergy = src.readDouble();
            oldBgEnergy = src.readDouble();
            commonEnergy = src.readDouble();
            cpuEnergy = src.readDouble();
            sensorEnergy = src.readDouble();
            wifiEnergy = src.readDouble();
            //dataEnergy = src.readDouble();
            mobileDataEnergy = src.readDouble();
            wifiDataEnergy = src.readDouble();
            videoEnergy = src.readDouble();
            audioEnergy = src.readDouble();

            //Writing all time values
            fgTime = src.readLong();
            bgTime = src.readLong();
            mLastTime = src.readLong();
            usageTime = src.readLong();

            //Writing misc data
            launchCount = src.readInt();
            frameCount = src.readLong();
            uiActivity = src.readLong();
        }
    }

    public final class SystemStats {
        double mLastDischargeRate;
        double mCurrDischargeRate;
        double mLastFgDischargeRate;
        double mCurrFgDischargeRate;
        double mLastBgDischargeRate;
        double mCurrBgDischargeRate;
        long mLastChargeDuration;
        double mLastChargeRate;
        boolean lowPowerState;
        long totalScreenOnTime;
        long totalScreenOffTime;
        long mLastScreenOn;
        long mLastScreenOff;
        long upTime;
        double phoneEnergy;
        double screenEnergy;
        double wifiEnergy;
        double idleEnergy;
        double radioEnergy;
        double bluetoothEnergy;

        public long getUptime(){
            return upTime;
        }


        public double getCurrentDischargeRate(){
            return mCurrDischargeRate;
        }

        public double getCurrentFgDischargeRate(){
            return mCurrFgDischargeRate;
        }

        public double getCurrentBgDischargeRate(){
            return mCurrBgDischargeRate;
        }

        public long getTotalScreenOnTime(){
            return totalScreenOnTime;
        }

        public double getPhoneEnergy(){
            return phoneEnergy;
        }

        public long getTotalScreenOffTime(){
            return totalScreenOffTime;
        }

        public double getScreenEnergy(){
            return screenEnergy;
        }

        public double getWifiEnergy(){
            return wifiEnergy;
        }

        public double getRadioEnergy(){
            return radioEnergy;
        }

        public double getIdleEnergy(){
            return idleEnergy;
        }

        public double getBluetoothEnergy(){
            return bluetoothEnergy;
        }

        public void updateScreenTime(long time, int opt) {
            if (opt == 0) {
                mLastScreenOff = time;
                if (mLastScreenOn != 0) totalScreenOnTime += mLastScreenOff - mLastScreenOn;
            }
            else if (opt == 1) {
                mLastScreenOn = time;
                if (mLastScreenOff != 0) totalScreenOffTime += mLastScreenOn - mLastScreenOff;
            }

        }

        public void updateUpTime(long time){
            upTime = time;
        }

        public void updateTotalDischargeRate(int discharge){
            if(upTime == 0)
                return ;
            mLastDischargeRate = mCurrDischargeRate;
            mCurrDischargeRate = (double)discharge/(double)upTime;
        }

        public void updateForegroundDischargeRate(int discharge){
            if (totalScreenOnTime == 0)
                return;
            mLastFgDischargeRate = mCurrFgDischargeRate;
            mCurrFgDischargeRate = (double)discharge/(double)totalScreenOnTime;
        }

        public void updateBackgroundDischargeRate(int discharge){
            if (totalScreenOffTime == 0)
                return;
            mLastBgDischargeRate = mCurrBgDischargeRate;
            mCurrBgDischargeRate = (double)discharge/(double)totalScreenOffTime;
        }

        public double updateEnergy(double screen, double phone, double radio, double wifi, double idle, double bluetooth){
            screenEnergy = screen;
            phoneEnergy = phone;
            radioEnergy = radio;
            wifiEnergy = wifi;
            idleEnergy = idle;
            bluetoothEnergy = bluetooth;
            return screenEnergy + phoneEnergy + radioEnergy + wifiEnergy + idleEnergy + bluetoothEnergy;
        }


        public void resetOnCharge() {
            mLastScreenOff = 0;
            mLastScreenOn = 0;
            totalScreenOffTime = 0;
            totalScreenOnTime = 0;
            upTime = 0;
            mLastDischargeRate = mCurrDischargeRate;
            mCurrDischargeRate = 0.0;
            mLastFgDischargeRate = mCurrFgDischargeRate;
            mCurrFgDischargeRate = 0.0;
            mLastBgDischargeRate = mCurrBgDischargeRate;
            mCurrBgDischargeRate = 0.0;
            screenEnergy = phoneEnergy = radioEnergy = wifiEnergy = idleEnergy = bluetoothEnergy = 0.0;
        }

        public void clear(){
            mLastDischargeRate = 0.0;
            mCurrDischargeRate = 0.0;
            mLastFgDischargeRate = 0.0;
            mCurrFgDischargeRate = 0.0;
            mLastBgDischargeRate= 0.0;
            mCurrBgDischargeRate = 0.0;
            mLastChargeDuration = 0;
            mLastChargeRate = 0.0;
            lowPowerState = false;
            totalScreenOnTime = 0;
            totalScreenOffTime = 0;
            mLastScreenOn = 0;
            mLastScreenOff = 0;
            upTime = 0;
            screenEnergy = phoneEnergy = radioEnergy = wifiEnergy = idleEnergy = bluetoothEnergy = 0.0;
        }

        public void writeToParcel(Parcel dest){
            dest.writeDouble(mLastDischargeRate);
            dest.writeDouble(mCurrDischargeRate);
            dest.writeDouble(mLastFgDischargeRate);
            dest.writeDouble(mCurrFgDischargeRate);
            dest.writeDouble(mLastBgDischargeRate);
            dest.writeDouble(mCurrBgDischargeRate);
            dest.writeLong(totalScreenOnTime);
            dest.writeLong(totalScreenOffTime);
            dest.writeLong(upTime);
            dest.writeLong(mLastScreenOn);
            dest.writeLong(mLastScreenOff);
            dest.writeDouble(screenEnergy);
            dest.writeDouble(phoneEnergy);
            dest.writeDouble(radioEnergy);
            dest.writeDouble(wifiEnergy);
            dest.writeDouble(idleEnergy);
            dest.writeDouble(bluetoothEnergy);
        }

        public void readFromParcel(Parcel src) {
            mLastDischargeRate = src.readDouble();
            mCurrDischargeRate = src.readDouble();
            mLastFgDischargeRate = src.readDouble();
            mCurrFgDischargeRate = src.readDouble();
            mLastBgDischargeRate = src.readDouble();
            mCurrBgDischargeRate = src.readDouble();
            totalScreenOnTime = src.readLong();
            totalScreenOffTime = src.readLong();
            upTime = src.readLong();
            mLastScreenOn = src.readLong();
            mLastScreenOff = src.readLong();
            screenEnergy = src.readDouble();
            phoneEnergy = src.readDouble();
            radioEnergy = src.readDouble();
            wifiEnergy = src.readDouble();
            idleEnergy = src.readDouble();
            bluetoothEnergy = src.readDouble();
        }
    }



    //taken from fuelgauge/Utils.formatElapsedTime
    public String formatElapsedTime(double millis) {
        String sb;
        int seconds = (int) Math.floor(millis / 1000);

        int days = 0, hours = 0, minutes = 0;
        if (seconds > SECONDS_PER_DAY) {
            days = seconds / SECONDS_PER_DAY;
            seconds -= days * SECONDS_PER_DAY;
        }
        if (seconds > SECONDS_PER_HOUR) {
            hours = seconds / SECONDS_PER_HOUR;
            seconds -= hours * SECONDS_PER_HOUR;
        }
        if (seconds > SECONDS_PER_MINUTE) {
            minutes = seconds / SECONDS_PER_MINUTE;
            seconds -= minutes * SECONDS_PER_MINUTE;
        }
        if (days > 0) {
            sb = days + "d "+ hours +"h "+ minutes +"m "+ seconds + "s";
        } else if (hours > 0) {
            sb = hours +"h "+ minutes +"m "+ seconds + "s";
        } else if (minutes > 0) {
            sb = minutes +"m "+ seconds + "s";
        } else {
            sb = seconds + "s";
        }

        return sb;
    }

    public JoulerStats(Parcel src) {
        mUidArray = new SparseArray<JoulerStats.UidStats>();
        mSystemStats = new JoulerStats.SystemStats();
        readFromParcel(src);
    }

    public JoulerStats() {
        mUidArray = new SparseArray<JoulerStats.UidStats>();
        mSystemStats = new JoulerStats.SystemStats();
    }

    @Override
    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        int size = mUidArray.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            dest.writeInt(mUidArray.keyAt(i));
            UidStats uid = mUidArray.valueAt(i);

            uid.writeToParcel(dest);
        }

        SystemStats sys = mSystemStats;
        sys.writeToParcel(dest);
    }

    public void readFromParcel(Parcel src) {
        int num = src.readInt();
        mUidArray.clear();
        for (int i = 0; i < num; i++) {
            int uid = src.readInt();
            UidStats u = new UidStats(uid);
            u.readFromParcel(src);
            mUidArray.put(uid, u);
        }

        mSystemStats.clear();
        mSystemStats.readFromParcel(src);

    }

    public static final Parcelable.Creator<JoulerStats> CREATOR =
        new Parcelable.Creator<JoulerStats>() {
            public JoulerStats createFromParcel(Parcel src) {
                return new JoulerStats(src);
            }

            @Override
            public JoulerStats[] newArray(int size) {
                // TODO Auto-generated method stub
                return new JoulerStats[size];
            }

        };

}
