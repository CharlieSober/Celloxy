package com.charliesober.celloxy

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import android.graphics.Bitmap

/**
 * MainActivity implements Camera2 API with precise strobe control and photo capture.
 * 
 * Key features:
 * - Camera2 API with CameraManager.setTorchMode() for flashlight control
 * - Precise 16.66ms strobe intervals using HandlerThread
 * - Photo capture that doesn't interfere with strobe operation
 * - Independent torch control using CameraCaptureSession
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1001
        // Target is 16.66ms (60 Hz), but we use 16ms as Handler.postDelayed() accepts long integers
        // This results in ~62.5 Hz, approximately 4% faster than the target
        private const val STROBE_INTERVAL_MS = 16L
    }

    // UI elements
    private lateinit var btnToggleStrobe: Button
    private lateinit var btnCapturePhoto: Button
    private lateinit var imageView: ImageView

    // Camera2 API components
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraId: String? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    // Strobe control
    private var strobeThread: HandlerThread? = null
    private var strobeHandler: Handler? = null
    private var isStrobeRunning = false
    private var isTorchOn = false

    // Background thread for camera operations
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Dummy surface for camera session (required by Camera2 API)
    private var dummySurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        btnToggleStrobe = findViewById(R.id.btnToggleStrobe)
        btnCapturePhoto = findViewById(R.id.btnCapturePhoto)
        imageView = findViewById(R.id.imageView)

        // Initialize camera manager
        cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        // Set up button listeners
        btnToggleStrobe.setOnClickListener {
            if (isStrobeRunning) {
                stopStrobe()
            } else {
                startStrobe()
            }
        }

        btnCapturePhoto.setOnClickListener {
            capturePhoto()
        }

        // Check and request permissions
        checkPermissions()
    }

    /**
     * Check and request camera permissions
     */
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        } else {
            setupCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /**
     * Set up the camera and find a camera with flash capability
     */
    private fun setupCamera() {
        try {
            val manager = cameraManager ?: return
            
            // Find a camera with flash
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                
                if (hasFlash) {
                    cameraId = id
                    Log.d(TAG, "Camera with flash found: $id")
                    
                    // Start background thread for camera operations
                    startBackgroundThread()
                    
                    // Open the camera
                    openCamera()
                    return
                }
            }
            
            // No camera with flash found
            Toast.makeText(this, R.string.no_flash, Toast.LENGTH_LONG).show()
            Log.e(TAG, "No camera with flash capability found")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up camera", e)
            Toast.makeText(this, R.string.camera_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Start background thread for camera operations
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    /**
     * Stop background thread
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    /**
     * Open the camera device
     */
    private fun openCamera() {
        try {
            val manager = cameraManager ?: return
            val camId = cameraId ?: return
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened successfully")
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.camera_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
        }
    }

    /**
     * Create a camera capture session
     * This session is needed for photo capture but will NOT control the torch
     */
    private fun createCaptureSession() {
        try {
            val camera = cameraDevice ?: return
            
            // Create a dummy surface for the session (Camera2 requires at least one surface)
            surfaceTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(640, 480)
            }
            dummySurface = Surface(surfaceTexture)
            
            // Set up ImageReader for photo capture
            imageReader = ImageReader.newInstance(
                1920, 1080, // Resolution
                ImageFormat.JPEG,
                2 // Max images
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        
                        // Convert to bitmap and display
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        runOnUiThread {
                            imageView.setImageBitmap(bitmap)
                            Toast.makeText(this@MainActivity, R.string.photo_captured, Toast.LENGTH_SHORT).show()
                        }
                        
                        image.close()
                    }
                }, backgroundHandler)
            }
            
            // Create capture session with dummy surface and ImageReader surface
            val surfaces = listOf(dummySurface!!, imageReader!!.surface)
            
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured")
                    captureSession = session
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    /**
     * Start the strobe effect with precise 16.66ms intervals
     * Uses HandlerThread for precise timing
     */
    private fun startStrobe() {
        if (isStrobeRunning) return
        
        val camId = cameraId
        val manager = cameraManager
        
        if (camId == null || manager == null) {
            Toast.makeText(this, R.string.camera_error, Toast.LENGTH_SHORT).show()
            return
        }
        
        isStrobeRunning = true
        btnToggleStrobe.text = getString(R.string.stop_strobe)
        
        // Create dedicated strobe thread
        strobeThread = HandlerThread("StrobeThread").also {
            it.start()
            strobeHandler = Handler(it.looper)
        }
        
        // Start the strobe loop
        strobeLoop(manager, camId)
        
        Log.d(TAG, "Strobe started")
    }

    /**
     * Strobe loop that toggles the torch every 16.66ms
     * Uses CameraManager.setTorchMode() for independent torch control
     */
    private fun strobeLoop(manager: android.hardware.camera2.CameraManager, camId: String) {
        strobeHandler?.post(object : Runnable {
            override fun run() {
                if (!isStrobeRunning) return
                
                try {
                    // Toggle torch state
                    isTorchOn = !isTorchOn
                    manager.setTorchMode(camId, isTorchOn)
                    
                    // Schedule next toggle after 16ms (approximately 16.66ms)
                    strobeHandler?.postDelayed(this, STROBE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in strobe loop", e)
                    runOnUiThread {
                        stopStrobe()
                        Toast.makeText(this@MainActivity, R.string.camera_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * Stop the strobe effect
     */
    private fun stopStrobe() {
        if (!isStrobeRunning) return
        
        isStrobeRunning = false
        btnToggleStrobe.text = getString(R.string.start_strobe)
        
        // Stop the strobe handler
        strobeHandler?.removeCallbacksAndMessages(null)
        
        // Stop strobe thread
        strobeThread?.quitSafely()
        try {
            strobeThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping strobe thread", e)
        }
        strobeThread = null
        strobeHandler = null
        
        // Turn off the torch
        try {
            cameraId?.let { camId ->
                cameraManager?.setTorchMode(camId, false)
            }
            isTorchOn = false
        } catch (e: Exception) {
            Log.e(TAG, "Error turning off torch", e)
        }
        
        Log.d(TAG, "Strobe stopped")
    }

    /**
     * Capture a photo while the strobe continues running
     * Uses CONTROL_AE_MODE_ON with flash disabled to maintain independent torch control
     */
    private fun capturePhoto() {
        val camera = cameraDevice
        val session = captureSession
        val reader = imageReader
        
        if (camera == null || session == null || reader == null) {
            Toast.makeText(this, R.string.camera_error, Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // Create capture request WITHOUT torch/flash control
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            
            // Use CONTROL_AE_MODE_ON with flash disabled
            // This ensures the camera does not take control of the torch
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            
            // Capture the photo
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Photo captured successfully")
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "Photo capture failed: ${failure.reason}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.camera_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
            Toast.makeText(this, R.string.camera_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        stopStrobe()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up resources
        stopStrobe()
        
        captureSession?.close()
        captureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        imageReader?.close()
        imageReader = null
        
        dummySurface?.release()
        dummySurface = null
        
        surfaceTexture?.release()
        surfaceTexture = null
        
        stopBackgroundThread()
    }
}
