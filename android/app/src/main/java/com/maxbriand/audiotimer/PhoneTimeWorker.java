package com.maxbriand.audiotimer;

import android.content.Context;

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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/*
 * Posts the screen sessions (PhoneTime.rows) to <upload URL>/phone, with the same host and
 * token as the nights. Same WorkManager contract as UploadWorker — held until there is a
 * network, run with the app closed, kept across reboots — but nothing to delete on success:
 * the sessions stay in Android's log, and the next run simply sends them again.
 */
public class PhoneTimeWorker extends Worker {
  static final String WORK = "phone-time";
  private static final int CONNECT_MS = 15000;
  private static final int READ_MS = 30000;

  public PhoneTimeWorker(@NonNull Context c, @NonNull WorkerParameters p){ super(c, p); }

  /* now: the 📱 Export button — REPLACE, so a run waiting out a backoff is not what decides
     when the tap takes effect. Otherwise KEEP: a queued run will read the whole log anyway. */
  static void schedule(Context c, boolean now){
    OneTimeWorkRequest.Builder b = new OneTimeWorkRequest.Builder(PhoneTimeWorker.class)
      .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS);
    if (now) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
    WorkManager.getInstance(c).enqueueUniqueWork(WORK,
      now ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP, b.build());
  }

  @NonNull
  @Override
  public Result doWork(){
    Context c = getApplicationContext();
    String base = Outbox.url(c);
    if (base.isEmpty()){ PhoneTime.setStatus(c, "no server set in ⚙", 0); return Result.success(); }
    if (!PhoneTime.granted(c)){ PhoneTime.setStatus(c, "no usage access", 0); return Result.success(); }

    HttpURLConnection conn = null;
    try {
      JSONArray rows = PhoneTime.rows(c);
      JSONObject body = new JSONObject();
      body.put("app", "audio-timer");
      body.put("device", Outbox.device(c));
      body.put("screens", rows);
      byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

      conn = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/phone").openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(CONNECT_MS);
      conn.setReadTimeout(READ_MS);
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(payload.length);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      String token = Outbox.token(c);
      if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
      try (OutputStream out = conn.getOutputStream()){ out.write(payload); }

      int status = conn.getResponseCode();
      if (status >= 200 && status < 300){
        PhoneTime.setStatus(c, null, rows.length());
        return Result.success();
      }
      // Permanent, as in UploadWorker: the next midnight or the Export button tries again.
      if (status >= 400 && status < 500 && status != 408 && status != 429){
        PhoneTime.setStatus(c, status == 401 || status == 403 ? "server rejected the token"
          : "server refused it (HTTP " + status + ")", 0);
        return Result.failure();
      }
      PhoneTime.setStatus(c, "server busy (HTTP " + status + ") — will retry", 0);
      return Result.retry();
    } catch (Exception e){
      PhoneTime.setStatus(c, "could not reach the server — will retry", 0);
      return Result.retry();
    } finally {
      if (conn != null) conn.disconnect();
    }
  }
}
