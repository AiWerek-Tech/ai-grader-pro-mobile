package com.wmvaa.aigrader

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.wmvaa.aigrader.databinding.ActivityQrCaptureBinding

class QrCaptureActivity : AppCompatActivity(), DecoratedBarcodeView.TorchListener {

    private lateinit var binding: ActivityQrCaptureBinding
    private lateinit var capture: CaptureManager
    private var isTorchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.barcodeScannerView.setTorchListener(this)

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            binding.btnToggleTorch.visibility = View.GONE
        }

        binding.btnToggleTorch.setOnClickListener {
            if (isTorchOn) {
                binding.barcodeScannerView.setTorchOff()
            } else {
                binding.barcodeScannerView.setTorchOn()
            }
        }

        binding.btnCloseQr.setOnClickListener {
            finish()
        }

        capture = CaptureManager(this, binding.barcodeScannerView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    override fun onTorchOn() {
        isTorchOn = true
        binding.btnToggleTorch.text = "🔦  Matikan Lampu Senter (Flash)"
    }

    override fun onTorchOff() {
        isTorchOn = false
        binding.btnToggleTorch.text = "🔦  Nyalakan Lampu Senter (Flash)"
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return binding.barcodeScannerView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
}
