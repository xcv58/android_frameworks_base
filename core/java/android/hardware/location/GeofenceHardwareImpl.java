/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.location;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.IGpsGeofenceHardware;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * This class manages the geofences which are handled by hardware.
 *
 * @hide
 */
public final class GeofenceHardwareImpl {
    private static final String TAG = "GeofenceHardwareImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private static GeofenceHardwareImpl sInstance;
    private PowerManager.WakeLock mWakeLock;
    private SparseArray<IGeofenceHardwareCallback> mGeofences =
            new SparseArray<IGeofenceHardwareCallback>();
    private ArrayList<IGeofenceHardwareCallback>[] mCallbacks =
            new ArrayList[GeofenceHardware.NUM_MONITORS];

    private IGpsGeofenceHardware mGpsService;

    private int[] mSupportedMonitorTypes = new int[GeofenceHardware.NUM_MONITORS];

    // mGeofenceHandler message types
    private static final int GEOFENCE_TRANSITION_CALLBACK = 1;
    private static final int ADD_GEOFENCE_CALLBACK = 2;
    private static final int REMOVE_GEOFENCE_CALLBACK = 3;
    private static final int PAUSE_GEOFENCE_CALLBACK = 4;
    private static final int RESUME_GEOFENCE_CALLBACK = 5;
    private static final int ADD_GEOFENCE = 6;
    private static final int REMOVE_GEOFENCE = 7;

    // mCallbacksHandler message types
    private static final int GPS_GEOFENCE_STATUS = 1;
    private static final int CALLBACK_ADD = 2;
    private static final int CALLBACK_REMOVE = 3;

    // The following constants need to match GpsLocationFlags enum in gps.h
    private static final int LOCATION_INVALID = 0;
    private static final int LOCATION_HAS_LAT_LONG = 1;
    private static final int LOCATION_HAS_ALTITUDE = 2;
    private static final int LOCATION_HAS_SPEED = 4;
    private static final int LOCATION_HAS_BEARING = 8;
    private static final int LOCATION_HAS_ACCURACY = 16;

    // Resolution level constants used for permission checks.
    // These constants must be in increasing order of finer resolution.
    private static final int RESOLUTION_LEVEL_NONE = 1;
    private static final int RESOLUTION_LEVEL_COARSE = 2;
    private static final int RESOLUTION_LEVEL_FINE = 3;

    // GPS Geofence errors. Should match gps.h constants.
    private static final int GPS_GEOFENCE_OPERATION_SUCCESS = 0;
    private static final int GPS_GEOFENCE_ERROR_TOO_MANY_GEOFENCES = 100;
    private static final int GPS_GEOFENCE_ERROR_ID_EXISTS  = -101;
    private static final int GPS_GEOFENCE_ERROR_ID_UNKNOWN = -102;
    private static final int GPS_GEOFENCE_ERROR_INVALID_TRANSITION = -103;
    private static final int GPS_GEOFENCE_ERROR_GENERIC = -149;



