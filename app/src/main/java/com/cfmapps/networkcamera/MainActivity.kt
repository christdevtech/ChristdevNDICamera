package com.cfmapps.networkcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ndiCameraManager: NdiCameraManager
    private var isStreaming = false

    private var selectedCameraId: String? = null
    private var selectedSize: Size? = null
    private var exposureRange: android.util.Range<Int>? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            setupUI()
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

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            setupUI()
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

    private fun setupUI() {
        val cameraIds = ndiCameraManager.getCameraIds()
        if (cameraIds.isEmpty()) {
            Toast.makeText(this, "No cameras found", Toast.LENGTH_LONG).show()
            return
        }

        // 1. Initial Selection
        if (selectedCameraId == null) {
            selectedCameraId = cameraIds.firstOrNull { id ->
                val chars = (getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager).getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraIds[0]
        }

        // 2. Setup Spinners
        setupCameraSpinner(cameraIds)
        
        // 3. Start Preview
        if (binding.cameraTextureView.isAvailable) {
            startInitialPreview()
        } else {
            binding.cameraTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    startInitialPreview()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    private fun setupCameraSpinner(cameraIds: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraIds)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCamera.adapter = adapter
        
        val initialIndex = cameraIds.indexOf(selectedCameraId)
        if (initialIndex >= 0) binding.spinnerCamera.setSelection(initialIndex)

        binding.spinnerCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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

        // Setup Exposure Slider for this camera
        exposureRange = ndiCameraManager.getExposureRange(cameraId)
        exposureRange?.let { range ->
            // Android SeekBar is 0-indexed, so we map [-min, max] to [0, max-min]
            binding.seekExposure.max = range.upper - range.lower
            binding.seekExposure.progress = 0 - range.lower // default to 0 EV
            binding.seekExposure.isEnabled = true
        } ?: run {
            binding.seekExposure.isEnabled = false
        }

        // Define standard broadcast formats to simplify the UI
        val standardFormats = mapOf(
            Size(3840, 2160) to "4K UHD",
            Size(1920, 1080) to "1080p FHD",
            Size(1280, 720) to "720p HD",
            Size(854, 480) to "480p SD (16:9)",
            Size(720, 480) to "480p SD"
        )

        // Filter and sort available resolutions
        var displayResolutions = allResolutions
            .filter { standardFormats.containsKey(it) }
            .sortedByDescending { it.width * it.height }

        // Fallback: If no standard sizes are found, show the top 5 highest resolutions
        if (displayResolutions.isEmpty()) {
            displayResolutions = allResolutions
                .sortedByDescending { it.width * it.height }
                .take(5)
        }

        val resStrings = displayResolutions.map { size ->
            val name = standardFormats[size]
            if (name != null) "$name (${size.width}x${size.height})" else "${size.width}x${size.height}"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resStrings)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = adapter

        // Set default to 720p, 1080p, or the highest available
        val defaultSize = displayResolutions.find { it.width == 1280 && it.height == 720 } 
                        ?: displayResolutions.find { it.width == 1920 && it.height == 1080 }
                        ?: displayResolutions[0]
        
        selectedSize = defaultSize
        binding.spinnerResolution.setSelection(displayResolutions.indexOf(defaultSize))

        binding.spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSize = displayResolutions[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun startInitialPreview() {
        if (selectedCameraId != null && selectedSize != null) {
            restartPreview()
        }
    }

    private fun restartPreview() {
        val id = selectedCameraId ?: return
        val size = selectedSize ?: return
        
        lifecycleScope.launch {
            ndiCameraManager.close()
            try {
                // Adjust TextureView aspect ratio
                adjustAspectRatio(size.width, size.height)

                // Ensure orientation is locked while streaming
                if (isStreaming) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }

                ndiCameraManager.openCamera(id, size)
                ndiCameraManager.startPreview(binding.cameraTextureView)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error restarting preview", e)
            }
        }
    }

    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        val viewWidth = binding.root.width
        val viewHeight = binding.root.height
        if (viewWidth == 0 || viewHeight == 0) return

        val display = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val rotation = display.defaultDisplay.rotation
        val isPortrait = rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180

        val effectiveWidth = if (isPortrait) videoHeight else videoWidth
        val effectiveHeight = if (isPortrait) videoWidth else videoHeight

        val aspectRatio = effectiveWidth.toFloat() / effectiveHeight.toFloat()
        val layoutParams = binding.cameraTextureView.layoutParams
        
        if (viewWidth > viewHeight * aspectRatio) {
            layoutParams.width = (viewHeight * aspectRatio).toInt()
            layoutParams.height = viewHeight
        } else {
            layoutParams.width = viewWidth
            layoutParams.height = (viewWidth / aspectRatio).toInt()
        }
        binding.cameraTextureView.layoutParams = layoutParams

        // Fix the local preview orientation
        applyPreviewTransform(layoutParams.width, layoutParams.height, isPortrait)
    }

    private fun applyPreviewTransform(viewWidth: Int, viewHeight: Int, isPortrait: Boolean) {
        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (isPortrait) {
            // Camera sensor is 90 degrees offset from portrait display
            val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = selectedCameraId ?: return
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            // Rotate visually
            matrix.postRotate(sensorOrientation.toFloat(), centerX, centerY)
            
            // Re-scale after rotation
            val scaleX = viewHeight.toFloat() / viewWidth.toFloat()
            val scaleY = viewWidth.toFloat() / viewHeight.toFloat()
            matrix.postScale(scaleX, scaleY, centerX, centerY)
        }
        
        binding.cameraTextureView.setTransform(matrix)
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            binding.settingsSheet.visibility = View.VISIBLE
        }

        binding.seekExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
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

        binding.btnApplySettings.setOnClickListener {
            binding.settingsSheet.visibility = View.GONE
            restartPreview()
        }

        binding.btnStreamToggle.setOnClickListener {
            if (isStreaming) {
                stopStream()
            } else {
                startStream()
            }
        }
    }

    private fun startStream() {
        val ndiName = binding.etNdiName.text.toString()
        
        // Lock orientation BEFORE starting stream
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        
        if (ndiCameraManager.initializeNdi(ndiName)) {
            isStreaming = true
            binding.tvStatus.text = "LIVE • $ndiName"
            binding.tvStatus.setTextColor(Color.RED)
            binding.btnStreamToggle.text = "STOP"
            binding.btnSettings.visibility = View.GONE
            binding.tallyBorder.visibility = View.VISIBLE
            binding.tallyBorder.setBackgroundColor(Color.argb(100, 255, 0, 0))
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Toast.makeText(this, "Failed to start NDI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStream() {
        isStreaming = false
        ndiCameraManager.destroyNdi()
        
        // Unlock orientation
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        binding.tvStatus.text = "READY"
        binding.tvStatus.setTextColor(Color.WHITE)
        binding.btnStreamToggle.text = "GO LIVE"
        binding.btnSettings.visibility = View.VISIBLE
        binding.tallyBorder.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupUI()
        }
    }

    override fun onPause() {
        super.onPause()
        // If we're not streaming, we can close the camera to save power
        if (!isStreaming) {
            ndiCameraManager.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ndiCameraManager.release()
    }
}
