import AVFoundation
import Foundation

/// 16 kHz s16le mono capture + playback. Used both with the system PTT audio session
/// (didActivate / didDeactivate) and the simulator fallback session.
final class PcmAudio {
    static let sampleRate = 16000
    static let frameSamples = 1600 // 100ms

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var converter: AVAudioConverter?
    private var started = false
    private var nextTime: AVAudioTime?
    var onCapture: ((Data) -> Void)?

    func startEngine() throws {
        if started { return }
        let session = AVAudioSession.sharedInstance()
        // When PushToTalk already activated the session, this is a no-op / may fail; ignore.
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP])
        try? session.setActive(true, options: [])

        let input = engine.inputNode
        let inFmt = input.outputFormat(forBus: 0)
        guard let outFmt = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(Self.sampleRate), channels: 1, interleaved: true) else {
            throw NSError(domain: "PcmAudio", code: 1)
        }
        converter = AVAudioConverter(from: inFmt, to: outFmt)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 2048, format: inFmt) { [weak self] buffer, _ in
            self?.downsample(buffer, to: outFmt)
        }
        if engine.attachedNodes.contains(player) == false {
            engine.attach(player)
        }
        let playFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(Self.sampleRate), channels: 1, interleaved: false)!
        engine.connect(player, to: engine.mainMixerNode, format: playFmt)
        try engine.start()
        player.play()
        started = true
        nextTime = nil
    }

    func stopEngine() {
        guard started else { return }
        engine.inputNode.removeTap(onBus: 0)
        player.stop()
        engine.stop()
        started = false
        nextTime = nil
    }

    /// Play incoming s16le mono 16k (payload only, no streamId prefix).
    func play(pcm: Data) {
        guard started, !pcm.isEmpty else { return }
        let n = pcm.count / 2
        guard let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(Self.sampleRate), channels: 1, interleaved: false),
              let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(n)),
              let ch = buf.floatChannelData else { return }
        buf.frameLength = AVAudioFrameCount(n)
        pcm.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            for i in 0..<n { ch[0][i] = Float(src[i]) / 32768.0 }
        }
        let now = player.lastRenderTime ?? AVAudioTime(hostTime: mach_absolute_time())
        if nextTime == nil || (nextTime!.isHostTimeValid && nextTime!.hostTime < now.hostTime) {
            // 180ms jitter buffer
            let delay = AVAudioTime.hostTime(forSeconds: 0.18)
            nextTime = AVAudioTime(hostTime: now.hostTime + delay)
        }
        if let t = nextTime {
            player.scheduleBuffer(buf, at: t, options: [])
            let dur = AVAudioTime.hostTime(forSeconds: Double(n) / Double(Self.sampleRate))
            nextTime = AVAudioTime(hostTime: t.hostTime + dur)
        } else {
            player.scheduleBuffer(buf, completionHandler: nil)
        }
    }

    func resetPlayback() { nextTime = nil }

    private var pending = Data()
    private func downsample(_ buffer: AVAudioPCMBuffer, to outFmt: AVAudioFormat) {
        guard let converter, let out = AVAudioPCMBuffer(pcmFormat: outFmt, frameCapacity: 4096) else { return }
        var err: NSError?
        let inputBlock: AVAudioConverterInputBlock = { _, status in
            status.pointee = .haveData
            return buffer
        }
        converter.convert(to: out, error: &err, withInputFrom: inputBlock)
        guard err == nil, out.frameLength > 0, let ch = out.int16ChannelData else { return }
        let n = Int(out.frameLength)
        let bytes = Data(bytes: ch[0], count: n * 2)
        pending.append(bytes)
        let frameBytes = Self.frameSamples * 2
        while pending.count >= frameBytes {
            let chunk = pending.prefix(frameBytes)
            pending.removeFirst(frameBytes)
            onCapture?(Data(chunk))
        }
    }
}
