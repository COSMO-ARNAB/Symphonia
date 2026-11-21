-keep class org.webrtc.audio.JavaAudioDeviceModule {
    private org.webrtc.audio.WebRtcAudioRecord audioInput;
}
-keep class org.webrtc.audio.WebRtcAudioRecord {
    private android.media.AudioRecord audioRecord;
}
