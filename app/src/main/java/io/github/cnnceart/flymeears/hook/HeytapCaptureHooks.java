package io.github.cnnceart.flymeears.hook;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;

/**
 * 调试用：记录欢律（com.heytap.headset）与耳机之间的 RFCOMM 原始字节。
 *
 * <p>只有在 LSPosed 作用域里勾选 com.heytap.headset 才会加载。用途是让官方 App
 * 自己说一次「低延迟 / 游戏模式 / 双设备」等命令，我们记下真实帧，再移植到 FlymeEars。
 *
 * <p>发布前会移除这个作用域与类。
 */
public final class HeytapCaptureHooks {

    private static final String TAG = "FlymeEars";
    private static final String PREFIX = "heytap: ";

    private HeytapCaptureHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Method output = BluetoothSocket.class.getDeclaredMethod("getOutputStream");
            module.hook(output).intercept(chain -> {
                OutputStream raw = (OutputStream) chain.proceed();
                BluetoothSocket socket = (BluetoothSocket) chain.getThisObject();
                return new LoggingOutput(socket, raw);
            });
            Method input = BluetoothSocket.class.getDeclaredMethod("getInputStream");
            module.hook(input).intercept(chain -> {
                InputStream raw = (InputStream) chain.proceed();
                BluetoothSocket socket = (BluetoothSocket) chain.getThisObject();
                return new LoggingInput(socket, raw);
            });
            Log.i(TAG, PREFIX + "RFCOMM 抓包 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, PREFIX + "抓包 Hook 失败", error);
        }

        try {
            Method open = java.net.URL.class.getDeclaredMethod("openConnection");
            module.hook(open).intercept(chain -> {
                Log.i(TAG, PREFIX + "URL " + chain.getThisObject());
                return chain.proceed();
            });
            Log.i(TAG, PREFIX + "URL 抓取 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, PREFIX + "URL Hook 失败", error);
        }

        try {
            Class<?> stringLoader = Class.forName(
                    "com.bumptech.glide.load.model.StringLoader", false, loader);
            Class<?> options = Class.forName(
                    "com.bumptech.glide.load.Options", false, loader);
            Method build = stringLoader.getDeclaredMethod(
                    "buildLoadData", String.class, int.class, int.class, options);
            module.hook(build).intercept(chain -> {
                Log.i(TAG, PREFIX + "GLIDE " + chain.getArg(0));
                return chain.proceed();
            });
            Log.i(TAG, PREFIX + "Glide 模型 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, PREFIX + "Glide Hook 失败", error);
        }
    }

    private static String label(BluetoothSocket socket) {
        try {
            return socket.getRemoteDevice().getAddress();
        } catch (Throwable error) {
            return "?";
        }
    }

    private static final class LoggingOutput extends OutputStream {
        private final BluetoothSocket socket;
        private final OutputStream delegate;

        LoggingOutput(BluetoothSocket socket, OutputStream delegate) {
            this.socket = socket;
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            log("TX", label(socket), buffer, offset, length);
            delegate.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class LoggingInput extends InputStream {
        private final BluetoothSocket socket;
        private final InputStream delegate;

        LoggingInput(BluetoothSocket socket, InputStream delegate) {
            this.socket = socket;
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                log("RX", label(socket), buffer, offset, count);
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static void log(String direction, String address, byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        int shown = Math.min(length, 256);
        StringBuilder hex = new StringBuilder(shown * 3);
        for (int i = 0; i < shown; i++) {
            hex.append(String.format(Locale.ROOT, "%02X ", data[offset + i]));
        }
        Log.i(TAG, PREFIX + direction + " " + address + " [" + length + "] " + hex.toString().trim());
    }
}
