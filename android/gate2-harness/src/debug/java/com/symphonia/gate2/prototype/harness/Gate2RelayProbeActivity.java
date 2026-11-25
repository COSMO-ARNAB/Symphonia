package com.symphonia.gate2.prototype.harness;

import android.app.Activity;
import android.os.Bundle;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Gate2RelayProbeActivity extends Activity {
    static { System.loadLibrary("relay_probe"); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean finished;

    static native int runNode(String script, String worker);

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView state = new TextView(this);
        state.setText("Running local mediasoup worker probe");
        state.setPadding(32, 32, 32, 32);
        state.setTextIsSelectable(true);
        setContentView(state);
        Runnable showLink = new Runnable() {
            @Override public void run() {
                if (finished) return;
                File trace = new File(getCacheDir(), "gate2-tone-probe/trace.txt");
                try {
                    for (String line : Files.readAllLines(trace.toPath())) {
                        if (!line.startsWith("JOIN ")) continue;
                        String url = line.substring(5);
                        state.setText("Synthetic tone only. Open on a second device on the same Wi-Fi. Tap here to copy. Back stops the probe.\n\n" + url);
                        state.setOnClickListener(view -> getSystemService(ClipboardManager.class)
                                .setPrimaryClip(ClipData.newPlainText("Tone probe link", url)));
                        return;
                    }
                } catch (IOException ignored) {
                    // The relay writes the link once it has bound its port.
                }
                handler.postDelayed(this, 500);
            }
        };
        new Thread(() -> {
            String result;
            try {
                File trace = new File(getCacheDir(), "gate2-tone-probe/trace.txt");
                Files.deleteIfExists(trace.toPath());
                File script = extractProbe();
                handler.post(showLink);
                File worker = new File(getApplicationInfo().nativeLibraryDir, "libmediasoup_worker.so");
                result = "Worker probe exit: " + runNode(script.getPath(), worker.getPath());
            } catch (IOException error) {
                Log.e("SymphoniaRelayProbe", "Unable to unpack probe", error);
                result = "Unable to unpack probe";
            }
            Log.i("SymphoniaRelayProbe", result);
            String message = result;
            runOnUiThread(() -> {
                finished = true;
                state.setOnClickListener(null);
                state.setText(message);
            });
        }, "RelayProbeNode").start();
    }

    @Override protected void onDestroy() {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
        // This activity owns an isolated app process dedicated to the one-shot Node runtime.
        if (!isChangingConfigurations()) Process.killProcess(Process.myPid());
    }

    private File extractProbe() throws IOException {
        File directory = new File(getCacheDir(), "gate2-tone-probe");
        Files.createDirectories(directory.toPath());
        try (ZipInputStream input = new ZipInputStream(getAssets().open("probe.zip"))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                File item = new File(directory, entry.getName()).getCanonicalFile();
                if (!item.toPath().startsWith(directory.getCanonicalFile().toPath())) {
                    throw new IOException("Invalid asset path");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(item.toPath());
                } else {
                    Files.createDirectories(item.toPath().getParent());
                    Files.copy(input, item.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return new File(directory, "probe.cjs");
    }
}
