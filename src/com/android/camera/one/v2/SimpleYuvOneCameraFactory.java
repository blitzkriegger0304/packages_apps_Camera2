/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.camera.one.v2;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import com.android.camera.app.OrientationManager;
import com.android.camera.async.CallbackRunnable;
import com.android.camera.async.ConcurrentState;
import com.android.camera.async.HandlerExecutor;
import com.android.camera.async.Lifetime;
import com.android.camera.async.Updatable;
import com.android.camera.one.OneCamera;
import com.android.camera.one.v2.autofocus.ManualAutoFocus;
import com.android.camera.one.v2.autofocus.ManualAutoFocusFactory;
import com.android.camera.one.v2.camera2proxy.CameraCaptureSessionProxy;
import com.android.camera.one.v2.camera2proxy.CameraDeviceProxy;
import com.android.camera.one.v2.camera2proxy.CameraDeviceRequestBuilderFactory;
import com.android.camera.one.v2.commands.CameraCommandExecutor;
import com.android.camera.one.v2.commands.PreviewCommand;
import com.android.camera.one.v2.commands.RunnableCameraCommand;
import com.android.camera.one.v2.common.SimpleCaptureStream;
import com.android.camera.one.v2.common.TimestampResponseListener;
import com.android.camera.one.v2.common.TotalCaptureResultResponseListener;
import com.android.camera.one.v2.common.ZoomedCropRegion;
import com.android.camera.one.v2.core.CaptureStream;
import com.android.camera.one.v2.core.RequestTemplate;
import com.android.camera.one.v2.core.FrameServer;
import com.android.camera.one.v2.core.FrameServerFactory;
import com.android.camera.one.v2.initialization.CameraStarter;
import com.android.camera.one.v2.initialization.InitializedOneCameraFactory;
import com.android.camera.one.v2.photo.ImageRotationCalculator;
import com.android.camera.one.v2.photo.ImageRotationCalculatorImpl;
import com.android.camera.one.v2.photo.ImageSaver;
import com.android.camera.one.v2.photo.PictureTaker;
import com.android.camera.one.v2.photo.PictureTakerFactory;
import com.android.camera.one.v2.photo.YuvImageBackendImageSaver;
import com.android.camera.one.v2.sharedimagereader.ImageStreamFactory;
import com.android.camera.one.v2.sharedimagereader.SharedImageReaderFactory;
import com.android.camera.util.Size;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Creates a camera which takes jpeg images using the hardware encoder with
 * baseline functionality.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class SimpleYuvOneCameraFactory {
    /**
     * Finishes constructing the camera when prerequisites, e.g. the preview
     * stream and capture session, are ready.
     */
    private static class CameraStarterImpl implements CameraStarter {
        private final CameraDeviceProxy mDevice;
        private final CameraCharacteristics mCameraCharacteristics;
        private final ImageReader mImageReader;
        private final Handler mMainHandler;

        private CameraStarterImpl(
                CameraDeviceProxy device,
                CameraCharacteristics cameraCharacteristics,
                ImageReader imageReader, Handler mainHandler) {
            mDevice = device;
            mCameraCharacteristics = cameraCharacteristics;
            mImageReader = imageReader;
            mMainHandler = mainHandler;
        }

        @Override
        public CameraControls startCamera(Lifetime cameraLifetime,
                CameraCaptureSessionProxy cameraCaptureSession,
                Surface previewSurface,
                ConcurrentState<Float> zoomState,
                Updatable<TotalCaptureResult> metadataCallback,
                Updatable<Boolean> readyState) {
            // Build the FrameServer from the CaptureSession
            FrameServerFactory frameServerFactory =
                    new FrameServerFactory(new Lifetime(cameraLifetime), cameraCaptureSession);
            FrameServer frameServer = frameServerFactory.provideFrameServer();

            // Build the shared image reader
            SharedImageReaderFactory sharedImageReaderFactory = new SharedImageReaderFactory(new
                    Lifetime(cameraLifetime), mImageReader);

            Updatable<Long> globalTimestampCallback =
                    sharedImageReaderFactory.provideGlobalTimestampQueue();
            ImageStreamFactory imageStreamFactory =
                    sharedImageReaderFactory.provideSharedImageReader();

            // The request builder used by all camera operations.
            // Streams, ResponseListeners, and Parameters added to
            // this will be applied to *all* requests sent to the camera.
            RequestTemplate rootBuilder = new RequestTemplate
                    (new CameraDeviceRequestBuilderFactory(mDevice));
            // The shared image reader must be wired to receive every timestamp
            // for every image (including the preview).
            rootBuilder.addResponseListener(
                    new TimestampResponseListener(globalTimestampCallback));

            rootBuilder.addResponseListener(new TotalCaptureResultResponseListener
                    (metadataCallback));

            ZoomedCropRegion cropRegion = new ZoomedCropRegion(mCameraCharacteristics,
                    zoomState);
            rootBuilder.setParam(CaptureRequest.SCALER_CROP_REGION, cropRegion);

            CaptureStream previewStream = new SimpleCaptureStream(previewSurface);
            rootBuilder.addStream(previewStream);

            int templateType = CameraDevice.TEMPLATE_PREVIEW;

            ScheduledExecutorService miscThreadPool = Executors.newScheduledThreadPool(1);

            CameraCommandExecutor cameraCommandExecutor = new CameraCommandExecutor(miscThreadPool);
            PreviewCommand previewCommand = new PreviewCommand(frameServer, rootBuilder,
                    templateType);
            Runnable previewRunner = new RunnableCameraCommand(cameraCommandExecutor,
                    previewCommand);

            // Restart the preview when the zoom changes.
            zoomState.addCallback(new CallbackRunnable(previewRunner), miscThreadPool);

            OrientationManager.DeviceOrientation sensorOrientation = getSensorOrientation();

            ManualAutoFocusFactory manualAutoFocusFactory = new ManualAutoFocusFactory(new
                    Lifetime(cameraLifetime), frameServer, miscThreadPool, cropRegion,
                    sensorOrientation, previewRunner, rootBuilder, templateType);
            ManualAutoFocus autoFocus = manualAutoFocusFactory.provideManualAutoFocus();
            Supplier<MeteringRectangle[]> aeRegions =
                    manualAutoFocusFactory.provideAEMeteringRegion();
            Supplier<MeteringRectangle[]> afRegions =
                    manualAutoFocusFactory.provideAFMeteringRegion();

            rootBuilder.setParam(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
            rootBuilder.setParam(CaptureRequest.CONTROL_AF_REGIONS, afRegions);

            HandlerExecutor mainExecutor = new HandlerExecutor(mMainHandler);

            // Used to rotate images the right way based on the sensor used for taking the image.
            ImageRotationCalculator imageRotationCalculator = ImageRotationCalculatorImpl
                    .from(mCameraCharacteristics);

            ImageSaver.Builder imageSaverBuilder = new YuvImageBackendImageSaver(mainExecutor,
                    imageRotationCalculator);

            PictureTakerFactory pictureTakerFactory = new PictureTakerFactory(mainExecutor,
                    cameraCommandExecutor, imageSaverBuilder, frameServer, rootBuilder,
                    imageStreamFactory);
            PictureTaker pictureTaker = pictureTakerFactory.providePictureTaker();

            previewRunner.run();

            return new CameraControls(pictureTaker, autoFocus);
        }

        private OrientationManager.DeviceOrientation getSensorOrientation() {
            Integer degrees = mCameraCharacteristics.get(CameraCharacteristics
                    .SENSOR_ORIENTATION);

            switch (degrees) {
                case 0:
                    return OrientationManager.DeviceOrientation.CLOCKWISE_0;
                case 90:
                    return OrientationManager.DeviceOrientation.CLOCKWISE_90;
                case 180:
                    return OrientationManager.DeviceOrientation.CLOCKWISE_180;
                case 270:
                    return OrientationManager.DeviceOrientation.CLOCKWISE_270;
                default:
                    return OrientationManager.DeviceOrientation.CLOCKWISE_0;
            }
        }
    }

    private final InitializedOneCameraFactory mInitializedOneCameraFactory;

    public SimpleYuvOneCameraFactory(CameraDevice cameraDevice,
            CameraCharacteristics characteristics, Handler mainHandler, Size pictureSize) {
        CameraDeviceProxy device = new CameraDeviceProxy(cameraDevice);

        int imageFormat = ImageFormat.YUV_420_888;
        // TODO This is totally arbitrary, and could probably be increased.
        int maxImageCount = 10;

        ImageReader imageReader = ImageReader.newInstance(pictureSize.getWidth(),
                pictureSize.getHeight(), imageFormat, maxImageCount);

        // FIXME TODO Close the ImageReader when all images have been freed!

        List<Surface> outputSurfaces = new ArrayList<>();
        outputSurfaces.add(imageReader.getSurface());

        CameraStarter cameraStarter = new CameraStarterImpl(device, characteristics, imageReader,
                mainHandler);

        mInitializedOneCameraFactory =
                new InitializedOneCameraFactory(cameraStarter, device, characteristics,
                        outputSurfaces, imageFormat, mainHandler);
    }

    public OneCamera provideOneCamera() {
        return mInitializedOneCameraFactory.provideOneCamera();
    }
}