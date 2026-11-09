import Foundation
import PushToTalk
import AVFoundation
import UIKit

/// Wraps Apple's PTChannelManager. The system UI (Dynamic Island / lock screen)
/// and Bluetooth PTT accessories all funnel through these delegate callbacks.
final class PttSystemChannel: NSObject, PTChannelManagerDelegate, PTChannelRestorationDelegate {
    static let shared = PttSystemChannel()

    private(set) var manager: PTChannelManager?
    private(set) var joinedUUID: UUID?
    private(set) var joinedName: String?
    private(set) var pushToken: Data?
    var onTransmitBegin: (() -> Void)?
    var onTransmitEnd: (() -> Void)?
    var onAudioActivate: ((AVAudioSession) -> Void)?
    var onAudioDeactivate: ((AVAudioSession) -> Void)?
    var onPushToken: ((Data) -> Void)?
    var onIncomingSpeaker: ((String, UUID, [String: Any]) -> Void)?

    func prepare() {
        if manager != nil { return }
        Task { @MainActor in
            do {
                manager = try await PTChannelManager.channelManager(delegate: self, restorationDelegate: self)
            } catch {
                print("[PTT] PTChannelManager init failed: \(error)")
            }
        }
    }

    /// Must be called from the foreground after explicit user action (joining a channel).
    func join(channelId: String, name: String) {
        let uuid = ChannelUUID.uuid(forChannelId: channelId)
        guard let manager else {
            print("[PTT] manager not ready; will retry join \(channelId)")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                self?.join(channelId: channelId, name: name)
            }
            return
        }
        if joinedUUID == uuid { return }
        if let prev = joinedUUID { manager.requestLeaveChannel(channelUUID: prev) }
        let desc = PTChannelDescriptor(name: name, image: UIImage(systemName: "dot.radiowaves.left.and.right"))
        manager.requestJoinChannel(channelUUID: uuid, descriptor: desc)
        joinedUUID = uuid
        joinedName = name
    }

    func leave() {
        guard let manager, let uuid = joinedUUID else { return }
        manager.requestLeaveChannel(channelUUID: uuid)
        joinedUUID = nil
        joinedName = nil
    }

    func requestBeginTransmit() {
        guard let manager, let uuid = joinedUUID else { return }
        manager.requestBeginTransmitting(channelUUID: uuid)
    }

    func requestEndTransmit() {
        guard let manager, let uuid = joinedUUID else { return }
        manager.stopTransmitting(from: uuid)
    }

    func setRemoteSpeaker(name: String?) {
        guard let manager, let uuid = joinedUUID else { return }
        if let name {
            manager.setActiveRemoteParticipant(PTParticipant(name: name, image: nil), channelUUID: uuid)
        } else {
            manager.setActiveRemoteParticipant(nil, channelUUID: uuid)
        }
    }

    // MARK: PTChannelManagerDelegate

    func channelManager(_ channelManager: PTChannelManager, didJoinChannel channelUUID: UUID, reason: PTChannelJoinReason) {
        joinedUUID = channelUUID
    }

    func channelManager(_ channelManager: PTChannelManager, didLeaveChannel channelUUID: UUID, reason: PTChannelLeaveReason) {
        if joinedUUID == channelUUID {
            joinedUUID = nil
            joinedName = nil
        }
    }

    func channelManager(_ channelManager: PTChannelManager, receivedEphemeralPushToken pushToken: Data) {
        self.pushToken = pushToken
        onPushToken?(pushToken)
    }

    func incomingPushResult(channelManager: PTChannelManager, channelUUID: UUID, pushPayload: [String: Any]) -> PTPushResult {
        onIncomingSpeaker?(pushPayload["activeSpeaker"] as? String ?? "", channelUUID, pushPayload)
        if let speaker = pushPayload["activeSpeaker"] as? String, !speaker.isEmpty {
            return .activeRemoteParticipant(PTParticipant(name: speaker, image: nil))
        }
        // Do NOT return .leaveChannel just because a talk ended — that would drop the system channel.
        return .activeRemoteParticipant(PTParticipant(name: "對講", image: nil))
    }

    func channelManager(_ channelManager: PTChannelManager, failedToJoinChannel channelUUID: UUID, error: Error) {
        print("[PTT] join failed: \(error)")
    }

    func channelManager(_ channelManager: PTChannelManager, channelUUID: UUID, didBeginTransmittingFrom source: PTChannelTransmitRequestSource) {
        onTransmitBegin?()
    }

    func channelManager(_ channelManager: PTChannelManager, channelUUID: UUID, didEndTransmittingFrom source: PTChannelTransmitRequestSource) {
        onTransmitEnd?()
    }

    func channelManager(_ channelManager: PTChannelManager, didActivate audioSession: AVAudioSession) {
        onAudioActivate?(audioSession)
    }

    func channelManager(_ channelManager: PTChannelManager, didDeactivate audioSession: AVAudioSession) {
        onAudioDeactivate?(audioSession)
    }

    // MARK: Restoration

    func channelDescriptor(restoredChannelUUID channelUUID: UUID) -> PTChannelDescriptor {
        PTChannelDescriptor(name: joinedName ?? "一鍵對講", image: UIImage(systemName: "dot.radiowaves.left.and.right"))
    }
}
