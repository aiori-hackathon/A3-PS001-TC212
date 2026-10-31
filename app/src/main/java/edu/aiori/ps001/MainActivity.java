package edu.aiori.ps001;

import android.app.Activity;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Controlled lab prototype. Operations stay in the app-private lab directory. */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<JSONObject> eventLog = Collections.synchronizedList(new ArrayList<>());
    private final List<JSONObject> runLog = Collections.synchronizedList(new ArrayList<>());
    private TextView status, output;
    private File targetDir;
    private LabObserver observer;
    private volatile RunContext activeRun;
    private volatile long runStartMs, alertTimestampMs;
    private volatile boolean alertRaised;
    private volatile int filesAtAlert, eventsAtAlert, alertCount, truePositiveAlerts;
    private volatile int falsePositiveAlerts, detectedSimulations, simulationRuns;
    private volatile String currentRunId = "", currentLabel = "", currentWorkload = "";
    private static final long WINDOW_MS = 2000;
    private static final int MIN_DISTINCT_FILES = 5, MIN_MUTATIONS = 10;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        targetDir = new File(getFilesDir(), "lab_target");
        if (!targetDir.exists()) targetDir.mkdirs();
        buildUi();
        startObserver();
        log("Lab directory: " + targetDir.getAbsolutePath());
        log("Dummy files only. FileObserver OPEN coverage is not assumed; observed mutations are scored.");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 24, 24, 24);
        page.setBackgroundColor(0xFF10151E);
        scroll.addView(page);
        TextView title = new TextView(this);
        title.setText("AIORI 3 · PS-001\\nRansomware Early Warning");
        title.setTextSize(24); title.setTextColor(0xFFF1F5F9);
        page.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Controlled lab • dummy files • scoped response");
        subtitle.setTextColor(0xFF9CA3AF); subtitle.setPadding(0, 8, 0, 16);
        page.addView(subtitle);
        addButton(page, "Run all 5 workloads", v -> runSuite());
        addButton(page, "Benign 1 · sequential updates", v -> runWorkload("benign_sequential", false, 12, 180));
        addButton(page, "Benign 2 · spaced bulk update", v -> runWorkload("benign_spaced", false, 12, 500));
        addButton(page, "Simulation 1 · rapid writes", v -> runWorkload("sim_rapid_writes", true, 20, 25));
        addButton(page, "Simulation 2 · rename burst", v -> runWorkload("sim_rename_burst", true, 20, 25));
        addButton(page, "Simulation 3 · mixed write/rename", v -> runWorkload("sim_mixed_burst", true, 20, 25));
        addButton(page, "Export JSON + CSV evidence", v -> exportEvidence());
        addButton(page, "Reset lab files and counters", v -> resetLab());
        status = new TextView(this); status.setTextColor(0xFF93C5FD);
        status.setPadding(0, 18, 0, 8); page.addView(status);
        output = new TextView(this); output.setTextColor(0xFFE5E7EB);
        output.setTextSize(13); output.setPadding(0, 8, 0, 20); page.addView(output);
        setContentView(scroll); updateStatus();
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(listener); parent.addView(b);
    }

    private void startObserver() {
        if (observer != null) observer.stopWatching();
        observer = new LabObserver(targetDir.getAbsolutePath());
        observer.startWatching();
    }

    private void runSuite() {
        if (activeRun != null) { log("A workload is already running."); return; }
        String[][] cases = {
            {"benign_sequential","false","12","180"},
            {"benign_spaced","false","12","500"},
            {"sim_rapid_writes","true","20","25"},
            {"sim_rename_burst","true","20","25"},
            {"sim_mixed_burst","true","20","25"}
        };
        runNext(cases, 0);
    }

    private void runNext(String[][] cases, int i) {
        if (i >= cases.length) { log("Suite complete. Export evidence for evaluation."); return; }
        String[] c = cases[i];
        runWorkload(c[0], Boolean.parseBoolean(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]),
            () -> main.postDelayed(() -> runNext(cases, i + 1), 700));
    }

    private void runWorkload(String name, boolean simulation, int count, int delay) {
        runWorkload(name, simulation, count, delay, null);
    }

    private void runWorkload(String name, boolean simulation, int count, int delay, Runnable completion) {
        if (activeRun != null) { log("Busy: wait for the current run."); return; }
        clearTarget();
        synchronized (eventLog) { eventLog.clear(); }
        currentRunId = UUID.randomUUID().toString();
        currentLabel = simulation ? "simulation" : "benign";
        currentWorkload = name;
        runStartMs = System.currentTimeMillis(); alertTimestampMs = 0;
        alertRaised = false; filesAtAlert = 0; eventsAtAlert = 0;
        RunContext ctx = new RunContext(currentRunId, simulation, name, runStartMs);
        activeRun = ctx;
        if (simulation) simulationRuns++;
        log("START " + name + " | " + currentRunId); updateStatus();
        ctx.future = executor.submit(() -> {
            String outcome = "completed";
            try {
                for (int i = 0; i < count && !ctx.cancelled.get(); i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    File file = new File(targetDir, String.format(Locale.US, "dummy_%02d.txt", i));
                    if ("sim_rename_burst".equals(name)) {
                        File temp = new File(targetDir, "stage_" + i + ".tmp");
                        writeDummy(temp, "harmless simulated content " + i + "\\n");
                        if (!ctx.cancelled.get() && !temp.renameTo(file)) writeDummy(file, "fallback dummy\\n");
                    } else if ("sim_mixed_burst".equals(name)) {
                        writeDummy(file, "harmless simulated content " + i + "\\n");
                        File renamed = new File(targetDir, "renamed_" + i + ".txt");
                        if (!ctx.cancelled.get()) file.renameTo(renamed);
                    } else {
                        writeDummy(file, (simulation ? "harmless simulation " : "benign update ") + i + "\\n");
                    }
                    if (delay > 0) Thread.sleep(delay);
                }
                if (ctx.cancelled.get()) outcome = "stopped_by_detector";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome = ctx.cancelled.get() ? "stopped_by_detector" : "interrupted";
            } catch (Exception e) {
                outcome = "error:" + e.getClass().getSimpleName();
                log("Workload error: " + e.getMessage());
            } finally {
                String finalOutcome = outcome;
                main.post(() -> finishRun(ctx, finalOutcome, completion));
            }
        });
    }

    private void writeDummy(File file, String value) throws IOException {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private void finishRun(RunContext ctx, String outcome, Runnable completion) {
        if (activeRun != ctx) return;
        long end = System.currentTimeMillis();
        JSONObject r = new JSONObject();
        try {
            r.put("run_id", ctx.id); r.put("workload", ctx.workload);
            r.put("label", ctx.simulation ? "simulation" : "benign");
            r.put("start_ms", ctx.startMs); r.put("end_ms", end);
            r.put("duration_ms", end - ctx.startMs); r.put("outcome", outcome);
            r.put("alert", ctx.alerted); r.put("alert_timestamp_ms", ctx.alertTimestampMs);
            r.put("detection_delay_ms", ctx.alerted ? Math.max(0, ctx.alertTimestampMs - ctx.startMs) : JSONObject.NULL);
            r.put("distinct_files_at_alert", ctx.filesAtAlert); r.put("events_at_alert", ctx.eventsAtAlert);
        } catch (Exception ignored) {}
        runLog.add(r);
        log("END " + ctx.workload + " | " + outcome + (ctx.alerted ? " | ALERT" : " | no alert"));
        activeRun = null; updateStatus();
        if (completion != null) completion.run();
    }

    private void onFsEvent(int event, String path) {
        if (path == null || path.isEmpty()) return;
        String type = eventName(event);
        long now = System.currentTimeMillis();
        JSONObject entry = new JSONObject();
        try {
            entry.put("timestamp_ms", now); entry.put("run_id", currentRunId);
            entry.put("label", currentLabel); entry.put("workload", currentWorkload);
            entry.put("event", type); entry.put("path", path);
            entry.put("elapsed_ms", Math.max(0, now - runStartMs));
        } catch (Exception ignored) {}
        eventLog.add(entry);
        RunContext ctx = activeRun;
        if (ctx == null || ctx.alerted) return;

        long cutoff = now - WINDOW_MS;
        java.util.HashSet<String> distinct = new java.util.HashSet<>();
        int mutations = 0, renames = 0, recentCount = 0;
        synchronized (eventLog) {
            for (JSONObject e : eventLog) {
                if (e.optLong("timestamp_ms", 0) < cutoff ||
                    !e.optString("run_id").equals(ctx.id)) continue;
                String ev = e.optString("event");
                if (ev.contains("WRITE") || ev.contains("CREATE") || ev.contains("DELETE") || ev.contains("MOVE")) mutations++;
                if (ev.contains("MOVE")) renames++;
                distinct.add(e.optString("path")); recentCount++;
            }
        }
        int score = 0;
        if (mutations >= MIN_MUTATIONS) score += 2;
        if (distinct.size() >= MIN_DISTINCT_FILES) score += 2;
        if (renames >= 3) score += 1;
        if (score >= 4) {
            ctx.alerted = true; ctx.alertTimestampMs = now;
            ctx.filesAtAlert = distinct.size(); ctx.eventsAtAlert = recentCount;
            alertRaised = true; alertTimestampMs = now; filesAtAlert = distinct.size(); eventsAtAlert = recentCount;
            alertCount++;
            if (ctx.simulation) { truePositiveAlerts++; detectedSimulations++; }
            else falsePositiveAlerts++;
            log("ALERT score=" + score + " mutations=" + mutations + " paths=" + distinct.size() + " renames=" + renames);
            // Cancel only the harness Future/token registered for this run.
            ctx.cancelled.set(true);
            if (ctx.future != null) ctx.future.cancel(true);
            updateStatus();
        }
    }

    private String eventName(int event) {
        if ((event & (FileObserver.MOVED_FROM | FileObserver.MOVED_TO)) != 0) return "MOVE_RENAME";
        if ((event & FileObserver.CLOSE_WRITE) != 0) return "CLOSE_WRITE";
        if ((event & FileObserver.MODIFY) != 0) return "MODIFY_WRITE";
        if ((event & FileObserver.CREATE) != 0) return "CREATE";
        if ((event & FileObserver.DELETE) != 0) return "DELETE";
        if ((event & FileObserver.ATTRIB) != 0) return "ATTRIB";
        return "OTHER_" + event;
    }

    private class LabObserver extends FileObserver {
        LabObserver(String path) { super(path, FileObserver.ALL_EVENTS); }
        @Override public void onEvent(int event, String path) { onFsEvent(event, path); }
    }

    private static class RunContext {
        final String id, workload;
        final boolean simulation;
        final long startMs;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile Future<?> future;
        volatile boolean alerted;
        volatile long alertTimestampMs;
        volatile int filesAtAlert, eventsAtAlert;
        RunContext(String id, boolean simulation, String workload, long startMs) {
            this.id = id; this.simulation = simulation; this.workload = workload; this.startMs = startMs;
        }
    }

    private void exportEvidence() {
        try {
            File out = new File(getFilesDir(), "exports"); if (!out.exists()) out.mkdirs();
            JSONArray events = new JSONArray(), runs = new JSONArray();
            synchronized (eventLog) { for (JSONObject e : eventLog) events.put(e); }
            synchronized (runLog) { for (JSONObject r : runLog) runs.put(r); }
            JSONObject all = new JSONObject();
            all.put("project", "AIORI 3 PS-001"); all.put("exported_at_ms", System.currentTimeMillis());
            all.put("scope", targetDir.getAbsolutePath());
            all.put("thresholds", new JSONObject().put("window_ms", WINDOW_MS)
                .put("min_mutations", MIN_MUTATIONS).put("min_distinct_files", MIN_DISTINCT_FILES));
            all.put("events", events); all.put("runs", runs);
            File json = new File(out, "evidence.json");
            try (FileWriter w = new FileWriter(json)) { w.write(all.toString(2)); }
            File csvFile = new File(out, "events.csv");
            try (FileWriter w = new FileWriter(csvFile)) {
                w.write("timestamp_ms,run_id,label,workload,event,path,elapsed_ms\\n");
                for (int i = 0; i < events.length(); i++) {
                    JSONObject e = events.getJSONObject(i);
                    w.write(csv(e.optString("timestamp_ms")) + "," + csv(e.optString("run_id")) + "," +
                        csv(e.optString("label")) + "," + csv(e.optString("workload")) + "," +
                        csv(e.optString("event")) + "," + csv(e.optString("path")) + "," +
                        csv(e.optString("elapsed_ms")) + "\\n");
                }
            }
            log("Exported evidence.json and events.csv to app-private files/exports. Retrieve via Android Studio Device Explorer.");
        } catch (Exception e) { log("Export failed: " + e.getMessage()); }
    }

    private String csv(String s) { return "\\"" + s.replace("\\"", "\\"\\"") + "\\""; }

    private void resetLab() {
        if (activeRun != null) { log("Wait for current workload before reset."); return; }
        clearTarget(); eventLog.clear(); runLog.clear();
        alertCount = truePositiveAlerts = falsePositiveAlerts = detectedSimulations = simulationRuns = 0;
        log("Lab reset."); updateStatus();
    }

    private void clearTarget() {
        File[] children = targetDir.listFiles();
        if (children != null) for (File f : children) if (f.isFile()) f.delete();
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText("Target: app-private lab directory\\nRuns logged: " + runLog.size() +
            " | Events: " + eventLog.size() + "\\nAlerts: " + alertCount +
            " | TP alerts: " + truePositiveAlerts + " | FP alerts: " + falsePositiveAlerts +
            "\\nDetected simulations: " + detectedSimulations + "/" + simulationRuns);
    }

    private void log(String msg) {
        if (output == null) return;
        main.post(() -> {
            output.append("• " + msg + "\\n");
            if (output.getLineCount() > 250) output.setText("Recent output truncated.\\n");
        });
    }

    @Override protected void onDestroy() {
        if (observer != null) observer.stopWatching();
        RunContext ctx = activeRun;
        if (ctx != null) { ctx.cancelled.set(true); if (ctx.future != null) ctx.future.cancel(true); }
        executor.shutdownNow(); super.onDestroy();
    }
}
