package com.golfapp.swingly.analyzer

import android.graphics.PointF
import com.golfapp.swingly.constants.PoseConstants
import com.golfapp.swingly.dao.Handiness
import com.golfapp.swingly.dao.SwingPhaseData
import com.google.mlkit.vision.pose.PoseLandmark
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class DownTheLine(
    var spineTiltAtAddress: Double = 0.0,
    var leftKneeBendAtAddress: Double = 0.0,
    var rightKneeBendAtAddress: Double = 0.0
)

@Serializable
data class Facing(
    var hipSwayAtTop: Float = 0f
)

@Serializable
data class General(
    var hipTurnAtTop: Double = 0.0,
    var shoulderTiltAtTop: Double = 0.0,
    var duration: Long = 0
)

@Serializable
data class BackSwingResultData(
    var generalData: General = General(),
    var facingData: Facing = Facing(),
    var downTheLineData: DownTheLine = DownTheLine()
)

// All user units in inches
class BackSwingAnalyzer(
    private val backSwingData: SwingPhaseData,
    private val height: Float = 74f,
    private val hipWidth: Float = 15.4f,
    private val shoulderWidth: Float = 18.1f,
    private val handiness: Handiness = Handiness.RIGHT
) {
    private val firstPosition = backSwingData.getPoses().first().pose
    private val lastPosition = backSwingData.getPoses().last().pose

    // Private variables about the positions translated into world space
    private var shoulderWidthInPoints = 0f
    private var hipWidthInPoints = 0f
    private var inchesPerPoint = 0f
    private var pointsPerInch = 0f

    var swingData: BackSwingResultData = BackSwingResultData()

    fun run() {
        val poseLengthInPoints = calculatePoseLength()
        inchesPerPoint = height / poseLengthInPoints
        pointsPerInch = poseLengthInPoints / height

        hipWidthInPoints = hipWidth * inchesPerPoint
        shoulderWidthInPoints = shoulderWidth * inchesPerPoint


        swingData.generalData.duration = backSwingData.endTime - backSwingData.startTime

        swingData.generalData.hipTurnAtTop = radToDegrees(abs(
            calculateHipTurn(firstPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position, firstPosition.getPoseLandmark(PoseLandmark.LEFT_HIP)!!.position) -
                    calculateHipTurn(lastPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position, lastPosition.getPoseLandmark(PoseLandmark.LEFT_HIP)!!.position)))
        swingData.generalData.shoulderTiltAtTop = radToDegrees(calculateShoulderTilt(lastPosition.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)!!.position, lastPosition.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)!!.position))
        swingData.facingData.hipSwayAtTop = (lastPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position.x - lastPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position.x
                - firstPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position.x - lastPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position.x) * pointsPerInch

        val hipToShoulderDist = distance(firstPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position, firstPosition.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)!!.position)
        val spineReferencePoint = PointF(firstPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position.x, firstPosition.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)!!.position.y)
        val spineReferenceDist = distance(firstPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position, spineReferencePoint)
        swingData.downTheLineData.spineTiltAtAddress = radToDegrees(calculateSpineTilt(spineReferenceDist, hipToShoulderDist))

        val rightHipToKneeDist = distance(firstPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position, firstPosition.getPoseLandmark(PoseLandmark.RIGHT_KNEE)!!.position)
        val rightKneeReferencePoint = PointF(firstPosition.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position.y, firstPosition.getPoseLandmark(PoseLandmark.RIGHT_KNEE)!!.position.x)
        val rightKneeReferenceDist = distance(firstPosition.getPoseLandmark(PoseLandmark.RIGHT_KNEE)!!.position, rightKneeReferencePoint)
        swingData.downTheLineData.rightKneeBendAtAddress = radToDegrees(acos(rightKneeReferenceDist / rightHipToKneeDist))

        val leftHipToKneeDist = distance(firstPosition.getPoseLandmark(PoseLandmark.LEFT_HIP)!!.position, firstPosition.getPoseLandmark(PoseLandmark.LEFT_KNEE)!!.position)
        val leftKneeReferencePoint = PointF(firstPosition.getPoseLandmark(PoseLandmark.LEFT_HIP)!!.position.y, firstPosition.getPoseLandmark(PoseLandmark.LEFT_KNEE)!!.position.x)
        val leftKneeReferenceDist = distance(firstPosition.getPoseLandmark(PoseLandmark.LEFT_KNEE)!!.position, leftKneeReferencePoint)
        swingData.downTheLineData.leftKneeBendAtAddress = radToDegrees(acos(leftKneeReferenceDist / leftHipToKneeDist))
    }

    private fun distance(point1: PointF, point2: PointF): Float {
        return sqrt((point1.x - point2.x).pow(2) + (point1.y - point2.y).pow(2))
    }

    private fun calculatePoseLength(): Float {
        val shin = distance(firstPosition.getPoseLandmark(PoseLandmark.LEFT_ANKLE)!!.position, firstPosition.getPoseLandmark(PoseLandmark.LEFT_KNEE)!!.position)
        val femur = distance(firstPosition.getPoseLandmark(PoseLandmark.LEFT_KNEE)!!.position, firstPosition.getPoseLandmark(PoseLandmark.LEFT_HIP)!!.position)
        val hipToShoulder = distance(firstPosition.getPoseLandmark(PoseLandmark.LEFT_HIP)!!.position, firstPosition.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)!!.position)
        val shoulderToNose = abs(firstPosition.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)!!.position.y - firstPosition.getPoseLandmark(PoseLandmark.NOSE)!!.position.y)
        val headLength = abs(firstPosition.getPoseLandmark(PoseLandmark.NOSE)!!.position.y - firstPosition.getPoseLandmark(PoseLandmark.LEFT_EYE)!!.position.y) * PoseConstants.noseToEyePorportionToHead

        return shin + femur + hipToShoulder + shoulderToNose + headLength
    }

    private fun radToDegrees(rad: Float): Double {
        return rad * (180 / PI)
    }

    
    private fun calculateHipTurn(rightHip: PointF, leftHip: PointF): Float {
        return acos(distance(rightHip, leftHip) / hipWidthInPoints)
    }

    private fun calculateSpineTilt(referenceDistance: Float, hipToShoulderDistance: Float) : Float {
        return acos(referenceDistance / hipToShoulderDistance)
    }

    private fun calculateShoulderTilt(rightShoulder: PointF, leftShoulder: PointF): Float {
        return acos(shoulderWidthInPoints / distance(rightShoulder, leftShoulder))
    }
}