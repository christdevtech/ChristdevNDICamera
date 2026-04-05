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

        setupCameraControls(cameraId)

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

    private fun setupCameraControls(cameraId: String) {
        // Auto Exposure Control
        exposureRange = ndiCameraManager.getExposureRange(cameraId)
        exposureRange?.let { range ->
            val max = range.upper - range.lower
            val progress = 0 - range.lower
            
            binding.seekExposure.max = max
            binding.seekExposure.progress = progress
            binding.seekExposure.isEnabled = true
            
            binding.seekQuickExposure.max = max
            binding.seekQuickExposure.progress = progress
            binding.seekQuickExposure.isEnabled = true
        } ?: run { 
            binding.seekExposure.isEnabled = false 
            binding.seekQuickExposure.isEnabled = false
        }

        // ISO Control
        isoRange = ndiCameraManager.getIsoRange(cameraId)
        isoRange?.let { range ->
            val max = range.upper - range.lower
            val progress = range.lower
            
            binding.seekIso.max = max
            binding.seekIso.progress = progress
            binding.tvIsoLabel.text = "ISO: ${range.lower}"
            
            binding.seekQuickIso.max = max
            binding.seekQuickIso.progress = progress
            binding.tvQuickIsoLabel.text = "ISO: ${range.lower}"
        }

        // Shutter Speed Control
        shutterRange = ndiCameraManager.getShutterSpeedRange(cameraId)
        shutterRange?.let { range ->
            binding.seekShutter.max = 100
            binding.seekShutter.progress = 0
            val initialMs = range.lower / 1000000.0
            val text = "Shutter: ${String.format(java.util.Locale.US, "%.1f", initialMs)} ms"
            
            binding.tvShutterLabel.text = text
            
            binding.seekQuickShutter.max = 100
            binding.seekQuickShutter.progress = 0
            binding.tvQuickShutterLabel.text = text
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
        
        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(0f, 0f, videoHeight.toFloat(), videoWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.CENTER)

        if (android.view.Surface.ROTATION_90 == rotation || android.view.Surface.ROTATION_270 == rotation) {
            val scale = Math.min(
                viewHeight.toFloat() / videoHeight,
                viewWidth.toFloat() / videoWidth
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else {
            val scale = Math.min(
                viewHeight.toFloat() / videoWidth,
                viewWidth.toFloat() / videoHeight
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f, centerX, centerY)
        }
        binding.cameraTextureView.setTransform(matrix)
        
        val lp = binding.cameraTextureView.layoutParams
        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        binding.cameraTextureView.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isStreaming) {
            startInitialPreview()
        }
    }

    private fun syncManualModeUI(isChecked: Boolean) {
        binding.layoutAutoControls.visibility = if (isChecked) View.GONE else View.VISIBLE
        binding.layoutManualControls.visibility = if (isChecked) View.VISIBLE else View.GONE
        
        binding.seekQuickExposure.visibility = if (isChecked) View.GONE else View.VISIBLE
        binding.layoutQuickManualControls.visibility = if (isChecked) View.VISIBLE else View.GONE
        
        binding.switchManualMode.isChecked = isChecked
        binding.switchQuickManualMode.isChecked = isChecked
        
        if (isChecked) {
            val currentIso = ndiCameraManager.lastAutoIso
            val currentShutter = ndiCameraManager.lastAutoShutter
            
            isoRange?.let { range ->
                val clampedIso = currentIso.coerceIn(range.lower, range.upper)
                val progress = clampedIso - range.lower
                binding.seekIso.progress = progress
                binding.seekQuickIso.progress = progress
                val text = "ISO: $clampedIso"
                binding.tvIsoLabel.text = text
                binding.tvQuickIsoLabel.text = text
                ndiCameraManager.setIso(clampedIso)
            }
            
            shutterRange?.let { range ->
                val clampedShutter = currentShutter.coerceIn(range.lower, range.upper)
                val fraction = (clampedShutter - range.lower).toDouble() / (range.upper - range.lower).toDouble()
                val progress = (fraction * 100).toInt()
                binding.seekShutter.progress = progress
                binding.seekQuickShutter.progress = progress
                val ms = clampedShutter / 1000000.0
                val text = "Shutter: ${String.format(java.util.Locale.US, "%.1f", ms)} ms"
                binding.tvShutterLabel.text = text
                binding.tvQuickShutterLabel.text = text
                ndiCameraManager.setShutterSpeed(clampedShutter)
            }
        }
        ndiCameraManager.setManualMode(isChecked)
    }

    private fun handleExposureChange(progress: Int) {
        exposureRange?.let { range ->
            val ev = progress + range.lower
            ndiCameraManager.setExposure(ev)
            binding.seekExposure.progress = progress
            binding.seekQuickExposure.progress = progress
        }
    }
    
    private fun handleIsoChange(progress: Int) {
        isoRange?.let { range ->
            val iso = progress + range.lower
            val text = "ISO: $iso"
            binding.tvIsoLabel.text = text
            binding.tvQuickIsoLabel.text = text
            binding.seekIso.progress = progress
            binding.seekQuickIso.progress = progress
            ndiCameraManager.setIso(iso)
        }
    }
    
    private fun handleShutterChange(progress: Int) {
        shutterRange?.let { range ->
            val fraction = progress / 100.0
            val shutterSpeed = range.lower + (fraction * (range.upper - range.lower)).toLong()
            val ms = shutterSpeed / 1000000.0
            val text = "Shutter: ${String.format(java.util.Locale.US, "%.1f", ms)} ms"
            binding.tvShutterLabel.text = text
            binding.tvQuickShutterLabel.text = text
            binding.seekShutter.progress = progress
            binding.seekQuickShutter.progress = progress
            ndiCameraManager.setShutterSpeed(shutterSpeed)
        }
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            binding.settingsSheet.visibility = View.VISIBLE
            binding.quickControlsOverlay.visibility = View.GONE
        }

        binding.btnQuickSettings.setOnClickListener {
            if (binding.quickControlsOverlay.visibility == View.VISIBLE) {
                binding.quickControlsOverlay.visibility = View.GONE
            } else {
                binding.quickControlsOverlay.visibility = View.VISIBLE
            }
        }

        binding.switchManualMode.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchQuickManualMode.isChecked != isChecked) {
                syncManualModeUI(isChecked)
            }
        }
        
        binding.switchQuickManualMode.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchManualMode.isChecked != isChecked) {
                syncManualModeUI(isChecked)
            }
        }

        val exposureListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) handleExposureChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }
        binding.seekExposure.setOnSeekBarChangeListener(exposureListener)
        binding.seekQuickExposure.setOnSeekBarChangeListener(exposureListener)

        val isoListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) handleIsoChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }
        binding.seekIso.setOnSeekBarChangeListener(isoListener)
        binding.seekQuickIso.setOnSeekBarChangeListener(isoListener)

        val shutterListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) handleShutterChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }
        binding.seekShutter.setOnSeekBarChangeListener(shutterListener)
        binding.seekQuickShutter.setOnSeekBarChangeListener(shutterListener)

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
        
        // Force cleanup before starting to prevent port clashes
        ndiCameraManager.destroyNdi()
        Thread.sleep(100)
        
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
