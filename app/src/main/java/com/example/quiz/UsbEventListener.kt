package com.example.quiz

import android.hardware.usb.UsbDevice

interface UsbEventListener {
    fun setupUsbConnection(device: UsbDevice)
    fun onUsbDeviceDetached()
}
