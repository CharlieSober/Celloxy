# Celloxy - Camera2 Strobe Flash App

A precise Android flashlight strobe application built with Kotlin and the Camera2 API.

## Features

### Camera2 API Implementation
- **Camera2 API**: Uses the modern Camera2 API (not legacy Camera API)
- **Independent Torch Control**: Implements flashlight control using `CameraManager.setTorchMode()`
- **Photo Capture**: Captures photos without interfering with strobe operation
- **CameraCaptureSession**: Properly configured capture session with CONTROL_AE_MODE_ON

### Precise Strobe Effect
- **Timing**: 16.66ms ON/OFF intervals (60 Hz strobe frequency)
- **Background Thread**: Uses `HandlerThread` for precise timing control
- **Continuous Operation**: Strobe continues running even during photo capture
- **Independent Control**: Torch operates independently from camera capture

### User Interface
- **Start/Stop Button**: Toggle the strobe effect on and off
- **Capture Button**: Take photos while strobe is running
- **Image Preview**: Display captured photos in an ImageView

## Technical Implementation

### MainActivity.kt

The main activity implements:

1. **Permission Handling**
   - Requests CAMERA permissions at runtime
   - Checks for flash hardware availability

2. **Camera Setup**
   - Opens camera device using Camera2 API
   - Creates CameraCaptureSession for photo capture
   - Sets up ImageReader for captured images

3. **Strobe Control**
   - Dedicated HandlerThread for strobe timing
   - Uses `CameraManager.setTorchMode()` for torch control
   - Toggles every 16ms (approximately 16.66ms)
   - Runs independently from camera operations

4. **Photo Capture**
   - Creates still capture request with `TEMPLATE_STILL_CAPTURE`
   - Uses `CONTROL_AE_MODE_ON` with `FLASH_MODE_OFF`
   - Ensures torch control remains independent
   - Displays captured photo in ImageView

### Key Code Components

#### Strobe Loop (HandlerThread)
```kotlin
private fun strobeLoop(manager: CameraManager, camId: String) {
    strobeHandler?.post(object : Runnable {
        override fun run() {
            if (!isStrobeRunning) return
            
            // Toggle torch state
            isTorchOn = !isTorchOn
            manager.setTorchMode(camId, isTorchOn)
            
            // Schedule next toggle after 16ms
            strobeHandler?.postDelayed(this, STROBE_INTERVAL_MS)
        }
    })
}
```

#### Photo Capture (Independent of Torch)
```kotlin
private fun capturePhoto() {
    val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
    captureBuilder.addTarget(reader.surface)
    
    // Use CONTROL_AE_MODE_ON with flash disabled
    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
    
    session.capture(captureBuilder.build(), callback, backgroundHandler)
}
```

### Layout (activity_main.xml)

The UI layout includes:
- **ImageView**: Displays captured photos (fills most of the screen)
- **Toggle Strobe Button**: Starts/stops the strobe effect
- **Capture Photo Button**: Takes a photo while strobe runs
- **ConstraintLayout**: Responsive layout for different screen sizes

## Project Structure

```
Celloxy/
├── app/
│   ├── build.gradle                 # App-level Gradle configuration
│   ├── proguard-rules.pro          # ProGuard rules
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml # App manifest with permissions
│           ├── java/com/charliesober/celloxy/
│           │   └── MainActivity.kt  # Main activity implementation
│           └── res/
│               ├── layout/
│               │   └── activity_main.xml    # UI layout
│               └── values/
│                   └── strings.xml          # String resources
├── build.gradle                    # Project-level Gradle configuration
├── settings.gradle                 # Gradle settings
└── gradle.properties              # Gradle properties
```

## Requirements

### Android SDK
- **Minimum SDK**: 21 (Android 5.0 Lollipop)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 34

### Dependencies
- Kotlin 1.9.0
- AndroidX Core KTX 1.12.0
- AndroidX AppCompat 1.6.1
- Material Components 1.10.0
- ConstraintLayout 2.1.4

### Permissions
- `android.permission.CAMERA` - Required for camera access
- `android.permission.FLASHLIGHT` - Required for flashlight control

### Hardware Features
- `android.hardware.camera` - Required
- `android.hardware.camera.flash` - Required

## Building the App

1. **Open in Android Studio**
   ```bash
   # Open the project in Android Studio
   studio /path/to/Celloxy
   ```

2. **Sync Gradle**
   - Android Studio will automatically sync Gradle dependencies

3. **Build APK**
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on Device**
   ```bash
   ./gradlew installDebug
   ```

## Usage

1. **Launch the App**
   - Grant camera permissions when prompted

2. **Start Strobe**
   - Tap "Start Strobe" button
   - Flashlight will begin strobing at 60 Hz (16.66ms intervals)

3. **Capture Photo**
   - While strobe is running, tap "Capture Photo"
   - Photo appears in the ImageView above
   - Strobe continues without interruption

4. **Stop Strobe**
   - Tap "Stop Strobe" to turn off the flashlight

## Technical Details

### Why HandlerThread?
- Provides precise timing control on a dedicated background thread
- Better than Timer which has less precise timing guarantees
- Allows proper cleanup and lifecycle management

### Why Independent Torch Control?
- Using `CameraManager.setTorchMode()` keeps torch control separate from camera operations
- Photo capture uses `FLASH_MODE_OFF` to prevent camera from controlling the flash
- This ensures strobe continues uninterrupted during photo capture

### Timing Precision
- **Target**: 16.66ms (60 Hz strobe frequency)
- **Implementation**: 16ms intervals
- **Actual Frequency**: ~62.5 Hz (approximately 4% faster than target)
- **Note**: The 16ms interval is used instead of 16.66ms because Handler.postDelayed() accepts long integers. This results in a slightly faster strobe rate. The difference is minimal and acceptable for most use cases.
- **Precision depends on**: Device scheduler, system load, and Android's message queue processing

### Thread Safety
- All camera operations on dedicated background thread
- UI updates using `runOnUiThread()`
- Proper synchronization for strobe control

## Lifecycle Management

The app properly handles lifecycle events:
- **onPause()**: Stops strobe when app is paused
- **onDestroy()**: Cleans up all resources:
  - Stops strobe thread
  - Closes camera session
  - Closes camera device
  - Releases ImageReader
  - Stops background threads

## Troubleshooting

### No Flash Error
- Device must have a hardware flash
- Check `FLASH_INFO_AVAILABLE` in camera characteristics

### Permission Denied
- App requires CAMERA permission
- Grant permission in device settings if denied

### Camera Error
- Ensure no other app is using the camera
- Check logcat for detailed error messages
- Try restarting the app

## License

This project is provided as-is for educational and development purposes.
