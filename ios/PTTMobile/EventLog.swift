import Foundation

enum EventLog {
    private static var lines: [String] = []
    private static let lock = NSLock()
    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func add(_ kind: String, _ detail: String = "") {
        let msg = redact("[\(kind)] \(detail)")
        let line = iso.string(from: Date()) + " " + msg
        lock.lock()
        lines.append(line)
        if lines.count > 200 { lines.removeFirst(lines.count - 200) }
        lock.unlock()
    }

    static func dump() -> [String] {
        lock.lock(); defer { lock.unlock() }
        return lines
    }

    static func redact(_ s: String) -> String {
        var out = s
        out = out.replacingOccurrences(of: #"Bearer\s+[A-Za-z0-9._~+/=-]+"#, with: "Bearer ***", options: .regularExpression)
        out = out.replacingOccurrences(of: #""(token|password|oldPassword|newPassword|authorization|cookie)"\s*:\s*"[^"]*""#, with: "\"$1\":\"***\"", options: .regularExpression)
        out = out.replacingOccurrences(of: #"\b(token|password|authorization)=([^\s&]+)"#, with: "$1=***", options: .regularExpression)
        return out
    }
}
