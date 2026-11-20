package id.ojolmitra.driver

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun formatClock(epochMillis: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm"
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

actual val platformName: String
    get() =
    "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"
