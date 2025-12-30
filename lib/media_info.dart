import 'dart:io';
import 'dart:ui';

class MediaInfo {
  final String path;
  final String? title;
  final String? author;
  final int width;
  final int height;

  /// [Android] API level 17
  final int? orientation;

  /// bytes
  final int filesize;
  /// milliseconds
  final double duration;
  final bool isCancel;
  final num? bitRates;
  final num frameRate;

  MediaInfo({
    required this.path,
    this.title,
    this.author,
    required this.width,
    required this.height,
    this.orientation,
    required this.filesize,
    required this.duration,
    this.isCancel = false,
    this.bitRates,
    required this.frameRate,
  });

  factory MediaInfo.fromJson(Map<String, dynamic> json) {
    return MediaInfo(
      path: json['path'],
      title: json['title'],
      author: json['author'],
      width: json['width'],
      height: json['height'],
      orientation: json['orientation'],
      filesize: json['filesize'],
      duration: double.tryParse('${json['duration']}') ?? 0,
      isCancel: json['isCancel'] ?? false,
      bitRates: json['bitRates'],
      frameRate: json['frameRate'],
    );
  }

  Map<String, dynamic> toJson() {
    final data = <String, dynamic>{};
    data['path'] = path;
    data['title'] = title;
    data['author'] = author;
    data['width'] = width;
    data['height'] = height;
    if (orientation != null) {
      data['orientation'] = orientation;
    }
    data['filesize'] = filesize;
    data['duration'] = duration;
    data['isCancel'] = isCancel;
    data['bitRates'] = bitRates;
    data['frameRate'] = frameRate;
    return data;
  }

  late final file = File(path);

  late final dimension = Size(width.toDouble(), height.toDouble());
}
