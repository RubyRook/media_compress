import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:media_compress/media_info.dart';
import 'package:media_compress/subscription.dart';

export 'package:media_compress/media_info.dart';

enum Quality {
  /// Resolution 640x480 Quality
  x480p('480P', 480),
  /// Resolution 960x540 Quality
  x540p('540P', 540),
  /// Resolution 1280x720 Quality
  x720p('720P', 720),
  /// Resolution 1920x1080 Quality
  x1080p('1080P', 1080);

  final String title;
  final int value;
  const Quality(this.title, this.value);

  static Quality fromSide(num side) {
    if (side >= x1080p.value) {
      return x1080p;
    }
    else if (side >= x720p.value) {
      return x720p;
    }
    else if (side >= x540p.value) {
      return x540p;
    }
    else if (side >= x480p.value) {
      return x480p;
    }

    return x1080p;
  }
}

enum ErrorCode {
  canceled("COMPRESSION_CANCELED"),
  failed("COMPRESSION_FAILED");

  final String code;
  const ErrorCode(this.code);

}

enum MethodName {
  compress,
  cancelCompression,
  getByteThumbnail,
  getMediaInfo,
  deleteAllCache,
}

mixin _CompressMixin {
  final compressProgress$ = ObservableBuilder<double>();
  final _channel = const MethodChannel('media_compress');

  @protected
  void initProcessCallback() {
    _channel.setMethodCallHandler(_progressCallback);
  }

  MethodChannel get channel => _channel;

  bool _isCompressing = false;

  bool get isCompressing => _isCompressing;

  @protected
  void setProcessingStatus(bool status) {
    _isCompressing = status;
  }

  Future<void> _progressCallback(MethodCall call) async {
    switch (call.method) {
      case 'updateProgress':
        final progress = double.tryParse(call.arguments.toString());
        if (progress != null) compressProgress$.next(progress);
        break;
    }
  }
}

class MediaCompress with _CompressMixin {
  MediaCompress._(){
    initProcessCallback();
  }
  static final instance = MediaCompress._();

  Future<void> cancelCompression() async {
    _invoke(MethodName.cancelCompression);
  }

  Future<MediaInfo?> compress({
    required String path,
    required Quality quality,
    int? duration,
    int frameRate = 30,
  })
  async {
    if (isCompressing) {
      throw StateError('Already have a compression process!');
    }

    setProcessingStatus(true);
    final result = await _invoke<String>(MethodName.compress, {
      'path': path,
      'quality': quality.index,
      'duration': duration,
      'frameRate': frameRate,
    }).whenComplete(()=> setProcessingStatus(false));

    if (result != null) {
      final jsonMap = json.decode(result);
      return MediaInfo.fromJson(jsonMap);
    }
    else {
      return null;
    }
  }

  Future<Uint8List?> getByteThumbnail(String path, {
    int quality = 100,
    int position = -1,
  })
  async {
    assert(quality > 1 || quality < 100);

    return await _invoke<Uint8List>(MethodName.getByteThumbnail, {
      'path': path,
      'quality': quality,
      'position': position,
    });
  }

  Future<MediaInfo> getMediaInfo(String path) async {
    // Not to set the result as strong-mode so that it would have exception to
    // lead to the failure of compression
    final jsonStr = await (_invoke<String>(MethodName.getMediaInfo, {'path': path}));
    final jsonMap = json.decode(jsonStr!);
    return MediaInfo.fromJson(jsonMap);
  }

  Future<bool?> deleteAllCache() async {
    return await _invoke<bool>(MethodName.deleteAllCache);
  }

  Future<T?> _invoke<T>(MethodName name, [Map<String, dynamic>? params]) async {
    T? result;
    try {
      result = params != null
          ? await channel.invokeMethod(name.name, params)
          : await channel.invokeMethod(name.name);

    } on PlatformException catch (e) {
      if (name == MethodName.compress) {
        rethrow;
      }
      else {
        debugPrint('Error from MediaCompress: Method: $name $e');
      }
    }

    return result;
  }

  Quality qualityGen(num n) {
    Quality? result;
    for (final quality in Quality.values) {
      if (n > quality.value) {
        continue;
      }

      result = quality;
    }

    return result ?? Quality.x1080p;
  }
}
