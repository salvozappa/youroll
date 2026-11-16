// YouRoll-specific: the OpenGL ES stage of the segment writer. On iOS the writer
// receives CVPixelBuffers and normalises mismatched frames with Core Image
// (CaptureSegmentWriter.swift `appendVideo`); on Android camera frames arrive in
// a SurfaceTexture and are drawn into the encoder's input surface here.
// YouRoll Android port copyright (c) 2026 Salvatore Zappalà, MIT licence.
package com.salvozappa.youroll

import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** One EGL context with a recordable config. Use from a single thread. */
internal class CaptureEGL {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private val pbuffer: EGLSurface

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("No EGL display.")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("EGL could not start.")
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw IllegalStateException("No recordable EGL configuration.")
        }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("EGL context could not be created.")
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(null)
    }

    fun windowSurface(surface: Surface): EGLSurface {
        val result = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (result == null || result == EGL14.EGL_NO_SURFACE) throw IllegalStateException("The encoder surface could not be attached (EGL ${EGL14.eglGetError()}).")
        return result
    }

    /** null makes the off-screen surface current (texture updates between takes). */
    fun makeCurrent(surface: EGLSurface?) {
        val target = surface ?: pbuffer
        if (!EGL14.eglMakeCurrent(display, target, target, context)) throw IllegalStateException("EGL error ${EGL14.eglGetError()}.")
    }

    fun present(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
        if (!EGL14.eglSwapBuffers(display, surface)) throw IllegalStateException("The video encoder refused a frame (EGL ${EGL14.eglGetError()}).")
    }

    fun releaseSurface(surface: EGLSurface) {
        makeCurrent(null)
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object { const val EGL_RECORDABLE_ANDROID = 0x3142 }
}

/**
 * Draws camera frames into a canvas framebuffer at the segment's size, then the
 * canvas into the encoder surface. The canvas keeps the last picture, so a camera
 * change can keep writing the held image (iOS keeps `lastPixel` for the same reason).
 */
internal class CaptureFrameRenderer {
    private val quad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coordinates: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val cameraProgram = Program(CAMERA_FRAGMENT)
    private val canvasProgram = Program(CANVAS_FRAGMENT)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var framebuffer = 0
    private var canvasTexture = 0
    var canvasWidth = 0; private set
    var canvasHeight = 0; private set

    fun createCameraTexture(): Int {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return names[0]
    }

    fun deleteTexture(name: Int) = GLES20.glDeleteTextures(1, intArrayOf(name), 0)

    fun ensureCanvas(width: Int, height: Int) {
        if (width == canvasWidth && height == canvasHeight && framebuffer != 0) return
        releaseCanvas()
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0); canvasTexture = names[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, names, 0); framebuffer = names[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, canvasTexture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw IllegalStateException("The video canvas could not be created ($status).")
        canvasWidth = width; canvasHeight = height
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** Draw one camera frame into the canvas (black bars where the frame does not fill it). */
    fun drawCamera(texture: Int, surfaceTransform: FloatArray, placement: CameraPlacement) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, canvasWidth, canvasHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val raw = CaptureCanvas.multiply(surfaceTransform, CaptureCanvas.cameraTransformUndo(surfaceTransform))
        val textureMatrix = CaptureCanvas.multiply(raw, placement.texture)
        cameraProgram.draw(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture, placement.position, textureMatrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** Copy the canvas to the current EGL surface (the encoder input). */
    fun drawCanvas() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, canvasWidth, canvasHeight)
        canvasProgram.draw(GLES20.GL_TEXTURE_2D, canvasTexture, identity, identity)
    }

    private fun releaseCanvas() {
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (canvasTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(canvasTexture), 0)
        framebuffer = 0; canvasTexture = 0; canvasWidth = 0; canvasHeight = 0
    }

    fun release() {
        releaseCanvas()
        cameraProgram.release(); canvasProgram.release()
    }

    private inner class Program(fragment: String) {
        private val program: Int
        private val position: Int
        private val coordinate: Int
        private val positionMatrix: Int
        private val textureMatrix: Int

        init {
            val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
            val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) throw IllegalStateException("GL program: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteShader(vertexShader); GLES20.glDeleteShader(fragmentShader)
            position = GLES20.glGetAttribLocation(program, "aPosition")
            coordinate = GLES20.glGetAttribLocation(program, "aCoordinate")
            positionMatrix = GLES20.glGetUniformLocation(program, "uPosition")
            textureMatrix = GLES20.glGetUniformLocation(program, "uTexture")
        }

        fun draw(target: Int, texture: Int, positionTransform: FloatArray, textureTransform: FloatArray) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, texture)
            GLES20.glUniformMatrix4fv(positionMatrix, 1, false, positionTransform, 0)
            GLES20.glUniformMatrix4fv(textureMatrix, 1, false, textureTransform, 0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(coordinate)
            GLES20.glBindTexture(target, 0)
        }

        fun release() = GLES20.glDeleteProgram(program)

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) throw IllegalStateException("GL shader: " + GLES20.glGetShaderInfoLog(shader))
            return shader
        }
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uPosition;
            uniform mat4 uTexture;
            attribute vec4 aPosition;
            attribute vec4 aCoordinate;
            varying vec2 vCoordinate;
            void main() {
                gl_Position = uPosition * aPosition;
                vCoordinate = (uTexture * aCoordinate).xy;
            }"""
        const val CAMERA_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vCoordinate;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vCoordinate); }"""
        const val CANVAS_FRAGMENT = """
            precision mediump float;
            varying vec2 vCoordinate;
            uniform sampler2D sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vCoordinate); }"""

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}

