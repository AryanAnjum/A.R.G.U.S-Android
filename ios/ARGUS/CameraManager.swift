import AVFoundation
import CoreImage
import ImageIO
import UIKit

/// The iPhone's back or front camera: a live preview, throttled upright JPEG frames for the server,
/// sharp photos for OCR, and QR code reading.
final class CameraManager: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate,
                           AVCapturePhotoCaptureDelegate, AVCaptureMetadataOutputObjectsDelegate {
    let session = AVCaptureSession()
    @Published private(set) var isRunning = false

    /// Called on the camera queue with each sample buffer; the app decides whether to send it.
    var onFrame: (@Sendable (CMSampleBuffer) -> Void)?

    private let sessionQueue = DispatchQueue(label: "argus.camera.session")
    private let videoQueue = DispatchQueue(label: "argus.camera.frames")
    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    /// Called on the main queue with the text of a QR code while `scanForQRCodes(true)` is on.
    var onQRCode: ((String) -> Void)?
    private var configured = false
    private var input: AVCaptureDeviceInput?
    /// True while the front camera is the one in use.
    @Published private(set) var isFront = false
    private var photoHandlers: [Int64: (Data?) -> Void] = [:]
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    func start() {
        sessionQueue.async {
            if !self.configured { self.configure() }
            guard self.configured, !self.session.isRunning else { return }
            self.session.startRunning()
            DispatchQueue.main.async { self.isRunning = self.session.isRunning }
        }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning { self.session.stopRunning() }
            DispatchQueue.main.async { self.isRunning = false }
        }
    }

    /// Switches between the back and the front camera (also while running).
    func setFront(_ front: Bool) {
        sessionQueue.async {
            guard front != self.wantFront else { return }
            self.wantFront = front
            guard self.configured else { return }
            self.session.beginConfiguration()
            self.attachCamera()
            self.orientFrames()
            self.session.commitConfiguration()
        }
    }

    private var wantFront = false

    /// Puts the wanted camera (back or front) on the session. Call inside begin/commitConfiguration.
    @discardableResult
    private func attachCamera() -> Bool {
        let position: AVCaptureDevice.Position = wantFront ? .front : .back
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
              let newInput = try? AVCaptureDeviceInput(device: device) else { return false }
        if let old = input { session.removeInput(old) }
        guard session.canAddInput(newInput) else {
            if let old = input, session.canAddInput(old) { session.addInput(old) }
            return false
        }
        session.addInput(newInput)
        input = newInput
        if (try? device.lockForConfiguration()) != nil {
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
            device.unlockForConfiguration()
        }
        let front = wantFront
        DispatchQueue.main.async { self.isFront = front }
        return true
    }

    /// Frames arrive upright in portrait, so the model never sees sideways text.
    private func orientFrames() {
        for output in [videoOutput as AVCaptureOutput, photoOutput] {
            if let conn = output.connection(with: .video), conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
        }
    }

    private func configure() {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // 1080p keeps 1920 px OCR photos sharp while the stream frames are scaled down before sending.
        session.sessionPreset = session.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
        guard attachCamera() else { return }

        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: videoQueue)
        if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
        if session.canAddOutput(photoOutput) {
            session.addOutput(photoOutput)
            photoOutput.maxPhotoQualityPrioritization = .quality
        }
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: .main)
        }
        orientFrames()
        configured = true
    }

    /// Turns QR code reading on or off (used to pair with the PC's Connect phone panel).
    func scanForQRCodes(_ on: Bool) {
        sessionQueue.async {
            guard self.configured, self.session.outputs.contains(self.metadataOutput) else { return }
            let canReadQR = self.metadataOutput.availableMetadataObjectTypes.contains(.qr)
            self.metadataOutput.metadataObjectTypes = on && canReadQR ? [.qr] : []
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        for object in metadataObjects {
            if let code = object as? AVMetadataMachineReadableCodeObject, let text = code.stringValue {
                onQRCode?(text)
                return
            }
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        onFrame?(sampleBuffer)
    }

    /// Upright JPEG with its long side at most `maxPx`, base64 encoded.
    func encode(_ sampleBuffer: CMSampleBuffer, maxPx: CGFloat, quality: CGFloat) -> String? {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        return encode(CIImage(cvPixelBuffer: pixels), maxPx: maxPx, quality: quality)
    }

    private func encode(_ image: CIImage, maxPx: CGFloat, quality: CGFloat) -> String? {
        let longSide = max(image.extent.width, image.extent.height)
        var img = image
        if longSide > maxPx {
            let s = maxPx / longSide
            img = img.transformed(by: CGAffineTransform(scaleX: s, y: s))
        }
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let data = ciContext.jpegRepresentation(
                of: img, colorSpace: space,
                options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): quality])
        else { return nil }
        return data.base64EncodedString()
    }

    /// Takes a full-quality photo and returns it as an upright base64 JPEG (long side `maxPx`), or nil.
    func capturePhoto(maxPx: CGFloat, completion: @escaping (String?) -> Void) {
        sessionQueue.async {
            guard self.session.isRunning, self.session.outputs.contains(self.photoOutput) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            settings.photoQualityPrioritization = .quality
            if let conn = self.photoOutput.connection(with: .video), conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
            self.photoHandlers[settings.uniqueID] = { data in
                var encoded: String?
                // applyOrientationProperty turns the photo upright from its EXIF orientation.
                if let data, let ci = CIImage(data: data, options: [.applyOrientationProperty: true]) {
                    encoded = self.encode(ci, maxPx: maxPx, quality: 0.9)
                }
                DispatchQueue.main.async { completion(encoded) }
            }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let id = photo.resolvedSettings.uniqueID
        sessionQueue.async {
            let handler = self.photoHandlers.removeValue(forKey: id)
            handler?(error == nil ? photo.fileDataRepresentation() : nil)
        }
    }
}
