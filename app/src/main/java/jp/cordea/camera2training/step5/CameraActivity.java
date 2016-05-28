package jp.cordea.camera2training.step5;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
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
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import com.ogaclejapan.rx.binding.tuple.Tuple;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import jp.cordea.camera2training.AspectFitTextureView;
import jp.cordea.camera2training.R;
import rx.Observable;
import rx.Subscription;

/**
 * Step 5
 * インアウトカメラの切り替えを実装する
 */
public class CameraActivity extends AppCompatActivity {

    @BindView(R.id.toolbar)
    Toolbar toolbar;

    @BindView(R.id.texture_view)
    AspectFitTextureView textureView;

    @BindView(R.id.switch_button)
    FloatingActionButton switchButton;

    @BindView(R.id.shot_button)
    FloatingActionButton shotButton;

    private static final String THREAD_NAME = "CameraBackgroundThread";

    private static final int PERMISSION_REQUEST_CODE = 100;

    private static final int CAMERA_CONNECTION_SEMAPHORE_ACQUIRE_TIMEOUT = 2500;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private StateType state = StateType.STATE_PREVIEW;

    private CameraDevice cameraDevice;

    private CameraCaptureSession captureSession;

    private ImageReader imageReader;

    private String cameraId;

    private Handler backgroundHandler;

    private Subscription subscription;

    private CaptureRequest.Builder captureRequestBuilder;

    private CaptureRequest captureRequest;

    private HandlerThread backgroundThread;

    private int sensorOrientation = 0;

    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;

    private Semaphore cameraConnectionSemaphore = new Semaphore(1);

