import AuthenticationServices
import CryptoKit
import HypemanKit
import OSLog
import SwiftUI

/// Sign in with Apple, and out again, or delete the account. Goals stay on the phone either way.
struct AccountSheet: View {
    @Environment(\.colorScheme) private var colorScheme
    @State private var account = Account.shared
    @State private var me: Me?
    @State private var nonce = Nonce()
    @State private var busy = false
    @State private var confirmDelete = false
    @State private var failure: String?

    private let log = Logger(subsystem: "com.hypeman.app", category: "AccountSheet")

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("ACCOUNT")
                .font(Theme.display(36))
                .accessibilityAddTraits(.isHeader)
            if account.isSignedIn {
                signedIn
            } else {
                signedOut
            }
        }
        .padding(EdgeInsets(top: 28, leading: 20, bottom: 12, trailing: 20))
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .presentationDetents([.medium])
        .disabled(busy)
        .task(id: account.isSignedIn) { await loadMe() }
        .confirmationDialog("Delete your account?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete account", role: .destructive) { Task { await deleteAccount() } }
        } message: {
            Text("You’ll confirm with Apple. Your account goes now and everything the server keeps for it within 30 days. Your goals stay on this iPhone.")
        }
        .alert("Something went wrong", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Sign in to keep things that belong to you, not this phone. Your goals stay on this iPhone either way.")
                .font(Theme.text(16, relativeTo: .subheadline))
                .foregroundStyle(Theme.muted)
            SignInWithAppleButton(.signIn) { request in
                nonce = Nonce()
                request.requestedScopes = [.email]
                request.nonce = nonce.hashed
            } onCompletion: { result in
                Task { await signIn(result) }
            }
            .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
            .frame(height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }

    private var signedIn: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text("SIGNED IN WITH APPLE")
                    .font(Theme.text(12, weight: .bold, relativeTo: .caption))
                    .kerning(1.2)
                    .foregroundStyle(Theme.muted)
                Text(email ?? " ")
                    .font(Theme.text(17, weight: .bold))
                    .lineLimit(1)
                    .redacted(reason: me == nil ? .placeholder : [])
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
            Button {
                Task {
                    busy = true
                    await account.signOut()
                    busy = false
                }
            } label: {
                Text("SIGN OUT")
                    .font(Theme.display(22, relativeTo: .headline))
                    .kerning(1.3)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.ink, lineWidth: 1.5))
            }
            .buttonStyle(.plain)
            Button("Delete account", role: .destructive) { confirmDelete = true }
                .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
    }

    /// Apple hides the address when the user chooses to, giving a relay address instead; that's still what we show.
    private var email: String? {
        guard let me else { return nil }
        return me.identities.first { $0.provider == "APPLE" }?.email ?? "Apple ID"
    }

    private func signIn(_ result: Result<ASAuthorization, Error>) async {
        do {
            let credential = try appleCredential(result.get())
            busy = true
            defer { busy = false }
            try await account.signInWithApple(identityToken: credential.token, nonce: nonce.raw, appleUserID: credential.user)
            // Purchases this Apple ID already made, e.g. before reinstalling.
            try? await Store.shared.reportCurrentEntitlements()
        } catch {
            show(error)
        }
    }

    private func deleteAccount() async {
        do {
            let authorization = try await AppleReauthorization.run()
            guard let code = (authorization.credential as? ASAuthorizationAppleIDCredential)?.authorizationCode else {
                throw APIError(code: "NO_AUTHORIZATION_CODE", message: "Apple didn’t confirm. Please try again.", requestId: nil)
            }
            busy = true
            defer { busy = false }
            try await account.deleteAccount(appleAuthorizationCode: String(decoding: code, as: UTF8.self))
        } catch {
            show(error)
        }
    }

    private func loadMe() async {
        me = nil
        guard account.isSignedIn else { return }
        do {
            me = try await account.me()
        } catch let error as APIError where error.endsSession {
            // The account now shows as signed out; say why.
            failure = error.message
        } catch {
            log.error("Could not load the account: \(error)")
        }
    }

    private func appleCredential(_ authorization: ASAuthorization) throws -> (token: String, user: String) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let token = credential.identityToken.map({ String(decoding: $0, as: UTF8.self) }) else {
            throw APIError(code: "NO_IDENTITY_TOKEN", message: "Apple didn’t sign you in. Please try again.", requestId: nil)
        }
        return (token, credential.user)
    }

    /// Cancelling Apple's sheet isn't a failure.
    private func show(_ error: Error) {
        if (error as? ASAuthorizationError)?.code == .canceled { return }
        log.error("Account action failed: \(error)")
        failure = (error as? APIError)?.message ?? "Please try again."
    }
}

/// The random value Apple signs into the identity token, so a stolen token can't be replayed. Apple gets its SHA-256;
/// the server gets the raw value and checks the two match.
private struct Nonce {
    let raw: String

    init() {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        raw = Data(bytes).base64EncodedString()
    }

    var hashed: String {
        SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// Sign in with Apple again without the button, to get the fresh authorization code account deletion needs.
@MainActor
private final class AppleReauthorization: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    /// The controller holds its delegate weakly, so the one in progress is kept here until Apple answers.
    private static var inProgress: AppleReauthorization?

    private let controller = ASAuthorizationController(authorizationRequests: [ASAuthorizationAppleIDProvider().createRequest()])
    private var continuation: CheckedContinuation<ASAuthorization, Error>?

    static func run() async throws -> ASAuthorization {
        let reauthorization = AppleReauthorization()
        inProgress = reauthorization
        return try await withCheckedThrowingContinuation { continuation in
            reauthorization.continuation = continuation
            reauthorization.controller.delegate = reauthorization
            reauthorization.controller.presentationContextProvider = reauthorization
            reauthorization.controller.performRequests()
        }
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        finish(.success(authorization))
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        finish(.failure(error))
    }

    private func finish(_ result: Result<ASAuthorization, Error>) {
        continuation?.resume(with: result)
        continuation = nil
        Self.inProgress = nil
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
