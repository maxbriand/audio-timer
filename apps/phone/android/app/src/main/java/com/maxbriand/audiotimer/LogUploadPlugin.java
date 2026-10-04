package com.maxbriand.audiotimer;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.List;

/*
 * The page's handle on the upload outbox.
 *
 * The page hands over finished nights (enqueue) and later asks what actually landed (drain);
 * everything between those two calls happens without it, in UploadWorker, possibly days later
 * and with the app closed. The page must therefore never assume an enqueue means "sent" — the
 * only evidence a night reached the server is its id coming back out of drain().
 */
@CapacitorPlugin(name = "LogUpload")
public class LogUploadPlugin extends Plugin {

  /* Where to send, and what with. Saving a URL also kicks a run, so entering the settings on
     a phone that already has a backlog does not mean waiting for the next night. */
  @PluginMethod
  public void configure(PluginCall call){
    String url = call.getString("url", "");
    String token = call.getString("token", "");
    if (url != null && !url.isEmpty() && !UploadWorker.usableUrl(url)){
      call.reject("that does not look like a URL");
      return;
    }
    // The page pushes its config on every boot so the two copies cannot drift, which means
    // this is usually a no-op. Only a real change touches the queue — otherwise every app
    // open would reset the backoff of a job that is patiently waiting out a server outage.
    boolean changed = !Outbox.url(getContext()).equals(url == null ? "" : url)
                   || !Outbox.token(getContext()).equals(token == null ? "" : token);
    Outbox.setConfig(getContext(), url, token);
    if (changed){
      if (url == null || url.isEmpty()){
        UploadWorker.cancel(getContext());
        Outbox.clear(getContext());
      } else {
        UploadWorker.scheduleNow(getContext());
      }
    }
    call.resolve();
  }

  /* The config as last pushed. A page whose storage was wiped reads it back rather than
     pushing its empty one — which would also throw away the nights still queued. */
  @PluginMethod
  public void config(PluginCall call){
    JSObject r = new JSObject();
    r.put("url", Outbox.url(getContext()));
    r.put("token", Outbox.token(getContext()));
    call.resolve(r);
  }

  /* One finished run. Re-enqueuing the same id overwrites the staged copy rather than adding
     a second one, which is what makes a night reopened within the 10-minute gap safe to send
     again. */
  @PluginMethod
  public void enqueue(PluginCall call){
    String id = call.getString("id", "");
    String json = call.getString("json", "");
    if (id == null || id.isEmpty() || json == null || json.isEmpty()){
      call.reject("id and json are required");
      return;
    }
    // Where it goes and when it may: the page stages every log here, each due 2 hours after
    // it was logged (Outbox.HOLD_MS), so it goes out on time with the app closed.
    String route = call.getString("route", ""), key = call.getString("key", "sessions");
    String device = call.getString("device", "");
    long notBefore = 0;
    try { notBefore = Long.parseLong(call.getString("notBefore", "0")); } catch (NumberFormatException ignored){}
    try {
      Outbox.put(getContext(), id, json, route, key, device, notBefore);
    } catch (Exception e){
      call.reject("could not stage the run: " + e.getMessage());
      return;
    }
    UploadWorker.scheduleDue(getContext());
    call.resolve();
  }

  /* A run deleted from the log before it went out: unstage it, so the worker never sends it.
     Nothing staged under that id is not an error — it may have been sent already. */
  @PluginMethod
  public void discard(PluginCall call){
    String id = call.getString("id", "");
    if (id == null || id.isEmpty()){
      call.reject("id is required");
      return;
    }
    Outbox.remove(getContext(), id);
    call.resolve();
  }

  /* What landed while the page was not running, plus the state of the queue for the ⚙ line.
     Reading the ledger clears it, so each id is reported once — the page stamps those runs
     and it is the stamp, not this call, that survives. */
  @PluginMethod
  public void drain(PluginCall call){
    List<String> ids = Outbox.takeUploaded(getContext());
    JSArray uploaded = new JSArray();
    for (String id : ids) uploaded.put(id);
    JSObject out = new JSObject();
    out.put("uploaded", uploaded);
    out.put("pending", Outbox.pending(getContext()));
    out.put("lastError", Outbox.lastError(getContext()));
    out.put("lastOkAt", Outbox.lastOkAt(getContext()));
    call.resolve(out);
  }

  /* The rows staged natively (the reminders' buttons), for the page to take into its log;
     `staged` false means the worker already sent it. handoffAck once they are written. */
  @PluginMethod
  public void handoff(PluginCall call){
    JSArray rows = new JSArray();
    for (java.io.File f : Outbox.handoff(getContext())){
      try {
        byte[] buf = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)){
          int n = 0, r;
          while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        }
        String json = new String(buf, java.nio.charset.StandardCharsets.UTF_8);   // minSdk 23: no Files
        String id = new org.json.JSONObject(json).optString("id");
        JSObject o = new JSObject();
        o.put("id", id);
        o.put("json", json);
        o.put("staged", Outbox.staged(getContext(), id));
        rows.put(o);
      } catch (Exception ignored){}
    }
    JSObject out = new JSObject();
    out.put("rows", rows);
    call.resolve(out);
  }

  @PluginMethod
  public void handoffAck(PluginCall call){
    JSArray ids = call.getArray("ids");
    try { for (int i = 0; ids != null && i < ids.length(); i++) Outbox.handoffAck(getContext(), ids.getString(i)); }
    catch (Exception ignored){}
    call.resolve();
  }

  @PluginMethod
  public void flush(PluginCall call){
    UploadWorker.scheduleNow(getContext());
    call.resolve();
  }
}
