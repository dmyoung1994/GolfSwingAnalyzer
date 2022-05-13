/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.golfapp.swingly.mlkit

import android.annotation.SuppressLint
import android.util.Log
import com.golfapp.swingly.activity.SwingRecorderFragment
import com.golfapp.swingly.camera.CameraManager
import com.golfapp.swingly.constants.PoseConstants
import com.golfapp.swingly.dao.FullSwing
import com.golfapp.swingly.dao.SwingPhase
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.golfapp.swingly.graphics.GraphicOverlay
import com.google.mlkit.vision.pose.*
import com.golfapp.swingly.graphics.PoseGraphic
import java.util.*
import kotlin.concurrent.timerTask
import kotlin.math.abs
import kotlin.math.max

/** A processor to run pose detector.  */
@SuppressLint("UnsafeExperimentalUsageError")
class SwingDetectorProcessor(
    fragment: SwingRecorderFragment,
    options: PoseDetectorOptionsBase,
    private val cameraManager: CameraManager
) : VisionProcessorBase<Pose>(fragment.baseContext) {
    private val detector: PoseDetector = PoseDetection.getClient(options)
    private var currentSwingPhase = SwingPhase.NOT_ENGAGED
    private var previousSwingPhase = SwingPhase.NOT_ENGAGED
    // TODO handle follow through
    // This is to eliminate false positives due to jitter
    private var restingCounter = 10

    // Bigger is lower on screen
    private var lowestHandVal = -1f
    // Smaller is higher on screen
    private var highestHandVal = 9000f
    private var previousHandPosition = Pair(0f, 0f)

    private var maxJiggle: Float = 1.5f
    private var frameJiggle: Float = 0f
    private var changeBuffer: Float = 5f

    private var fullSwingData: FullSwing? = null

    override fun stop() {
        super.stop()
        detector.close()
    }

    override fun detectInImage(image: InputImage): Task<Pose> {
        return detector.process(image)
    }

    override fun onSuccess(
        results: Pose,
        graphicOverlay: GraphicOverlay
    ) {
        if (results.allPoseLandmarks.isEmpty()) {
            return
        }

        // Don't care if the whole body isn't in frame. Ignore process
        var allPointsInFrame = true
        for (landmark in results.allPoseLandmarks) {
            // We only care about it the points we care about are in the frame
            if (PoseConstants.drawablePoints.contains(landmark.landmarkType)) {
                allPointsInFrame = allPointsInFrame && landmark.inFrameLikelihood > 0.5
            }
        }

        if (!allPointsInFrame) {
            resetPositions()
            return
        }

        graphicOverlay.add(PoseGraphic(graphicOverlay, results))
        analyzeSwing(results)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyzeSwing(pose: Pose) {
        // If we have nothing to process, just skip
        if (pose.allPoseLandmarks.isEmpty()) {
            cameraManager.promptGetInFrame()
            return
        }

        val handPositionY = (pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)!!.position.y
                            + pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)!!.position.y) / 2
        val handPositionX = (pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)!!.position.x
                            + pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)!!.position.x) / 2


        val hipPositionY = (pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)!!.position.y
                           + pose.getPoseLandmark(PoseLandmark.LEFT_HIP)!!.position.y) / 2

        currentSwingPhase = progressSwingPhase(handPositionX, handPositionY, hipPositionY)

        when (currentSwingPhase) {
            SwingPhase.READY -> {
                frameJiggle = abs(previousHandPosition.second - handPositionY)
                maxJiggle = max(frameJiggle, maxJiggle)
                trackLowPos(handPositionY)
            }
            SwingPhase.BACK_SWING -> {
                trackHighPos(handPositionY)
                fullSwingData!!.backSwingData.addPose(pose)
            }
            SwingPhase.DOWN_SWING -> {
                fullSwingData!!.downSwingPhaseData.addPose(pose)
            }
            SwingPhase.FOLLOW_THROUGH -> {
                fullSwingData!!.followThroughPhaseData.addPose(pose)
            }
            else -> {/* NOOP */}
        }

        previousHandPosition = Pair(handPositionX, handPositionY)

        if (currentSwingPhase == previousSwingPhase) {
            return
        }

        Log.d(TAG, "Swing Phase: $currentSwingPhase")

        // Below here will only fire once
        when (currentSwingPhase) {
            SwingPhase.READY -> {
                fullSwingData = FullSwing()
                cameraManager.onSwingReady()
            }
            SwingPhase.BACK_SWING -> {
                fullSwingData!!.downSwingPhaseData.startTime = System.currentTimeMillis()
            }
            SwingPhase.DOWN_SWING -> {
                fullSwingData!!.backSwingData.endTime = System.currentTimeMillis()
                fullSwingData!!.downSwingPhaseData.startTime = System.currentTimeMillis()
            }
            SwingPhase.FOLLOW_THROUGH -> {
                fullSwingData?.downSwingPhaseData?.endTime = System.currentTimeMillis()
                fullSwingData?.followThroughPhaseData?.startTime = System.currentTimeMillis()
                resetSwingTrackerAfterDelay()
            }
            else -> {/* NOOP */}
        }

        previousSwingPhase = currentSwingPhase
    }

    private fun progressSwingPhase(handPositionX: Float, handPositionY: Float, hipPositionY: Float): SwingPhase {
        return when {
            isSwingReady(handPositionX, handPositionY, hipPositionY) -> {
                SwingPhase.READY
            }
            isBackSwingStarted(handPositionY) -> {
                SwingPhase.BACK_SWING
            }
            isDownSwingStarted(handPositionY) -> {
                SwingPhase.DOWN_SWING
            }
            isFollowThroughStarted(handPositionY) -> {
                SwingPhase.FOLLOW_THROUGH
            }
            else -> {
                return currentSwingPhase
            }
        }
    }

    private fun isSwingReady(handPositionX: Float, handPositionY: Float, hipPositionY: Float): Boolean {
        val prePhase = abs(handPositionX - previousHandPosition.first) <= maxJiggle
                && abs(handPositionY - previousHandPosition.second) <= maxJiggle
                && handPositionY >= hipPositionY
                && currentSwingPhase == SwingPhase.NOT_ENGAGED

        if (prePhase) {
            restingCounter -= 1
        } else {
            restingCounter = 10
        }

        return prePhase && restingCounter == 0
    }

    private fun isBackSwingStarted(position: Float): Boolean {
        if (abs(position - lowestHandVal) < changeBuffer) {
            return false
        }

        return position < lowestHandVal && currentSwingPhase == SwingPhase.READY
    }

    private fun isDownSwingStarted(position: Float): Boolean {
        if (abs(position - highestHandVal) < changeBuffer) {
            return false
        }

        return position > highestHandVal && currentSwingPhase == SwingPhase.BACK_SWING
    }

    private fun isFollowThroughStarted(handPositionY: Float): Boolean {
        val isRisingAgain = handPositionY - previousHandPosition.second <= 0
        return isRisingAgain && currentSwingPhase == SwingPhase.DOWN_SWING
    }

    private fun trackLowPos(position: Float) {
        if (position > lowestHandVal) {
            lowestHandVal = position
        }
    }

    private fun trackHighPos(position: Float) {
        if (position < highestHandVal) {
            highestHandVal = position
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun resetSwingTrackerAfterDelay() {
        Timer().schedule(timerTask {
            cameraManager.onSwingFinished(fullSwingData)
            resetPositions()
        }, 1500)
    }


    private fun resetPositions() {
        // Save video and preform analytics
        fullSwingData = null
        lowestHandVal = -1f
        highestHandVal = 9000f
        currentSwingPhase = SwingPhase.NOT_ENGAGED
        previousHandPosition = Pair(0f, 0f)
        restingCounter = 10
        frameJiggle = 0f
    }

    override fun onFailure(e: Exception) {
        Log.e(TAG, "Pose detection failed!", e)
    }

    companion object {
        private const val TAG = "PoseDetectorProcessor"
    }

}