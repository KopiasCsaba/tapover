package net.kopias.tapover.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import net.kopias.tapover.R;

/**
 * The main screen's "Enjoying TapOver?" card, added as the list footer so it sits below setup and
 * the presets. Tapping it just opens the sponsor page in the browser (no tracking).
 */
public final class SponsorCard {
    private static final String SPONSOR_URL = "https://kopiascsaba.hu/sponsor";

    private SponsorCard() {
    }

    public static void addTo(Activity activity, ListView list) {
        View footer = activity.getLayoutInflater().inflate(R.layout.main_sponsor, list, false);
        footer.findViewById(R.id.btn_sponsor).setOnClickListener(v -> open(activity));
        list.addFooterView(footer, null, false);
    }

    private static void open(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SPONSOR_URL)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.sponsor_no_browser, SPONSOR_URL),
                    Toast.LENGTH_LONG).show();
        }
    }
}
