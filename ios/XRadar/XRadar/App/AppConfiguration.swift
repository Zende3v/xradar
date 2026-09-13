import Foundation
import XRadarData

/// Build-time configuration, injected through the xcconfig files into Info.plist.
struct AppConfiguration {
    let backend: BackendConfiguration
    /// Stadia Maps key for the basemap styles; nil when not configured.
    let stadiaAPIKey: String?

    init(info: [String: Any]) {
        backend = BackendConfiguration(string: info["XRBackendURL"] as? String) ?? .production
        let key = (info["XRStadiaAPIKey"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        stadiaAPIKey = key.isEmpty ? nil : key
    }

    static let current = AppConfiguration(info: Bundle.main.infoDictionary ?? [:])
}
