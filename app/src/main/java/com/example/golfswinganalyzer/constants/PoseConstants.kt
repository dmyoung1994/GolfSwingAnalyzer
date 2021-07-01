package com.example.golfswinganalyzer.constants

import com.google.common.collect.Sets
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.HashSet

class PoseConstants {
    companion object {
        val drawablePoints: HashSet<Int> = Sets.newHashSet(
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.NOSE
        )

        val noseToEyePorportionToHead = 4
    }

}