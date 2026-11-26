package dev.amusfq.cctv_cam;

import android.content.*;

public class Boot extends BroadcastReceiver {
  static SharedPreferences settings(Context c) {
    return c.createDeviceProtectedStorageContext()
        .getSharedPreferences("camera", Context.MODE_PRIVATE);
  }

  @Override
  public void onReceive(Context c, Intent intent) {
    if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())
        && !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
    if (settings(c).getBoolean("enabled", false))
      c.startForegroundService(new Intent(c, CameraService.class));
  }
}
