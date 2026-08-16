package com.maxbriand.audiotimer;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/*
 * Sends the outbox to the server, whenever the phone next has a network.
 *
 * This exists because of how the phone is actually used: the SIM comes out at night, which is
 * exactly when the app is open and recording, and goes back in during the day, when the app is
 * closed. A page-level "upload when online" listener would never fire — the WebView is not
 * running at the moment connectivity returns. WorkManager is, so the constraint below is the
 * whole point of the feature: Android holds the job until there is a network and then runs it
 * with the app closed, and reschedules it across reboots.
 *
 * The contract with the server is deliberately narrow, because a night is deleted from the
 * phone off the back of it:
 *   - the server upserts by session id, so a retry after a dropped response cannot duplicate;
 *   - it answers 2xx with {"accepted":[ids…]}, and only those ids are dropped from the outbox.
 * A 2xx with no readable body is taken as "all of them" — that is the only assumption made,
 * and it is the same one a plain REST client would make.
 */
public class UploadWorker extends Worker {
  static final String WORK = "log-upload";

  private static final int MAX_BATCH = 100;      // sessions per request
  private static final int MAX_ROUNDS = 20;      // batches per run, before leaving the rest
  private static final int CONNECT_MS = 15000;
  private static final int READ_MS = 30000;

  public UploadWorker(@NonNull Context c, @NonNull WorkerParameters p){ super(c, p); }

  /* Queue a run. KEEP, not REPLACE: an enqueue that lands while a run is already waiting or
     retrying must not reset its backoff — the file is in the outbox either way, and the run
     that eventually fires picks up everything it finds. */
  static void schedule(Context c){
    WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request(false));
  }

  /* The ⚙ "Send now" button. REPLACE so a job sitting in a long backoff is not what decides
     when the user's tap takes effect. */
  static void scheduleNow(Context c){
    WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(true));
  }

  static void cancel(Context c){ WorkManager.getInstance(c).cancelUniqueWork(WORK); }

  private static OneTimeWorkRequest request(boolean now){
    OneTimeWorkRequest.Builder b = new OneTimeWorkRequest.Builder(UploadWorker.class)
      .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS);
    if (now) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
    return b.build();
  }

  @NonNull
  @Override
  public Result doWork(){
    Context c = getApplicationContext();
    String url = Outbox.url(c);
    if (url.isEmpty()) return Result.success();          // not set up: nothing to do, ever

    for (int round = 0; round < MAX_ROUNDS; round++){
      List<File> files = Outbox.list(c);
      if (files.isEmpty()){
        Outbox.setStatus(c, null, false);
        return Result.success();
      }
      if (files.size() > MAX_BATCH) files = files.subList(0, MAX_BATCH);

      // id -> file, built from the file contents rather than the names, so the ids the server
      // answers with map back to exactly the files that carried them.
      Map<String, File> batch = new HashMap<>();
      JSONArray sessions = new JSONArray();
      for (File f : files){
        try {
          JSONObject o = new JSONObject(Outbox.read(f));
          String id = o.optString("id", "");
          if (id.isEmpty()){ f.delete(); continue; }     // not a session row: drop it
          batch.put(id, f);
          sessions.put(o);
        } catch (Exception e){
          f.delete();                                    // unreadable: it will never send
        }
      }
      if (batch.isEmpty()) return Result.success();

      Result r = send(c, url, sessions, batch);
      if (r != null) return r;                           // failed or retrying — stop here
    }
    // Still more waiting than one run should push. Come back for the rest.
    schedule(c);
    return Result.success();
  }

  /* Returns null to carry on with the next batch, or the Result this run should end with. */
  private Result send(Context c, String url, JSONArray sessions, Map<String, File> batch){
    HttpURLConnection conn = null;
    try {
      JSONObject body = new JSONObject();
      body.put("app", "audio-timer");
      body.put("device", Outbox.device(c));
      body.put("sentAt", utcNow());
      body.put("sessions", sessions);
      byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(CONNECT_MS);
      conn.setReadTimeout(READ_MS);
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(payload.length);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      String token = Outbox.token(c);
      if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
      try (OutputStream out = conn.getOutputStream()){ out.write(payload); }

      int status = conn.getResponseCode();
      if (status >= 200 && status < 300){
        List<String> accepted = acceptedIds(readBody(conn, false), batch);
        for (String id : accepted) Outbox.remove(c, id);
        Outbox.markUploaded(c, accepted);
        Outbox.setStatus(c, null, true);
        // A 2xx that accepted nothing would spin this loop forever against the same files.
        return accepted.isEmpty() ? Result.success() : null;
      }

      // Permanent: nothing about waiting or trying again changes the answer, and a job that
      // keeps retrying a rejected token just burns battery. A new night, or saving the
      // settings again, enqueues fresh work.
      if (status == 401 || status == 403){
        Outbox.setStatus(c, "server rejected the token", false);
        return Result.failure();
      }
      if (status >= 400 && status < 500 && status != 408 && status != 429){
        Outbox.setStatus(c, "server refused the log (HTTP " + status + ")", false);
        return Result.failure();
      }
      Outbox.setStatus(c, "server busy (HTTP " + status + ") — will retry", false);
      return Result.retry();
    } catch (Exception e){
      // Offline mid-flight, DNS, TLS, timeout: all the ordinary ways a phone's network is not
      // there. Nothing was deleted, so the retry simply sends the same files again.
      Outbox.setStatus(c, "could not reach the server — will retry", false);
      return Result.retry();
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private static String utcNow(){
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }

  /* Which ids the server says it stored. No usable answer means the 2xx stands for the whole
     batch — but an explicit list always wins, so a server that only took some keeps the rest. */
  private List<String> acceptedIds(String text, Map<String, File> batch){
    List<String> all = new ArrayList<>(batch.keySet());
    if (text == null || text.isEmpty()) return all;
    try {
      JSONArray arr = new JSONObject(text).optJSONArray("accepted");
      if (arr == null) return all;
      List<String> out = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++){
        String id = arr.optString(i, "");
        if (batch.containsKey(id)) out.add(id);
      }
      return out;
    } catch (Exception e){
      return all;
    }
  }

  private String readBody(HttpURLConnection conn, boolean error){
    try (InputStream in = error ? conn.getErrorStream() : conn.getInputStream()){
      if (in == null) return "";
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      // Bounded: a misconfigured URL pointing at something enormous must not be read into RAM.
      while ((n = in.read(buf)) > 0 && bos.size() < 64 * 1024) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    } catch (Exception e){
      return "";
    }
  }

  /* Only ever called from the page, where a bad URL can still be reported to the user. */
  static boolean usableUrl(String url){
    try {
      Uri u = Uri.parse(url);
      String s = u.getScheme();
      if (s == null || u.getHost() == null) return false;
      return s.equalsIgnoreCase("https") || s.equalsIgnoreCase("http");
    } catch (Exception e){
      return false;
    }
  }
}
