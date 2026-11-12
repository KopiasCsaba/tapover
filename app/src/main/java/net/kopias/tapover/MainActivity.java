package net.kopias.tapover;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import net.kopias.tapover.detect.TapOverAccessibilityService;
import net.kopias.tapover.media.MediaPermissions;
import net.kopias.tapover.preset.Preset;
import net.kopias.tapover.preset.PresetStore;
import net.kopias.tapover.ui.EditorActivity;
import net.kopias.tapover.ui.PresetAdapter;
import net.kopias.tapover.ui.SetupCard;
import net.kopias.tapover.ui.SponsorCard;
import net.kopias.tapover.ui.WindowInsetsHelper;

/**
 * Preset manager: lists configured presets (add / edit / delete) and shows the permission controls
 * TapOver needs (draw-over-other-apps, accessibility service, media read for the latest-photo lookup).
 */
public class MainActivity extends Activity {

    private static final int REQ_MEDIA = 1001;

    private PresetStore store;
    private final List<Preset> presets = new ArrayList<>();
    private PresetAdapter adapter;

    private TextView statusOverlay;
    private TextView statusAccessibility;
    private TextView statusMedia;

    private Button btnOverlay;
    private Button btnAccessibility;
    private Button btnMedia;
    private View checkOverlay;
    private View checkAccessibility;
    private View checkMedia;
    /** List header holding the permission rows, the setup card and the presets title. */
    private View header;
    private SetupCard setupCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // targetSdk 35 lays this out edge-to-edge: the app bar spans the status bar and the
        // list below it clears the nav bar.
        WindowInsetsHelper.setupAppBar(this, getString(R.string.app_name));
        store = new PresetStore(this);
        ListView list = findViewById(R.id.presets_list);
        WindowInsetsHelper.padScrollingContent(list);
        header = getLayoutInflater().inflate(R.layout.main_header, list, false);
        list.addHeaderView(header, null, false);
        SponsorCard.addTo(this, list);

        statusOverlay = hv(R.id.status_overlay);
        statusAccessibility = hv(R.id.status_accessibility);
        statusMedia = hv(R.id.status_media);

        btnOverlay = hv(R.id.btn_grant_overlay);
        btnOverlay.setOnClickListener(v -> {
            setupCard.markTriedOverlay();
            requestOverlayPermission();
        });
        btnAccessibility = hv(R.id.btn_grant_accessibility);
        btnAccessibility.setOnClickListener(v -> {
            setupCard.markTriedAccessibility();
            openAccessibilitySettings();
        });
        btnMedia = hv(R.id.btn_grant_media);
        btnMedia.setOnClickListener(v -> requestMediaPermission());
        checkOverlay = hv(R.id.check_overlay);
        checkAccessibility = hv(R.id.check_accessibility);
        checkMedia = hv(R.id.check_media);
        setupCard = new SetupCard(this, header, btnAccessibility, this::openAccessibilitySettings,
                this::openAppInfo, this::updatePermissionStatuses);
        ((Button) hv(R.id.btn_add_preset)).setOnClickListener(v ->
                startActivity(new Intent(this, EditorActivity.class)));

        adapter = new PresetAdapter(this, presets, this::confirmDelete);
        // The empty hint lives in the header (a ListView empty view would hide the header too).
        View emptyView = hv(R.id.empty);
        adapter.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override public void onChanged() {
                emptyView.setVisibility(presets.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent i = new Intent(this, EditorActivity.class);
            int index = position - list.getHeaderViewsCount();
            if (index < 0 || index >= presets.size()) {
                return;
            }
            i.putExtra(EditorActivity.EXTRA_PRESET_ID, presets.get(index).id);
            startActivity(i);
        });
    }

    private <T extends View> T hv(int id) {
        return header.findViewById(id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        presets.clear();
        presets.addAll(store.load());
        adapter.notifyDataSetChanged();
        updatePermissionStatuses();
    }

    private void openAccessibilitySettings() {
        startSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    /**
     * Settings screens get their own task: otherwise they stack inside TapOver's task, and
     * reopening TapOver from the launcher mid-setup shows that Settings page instead of the card.
     */
    private void startSettings(Intent i) {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openAppInfo() {
        startSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void updatePermissionStatuses() {
        bindPermissionRow(statusOverlay, btnOverlay, checkOverlay, Settings.canDrawOverlays(this),
                R.string.overlay_permission_granted, R.string.overlay_permission_missing);
        bindPermissionRow(statusAccessibility, btnAccessibility, checkAccessibility,
                isAccessibilityServiceEnabled(),
                R.string.accessibility_enabled, R.string.accessibility_disabled);
        bindPermissionRow(statusMedia, btnMedia, checkMedia, hasMediaPermission(),
                R.string.media_permission_granted, R.string.media_permission_missing);
        setupCard.update(Settings.canDrawOverlays(this), isAccessibilityServiceEnabled());
    }

    /** Granted → green checkmark (button hidden); missing → the Grant/Enable button (check hidden). */
    private void bindPermissionRow(TextView status, Button grantBtn, View check, boolean granted,
                                   int grantedStr, int missingStr) {
        status.setText(granted ? grantedStr : missingStr);
        grantBtn.setVisibility(granted ? View.GONE : View.VISIBLE);
        check.setVisibility(granted ? View.VISIBLE : View.GONE);
    }

    /** Deleting is immediate and has no undo, so ask first. */
    private void confirmDelete(Preset p) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm,
                        TextUtils.isEmpty(p.name) ? p.targetPackage : p.name))
                .setPositiveButton(R.string.delete, (d, w) -> deletePreset(p))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deletePreset(Preset p) {
        presets.remove(p);
        store.save(presets);
        TapOverAccessibilityService.requestReload();
        adapter.notifyDataSetChanged();
    }

    // --- permissions ---

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void requestMediaPermission() {
        if (!hasMediaPermission()) {
            requestPermissions(MediaPermissions.required(), REQ_MEDIA);
        }
    }

    /**
     * Result of the media-permission request. If it came back denied AND the system will no longer
     * show the runtime dialog (permanently denied — "don't ask again"), the Grant button can't do
     * anything useful, so send the user to the app's settings to grant it manually. (A cancelled
     * dialog returns an empty result — we leave that alone rather than be pushy.)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MEDIA || grantResults.length == 0 || hasMediaPermission()) {
            return;
        }
        boolean canPromptAgain = false;
        for (String p : MediaPermissions.required()) {
            if (shouldShowRequestPermissionRationale(p)) {
                canPromptAgain = true;
                break;
            }
        }
        if (!canPromptAgain) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private boolean hasMediaPermission() {
        return MediaPermissions.granted(this);
    }

    private boolean isAccessibilityServiceEnabled() {
        return TapOverAccessibilityService.isEnabled(this);
    }
}