/**
 * Where one camera's frames go in a segment's canvas. The file's rotation
 * metadata is the first camera's rotation; a later camera whose sensor needs a
 * different rotation (front 270° after rear 90°) is turned by the difference and
 * fitted, as iOS normalises mismatched frames into the segment's canvas.
 */
internal class CameraPlacement(val position: FloatArray, val texture: FloatArray) {
    companion object {
        fun of(crop: Rect, bufferWidth: Int, bufferHeight: Int, rotation: Int, segmentRotation: Int, mirror: Boolean,
               canvasWidth: Int, canvasHeight: Int): CameraPlacement {
            val geometry = CaptureCanvas.fit(crop.width(), crop.height(), rotation, segmentRotation, canvasWidth, canvasHeight)
            val position = FloatArray(16)
            val turn = FloatArray(16)
            Matrix.setRotateM(turn, 0, -geometry.turn.toFloat(), 0f, 0f, 1f)
            val scale = FloatArray(16)
            Matrix.setIdentityM(scale, 0)
            Matrix.scaleM(scale, 0, geometry.scaleX.toFloat(), geometry.scaleY.toFloat(), 1f)
            Matrix.multiplyMM(position, 0, scale, 0, turn, 0)
            // Texture space before the SurfaceTexture transform: origin bottom-left.
            val w = bufferWidth.toFloat().coerceAtLeast(1f); val h = bufferHeight.toFloat().coerceAtLeast(1f)
            val u0 = crop.left / w; val u1 = crop.right / w
            val v0 = 1f - crop.bottom / h; val v1 = 1f - crop.top / h
            val texture = FloatArray(16)
            Matrix.setIdentityM(texture, 0)
            Matrix.translateM(texture, 0, u0, v0, 0f)
            Matrix.scaleM(texture, 0, u1 - u0, v1 - v0, 1f)
            if (mirror) {
                // Mirror across the upright picture's vertical axis.
                if (rotation % 180 == 0) { Matrix.translateM(texture, 0, 1f, 0f, 0f); Matrix.scaleM(texture, 0, -1f, 1f, 1f) }
                else { Matrix.translateM(texture, 0, 0f, 1f, 0f); Matrix.scaleM(texture, 0, 1f, -1f, 1f) }
            }
            return CameraPlacement(position, texture)
        }
    }
}

/** Pure geometry of [CameraPlacement] (JVM-tested). */
object CaptureCanvas {
    /** [turn] clockwise degrees; [scaleX]/[scaleY] of the full-canvas quad after turning. */
    data class Fit(val turn: Int, val scaleX: Double, val scaleY: Double)

    fun fit(width: Int, height: Int, rotation: Int, segmentRotation: Int, canvasWidth: Int, canvasHeight: Int): Fit {
        val turn = ((rotation - segmentRotation) % 360 + 360) % 360
        val turnedWidth = if (turn % 180 == 0) width else height
        val turnedHeight = if (turn % 180 == 0) height else width
        if (turnedWidth <= 0 || turnedHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return Fit(turn, 1.0, 1.0)
        val scale = minOf(canvasWidth.toDouble() / turnedWidth, canvasHeight.toDouble() / turnedHeight)
        return Fit(turn, turnedWidth * scale / canvasWidth, turnedHeight * scale / canvasHeight)
    }

    /** Encoders want even dimensions. */
    fun canvasSize(crop: Rect): Pair<Int, Int> = (crop.width() and 1.inv()) to (crop.height() and 1.inv())

    /**
     * A SurfaceTexture fed by the camera carries the camera transform (sensor
     * orientation, possibly a mirror) in its transform matrix (CameraX
     * `TransformationInfo.hasCameraTransform`). The writer wants the raw sensor
     * buffer, as a MediaCodec surface receives it, and applies `rotationDegrees`
     * as file metadata like the CameraX Recorder. This returns the texture-space
     * quarter turn and/or mirror about the centre (column-major 4×4) that cancels
     * the matrix's turn, leaving only the usual vertical flip of [surfaceTransform].
     */
    fun cameraTransformUndo(surfaceTransform: FloatArray): FloatArray {
        for (mirror in listOf(false, true)) for (quarter in 0..3) {
            val candidate = centreTurn(quarter, mirror)
            val product = multiply(surfaceTransform, candidate)
            val epsilon = 1e-3f
            if (product[0] > epsilon && product[5] < -epsilon && kotlin.math.abs(product[1]) < epsilon && kotlin.math.abs(product[4]) < epsilon) {
                return candidate
            }
        }
        return identity()
    }

    /** Texture coordinates turned by [quarter] × 90° (counter-clockwise) about (0.5, 0.5), mirrored in u first. */
    fun centreTurn(quarter: Int, mirror: Boolean): FloatArray {
        // Linear part a b / c d acting on (u - 0.5, v - 0.5).
        var a = 1f; var b = 0f; var c = 0f; var d = 1f
        repeat(((quarter % 4) + 4) % 4) { val na = -c; val nb = -d; c = a; d = b; a = na; b = nb }
        if (mirror) { a = -a; c = -c }
        val m = identity()
        m[0] = a; m[1] = c; m[4] = b; m[5] = d
        m[12] = 0.5f - (a * 0.5f + b * 0.5f)
        m[13] = 0.5f - (c * 0.5f + d * 0.5f)
        return m
    }

    fun identity(): FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** Column-major 4×4 product [left] · [right]. */
    fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (column in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += left[k * 4 + row] * right[column * 4 + k]
            out[column * 4 + row] = sum
        }
        return out
    }
}
