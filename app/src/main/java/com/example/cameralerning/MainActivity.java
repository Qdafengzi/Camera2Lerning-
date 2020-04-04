package com.example.cameralerning;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity ";
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private int mCaptureState = STATE_PREVIEW;

    private TextureView mTextureView;
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.d(TAG, width + " " + height);
            setUpCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            Log.d(TAG, "打开相机成功");
            //如果在录制
            if (isRecording) {
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                startRecord();
                mMediaRecorder.start();

                //开始计时
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.setVisibility(View.VISIBLE);
                mChronometer.start();
            } else {
                //开始预览
                startPreView();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
        }
    };

    private HandlerThread mBackGroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private Size mVideoSize;

    //时候支持闪光灯
    private boolean mFlashSupported = false;

    private Size mImageSize;
    private ImageReader mImageReader;
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireLatestImage()));
        }
    };

    /**
     * 保存图片
     */
    private class ImageSaver implements Runnable {
        private final Image mImage;

        private ImageSaver(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            ByteBuffer byteBuffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(mImageFileAbsolutePathName);
                fileOutputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();

                //刷新文件媒体系统 更新数据库
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    galleryAddPhotoAboveApi28(mImageFileName, mImageFileAbsolutePathName);
                } else {
                    galleryAddPhotoBelowApi28(mImageFileAbsolutePathName);
                }

                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private MediaRecorder mMediaRecorder;
    private int mTotalRotation;

    private CameraCaptureSession mPreviewCaptureSession;
    private CameraCaptureSession.CaptureCallback mPreviewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult captureResult) {
            switch (mCaptureState) {
                case STATE_PREVIEW:
                    // Do nothing
                    break;
                case STATE_WAIT_LOCK:
                    mCaptureState = STATE_PREVIEW;
                    Log.d(TAG, "mCaptureState:" + mCaptureState);
                    Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);

                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) {

                        Integer aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            Toast.makeText(getApplicationContext(), "AE Locked!", Toast.LENGTH_SHORT).show();
                            startStillCaptureRequest();
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            process(result);
        }
    };


    private CameraCaptureSession mRecordCaptureSession;
    private CameraCaptureSession.CaptureCallback mRecordCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult captureResult) {
            switch (mCaptureState) {
                case STATE_PREVIEW:
                    // Do nothing
                    break;
                case STATE_WAIT_LOCK:
                    mCaptureState = STATE_PREVIEW;
                    Log.d(TAG, "mCaptureState:" + mCaptureState);
                    Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) {

                        Integer aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            Toast.makeText(getApplicationContext(), "AE Locked!", Toast.LENGTH_SHORT).show();
                            startStillCaptureRequest();
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            process(result);
        }
    };
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private static SparseIntArray ORITENTATIONS = new SparseIntArray();

    static {
        ORITENTATIONS.append(Surface.ROTATION_0, 0);
        ORITENTATIONS.append(Surface.ROTATION_90, 90);
        ORITENTATIONS.append(Surface.ROTATION_180, 180);
        ORITENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private ImageButton mImageVideoButton;
    private boolean isRecording = false;
    private boolean isTimeLapse = false;
    private ImageButton mImagePictureButton;
    //计时器
    private Chronometer mChronometer;
    private File mVideoFolder;
    private String mVideoFileAbsolutePathName;
    private String mVideoFileName;
    private File mImageFolder;
    private String mImageFileAbsolutePathName;
    private String mImageFileName;


    /**
     * 按照面积比较大小
     */
    private static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size o1, Size o2) {
            return Long.signum((long) o1.getWidth() * o1.getHeight() /
                    (long) o2.getWidth() * o2.getHeight());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createVideoFolder();
        createImageFolder();

        mMediaRecorder = new MediaRecorder();

        mChronometer = findViewById(R.id.chronometer);
        mTextureView = findViewById(R.id.textureView);
        mImageVideoButton = findViewById(R.id.ib_video_btn);
        mImagePictureButton = findViewById(R.id.ib_img_btn);

        mImageVideoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRecording || isTimeLapse) {
                    mChronometer.stop();
                    mChronometer.setVisibility(View.INVISIBLE);

                    isRecording = false;
                    isTimeLapse = false;
                    mImageVideoButton.setImageResource(R.drawable.video_online_btn);
                    //暂停录制
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();

                    //刷新文件媒体系统 更新数据库
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        galleryAddPhotoAboveApi28(mVideoFileName, mVideoFileAbsolutePathName);
                    } else {
                        galleryAddPhotoBelowApi28(mVideoFileAbsolutePathName);
                    }


                    startPreView();

                } else {
                    isRecording = true;
                    mImageVideoButton.setImageResource(R.drawable.video_busy_btn);
                    checkWriteStoragePermission();
                }
            }
        });

        mImagePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "拍照");
                lockFocus();
            }
        });

        mImageVideoButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                isTimeLapse = true;
                mImageVideoButton.setImageResource(R.drawable.video_time_lapse_btn);
                checkWriteStoragePermission();
                return true;
            }
        });


    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            setUpCamera(mTextureView.getWidth(), mTextureView.getHeight());
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        //释放资源
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        //沉浸式状态栏
        if (hasFocus) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    /**
     * 配置相机
     *
     * @param width
     * @param height
     */
    private void setUpCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null) {
                for (String cameraId : cameraManager.getCameraIdList()) {
                    //获取相机属性
                    CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                    try {
                        // 不使用前置摄像头。
                        Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                            continue;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                    mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                    boolean swapRotation = mTotalRotation == 90 || mTotalRotation == 270;
                    int rotationWidth = width;
                    int rotationHeight = height;
                    //如果是横屏的状态 交换 宽高
                    if (swapRotation) {
                        rotationWidth = height;
                        rotationHeight = width;
                    }
                    Log.d(TAG, "rotationWidth :" + rotationWidth + " rotationHeight:" + rotationHeight);
                    if (map == null) {
                        return;
                    }
                    // 检查闪光灯是否支持。
                    Boolean available = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    mFlashSupported = available == null ? false : available;

                    mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotationWidth, rotationHeight);
                    mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder.class), rotationWidth, rotationHeight);

                    mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotationWidth, rotationHeight);
                    mImageReader = ImageReader.newInstance(mImageSize.getWidth(), mImageSize.getHeight(), ImageFormat.JPEG, 1);
                    mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

                    Log.d(TAG, "previewSize:" + mPreviewSize);
                    mCameraId = cameraId;
                    return;
                }
            }
        } catch (NullPointerException | CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 连接相机
     */
    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            return;
        }
        try {
            //如果是大于等于Android 6.0先申请相机权限 和录音权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
                } else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(this, "打开相机", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION_RESULT);
                }
            } else {
                //6.0一下的设备直接打开摄像头
                cameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 开始录制
     */
    private void startRecord() {
        try {
            if (isRecording) {
                setUpMediaRecorder();
            } else {
                setUpTimeLapse();
            }
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recorderSurface = mMediaRecorder.getSurface();
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCaptureRequestBuilder.addTarget(recorderSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface, mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mRecordCaptureSession = session;
                    try {
                        mRecordCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "录制出错：");
                }
            }, null);

        } catch (IOException | CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 发起抓取（捕捉）静态请求
     */
    private void startStillCaptureRequest() {
        try {
            if (isRecording) {
                mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);
            } else {
                mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            }
            mCaptureRequestBuilder.addTarget(mImageReader.getSurface());

            // 使用相同的AE和AF模式作为预览。
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(mCaptureRequestBuilder);

            mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mTotalRotation);
            CameraCaptureSession.CaptureCallback stillCaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                    try {
                        createImageFileName();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            };
            if (isRecording) {
                mRecordCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback, null);
            } else {
                mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 开始预览
     */
    private void startPreView() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);
        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mPreviewCaptureSession = session;
                    // 自动对焦应
                    mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    // 闪光灯
                    setAutoFlash(mCaptureRequestBuilder);

                    try {
                        mPreviewCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(getApplicationContext(), "不能建立相机预览", Toast.LENGTH_SHORT).show();
                }
            }, null);


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 关闭摄像头
     */
    private void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    /**
     * 开启后台线程
     */
    private void startBackgroundThread() {
        mBackGroundHandlerThread = new HandlerThread("camera2ImgVideo");
        mBackGroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackGroundHandlerThread.getLooper());
    }

    /**
     * 关闭后台线程
     */
    private void stopBackgroundThread() {
        mBackGroundHandlerThread.quitSafely();
        try {
            mBackGroundHandlerThread.join();
            mBackGroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 传感器的方向
     *
     * @param cameraCharacteristics
     * @param deviceOrientation
     * @return
     */
    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
        int sensorOrientation = 0;
        try {
            sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            deviceOrientation = ORITENTATIONS.get(deviceOrientation);
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }

    /**
     * 选择最佳尺寸
     *
     * @param choices
     * @param width
     * @param height
     * @return
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        Size bigEnough = null;
        int minAreaDiff = Integer.MAX_VALUE;
        for (Size option : choices) {
            int diff = (width * height) - (option.getWidth() * option.getHeight());
            if (diff >= 0 && diff < minAreaDiff &&
                    option.getWidth() <= width &&
                    option.getHeight() <= height) {
                minAreaDiff = diff;
                bigEnough = option;
            }
        }
        if (bigEnough != null) {
            return bigEnough;
        } else {
            Arrays.sort(choices, new CompareSizeByArea());
            return choices[0];
        }
    }


    /**
     * 创建文件夹
     */
    private void createVideoFolder() {
        File movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        mVideoFolder = new File(movieFile, "camera2");
        if (!mVideoFolder.exists()) {
            mVideoFolder.mkdirs();
        }
    }

    /**
     * 创建视频文件名字
     *
     * @throws IOException
     */
    private void createVideoFileName() throws IOException {
        String timestamp = SimpleDateFormat.getDateInstance().format(new Date());
        String prepend = "video_" + timestamp + "_";
        File videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder);
        mVideoFileAbsolutePathName = videoFile.getAbsolutePath();
        mVideoFileName = videoFile.getName();
        Log.d(TAG, "文件名称：" + mVideoFileAbsolutePathName);
    }

    /**
     * 创建图片文件夹
     */
    private void createImageFolder() {
        File imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mImageFolder = new File(imageFile, "camera2");
        if (!mImageFolder.exists()) {
            mImageFolder.mkdirs();
        }
    }

    /**
     * 创建图片文件名字
     *
     * @throws IOException
     */
    private void createImageFileName() throws IOException {
        String timestamp = SimpleDateFormat.getDateInstance().format(new Date());
        String prepend = "image_" + timestamp + "_";
        File imageFile = File.createTempFile(prepend, ".jpg", mImageFolder);
        mImageFileAbsolutePathName = imageFile.getAbsolutePath();
        mVideoFileName = imageFile.getName();
        Log.d(TAG, "文件名称：" + mImageFileAbsolutePathName);
    }

    /**
     * 检查写入的权限
     */
    private void checkWriteStoragePermission() {
        /*
         * 大于等于Android 6.0才会处理
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //开始录制
                startRecord();
                mMediaRecorder.start();
                //开始计时
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.setVisibility(View.VISIBLE);
                mChronometer.start();
            } else {
                /*
                 * 如果没有权限则申请
                 */
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, "给写入权限", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT);
            }
        } else {
            try {
                createVideoFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }
            startRecord();
            mMediaRecorder.start();
            //开始计时
            mChronometer.setBase(SystemClock.elapsedRealtime());
            mChronometer.setVisibility(View.VISIBLE);
            mChronometer.start();
        }
    }

    /**
     * 多媒体参数配置
     *
     * @throws IOException
     */
    private void setUpMediaRecorder() throws IOException {
        //视频源
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        //音频源
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        //输出格式
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        //输出文件
        mMediaRecorder.setOutputFile(mVideoFileAbsolutePathName);
        //视频编码比特率
        mMediaRecorder.setVideoEncodingBitRate(1000000);
        //帧率
        mMediaRecorder.setVideoFrameRate(30);
        //视频尺寸
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        //视频编码格式 h264
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        //音频编码格式 AAC
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        //方向
        mMediaRecorder.setOrientationHint(mTotalRotation);
        //准备
        mMediaRecorder.prepare();

    }

    /**
     * 流光视频配置
     *
     * @throws IOException
     */
    private void setUpTimeLapse() throws IOException {
        //视频源
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        //时间流逝
        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_TIME_LAPSE_HIGH));
        //输出文件
        mMediaRecorder.setOutputFile(mVideoFileAbsolutePathName);
        //捕捉速率
        mMediaRecorder.setCaptureRate(2);
        //方向
        mMediaRecorder.setOrientationHint(mTotalRotation);
        //准备
        mMediaRecorder.prepare();

    }

    /**
     * 锁定聚焦
     */
    private void lockFocus() {
        //相机对焦
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        //状态修改
        mCaptureState = STATE_WAIT_LOCK;
        try {
            if (isRecording) {
                mRecordCaptureSession.capture(mCaptureRequestBuilder.build(), mRecordCaptureCallback, mBackgroundHandler);
            } else {
                mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 解锁焦点
     *
     * @param request
     */
    private void unlockFocus(CaptureRequest request) {
        try {
            // 重置自动对焦
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(mCaptureRequestBuilder);
            mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler);
            // 将相机恢复正常的预览状态。
            mCaptureState = STATE_PREVIEW;
            // 打开连续取景模式
            mPreviewCaptureSession.setRepeatingRequest(request, mPreviewCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * 闪光灯的配置 自动配置
     *
     * @param requestBuilder
     */
    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    /**
     * 刷新媒体数据库 使照片可以在相册看到
     * @param file
     */
    private void galleryAddPhotoBelowApi28(String file) {
        File f = new File(file);
        Uri contentUri = Uri.fromFile(f);
        // Intent.ACTION_MEDIA_SCANNER_SCAN_FILE is deprecated!
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, contentUri);
        sendBroadcast(mediaScanIntent);
    }

    /**
     * 刷新媒体数据库 使照片可以在相册看到
     * @param fileName
     * @param filePath
     */
    private void galleryAddPhotoAboveApi28(String fileName, final String filePath) {
        OutputStream out = null;
        try {
            ContentValues values = new ContentValues();
            ContentResolver resolver = this.getContentResolver();

            // MediaStore.Images.ImageColumns.DATA is deprecated!
            values.put(MediaStore.Images.ImageColumns.DATA, filePath);
            values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/jpg");
            values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, System.currentTimeMillis() + "");
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                Bitmap bitmap = BitmapFactory.decodeFile(filePath);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                out.flush();
                out.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        //相机和录音权限
        if (requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(), "程序将不能运行没有相机权限允许", Toast.LENGTH_SHORT).show();
            }

            if (grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(), "程序将不能运行没有录音权限允许", Toast.LENGTH_SHORT).show();
            }
            //读写权限
        } else if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                isRecording = true;
                mImageVideoButton.setImageResource(R.drawable.video_busy_btn);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                Toast.makeText(getApplicationContext(), "没有写入权限不能生成文件", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
