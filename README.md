# straight_face_landmarker

WORLD OOGIRI向けのAndroid-only Flutter pluginです。CameraXのフロントカメラ、
MediaPipe Face Landmarkerの`LIVE_STREAM`、MethodChannel/EventChannel、
PlatformViewを提供します。製品判定はhost側のDart engineが担当します。

失格証拠画像は違反開始timestampと同じ解析済みframeだけを有限メモリへ保持し、
近傍frameへの置換は行いません。画像は明示的な保存操作まで端末メモリだけで扱い、
保存・破棄・Session終了のAPIを提供します。このpluginは顔画像をFirebase、
Analytics、Crashlyticsへ送信する機能を実装していません。

## Integration

FlutterFlow/Flutter hostからは、公開済みの固定commit SHAを指定して取り込みます。
認証情報やPersonal Access TokenをGit URLへ埋め込まないでください。

このpluginはAndroid API 24以上を対象とし、host projectが提供するAndroid Gradle
PluginとKotlin Android pluginを使用します。plugin自身はAGP/Kotlinのversionを
上書きしません。

`android/src/main/assets/face_landmarker.task`はGoogle MediaPipe公式の固定モデルです。
配布前に次の値を検証してください。

- Bytes: `3758596`
- SHA-256: `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`

## License

WORLD OOGIRI-authored source code is proprietary and all rights are reserved.
Repository visibility does not grant an open-source license. Third-party
libraries and the Face Landmarker model retain their own terms; see
`THIRD_PARTY_NOTICES.md` and `LICENSES/`.
