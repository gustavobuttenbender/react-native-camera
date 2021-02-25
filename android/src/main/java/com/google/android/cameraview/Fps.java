package com.google.android.cameraview;

import android.hardware.Camera;

import java.util.ArrayList;

import static java.lang.Math.abs;

// from google mlkit showcase solution https://github.com/googlesamples/mlkit

public class Fps {
    public static int[] selectPreviewFpsRange(ArrayList<int[]> fpsRange) {
        // The camera API uses integers scaled by a factor of 1000 instead of floating-point frame
        // rates.
        int desiredPreviewFpsScaled = (int) (30.0f * 1000f);

        // The method for selecting the best range is to minimize the sum of the differences between
        // the desired value and the upper and lower bounds of the range.  This may select a range
        // that the desired value is outside of, but this is often preferred.  For example, if the
        // desired frame rate is 29.97, the range (30, 30) is probably more desirable than the
        // range (15, 30).
        int[] selectedFpsRange = null;

        int minDiff = Integer.MAX_VALUE;
        for (int[] range : fpsRange) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = abs(deltaMin) + abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }
}
