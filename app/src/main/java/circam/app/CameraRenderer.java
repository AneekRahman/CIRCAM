package circam.app;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;


import circam.app.filter.CameraFilter;
import circam.app.filter.ChromaticAberrationFilter;

import static android.content.Context.WINDOW_SERVICE;

public class CameraRenderer implements TextureView.SurfaceTextureListener, Runnable {

    // Camera orientation variables
    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();
    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }
    ///////////////////////////////////////////////////////////////////////////////////

    private static final String TAG = "Camera-Renderer::::::";


    private static final int EGL_OPENGL_ES2_BIT = 4;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    private static final int DRAW_INTERVAL = 1000 / 30;

    private Context mContext;

    private Camera mCamera;
    private int mCameraId;
    private SurfaceTexture mSurfaceTexture, mCameraSurfaceTexture;
    private int mCameraTextureId;
    private int mHeight;
    private int mWidth;

    private EGLDisplay eglDisplay;
    private EGLSurface eglSurface;
    private EGLContext eglContext;
    public EGL10 egl10;

    private Thread mRenderThread;

    private int mSelectedFilterId = 0;
    private CameraFilter mSelectedCameraFilter;
    private SparseArray<CameraFilter> mCameraFilterList = new SparseArray<>();

    CircularEncoder mCircularEncoder;
    private static int VIDEO_WIDTH = 1080;  // dimensions for 720p video
    private static int VIDEO_HEIGHT = 1920;
    private static final int VIDEO_BIT_RATE = 6000000;
    private static final int DESIRED_PREVIEW_FPS = 15;
    private int mCameraPreviewThousandFps;
    private MainHandler mHandler;

    private boolean isRecording = false;


    // Instance of CameraRenderer
    public CameraRenderer(Context context) {
        this.mContext = context;
    }

    public void startEncoding(){

        isRecording = true;

    }

    public void stopEncoding(){

        isRecording = false;
        mCircularEncoder.saveVideo(createOutputFile());

    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {

        if (mRenderThread != null && mRenderThread.isAlive()) {
            mRenderThread.interrupt();
        }
        mRenderThread = new Thread(this);

        mSurfaceTexture = surfaceTexture;
        mHeight = -height;
        mWidth = -width;

        openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS);

        mCamera.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] bytes, Camera camera) {
                mCircularEncoder.frameAvailableSoon();

            }
        });

        mHandler = new MainHandler(this);

        mRenderThread.start();

    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {

        mHeight = -height;
        mWidth = -width;

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {

        mCircularEncoder.shutdown();

        releaseRenderer();

        releasePreviewEGL();

        releaseCamera();

        return true;

    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {/* EMPTY */}

    @Override
    public void run() {

        try {
            mCircularEncoder = new CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BIT_RATE, mCameraPreviewThousandFps / 1000, 20, mHandler);
        } catch (IOException e) { e.printStackTrace(); }

        initGL(mCircularEncoder.getInputSurface());

        loadAllFilters();

        if(mCamera != null) startCameraPreview();

        // Render loop
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (mWidth < 0 && mWidth < 0) GLES20.glViewport(0, 0, mWidth = -mWidth, mHeight = -mHeight);

                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                // Update the camera preview texture
                synchronized (this) {
                    mCameraSurfaceTexture.updateTexImage();
                }

                // Draw camera preview
                mSelectedCameraFilter.draw(mCameraTextureId, mWidth, mHeight);

                // Flush
                GLES20.glFlush();
                egl10.eglSwapBuffers(eglDisplay, eglSurface);

                Thread.sleep(DRAW_INTERVAL);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        mCameraSurfaceTexture.release();
        GLES20.glDeleteTextures(1, new int[]{mCameraTextureId}, 0);

    }

    private void loadAllFilters(){

        // Setup camera filters map
        mCameraFilterList.append(0, new ChromaticAberrationFilter(mContext));
        setSelectedFilter(mSelectedFilterId);

    }

    private void releaseRenderer(){

        if (mRenderThread != null && mRenderThread.isAlive()) {
            mRenderThread.interrupt();
        }
        CameraFilter.release();

    }

                                 // Camera related defined methods

    private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {

        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open(getBackFacingCameraID());    // opens first back-facing camera
        }
        if (mCamera == null) { throw new RuntimeException("Unable to open camera"); }

        Camera.Parameters parms = mCamera.getParameters();

        setPreviewSize(parms, desiredWidth, desiredHeight);
        // Try to set the frame rate to a constant value.
        mCameraPreviewThousandFps = chooseFixedPreviewFps(parms, desiredFps * 1000);
        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        mCamera.setParameters(parms);

        Camera.Size cameraPreviewSize = parms.getPreviewSize();
        Log.i(TAG, "Camera config: " + cameraPreviewSize.width + "x" + cameraPreviewSize.height + " @" + (mCameraPreviewThousandFps / 1000.0f) + "fps");

        Display display = ((WindowManager)mContext.getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        if(display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
        } else if(display.getRotation() == Surface.ROTATION_270) {
            mCamera.setDisplayOrientation(180);
        }

        Toast.makeText(mContext, "Initialized camera", Toast.LENGTH_SHORT).show();

    }
    // Get fixed FPS
    public static int chooseFixedPreviewFps(Camera.Parameters parms, int desiredThousandFps) {
        List<int[]> supported = parms.getSupportedPreviewFpsRange();

        for (int[] entry : supported) {
            //Log.d(TAG, "entry: " + entry[0] + " - " + entry[1]);
            if ((entry[0] == entry[1]) && (entry[0] == desiredThousandFps)) {
                parms.setPreviewFpsRange(entry[0], entry[1]);
                return entry[0];
            }
        }

        int[] tmp = new int[2];
        parms.getPreviewFpsRange(tmp);
        int guess;
        if (tmp[0] == tmp[1]) {
            guess = tmp[0];
        } else {
            guess = tmp[1] / 2;     // shrug
        }

        Log.d(TAG, "Couldn't find match for " + desiredThousandFps + ", using " + guess);
        return guess;
    }
    public static void setPreviewSize(Camera.Parameters parms, int width, int height) {
        // We should make sure that the requested MPEG size is less than the preferred
        // size, and has the same aspect ratio.
        Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
        if (ppsfv != null) {
            Log.d(TAG, "Camera preferred preview size for video is " + ppsfv.width + "x" + ppsfv.height);
        }
        for (Camera.Size size : parms.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                parms.setPreviewSize(width, height);
                return;
            }
        }

        Log.w(TAG, "Unable to set preview size to " + width + "x" + height);
        if (ppsfv != null) {
            parms.setPreviewSize(ppsfv.width, ppsfv.height);
        }
        // else use whatever the default size is
    }

    // Camera preview init
    private void startCameraPreview(){

        // Create texture for camera preview
        mCameraTextureId = MyGLUtils.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        mCameraSurfaceTexture = new SurfaceTexture(mCameraTextureId);

        // Start camera preview
        try {
            mCamera.setPreviewTexture(mCameraSurfaceTexture);
            mCamera.startPreview();
        } catch (IOException ioe) {}

    }
    // Get Camera orientation
    private void setCameraOrientation(){

        Display display = ((WindowManager)mContext.getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        if(display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
        } else if(display.getRotation() == Surface.ROTATION_270) {
            mCamera.setDisplayOrientation(180);
        } else {
        }


    }
    // Get front camera id
    private int getFrontFacingCameraID() {
        int cameraId = -1;
        // Search for the front facing camera
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                cameraId = i;
                break;
            }
        }
        return cameraId;
    }
    // Get back camera id
    private int getBackFacingCameraID() {
        int cameraId = -1;
        //Search for the back facing camera
        //get the number of cameras
        int numberOfCameras = Camera.getNumberOfCameras();
        //for every camera check
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                cameraId = i;
                break;
            }
        }
        return cameraId;
    }

    private void releaseCamera(){

        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

    }

                            // OpenGL methods

    private void initGL(Surface texture) {
        egl10 = (EGL10) EGLContext.getEGL();

        eglDisplay = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

        if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed " + android.opengl.GLUtils.getEGLErrorString(egl10.eglGetError()));
        }

        int[] version = new int[2];
        if (!egl10.eglInitialize(eglDisplay, version)) {
            throw new RuntimeException("eglInitialize failed " + android.opengl.GLUtils.getEGLErrorString(egl10.eglGetError()));
        }

        int[] configsCount = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        int[] configSpec = {
                EGL10.EGL_RENDERABLE_TYPE,
                EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 0,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_NONE
        };

        EGLConfig eglConfig = null;
        if (!egl10.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount)) {
            throw new IllegalArgumentException("eglChooseConfig failed " + android.opengl.GLUtils.getEGLErrorString(egl10.eglGetError()));
        } else if (configsCount[0] > 0) {
            eglConfig = configs[0];
        }
        if (eglConfig == null) {
            throw new RuntimeException("eglConfig not initialized");
        }

        int[] attrib_list = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};

        eglContext = egl10.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);
        eglSurface = egl10.eglCreateWindowSurface(eglDisplay, eglConfig, texture, null);

        if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
            int error = egl10.eglGetError();
            if (error == EGL10.EGL_BAD_NATIVE_WINDOW) {
                Log.e(TAG, "eglCreateWindowSurface returned EGL10.EGL_BAD_NATIVE_WINDOW");
                return;
            }
            throw new RuntimeException("eglCreateWindowSurface failed " +
                    android.opengl.GLUtils.getEGLErrorString(error));
        }

        if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed " +
                    android.opengl.GLUtils.getEGLErrorString(egl10.eglGetError()));
        }
    }

    public void releasePreviewEGL() {
        if (eglDisplay != egl10.EGL_NO_DISPLAY) {
            // Android is unusual in that it uses a reference-counted EGLDisplay.  So for
            // every eglInitialize() we need an eglTerminate().
            egl10.eglMakeCurrent(eglDisplay, egl10.EGL_NO_SURFACE, egl10.EGL_NO_SURFACE, egl10.EGL_NO_CONTEXT);
            egl10.eglDestroyContext(eglDisplay, eglContext);
            egl10.eglTerminate(eglDisplay);
        }

        eglDisplay = egl10.EGL_NO_DISPLAY;
        eglContext = egl10.EGL_NO_CONTEXT;
    }

    public void setSelectedFilter(@NonNull int id) {
        mSelectedFilterId = id;
        mSelectedCameraFilter = mCameraFilterList.get(id);
        if (mSelectedCameraFilter != null)
            mSelectedCameraFilter.onAttach();
    }

    private File createOutputFile(){

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyymmddhhmmss");
        String date = dateFormat.format(new Date());
        File outFile = new File(Environment.getExternalStorageDirectory(), "circam_" + date + ".mp4");
        if(outFile.exists()) outFile.delete();
        return outFile;

    }

    private static class MainHandler extends Handler implements CircularEncoder.Callback {
        public static final int MSG_BLINK_TEXT = 0;
        public static final int MSG_FRAME_AVAILABLE = 1;
        public static final int MSG_FILE_SAVE_COMPLETE = 2;
        public static final int MSG_BUFFER_STATUS = 3;

        private WeakReference<CameraRenderer> mWeakActivity;

        public MainHandler(CameraRenderer activity) {
            mWeakActivity = new WeakReference<CameraRenderer>(activity);
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void fileSaveComplete(int status) {
            sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null));
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void bufferStatus(long totalTimeMsec) {
            sendMessage(obtainMessage(MSG_BUFFER_STATUS,
                    (int) (totalTimeMsec >> 32), (int) totalTimeMsec));
        }


        @Override
        public void handleMessage(Message msg) {
            CameraRenderer activity = mWeakActivity.get();
            if (activity == null) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            switch (msg.what) {
                case MSG_BLINK_TEXT: {
                    //TODO Do something
                    break;
                }
                case MSG_FRAME_AVAILABLE: {
                    //TODO Do something
                    break;
                }
                case MSG_FILE_SAVE_COMPLETE: {
                    //TODO Do something
                    break;
                }
                case MSG_BUFFER_STATUS: {
                    //TODO Do something
                    break;
                }
                default:
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }


}
