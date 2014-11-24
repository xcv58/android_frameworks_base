package edu.buffalo.cse.phonelab.json;

import edu.buffalo.cse.phonelab.json.StrictJSONObject;

/**
 * Any class that implements this interface has a toJSONObject function.
 *
 * {@hide}
 */
public interface JSONable{
    public abstract StrictJSONObject toJSONObject();
}
