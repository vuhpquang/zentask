import AppKit
import Foundation
import Observation
import Security

/// In-app update from GitHub Releases (github.com/vuhpquang/zentask), adapted from LaunchApp's updater.
///
/// 1. `check()` reads `/releases/latest` and compares its tag (`v1.2.3`) with the bundle version.
/// 2. `install()` downloads `ZenTask-X.Y.Z.zip`, unpacks it, checks the app inside (same bundle id, valid
///    signature, same signer as this copy), then a small shell script waits for ZenTask to quit, swaps the
///    bundle in place and opens the new one.
///
/// Checks run 10 s after launch and every 6 hours while "Check for updates automatically" is on.
@MainActor
@Observable
final class Updater {
    static let shared = Updater()
    static let repo = "vuhpquang/zentask"
    static let autoCheckKey = "updates.autoCheck"

    enum State: Equatable {
        case idle, checking, upToDate
        case available(version: String)
        case downloading, installing
        case failed(String)
    }

    private(set) var state: State = .idle
    private(set) var lastChecked: Date?
    private(set) var releaseNotes = ""
    @ObservationIgnored private var assetURL: URL?
    @ObservationIgnored private var timer: Timer?

    var currentVersion: String {
        #if DEBUG
        if let v = ProcessInfo.processInfo.environment["ZENTASK_PRETEND_VERSION"] { return v }
        #endif
        return AppInfo.bundleVersion
    }

    private init() {}

    // MARK: Scheduling

