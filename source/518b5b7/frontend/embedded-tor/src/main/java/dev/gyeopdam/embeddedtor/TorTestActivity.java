package dev.gyeopdam.embeddedtor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Deliberately separate from the offline conversation demo; no user message or endpoint input. */
public final class TorTestActivity extends Activity {
    // Android's documented compatibility key; a preview-redaction hint, not an access control.
    private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private EmbeddedTorController controller;
    private final Runnable changed = this::render;
    private TextView status;
    private Button start;
    private Button stop;
    private Button probe;
    private TextView probeStatus;
    private final DiagnosticLog diagnostics = new DiagnosticLog(SystemClock::elapsedRealtime);
    private TextView diagnosticText;
    private Button copyLog;
    private Button clearLog;
    private AlertDialog copyWarning;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText(R.string.title); title.setTextSize(24);
        content.addView(title);
        TextView warning = new TextView(this);
        warning.setText(R.string.warning); content.addView(warning);
        status = new TextView(this);
        status.setTextSize(20); status.setPadding(0, padding, 0, padding); content.addView(status);
        start = new Button(this); start.setText(R.string.start); content.addView(start);
        stop = new Button(this); stop.setText(R.string.stop); content.addView(stop);
        TextView probeInfo = new TextView(this);
        probeInfo.setText(R.string.probe_info); content.addView(probeInfo);
        probe = new Button(this); probe.setText(R.string.probe); content.addView(probe);
        probeStatus = new TextView(this);
        probeStatus.setPadding(0, padding, 0, padding); content.addView(probeStatus);
        TextView lifecycle = new TextView(this);
        lifecycle.setText(R.string.lifecycle); content.addView(lifecycle);
        TextView logTitle = new TextView(this);
        logTitle.setText(R.string.log_title); logTitle.setTextSize(20);
        logTitle.setPadding(0, padding, 0, padding / 2); content.addView(logTitle);
        TextView logInfo = new TextView(this);
        logInfo.setText(R.string.log_info); content.addView(logInfo);
        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        copyLog = new Button(this); copyLog.setText(R.string.log_copy);
        clearLog = new Button(this); clearLog.setText(R.string.log_clear);
        logActions.addView(copyLog, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        logActions.addView(clearLog, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(logActions);
        diagnosticText = new TextView(this);
        diagnosticText.setTypeface(Typeface.MONOSPACE); diagnosticText.setTextSize(12);
        // Do not let view-state restoration or implicit selection create a second export path.
        diagnosticText.setSaveEnabled(false); diagnosticText.setTextIsSelectable(false);
        diagnosticText.setPadding(0, padding / 2, 0, 0); content.addView(diagnosticText);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(TorTalkInsets.host(this,scroll));
        controller = EmbeddedTorController.get(this);
        controller.addListener(changed);
        diagnostics.recordAction(DiagnosticLog.Action.SCREEN_OPENED);
        start.setOnClickListener(view -> {
            diagnostics.recordAction(DiagnosticLog.Action.START_REQUESTED);
            controller.start(); render();
        });
        stop.setOnClickListener(view -> {
            diagnostics.recordAction(DiagnosticLog.Action.STOP_REQUESTED);
            controller.stop(); render();
        });
        probe.setOnClickListener(view -> {
            diagnostics.recordAction(DiagnosticLog.Action.PROBE_REQUESTED);
            controller.requestProbe(); render();
        });
        copyLog.setOnClickListener(view -> confirmCopy());
        clearLog.setOnClickListener(view -> {
            diagnostics.clear(); renderLog();
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
        });
        render();
    }
    private void render() {
        TorReadinessGate.Phase phase = controller.phase();
        switch (phase) {
            case READY: status.setText(R.string.ready); break;
            case STARTING: status.setText(getString(R.string.starting, controller.progress())); break;
            case FAILED: status.setText(R.string.failed); break;
            default: status.setText(R.string.stopped);
        }
        switch(controller.nativeStopState()) {
            case STOP_REQUESTED: status.setText(R.string.tor_stop_requested); break;
            case OFFLINE_CONFIRMED: status.setText(R.string.tor_offline_confirmed); break;
            case TERMINATED_CONFIRMED: status.setText(R.string.tor_terminated_confirmed); break;
            case TERMINATION_UNCONFIRMED: status.setText(R.string.tor_termination_unconfirmed); break;
            default: break;
        }
        start.setEnabled(controller.canStart());
        stop.setEnabled(phase == TorReadinessGate.Phase.STARTING || phase == TorReadinessGate.Phase.READY);
        OnionProbeSession.Result result = controller.probeResult();
        diagnostics.recordTor(phase, controller.progress());
        diagnostics.recordProbe(result.state, result.stage, result.reason, result.socksStatus,
                result.httpStatus, result.bodyBytes, result.elapsedMillis);
        switch (result.state) {
            case RUNNING: probeStatus.setText(getString(R.string.probe_running_stage,
                    result.stage.name(), result.elapsedMillis)); break;
            case RESPONSE:
                probeStatus.setText(getString(R.string.probe_response, result.httpStatus, result.bodyBytes)); break;
            case FAILED: probeStatus.setText(getString(R.string.probe_diagnostic, getString(R.string.probe_failed),
                    result.diagnosticCode(), result.elapsedMillis)); break;
            case TIMED_OUT: probeStatus.setText(getString(R.string.probe_diagnostic, getString(R.string.probe_timeout),
                    result.diagnosticCode(), result.elapsedMillis)); break;
            case CANCELLED: probeStatus.setText(R.string.probe_cancelled); break;
            default: probeStatus.setText(R.string.probe_idle);
        }
        probe.setEnabled(controller.canProbe());
        renderLog();
    }
    private void renderLog() {
        String text = diagnostics.isEmpty() ? getString(R.string.log_empty) : diagnostics.snapshot();
        if (!text.contentEquals(diagnosticText.getText())) diagnosticText.setText(text);
        copyLog.setEnabled(!diagnostics.isEmpty());
        clearLog.setEnabled(!diagnostics.isEmpty());
    }
    private void confirmCopy() {
        if (diagnostics.isEmpty() || (copyWarning != null && copyWarning.isShowing())) return;
        copyWarning = new AlertDialog.Builder(this)
                .setTitle(R.string.log_copy)
                .setMessage(R.string.log_copy_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.log_copy_confirm, (dialog, which) -> copyDiagnostics())
                .create();
        // Dialog windows need the same screenshot protection as the owning Activity.
        if (copyWarning.getWindow() != null) {
            copyWarning.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        copyWarning.show();
    }
    private void copyDiagnostics() {
        if (diagnostics.isEmpty()) return;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) { copyFailed(); return; }
        // Never read the old clipboard, and never clear clipboard content belonging to another app.
        ClipData clip = ClipData.newPlainText(getString(R.string.log_clip_label), diagnostics.snapshot());
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        try {
            clipboard.setPrimaryClip(clip);
        } catch (RuntimeException unavailable) {
            // Clipboard failures are fixed codes too; never retain or display exception text.
            copyFailed();
            return;
        }
        diagnostics.recordAction(DiagnosticLog.Action.COPY_SUCCEEDED);
        renderLog();
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
    }
    private void copyFailed() {
        diagnostics.recordAction(DiagnosticLog.Action.COPY_FAILED);
        renderLog();
        Toast.makeText(this, R.string.log_copy_failed, Toast.LENGTH_SHORT).show();
    }
    @Override public void onStop() {
        if (copyWarning != null) { copyWarning.dismiss(); copyWarning = null; }
        diagnostics.recordAction(DiagnosticLog.Action.SCREEN_STOPPED);
        controller.cancelProbe();
        controller.detachForeground();
        super.onStop();
    }
    @Override public void onStart() {
        super.onStart();
        controller.attachForeground();
    }
    @Override public void onDestroy() {
        controller.removeListener(changed);
        diagnostics.clear();
        super.onDestroy();
    }
}
