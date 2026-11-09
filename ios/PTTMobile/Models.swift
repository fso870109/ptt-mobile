import Foundation
import CryptoKit

struct User: Identifiable, Equatable {
    let id: String
    let username: String
    let displayName: String
    let role: String
    let disabled: Bool
}

struct Channel: Identifiable, Equatable {
    let id: String
    let name: String
    let open: Bool
    let myRole: String
    let myRoleLabel: String
    let canTalk: Bool
    let online: Int
    let last: Int64
    let lastTalk: LastTalk?
}

struct LastTalk: Equatable {
    let from: String
    let fromName: String
    let toName: String?
    let ts: Int64
    let durMs: Int64
}

struct Member: Identifiable, Equatable {
    let id: String
    let name: String
    let role: String
}

struct OnlineUser: Identifiable, Equatable {
    let id: String
    let name: String
    let channel: String?
}

struct DmSummary: Identifiable, Equatable {
    var id: String { conv }
    let conv: String
    let peerId: String
    let peerName: String
    let last: Int64
}

struct LocEntry: Identifiable, Equatable {
    let id: String
    let name: String
    let online: Bool
    let channel: String?
    let channelName: String?
    let lat: Double
    let lng: Double
    let acc: Int?
    let battery: Int?
    let charging: Bool?
    let ts: Int64
}

struct AlertInfo: Identifiable, Equatable {
    let id: String
    let userId: String
    let name: String
    let channel: String?
    let channelName: String?
    let lat: Double?
    let lng: Double?
    let acc: Int?
    let ts: Int64
}

struct TalkInfo: Equatable {
    let from: String
    let fromName: String
    let streamId: Int
    let conv: String
}

enum TalkTarget: Equatable {
    case channel(id: String, name: String)
    case dm(peerId: String, peerName: String, conv: String)

    var conv: String {
        switch self {
        case .channel(let id, _): return id
        case .dm(_, _, let conv): return conv
        }
    }

    var label: String {
        switch self {
        case .channel(_, let name): return "# \(name)"
        case .dm(_, let peerName, _): return "🔒 私人對講：\(peerName)"
        }
    }
}

enum JSONX {
    static func str(_ o: [String: Any], _ k: String) -> String? {
        guard let v = o[k], !(v is NSNull) else { return nil }
        return v as? String ?? "\(v)"
    }
    static func int(_ o: [String: Any], _ k: String) -> Int? {
        guard let v = o[k], !(v is NSNull) else { return nil }
        if let i = v as? Int { return i }
        if let n = v as? NSNumber { return n.intValue }
        return nil
    }
    static func int64(_ o: [String: Any], _ k: String) -> Int64? {
        guard let v = o[k], !(v is NSNull) else { return nil }
        if let i = v as? Int64 { return i }
        if let n = v as? NSNumber { return n.int64Value }
        return nil
    }
    static func dbl(_ o: [String: Any], _ k: String) -> Double? {
        guard let v = o[k], !(v is NSNull) else { return nil }
        if let d = v as? Double { return d }
        if let n = v as? NSNumber { return n.doubleValue }
        return nil
    }
    static func bool(_ o: [String: Any], _ k: String) -> Bool? {
        guard let v = o[k], !(v is NSNull) else { return nil }
        return v as? Bool
    }
    static func obj(_ o: [String: Any], _ k: String) -> [String: Any]? { o[k] as? [String: Any] }
    static func arr(_ o: [String: Any], _ k: String) -> [[String: Any]] {
        o[k] as? [[String: Any]] ?? []
    }

    static func user(_ o: [String: Any]) -> User {
        User(id: str(o, "id") ?? "", username: str(o, "username") ?? "",
             displayName: str(o, "displayName") ?? "", role: str(o, "role") ?? "user",
             disabled: bool(o, "disabled") ?? false)
    }
    static func lastTalk(_ o: [String: Any]?) -> LastTalk? {
        guard let o else { return nil }
        return LastTalk(from: str(o, "from") ?? "", fromName: str(o, "fromName") ?? "",
                        toName: str(o, "toName"), ts: int64(o, "ts") ?? 0, durMs: int64(o, "durMs") ?? 0)
    }
    static func channel(_ o: [String: Any]) -> Channel {
        Channel(id: str(o, "id") ?? "", name: str(o, "name") ?? "", open: bool(o, "open") ?? false,
                myRole: str(o, "myRole") ?? "member", myRoleLabel: str(o, "myRoleLabel") ?? "",
                canTalk: bool(o, "canTalk") ?? true, online: int(o, "online") ?? 0,
                last: int64(o, "last") ?? 0, lastTalk: lastTalk(obj(o, "lastTalk")))
    }
    static func member(_ o: [String: Any]) -> Member {
        Member(id: str(o, "id") ?? "", name: str(o, "name") ?? "", role: str(o, "role") ?? "")
    }
    static func online(_ o: [String: Any]) -> OnlineUser {
        OnlineUser(id: str(o, "id") ?? "", name: str(o, "name") ?? "", channel: str(o, "channel"))
    }
    static func dm(_ o: [String: Any]) -> DmSummary {
        DmSummary(conv: str(o, "conv") ?? "", peerId: str(o, "peerId") ?? "",
                  peerName: str(o, "peerName") ?? "", last: int64(o, "last") ?? 0)
    }
    static func loc(_ o: [String: Any]) -> LocEntry {
        LocEntry(id: str(o, "id") ?? "", name: str(o, "name") ?? "", online: bool(o, "online") ?? false,
                 channel: str(o, "channel"), channelName: str(o, "channelName"),
                 lat: dbl(o, "lat") ?? 0, lng: dbl(o, "lng") ?? 0, acc: int(o, "acc"),
                 battery: int(o, "battery"), charging: bool(o, "charging"), ts: int64(o, "ts") ?? 0)
    }
    static func alert(_ o: [String: Any]) -> AlertInfo {
        AlertInfo(id: str(o, "id") ?? "", userId: str(o, "userId") ?? "", name: str(o, "name") ?? "",
                  channel: str(o, "channel"), channelName: str(o, "channelName"),
                  lat: dbl(o, "lat"), lng: dbl(o, "lng"), acc: int(o, "acc"), ts: int64(o, "ts") ?? 0)
    }
}

enum ChannelUUID {
    /// 由頻道 id 決定性產生 UUID v5，App 與（未來的）伺服器可用同一算法對應系統 PTT 頻道。
    static func uuid(forChannelId id: String) -> UUID {
        let ns = UUID(uuidString: "6ba7b810-9dad-11d1-80b4-00c04fd430c8")!
        return uuidV5(namespace: ns, name: "app.ptt.mobile.channel.\(id)")
    }

    static func uuidV5(namespace: UUID, name: String) -> UUID {
        var nsBytes = [UInt8](repeating: 0, count: 16)
        withUnsafeBytes(of: namespace.uuid) { buf in
            for i in 0..<16 { nsBytes[i] = buf[i] }
        }
        var data = Data(nsBytes)
        data.append(contentsOf: name.utf8)
        let digest = Insecure.SHA1.hash(data: data)
        var bytes = Array(digest)
        bytes[6] = (bytes[6] & 0x0F) | 0x50
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}
