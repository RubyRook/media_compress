import 'dart:io';

class MediaInfo {
  String? path;
  String? title;
  String? author;
  int? width;
  int? height;

  /// [Android] API level 17
  int? orientation;

  /// bytes
  int? filesize; // filesize
  /// milliseconds
  double? duration;
  bool? isCancel;
  File? file;
  num? bitRates;

  MediaInfo({
    required this.path,
    this.title,
    this.author,
    this.width,
    this.height,
    this.orientation,
    this.filesize,
    this.duration,
    this.isCancel,
    this.file,
    this.bitRates,
  });

  MediaInfo.fromJson(Map<String, dynamic> json) {
    path = json['path'];
    title = json['title'];
    author = json['author'];
    width = json['width'];
    height = json['height'];
    orientation = json['orientation'];
    filesize = json['filesize'];
    duration = double.tryParse('${json['duration']}');
    isCancel = json['isCancel'];
    file = File(path!);
    bitRates = json['bitRates'];
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
    if (isCancel != null) {
      data['isCancel'] = isCancel;
    }
    data['file'] = File(path!).toString();
    data['bitRates'] = bitRates;
    return data;
  }
}
