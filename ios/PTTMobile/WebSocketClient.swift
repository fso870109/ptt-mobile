import Foundation

protocol WebSocketClientDelegate: AnyObject {
    func wsDidOpen()
    func wsDidReceive(json: [String: Any])
    func wsDidReceive(binary: Data)
    func wsDidClose(code: Int, reason: String)
}

final class WebSocketClient: NSObject, URLSessionWebSocketDelegate {
    weak var delegate: WebSocketClientDelegate?
    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private(set) var isOpen = false

    func connect(url: URL) {
        close()
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        task = session?.webSocketTask(with: url)
        task?.resume()
        receiveLoop()
    }

    func send(json: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }

    func send(binary: Data) {
        task?.send(.data(binary)) { _ in }
    }

    func close() {
        isOpen = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        isOpen = true
        delegate?.wsDidOpen()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        isOpen = false
        let text = reason.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        delegate?.wsDidClose(code: Int(closeCode.rawValue), reason: text)
    }

    private func receiveLoop() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let msg):
                switch msg {
                case .string(let text):
                    if let data = text.data(using: .utf8),
                       let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        DispatchQueue.main.async { self.delegate?.wsDidReceive(json: obj) }
                    }
                case .data(let data):
                    DispatchQueue.main.async { self.delegate?.wsDidReceive(binary: data) }
                @unknown default:
                    break
                }
                self.receiveLoop()
            case .failure(let error):
                self.isOpen = false
                DispatchQueue.main.async { self.delegate?.wsDidClose(code: 1006, reason: error.localizedDescription) }
            }
        }
    }
}
