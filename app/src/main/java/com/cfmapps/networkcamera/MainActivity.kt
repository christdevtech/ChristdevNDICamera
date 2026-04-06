package com.cfmapps.networkcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cfmapps.networkcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ndiCameraManager: NdiCameraManager
    private var isStreaming = false

    private var selectedCameraId: String? = null
    private var selectedSize: Size? = null
    private var isPortraitLock: Boolean = false
    
    // Camera Control Ranges
    private var exposureRange: android.util.Range<Int>? = null
    private var isoRange: android.util.Range<Int>? = null
    private var shutterRange: android.util.Range<Long>? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            initSetupScreen()
        } else {
            handlePermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Professional touch: Keep screen on during operation
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ndiCameraManager = NdiCameraManager(this)

        setupListeners()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!isStreaming) {
                // Ensure setup screen is up to date if we resumed without streaming
                initSetupScreen()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isStreaming) {
            ndiCameraManager.close()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isStreaming && binding.setupContainer.visibility == View.VISIBLE) {
            // Keep setup screen up to date on rotation
        } else if (isStreaming && binding.cameraTextureView.isAvailable) {
            // Refresh matrix
            binding.cameraTextureView.post {
                selectedSize?.let { size -> adjustAspectRatio(size.width, size.height) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ndiCameraManager.release()
    }

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initSetupScreen()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun handlePermissionDenied() {
        val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
        if (!showRationale) {
            Toast.makeText(this, "Permission permanently denied. Please enable in Settings.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Camera permission is mandatory for this app.", Toast.LENGTH_LONG).show()
        }
    }

    // ==========================================
    // 1. SETUP SCREEN LOGIC
    // ==========================================
    private fun initSetupScreen() {
        // Ensure UI is in Setup Mode
        binding.setupContainer.visibility = View.VISIBLE
        binding.liveContainer.visibility = View.GONE
        
        val cameraIds = ndiCameraManager.getCameraIds()
        if (cameraIds.isEmpty()) {
            Toast.makeText(this, "No cameras found", Toast.LENGTH_LONG).show()
            return
        }

        // 1. Camera List
        if (selectedCameraId == null) {
            selectedCameraId = cameraIds.firstOrNull { id ->
                val chars = (getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager).getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraIds[0]
        }
        setupCameraSpinner(cameraIds)

        // 2. FPS List
        val fpsOptions = listOf(30, 60, 24, 25, 50)
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsOptions.map { "$it fps" })
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSetupFps.adapter = fpsAdapter
        binding.spinnerSetupFps.setSelection(0)

        // 3. Orientation List
        val orientationOptions = listOf("Landscape", "Portrait")
        val oriAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, orientationOptions)
        oriAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSetupOrientation.adapter = oriAdapter
        binding.spinnerSetupOrientation.setSelection(0)

        // 4. Audio Devices
        setupAudioDevices()
    }

    private fun setupCameraSpinner(cameraIds: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraIds)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSetupCamera.adapter = adapter
        
        val initialIndex = cameraIds.indexOf(selectedCameraId)
        if (initialIndex >= 0) binding.spinnerSetupCamera.setSelection(initialIndex)

        binding.spinnerSetupCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newId = cameraIds[position]
                if (newId != selectedCameraId) {
                    selectedCameraId = newId
                    updateResolutionOptions(newId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        selectedCameraId?.let { updateResolutionOptions(it) }
    }

    private fun updateResolutionOptions(cameraId: String) {
        val allResolutions = ndiCameraManager.getCameraResolutions(cameraId)
        if (allResolutions.isEmpty()) return

        // Populate controls ranges in advance
        setupLiveCameraControlsRanges(cameraId)

        val standardFormats = mapOf(
            Size(3840, 2160) to "4K UHD",
            Size(1920, 1080) to "1080p FHD",
            Size(1280, 720) to "720p HD",
            Size(854, 480) to "480p SD",
            Size(720, 480) to "480p SD"
        )

        var displayResolutions = allResolutions
            .filter { standardFormats.containsKey(it) }
            .sortedByDescending { it.width * it.height }

        if (displayResolutions.isEmpty()) {
            displayResolutions = allResolutions.sortedByDescending { it.width * it.height }.take(5)
        }

        val resStrings = displayResolutions.map { size ->
            val name = standardFormats[size]
            if (name != null) "$name (${size.width}x${size.height})" else "${size.width}x${size.height}"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resStrings)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSetupResolution.adapter = adapter

        // Set default to 1080p as requested, or fallback to best
        val defaultSize = displayResolutions.find { it.width == 1920 && it.height == 1080 } 
                        ?: displayResolutions.find { it.width == 1280 && it.height == 720 }
                        ?: displayResolutions[0]
        
        selectedSize = defaultSize
        binding.spinnerSetupResolution.setSelection(displayResolutions.indexOf(defaultSize))

        binding.spinnerSetupResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSize = displayResolutions[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupAudioDevices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            
            if (devices.isNotEmpty()) {
                val deviceNames = devices.map { it.productName.toString() }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerSetupMicSource.adapter = adapter
            } else {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("No Microphone Detected"))
                binding.spinnerSetupMicSource.adapter = adapter
                binding.switchSetupMic.isChecked = false
            }
        } else {
            binding.switchSetupMic.isChecked = false
            binding.switchSetupMic.isEnabled = false
        }
    }

    // ==========================================
    // 2. LIVE SCREEN LOGIC
    // ==========================================
    
    private fun startLiveBroadcast() {
        val ndiName = binding.etSetupName.text.toString()
        val fpsString = binding.spinnerSetupFps.selectedItem.toString()
        val fps = fpsString.replace(" fps", "").toIntOrNull() ?: 30
        
        isPortraitLock = binding.spinnerSetupOrientation.selectedItem.toString() == "Portrait"
        
        // Lock OS Orientation to requested setup (Allows upside down rotations)
        requestedOrientation = if (isPortraitLock) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        ndiCameraManager.targetFps = fps
        ndiCameraManager.forcePortraitMode = isPortraitLock

        // Force cleanup before starting NDI
        ndiCameraManager.destroyNdi()
        Thread.sleep(100)
        
        if (ndiCameraManager.initializeNdi(ndiName)) {
            isStreaming = true
            
            // Swap UI
            binding.setupContainer.visibility = View.GONE
            binding.liveContainer.visibility = View.VISIBLE
            binding.liveHudPanel.visibility = View.GONE // Start collapsed
            
            // Tally
            binding.tallyBorder.visibility = View.VISIBLE
            binding.tvLiveStatus.text = getString(R.string.live_status, ndiName)
            
            // Start Camera
            startLocalPreview()
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Toast.makeText(this, "Failed to start NDI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLiveBroadcast() {
        isStreaming = false
        ndiCameraManager.destroyNdi()
        ndiCameraManager.close()
        
        // Unlock orientation
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        // Swap UI back
        binding.setupContainer.visibility = View.VISIBLE
        binding.liveContainer.visibility = View.GONE
        binding.tallyBorder.visibility = View.GONE
        
        // Reset HUD state
        binding.switchLiveManualMode.isChecked = false
        syncManualModeUI(false)
    }

    private fun startLocalPreview() {
        if (binding.cameraTextureView.isAvailable) {
            attachCameraToTexture()
        } else {
            binding.cameraTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    attachCameraToTexture()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    private fun attachCameraToTexture() {
        val id = selectedCameraId ?: return
        val size = selectedSize ?: return
        
        lifecycleScope.launch {
            ndiCameraManager.close()
            try {
                adjustAspectRatio(size.width, size.height)
                ndiCameraManager.openCamera(id, size)
                ndiCameraManager.startPreview(binding.cameraTextureView)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error restarting preview", e)
            }
        }
    }

    /**
     * Configures the TextureView transform to display the camera preview with correct
     * orientation and aspect ratio.
     *
     * KEY INSIGHT: Camera2's SurfaceTexture already applies the sensor orientation rotation
     * via its internal GL transform matrix. This means:
     *   - In portrait (ROTATION_0): content is already upright, no extra rotation needed
     *   - In landscape (ROTATION_90/270): we must compensate because the GL transform
     *     still rotated for the device's natural (portrait) orientation
     *
     * This follows the standard Google Camera2Basic configureTransform pattern.
     */
    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        val viewWidth = binding.liveContainer.width.toFloat()
        val viewHeight = binding.liveContainer.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) {
            binding.cameraTextureView.post { adjustAspectRatio(videoWidth, videoHeight) }
            return
        }

        @Suppress("DEPRECATION")
        val rotation = (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation

        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth, viewHeight)
        // After the SurfaceTexture's internal GL rotation, the effective content dimensions
        // are swapped (height x width) relative to the raw buffer. So bufferRect uses
        // (videoHeight x videoWidth) to represent the post-rotation effective size.
        val bufferRect = android.graphics.RectF(0f, 0f, videoHeight.toFloat(), videoWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                // In landscape: the SurfaceTexture GL transform rotated for portrait,
                // but the display is landscape. We need to re-map and rotate to compensate.
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
                // Scale to fit within the view (letterbox / contain)
                val scale = Math.min(
                    viewWidth / videoWidth.toFloat(),
                    viewHeight / videoHeight.toFloat()
                )
                matrix.postScale(scale, scale, centerX, centerY)
                // Rotate to match the actual display orientation
                val rotationDegrees = 90f * (rotation - 2)
                matrix.postRotate(rotationDegrees, centerX, centerY)
            }
            Surface.ROTATION_180 -> {
                // Upside-down portrait: content is upright but flipped 180°
                matrix.postRotate(180f, centerX, centerY)
            }
            // Surface.ROTATION_0 -> identity matrix (SurfaceTexture already handled rotation)
        }

        // For portrait (ROTATION_0), we may still need aspect ratio correction
        // if the view aspect ratio doesn't perfectly match the video.
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            // The effective content after SurfaceTexture rotation is portrait: (videoHeight x videoWidth)
            val contentWidth = videoHeight.toFloat()
            val contentHeight = videoWidth.toFloat()
            val viewAspect = viewWidth / viewHeight
            val contentAspect = contentWidth / contentHeight

            if (Math.abs(viewAspect - contentAspect) > 0.01f) {
                // Need to letterbox: scale content to fit within view
                val scale = Math.min(viewWidth / contentWidth, viewHeight / contentHeight)
                val scaleX = (contentWidth * scale) / viewWidth
                val scaleY = (contentHeight * scale) / viewHeight
                matrix.postScale(scaleX, scaleY, centerX, centerY)
            }
        }

        binding.cameraTextureView.setTransform(matrix)

        // Ensure the TextureView fills the container so the Matrix can position freely
        val lp = binding.cameraTextureView.layoutParams
        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        binding.cameraTextureView.layoutParams = lp
    }

    // ==========================================
    // 3. CAMERA HUD CONTROLS
    // ==========================================

    private fun setupLiveCameraControlsRanges(cameraId: String) {
        // Auto Exposure Control
        exposureRange = ndiCameraManager.getExposureRange(cameraId)
        exposureRange?.let { range ->
            val max = range.upper - range.lower
            val progress = 0 - range.lower
            
            binding.seekLiveExposure.max = max
            binding.seekLiveExposure.progress = progress
            binding.seekLiveExposure.isEnabled = true
        } ?: run { 
            binding.seekLiveExposure.isEnabled = false 
        }

        // ISO Control
        isoRange = ndiCameraManager.getIsoRange(cameraId)
        isoRange?.let { range ->
            val max = range.upper - range.lower
            val progress = range.lower
            
            binding.seekLiveIso.max = max
            binding.seekLiveIso.progress = progress
            binding.tvLiveIsoLabel.text = getString(R.string.iso, range.lower)
        }

        // Shutter Speed Control
        shutterRange = ndiCameraManager.getShutterSpeedRange(cameraId)
        shutterRange?.let { range ->
            binding.seekLiveShutter.max = 100
            binding.seekLiveShutter.progress = 0
            val initialMs = range.lower / 1000000.0
            binding.tvLiveShutterLabel.text = getString(R.string.shutter, String.format(Locale.US, "%.1f", initialMs))
        }
    }

    private fun syncManualModeUI(isChecked: Boolean) {
        binding.layoutLiveAutoControls.visibility = if (isChecked) View.GONE else View.VISIBLE
        binding.layoutLiveManualControls.visibility = if (isChecked) View.VISIBLE else View.GONE
        
        if (isChecked) {
            val currentIso = ndiCameraManager.lastAutoIso
            val currentShutter = ndiCameraManager.lastAutoShutter
            
            isoRange?.let { range ->
                val clampedIso = currentIso.coerceIn(range.lower, range.upper)
                val progress = clampedIso - range.lower
                binding.seekLiveIso.progress = progress
                binding.tvLiveIsoLabel.text = getString(R.string.iso, clampedIso)
                ndiCameraManager.setIso(clampedIso)
            }
            
            shutterRange?.let { range ->
                val clampedShutter = currentShutter.coerceIn(range.lower, range.upper)
                val fraction = (clampedShutter - range.lower).toDouble() / (range.upper - range.lower).toDouble()
                val progress = (fraction * 100).toInt()
                binding.seekLiveShutter.progress = progress
                val ms = clampedShutter / 1000000.0
                binding.tvLiveShutterLabel.text = getString(R.string.shutter, String.format(Locale.US, "%.1f", ms))
                ndiCameraManager.setShutterSpeed(clampedShutter)
            }
        }
        ndiCameraManager.setManualMode(isChecked)
    }

    private fun setupListeners() {
        // Setup Screen
        binding.btnStartStream.setOnClickListener {
            startLiveBroadcast()
        }

        // Live Screen
        binding.btnStopStream.setOnClickListener {
            stopLiveBroadcast()
        }

        binding.btnLiveHudToggle.setOnClickListener {
            if (binding.liveHudPanel.visibility == View.VISIBLE) {
                binding.liveHudPanel.visibility = View.GONE
            } else {
                binding.liveHudPanel.visibility = View.VISIBLE
            }
        }

        binding.switchLiveManualMode.setOnCheckedChangeListener { _, isChecked ->
            syncManualModeUI(isChecked)
        }

        binding.seekLiveExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exposureRange?.let { range ->
                        val ev = progress + range.lower
                        ndiCameraManager.setExposure(ev)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.seekLiveIso.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isoRange?.let { range ->
                        val iso = progress + range.lower
                        binding.tvLiveIsoLabel.text = getString(R.string.iso, iso)
                        ndiCameraManager.setIso(iso)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.seekLiveShutter.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    shutterRange?.let { range ->
                        val fraction = progress / 100.0
                        val shutterSpeed = range.lower + (fraction * (range.upper - range.lower)).toLong()
                        val ms = shutterSpeed / 1000000.0
                        binding.tvLiveShutterLabel.text = getString(R.string.shutter, String.format(Locale.US, "%.1f", ms))
                        ndiCameraManager.setShutterSpeed(shutterSpeed)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }
}
