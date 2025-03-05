import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

object UsbManagerSingleton {
    var usbConnection: UsbDeviceConnection? = null
    var usbEndpoint: UsbEndpoint? = null
}