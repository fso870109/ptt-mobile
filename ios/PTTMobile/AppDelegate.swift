import UIKit
import PushToTalk
import AVFoundation

/// PTChannelManager 必須在啟動時盡早建立，系統才能還原頻道並把 PTT 推播交給 App。
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UIDevice.current.isBatteryMonitoringEnabled = true
        PttEngine.shared.prepareAtLaunch()
        return true
    }
}
