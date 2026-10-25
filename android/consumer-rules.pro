# MediaPipe framework JNI entry points and callback contracts.
# Kept in sync with the narrowly scoped rules published in:
# https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/java/com/google/mediapipe/framework/proguard.pgcfg

# Keep public members of MediaPipe's public framework interfaces. This also
# preserves matching methods on implementing callback classes.
-keep public interface com.google.mediapipe.framework.* {
    public *;
}

# Invoked by native code.
-keep public class com.google.mediapipe.framework.Packet {
    public static *** create(***);
    public long getNativeHandle();
    public void release();
}

# Invoked by native code.
-keep public class com.google.mediapipe.framework.PacketCreator {
    *** releaseWithSyncToken(...);
}

# Invoked by native code.
-keep public class com.google.mediapipe.framework.MediaPipeException {
    <init>(int, byte[]);
}

# Required by PacketCreator#createProto. JNI reads these fields by name.
-keep class com.google.mediapipe.framework.ProtoUtil$SerializedMessage { *; }
