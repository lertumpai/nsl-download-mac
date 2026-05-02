import Foundation
import UserNotifications

final class NotificationService {
    static let shared = NotificationService()

    func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func notifyCompletion(title: String) {
        send(title: "Download Complete", body: title, identifier: "complete-\(UUID().uuidString)")
    }

    func notifyFailure(title: String, error: String) {
        send(title: "Download Failed", body: "\(title): \(error)", identifier: "fail-\(UUID().uuidString)")
    }

    private func send(title: String, body: String, identifier: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
