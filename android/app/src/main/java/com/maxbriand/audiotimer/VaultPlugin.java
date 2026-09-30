package com.maxbriand.audiotimer;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/*
 * A copy of the page's own state — settings, fatigue checks, issues, the server config — kept
 * outside the WebView.
 *
 * The page stores everything in IndexedDB and localStorage, which Android treats as a cache:
 * with the phone nearly full, it deletes a WebView's storage whole and without a word (it did
 * on 2026-09-29, at 124 of 128 GB). The app then boots as a fresh install and pushes its
 * defaults over the native alarms. This file sits in the app's files dir, which only an
 * uninstall or "Clear storage" removes, and the page restores itself from it when it finds
 * its database newly created.
 *
 * The page owns the format; this side only stores one string, written to a temp file and
 * renamed over the old one, so a kill mid-write leaves the previous copy intact.
 */
@CapacitorPlugin(name = "Vault")
public class VaultPlugin extends Plugin {
  private static final String FILE = "vault.json";

  private File file(){ return new File(getContext().getFilesDir(), FILE); }

  @PluginMethod
  public void load(PluginCall call){
    JSObject r = new JSObject();
    File f = file();
    if (!f.exists()){ r.put("data", ""); call.resolve(r); return; }
    try (InputStream in = new FileInputStream(f)){
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      r.put("data", new String(out.toByteArray(), StandardCharsets.UTF_8));
      call.resolve(r);
    } catch (Exception e){
      call.reject("vault unreadable: " + e.getMessage());
    }
  }

  @PluginMethod
  public void save(PluginCall call){
    String data = call.getString("data", "");
    if (data == null || data.isEmpty()){ call.reject("nothing to save"); return; }
    File tmp = new File(getContext().getFilesDir(), FILE + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)){
      out.write(data.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    } catch (Exception e){
      call.reject("vault not written: " + e.getMessage());
      return;
    }
    if (!tmp.renameTo(file())){ call.reject("vault not replaced"); return; }
    call.resolve();
  }
}
