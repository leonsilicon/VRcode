import CoreBluetooth
import Foundation
import IOBluetooth
import Network

let linkServiceUUID = "94CE62E0-9F70-4F80-A008-927272730001"
private let linkRX = CBUUID(string: "94CE62E0-9F70-4F80-A008-927272730002")
private let linkTX = CBUUID(string: "94CE62E0-9F70-4F80-A008-927272730003")

/// A carrier forwards opaque TLS bytes to the local relay. It never terminates TLS or sees tokens.
final class CarrierBridge {
  private let socket: NWConnection
  private let queue = DispatchQueue(label: "vrcode.carrier")
  private let send: (Data) -> Bool
  private let ended: () -> Void
  private var pending = 0
  private var closed = false

  init(port: UInt16, send: @escaping (Data) -> Bool, ended: @escaping () -> Void) {
    socket = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
    self.send = send; self.ended = ended
    socket.stateUpdateHandler = { [weak self] state in
      if case .failed = state { self?.close() }
      if case .ready = state { self?.read() }
    }
    socket.start(queue: queue)
  }

  func receive(_ data: Data) {
    queue.async {
      guard !self.closed else { return }
      self.pending += data.count
      guard self.pending <= 2 * 1024 * 1024 else { self.close(); return }
      self.socket.send(content: data, completion: .contentProcessed { error in
        self.pending -= data.count
        if error != nil { self.close() }
      })
    }
  }

  private func read() {
    socket.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, done, error in
      guard let self, !self.closed else { return }
      if let data, !data.isEmpty, !self.send(data) { self.close(); return }
      if done || error != nil { self.close() } else { self.read() }
    }
  }

  func close() {
    queue.async {
      guard !self.closed else { return }; self.closed = true
      self.socket.cancel(); self.ended()
    }
  }
}

final class ClassicCarrier: NSObject, IOBluetoothRFCOMMChannelDelegate {
  private var record: IOBluetoothSDPServiceRecord?
  private var notification: IOBluetoothUserNotification?
  private var channels: [UInt: (IOBluetoothRFCOMMChannel, CarrierBridge)] = [:]
  private let port: UInt16

  init(port: UInt16) { self.port = port; super.init() }

  func start() throws { try publish() }

  private func publish() throws {
    let uuid = Data(UUID(uuidString: linkServiceUUID)!.uuidString.replacingOccurrences(of: "-", with: "").hexBytes)
    let attributes: [String: Any] = [
      "0001 - ServiceClassIDList": [uuid],
      "0004 - ProtocolDescriptorList": [
        [Data([0x01, 0x00])],
        [Data([0x00, 0x03]), ["DataElementType": 1, "DataElementSize": 1, "DataElementValue": 7]],
      ],
      "0005 - BrowseGroupList": [Data([0x10, 0x02])],
      "0100 - ServiceName": "VRcode Secure Link",
      "LocalAttributes": ["Persistent": false],
    ]
    guard let record = IOBluetoothSDPServiceRecord.publishedServiceRecord(with: attributes) else {
      throw LinkFailure.message("Bluetooth Classic SDP publication failed; check Bluetooth permission and power")
    }
    var channel: BluetoothRFCOMMChannelID = 0
    guard record.getRFCOMMChannelID(&channel) == kIOReturnSuccess else { throw LinkFailure.message("No RFCOMM channel available") }
    DispatchQueue.main.async { [self] in
      self.record = record
      guard let notification = IOBluetoothRFCOMMChannel.register(forChannelOpenNotifications: self,
        selector: #selector(opened(_:channel:)), withChannelID: channel, direction: kIOBluetoothUserNotificationChannelDirectionIncoming) else {
        record.remove(); self.record = nil
        log("Could not register the Bluetooth Classic receiver"); return
      }
      self.notification = notification
      log("Bluetooth Classic relay registered on RFCOMM channel \(channel)")
    }
  }

  @objc private func opened(_ notification: IOBluetoothUserNotification, channel: IOBluetoothRFCOMMChannel) {
    log("Bluetooth Classic carrier opened")
    guard channels.count < 4 else { channel.close(); return }
    let id = UInt(bitPattern: Unmanaged.passUnretained(channel).toOpaque())
    channel.setDelegate(self)
    let bridge = CarrierBridge(port: port, send: { [weak channel] bytes in
      guard let channel else { return false }
      var success = true
      bytes.withUnsafeBytes { raw in
        var offset = 0
        while offset < raw.count {
          let count = min(Int(channel.getMTU()), raw.count - offset)
          if count == 0 || channel.writeSync(UnsafeMutableRawPointer(mutating: raw.baseAddress!.advanced(by: offset)), length: UInt16(count)) != kIOReturnSuccess {
            success = false; break
          }
          offset += count
        }
      }
      return success
    }, ended: { [weak self, weak channel] in
      DispatchQueue.main.async { channel?.close(); self?.channels.removeValue(forKey: id) }
    })
    channels[id] = (channel, bridge)
  }

