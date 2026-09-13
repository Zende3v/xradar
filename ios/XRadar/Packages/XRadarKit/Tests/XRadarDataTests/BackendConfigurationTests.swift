import Foundation
import Testing
@testable import XRadarData

struct BackendConfigurationTests {
    @Test func acceptsAbsoluteHTTPSURL() throws {
        let configuration = try #require(BackendConfiguration(string: " https://debian.taila9954f.ts.net/ "))
        #expect(configuration.baseURL.host() == "debian.taila9954f.ts.net")
        #expect(configuration.baseURL.scheme == "https")
    }

    @Test(arguments: [nil, "", "   ", "debian.taila9954f.ts.net", "ftp://host/", "https://"] as [String?])
    func rejectsInvalidValues(_ value: String?) {
        #expect(BackendConfiguration(string: value) == nil)
    }

    @Test func productionPointsAtTheVPS() {
        #expect(BackendConfiguration.production.baseURL.absoluteString == "https://debian.taila9954f.ts.net/")
    }
}
