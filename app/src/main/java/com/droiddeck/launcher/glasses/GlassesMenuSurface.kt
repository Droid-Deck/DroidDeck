package com.droiddeck.launcher.glasses

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

class GlassesMenuSurface(
    context: Context,
    private val pose: GlassesMenuPose,
    private val onFailure: (Throwable) -> Unit = {},
    private val onInput: (SurfaceTexture, Surface) -> Unit,
) : GLSurfaceView(context) {
    private data class Scene(val settings: GlassesSettings, val state: GlassesState)

    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val generation = AtomicInteger()
    private val pendingFrame = AtomicBoolean()
    private val inputLock = Any()
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    @Volatile private var scene = Scene(GlassesSettings(), GlassesState())
    @Volatile private var sourceAspect = 16f / 9f
    private val renderer = MenuRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun configure(settings: GlassesSettings, state: GlassesState) {
        pose.configure(settings)
        scene = Scene(settings, state)
    }

    fun setSourceSize(width: Int, height: Int) {
        if (width > 0 && height > 0) sourceAspect = width.toFloat() / height
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        queueEvent { renderer.release() }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
        // GLSurfaceView may exit its GL thread before queued cleanup is consumed.
        releaseInput()
    }

    private fun releaseInput() = synchronized(inputLock) {
        inputTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release()
        inputTexture?.release()
        inputSurface = null
        inputTexture = null
        pendingFrame.set(false)
    }

    private fun fail(error: Throwable) {
        if (!failed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        Log.e("GlassesMenu", "Menu rendering is unavailable", error)
        main.post { if (!closed.get()) onFailure(error) }
    }

    private inner class MenuRenderer : Renderer {
        private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
        private val textureMatrix = FloatArray(16)
        private var texture = 0
        private var program = 0
        private var width = 1
        private var height = 1
        private var hasFrame = false
        private var positionLocation = 0
        private var textureLocation = 0
        private var transformLocation = 0
        private var rotationLocation = IntArray(3)
        private var headLocation = 0
        private var screenLocation = 0
        private var opticsLocation = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            releaseInput()
            texture = 0
            program = 0
            hasFrame = false
            if (closed.get() || failed.get()) return
            val token = generation.incrementAndGet()
            try {
                program = linkProgram()
                positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
                textureLocation = GLES20.glGetUniformLocation(program, "uTexture")
                transformLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
                rotationLocation = IntArray(3) { GLES20.glGetUniformLocation(program, "uRotation$it") }
                headLocation = GLES20.glGetUniformLocation(program, "uHead")
                screenLocation = GLES20.glGetUniformLocation(program, "uScreen")
                opticsLocation = GLES20.glGetUniformLocation(program, "uOptics")
                val names = IntArray(1)
                GLES20.glGenTextures(1, names, 0)
                texture = names[0]
                check(texture != 0) { "Could not allocate the menu texture" }
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                checkGl("initialize menu texture")
                val incoming = SurfaceTexture(texture)
                val surface = synchronized(inputLock) {
                    inputTexture = incoming
                    Surface(incoming).also { inputSurface = it }
                }
                incoming.setOnFrameAvailableListener({
                    if (!closed.get() && generation.get() == token) pendingFrame.set(true)
                }, main)
                main.post {
                    if (!closed.get() && !failed.get() && generation.get() == token) onInput(incoming, surface)
                }
            } catch (e: RuntimeException) {
                fail(e)
                release()
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, this.width, this.height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (closed.get() || failed.get()) return
            GLES20.glClearColor(0.015f, 0.015f, 0.02f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0) return
            if (pendingFrame.getAndSet(false)) synchronized(inputLock) {
                inputTexture?.let {
                    try {
                        it.updateTexImage()
                        it.getTransformMatrix(textureMatrix)
                        checkGl("update menu texture")
                        hasFrame = true
                    } catch (e: RuntimeException) {
                        fail(e)
                        release()
                        return
                    }
                }
            }
            if (!hasFrame) return
            val current = scene
            val frame = pose.snapshot()
            val halfWidth = current.settings.screenWidthDm.coerceIn(5, 200) / 20f
            val distance = current.settings.screenDistanceDm.coerceIn(5, 200) / 10f
            val mono = !current.state.sbs
            val fov = current.state.verticalFovDeg.takeIf { it.isFinite() }?.coerceIn(20f, 140f) ?: 23.3f
            val tangent = tan(Math.toRadians(fov.toDouble()) / 2).toFloat()
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(textureLocation, 0)
            GLES20.glUniformMatrix4fv(transformLocation, 1, false, textureMatrix, 0)
            for (row in 0..2) GLES20.glUniform3fv(rotationLocation[row], 1, frame.rotation, row * 3)
            GLES20.glUniform3fv(headLocation, 1, frame.position, 0)
            GLES20.glUniform3f(screenLocation, halfWidth, distance, sourceAspect)
            GLES20.glUniform4f(opticsLocation, tangent, width.toFloat() / height / (if (mono) 1f else 2f),
                if (mono) 0f else IPD_METERS / 2f, if (mono) 1f else 0f)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
        }

        fun release() {
            releaseInput()
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            texture = 0
            program = 0
            hasFrame = false
        }

        private fun checkGl(operation: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "Could not $operation: GL error 0x${error.toString(16)}" }
        }

        private fun shader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException(error)
            }
            return shader
        }

        private fun linkProgram(): Int {
            val vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
            var fragment = 0
            var linked = 0
            try {
                fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
                linked = GLES20.glCreateProgram()
                GLES20.glAttachShader(linked, vertex)
                GLES20.glAttachShader(linked, fragment)
                GLES20.glLinkProgram(linked)
                val status = IntArray(1)
                GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { GLES20.glGetProgramInfoLog(linked) }
                return linked
            } catch (e: RuntimeException) {
                if (linked != 0) GLES20.glDeleteProgram(linked)
                throw e
            } finally {
                GLES20.glDeleteShader(vertex)
                if (fragment != 0) GLES20.glDeleteShader(fragment)
            }
        }
    }

    companion object {
        internal const val IPD_METERS = 0.064f
        private const val VERTEX = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aPosition * 0.5 + 0.5;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTextureMatrix;
            uniform vec3 uRotation0, uRotation1, uRotation2, uHead, uScreen;
            uniform vec4 uOptics;
            varying vec2 vUv;
            vec3 rotate(vec3 v) {
                return vec3(dot(uRotation0, v), dot(uRotation1, v), dot(uRotation2, v));
            }
            void main() {
                bool mono = uOptics.w > 0.5;
                float eye = mono || vUv.x < 0.5 ? 0.0 : 1.0;
                vec2 local = vec2(mono ? vUv.x : vUv.x * 2.0 - eye, 1.0 - vUv.y);
                vec2 ray = (local - 0.5) * vec2(uOptics.y, 1.0) * (2.0 * uOptics.x);
                vec3 direction = rotate(vec3(ray.x, -ray.y, -1.0));
                vec3 origin = uHead + rotate(vec3((eye < 0.5 ? -1.0 : 1.0) * uOptics.z, 0.0, 0.0));
                vec3 color = vec3(0.015, 0.015, 0.02);
                if (direction.z < -0.0001) {
                    float distance = (-uScreen.y - origin.z) / direction.z;
                    vec3 hit = origin + direction * distance;
                    vec2 source = vec2(hit.x / uScreen.x, -hit.y * uScreen.z / uScreen.x) * 0.5 + 0.5;
                    if (distance > 0.0 && all(greaterThanEqual(source, vec2(0.0))) && all(lessThanEqual(source, vec2(1.0)))) {
                        vec2 uv = (uTextureMatrix * vec4(source.x, 1.0 - source.y, 0.0, 1.0)).xy;
                        color = texture2D(uTexture, uv).rgb;
                    }
                }
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
