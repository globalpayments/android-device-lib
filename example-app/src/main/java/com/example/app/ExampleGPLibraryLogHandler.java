package com.example.app;

import com.tsys.payments.library.logging.GPLibraryLogLevel;
import com.tsys.payments.library.logging.GPLibraryLogger.GPLibraryLogCallback;
import org.json.JSONObject;
import timber.log.Timber;

public class ExampleGPLibraryLogHandler implements GPLibraryLogCallback {
    private static final String TAG = ExampleGPLibraryLogHandler.class.getSimpleName();

    @Override
    public void onLog(JSONObject logDataJson) {
        String logMessage = logDataJson.toString();
        String levelValue = logDataJson.optString("level", GPLibraryLogLevel.DEBUG.name());
        com.tsys.payments.library.logging.GPLibraryLogLevel logLevel;

        try {
            logLevel = GPLibraryLogLevel.valueOf(levelValue);
        } catch (IllegalArgumentException exception) {
            logLevel = GPLibraryLogLevel.DEBUG;
        }

        switch (logLevel) {
            case VERBOSE:
                Timber.tag(TAG).v(logMessage);
                break;
            case INFO:
                Timber.tag(TAG).i(logMessage);
                break;
            case WARNING:
                Timber.tag(TAG).w(logMessage);
                break;
            case ERROR:
                Timber.tag(TAG).e(logMessage);
                break;
            case DEBUG:
            case UNKNOWN:
            default:
                Timber.tag(TAG).d(logMessage);
                break;
        }
    }
}
