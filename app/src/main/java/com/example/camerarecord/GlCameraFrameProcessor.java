package com.example.camerarecord;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;

/**
 * Receives camera frames via SurfaceTexture, rotates them in OpenGL and renders to MediaCodec input surface.
 */
public class GlCameraFrameProcessor {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "  gl_FragColor.r = 1.0;\n" +
            "}\n";

    private final Surface codecInputSurface;
    private final int width;
    private final int height;
    private final int rotationDegrees;

    private Thread glThread;
    private final Object frameSyncObject = new Object();
    private boolean frameAvailable = false;
    private volatile boolean running = false;

    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraInputSurface;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program = 0;
    private int oesTextureId = 0;

    public GlCameraFrameProcessor(Surface codecInputSurface, int width, int height, int rotationDegrees) {
        this.codecInputSurface = codecInputSurface;
        this.width = width;
        this.height = height;
        this.rotationDegrees = rotationDegrees;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        CountDownLatch initLatch = new CountDownLatch(1);
        glThread = new Thread(() -> {
            try {
                initEgl();
                initGlObjects();
            } finally {
                initLatch.countDown();
            }
            renderLoop();
            releaseGlObjects();
            releaseEgl();
        }, "GlCameraFrameProcessor");
        glThread.start();
        try {
            initLatch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public Surface getCameraInputSurface() {
        return cameraInputSurface;
    }

    public void stop() {
        running = false;
        synchronized (frameSyncObject) {
            frameSyncObject.notifyAll();
        }
        if (glThread != null) {
            try {
                glThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            glThread = null;
        }
        if (cameraInputSurface != null) {
            cameraInputSurface.release();
            cameraInputSurface = null;
        }
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }
    }

    private void renderLoop() {
        float[] stMatrix = new float[16];
        float[] rotateMatrix = new float[16];
        float[] finalMatrix = new float[16];

        Matrix.setIdentityM(rotateMatrix, 0);
        Matrix.translateM(rotateMatrix, 0, 0.5f, 0.5f, 0f);
        Matrix.rotateM(rotateMatrix, 0, (float)(180), 0f, 0f, 1f);
        Matrix.translateM(rotateMatrix, 0, -0.5f, -0.5f, 0f);

        while (running) {
            synchronized (frameSyncObject) {
                while (!frameAvailable && running) {
                    try {
                        frameSyncObject.wait();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frameAvailable = false;
            }

            if (!running) {
                break;
            }

            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(stMatrix);
            Matrix.multiplyMM(finalMatrix, 0, stMatrix, 0, rotateMatrix, 0);

            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            int textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
            int matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");

            float[] triangleVertices = {
                    -1f, -1f,
                    1f, -1f,
                    -1f, 1f,
                    1f, 1f
            };
            float[] textureVertices = {
                    0f, 1f,
                    1f, 1f,
                    0f, 0f,
                    1f, 0f
            };

            java.nio.FloatBuffer vertexBuffer = java.nio.ByteBuffer
                    .allocateDirect(triangleVertices.length * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertexBuffer.put(triangleVertices).position(0);

            java.nio.FloatBuffer texBuffer = java.nio.ByteBuffer
                    .allocateDirect(textureVertices.length * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
            texBuffer.put(textureVertices).position(0);

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(textureHandle);
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, finalMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0);

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], codecInputSurface, surfaceAttribs, 0);

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void initGlObjects() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        cameraSurfaceTexture = new SurfaceTexture(oesTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(height, width);
        cameraSurfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
            synchronized (frameSyncObject) {
                frameAvailable = true;
                frameSyncObject.notifyAll();
            }
        });

        cameraInputSurface = new Surface(cameraSurfaceTexture);
    }

    private void releaseGlObjects() {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (oesTextureId != 0) {
            int[] textures = {oesTextureId};
            GLES20.glDeleteTextures(1, textures, 0);
            oesTextureId = 0;
        }
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
