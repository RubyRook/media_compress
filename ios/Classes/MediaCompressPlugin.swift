import AVFoundation
import Foundation
import Flutter

public class MediaCompressPlugin: NSObject, FlutterPlugin {
  private let avController = AvController()
  private let channel: FlutterMethodChannel
  private let channelName = "media_compress"
  private var stopCommand = false

  init(channel: FlutterMethodChannel) {
    self.channel = channel
  }

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "media_compress", binaryMessenger: registrar.messenger())
    let instance = MediaCompressPlugin(channel: channel)
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let args = call.arguments as? Dictionary<String, Any>

    switch call.method {
    case "cancelCompression":
      cancelCompression(result)

    case "compress":
      let path = args!["path"] as! String
      let quality = args!["quality"] as! NSNumber
      // let startTime = args!["startTime"] as? Double
      let duration = args!["duration"] as? Double
      let frameRate = args!["frameRate"] as? Int
      compress(path, quality, duration, frameRate, result)

    case "getByteThumbnail":
      let path = args!["path"] as! String
      let quality = args!["quality"] as! NSNumber
      let position = args!["position"] as! NSNumber
      getByteThumbnail(path, quality, position, result)

    case "getMediaInfo":
      let path = args!["path"] as! String
      getMediaInfo(path, result)

    case "deleteAllCache":
      Utility.deleteFile(Utility.basePath(), clear: true)
      result(true)

    default:
      result(FlutterMethodNotImplemented)
    }
  }

  public func getMediaInfoJson(_ path: String)->[String : Any?] {
    let url = Utility.getPathUrl(path)
    let asset = avController.getVideoAsset(url)
    guard let track = avController.getTrack(asset) else { return [:] }

    let playerItem = AVPlayerItem(url: url)
    let metadataAsset = playerItem.asset

    let orientation = avController.getVideoOrientation(path)

    let title = avController.getMetaDataByTag(metadataAsset, key: "title")
    let author = avController.getMetaDataByTag(metadataAsset, key: "author")
    let bitRates = track.estimatedDataRate
    // Convert to Kilobits per second (Kbps) for easier reading
    // let bitrateInKbps = bitRates / 1024.0
    // print("Estimated video bitrate: \(bitrateInKbps) Kbps")

    let duration = asset.duration.seconds * 1000
    let filesize = track.totalSampleDataLength
    let frameRate = track.nominalFrameRate

    let size = track.naturalSize.applying(track.preferredTransform)

    let width = abs(size.width)
    let height = abs(size.height)

    let dictionary = [
      "path": Utility.excludeFileProtocol(path), // not nil
      "title": title, // not nil (empty string)
      "author": author, // not nil (empty string)
      "width": width, // not nil
      "height": height, // not nil
      "duration": duration, // not nil
      "filesize": filesize, // not nil
      "orientation": orientation,
      "bitRates": bitRates,
      "frameRate": frameRate // not nil
    ] as [String : Any?]
    return dictionary
  }

  private func getOutputSize(from originalSize: CGSize, for quality: NSNumber) -> CGSize {
    let videoQuality = quality.intValue
    var resolution: CGSize

    switch videoQuality {
      case 0:
        resolution = CGSize(width: 640, height: 480)
      case 1:
        resolution = CGSize(width: 960, height: 540)
      case 2:
        resolution = CGSize(width: 1280, height: 720)
      case 3:
        resolution = CGSize(width: 1920, height: 1080)
      default:
        resolution = CGSize(width: 640, height: 480)
    }

    // Portrait
    if originalSize.height > originalSize.width {
      let aspect = originalSize.height / originalSize.width
      let height = resolution.width // In portrait: quality size width become height
      let width = height / aspect
      return CGSize(width: width, height: height)
    }
    // Landscape
    else {
      let aspect = originalSize.width / originalSize.height
      let width = resolution.height * aspect
      return CGSize(width: width, height: resolution.height)
    }
  }

  public func compress(_ path: String, _ quality: NSNumber, _ duration: Double?, _ frameRate: Int?, _ result: @escaping FlutterResult) {
    let sourceVideoUrl = Utility.getPathUrl(path)
    let sourceVideoAsset = AVAsset(url: sourceVideoUrl)

    // 1. Get Video and Audio Tracks
    guard let sourceVideoTrack = sourceVideoAsset.tracks(withMediaType: .video).first else {
      result(FlutterError(code: "NO_VIDEO_TRACK", message: "No video track found in the source asset", details: nil))
      return
    }

    // Get the audio track, if it exists. It's not an error if it doesn't.
    let sourceAudioTrack = sourceVideoAsset.tracks(withMediaType: .audio).first

    // 2. Setup Asset Reader
    guard let reader = try? AVAssetReader(asset: sourceVideoAsset) else {
      result(FlutterError(code: "READER_ERROR", message: "Unable to initialize asset reader", details: nil))
      return
    }

    let uuid = NSUUID()
    let compressionUrl = Utility.getPathUrl("\(Utility.basePath())/\(Utility.getFileName(path))-\(uuid.uuidString).mp4")
    try? FileManager.default.removeItem(at: compressionUrl)

    // 3. Setup Asset Writer
    guard let writer = try? AVAssetWriter(outputURL: compressionUrl, fileType: .mp4) else {
      result(FlutterError(code: "WRITER_ERROR", message: "Unable to initialize asset writer", details: nil))
      return
    }

    // 4. Configure Video Writer Input
    var estimatedDataRate = Int(sourceVideoTrack.estimatedDataRate)
    if estimatedDataRate == 0 { estimatedDataRate = 1_000_000 }

    let outputSize = getOutputSize(from: sourceVideoTrack.naturalSize, for: quality)
    let bitrate: Int

    switch quality.intValue {
      case 0: bitrate = 1_000_000 // 1 Mbps
      case 1: bitrate = estimatedDataRate < 1_800_000 ? estimatedDataRate: 1_800_000 // 1.8 Mbps
      case 2: bitrate = estimatedDataRate < 2_500_000 ? estimatedDataRate: 2_500_000 // 2.5 Mbps
      case 3: bitrate = estimatedDataRate < 5_000_000 ? estimatedDataRate: 5_000_000 // 5 Mbps
      default: bitrate = 1_000_000
    }

    let videoOutputSettings: [String: Any] = [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: outputSize.width,
      AVVideoHeightKey: outputSize.height,
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: bitrate,
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
      ],
    ]

    let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoOutputSettings, sourceFormatHint: sourceVideoTrack.formatDescriptions.first as! CMFormatDescription?)
    videoInput.transform = sourceVideoTrack.preferredTransform

    // 5. Configure Audio Writer Input (if audio track exists)
    var audioInput: AVAssetWriterInput?
    var audioSettings:[String: Any] = [:]
    if sourceAudioTrack != nil {
      audioSettings = [
        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
        AVSampleRateKey: 44100,
        AVNumberOfChannelsKey: 2,
        AVEncoderBitRateKey: 128000,
      ]
      let writerAudioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
      writerAudioInput.expectsMediaDataInRealTime = false
      audioInput = writerAudioInput
    }

    // 6. Configure Reader Outputs
    let readerVideoOutput = AVAssetReaderTrackOutput(track: sourceVideoTrack, outputSettings: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
    ])

    var readerAudioOutput: AVAssetReaderTrackOutput?
    if let audioTrack = sourceAudioTrack {
      // For audio, we read the samples in their original format.
      readerAudioOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM
      ])
    }

    // 7. Attach Inputs and Outputs to Reader and Writer
    if reader.canAdd(readerVideoOutput) { reader.add(readerVideoOutput) } else {
      result(FlutterError(code: "READER_ERROR", message: "Cannot add video reader output", details: nil)); return
    }
    if writer.canAdd(videoInput) { writer.add(videoInput) } else {
      result(FlutterError(code: "WRITER_ERROR", message: "Cannot add video writer input", details: nil)); return
    }

    if let audioOut = readerAudioOutput, let audioIn = audioInput {
      if reader.canAdd(audioOut) { reader.add(audioOut) } else {
        result(FlutterError(code: "READER_ERROR", message: "Cannot add audio reader output", details: nil)); return
      }
      if writer.canAdd(audioIn) { writer.add(audioIn) } else {
        result(FlutterError(code: "WRITER_ERROR", message: "Cannot add audio writer input", details: nil)); return
      }
    }

    // Configure time range for trimming
    let videoDuration = sourceVideoAsset.duration.seconds
    var trim = videoDuration
    if let requestedDuration = duration, requestedDuration < videoDuration {
      trim = requestedDuration
    }
    if trim > 0 {
      reader.timeRange = CMTimeRange(start: .zero, duration: CMTime(seconds: trim, preferredTimescale: sourceVideoAsset.duration.timescale))
    }


    // 8. Start Writing/Reading Session
    writer.startWriting()
    reader.startReading()
    writer.startSession(atSourceTime: .zero)

    // 9. Process Media Data Concurrently
    let processingQueue = DispatchQueue(label: "media-compression-queue", qos: .userInitiated)
    let dispatchGroup = DispatchGroup()

    var lastAppendedFrameTime = CMTime.zero
    let targetFrameRate = frameRate != nil ? Int32(frameRate!) : 0
    let frameDuration = targetFrameRate > 0 ? CMTime(value: 1, timescale: targetFrameRate) : .invalid

    // --- Process Video Track ---
    dispatchGroup.enter()
    videoInput.requestMediaDataWhenReady(on: processingQueue) { [weak self] in
      guard let self = self else { return }
      while videoInput.isReadyForMoreMediaData {
        if self.stopCommand {
          reader.cancelReading()
          videoInput.markAsFinished()
          dispatchGroup.leave()
          print("Stop command received: Line \(#line)")
          return
        }

        if let sampleBuffer = readerVideoOutput.copyNextSampleBuffer() {
          let progress = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) / trim
          DispatchQueue.main.async {
            self.channel.invokeMethod("updateProgress", arguments: "\(progress * 100)")
          }

          var shouldAppend = true
          if frameRate != nil {
            let currentTimestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)

            // If the current frame is PAST our target time, it's the one we should keep.
            if currentTimestamp >= lastAppendedFrameTime {
              shouldAppend = true
              // We've appended a frame, so schedule the next append time.
              lastAppendedFrameTime = CMTimeAdd(lastAppendedFrameTime, frameDuration)
            } else {
              // This frame arrived before our scheduled time. Drop it.
              shouldAppend = false
            }
          }

          if shouldAppend {
            // Note: Appending the buffer fails if the writer isn't ready.
            // Your original code handles this well.
            if !videoInput.append(sampleBuffer) {
              reader.cancelReading()
              videoInput.markAsFinished()
              dispatchGroup.leave()
              result(FlutterError(
                code: "APPEND_ERROR",
                message: "Line \(#line): Unable to append video sample buffer",
                details: "\(videoOutputSettings)"
              ))
              break
            }
          }
        } else {
          videoInput.markAsFinished()
          dispatchGroup.leave()
          break
        }
      }
    }

    // --- Process Audio Track (if exists) ---
    if let writerAudioInput = audioInput, let trackAudioOutput = readerAudioOutput {
      dispatchGroup.enter()
      writerAudioInput.requestMediaDataWhenReady(on: processingQueue) { [weak self] in
        guard let self = self else { return }
        while writerAudioInput.isReadyForMoreMediaData {
          if self.stopCommand {
            // Video queue will cancel the reader, just finish this input.
            writerAudioInput.markAsFinished()
            dispatchGroup.leave()
            return
          }

          if let sampleBuffer = trackAudioOutput.copyNextSampleBuffer() {
            if !writerAudioInput.append(sampleBuffer) {
              reader.cancelReading()
              videoInput.markAsFinished()
              dispatchGroup.leave()
              result(FlutterError(
                code: "APPEND_ERROR",
                message: "Line \(#line): Unable to append audio sample buffer",
                details: "\(audioSettings)"
              ))
              break
            }
          } else {
            writerAudioInput.markAsFinished()
            dispatchGroup.leave()
            break
          }
        }
      }
    }

    // 10. Finalize Writing
    dispatchGroup.notify(queue: .main) { [weak self] in
      guard let self = self else { return }
      if self.stopCommand {
        writer.cancelWriting()
        self.stopCommand = false // Reset command
        var json = self.getMediaInfoJson(path)
        json["isCancel"] = true
        result(Utility.keyValueToJson(json))
        return
      }

      // Handle reader errors after processing
      guard reader.status == .completed else {
        writer.cancelWriting() // Important to prevent a corrupted file
        if reader.status == .failed {
          let errorMessage = reader.error?.localizedDescription ?? "Unknown reader error"
          result(FlutterError(code: "COMPRESSION_FAILED", message: "Reader failed: \(errorMessage)", details: nil))
        }
        // If cancelled, the stopCommand block will handle the response.
        return
      }

      writer.finishWriting {
        if writer.status == .completed {
          var json = self.getMediaInfoJson(Utility.excludeEncoding(compressionUrl.path))
          json["isCancel"] = false
          result(Utility.keyValueToJson(json))
        } else {
          let errorMessage = writer.error?.localizedDescription ?? "Unknown writer error"
          result(FlutterError(code: "COMPRESSION_FAILED", message: "Writer failed: \(errorMessage)", details: writer.error?.localizedDescription))
        }
      }
    }
  }

  private func cancelCompression(_ result: FlutterResult) {
    stopCommand = true
    result("")
  }

  private func getByteThumbnail(_ path: String, _ quality: NSNumber, _ position: NSNumber, _ result: FlutterResult) {
    if let bitmap = getBitMap(path,quality,position,result) {
      result(bitmap)
    }
  }

  private func getBitMap(_ path: String, _ quality: NSNumber, _ position: NSNumber, _ result: FlutterResult)-> Data? {
    let url = Utility.getPathUrl(path)
    let asset = avController.getVideoAsset(url)
    guard let track = avController.getTrack(asset) else { return nil }

    let assetImgGenerate = AVAssetImageGenerator(asset: asset)
    assetImgGenerate.appliesPreferredTrackTransform = true

    let timeScale = CMTimeScale(track.nominalFrameRate)
    let time = CMTimeMakeWithSeconds(Float64(truncating: position),preferredTimescale: timeScale)
    guard let img = try? assetImgGenerate.copyCGImage(at:time, actualTime: nil) else {
      return nil
    }
    let thumbnail = UIImage(cgImage: img)
    let compressionQuality = CGFloat(0.01 * Double(truncating: quality))
    return thumbnail.jpegData(compressionQuality: compressionQuality)
  }

  private func getMediaInfo(_ path: String,_ result: FlutterResult) {
    let json = getMediaInfoJson(path)
    let string = Utility.keyValueToJson(json)
    result(string)
  }
}

