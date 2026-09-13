import Foundation
import Testing
@testable import XRadar

@MainActor
struct AppConfigurationTests {
    @Test func readsBackendAndStadiaKey() {
        let configuration = AppConfiguration(info: [
            "XRBackendURL": "https://example.org/",
            "XRStadiaAPIKey": "abc123",
        ])
        #expect(configuration.backend.baseURL.absoluteString == "https://example.org/")
        #expect(configuration.stadiaAPIKey == "abc123")
    }

    @Test func fallsBackWhenValuesAreMissingOrBlank() {
        let configuration = AppConfiguration(info: ["XRBackendURL": "", "XRStadiaAPIKey": "  "])
        #expect(configuration.backend == .production)
        #expect(configuration.stadiaAPIKey == nil)
    }

    @Test func bundleCarriesTheBackendURL() {
        #expect(AppConfiguration.current.backend.baseURL.scheme == "https")
    }
}
