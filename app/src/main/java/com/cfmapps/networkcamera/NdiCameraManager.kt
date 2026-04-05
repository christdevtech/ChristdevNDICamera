package com.cfmapps.networkcamera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class NdiCameraManager(private val context: Context) {
    private val systemCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: android.hardware.camera2.CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    
    private val imageReaderThread = HandlerThread("ImageReaderThread").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    // Current settings
    private var currentSize = Size(1280, 720) 

    fun getCameraIds(): List<String> {
        return systemCameraManager.cameraIdList.toList()
    }

    fun getCameraResolutions(cameraId: String): List<Size> {
        val characteristics = systemCameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
    }

    fun getExposureRange(cameraId: String): android.util.Range<Int>? {
        val characteristics = systemCameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    }

    fun setExposure(value: Int) {
        captureRequestBuilder?.let { builder ->
            builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value)
            try {
                captureSession?.setRepeatingRequest(builder.build(), null, cameraHandler)
            } catch (e: Exception) {
                Log.e("NdiCameraManager", "Failed to set exposure", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String, resolution: Size): CameraDevice = suspendCancellableCoroutine { cont ->
        currentSize = resolution
        systemCameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                cont.resume(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("NdiCameraManager", "Camera Error: $error")
                close()
            }
        }, cameraHandler)
    }

    fun startPreview(textureView: TextureView) {
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(currentSize.width, currentSize.height)
        val previewSurface = Surface(surfaceTexture)
        
        // Industry Standard: Use YUV_420_888 for high-efficiency frame access
        imageReader = ImageReader.newInstance(currentSize.width, currentSize.height, ImageFormat.YUV_420_888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // Here we will call the Native NDI wrapper to send this image
                processImageForNdi(image)
            } finally {
                image.close()
            }
        }, imageReaderHandler)

        val surfaces = listOf(previewSurface, imageReader!!.surface)

        cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                captureRequestBuilder?.addTarget(previewSurface)
                captureRequestBuilder?.addTarget(imageReader!!.surface)
                
                // Professional touch: Enable Continuous Auto Focus
                captureRequestBuilder?.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, 
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                
                session.setRepeatingRequest(captureRequestBuilder!!.build(), null, cameraHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("NdiCameraManager", "Failed to configure capture session")
            }
        }, cameraHandler)
    }

    fun processImageForNdi(image: android.media.Image) {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val rotation = getFrameRotation()

        // Pass the buffers and required rotation directly to the NDI SDK
        sendVideoFrame(
            image.width, image.height,
            yBuffer, planes[0].rowStride,
            uBuffer, planes[1].rowStride,
            vBuffer, planes[2].rowStride,
            planes[1].pixelStride,
            rotation
        )
    }

    private fun getFrameRotation(): Int {
        val display = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val displayRotation = display.defaultDisplay.rotation
        val degrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        
        val cameraId = cameraDevice?.id ?: return 0
        val characteristics = systemCameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        
        val isFront = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        
        return if (isFront) {
            (sensorOrientation + degrees) % 360
        } else {
            (sensorOrientation - degrees + 360) % 360
        }
    }

    external fun initializeNdi(name: String): Boolean
    private external fun sendVideoFrame(
        width: Int, height: Int,
        yBuffer: java.nio.ByteBuffer, yRowStride: Int,
        uBuffer: java.nio.ByteBuffer, uRowStride: Int,
        vBuffer: java.nio.ByteBuffer, vRowStride: Int,
        uvPixelStride: Int,
        rotation: Int
    )
    external fun destroyNdi()

    companion object {
        init {
            System.loadLibrary("ndi_wrapper")
        }
    }

    fun close() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            Log.e("NdiCameraManager", "Error closing session", e)
        }
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    fun release() {
        close()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }
}