    public synchronized static GeofenceHardwareImpl getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GeofenceHardwareImpl(context);
        }
        return sInstance;
    }

    private GeofenceHardwareImpl(Context context) {
        mContext = context;
        // Init everything to unsupported.
        setMonitorAvailability(GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                GeofenceHardware.MONITOR_UNSUPPORTED);

    }

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager powerManager =
                    (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }
        mWakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (mWakeLock.isHeld()) mWakeLock.release();
    }

    private void updateGpsHardwareAvailability() {
        //Check which monitors are available.
        boolean gpsSupported;
        try {
            gpsSupported = mGpsService.isHardwareGeofenceSupported();
        } catch (RemoteException e) {
            Log.e(TAG, "Remote Exception calling LocationManagerService");
            gpsSupported = false;
        }

        if (gpsSupported) {
            // Its assumed currently available at startup.
            // native layer will update later.
            setMonitorAvailability(GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                    GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE);
        }
    }

    public void setGpsHardwareGeofence(IGpsGeofenceHardware service) {
        if (mGpsService == null) {
            mGpsService = service;
            updateGpsHardwareAvailability();
        } else if (service == null) {
            mGpsService = null;
            Log.w(TAG, "GPS Geofence Hardware service seems to have crashed");
        } else {
            Log.e(TAG, "Error: GpsService being set again.");
        }
    }

    public int[] getMonitoringTypesAndStatus() {
        synchronized (mSupportedMonitorTypes) {
            return mSupportedMonitorTypes;
        }
    }

    public boolean addCircularFence(int geofenceId, double latitude, double longitude,
            double radius, int lastTransition,int monitorTransitions, int notificationResponsivenes,
            int unknownTimer, int monitoringType, IGeofenceHardwareCallback callback) {
        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) {
            Log.d(TAG, "addCircularFence: GeofenceId: " + geofenceId + "Latitude: " + latitude +
                    "Longitude: " + longitude + "Radius: " + radius + "LastTransition: "
                    + lastTransition + "MonitorTransition: " + monitorTransitions +
                    "NotificationResponsiveness: " + notificationResponsivenes +
                    "UnKnown Timer: " + unknownTimer + "MonitoringType: " + monitoringType);

        }
        boolean result;
        Message m = mGeofenceHandler.obtainMessage(ADD_GEOFENCE, callback);
        m.arg1 = geofenceId;
        mGeofenceHandler.sendMessage(m);

        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.addCircularHardwareGeofence(geofenceId, latitude,
                            longitude, radius, lastTransition, monitorTransitions,
                            notificationResponsivenes, unknownTimer);
                } catch (RemoteException e) {
                    Log.e(TAG, "AddGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (!result) {
            m = mGeofenceHandler.obtainMessage(REMOVE_GEOFENCE);
            m.arg1 = geofenceId;
            mGeofenceHandler.sendMessage(m);
        }

        if (DEBUG) Log.d(TAG, "addCircularFence: Result is: " + result);
        return result;
    }

    public boolean removeGeofence(int geofenceId, int monitoringType) {
        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) Log.d(TAG, "Remove Geofence: GeofenceId: " + geofenceId);
        boolean result = false;
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.removeHardwareGeofence(geofenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoveGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (DEBUG) Log.d(TAG, "removeGeofence: Result is: " + result);
        return result;
    }

    public boolean pauseGeofence(int geofenceId, int monitoringType) {
        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) Log.d(TAG, "Pause Geofence: GeofenceId: " + geofenceId);
        boolean result;
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.pauseHardwareGeofence(geofenceId);
                } catch (RemoteException e) {
                    Log.e(TAG, "PauseGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (DEBUG) Log.d(TAG, "pauseGeofence: Result is: " + result);
        return result;
    }


    public boolean resumeGeofence(int geofenceId, int monitorTransition, int monitoringType) {
        // This API is not thread safe. Operations on the same geofence need to be serialized
        // by upper layers
        if (DEBUG) Log.d(TAG, "Resume Geofence: GeofenceId: " + geofenceId);
        boolean result;
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                if (mGpsService == null) return false;
                try {
                    result = mGpsService.resumeHardwareGeofence(geofenceId, monitorTransition);
                } catch (RemoteException e) {
                    Log.e(TAG, "ResumeGeofence: Remote Exception calling LocationManagerService");
                    result = false;
                }
                break;
            default:
                result = false;
        }
        if (DEBUG) Log.d(TAG, "resumeGeofence: Result is: " + result);
        return result;
    }

    public boolean registerForMonitorStateChangeCallback(int monitoringType,
            IGeofenceHardwareCallback callback) {
        Message m = mCallbacksHandler.obtainMessage(CALLBACK_ADD, callback);
        m.arg1 = monitoringType;
        mCallbacksHandler.sendMessage(m);
        return true;
    }

    public boolean unregisterForMonitorStateChangeCallback(int monitoringType,
            IGeofenceHardwareCallback callback) {
        Message m = mCallbacksHandler.obtainMessage(CALLBACK_REMOVE, callback);
        m.arg1 = monitoringType;
        mCallbacksHandler.sendMessage(m);
        return true;
    }

    private Location getLocation(int flags, double latitude,
            double longitude, double altitude, float speed, float bearing, float accuracy,
            long timestamp) {
        if (DEBUG) Log.d(TAG, "GetLocation: " + flags + ":" + latitude);
        Location location = new Location(LocationManager.GPS_PROVIDER);
        if ((flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            location.setTime(timestamp);
            // It would be nice to push the elapsed real-time timestamp
            // further down the stack, but this is still useful
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
        if ((flags & LOCATION_HAS_ALTITUDE) == LOCATION_HAS_ALTITUDE) {
            location.setAltitude(altitude);
        } else {
            location.removeAltitude();
        }
        if ((flags & LOCATION_HAS_SPEED) == LOCATION_HAS_SPEED) {
            location.setSpeed(speed);
        } else {
            location.removeSpeed();
        }
        if ((flags & LOCATION_HAS_BEARING) == LOCATION_HAS_BEARING) {
            location.setBearing(bearing);
        } else {
            location.removeBearing();
        }
        if ((flags & LOCATION_HAS_ACCURACY) == LOCATION_HAS_ACCURACY) {
            location.setAccuracy(accuracy);
        } else {
            location.removeAccuracy();
        }
        return location;
    }

    /**
     * called from GpsLocationProvider to report geofence transition
     */
    public void reportGpsGeofenceTransition(int geofenceId, int flags, double latitude,
            double longitude, double altitude, float speed, float bearing, float accuracy,
            long timestamp, int transition, long transitionTimestamp) {
        if (DEBUG) Log.d(TAG, "GeofenceTransition: Flags: " + flags + " Lat: " + latitude +
            " Long: " + longitude + " Altitude: " + altitude + " Speed: " + speed + " Bearing: " +
            bearing + " Accuracy: " + accuracy + " Timestamp: " + timestamp + " Transition: " +
            transition + " TransitionTimestamp: " + transitionTimestamp);
        Location location = getLocation(flags, latitude, longitude, altitude, speed, bearing,
                accuracy, timestamp);
        GeofenceTransition t = new GeofenceTransition(geofenceId, transition, timestamp, location);
        acquireWakeLock();
        Message m = mGeofenceHandler.obtainMessage(GEOFENCE_TRANSITION_CALLBACK, t);
        mGeofenceHandler.sendMessage(m);
    }

    /**
     * called from GpsLocationProvider to report GPS status change.
     */
    public void reportGpsGeofenceStatus(int status, int flags, double latitude,
            double longitude, double altitude, float speed, float bearing, float accuracy,
            long timestamp) {
        Location location = getLocation(flags, latitude, longitude, altitude, speed, bearing,
                accuracy, timestamp);
        boolean available = false;
        if (status == GeofenceHardware.GPS_GEOFENCE_AVAILABLE) available = true;

        int val = (available ? GeofenceHardware.MONITOR_CURRENTLY_UNAVAILABLE :
                GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE);
        setMonitorAvailability(GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE, val);

        acquireWakeLock();
        Message m = mCallbacksHandler.obtainMessage(GPS_GEOFENCE_STATUS, location);
        m.arg1 = val;
        mCallbacksHandler.sendMessage(m);
    }

    /**
     * called from GpsLocationProvider add geofence callback.
     */
    public void reportGpsGeofenceAddStatus(int geofenceId, int status) {
        if (DEBUG) Log.d(TAG, "Add Callback: GPS : Id: " + geofenceId + " Status: " + status);
        acquireWakeLock();
        Message m = mGeofenceHandler.obtainMessage(ADD_GEOFENCE_CALLBACK);
        m.arg1 = geofenceId;
        m.arg2 = getGeofenceStatus(status);
        mGeofenceHandler.sendMessage(m);
    }

    /**
     * called from GpsLocationProvider remove geofence callback.
     */
    public void reportGpsGeofenceRemoveStatus(int geofenceId, int status) {
        if (DEBUG) Log.d(TAG, "Remove Callback: GPS : Id: " + geofenceId + " Status: " + status);
        acquireWakeLock();
        Message m = mGeofenceHandler.obtainMessage(REMOVE_GEOFENCE_CALLBACK);
        m.arg1 = geofenceId;
        m.arg2 = getGeofenceStatus(status);
        mGeofenceHandler.sendMessage(m);
    }

    /**
     * called from GpsLocationProvider pause geofence callback.
     */
    public void reportGpsGeofencePauseStatus(int geofenceId, int status) {
        if (DEBUG) Log.d(TAG, "Pause Callback: GPS : Id: " + geofenceId + " Status: " + status);
        acquireWakeLock();
        Message m = mGeofenceHandler.obtainMessage(PAUSE_GEOFENCE_CALLBACK);
        m.arg1 = geofenceId;
        m.arg2 = getGeofenceStatus(status);
        mGeofenceHandler.sendMessage(m);
    }

    /**
     * called from GpsLocationProvider resume geofence callback.
     */
    public void reportGpsGeofenceResumeStatus(int geofenceId, int status) {
        if (DEBUG) Log.d(TAG, "Resume Callback: GPS : Id: " + geofenceId + " Status: " + status);
        acquireWakeLock();
        Message m = mGeofenceHandler.obtainMessage(RESUME_GEOFENCE_CALLBACK);
        m.arg1 = geofenceId;
        m.arg2 = getGeofenceStatus(status);
        mGeofenceHandler.sendMessage(m);
    }

    // All operations on mGeofences
    private Handler mGeofenceHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            int geofenceId;
            int status;
            IGeofenceHardwareCallback callback;
            switch (msg.what) {
                case ADD_GEOFENCE:
                    geofenceId = msg.arg1;
                    callback = (IGeofenceHardwareCallback) msg.obj;
                    mGeofences.put(geofenceId, callback);
                    break;
                case REMOVE_GEOFENCE:
                    geofenceId = msg.arg1;
                    mGeofences.remove(geofenceId);
                    break;
                case ADD_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    callback = mGeofences.get(geofenceId);
                    if (callback == null) return;

                    try {
                        callback.onGeofenceAdd(geofenceId, msg.arg2);
                    } catch (RemoteException e) {Log.i(TAG, "Remote Exception:" + e);}
                    releaseWakeLock();
                    break;
                case REMOVE_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    callback = mGeofences.get(geofenceId);
                    if (callback == null) return;

                    try {
                        callback.onGeofenceRemove(geofenceId, msg.arg2);
                    } catch (RemoteException e) {}
                    mGeofences.remove(geofenceId);
                    releaseWakeLock();
                    break;

                case PAUSE_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    callback = mGeofences.get(geofenceId);
                    if (callback == null) return;

                    try {
                        callback.onGeofencePause(geofenceId, msg.arg2);
                    } catch (RemoteException e) {}
                    releaseWakeLock();
                    break;

                case RESUME_GEOFENCE_CALLBACK:
                    geofenceId = msg.arg1;
                    callback = mGeofences.get(geofenceId);
                    if (callback == null) return;

                    try {
                        callback.onGeofenceResume(geofenceId, msg.arg2);
                    } catch (RemoteException e) {}
                    releaseWakeLock();
                    break;

                case GEOFENCE_TRANSITION_CALLBACK:
                    GeofenceTransition geofenceTransition = (GeofenceTransition)(msg.obj);
                    callback = mGeofences.get(geofenceTransition.mGeofenceId);

                    if (DEBUG) Log.d(TAG, "GeofenceTransistionCallback: GPS : GeofenceId: " +
                            geofenceTransition.mGeofenceId +
                            "Transition: " + geofenceTransition.mTransition +
                            "Location: " + geofenceTransition.mLocation + ":" + mGeofences);

                    try {
                        callback.onGeofenceChange(
                                geofenceTransition.mGeofenceId, geofenceTransition.mTransition,
                                geofenceTransition.mLocation, geofenceTransition.mTimestamp,
                                GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE);
                    } catch (RemoteException e) {}
                    releaseWakeLock();
                    break;
            }
        }
    };

    // All operations on mCallbacks
    private Handler mCallbacksHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            int monitoringType;
            ArrayList<IGeofenceHardwareCallback> callbackList;
            IGeofenceHardwareCallback callback;

            switch (msg.what) {
                case GPS_GEOFENCE_STATUS:
                    Location location = (Location) msg.obj;
                    int val = msg.arg1;
                    boolean available;
                    available = (val == GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE ?
                            true : false);
                    callbackList = mCallbacks[GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE];
                    if (callbackList == null) return;

                    if (DEBUG) Log.d(TAG, "MonitoringSystemChangeCallback: GPS : " + available);

                    for (IGeofenceHardwareCallback c: callbackList) {
                        try {
                            c.onMonitoringSystemChange(
                                    GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE, available,
                                    location);
                        } catch (RemoteException e) {}
                    }
                    releaseWakeLock();
                    break;
                case CALLBACK_ADD:
                    monitoringType = msg.arg1;
                    callback = (IGeofenceHardwareCallback) msg.obj;
                    callbackList = mCallbacks[monitoringType];
                    if (callbackList == null) {
                        callbackList = new ArrayList<IGeofenceHardwareCallback>();
                        mCallbacks[monitoringType] = callbackList;
                    }
                    if (!callbackList.contains(callback)) callbackList.add(callback);
                    break;
                case CALLBACK_REMOVE:
                    monitoringType = msg.arg1;
                    callback = (IGeofenceHardwareCallback) msg.obj;
                    callbackList = mCallbacks[monitoringType];
                    if (callbackList != null) {
                        callbackList.remove(callback);
                    }
                    break;
            }
        }
    };

    private class GeofenceTransition {
        private int mGeofenceId, mTransition;
        private long mTimestamp;
        private Location mLocation;

        GeofenceTransition(int geofenceId, int transition, long timestamp, Location location) {
            mGeofenceId = geofenceId;
            mTransition = transition;
            mTimestamp = timestamp;
            mLocation = location;
        }
    }

    private void setMonitorAvailability(int monitor, int val) {
        synchronized (mSupportedMonitorTypes) {
            mSupportedMonitorTypes[monitor] = val;
        }
    }


    int getMonitoringResolutionLevel(int monitoringType) {
        switch (monitoringType) {
            case GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE:
                return RESOLUTION_LEVEL_FINE;
        }
        return RESOLUTION_LEVEL_NONE;
    }

    int getAllowedResolutionLevel(int pid, int uid) {
        if (mContext.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION,
                pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_FINE;
        } else if (mContext.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return RESOLUTION_LEVEL_COARSE;
        } else {
            return RESOLUTION_LEVEL_NONE;
        }
    }

    private int getGeofenceStatus(int status) {
        switch (status) {
            case GPS_GEOFENCE_OPERATION_SUCCESS:
                return GeofenceHardware.GEOFENCE_SUCCESS;
            case GPS_GEOFENCE_ERROR_GENERIC:
                return GeofenceHardware.GEOFENCE_FAILURE;
            case GPS_GEOFENCE_ERROR_ID_EXISTS:
                return GeofenceHardware.GEOFENCE_ERROR_ID_EXISTS;
            case GPS_GEOFENCE_ERROR_INVALID_TRANSITION:
                return GeofenceHardware.GEOFENCE_ERROR_INVALID_TRANSITION;
            case GPS_GEOFENCE_ERROR_TOO_MANY_GEOFENCES:
                return GeofenceHardware.GEOFENCE_ERROR_TOO_MANY_GEOFENCES;
            case GPS_GEOFENCE_ERROR_ID_UNKNOWN:
                return GeofenceHardware.GEOFENCE_ERROR_ID_UNKNOWN;
        }
        return -1;
    }
}