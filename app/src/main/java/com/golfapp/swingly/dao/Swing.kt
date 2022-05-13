package com.golfapp.swingly.dao

import com.google.mlkit.vision.pose.Pose
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class SwingPosition(
    @Contextual
    var pose: Pose
)

@Serializable
class SwingPhaseData {
    var startTime: Long = 0
    var endTime: Long = 0
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

@Serializable
data class FullSwing(
    var backSwingData: SwingPhaseData = SwingPhaseData(),
    var downSwingPhaseData: SwingPhaseData = SwingPhaseData(),
    var followThroughPhaseData: SwingPhaseData = SwingPhaseData()
)

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