package com.globalpayments.library.utilities;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import androidx.annotation.RestrictTo;

/**
 * Utility class for network-related operations.
 * <p>
 * This class is restricted to use within this library only.
 * It must not be used by consumers of the library.
 * </p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class NetworkUtils {

    // Prevent instantiation
    private NetworkUtils() {
    }

    /**
     * Checks whether a network connection is unavailable on the device.
     *
     * @param context Application or activity context used to access system services.
     * @return {@code true} if no active network connection is available, {@code false} otherwise.
     */
    public static boolean isNetworkUnavailable(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return true;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                return true;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            return capabilities == null
                    || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo == null || !activeNetworkInfo.isConnected();
        }
    }
}

