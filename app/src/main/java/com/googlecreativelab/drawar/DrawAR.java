/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecreativelab.drawar;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import com.flask.colorpicker.ColorPickerView;
import com.flask.colorpicker.OnColorChangedListener;
import com.flask.colorpicker.OnColorSelectedListener;
import com.flask.colorpicker.builder.ColorPickerClickListener;
import com.flask.colorpicker.builder.ColorPickerDialogBuilder;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.googlecreativelab.drawar.rendering.BackgroundRenderer;
import com.googlecreativelab.drawar.rendering.LineShaderRenderer;
import com.googlecreativelab.drawar.rendering.LineUtils;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;


/**
 * This is a complex example that shows how to create an augmented reality (AR) application using
 * the ARCore API.
 */

public class DrawAR extends AppCompatActivity implements GLSurfaceView.Renderer, GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener{
    private static final String TAG = DrawAR.class.getSimpleName();

    private GLSurfaceView mSurfaceView;

    private Session mSession;
    private BackgroundRenderer mBackgroundRenderer = new BackgroundRenderer();
    private LineShaderRenderer mLineShaderRenderer = new LineShaderRenderer();
    private Frame mFrame;

    private float[] projmtx = new float[16];
    private float[] viewmtx = new float[16];
    private float[] mZeroMatrix = new float[16];

    private boolean mPaused = false;

    private float mScreenWidth = 0;
    private float mScreenHeight = 0;

    private BiquadFilter biquadFilter;
    private Vector3f mLastPoint;
    private AtomicReference<Vector2f> lastTouch = new AtomicReference<>();

    private GestureDetectorCompat mDetector;

    private LinearLayout mSettingsUI;
    private LinearLayout mButtonBar;

    private SeekBar mLineWidthBar;
    private SeekBar mLineDistanceScaleBar;
    private SeekBar mSmoothingBar;
    private RelativeLayout mLineColorView;
    private ImageView mLineColorImage;

    private float mLineWidthMax = 0.33f;
    private float mDistanceScale = 0.0f;
    private float mLineSmoothing = 0.1f;

    private float[] mLastFramePosition;

    private AtomicBoolean bIsTracking = new AtomicBoolean(true);
    private AtomicBoolean bReCenterView = new AtomicBoolean(false);
    private AtomicBoolean bTouchDown = new AtomicBoolean(false);
    private AtomicBoolean bClearDrawing = new AtomicBoolean(false);
    private AtomicBoolean bLineParameters = new AtomicBoolean(false);
    private AtomicBoolean bUndo = new AtomicBoolean(false);
    private AtomicBoolean bNewStroke = new AtomicBoolean(false);

    private ArrayList<ArrayList<Vector3f>> mStrokes;

    private DisplayRotationHelper mDisplayRotationHelper;

    private boolean bInstallRequested;

    private TrackingState mState;
    /**
     * Setup the app when main activity is created
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);

        setContentView(R.layout.activity_main);

        // GLSurfaceView控件
        mSurfaceView = findViewById(R.id.surfaceview);
        mSettingsUI = findViewById(R.id.strokeUI);
        mButtonBar = findViewById(R.id.button_bar);

        // Settings seek bars
        mLineDistanceScaleBar = findViewById(R.id.distanceScale);
        mLineWidthBar = findViewById(R.id.lineWidth);
        mSmoothingBar = findViewById(R.id.smoothingSeekBar);
        mLineColorView=findViewById(R.id.lineColorView);
        mLineColorImage=findViewById(R.id.lineColorImage);

        // 读取上一次设置
        mLineDistanceScaleBar.setProgress(sharedPref.getInt("mLineDistanceScale", 1));
        mLineWidthBar.setProgress(sharedPref.getInt("mLineWidth", 10));
        mSmoothingBar.setProgress(sharedPref.getInt("mSmoothing", 50));
        int defaultColor=sharedPref.getInt("mLineColor",0xffffffff);
        Vector3f curColor = new Vector3f(Color.red(defaultColor)/255f,Color.green(defaultColor)/255f,Color.blue(defaultColor)/255f);
        AppSettings.setColor(curColor);
        mLineColorImage.setColorFilter(defaultColor);

        mDistanceScale = LineUtils.map((float) mLineDistanceScaleBar.getProgress(), 0, 100, 1, 200, true);
        mLineWidthMax = LineUtils.map((float) mLineWidthBar.getProgress(), 0f, 100f, 0.1f, 5f, true);
        mLineSmoothing = LineUtils.map((float) mSmoothingBar.getProgress(), 0, 100, 0.01f, 0.2f, true);

        SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
            /**
             * Listen for seekbar changes, and update the settings
             */
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                SharedPreferences.Editor editor = sharedPref.edit();

