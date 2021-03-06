/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.devicemotion;

import java.util.List;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.widget.Toast;

import android.os.Handler;
import android.os.Looper;

/**
 * This class listens to the accelerometer sensor and stores the latest
 * acceleration values x,y,z.
 */
public class OrientationListener extends CordovaPlugin implements SensorEventListener {

    public static int STOPPED = 0;
    public static int STARTING = 1;
    public static int RUNNING = 2;
    public static int ERROR_FAILED_TO_START = 3;

    private float x, y, z; // most recent acceleration values
    private float orientation;
    private long timestamp; // time of most recent value
    private int status; // status of listener
    private int accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;

    private SensorManager sensorManager; // Sensor manager
    private Sensor mSensor; // Acceleration sensor returned by sensor manager

    private float[] mGData = new float[3];
    private float[] mMData = new float[3];
    private float[] mR = new float[16];
    private float[] mOrientation = new float[3];
    private static final float RAD2DEG = (float) (180.0f / Math.PI);
    private boolean mHasMagneticSensor = false;

    private CallbackContext callbackContext; // Keeps track of the JS callback context.

    private Handler mainHandler = null;
    private Runnable mainRunnable = new Runnable() {
        public void run() {
            OrientationListener.this.timeout();
        }
    };

    /**
     * Create an accelerometer listener.
     */
    public OrientationListener() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.orientation = 0;
        this.timestamp = 0;
        this.setStatus(OrientationListener.STOPPED);
    }

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The associated CordovaWebView.
     */
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.sensorManager = (SensorManager) cordova.getActivity().getSystemService(Context.SENSOR_SERVICE);
    }

    /**
     * Executes the request.
     *
     * @param action        The action to execute.
     * @param args          The exec() arguments.
     * @param callbackId    The callback id used when calling back into JavaScript.
     * @return              Whether the action was valid.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if (action.equals("start")) {
            this.callbackContext = callbackContext;
            if (this.status != OrientationListener.RUNNING) {
                // If not running, then this is an async call, so don't worry about waiting
                // We drop the callback onto our stack, call start, and let start and the sensor callback fire off the callback down the road
                this.start();
            }
        }
        else if (action.equals("stop")) {
            if (this.status == OrientationListener.RUNNING) {
                this.stop();
            }
        } else {
            // Unsupported action
            return false;
        }

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT, "");
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
        return true;
    }

    /**
     * Called by AccelBroker when listener is to be shut down.
     * Stop listener.
     */
    public void onDestroy() {
        this.stop();
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------
    //
    /**
     * Start listening for acceleration sensor.
     *
     * @return          status of listener
    */
    private int start() {
        // If already starting or running, then restart timeout and return
        if ((this.status == OrientationListener.RUNNING) || (this.status == OrientationListener.STARTING)) {
            startTimeout();
            return this.status;
        }

        this.setStatus(OrientationListener.STARTING);

        // 1. try to go with the rotation vector introduced in 2.3.
        // Uses sensor fusion to combine everything available
        // (accelerometer, magnetic field, gyroscope,...)
        // It's OK to call this on older devices -> only using a new int
        boolean hasRotationSensor = this.sensorManager.registerListener(this,
                this.sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                SensorManager.SENSOR_DELAY_UI);
        if (!hasRotationSensor) {

            // 2. fall back on accelerometer sensor

            boolean hasAccelerometer = this.sensorManager.registerListener(
                    this, this.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_UI);
            if (!hasAccelerometer) {
                this.setStatus(OrientationListener.ERROR_FAILED_TO_START);
                this.fail(OrientationListener.ERROR_FAILED_TO_START, "No sensors found to register accelerometer listening to.");

                Toast.makeText(this.cordova.getActivity().getApplicationContext(),
                        "Device is missing necessary sensors!", Toast.LENGTH_LONG)
                        .show();
                return this.status;
            }

            mHasMagneticSensor = this.sensorManager.registerListener(this,
                    this.sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                    SensorManager.SENSOR_DELAY_UI);
            if (!mHasMagneticSensor) {
                // accelerometer is not really accurate
                Toast.makeText(this.cordova.getActivity().getApplicationContext(),
                        "Inclination may be inaccurate due to missing sensors!", Toast.LENGTH_LONG)
                        .show();
            }
        }

        // // Get rotation vector from sensor manager
        // List<Sensor> list = this.sensorManager.getSensorList(Sensor.TYPE_ROTATION_VECTOR);

        // // If found, then register as listener
        // if ((list != null) && (list.size() > 0)) {
        //     this.mSensor = list.get(0);
        //     if (this.sensorManager.registerListener(this, this.mSensor, SensorManager.SENSOR_DELAY_UI)) {
        //         this.setStatus(OrientationListener.STARTING);
        //         // CB-11531: Mark accuracy as 'reliable' - this is complementary to
        //         // setting it to 'unreliable' 'stop' method
        //         this.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
        //     } else {
        //         this.setStatus(OrientationListener.ERROR_FAILED_TO_START);
        //         this.fail(OrientationListener.ERROR_FAILED_TO_START, "Device sensor returned an error.");
        //         return this.status;
        //   };

        // } else {
        //     this.setStatus(OrientationListener.ERROR_FAILED_TO_START);
        //     this.fail(OrientationListener.ERROR_FAILED_TO_START, "No sensors found to register accelerometer listening to.");
        //     return this.status;
        // }

        startTimeout();

        return this.status;
    }
    private void startTimeout() {
        // Set a timeout callback on the main thread.
        stopTimeout();
        mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(mainRunnable, 2000);
    }
    private void stopTimeout() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(mainRunnable);
        }
    }
    /**
     * Stop listening to acceleration sensor.
     */
    private void stop() {
        stopTimeout();
        if (this.status != OrientationListener.STOPPED) {
            this.sensorManager.unregisterListener(this);
        }
        this.setStatus(OrientationListener.STOPPED);
        this.accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    }

    /**
     * Returns latest cached position if the sensor hasn't returned newer value.
     *
     * Called two seconds after starting the listener.
     */
    private void timeout() {
        if (this.status == OrientationListener.STARTING &&
            this.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            // call win with latest cached position
            // but first check if cached position is reliable
            this.timestamp = System.currentTimeMillis();
            this.win();
        }
    }

    /**
     * Called when the accuracy of the sensor has changed.
     *
     * @param sensor
     * @param accuracy
     */
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Only look at accelerometer events
        if (sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        // If not running, then just return
        if (this.status == OrientationListener.STOPPED) {
            return;
        }
        this.accuracy = accuracy;
    }

    /**
     * Sensor listener event.
     *
     * @param SensorEvent event
     */
    public void onSensorChanged(SensorEvent event) {
        float orientation = 0;

        switch (event.sensor.getType()) {

        case Sensor.TYPE_ROTATION_VECTOR:
            SensorManager.getRotationMatrixFromVector(mR, event.values);
            orientation = getRotationOrientation();
            break;

        case Sensor.TYPE_ACCELEROMETER:
            System.arraycopy(event.values, 0, mGData, 0, 3);

            if (mHasMagneticSensor) {
                SensorManager.getRotationMatrix(mR, null, mGData, mMData);
                orientation = getRotationOrientation();
            } else {
                // Use fallback method for phones without rotation vector or
                // magnetic sensor - this is very poor quality!
                // TODO: this should probably be 90/9,81 -> 9,1743119266055
                orientation = -event.values[1] * 9; // "9" was found using trial + error
            }
            break;

        case Sensor.TYPE_MAGNETIC_FIELD:
            System.arraycopy(event.values, 0, mMData, 0, 3);
            SensorManager.getRotationMatrix(mR, null, mGData, mMData);
            orientation = getRotationOrientation();
            break;

        default:
            // we should not be here.
            return;
        }

        // If not running, then just return
        if (this.status == OrientationListener.STOPPED) {
            return;
        }
        this.setStatus(OrientationListener.RUNNING);

        if (this.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            // Log.d("OrientationListener", "updating values " + orientation);

            // Save time that event was received
            this.timestamp = System.currentTimeMillis();
            this.x = event.values[0];
            this.y = event.values[1];
            this.z = event.values[2];
            this.orientation = orientation;

            this.win();
        }
    }

    /**
     * Called when the view navigates.
     */
    @Override
    public void onReset() {
        if (this.status == OrientationListener.RUNNING) {
            this.stop();
        }
    }

    /**
     * Return orientation using current orientation matrix.
     *
     * @return orientation in degree
     */
    private float getRotationOrientation() {
        SensorManager.getOrientation(mR, mOrientation);
        return mOrientation[1] * RAD2DEG;
    }

    // Sends an error back to JS
    private void fail(int code, String message) {
        // Error object
        JSONObject errorObj = new JSONObject();
        try {
            errorObj.put("code", code);
            errorObj.put("message", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PluginResult err = new PluginResult(PluginResult.Status.ERROR, errorObj);
        err.setKeepCallback(true);
        callbackContext.sendPluginResult(err);
    }

    private void win() {
        // Success return object
        PluginResult result = new PluginResult(PluginResult.Status.OK, this.getAccelerationJSON());
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    private void setStatus(int status) {
        this.status = status;
    }
    private JSONObject getAccelerationJSON() {
        JSONObject r = new JSONObject();
        try {
            r.put("x", this.x);
            r.put("y", this.y);
            r.put("z", this.z);
            r.put("orientation", this.orientation);
            r.put("timestamp", this.timestamp);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return r;
    }
}
