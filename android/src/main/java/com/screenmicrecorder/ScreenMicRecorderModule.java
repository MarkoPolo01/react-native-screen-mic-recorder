package com.screenmicrecorder;

import androidx.annotation.NonNull;
import android.util.Log;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.content.Context;
import android.app.Activity;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

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
    private String fileName;      // имя, которое мы даём файлу
    private String outputPath;    // путь, который мы задаём hbRecorder (private app dir)

    public ScreenMicRecorderModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        // Добавляем слушатель результата Activity (разрешение на запись экрана)
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
                    } else {
                        Log.e("ScreenMicRecorder", "HBRecorder is null onActivityResult");
                        if (startPromise != null) {
                            startPromise.reject("NO_RECORDER", "HBRecorder instance missing");
                            startPromise = null;
                        }
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

    @ReactMethod public void addListener(String ignoredEventName) {}
    @ReactMethod public void removeListeners(Integer ignoredCount) {}

    @ReactMethod
    public void startRecording(ReadableMap config, Promise promise) {
        startPromise = promise;

        // Приватный каталог приложения (не виден в галерее, не синхронизируется)
        File privateDir = this.reactContext.getFilesDir();
        if (!privateDir.exists()) privateDir.mkdirs();

        hbRecorder = new HBRecorder(this.reactContext, this);

        boolean micEnabled = config.hasKey("mic") && config.getBoolean("mic");
        hbRecorder.isAudioEnabled(micEnabled);

        hbRecorder.setVideoEncoder("DEFAULT");

        // задаём уникальное имя и путь
        fileName = "recording_" + System.currentTimeMillis() + ".mp4";
        outputPath = privateDir.getAbsolutePath();

        hbRecorder.setOutputPath(outputPath);
        hbRecorder.setFileName(fileName);

        boolean notificationActionEnabled =
            config.hasKey("notificationActionEnabled") && config.getBoolean("notificationActionEnabled");
        if (!notificationActionEnabled) {
            hbRecorder.setNotificationDescription("Stop recording from the application");
        }

        try {
            Activity current = getCurrentActivity();
            if (current == null) {
                if (startPromise != null) {
                    startPromise.reject("NO_ACTIVITY", "Current activity is null");
                    startPromise = null;
                }
                return;
            }
            MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) reactContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            current.startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                SCREEN_RECORD_REQUEST_CODE
            );
        } catch (Exception e) {
            Log.e("ScreenMicRecorder", "startRecording failed", e);
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
                    stopPromise.reject("STOP_FAILED", "HBRecorder is null");
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
        boolean deleted = fdelete.delete();
        promise.resolve(deleted);
        Log.d("ScreenMicRecorder", deleted ? "Deleted " + filename : "Unable to delete " + filename);
    }

    // --- HBRecorder callbacks ---

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
        // Правильный источник пути — hbRecorder.getFilePath()
        String uri = null;
        try {
            uri = hbRecorder.getFilePath();
        } catch (Exception e) {
            Log.w("ScreenMicRecorder", "getFilePath() threw", e);
            uri = null;
        }

        // Если по какой-то причине getFilePath() вернул null, используем fallback:
        if (uri == null || uri.isEmpty()) {
            // fallback — наш outputPath + filename (в редких случаях)
            uri = outputPath != null && fileName != null ? outputPath + "/" + fileName : null;
            Log.w("ScreenMicRecorder", "Using fallback uri: " + uri);
        }

        Log.d("ScreenMicRecorder", "HBRecorder Completed. URI: " + uri);

        if (stopPromise != null) {
            if (uri != null) {
                stopPromise.resolve(uri);
            } else {
                stopPromise.reject("NO_PATH", "Recorder completed but no file path available");
            }
            stopPromise = null;
        }

        WritableMap params = Arguments.createMap();
        params.putString("value", uri);
        this.reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("stopEvent", params);
    }

    @Override
    public void HBRecorderOnError(int errorCode, String reason) {
        Log.d("ScreenMicRecorder", "HBRecorderOnError : " + errorCode + " " + reason);

        if (stopPromise != null) {
            stopPromise.reject("STOP_ERROR", "RecorderOnError:" + errorCode + " " + reason);
            stopPromise = null;
        } else if (startPromise != null) {
            startPromise.reject("START_ERROR", "RecorderOnError:" + errorCode + " " + reason);
            startPromise = null;
        }
    }

    @Override
    public void HBRecorderOnPause() {}

    @Override
    public void HBRecorderOnResume() {}
}