                if (seekBar == mLineDistanceScaleBar) {
                    editor.putInt("mLineDistanceScale", progress);
                    mDistanceScale = LineUtils.map((float) progress, 0f, 100f, 1f, 200f, true);
                } else if (seekBar == mLineWidthBar) {
                    editor.putInt("mLineWidth", progress);
                    mLineWidthMax = LineUtils.map((float) progress, 0f, 100f, 0.1f, 5f, true);
                } else if (seekBar == mSmoothingBar) {
                    editor.putInt("mSmoothing", progress);
                    mLineSmoothing = LineUtils.map((float) progress, 0, 100, 0.01f, 0.2f, true);
                }
                mLineShaderRenderer.bNeedsUpdate.set(true);

                editor.apply();

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };

        // 设置事件监听
        mLineDistanceScaleBar.setOnSeekBarChangeListener(seekBarChangeListener);
        mLineWidthBar.setOnSeekBarChangeListener(seekBarChangeListener);
        mSmoothingBar.setOnSeekBarChangeListener(seekBarChangeListener);
        mLineColorView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ColorPickerDialogBuilder
                        .with(DrawAR.this)
                        .setTitle("Choose color")
                        .initialColor(0xffffffff)
                        .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
                        .density(12)
                        .setOnColorChangedListener(new OnColorChangedListener() {
                            @Override
                            public void onColorChanged(int selectedColor) {
                                // Handle on color change
                                Log.d("ColorPicker", "onColorChanged: 0x" + Integer.toHexString(selectedColor));
                            }
                        })
                        .setOnColorSelectedListener(new OnColorSelectedListener() {
                            @Override
                            public void onColorSelected(int selectedColor) {
                            }
                        })
                        .setPositiveButton("ok", new ColorPickerClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int selectedColor, Integer[] allColors) {
                                Vector3f curColor = new Vector3f(Color.red(selectedColor)/255f,Color.green(selectedColor)/255f,Color.blue(selectedColor)/255f);
                                AppSettings.setColor(curColor);
                                mLineShaderRenderer.bNeedsUpdate.set(true);
                                mSettingsUI.setVisibility(View.GONE);
                                mLineColorImage.setColorFilter(selectedColor);

                                SharedPreferences.Editor editor = sharedPref.edit();
                                editor.putInt("mLineColor", selectedColor);
                                editor.apply();
                            }
                        })
                        .setNegativeButton("cancel", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                            }
                        })
                        .showColorEdit(false)
                        .setColorEditTextColor(ContextCompat.getColor(DrawAR.this, android.R.color.holo_blue_bright))
                        .build()
                        .show();

            }
        });

        // Hide the settings ui
        mSettingsUI.setVisibility(View.GONE);

        mDisplayRotationHelper = new DisplayRotationHelper(/*context=*/ this);
        // Reset the zero matrix
        Matrix.setIdentityM(mZeroMatrix, 0);

        mLastPoint = new Vector3f(0, 0, 0);

        bInstallRequested = false;

        // Set up renderer.
        /*
        配置GLSurfaceView:
        1 在pause状态下，保留EGL上下文
        2 OpenGL ES 版本选择 2.0 版本
        3 绘制表面选择RGBA分别为8位，深度16位，模板0位的配置
        4 设置自身为渲染器，即处理逻辑在这个类里实现
        4 渲染模式设为持续渲染，即一帧渲染完，马上开始下一帧的渲染
        */
        mSurfaceView.setPreserveEGLContextOnPause(true);
        mSurfaceView.setEGLContextClientVersion(2);
        mSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        mSurfaceView.setRenderer(this);
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // Setup touch detector
        mDetector = new GestureDetectorCompat(this, this);
        mDetector.setOnDoubleTapListener(this);

        // 线条集合初始化为空
        mStrokes = new ArrayList<>();
    }


    /**
     * addStroke adds a new stroke to the scene
     *
     * @param touchPoint a 2D point in screen space and is projected into 3D world space
     */
    private void addStroke(Vector2f touchPoint) {
        Vector3f newPoint = LineUtils.GetWorldCoords(touchPoint, mScreenWidth, mScreenHeight, projmtx, viewmtx);
        addStroke(newPoint);
    }


    /**
     * addPoint adds a point to the current stroke
     *
     * @param touchPoint a 2D point in screen space and is projected into 3D world space
     */
    private void addPoint(Vector2f touchPoint) {
        Vector3f newPoint = LineUtils.GetWorldCoords(touchPoint, mScreenWidth, mScreenHeight, projmtx, viewmtx);
        addPoint(newPoint);
    }


    /**
     * addStroke creates a new stroke
     *
     * @param newPoint a 3D point in world space
     */
    private void addStroke(Vector3f newPoint) {
        biquadFilter = new BiquadFilter(mLineSmoothing);
        for (int i = 0; i < 1500; i++) {
            biquadFilter.update(newPoint);
        }
        Vector3f p = biquadFilter.update(newPoint);
        mLastPoint = new Vector3f(p);
        mStrokes.add(new ArrayList<Vector3f>());
        mStrokes.get(mStrokes.size() - 1).add(mLastPoint);
    }

    /**
     * addPoint adds a point to the current stroke
     *
     * @param newPoint a 3D point in world space
     */
    private void addPoint(Vector3f newPoint) {
        if (LineUtils.distanceCheck(newPoint, mLastPoint)) {
            Vector3f p = biquadFilter.update(newPoint);
            mLastPoint = new Vector3f(p);
            mStrokes.get(mStrokes.size() - 1).add(mLastPoint);
        }
    }


    /**
     * onResume part of the Android Activity Lifecycle
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (mSession == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !bInstallRequested)) {
                    case INSTALL_REQUESTED:
                        bInstallRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!PermissionHelper.hasCameraPermission(this)) {
                    PermissionHelper.requestCameraPermission(this);
                    return;
                }

                mSession = new Session(/* context= */ this);
            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (Exception e) {
                message = "This device does not support AR";
                exception = e;
            }

            if (message != null) {
                Log.e(TAG, "Exception creating session", exception);
                return;
            }

            // Create default config and check if supported.
            Config config = new Config(mSession);
            if (!mSession.isSupported(config)) {
                Log.e(TAG, "Exception creating session Device Does Not Support ARCore", exception);
            }
            mSession.configure(config);
        }
        // Note that order matters - see the note in onPause(), the reverse applies here.
        mSession.resume();
        mSurfaceView.onResume();
        mDisplayRotationHelper.onResume();
        mPaused = false;
    }

    /**
     * onPause part of the Android Activity Lifecycle
     */
    @Override
    public void onPause() {
        super.onPause();
        // Note that the order matters - GLSurfaceView is paused first so that it does not try
        // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
        // still call mSession.update() and get a SessionPausedException.

        if (mSession != null) {
            mDisplayRotationHelper.onPause();
            mSurfaceView.onPause();
            mSession.pause();
        }

        mPaused = false;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        mScreenHeight = displayMetrics.heightPixels;
        mScreenWidth = displayMetrics.widthPixels;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Standard Android full-screen functionality.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * Create renderers after the Surface is Created and on the GL Thread
     */
    // 要实现在GLSurfaceView上绘制内容，需要实现GLSurfaceView.Renderer三个接口: onSurfaceCreated, onSurfaceChanged andonDrawFrame
    // 在可绘制表面创建或重新创建的时候被调用
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {

        if (mSession == null) {
            return;
        }

        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Create the texture and pass it to ARCore session to be filled during update().
        mBackgroundRenderer.createOnGlThread(/*context=*/this);

        try {

            mSession.setCameraTextureName(mBackgroundRenderer.getTextureId());
            mLineShaderRenderer.createOnGlThread(this);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // 在可绘制表面发生变化的时候被调用
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        mDisplayRotationHelper.onSurfaceChanged(width, height);
        mScreenWidth = width;
        mScreenHeight = height;
    }


    /**
     * update() is executed on the GL Thread.
     * The method handles all operations that need to take place before drawing to the screen.
     * The method :
     * extracts the current projection matrix and view matrix from the AR Pose
     * handles adding stroke and points to the data collections
     * updates the ZeroMatrix and performs the matrix multiplication needed to re-center the drawing
     * updates the Line Renderer with the current strokes, color, distance scale, line width etc
     */
    private void update() {

        if (mSession == null) {
            return;
        }

        mDisplayRotationHelper.updateSessionIfNeeded(mSession);

        try {

            mSession.setCameraTextureName(mBackgroundRenderer.getTextureId());

            mFrame = mSession.update();
            Camera camera = mFrame.getCamera();

            mState = camera.getTrackingState();

            // Update tracking states
            if (mState == TrackingState.TRACKING && !bIsTracking.get()) {
                bIsTracking.set(true);
            } else if (mState== TrackingState.STOPPED && bIsTracking.get()) {
                bIsTracking.set(false);
                bTouchDown.set(false);
            }

            // Get projection matrix.
            camera.getProjectionMatrix(projmtx, 0, AppSettings.getNearClip(), AppSettings.getFarClip());
            camera.getViewMatrix(viewmtx, 0);

            float[] position = new float[3];
            camera.getPose().getTranslation(position, 0);

            // Check if camera has moved much, if thats the case, stop touchDown events
            // (stop drawing lines abruptly through the air)
            if (mLastFramePosition != null) {
                Vector3f distance = new Vector3f(position[0], position[1], position[2]);
                distance.sub(new Vector3f(mLastFramePosition[0], mLastFramePosition[1], mLastFramePosition[2]));

                if (distance.length() > 0.15) {
                    bTouchDown.set(false);
                }
            }
            mLastFramePosition = position;

            // Multiply the zero matrix
            Matrix.multiplyMM(viewmtx, 0, viewmtx, 0, mZeroMatrix, 0);


            if (bNewStroke.get()) {
                bNewStroke.set(false);
                addStroke(lastTouch.get());
                mLineShaderRenderer.bNeedsUpdate.set(true);
            } else if (bTouchDown.get()) {
                addPoint(lastTouch.get());
                mLineShaderRenderer.bNeedsUpdate.set(true);
            }

            if (bReCenterView.get()) {
                bReCenterView.set(false);
                mZeroMatrix = getCalibrationMatrix();
            }

            // 删除所有线段，并设置渲染器需要更新为true
            if (bClearDrawing.get()) {
                bClearDrawing.set(false);
                clearDrawing();
                mLineShaderRenderer.bNeedsUpdate.set(true);
            }

            // 撤销操作，删除上一段线，并设置渲染器需要更新为true
            if (bUndo.get()) {
                bUndo.set(false);
                if (mStrokes.size() > 0) {
                    mStrokes.remove(mStrokes.size() - 1);
                    mLineShaderRenderer.bNeedsUpdate.set(true);
                }
            }
            mLineShaderRenderer.setDrawDebug(bLineParameters.get());

            // 更新
            if (mLineShaderRenderer.bNeedsUpdate.get()) {
                mLineShaderRenderer.setColor(AppSettings.getColor());
                mLineShaderRenderer.mDrawDistance = AppSettings.getStrokeDrawDistance();
                mLineShaderRenderer.setDistanceScale(mDistanceScale);
                mLineShaderRenderer.setLineWidth(mLineWidthMax);
                mLineShaderRenderer.clear();
                mLineShaderRenderer.updateStrokes(mStrokes);
                mLineShaderRenderer.upload();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * GL Thread Loop
     * clears the Color Buffer and Depth Buffer, draws the current texture from the camera
     * and draws the Line Renderer if ARCore is tracking the world around it
     */
    // 在绘制的时候调用。每绘制一次，就会调用一次，即每一帧触发一次。这里是主要的绘制逻辑。
    @Override
    public void onDrawFrame(GL10 gl) {
        if (mPaused) return;

        update();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (mFrame == null) {
            return;
        }

        // Draw background.
        // 绘制摄像头采到的数据
        mBackgroundRenderer.draw(mFrame);

        // Draw Lines
        // 画所有线
        if (mFrame.getCamera().getTrackingState() == TrackingState.TRACKING) {
            mLineShaderRenderer.draw(viewmtx, projmtx, mScreenWidth, mScreenHeight, AppSettings.getNearClip(), AppSettings.getFarClip());
        }
    }


    /**
     * Get a matrix usable for zero calibration (only position and compass direction)
     */
    public float[] getCalibrationMatrix() {
        float[] t = new float[3];
        float[] m = new float[16];

        mFrame.getCamera().getPose().getTranslation(t, 0);
        float[] z = mFrame.getCamera().getPose().getZAxis();
        Vector3f zAxis = new Vector3f(z[0], z[1], z[2]);
        zAxis.y = 0;
        zAxis.normalize();

        double rotate = Math.atan2(zAxis.x, zAxis.z);

        Matrix.setIdentityM(m, 0);
        Matrix.translateM(m, 0, t[0], t[1], t[2]);
        Matrix.rotateM(m, 0, (float) Math.toDegrees(rotate), 0, 1, 0);
        return m;
    }


    /**
     * Clears the Datacollection of Strokes and sets the Line Renderer to clear and update itself
     * Designed to be executed on the GL Thread
     */
    public void clearDrawing() {
        mStrokes.clear();
        mLineShaderRenderer.clear();
    }


    /**
     * onClickUndo handles the touch input on the GUI and sets the AtomicBoolean bUndo to be true
     * the actual undo functionality is executed in the GL Thread
     */
    public void onClickUndo(View button) {
        bUndo.set(true);
    }

    /**
     * onClickLineDebug toggles the Line Renderer's Debug View on and off. The line renderer will
     * highlight the lines on the same depth plane to allow users to draw things more coherently
     */
    public void onClickLineDebug(View button) {
        bLineParameters.set(!bLineParameters.get());
    }


    /**
     * onClickSettings toggles showing and hiding the Line Width, Smoothing, and Debug View toggle
     */
    public void onClickSettings(View button) {
        ImageButton settingsButton = findViewById(R.id.settingsButton);

        if (mSettingsUI.getVisibility() == View.GONE) {
            mSettingsUI.setVisibility(View.VISIBLE);
            mLineDistanceScaleBar = findViewById(R.id.distanceScale);
            mLineWidthBar = findViewById(R.id.lineWidth);

            settingsButton.setColorFilter(getResources().getColor(R.color.active));
        } else {
            mSettingsUI.setVisibility(View.GONE);
            settingsButton.setColorFilter(getResources().getColor(R.color.gray));
        }
    }

    /**
     * onClickClear handle showing an AlertDialog to clear the drawing
     */
    public void onClickClear(View button) {

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setMessage("Sure you want to clear?");

        // Set up the buttons
        builder.setPositiveButton("Clear ", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                bClearDrawing.set(true);
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }


    /**
     * onClickRecenter handles the touch input on the GUI and sets the AtomicBoolean bReCEnterView to be true
     * the actual recenter functionality is executed on the GL Thread
     */
    public void onClickRecenter(View button) {
        bReCenterView.set(true);
    }

    // ------- Touch events

    /**
     * onTouchEvent handles saving the lastTouch screen position and setting bTouchDown and bNewStroke
     * AtomicBooleans to trigger addPoint and addStroke on the GL Thread to be called
     */
    @Override
    public boolean onTouchEvent(MotionEvent tap) {
        this.mDetector.onTouchEvent(tap);

        if (tap.getAction() == MotionEvent.ACTION_DOWN ) {
            lastTouch.set(new Vector2f(tap.getX(), tap.getY()));
            bTouchDown.set(true);
            bNewStroke.set(true);
            return true;
        } else if (tap.getAction() == MotionEvent.ACTION_MOVE || tap.getAction() == MotionEvent.ACTION_POINTER_DOWN) {
            lastTouch.set(new Vector2f(tap.getX(), tap.getY()));
            bTouchDown.set(true);
            return true;
        } else if (tap.getAction() == MotionEvent.ACTION_UP || tap.getAction() == MotionEvent.ACTION_CANCEL) {
            bTouchDown.set(false);
            lastTouch.set(new Vector2f(tap.getX(), tap.getY()));
            return true;
        }

        return super.onTouchEvent(tap);
    }


    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        return false;
    }

    /**
     * onDoubleTap shows and hides the Button Bar at the Top of the View
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (mButtonBar.getVisibility() == View.GONE) {
            mButtonBar.setVisibility(View.VISIBLE);
        } else {
            mButtonBar.setVisibility(View.GONE);
        }
        return false;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent tap) {
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

}
