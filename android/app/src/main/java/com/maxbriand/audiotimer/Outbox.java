package com.maxbriand.audiotimer;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/*
 * The handover between the page and the uploader.
 *
 * The session log lives in the WebView's IndexedDB, which native code cannot read. So a
 * finished night is copied out as one small JSON file per run, into files/outbox/, and from
 * there UploadWorker owns it — the page can be gone, the process can be gone, the phone can
 * reboot, and the file is still there waiting for a network.
 *
 * The filename is the session id, so re-enqueuing a night that was reopened overwrites its
 * file instead of queuing it twice. Deletion is the only record of success: a file that is
 * still here has not been accepted by the server, whatever else may have happened.
 *
 * Everything is guarded by one lock because the page (main thread) and the worker (a
 * background thread) touch the same directory, and a run that lands mid-write must not read
 * half a night.
 */
final class Outbox {
  static final Object LOCK = new Object();

  private static final String PREFS = "logupload";
  private static final String KEY_URL = "url";
  private static final String KEY_TOKEN = "token";
  private static final String KEY_DEVICE = "device";
  private static final String KEY_ERROR = "lastError";
  private static final String KEY_OK_AT = "lastOkAt";
  private static final String UPLOADED = "uploaded.txt";

  private Outbox(){}

  static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static String url(Context c){ return prefs(c).getString(KEY_URL, ""); }
  static String token(Context c){ return prefs(c).getString(KEY_TOKEN, ""); }

  static void setConfig(Context c, String url, String token){
    prefs(c).edit().putString(KEY_URL, url == null ? "" : url)
                   .putString(KEY_TOKEN, token == null ? "" : token)
                   .apply();
  }

  /* Stable per-install id, so a server collecting from more than one phone can tell them
     apart. Generated on first use and never sent anywhere else. */
  static String device(Context c){
    SharedPreferences p = prefs(c);
    String id = p.getString(KEY_DEVICE, "");
    if (id.isEmpty()){
      id = UUID.randomUUID().toString();
      p.edit().putString(KEY_DEVICE, id).apply();
    }
    return id;
  }

  static void setStatus(Context c, String error, boolean ok){
    SharedPreferences.Editor e = prefs(c).edit().putString(KEY_ERROR, error == null ? "" : error);
    if (ok) e.putLong(KEY_OK_AT, System.currentTimeMillis());
    e.apply();
  }
  static String lastError(Context c){ return prefs(c).getString(KEY_ERROR, ""); }
  static long lastOkAt(Context c){ return prefs(c).getLong(KEY_OK_AT, 0); }

  static File dir(Context c){
    File d = new File(c.getFilesDir(), "outbox");
    if (!d.isDirectory()) d.mkdirs();
    return d;
  }

  // Session ids are UUIDs, but nothing stops the page from sending something else one day and
  // a stray '/' would write outside the directory. Deterministic, so remove() finds it again.
  private static String safe(String id){
    return id.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /* Written to a temp file and renamed: a half-written night is never a night the worker can
     pick up, and rename is the only atomic operation the filesystem offers here. */
  static void put(Context c, String id, String json) throws IOException {
    synchronized (LOCK){
      File dir = dir(c);
      File tmp = new File(dir, safe(id) + ".tmp");
      File out = new File(dir, safe(id) + ".json");
      try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)){
        w.write(json);
      }
      if (!tmp.renameTo(out)){
        tmp.delete();
        throw new IOException("could not stage " + id);
      }
    }
  }

  static List<File> list(Context c){
    synchronized (LOCK){
      File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
      List<File> out = new ArrayList<>();
      if (fs != null) for (File f : fs) out.add(f);
      return out;
    }
  }

  static int pending(Context c){ return list(c).size(); }

  static void remove(Context c, String id){
    synchronized (LOCK){ new File(dir(c), safe(id) + ".json").delete(); }
  }

  /* Forgetting the destination has to take the staged copies with it. The runs themselves are
     still in the page's own storage — and are no longer eligible to be cleared from it, since
     nothing is configured to have received them — so nothing is lost by dropping these. */
  static void clear(Context c){
    synchronized (LOCK){
      File[] fs = dir(c).listFiles();
      if (fs != null) for (File f : fs) f.delete();
      new File(c.getFilesDir(), UPLOADED).delete();
    }
  }

  static String read(File f) throws IOException {
    try (RandomAccessFile r = new RandomAccessFile(f, "r")){
      byte[] b = new byte[(int) r.length()];
      r.readFully(b);
      return new String(b, StandardCharsets.UTF_8);
    }
  }

  /* The ledger of what landed while the page was not running. The page reads it on its next
     open, stamps those runs as uploaded, and only then may the retention sweep drop them —
     so a night is never cleared locally on the strength of anything but a server 2xx. */
  static void markUploaded(Context c, List<String> ids) {
    if (ids.isEmpty()) return;
    synchronized (LOCK){
      File f = new File(c.getFilesDir(), UPLOADED);
      try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)){
        for (String id : ids) w.write(id + "\n");
      } catch (IOException ignored) {}
    }
  }

  static List<String> takeUploaded(Context c){
    synchronized (LOCK){
      List<String> out = new ArrayList<>();
      File f = new File(c.getFilesDir(), UPLOADED);
      if (!f.isFile()) return out;
      try {
        for (String line : read(f).split("\n")){
          String s = line.trim();
          if (!s.isEmpty()) out.add(s);
        }
      } catch (IOException ignored) {}
      f.delete();
      return out;
    }
  }
}
