package com.symphonia.gate2.prototype.harness;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Isolates the one-shot Node runtime from the capture service process. */
public final class Gate2PhoneRelayService extends Service {
    private File session;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent intent) {
        session = new File(getCacheDir(), "gate2-phone-relay/" + intent.getStringExtra("session"));
        final File incoming = session;
        new Thread(() -> {
            try {
                File app = new File(incoming, "app");
                unpack(app);
                if (new File(incoming, "stop").exists()) return;
                int result = Gate2RelayProbeActivity.runNode(
                        new File(app, "phone-relay.cjs").getPath(), incoming.getPath());
                Log.i("Gate2PhoneRelay", "Relay runtime exited with " + result);
            } catch (IOException | LinkageError error) {
                Log.e("Gate2PhoneRelay", "Unable to start relay runtime", error);
            } finally {
                try { Files.write(new File(incoming, "ended").toPath(), new byte[0]); }
                catch (IOException ignored) { }
                Process.killProcess(Process.myPid());
            }
        }, "Gate2PhoneRelayNode").start();
        return new Binder();
    }

    @Override public void onDestroy() {
        if (session != null) {
            try { Files.write(new File(session, "stop").toPath(), new byte[0]); }
            catch (IOException ignored) { }
            handler.postDelayed(() -> Process.killProcess(Process.myPid()), 3000);
        }
        super.onDestroy();
    }

    private void unpack(File app) throws IOException {
        File root = app.getCanonicalFile();
        Files.createDirectories(root.toPath());
        try (ZipInputStream input = new ZipInputStream(getAssets().open("phone-relay.zip"))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                File item = new File(root, entry.getName()).getCanonicalFile();
                if (!item.toPath().startsWith(root.toPath())) throw new IOException("Invalid relay asset");
                if (entry.isDirectory()) {
                    Files.createDirectories(item.toPath());
                } else {
                    Files.createDirectories(item.toPath().getParent());
                    Files.copy(input, item.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
