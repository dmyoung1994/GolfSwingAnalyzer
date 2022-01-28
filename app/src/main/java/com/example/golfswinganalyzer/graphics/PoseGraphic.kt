package com.example.golfswinganalyzer.graphics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import com.example.golfswinganalyzer.constants.PoseConstants
import com.example.golfswinganalyzer.graphics.GraphicOverlay.Graphic
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/** Draw the detected pose in preview.  */
class PoseGraphic(overlay: GraphicOverlay?, private val pose: Pose) : Graphic(overlay) {
    private val leftPaint: Paint
    private val rightPaint: Paint
    private val whitePaint: Paint = Paint()
    private val drawablePoints = PoseConstants.drawablePoints
    override fun draw(canvas: Canvas) {
        val landmarks = pose.allPoseLandmarks
        if (landmarks.isEmpty()) {
            return
        }
        // Draw all the points
        for (landmark in landmarks) {
            if (drawablePoints.contains(landmark.landmarkType)) {
                drawPoint(canvas, landmark.position, whitePaint)
            }
        }
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
        val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        val leftHeel = pose.getPoseLandmark(PoseLandmark.LEFT_HEEL)
        val rightHeel = pose.getPoseLandmark(PoseLandmark.RIGHT_HEEL)
        val leftFootIndex = pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
        val rightFootIndex = pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)
        drawLine(canvas, leftShoulder.position, rightShoulder.position, whitePaint)
        drawLine(canvas, leftHip.position, rightHip.position, whitePaint)

        // Left body
        drawLine(canvas, leftShoulder.position, leftElbow.position, leftPaint)
        drawLine(canvas, leftElbow.position, leftWrist.position, leftPaint)
        drawLine(canvas, leftShoulder.position, leftHip.position, leftPaint)
        drawLine(canvas, leftHip.position, leftKnee.position, leftPaint)
        drawLine(canvas, leftKnee.position, leftAnkle.position, leftPaint)
        drawLine(canvas, leftAnkle.position, leftHeel.position, leftPaint)
        drawLine(canvas, leftHeel.position, leftFootIndex.position, leftPaint)

        // Right body
        drawLine(canvas, rightShoulder.position, rightElbow.position, rightPaint)
        drawLine(canvas, rightElbow.position, rightWrist.position, rightPaint)
        drawLine(canvas, rightShoulder.position, rightHip.position, rightPaint)
        drawLine(canvas, rightHip.position, rightKnee.position, rightPaint)
        drawLine(canvas, rightKnee.position, rightAnkle.position, rightPaint)
        drawLine(canvas, rightAnkle.position, rightHeel.position, rightPaint)
        drawLine(canvas, rightHeel.position, rightFootIndex.position, rightPaint)
    }

    private fun drawPoint(canvas: Canvas, point: PointF?, paint: Paint?) {
        if (point == null) {
            return
        }
        canvas.drawCircle(
            translateX(point.x), translateY(point.y), PoseGraphic.Companion.DOT_RADIUS,
            paint!!
        )
    }

    private fun drawLine(canvas: Canvas, start: PointF?, end: PointF?, paint: Paint?) {
        if (start == null || end == null) {
            return
        }
        canvas.drawLine(
            translateX(start.x), translateY(start.y), translateX(end.x), translateY(end.y), paint!!
        )
    }

    companion object {
        private const val DOT_RADIUS = 8.0f
        private const val IN_FRAME_LIKELIHOOD_TEXT_SIZE = 30.0f
    }

    init {
        whitePaint.color = Color.WHITE
        whitePaint.textSize = PoseGraphic.Companion.IN_FRAME_LIKELIHOOD_TEXT_SIZE
        leftPaint = Paint()
        leftPaint.color = Color.GREEN
        rightPaint = Paint()
        rightPaint.color = Color.YELLOW
    }
}