import Foundation

/// Where the x_radar backend lives. Built from the app's Info.plist (`XRBackendURL`).
public struct BackendConfiguration: Sendable, Equatable {
    public let baseURL: URL

    public init(baseURL: URL) {
        self.baseURL = baseURL
    }

    /// Parses a configured value; nil unless it is an absolute http(s) URL with a host.
    public init?(string: String?) {
        guard let trimmed = string?.trimmingCharacters(in: .whitespacesAndNewlines),
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = url.host(), !host.isEmpty
        else { return nil }
        self.baseURL = url
    }

    /// The live backend (Tailscale Funnel on the VPS) — same as the Android build.
    public static let production = BackendConfiguration(
        baseURL: URL(string: "https://debian.taila9954f.ts.net/")!
    )
}
