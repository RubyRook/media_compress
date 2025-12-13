import AVFoundation
import Foundation
import Flutter

public class MediaCompressPlugin: NSObject, FlutterPlugin {
  private let avController = AvController()
  private let channel: FlutterMethodChannel
  private let channelName = "media_compress"
  private var exportSession: AVAssetExportSession? = nil
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

  private func getExportPreset(_ quality: NSNumber)->String {
    switch(quality) {
    case 0:
      return AVAssetExportPreset640x480
    case 1:
      return AVAssetExportPreset1280x720
    case 2:
      return AVAssetExportPreset1920x1080
    default:
      return AVAssetExportPreset640x480
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
    let bitRates = getVideoBitrate(for: url)

    let duration = asset.duration.seconds * 1000
    let filesize = track.totalSampleDataLength

    let size = track.naturalSize.applying(track.preferredTransform)

    let width = abs(size.width)
    let height = abs(size.height)

    let dictionary = [
      "path":Utility.excludeFileProtocol(path),
      "title":title,
      "author":author,
      "width":width,
      "height":height,
      "duration":duration,
      "filesize":filesize,
      "orientation":orientation,
      "bitRates":bitRates
    ] as [String : Any?]
    return dictionary
  }

  public func getVideoBitrate(for videoURL: URL) -> Float? {
    let asset = AVAsset(url: videoURL)
    // Find the first video track in the asset
    guard let videoTrack = asset.tracks(withMediaType: .video).first else {
      print("No video track found")
      return nil
    }

    // Use estimatedDataRate for a general bitrate estimate
    let estimatedBitrate = videoTrack.estimatedDataRate // Measured in bits per second (bps)

    if estimatedBitrate > 0 {
      // Convert to Kilobits per second (Kbps) for easier reading
      let bitrateInKbps = estimatedBitrate / 1024.0
      print("Estimated video bitrate: \(bitrateInKbps) Kbps")
      return estimatedBitrate
    } else {
      // Fallback for cases where estimatedDataRate is not available,
      // you can calculate it manually if necessary (though less accurate).
      // A manual calculation involves file size and duration, see more details on Stack Overflow.
      print("Estimated data rate not available, attempting manual calculation (less reliable)...")
      do {
        let attributes = try FileManager.default.attributesOfItem(atPath: videoURL.path)
        if let fileSize = attributes[FileAttributeKey.size] as? UInt64 {
          let duration = CMTimeGetSeconds(asset.duration)
          if duration > 0 {
            // Calculate total bits (file size in bytes * 8 bits/byte)
            let totalBits = Double(fileSize) * 8.0
            // Bitrate = total bits / duration in seconds
            let bitrate = totalBits / duration
            let bitrateInKbps = bitrate / 1024.0
            print("Calculated video bitrate: \(bitrateInKbps) Kbps")
            return Float(bitrate)
          }
        }
      } catch {
        print("Error calculating file size: \(error)")
      }
    }

    return nil
  }



  public func compress(_ path: String, _ quality: NSNumber, _ duration: Double?, _ frameRate: Int?, _ result: @escaping FlutterResult) {
    let sourceVideoUrl = Utility.getPathUrl(path)
    let sourceVideoType = "mp4"

    let sourceVideoAsset = avController.getVideoAsset(sourceVideoUrl)
    let sourceVideoTrack = avController.getTrack(sourceVideoAsset)

    let uuid = NSUUID()
    let compressionUrl = Utility
      .getPathUrl("\(Utility.basePath())/\(Utility.getFileName(path))\(uuid.uuidString).\(sourceVideoType)")

    let timescale = sourceVideoAsset.duration.timescale
    let videoDuration = sourceVideoAsset.duration.seconds


    let trim = Double(duration ?? videoDuration)
    let timeRange = CMTimeRange(start: .zero, duration: CMTime(seconds: trim, preferredTimescale: timescale))

    let session = sourceVideoTrack!.asset!
    let exportSession = AVAssetExportSession(asset: session, presetName: getExportPreset(quality))!

    exportSession.outputFileType = .mp4
    exportSession.outputURL = compressionUrl
    exportSession.shouldOptimizeForNetworkUse = true
    exportSession.timeRange = timeRange

    if frameRate != nil {
      let videoComposition = AVMutableVideoComposition(propertiesOf: sourceVideoAsset)
      videoComposition.frameDuration = CMTimeMake(value: 1, timescale: Int32(frameRate!))
      exportSession.videoComposition = videoComposition
    }

    Utility.deleteFile(compressionUrl.absoluteString)

    let timer = Timer.scheduledTimer(
      timeInterval: 0.1,
      target: self,
      selector: #selector(self.updateProgress),
      userInfo: exportSession,
      repeats: true
    )

    exportSession.exportAsynchronously(completionHandler: {
      timer.invalidate()

      if (self.stopCommand) {
        self.stopCommand = false
        var json = self.getMediaInfoJson(path)
        json["isCancel"] = true
        let jsonString = Utility.keyValueToJson(json)
        return result(jsonString)
      }

      var json = self.getMediaInfoJson(Utility.excludeEncoding(compressionUrl.path))
      json["isCancel"] = false
      let jsonString = Utility.keyValueToJson(json)
      result(jsonString)
    })
    self.exportSession = exportSession
  }

  private func cancelCompression(_ result: FlutterResult) {
    stopCommand = true
    exportSession?.cancelExport()
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

  @objc private func updateProgress(timer:Timer) {
    let asset = timer.userInfo as! AVAssetExportSession
    if (!stopCommand) {
      channel.invokeMethod("updateProgress", arguments: "\(String(describing: asset.progress * 100))")
    }
  }
}

