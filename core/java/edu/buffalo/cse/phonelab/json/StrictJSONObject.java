package edu.buffalo.cse.phonelab.json;

import java.lang.Iterable;

import android.util.Log;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;


/** 
 * Strict JSON Object.
 *
 * This is an attempt to put some restrictions on creating JSON object so the
 * final string format is as "JSON" as possible. Hopefully this will make post
 * data procesing a bit easier.
 *
 * It's different with org.json.JSONobject in two ways.
 *
 * First, values can only be one of following :
 *  - primitive types/wrappers (Boolean, Integer, Long, Float, Double)
 *  - String
 *  - StrictJSONObject
 *  - StrictJSONArray
 *  - Object that implements JSONable interface
 *  - Iterable of JSONable objects.
 * 
 * Of course this won't stop you from put some "semi-JSON" strings as values. But
 * the assumption here is, if you really want to put a string value, then you
 * know what you're doing and know how to process them at backend.
 *
 * Second, {@code put} method won't throw annoying JSONException.
 *
 * Also, this object provides a {@code log} method, which will log out the JSON
 * string to logcat, with specified tag.
 *
 * 
 */
public class StrictJSONObject {
    public static final String LOG_FORMAT = "1.0";
    public static final String DEFAULT_TAG = "PhoneLabLog";
    public static final String NONE = "<none>";

    public static final String KEY_ACTION = "Action";
    public static final String UNKNOWN = "<unknown>";

    private String tag = DEFAULT_TAG;
    private JSONObject json;

    public StrictJSONObject() {
        json = new JSONObject();
    }

    public StrictJSONObject(String tag) {
        this.tag = tag;
        json = new JSONObject();
    }

    public StrictJSONObject put(String name, Boolean value) {
        try {
            json.put(name, value == null? NONE: value);
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }

    public StrictJSONObject put(String name, Integer value) {
        try {
            json.put(name, value == null? NONE: value);
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }

    public StrictJSONObject put(String name, Long value) {
        try {
            json.put(name, value == null? NONE: value);
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }

    public StrictJSONObject put(String name, Float value) {
        try {
            json.put(name, value == null? NONE: value);
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }


    public StrictJSONObject put(String name, Double value) {
        try {
            json.put(name, value == null? NONE: value);
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }

    public StrictJSONObject put(String name, String value) {
        try {
            json.put(name, value == null? NONE: value);
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }

    public StrictJSONObject put(String name, StrictJSONObject value) {
        try {
            json.put(name, value == null? NONE: value.toJSONObject());
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }

    public StrictJSONObject put(String name, StrictJSONArray value) {
        try {
            json.put(name, value == null? NONE: value.toJSONArray());
        }
        catch (JSONException e) {
            // ignore
        }
        return this;
    }

    public <T extends JSONable> StrictJSONObject put(String name, T value) {
        if (value != null) {
            this.put(name, value.toJSONObject());
        }
        return this;
    }

    public <T extends JSONable> StrictJSONObject put(String name, Iterable<T> values) {
        if (values != null) {
            StrictJSONArray array = new StrictJSONArray();
            for (T t : values) {
                array.put(t);
            }
            this.put(name, array);
        }
        else {
            this.put(name, NONE);
        }
        return this;
    }

    public StrictJSONObject put(String name, String[] values) {
        if (values != null) {
            StrictJSONArray array = new StrictJSONArray();
            for (String s : values) {
                array.put(s);
            }
            this.put(name, array);
        }
        else {
            this.put(name, NONE);
        }
        return this;
    }

    public JSONObject toJSONObject() {
        try {
            return new JSONObject(json.toString());
        }
        catch (JSONException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return json.toString();
    }

    public void log() {
        this.put("LogFormat", LOG_FORMAT);
        if (!json.has(KEY_ACTION)) {
            try {
                json.put(KEY_ACTION, UNKNOWN);
            }
            catch (JSONException e) {
                // ignore it
            }
        }
        Log.i(tag, json.toString());
    }
}
