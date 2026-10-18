import Testing
@testable import HypemanKit

@Test func rolesMatchBackendNames() {
    #expect(ClipRole.allCases.map(\.rawValue) == ["START", "REMINDER", "NAG", "CONGRATS"])
}
