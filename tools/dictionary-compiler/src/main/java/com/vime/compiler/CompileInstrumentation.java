package com.vime.compiler;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.vime.ime.engine.RimeNative;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** No product Application, recordings, user data import, network or learning calls. */
public final class CompileInstrumentation extends Instrumentation {
    private String runId;
    private static final Set<String> INPUTS = Set.of("default.yaml", "essay.txt", "vime_pinyin.dict.yaml", "vime_pinyin.schema.yaml", "vime_nine.schema.yaml");
    private static final Set<String> OUTPUTS = Set.of("default.yaml", "vime_nine.prism.bin", "vime_nine.schema.yaml", "vime_pinyin.prism.bin", "vime_pinyin.reverse.bin", "vime_pinyin.schema.yaml", "vime_pinyin.table.bin");

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        runId = arguments == null ? "" : arguments.getString("runId", "");
        start();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    private byte[] asset(String name) throws Exception {
        try (InputStream input = getTargetContext().getAssets().open("rime/" + name)) { return input.readAllBytes(); }
    }
    private static void phrase(RimeNative nativeApi, JSONObject probes, String raw, String expected) throws Exception {
        JSONArray rows = new JSONArray(); boolean found = false;
        for (String row : nativeApi.candidates(raw, 0, 256)) {
            rows.put(row);
            String[] parts = row.split("\t", -1);
            if (parts.length == 3 && parts[0].equals(expected) && parts[1].equals(Integer.toString(raw.length()))) found = true;
        }
        probes.put(raw, rows);
        check(found, "Missing complete candidate " + raw + " => " + expected);
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); JSONObject report = new JSONObject();
        RimeNative nativeApi = null;
        File root = null;
        long started = SystemClock.elapsedRealtime();
        int code = Activity.RESULT_CANCELED;
        try {
            check(Build.PRODUCT.contains("sdk") && Build.SUPPORTED_ABIS[0].equals("x86_64"), "Owned x86 emulator required");
            check(Os.sysconf(OsConstants._SC_PAGESIZE) == 16384, "Strict 16KB process required");
            check(runId.matches("[A-Za-z0-9_-]{1,48}"), "Fresh runId required");
            check(getTargetContext().getPackageName().equals("com.vime.dictionary.compiler"), "Isolated compiler package required");
            root = new File(getTargetContext().getFilesDir(), "runs/" + runId);
            check(!root.exists(), "Existing compiler run is preserved");
            File shared = new File(root, "shared"), user = new File(root, "user");
            check(shared.mkdirs() && user.mkdirs(), "Cannot create isolated directories");
            JSONObject manifest = new JSONObject(new String(asset("manifest.json"), java.nio.charset.StandardCharsets.UTF_8));
            check(manifest.getString("format").equals("vime-rime-2") && !manifest.getBoolean("compiled"), "Plain source manifest required");
            JSONObject files = manifest.getJSONObject("files");
            Set<String> names = new HashSet<>(); for (Iterator<String> it = files.keys(); it.hasNext();) names.add(it.next());
            check(names.equals(INPUTS), "Only known public plaintext inputs are allowed");
            for (String name : INPUTS) {
                byte[] bytes = asset(name);
                check(sha(bytes).equals(files.getString(name)), "Input hash mismatch " + name);
                File target = new File(shared, name);
                Files.write(target.toPath(), bytes);
                check(target.setLastModified(manifest.getLong("compiler_source_mtime_ms")), "Cannot normalize compiler source mtime");
            }
            report.put("run_id", runId).put("source_id", manifest.getString("source_id"))
                .put("process_page_size", Os.sysconf(OsConstants._SC_PAGESIZE));
            nativeApi = new RimeNative();
            report.put("rime_version", nativeApi.initialize(shared.getAbsolutePath(), user.getAbsolutePath()));
            report.put("compile_ms", SystemClock.elapsedRealtime() - started);
            JSONObject probes = new JSONObject();
            report.put("probes", probes);
            phrase(nativeApi, probes, "nihao", "你好");
            phrase(nativeApi, probes, "zhendejiade", "真的假的");
            phrase(nativeApi, probes, "xuexiyingyu", "学习英语");
            phrase(nativeApi, probes, "64426", "你好");
            phrase(nativeApi, probes, "nihao", "你好");
            // Frequent conversational phrases absent as CC-CEDICT headwords.
            // These are probes only; they are never added to compiler input.
            String[][] conversation = {{"liangge", "两个"}, {"geiwo", "给我"}, {"geini", "给你"},
                {"yeshi", "也是"}, {"doushi", "都是"}, {"wode", "我的"}, {"nide", "你的"},
                {"haode", "好的"}, {"henduo", "很多"}, {"zheshi", "这是"}, {"woye", "我也"},
                {"laile", "来了"}, {"haimei", "还没"}, {"yitian", "一天"}, {"woshi", "我是"},
                {"rangwo", "让我"}, {"haiyao", "还要"}, {"wojiu", "我就"}, {"youge", "有个"},
                {"deren", "的人"}, {"haole", "好了"}, {"nani", "那你"}, {"yeyou", "也有"},
                {"douyou", "都有"}, {"tade", "他的"}, {"woxiang", "我想"}, {"qule", "去了"},
                {"yinian", "一年"}, {"najiu", "那就"}, {"bangwo", "帮我"}};
            for (String[] pair : conversation) phrase(nativeApi, probes, pair[0], pair[1]);
            nativeApi.shutdown(); nativeApi = null;
            File build = new File(user, "build");
            String[] generated = build.list();
            check(generated != null && new HashSet<>(Arrays.asList(generated)).equals(OUTPUTS), "Unexpected compiled public output set");
            JSONObject outputs = new JSONObject();
            for (String name : OUTPUTS) {
                File file = new File(build, name);
                check(file.isFile() && file.length() > 0, "Missing compiler output " + name);
                outputs.put(name, new JSONObject().put("sha256", sha(Files.readAllBytes(file.toPath()))).put("bytes", file.length()));
            }
            report.put("outputs", outputs).put("passed", true);
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            try { report.put("passed", false).put("error", error.toString()); } catch (Exception ignored) { }
        } finally {
            if (nativeApi != null) try { nativeApi.shutdown(); } catch (Throwable ignored) { }
            // Large candidate evidence must not travel in an ActivityManager
            // result Bundle. Export its exact bytes through the guarded host.
            try {
                check(root != null && root.isDirectory(), "No compiler output directory");
                byte[] bytes = report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(new File(root, "report.json").toPath(), bytes);
                result.putString("report", new JSONObject().put("run_id", runId)
                    .put("report_sha256", sha(bytes)).put("report_bytes", bytes.length).toString());
            } catch (Throwable error) {
                result.putString("report", "{\"passed\":false,\"error\":\"No persisted compiler report\"}");
                code = Activity.RESULT_CANCELED;
            }
            finish(code, result);
        }
    }
}
