import Foundation

enum ApiError: LocalizedError {
    case server(status: Int, error: String, message: String)
    case network(String)
    case decode

    var errorDescription: String? {
        switch self {
        case .server(_, _, let message): return message
        case .network(let s): return s
        case .decode: return "伺服器回應格式不對"
        }
    }
}

final class ApiClient {
    static let shared = ApiClient()

    func login(baseUrl: String, username: String, password: String, company: String?) async throws -> (token: String, user: [String: Any]) {
        var body: [String: Any] = ["username": username, "password": password]
        if let company, !company.isEmpty { body["company"] = company }
        let json = try await request(baseUrl: baseUrl, method: "POST", path: "/api/login", body: body, token: nil)
        guard let token = json["token"] as? String, !token.isEmpty,
              let user = json["user"] as? [String: Any] else { throw ApiError.decode }
        return (token, user)
    }

    func logout(baseUrl: String, token: String) async {
        _ = try? await request(baseUrl: baseUrl, method: "POST", path: "/api/logout", body: [:], token: token)
    }

    func request(baseUrl: String, method: String, path: String, body: [String: Any]?, token: String?) async throws -> [String: Any] {
        guard let url = URL(string: baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) ?? URL(string: baseUrl + path) else {
            throw ApiError.network("網址無效")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let token, !token.isEmpty { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body, method != "GET" { req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let http = resp as? HTTPURLResponse
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let code = http?.statusCode ?? 0
        if code < 200 || code >= 300 {
            throw ApiError.server(status: code, error: obj["error"] as? String ?? "http_\(code)",
                                  message: obj["message"] as? String ?? "HTTP \(code)")
        }
        return obj
    }

    static func wsURL(from httpUrl: String) -> URL? {
        var u = httpUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while u.hasSuffix("/") { u.removeLast() }
        if u.lowercased().hasPrefix("https://") { u = "wss://" + u.dropFirst(8) }
        else if u.lowercased().hasPrefix("http://") { u = "ws://" + u.dropFirst(7) }
        else if !(u.lowercased().hasPrefix("wss://") || u.lowercased().hasPrefix("ws://")) {
            u = "wss://" + u
        }
        return URL(string: u + "/ws")
    }
}
