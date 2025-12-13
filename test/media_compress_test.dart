// import 'package:flutter_test/flutter_test.dart';
// import 'package:media_compress/media_compress.dart';
// import 'package:media_compress/media_compress_platform_interface.dart';
// import 'package:media_compress/media_compress_method_channel.dart';
// import 'package:plugin_platform_interface/plugin_platform_interface.dart';
//
// class MockMediaCompressPlatform
//     with MockPlatformInterfaceMixin
//     implements MediaCompressPlatform {
//
//   @override
//   Future<String?> getPlatformVersion() => Future.value('42');
// }
//
// void main() {
//   final MediaCompressPlatform initialPlatform = MediaCompressPlatform.instance;
//
//   test('$MethodChannelMediaCompress is the default instance', () {
//     expect(initialPlatform, isInstanceOf<MethodChannelMediaCompress>());
//   });
//
//   test('getPlatformVersion', () async {
//     MediaCompress mediaCompressPlugin = MediaCompress();
//     MockMediaCompressPlatform fakePlatform = MockMediaCompressPlatform();
//     MediaCompressPlatform.instance = fakePlatform;
//
//     expect(await mediaCompressPlugin.getPlatformVersion(), '42');
//   });
// }
