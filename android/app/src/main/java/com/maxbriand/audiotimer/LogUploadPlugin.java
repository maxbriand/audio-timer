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
    try {
      Outbox.put(getContext(), id, json);
    } catch (Exception e){
      call.reject("could not stage the run: " + e.getMessage());
      return;
    }
    UploadWorker.schedule(getContext());
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

  @PluginMethod
  public void flush(PluginCall call){
    UploadWorker.scheduleNow(getContext());
    call.resolve();
  }
}
