package io.gigi.cameraimagereader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import io.gigi.cameraimagereader.databinding.ActivityMainBinding
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val cameraOpenCloseLock = Semaphore(1)
    private var imageReader: ImageReader? = null
    private val cameraBackgroundThread = HandlerThread("CameraThread")
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
            openCamera()
        }
    }

    private fun setupCamera() {
        try {
            startCameraBackgroundThread()
            setupCameraOutputs()
        } catch (e: CameraAccessException) {

        }
    }

    private fun setupCameraOutputs() {
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG,  /*maxImages*/2)
        imageReader?.setOnImageAvailableListener(
                onRawImageAvailableListener, cameraHandler)
    }

    private fun startCameraBackgroundThread() {
        cameraBackgroundThread.start()
        cameraHandler = Handler(cameraBackgroundThread.looper)
    }


    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            findCamera(manager, CameraMetadata.LENS_FACING_BACK)?.let { id ->
                manager.openCamera(id, cameraStateCallback, cameraHandler)
            }
        } catch (e: InterruptedException) {
            Log.e(this::class.simpleName, "Error opening camera")
        } catch (e: CameraAccessException) {
            Log.e(this::class.simpleName, "Error opening camera")
        } catch (e: SecurityException) {
            Log.e(this::class.simpleName, "Error opening camera")
        }
    }

    private fun findCamera(manager: CameraManager, cameraFacing: Int): String? {
        return try {
            manager.cameraIdList.first { cameraId ->
                manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == cameraFacing
            }

        } catch (e: CameraAccessException) {
            return null
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.let {
                it.close()
                captureSession = null

            }
            cameraDevice?.let {
                it.close()
                cameraDevice = null
            }
            imageReader?.let {
                it.close()
                imageReader = null
            }

        } catch (e: InterruptedException) {
            throw java.lang.RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }
    private fun startCaptureSession() {
        try {
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD) ?: return
            val targets = listOf(imageReader?.surface)
            targets.forEach { captureRequestBuilder.addTarget(it ?: return@forEach) }

            cameraDevice?.createCaptureSession(
                    targets,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            try {
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
                                captureSession = cameraCaptureSession
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                                return
                            } catch (e: java.lang.IllegalStateException) {
                                e.printStackTrace()
                                return
                            }
                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                            Log.e("TAG", "Failed to configure camera.")
                        }
                    },
                    cameraHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun handleImage(image: Image) {
        binding.cameraView.lockCanvas()?.let { canvas ->
            canvas.drawBitmap(
                    createBitmap(image),
                    null,
                    Rect(0, 0, binding.cameraView.width, binding.cameraView.bottom - binding.cameraView.top),
                    null)

            binding.cameraView.unlockCanvasAndPost(canvas)
        }
        image.close()
    }

    private fun createBitmap(image: Image): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
    }

    private val onRawImageAvailableListener = OnImageAvailableListener { reader ->
        try {
            reader.acquireLatestImage()?.let { image ->
                handleImage(image)

            }
        } catch (e: IllegalStateException) {
        }
    }

    private val cameraStateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@MainActivity.cameraDevice = cameraDevice
            startCaptureSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@MainActivity.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@MainActivity.cameraDevice = null
        }
    }
}