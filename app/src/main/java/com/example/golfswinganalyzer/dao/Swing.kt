package com.example.golfswinganalyzer.dao

import com.google.mlkit.vision.pose.Pose
import java.io.Serializable

data class SwingPosition(
    var pose: Pose
) {
}

class SwingPhaseData {
    var startTime: Long = 0;
    var endTime: Long = 0;
    private val bodyPositions: MutableCollection<SwingPosition> = mutableListOf()

    fun addPose(pose: Pose) {
        synchronized(this) {
            bodyPositions.add(SwingPosition(pose))
        }
    }

    fun getPoses() : List<SwingPosition> {
        synchronized(this) {
            return bodyPositions.toList()
        }
    }
}

data class FullSwing(
    var backSwingData: SwingPhaseData = SwingPhaseData(),
    var downSwingPhaseData: SwingPhaseData = SwingPhaseData(),
    var followThroughPhaseData: SwingPhaseData = SwingPhaseData()
) : Serializable {}

enum class SwingPhase {
    NOT_ENGAGED,
    READY,
    BACK_SWING,
    DOWN_SWING,
    FOLLOW_THROUGH,
}

enum class Handiness {
    RIGHT,
    LEFT
}