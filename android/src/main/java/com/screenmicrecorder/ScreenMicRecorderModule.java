package com.screenmicrecorder;

import androidx.annotation.NonNull;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.hbisoft.hbrecorder.HBRecorder;
import com.hbisoft.hbrecorder.HBRecorderListener;

import java.io.File;

@ReactModule(name = ScreenMicRecorderModule.NAME)
public class ScreenMicRecorderModule extends ReactContextBaseJavaModule implements HBRecorderListener {

    public static final String NAME = "ScreenMicRecorder";

    private final ReactApplicationContext reactContext;
    private final int SCREEN_RECORD_REQUEST_CODE = 1000;

    private Promise startPromise;
    private Promise stopPromise;

    private HBRecorder hbRecorder;

    public ScreenMicRecorderModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        reactContext.addActivityEventListener(new BaseActivityEventListener() {
            @Override
            public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
                if (requestCode != SCREEN_RECORD_REQUEST_CODE) return;

                if (resultCode == Activity.RESULT_CANCELED) {
                    Log.d("ScreenMicRecorder", "User denied permission");
                    if (startPromise != null) {
                        startPromise.resolve("userDeniedPermission");
                        startPromise = null;
                    }
                    return;
                }

                if (resultCode == Activity.RESULT_OK) {
                    Log.d("ScreenMicRecorder", "User accepted permission");
                    if (hbRecorder != null) {
                        hbRecorder.startScreenRecording(intent, resultCode);
                    }
                }
            }
        });
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void addListener(String ignoredEventName) {}

    @ReactMethod
    public void removeListeners(Integer ignoredCount) {}

    @ReactMethod
    public void startRecording(ReadableMap config, Promise promise) {
        startPromise = promise;

        hbRecorder = new HBRecorder(reactContext, this);

        // Микрофон
        boolean micEnabled = config.hasKey("mic") && config.getBoolean("mic");
        hbRecorder.isAudioEnabled(micEnabled);


        // Уникальное имя файла
        File cacheDir = reactContext.getCacheDir();
        if (!cacheDir.exists()) cacheDir.mkdirs();
        String fileName = "recording_" + System.currentTimeMillis() + ".mp4";

        // Устанавливаем путь и имя
        hbRecorder.setOutputPath(cacheDir.getAbsolutePath());
        hbRecorder.setFileName(fileName);

        // Уведомление
        boolean notificationActionEnabled = config.hasKey("notificationActionEnabled") && config.getBoolean("notificationActionEnabled");
        if (!notificationActionEnabled) {
            hbRecorder.setNotificationDescription("Stop recording from the application");
        }

        try {
            MediaProjectionManager mediaProjectionManager =
                    (MediaProjectionManager) reactContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            getCurrentActivity().startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    SCREEN_RECORD_REQUEST_CODE
            );
        } catch (Exception e) {
            if (startPromise != null) {
                startPromise.reject("START_FAILED", e.getMessage());
                startPromise = null;
            }
        }
    }

    @ReactMethod
    public void stopRecording(Promise promise) {
        Log.d("ScreenMicRecorder", "stopRecording");
        stopPromise = promise;
        try {
            if (hbRecorder != null) {
                hbRecorder.stopScreenRecording();
            } else {
                if (stopPromise != null) {
                    stopPromise.reject("NO_RECORDER", "Recorder not initialized");
                    stopPromise = null;
                }
            }
        } catch (Exception e) {
            Log.e("ScreenMicRecorder", "Stop failed", e);
            if (stopPromise != null) {
                stopPromise.reject("STOP_FAILED", e.getMessage());
                stopPromise = null;
            }
        }
    }

    @ReactMethod
    public void deleteRecording(String filename, Promise promise) {
        File fdelete = new File(filename);
        if (!fdelete.exists()) {
            promise.resolve(false);
            return;
        }
        if (fdelete.delete()) {
            Log.d("ScreenMicRecorder", "deleteRecording " + filename);
            promise.resolve(true);
        } else {
            Log.d("ScreenMicRecorder", "unable to delete " + filename);
            promise.reject("DELETE_FAILED", "Unable to delete file");
        }
    }

    // События HBRecorder
    @Override
    public void HBRecorderOnStart() {
        Log.d("ScreenMicRecorder", "HBRecorder Started");
        if (startPromise != null) {
            startPromise.resolve("started");
            startPromise = null;
        }
    }

    @Override
    public void HBRecorderOnComplete() {
        String uri = hbRecorder.getFilePath();
        Log.d("ScreenMicRecorder", "HBRecorder Completed. URI: " + uri);

        if (stopPromise != null) {
            stopPromise.resolve(uri);
            stopPromise = null;
        }

        WritableMap params = Arguments.createMap();
        params.putString("value", uri);
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("stopEvent", params);
    }

    @Override
    public void HBRecorderOnError(int errorCode, String reason) {
        Log.d("ScreenMicRecorder", "HBRecorderOnError : " + errorCode + " " + reason);
        if (startPromise != null) {
            startPromise.reject("RECORDER_ERROR", "RecorderOnError:" + errorCode + " " + reason);
            startPromise = null;
        }
        if (stopPromise != null) {
            stopPromise.reject("RECORDER_ERROR", "RecorderOnError:" + errorCode + " " + reason);
            stopPromise = null;
        }
    }

    @Override
    public void HBRecorderOnPause() {}

    @Override
    public void HBRecorderOnResume() {}
}