    private CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult result) {
                    switch (state) {
                        case STATE_WAITING_LOCK:
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                captureStillPicture();
                                return;
                            }
                            switch (afState) {
                                case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                                case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                                case CaptureResult.CONTROL_AF_STATE_INACTIVE:
                                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                        state = StateType.STATE_PICTURE_TAKEN;
                                        captureStillPicture();
                                    } else {
                                        startPrecaptureMeteringSequence();
                                    }
                                    break;
                            }
                            break;
                        case STATE_WAITING_PRECAPTURE:
                            afState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (afState == null ||
                                    afState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                                    afState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                state = StateType.STATE_WAITING_NON_PRECAPTURE;
                            }
                            break;
                        case STATE_WAITING_NON_PRECAPTURE:
                            afState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (afState == null || afState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                state = StateType.STATE_PICTURE_TAKEN;
                                captureStillPicture();
                            }
                            break;
                    }
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                    super.onCaptureProgressed(session, request, partialResult);
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    process(result);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_step5);

        ButterKnife.bind(this);
        setSupportActionBar(toolbar);

        shotButton.setOnClickListener(view -> takePicture());
        switchButton.setOnClickListener(view -> {
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT;
            } else {
                lensFacing = CameraCharacteristics.LENS_FACING_BACK;
            }
            closeCamera();
            openCamera();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                }
            });
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 1 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            }
        }
    }

    private void openCamera() {
        final Activity activity = this;
        if (subscription != null && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
        subscription = getCameraSetupObservable()
                .filter(size -> size != null)
                .subscribe(size -> {
                    if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ||
                                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
                        } else {
                            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
                        }
                        return;
                    }

                    try {
                        if (!cameraConnectionSemaphore.tryAcquire(CAMERA_CONNECTION_SEMAPHORE_ACQUIRE_TIMEOUT, TimeUnit.MILLISECONDS)) {
                            return;
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        return;
                    }

                    CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

                    try {
                        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                            @Override
                            public void onOpened(@NonNull CameraDevice cd) {
                                cameraConnectionSemaphore.release();
                                cameraDevice = cd;
                                createPreviewSession(size);
                            }

                            @Override
                            public void onDisconnected(@NonNull CameraDevice cd) {
                                cameraConnectionSemaphore.release();
                                cd.close();
                            }

                            @Override
                            public void onError(@NonNull CameraDevice cd, int i) {
                                cameraConnectionSemaphore.release();
                                cd.close();
                            }
                        }, backgroundHandler);

                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                });
    }

    private Observable<Size> getCameraSetupObservable() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String[] ids;
        try {
            ids = manager.getCameraIdList();
        } catch (CameraAccessException e) {
            return Observable.just(null);
        }

        return Observable
                .from(ids)
                .flatMap(s -> {
                    try {
                        return Observable
                                .just(manager.getCameraCharacteristics(s))
                                .filter(cameraCharacteristics -> cameraCharacteristics != null)
                                .filter(cameraCharacteristics -> {
                                    Integer integer = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                                    int ignore = CameraCharacteristics.LENS_FACING_BACK;
                                    if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                                        ignore = CameraCharacteristics.LENS_FACING_FRONT;
                                    }
                                    return integer == null || integer != ignore;
                                })
                                .map(cc -> {
                                    Integer orientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
                                    sensorOrientation = orientation == null ? 0 : orientation;
                                    return cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                                })
                                .filter(map -> map != null)
                                .map(map -> map.getOutputSizes(ImageFormat.JPEG))
                                .doOnNext(notification -> cameraId = s);
                    } catch (CameraAccessException e) {
                        return Observable.just(null);
                    }
                })
                .filter(os -> os != null)
                .map(original -> {
                    final int width = textureView.getWidth();
                    final int height = textureView.getHeight();

                    int rotatedWidth = width;
                    int rotatedHeight = height;
                    if (isNeedSwap(sensorOrientation)) {
                        rotatedWidth = height;
                        rotatedHeight = width;
                    }

                    return Tuple.create(original, new int[]{rotatedWidth, rotatedHeight});
                })
                .flatMap(tuple -> {
                    Size[] original = tuple.item1;
                    int[] rotatedSize = tuple.item2;
                    return Observable
                            .from(original)
                            .filter(size -> (rotatedSize[0] <= size.getWidth() && rotatedSize[1] <= size.getHeight()))
                            .toList()
                            .map(sizes1 -> {
                                if (sizes1.size() > 0) {
                                    return Collections.min(sizes1, (size, t1) ->
                                            (size.getHeight() * size.getWidth()) - (t1.getHeight() - t1.getWidth()));
                                } else {
                                    return Collections.max(Arrays.asList(original), (size, t1) ->
                                            (size.getHeight() * size.getWidth()) - (t1.getHeight() - t1.getWidth()));
                                }
                            });
                })
                .doOnNext(size -> {
                    textureView.setAspect((float)size.getWidth() / (float)size.getHeight());

                    imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
                    imageReader.setOnImageAvailableListener(ir ->
                            backgroundHandler.post(new ImageSaver(ir.acquireNextImage(), getImageFile())), backgroundHandler);
                });
    }

    private File getImageFile() {
        final String path = Environment.getExternalStorageDirectory().getPath();
        return new File(path, System.currentTimeMillis() + ".jpg");
    }

    private boolean isNeedSwap(int sensorRotation) {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();

        boolean swap = false;
        switch (rotation) {
            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                swap = (sensorRotation == 90 || sensorRotation == 270);
                break;
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                swap = (sensorRotation == 0 || sensorRotation == 180);
                break;
        }
        return swap;
    }

    private void createPreviewSession(Size size) {
        SurfaceTexture texture = textureView.getSurfaceTexture();

        texture.setDefaultBufferSize(size.getWidth(), size.getHeight());

        Surface surface = new Surface(texture);

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            return;
        }
        captureRequestBuilder.addTarget(surface);

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(surface);
        surfaces.add(imageReader.getSurface());

        try {
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                    captureRequest = captureRequestBuilder.build();
                    try {
                        captureSession.setRepeatingRequest(captureRequest, captureCallback, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread(THREAD_NAME);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();

            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try {
            cameraConnectionSemaphore.acquire();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (subscription != null && !subscription.isUnsubscribed()) {
                subscription.unsubscribe();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            cameraConnectionSemaphore.release();
        }
    }

    private void takePicture() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
        state = StateType.STATE_WAITING_LOCK;
        try {
            captureSession.capture(captureRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void unlockFocus() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
        try {
            captureSession.capture(captureRequestBuilder.build(), captureCallback, backgroundHandler);
            state = StateType.STATE_PREVIEW;
            captureSession.setRepeatingRequest(captureRequest, captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPrecaptureMeteringSequence() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
        state = StateType.STATE_WAITING_PRECAPTURE;
        try {
            captureSession.capture(captureRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private int getOrientation(int rotation) {
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    private void captureStillPicture() {
        CaptureRequest.Builder builder;
        try {
            builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return;
        }
        builder.addTarget(imageReader.getSurface());

        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(getWindowManager().getDefaultDisplay().getRotation()));

        try {
            captureSession.stopRepeating();
            captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    unlockFocus();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
}
