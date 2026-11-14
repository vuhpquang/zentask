import CoreLocation
import CoreWLAN
import UserNotifications
@preconcurrency import ZenTaskShared

/// Location reminders on the Mac, by Wi‑Fi only: joining a place's Wi‑Fi = arriving, leaving it = leaving.
/// Reading the Wi‑Fi name needs Location Services permission on macOS. The "inside" set is kept in UserDefaults and
/// only its changes notify (being on the Wi‑Fi already at launch isn't "arriving").
@MainActor
@Observable
final class WifiPlaces: NSObject, CWEventDelegate, CLLocationManagerDelegate {
    static let shared = WifiPlaces()

    private var core: ZenTaskCore?
    private let location = CLLocationManager()
    private(set) var authorized = false
    private(set) var ssid: String?
    private static let insideKey = "location.insideWifi"

    func start(core: ZenTaskCore) {
        guard self.core == nil else { return }
        self.core = core
        location.delegate = self
        authorized = Self.isAuthorized(location.authorizationStatus)
        CWWiFiClient.shared().delegate = self
        try? CWWiFiClient.shared().startMonitoringEvent(with: .ssidDidChange)
        check(notify: false)
    }

    /// Asks macOS for Location Services (needed to read the Wi‑Fi name).
    func requestPermission() { location.requestWhenInUseAuthorization() }

    nonisolated private static func isAuthorized(_ s: CLAuthorizationStatus) -> Bool { s == .authorizedAlways || s == .authorized }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let ok = Self.isAuthorized(manager.authorizationStatus)
        _Concurrency.Task { @MainActor in self.authorized = ok; self.check(notify: false) }
    }

    nonisolated func ssidDidChangeForWiFiInterface(withName interfaceName: String) {
        _Concurrency.Task { @MainActor in self.check(notify: true) }
    }

    /// Compares the Wi‑Fi's place with the stored inside-set; notifies arrivals / departures when [notify].
    func check(notify: Bool) {
        guard let core else { return }
        ssid = CWWiFiClient.shared().interface()?.ssid()
        let place = LocationPlanner.shared.placeForWifi(places: core.placeActions.list(), ssid: ssid)
        let before = Set(UserDefaults.standard.stringArray(forKey: Self.insideKey) ?? [])
        let after: Set<String> = place.map { [$0.id] } ?? []
        UserDefaults.standard.set(Array(after), forKey: Self.insideKey)
        guard notify else { return }
        for change in LocationPlanner.shared.changes(before: before, after: after) {
            guard let placeId = change.first as String?, let trigger = change.second as String?,
                  let hit = core.locationReminder(placeId: placeId, trigger: trigger), let text = hit.second else { continue }
            let content = UNMutableNotificationContent()
            content.title = text.first as String? ?? ""
            content.body = text.second as String? ?? ""
            content.sound = .default
            if let tasks = hit.first as? [ZTask], tasks.count == 1 { content.userInfo = ["kind": "task", "taskId": tasks[0].id] }
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "zt.place.\(placeId).\(trigger)", content: content, trigger: nil))
        }
    }
}
