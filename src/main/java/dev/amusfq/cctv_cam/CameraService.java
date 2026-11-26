package dev.amusfq.cctv_cam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public class CameraService extends Service {
  static volatile String status = "Stopped";
  static volatile String stats = "";
  private HandlerThread thread;
  private Handler worker;
  private PowerManager.WakeLock wake;
  private WifiManager.WifiLock wifi;
  private Pipeline current;
  private boolean closing;
  private int failures;
  static final String RELOAD_CREDENTIALS = "dev.amusfq.cctv_cam.RELOAD_CREDENTIALS";
  private static final String TAG = "CCTVCam";

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    thread = new HandlerThread("Capture");
    thread.start();
    worker = new Handler(thread.getLooper());
    wake =
        getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CCTVCam:stream");
    wifi =
        getApplicationContext()
            .getSystemService(WifiManager.class)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CCTVCam:stream");
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel("camera", "Live camera", NotificationManager.IMPORTANCE_LOW));
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, CameraService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "camera")
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("CCTV Cam")
        .setContentText("Background streaming enabled")
        .setOngoing(true)
        .setContentIntent(open)
        .addAction(0, "Stop", stop)
        .build();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    startForeground(1, notification());
    if (intent != null && "stop".equals(intent.getAction()))
      Boot.settings(this).edit().putBoolean("enabled", false).commit();
    if (!Boot.settings(this).getBoolean("enabled", false)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!wake.isHeld()) wake.acquire();
    if (!wifi.isHeld()) wifi.acquire();
    worker.post(
        () -> {
          if (intent != null && RELOAD_CREDENTIALS.equals(intent.getAction()) && current != null) {
            current.close();
            current = null;
            stats = "";
          }
          start();
        });
    return START_STICKY;
  }

  private void start() {
    if (closing || current != null || !Boot.settings(this).getBoolean("enabled", false)) return;
    current = new Pipeline();
    try {
      current.open();
    } catch (Exception e) {
      current.fail(e);
    }
  }

  @Override
  public void onDestroy() {
    worker.post(
        () -> {
          closing = true;
          worker.removeCallbacksAndMessages(null);
          if (current != null) current.close();
          current = null;
          status = "Stopped";
          stats = "";
          if (wifi.isHeld()) wifi.release();
          if (wake.isHeld()) wake.release();
          thread.quitSafely();
        });
    super.onDestroy();
  }

  private final class Pipeline {
    java.lang.Process server;
    DataOutputStream output;
    CameraDevice camera;
    MediaCodec encoder;
    Surface input;
    AudioRecord mic;
    volatile boolean closed;
    final SharedPreferences settings = Boot.settings(CameraService.this);
    final int fps = settings.getInt("fps", 20);
    final String facing = settings.getString("camera", "Rear");
    final boolean audio = settings.getBoolean("audio", false);

    void open() throws Exception {
      status = "Starting " + facing.toLowerCase(Locale.US) + " camera";
      String[] size = settings.getString("size", "720×480").split("×");
      CameraManager manager = getSystemService(CameraManager.class);
      String id = null;
      for (String candidate : manager.getCameraIdList()) {
        Integer lens =
            manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING);
        if (Objects.equals(
            lens,
            facing.equals("Front")
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK)) {
          id = candidate;
          break;
        }
      }
      if (id == null) throw new IOException("Selected camera unavailable");
      CameraCharacteristics cc = manager.getCameraCharacteristics(id);
      ProcessBuilder builder =
          new ProcessBuilder(getApplicationInfo().nativeLibraryDir + "/libstream.so")
              .redirectErrorStream(true);
      builder.environment().put("CAMERA_USER", settings.getString("username", "camera"));
      builder.environment().put("CAMERA_PASSWORD", settings.getString("password", ""));
      builder.environment().put("CAMERA_MIC", audio ? "1" : "0");
      server = builder.start();
      output = new DataOutputStream(new BufferedOutputStream(server.getOutputStream(), 32768));
      new Thread(this::readServer, "RTSP status").start();
      MediaFormat format =
          MediaFormat.createVideoFormat(
              "video/avc", Integer.parseInt(size[0]), Integer.parseInt(size[1]));
      format.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      format.setInteger(MediaFormat.KEY_BIT_RATE, settings.getInt("kbps", 1000) * 1000);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      encoder = MediaCodec.createByCodecName("OMX.MTK.VIDEO.ENCODER.AVC");
      encoder.setCallback(
          new MediaCodec.Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) {}

            public void onError(MediaCodec c, MediaCodec.CodecException e) {
              fail(e);
            }

            public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
              if (closed) return;
              try {
                for (String key : new String[] {"csd-0", "csd-1"}) {
                  ByteBuffer b = f.getByteBuffer(key);
                  if (b != null) send(0, 0, b.duplicate());
                }
              } catch (Exception e) {
                fail(e);
              }
            }

            public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
              if (closed) return;
              try {
                ByteBuffer b = c.getOutputBuffer(i);
                if (b != null && info.size > 0) {
                  b.position(info.offset);
                  b.limit(info.offset + info.size);
                  send(
                      (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ? 0 : 1,
                      info.presentationTimeUs,
                      b);
                }
                c.releaseOutputBuffer(i, false);
              } catch (Exception e) {
                fail(e);
              }
            }
          },
          worker);
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      input = encoder.createInputSurface();
      encoder.start();
      if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Camera permission required");
      manager.openCamera(
          id,
          new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice c) {
              if (closed) {
                c.close();
                return;
              }
              camera = c;
              try {
                CaptureRequest.Builder request =
                    c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                request.addTarget(input);
                Range<Integer> best = null;
                int score = Integer.MAX_VALUE;
                for (Range<Integer> range :
                    cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)) {
                  int diff =
                      2 * Math.abs(range.getUpper() - fps) + Math.abs(range.getLower() - fps);
                  if (diff < score) {
                    best = range;
                    score = diff;
                  }
                }
                if (best != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
                c.createCaptureSession(
                    Collections.singletonList(input),
                    new CameraCaptureSession.StateCallback() {
                      public void onConfigured(CameraCaptureSession session) {
                        if (closed) {
                          session.close();
                          return;
                        }
                        try {
                          session.setRepeatingRequest(request.build(), null, worker);
                          if (audio) startMic();
                        } catch (Exception e) {
                          fail(e);
                        }
                      }

                      public void onConfigureFailed(CameraCaptureSession s) {
                        fail(new IOException("Camera rejected this video mode"));
                      }
                    },
                    worker);
              } catch (Exception e) {
                fail(e);
              }
            }

            public void onDisconnected(CameraDevice c) {
              c.close();
              fail(new IOException("Camera disconnected"));
            }

            public void onError(CameraDevice c, int e) {
              c.close();
              fail(new IOException("Camera error " + e));
            }
          },
          worker);
    }

    private void readServer() {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(server.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("stats ")) {
            String[] parts = line.split(" ");
            if (parts.length == 4 && !closed) {
              String details =
                  parts[1] + " fps  ·  " + parts[2] + " kbps sent  ·  " + parts[3] + " viewers";
              worker.post(
                  () -> {
                    if (!closed) {
                      stats = details;
                      status = facing + " camera streaming";
                      failures = 0;
                    }
                  });
            }
          } else Log.w(TAG, line);
        }
      } catch (IOException e) {
        if (!closed) Log.w(TAG, "RTSP status", e);
      }
      worker.post(() -> fail(new IOException("RTSP server exited")));
    }

    private final byte[] buffer = new byte[32768];

    private synchronized void send(int kind, long pts, ByteBuffer data) throws IOException {
      if (closed) return;
      output.writeByte(kind);
      output.writeLong(pts);
      output.writeInt(data.remaining());
      while (data.hasRemaining()) {
        int n = Math.min(data.remaining(), buffer.length);
        data.get(buffer, 0, n);
        output.write(buffer, 0, n);
      }
      output.flush();
    }

    void startMic() {
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Microphone permission required");
      int min =
          AudioRecord.getMinBufferSize(
              16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      mic =
          new AudioRecord(
              MediaRecorder.AudioSource.MIC,
              16000,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              Math.max(3200, min * 2));
      mic.startRecording();
      final AudioRecord recorder = mic;
      new Thread(
              () -> {
                short[] samples = new short[320];
                ByteBuffer pcm = ByteBuffer.allocate(640);
                long first = System.nanoTime() / 1000;
                long total = 0;
                try {
                  while (!closed) {
                    int n = recorder.read(samples, 0, samples.length);
                    if (n <= 0) {
                      if (closed) return;
                      throw new IOException("Microphone read " + n);
                    }
                    pcm.clear();
                    for (int i = 0; i < n; i++) pcm.putShort(samples[i]);
                    pcm.flip();
                    send(2, first + total * 1000000 / 16000, pcm);
                    total += n;
                  }
                } catch (Exception e) {
                  worker.post(() -> fail(e));
                }
              },
              "Microphone")
          .start();
    }

    void fail(Exception e) {
      if (closed) return;
      Log.e(TAG, "Capture failed", e);
      close();
      current = null;
      stats = "";
      if (e instanceof SecurityException) {
        settings.edit().putBoolean("enabled", false).apply();
        stopSelf();
        return;
      }
      status = e.getMessage() + " · retrying";
      worker.postDelayed(
          CameraService.this::start, Math.min(30000, 2000L << Math.min(4, failures++)));
    }

    void close() {
      closed = true;
      if (server != null) server.destroy();
      if (camera != null) camera.close();
      if (mic != null) {
        try {
          mic.stop();
        } catch (RuntimeException ignored) {
        }
        mic.release();
      }
      if (encoder != null) {
        try {
          encoder.stop();
        } catch (RuntimeException ignored) {
        }
        encoder.release();
      }
      if (input != null) input.release();
      if (output != null)
        try {
          output.close();
        } catch (IOException ignored) {
        }
    }
  }
}
