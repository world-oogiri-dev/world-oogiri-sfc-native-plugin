import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:straight_face_landmarker/straight_face_landmarker.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('straight_face_landmarker/methods');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('pins and claims the exact session candidate timestamp', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'retainEvidenceFrame') return true;
      if (call.method == 'claimEvidencePhoto') {
        return <String, Object?>{
          'evidenceId': 'evidence-1',
          'sessionId': 'session-1',
          'frameTimestampMs': 4800,
          'width': 720,
          'height': 960,
          'mirrored': true,
          'mimeType': 'image/jpeg',
          'bytes': Uint8List.fromList(<int>[1, 2, 3]),
        };
      }
      return null;
    });
    final plugin = StraightFaceLandmarker();

    expect(
      await plugin.retainEvidenceFrame(
        sessionId: 'session-1',
        candidateId: 'smile-4800',
        frameTimestampMs: 4800,
      ),
      isTrue,
    );
    final photo = await plugin.claimEvidencePhoto(
      sessionId: 'session-1',
      candidateId: 'smile-4800',
      frameTimestampMs: 4800,
    );

    expect(photo, isNotNull);
    expect(photo!.sessionId, 'session-1');
    expect(photo.frameTimestampMs, 4800);
    expect(photo.mirrored, isTrue);
    expect(photo.bytes, Uint8List.fromList(<int>[1, 2, 3]));
    expect(calls[0].arguments, <String, Object?>{
      'sessionId': 'session-1',
      'candidateId': 'smile-4800',
      'frameTimestampMs': 4800,
    });
  });

  test('save and discard require the claimed evidence identity', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'saveEvidencePhoto') {
        return <String, Object?>{
          'saved': true,
          'contentUri': 'content://photo',
        };
      }
      if (call.method == 'discardEvidencePhoto') {
        return <String, Object?>{'discarded': true};
      }
      return null;
    });
    final plugin = StraightFaceLandmarker();

    final saved = await plugin.saveEvidencePhoto(
      sessionId: 'session-1',
      evidenceId: 'evidence-1',
    );
    final discarded = await plugin.discardEvidencePhoto(
      sessionId: 'session-1',
      evidenceId: 'evidence-1',
    );

    expect(saved['saved'], isTrue);
    expect(discarded, isTrue);
  });
}