  func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
    let id = UInt(bitPattern: Unmanaged.passUnretained(rfcommChannel).toOpaque())
    channels[id]?.1.receive(Data(bytes: dataPointer, count: dataLength))
  }

  func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
    let id = UInt(bitPattern: Unmanaged.passUnretained(rfcommChannel).toOpaque())
    channels.removeValue(forKey: id)?.1.close()
  }

  deinit { notification?.unregister(); record?.remove() }
}

final class BLECarrier: NSObject, CBPeripheralManagerDelegate {
  private var manager: CBPeripheralManager!
  private let port: UInt16
  private let queue = DispatchQueue(label: "vrcode.ble")
  private var central: CBCentral?
  private var bridge: CarrierBridge?
  private var tx: CBMutableCharacteristic!
  private var pending = Data()
  private var onReady: (() -> Void)?

  init(port: UInt16) { self.port = port; super.init() }
  func start(onReady: (() -> Void)? = nil) {
    self.onReady = onReady
    log("Bluetooth permission status: \(CBPeripheralManager.authorization.rawValue)")
    manager = CBPeripheralManager(delegate: self, queue: queue)
  }

  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    guard peripheral.state == .poweredOn else {
      bridge?.close(); bridge = nil; central = nil; pending.removeAll()
      log("Bluetooth LE unavailable: state \(peripheral.state.rawValue)"); return
    }
    let service = CBMutableService(type: CBUUID(string: linkServiceUUID), primary: true)
    tx = CBMutableCharacteristic(type: linkTX, properties: [.notify], value: nil, permissions: [])
    let rx = CBMutableCharacteristic(type: linkRX, properties: [.write], value: nil, permissions: [.writeable])
    service.characteristics = [rx, tx]
    peripheral.add(service)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
    guard error == nil else { log("BLE publication failed: \(error!)"); return }
    peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: linkServiceUUID)], CBAdvertisementDataLocalNameKey: "VRcode Mac"])
  }

  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
    guard error == nil else { log("Bluetooth LE advertising failed: \(error!)"); return }
    log("Bluetooth LE relay advertising")
    let ready = onReady; onReady = nil
    // IOBluetooth installs run-loop sources on its calling thread. Initialize
    // Classic on AppKit's thread, after Bluetooth permission/radio startup.
    DispatchQueue.main.async { ready?() }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
    guard self.central == nil else { return }
    self.central = central
    bridge = CarrierBridge(port: port, send: { [weak self] bytes in
      guard let self else { return false }
      return self.queue.sync {
        guard self.central != nil, self.pending.count + bytes.count <= 2 * 1024 * 1024 else { return false }
        self.pending.append(bytes); self.flush(); return true
      }
    }, ended: { [weak self] in
      self?.queue.async {
        guard self?.central?.identifier == central.identifier else { return }
        self?.bridge = nil; self?.central = nil; self?.pending.removeAll()
      }
    })
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
    if self.central?.identifier == central.identifier { bridge?.close(); bridge = nil; self.central = nil; pending.removeAll() }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    for request in requests {
      guard request.central.identifier == central?.identifier, request.characteristic.uuid == linkRX,
            request.offset == 0, let bytes = request.value, let bridge else {
        peripheral.respond(to: request, withResult: .writeNotPermitted); continue
      }
      bridge.receive(bytes)
      peripheral.respond(to: request, withResult: .success)
    }
  }

  func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) { flush() }

  private func flush() {
    guard let central else { return }
    while !pending.isEmpty {
      let bytes = Data(pending.prefix(min(central.maximumUpdateValueLength, 512)))
      guard manager.updateValue(bytes, for: tx, onSubscribedCentrals: [central]) else { return }
      pending.removeFirst(bytes.count)
    }
  }
}

private extension String {
  var hexBytes: [UInt8] { stride(from: 0, to: count, by: 2).compactMap { offset in
    let begin = index(startIndex, offsetBy: offset)
    return UInt8(self[begin..<index(begin, offsetBy: 2)], radix: 16)
  } }
}
