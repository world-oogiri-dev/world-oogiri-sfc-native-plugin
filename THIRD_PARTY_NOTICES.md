# Third-Party Notices

This file covers third-party artifacts only. It does not license
WORLD OOGIRI-authored source code.

## Face Landmarker model bundle

- Distributed file: `android/src/main/assets/face_landmarker.task`
- Official source: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
- Size: `3758596` bytes
- SHA-256: `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`
- Bundle members: `face_detector.tflite`, `face_landmarks_detector.tflite`,
  `face_blendshapes.tflite`, and
  `geometry_pipeline_metadata_landmarks.binarypb`.

The official Face Landmarker guide links model cards for BlazeFace short
range, FaceMesh V2, and Blendshape V2. Each model card states Apache License,
Version 2.0. The Apache License text is included at
`LICENSES/Apache-2.0.txt`.

## MediaPipe Tasks Vision

- Direct artifact: `com.google.mediapipe:tasks-vision:1.1.1`
- Transitive artifact: `com.google.mediapipe:tasks-core:1.1.1`
- Declared license: Apache License 2.0
- Copyright holder identified by the Maven metadata: The MediaPipe Authors

The complete notice shipped in the resolved MediaPipe Tasks Vision AAR is
preserved at `LICENSES/MediaPipe-Tasks-Vision-NOTICE.txt`. It includes the
MediaPipe Tasks privacy notice and licenses/copyright notices for bundled
third-party components. That notice states that input processing occurs on
device and that API performance/utilization metrics are sent to Google.

## AndroidX CameraX

- Direct artifacts: `androidx.camera:camera-core`, `camera-camera2`,
  `camera-lifecycle`, and `camera-view`, version `1.6.2`
- Declared primary license: Apache License 2.0
- Copyright: The Android Open Source Project

The resolved CameraX graph also contains CameraX camera2-pipe, camera-video,
and feature-combination-query artifacts. Their AAR license files must remain
in packaged dependency metadata.

CameraX camera-core additionally declares BSD-3-Clause for bundled libyuv
portions. The complete camera-core license aggregation, including applicable
copyright and BSD text, is preserved at
`LICENSES/AndroidX-Camera-Core-LICENSE.txt`.

## Changes

No third-party source or model bytes are modified by this repository. If a
third-party Apache-licensed file is modified in the future, the modified file
must carry a prominent change notice as required by Apache-2.0 section 4(b).
