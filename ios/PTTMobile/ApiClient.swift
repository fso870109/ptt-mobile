import Foundation
import UIKit

enum ApiErrors {
    static func message(error: String, fallback: String) -> String {
        switch error {
        case "need_org": return fallback.isEmpty ? "請輸入公司代碼" : fallback
        case "invalid_credentials": return fallback.isEmpty ? "帳號、密碼或公司代碼錯誤" : fallback
        case "disabled": return fallback.isEmpty ? "此帳號已停用" : fallback
        case "org_disabled": return fallback.isEmpty ? "此公司已被停用" : fallback
        case "org_expired": return fallback.isEmpty ? "此公司方案已到期，請聯絡平台管理員" : fallback
        case "org_inactive": return fallback.isEmpty ? "此公司已停用或方案到期" : fallback
        case "seat_limit": return fallback.isEmpty ? "已達此公司方案的人數上限" : fallback
        case "too_many_attempts": return fallback.isEmpty ? "嘗試次數過多，請稍後再試" : fallback
        default: return fallback.isEmpty ? error : fallback
        }
    }
}

enum ApiError: LocalizedError {
    case server(status: Int, error: String, message: String)
    case network(String)
    case decode

    var errorDescription: String? {
        switch self {
        case .server(_, let error, let message):
            return ApiErrors.message(error: error, fallback: message)
        case .network(let s): return s
        case .decode: return "伺服器回應格式不對"
        }
    }
}

final class ApiClient {
    static let shared = ApiClient()

    func login(baseUrl: String, username: String, password: String, org: String) async throws -> (token: String, user: [String: Any]) {
        let code = org.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if code.isEmpty {
            throw ApiError.server(status: 400, error: "need_org", message: "請輸入公司代碼")
        }
        let body: [String: Any] = ["username": username, "password": password, "org": code]
        let json = try await request(baseUrl: baseUrl, method: "POST", path: "/api/login", body: body, token: nil)
        guard let token = json["token"] as? String, !token.isEmpty,
              let user = json["user"] as? [String: Any] else { throw ApiError.decode }
        return (token, user)
    }

    func logout(baseUrl: String, token: String) async {
        _ = try? await request(baseUrl: baseUrl, method: "POST", path: "/api/logout", body: [:], token: token)
    }

    func listMessages(baseUrl: String, token: String, conv: String) async throws -> [ChatMessage] {
        let q = conv.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? conv
        let json = try await request(baseUrl: baseUrl, method: "GET", path: "/api/messages?conv=\(q)&limit=100", body: nil, token: token)
        return JSONX.arr(json, "messages").map(JSONX.chat)
    }

    func uploadPhoto(baseUrl: String, token: String, conv: String, jpeg: Data) async throws -> ChatMessage {
        let b64 = jpeg.base64EncodedString()
        let json = try await request(baseUrl: baseUrl, method: "POST", path: "/api/photos",
                                     body: ["conv": conv, "data": b64], token: token)
        guard let obj = json["message"] as? [String: Any] else { throw ApiError.decode }
        return JSONX.chat(obj)
    }

    func getPhoto(baseUrl: String, token: String, photoId: String) async throws -> Data {
        var root = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while root.hasSuffix("/") { root.removeLast() }
        guard let url = URL(string: root + "/api/photos/" + photoId) else {
            throw ApiError.network("網址無效")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code < 200 || code >= 300 {
            throw ApiError.server(status: code, error: "photo", message: "無法載入照片（\(code)）")
        }
        return data
    }

    func request(baseUrl: String, method: String, path: String, body: [String: Any]?, token: String?) async throws -> [String: Any] {
        var root = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while root.hasSuffix("/") { root.removeLast() }
        guard let url = URL(string: root + path) else {
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

    static func compressJPEG(_ data: Data, maxSide: CGFloat = 1280, maxBytes: Int = 200 * 1024) -> Data? {
        guard let src = UIImage(data: data) else { return nil }
        let longest = max(src.size.width, src.size.height)
        let scale = longest > maxSide ? maxSide / longest : 1
        let size = CGSize(width: max(1, src.size.width * scale), height: max(1, src.size.height * scale))
        let renderer = UIGraphicsImageRenderer(size: size)
        let scaled = renderer.image { _ in src.draw(in: CGRect(origin: .zero, size: size)) }
        var q: CGFloat = 0.82
        var out = scaled.jpegData(compressionQuality: q)
        while let bytes = out, bytes.count > maxBytes, q >= 0.4 {
            q -= 0.12
            out = scaled.jpegData(compressionQuality: q)
        }
        if let bytes = out, bytes.count <= 220 * 1024 { return bytes }
        return nil
    }
}

actor PhotoCache {
    static let shared = PhotoCache()
    private var images: [String: UIImage] = [:]

    func image(baseUrl: String, token: String, photoId: String) async -> UIImage? {
        if let hit = images[photoId] { return hit }
        guard let data = try? await ApiClient.shared.getPhoto(baseUrl: baseUrl, token: token, photoId: photoId),
              let img = UIImage(data: data) else { return nil }
        images[photoId] = img
        return img
    }
}