    func startAutomaticChecks() {
        UserDefaults.standard.register(defaults: [Self.autoCheckKey: true])
        timer?.invalidate()
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            MainActor.assumeIsolated { self?.autoCheck() }
        }
        timer = Timer.scheduledTimer(withTimeInterval: 6 * 3600, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.autoCheck() }
        }
    }

    private func autoCheck() {
        guard UserDefaults.standard.bool(forKey: Self.autoCheckKey) else { return }
        // Don't replace a "ready to install" or an error the user hasn't seen with a background result.
        if case .available = state { return }
        Task { await check() }
    }

    // MARK: Check

    private struct Release: Decodable {
        struct Asset: Decodable {
            let name: String
            let browser_download_url: URL
        }
        let tag_name: String
        let body: String?
        let assets: [Asset]
    }

    func check() async {
        switch state {
        case .checking, .downloading, .installing: return
        default: break
        }
        state = .checking
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/\(Self.repo)/releases/latest")!)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("ZenTask/\(currentVersion)", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            lastChecked = Date()
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                state = .failed("GitHub didn't answer. Try again later.")
                return
            }
            let release = try JSONDecoder().decode(Release.self, from: data)
            let remote = release.tag_name.trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
            guard Self.isVersion(remote, newerThan: currentVersion),
                  let zip = release.assets.first(where: { $0.name == "ZenTask-\(remote).zip" }) else {
                state = .upToDate
                return
            }
            assetURL = zip.browser_download_url
            releaseNotes = release.body ?? ""
            state = .available(version: remote)
        } catch {
            lastChecked = Date()
            state = .failed("Couldn't check for updates: \(error.localizedDescription)")
        }
    }

    /// Numeric dotted comparison; anything after "-" is ignored.
    nonisolated static func isVersion(_ a: String, newerThan b: String) -> Bool {
        func parts(_ v: String) -> [Int] {
            v.split(separator: "-").first.map { $0.split(separator: ".").map { Int($0) ?? 0 } } ?? []
        }
        let pa = parts(a), pb = parts(b)
        for i in 0..<max(pa.count, pb.count) {
            let x = i < pa.count ? pa[i] : 0
            let y = i < pb.count ? pb[i] : 0
            if x != y { return x > y }
        }
        return false
    }

    // MARK: Install

    func install() {
        guard let url = assetURL else { return }
        let target = Bundle.main.bundleURL
        guard target.pathExtension == "app" else {
            state = .failed("ZenTask isn't running from an app bundle")
            return
        }
        guard FileManager.default.isWritableFile(atPath: target.deletingLastPathComponent().path) else {
            state = .failed("No permission to replace \(target.path). Move ZenTask to Applications and try again.")
            return
        }
        state = .downloading
        let bundleId = Bundle.main.bundleIdentifier
        Task {
            do {
                let (location, response) = try await URLSession.shared.download(from: url)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw UpdateError("The download failed") }
                let staged = try await Task.detached { try Self.stage(zipAt: location, bundleId: bundleId) }.value
                relaunch(replacing: target, with: staged)
            } catch {
                state = .failed(error.localizedDescription)
            }
        }
    }

    private struct UpdateError: LocalizedError {
        let errorDescription: String?
        init(_ message: String) { errorDescription = message }
    }

    /// Unpacks the zip into a temporary folder and checks the app inside.
    nonisolated private static func stage(zipAt location: URL, bundleId: String?) throws -> URL {
        let fm = FileManager.default
        let work = fm.temporaryDirectory.appendingPathComponent("ZenTaskUpdate-\(UUID().uuidString)")
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        let zip = work.appendingPathComponent("update.zip")
        try fm.moveItem(at: location, to: zip)
        let unpacked = work.appendingPathComponent("unpacked")
        try run("/usr/bin/ditto", ["-x", "-k", zip.path, unpacked.path])

        guard let appName = try fm.contentsOfDirectory(atPath: unpacked.path).first(where: { $0.hasSuffix(".app") }) else {
            throw UpdateError("The update contains no app")
        }
        let newApp = unpacked.appendingPathComponent(appName)
        guard Bundle(url: newApp)?.bundleIdentifier == bundleId else {
            throw UpdateError("The update is for a different app")
        }
        do {
            try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", newApp.path])
        } catch {
            throw UpdateError("The update's signature is invalid")
        }
        guard satisfiesOwnSigner(newApp) else {
            throw UpdateError("The update isn't signed by the same developer")
        }
        return newApp
    }

    /// A copy signed with a certificate (the release identity) only accepts an update that meets its own
    /// designated requirement. Ad-hoc development builds have no signer to pin.
    nonisolated static func satisfiesOwnSigner(_ candidate: URL) -> Bool {
        var me: SecCode?
        guard SecCodeCopySelf([], &me) == errSecSuccess, let me else { return true }
        var staticMe: SecStaticCode?
        guard SecCodeCopyStaticCode(me, [], &staticMe) == errSecSuccess, let staticMe else { return true }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(staticMe, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let certs = (info as? [String: Any])?[kSecCodeInfoCertificates as String] as? [Any],
              !certs.isEmpty else {
            return true
        }
        var requirement: SecRequirement?
        guard SecCodeCopyDesignatedRequirement(staticMe, [], &requirement) == errSecSuccess, let requirement else { return false }
        var other: SecStaticCode?
        guard SecStaticCodeCreateWithPath(candidate as CFURL, [], &other) == errSecSuccess, let other else { return false }
        return SecStaticCodeCheckValidity(other, SecCSFlags(rawValue: kSecCSCheckAllArchitectures), requirement) == errSecSuccess
    }

    /// Hands the swap to a detached script, then quits so the bundle can be replaced.
    private func relaunch(replacing target: URL, with staged: URL) {
        state = .installing
        let script = """
        while kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null; do sleep 0.2; done
        rm -rf "$1" && /usr/bin/ditto "$2" "$1" && /usr/bin/xattr -dr com.apple.quarantine "$1" 2>/dev/null
        touch "$1"
        /usr/bin/open "$1"
        rm -rf "$3"
        """
        let work = staged.deletingLastPathComponent().deletingLastPathComponent()
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/bin/sh")
        task.arguments = ["-c", script, "zentask-updater", target.path, staged.path, work.path]
        do {
            try task.run()
        } catch {
            state = .failed("Couldn't start the installer: \(error.localizedDescription)")
            return
        }
        NSApp.terminate(nil)
    }

    @discardableResult
    nonisolated private static func run(_ tool: String, _ args: [String]) throws -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        try p.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        let out = String(data: data, encoding: .utf8) ?? ""
        guard p.terminationStatus == 0 else { throw UpdateError("\(tool) failed: \(out)") }
        return out
    }

    #if DEBUG
    /// Check → download → unpack → verify, without replacing anything (`ZENTASK_UPDATE_DRYRUN=1`).
    func dryRun() async -> String {
        await check()
        guard case .available(let v) = state, let url = assetURL else { return "state: \(state)" }
        do {
            let (location, _) = try await URLSession.shared.download(from: url)
            let id = "com.vuhpquang.zentask"
            let staged = try await Task.detached { try Self.stage(zipAt: location, bundleId: id) }.value
            return "available \(v); staged OK at \(staged.path)"
        } catch {
            return "available \(v); stage failed: \(error.localizedDescription)"
        }
    }
    #endif
}
