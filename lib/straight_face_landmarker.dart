import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Thin Dart facade over the Android CameraX/MediaPipe bridge.
///
/// It intentionally exposes observations instead of product decisions. The
/// straight-face rules live in the host application's pure Dart domain layer.
class StraightFaceLandmarker {
  static const MethodChannel _methods = MethodChannel(
    'straight_face_landmarker/methods',
  );
  static const EventChannel _events = EventChannel(
    'straight_face_landmarker/events',
  );

  Stream<LandmarkerEvent>? _eventStream;

  Stream<LandmarkerEvent> get events => _eventStream ??= _events
      .receiveBroadcastStream()
      .map(
        (dynamic value) =>
            LandmarkerEvent.fromMap(Map<Object?, Object?>.from(value as Map)),
      )
      .asBroadcastStream();

  Future<void> startCamera({required String sessionId}) async {
    await _methods.invokeMethod<void>('startCamera', <String, Object?>{
      'sessionId': sessionId,
    });
  }

  Future<Map<String, Object?>> stopCamera() async {
    final result = await _methods.invokeMapMethod<Object?, Object?>(
      'stopCamera',
    );
    return _stringKeyed(result);
  }

  Future<int> monotonicNowMs() async {
    final value = await _methods.invokeMethod<int>('monotonicNowMs');
    if (value == null) {
      throw StateError('Native monotonic clock returned null.');
    }
    return value;
  }

  Future<Uint8List?> claimFrameJpeg(int frameTimestampMs) {
    return _methods.invokeMethod<Uint8List>('claimFrameJpeg', <String, Object?>{
      'frameTimestampMs': frameTimestampMs,
    });
  }

  Future<bool> retainEvidenceFrame({
    required String sessionId,
    required String candidateId,
    required int frameTimestampMs,
  }) async {
    return await _methods.invokeMethod<bool>('retainEvidenceFrame', {
          'sessionId': sessionId,
          'candidateId': candidateId,
          'frameTimestampMs': frameTimestampMs,
        }) ??
        false;
  }

  Future<bool> releaseEvidenceCandidate({
    required String sessionId,
    required String candidateId,
  }) async {
    return await _methods.invokeMethod<bool>('releaseEvidenceCandidate', {
          'sessionId': sessionId,
          'candidateId': candidateId,
        }) ??
        false;
  }

  Future<EvidencePhoto?> claimEvidencePhoto({
    required String sessionId,
    required String candidateId,
    required int frameTimestampMs,
  }) async {
    final result = await _methods.invokeMapMethod<Object?, Object?>(
      'claimEvidencePhoto',
      {
        'sessionId': sessionId,
        'candidateId': candidateId,
        'frameTimestampMs': frameTimestampMs,
      },
    );
    return result == null ? null : EvidencePhoto.fromMap(result);
  }

  Future<Map<String, Object?>> saveEvidencePhoto({
    required String sessionId,
    required String evidenceId,
  }) async {
    final result = await _methods.invokeMapMethod<Object?, Object?>(
      'saveEvidencePhoto',
      {'sessionId': sessionId, 'evidenceId': evidenceId},
    );
    return _stringKeyed(result);
  }

  Future<bool> discardEvidencePhoto({
    required String sessionId,
    required String evidenceId,
  }) async {
    final result = await _methods.invokeMapMethod<Object?, Object?>(
      'discardEvidencePhoto',
      {'sessionId': sessionId, 'evidenceId': evidenceId},
    );
    return result?['discarded'] == true;
  }

  Future<void> discardFrames() {
    return _methods.invokeMethod<void>('discardFrames');
  }

  Future<Map<String, Object?>> getMetrics() async {
    final result = await _methods.invokeMapMethod<Object?, Object?>(
      'getMetrics',
    );
    return _stringKeyed(result);
  }

  static Map<String, Object?> _stringKeyed(Map<Object?, Object?>? input) {
    if (input == null) return <String, Object?>{};
    return input.map((key, value) => MapEntry(key.toString(), value));
  }
}

@immutable
class EvidencePhoto {
  const EvidencePhoto({
    required this.evidenceId,
    required this.sessionId,
    required this.frameTimestampMs,
    required this.width,
    required this.height,
    required this.mirrored,
    required this.mimeType,
    required this.bytes,
  });

  factory EvidencePhoto.fromMap(Map<Object?, Object?> input) {
    final values = input.map((key, value) => MapEntry(key.toString(), value));
    final bytes = values['bytes'];
    if (bytes is! Uint8List) {
      throw StateError('Native evidence photo did not contain JPEG bytes.');
    }
    return EvidencePhoto(
      evidenceId: values['evidenceId']?.toString() ?? '',
      sessionId: values['sessionId']?.toString() ?? '',
      frameTimestampMs: (values['frameTimestampMs'] as num?)?.toInt() ?? -1,
      width: (values['width'] as num?)?.toInt() ?? 0,
      height: (values['height'] as num?)?.toInt() ?? 0,
      mirrored: values['mirrored'] == true,
      mimeType: values['mimeType']?.toString() ?? 'image/jpeg',
      bytes: bytes,
    );
  }

  final String evidenceId;
  final String sessionId;
  final int frameTimestampMs;
  final int width;
  final int height;
  final bool mirrored;
  final String mimeType;
  final Uint8List bytes;
}

@immutable
class LandmarkerEvent {
  const LandmarkerEvent({required this.kind, required this.values});

  factory LandmarkerEvent.fromMap(Map<Object?, Object?> input) {
    final values = input.map((key, value) => MapEntry(key.toString(), value));
    return LandmarkerEvent(
      kind: values['kind']?.toString() ?? 'unknown',
      values: values,
    );
  }

  final String kind;
  final Map<String, Object?> values;

  String? get sessionId => values['sessionId']?.toString();
  int? get frameTimestampMs => (values['frameTimestampMs'] as num?)?.toInt();
  int? get resultTimestampMs => (values['resultTimestampMs'] as num?)?.toInt();
  int? get inferenceMs => (values['inferenceMs'] as num?)?.toInt();
  int get faceCount => (values['faceCount'] as num?)?.toInt() ?? 0;
  String get observationState =>
      values['observationState']?.toString() ??
      (faceCount == 0 ? 'no_face' : 'observed');
  double? get meanLuma => (values['meanLuma'] as num?)?.toDouble();
  double? get lipGapRatio => (values['lipGapRatio'] as num?)?.toDouble();

  Map<String, double> get eyeFeatures => _doubleMap('eyeFeatures');

  Map<String, double> get blendshapes => _doubleMap('blendshapes');

  Map<String, double> _doubleMap(String key) {
    final raw = values[key];
    if (raw is! Map) return <String, double>{};
    return raw.map<String, double>(
      (key, value) => MapEntry(key.toString(), (value as num).toDouble()),
    );
  }
}

class StraightFaceCameraPreview extends StatelessWidget {
  const StraightFaceCameraPreview({super.key});

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const ColoredBox(
        color: Color(0xFF111827),
        child: Center(
          child: Text(
            'Android専用PoC',
            textDirection: TextDirection.ltr,
            style: TextStyle(color: Color(0xFFFFFFFF)),
          ),
        ),
      );
    }
    return const AndroidView(
      viewType: 'straight_face_landmarker/camera_view',
      layoutDirection: TextDirection.ltr,
      creationParamsCodec: StandardMessageCodec(),
    );
  }
}
