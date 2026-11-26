package dev.amusfq.cctv_cam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

public class MainActivity extends Activity {
  private LinearLayout body;
  private TextView state, metrics, endpoint;
  private Button toggle;
  private Spinner preset, lens, size, fps, bitrate;
  private CheckBox mic;
  private SharedPreferences prefs;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String lastPreset;
  private String url = "";

  private int dp(int n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  private TextView label(String text, int sp) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTextSize(sp);
    v.setPadding(0, dp(8), 0, dp(3));
    body.addView(v);
    return v;
  }

  private Button button(String text, View.OnClickListener listener) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setOnClickListener(listener);
    body.addView(b);
    return b;
  }

  private Spinner choice(String title, String[] values, String saved) {
    TextView titleView = label(title, 15);
    Spinner s = new Spinner(this);
    s.setId(View.generateViewId());
    titleView.setLabelFor(s.getId());
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
    s.setAdapter(adapter);
    s.setSelection(Math.max(0, Arrays.asList(values).indexOf(saved)));
    body.addView(s);
    return s;
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    setTitle("CCTV Cam");
    prefs = Boot.settings(this);
    if (!prefs.contains("password")) {
      byte[] bytes = new byte[15];
      new SecureRandom().nextBytes(bytes);
      prefs
          .edit()
          .putString("username", prefs.getString("username", "admin"))
          .putString(
              "password",
              android.util.Base64.encodeToString(
                  bytes,
                  android.util.Base64.URL_SAFE
                      | android.util.Base64.NO_WRAP
                      | android.util.Base64.NO_PADDING))
          .commit();
    }
    if (prefs.getBoolean("enabled", false))
      startForegroundService(new Intent(this, CameraService.class));
    ScrollView scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(10), dp(20), dp(20));
    scroll.addView(body);
    setContentView(scroll);
    state = label("", 23);
    state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    metrics = label("", 15);
    endpoint = label("", 17);
    endpoint.setTextIsSelectable(true);
    button(
        "Copy RTSP URL",
        v -> {
          refreshUrl();
          getSystemService(ClipboardManager.class)
              .setPrimaryClip(ClipData.newPlainText("RTSP URL", url));
        });
    button("Edit viewer credentials", v -> credentials());
    toggle =
        button(
            "Start streaming",
            v -> {
              if (prefs.getBoolean("enabled", false)) stop();
              else requestStart();
            });
    lastPreset = prefs.getString("preset", "Low delay");
    preset =
        choice(
            "Video preset",
            new String[] {"Low delay", "Low heat", "High detail", "Manual"},
            lastPreset);
    lens = choice("Camera", new String[] {"Rear", "Front"}, prefs.getString("camera", "Rear"));
    size =
        choice(
            "Resolution",
            new String[] {"640×480", "720×480", "1280×720", "1920×1080"},
            prefs.getString("size", "720×480"));
    fps =
        choice(
            "Frames per second",
            new String[] {"15", "20", "24", "30"},
            String.valueOf(prefs.getInt("fps", 20)));
    bitrate =
        choice(
            "Video bitrate (kbps)",
            new String[] {"512", "1000", "2000", "3000", "4000"},
            String.valueOf(prefs.getInt("kbps", 1000)));
    mic = new CheckBox(this);
    mic.setText("Stream microphone audio");
    mic.setChecked(prefs.getBoolean("audio", false));
    body.addView(mic);
    label("Start resumes streaming after reboot. Stop turns off automatic startup.", 14);
    preset.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
            String selected = preset.getSelectedItem().toString();
            if (!selected.equals(lastPreset)) {
              if (i < 3) {
                size.setSelection(new int[] {1, 0, 3}[i]);
                fps.setSelection(new int[] {1, 0, 2}[i]);
                bitrate.setSelection(new int[] {1, 0, 4}[i]);
              }
              lastPreset = selected;
            }
            refresh();
          }
        });
  }

  private void credentials() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), dp(8), dp(20), 0);
    EditText user = new EditText(this);
    user.setSingleLine();
    user.setHint("Username");
    user.setContentDescription("Viewer username");
    user.setText(prefs.getString("username", "camera"));
    form.addView(user);
    EditText pass = new EditText(this);
    pass.setSingleLine();
    pass.setHint("Password");
    pass.setContentDescription("Viewer password");
    pass.setInputType(129);
    pass.setText(prefs.getString("password", ""));
    form.addView(pass);
    CheckBox show = new CheckBox(this);
    show.setText("Show password");
    form.addView(show);
    show.setOnCheckedChangeListener((v, on) -> pass.setInputType(on ? 145 : 129));
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle("Viewer credentials")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null);
    if (prefs.getBoolean("enabled", false))
      builder.setMessage("Changing the login disconnects current viewers.");
    AlertDialog dialog = builder.show();
    dialog
        .getButton(-1)
        .setOnClickListener(
            v -> {
              String name = user.getText().toString().trim(), secret = pass.getText().toString();
              if (name.isEmpty() || name.contains(":")) {
                user.setError("Enter a username without a colon");
                return;
              }
              if (secret.isEmpty()) {
                pass.setError("Enter a password");
                return;
              }
              boolean changed =
                  !name.equals(prefs.getString("username", "camera"))
                      || !secret.equals(prefs.getString("password", ""));
              if (changed) {
                if (!prefs
                    .edit()
                    .putString("username", name)
                    .putString("password", secret)
                    .commit()) {
                  pass.setError("Could not save credentials");
                  return;
                }
                if (prefs.getBoolean("enabled", false))
                  startForegroundService(
                      new Intent(this, CameraService.class)
                          .setAction(CameraService.RELOAD_CREDENTIALS));
              }
              dialog.dismiss();
            });
  }

  private void refreshUrl() {
    try {
      ArrayList<String> addresses = new ArrayList<>();
      for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!iface.isUp() || iface.isLoopback()) continue;
        for (InetAddress a : Collections.list(iface.getInetAddresses()))
          if (a instanceof Inet4Address) {
            String s = "rtsp://" + a.getHostAddress() + ":8554/live";
            if (iface.getName().startsWith("wlan")) addresses.add(0, s);
            else addresses.add(s);
          }
      }
      url = addresses.isEmpty() ? "" : addresses.get(0);
    } catch (SocketException e) {
      url = "";
    }
    endpoint.setText(url.isEmpty() ? "Waiting for network" : url);
  }

  private void requestStart() {
    ArrayList<String> missing = new ArrayList<>();
    for (String perm :
        mic.isChecked()
            ? new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}
            : new String[] {Manifest.permission.CAMERA})
      if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) missing.add(perm);
    if (!missing.isEmpty()) {
      requestPermissions(missing.toArray(new String[0]), 4);
      return;
    }
    prefs
        .edit()
        .putString("preset", lastPreset)
        .putString("camera", lens.getSelectedItem().toString())
        .putString("size", size.getSelectedItem().toString())
        .putInt("fps", Integer.parseInt(fps.getSelectedItem().toString()))
        .putInt("kbps", Integer.parseInt(bitrate.getSelectedItem().toString()))
        .putBoolean("audio", mic.isChecked())
        .putBoolean("enabled", true)
        .commit();
    startForegroundService(new Intent(this, CameraService.class));
    refresh();
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] results) {
    super.onRequestPermissionsResult(r, p, results);
    if (r != 4 || results.length == 0) return;
    for (int granted : results)
      if (granted != PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(
                this, "Permission required for selected camera or microphone", Toast.LENGTH_LONG)
            .show();
        return;
      }
    requestStart();
  }

  private void stop() {
    prefs.edit().putBoolean("enabled", false).commit();
    stopService(new Intent(this, CameraService.class));
    refresh();
  }

  private void refresh() {
    boolean enabled = prefs.getBoolean("enabled", false);
    state.setText(CameraService.status);
    metrics.setText(CameraService.stats);
    toggle.setText(enabled ? "Stop streaming" : "Start streaming");
    preset.setEnabled(!enabled);
    lens.setEnabled(!enabled);
    mic.setEnabled(!enabled);
    boolean manual = !enabled && "Manual".equals(lastPreset);
    for (Spinner s : new Spinner[] {size, fps, bitrate}) s.setEnabled(manual);
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          refreshUrl();
          refresh();
          handler.postDelayed(this, 2000);
        }
      };

  @Override
  public void onResume() {
    super.onResume();
    handler.post(tick);
  }

  @Override
  public void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }
}
