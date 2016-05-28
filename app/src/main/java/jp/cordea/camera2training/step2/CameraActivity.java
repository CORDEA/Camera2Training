package jp.cordea.camera2training.step2;

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
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.ogaclejapan.rx.binding.tuple.Tuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import butterknife.BindString;
import butterknife.BindView;
import butterknife.ButterKnife;
import jp.cordea.camera2training.AspectFitTextureView;
import jp.cordea.camera2training.R;
import rx.Observable;
import rx.Subscription;

/**
 * Step 2
 * 端末の向きを確認・調整する
 */
public class CameraActivity extends AppCompatActivity {

    @BindView(R.id.toolbar)
    Toolbar toolbar;

    @BindView(R.id.texture_view)
    AspectFitTextureView textureView;

    @BindString(R.string.title_format_text)
    String titleFormatText;

    @BindString(R.string.step2_text)
    String step2Text;

    private static final String THREAD_NAME = "CameraBackgroundThread";

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    private static final int CAMERA_CONNECTION_SEMAPHORE_ACQUIRE_TIMEOUT = 2500;

    private CameraDevice cameraDevice;

    private CameraCaptureSession cameraCaptureSession;

    private String cameraId;

    private Handler backgroundHandler;

    private HandlerThread backgroundThread;

    private Subscription subscription;

    private Semaphore cameraConnectionSemaphore = new Semaphore(1);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_step2);
        ButterKnife.bind(this);

        toolbar.setTitle(String.format(Locale.getDefault(), titleFormatText, step2Text));
        setSupportActionBar(toolbar);
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
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
                        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                        } else {
                            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
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
                                    return integer == null || integer != CameraCharacteristics.LENS_FACING_FRONT;
                                })
                                .filter(cc -> cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null)
                                .map(cc -> Tuple.create(
                                        cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG),
                                        cc.get(CameraCharacteristics.SENSOR_ORIENTATION)
                                ))
                                .doOnNext(notification -> {
                                    cameraId = s;
                                });
                    } catch (CameraAccessException e) {
                        return Observable.just(null);
                    }
                })
                .filter(cc -> cc != null)
                .flatMap(tuple -> {
                    int sensorRotation = tuple.item2;

                    final int width = textureView.getWidth();
                    final int height = textureView.getHeight();

                    int rotatedWidth = width;
                    int rotatedHeight = height;
                    if (isNeedSwap(sensorRotation)) {
                        rotatedWidth = height;
                        rotatedHeight = width;
                    }

                    final Size[] original = tuple.item1;

                    final int finalRotatedWidth = rotatedWidth;
                    final int finalRotatedHeight = rotatedHeight;
                    return Observable
                            .from(original)
                            .filter(size -> (finalRotatedWidth <= size.getWidth() && finalRotatedHeight <= size.getHeight()))
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
                .doOnNext(size -> textureView.setAspect((float)size.getWidth() / (float)size.getHeight()));
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

        final CaptureRequest.Builder captureRequestBuilder;
        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            return;
        }
        captureRequestBuilder.addTarget(surface);

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(surface);

        try {
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraCaptureSession = session;
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                    CaptureRequest captureRequest = captureRequestBuilder.build();
                    try {
                        cameraCaptureSession.setRepeatingRequest(captureRequest, null, backgroundHandler);
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
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
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

}
