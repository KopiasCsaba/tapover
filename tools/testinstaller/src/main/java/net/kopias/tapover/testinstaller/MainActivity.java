package net.kopias.tapover.testinstaller;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Picks an APK and installs it via a PackageInstaller session with the selected package source.
 * The session result comes back to this activity (singleTop → onNewIntent); a pending user action
 * (the system "Install?" confirmation) is launched from there.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final String ACTION_RESULT = "net.kopias.tapover.testinstaller.RESULT";

    private TextView status;
    private RadioGroup source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        source = findViewById(R.id.source);
        findViewById(R.id.pick).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive");
            startActivityForResult(i, REQ_PICK);
        });
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow \"Install unknown apps\" for this app first (Android asks on install).");
        }
    }

    private int selectedSource() {
        int id = source.getCheckedRadioButtonId();
        if (id == R.id.src_unspecified) return PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED;
        if (id == R.id.src_local) return PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE;
        if (id == R.id.src_downloaded) return PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE;
        return PackageInstaller.PACKAGE_SOURCE_STORE;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            install(data.getData());
        }
    }

    private void install(Uri apk) {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int src = selectedSource();
        params.setPackageSource(src);
        try {
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id);
                 InputStream in = getContentResolver().openInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, -1)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
                out.close();
                Intent result = new Intent(ACTION_RESULT).setPackage(getPackageName())
                        .setClass(this, MainActivity.class);
                PendingIntent pending = PendingIntent.getActivity(this, id, result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(pending.getIntentSender());
            }
            status.setText("Installing with packageSource=" + src + " …");
        } catch (Exception e) {
            status.setText("Install failed: " + e);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!ACTION_RESULT.equals(intent.getAction())) return;
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm != null) startActivity(confirm);
            return;
        }
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        status.setText(st == PackageInstaller.STATUS_SUCCESS
                ? "Installed: " + intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                : "Install failed (" + st + "): " + msg);
    }
}
