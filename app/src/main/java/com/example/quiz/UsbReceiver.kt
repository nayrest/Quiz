package com.example.quiz

import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbReceiver : BroadcastReceiver() { // Явное указание первичного конструктора
    private var listener: UsbEventListener? = null

    // Метод для установки listener
    fun setListener(listener: UsbEventListener) {
        this.listener = listener
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { listener?.setupUsbConnection(it) }
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> listener?.onUsbDeviceDetached()
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.quiz.USB_PERMISSION"
    }
}
