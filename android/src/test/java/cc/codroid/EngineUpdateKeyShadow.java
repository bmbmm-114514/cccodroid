package cc.codroid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** The host JVM has no Android Keystore. Keep a test-only HMAC key in this sandbox's private directory. */
@Implements(value = EngineUpdatePins.DeviceKey.class, isInAndroidSdk = false)
public class EngineUpdateKeyShadow {
    static boolean unavailable;

    @Implementation protected static SecretKey load(boolean create) throws Exception {
        if (unavailable) throw new IOException("test keystore unavailable");
        Path file = RuntimeEnvironment.getApplication().getFilesDir().toPath().resolve("test-agent-hmac");
        if (!Files.exists(file)) {
            if (!create) return null;
            Files.write(file, KeyGenerator.getInstance("HmacSHA256").generateKey().getEncoded());
        }
        return new SecretKeySpec(Files.readAllBytes(file), "HmacSHA256");
    }
}
