import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import jdk.security.jarsigner.JarSigner;

class SignBundle {
    static String fingerprint(Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    public static void main(String[] arguments) {
        char[] password = null;
        try {
            password = System.getenv("RELEASE_STORE_PASSWORD").toCharArray();
            KeyStore keys = KeyStore.getInstance(Path.of(System.getenv("RELEASE_KEYSTORE_PATH")).toFile(), password);
            var entry = (KeyStore.PrivateKeyEntry) keys.getEntry(System.getenv("RELEASE_KEY_ALIAS"), new KeyStore.PasswordProtection(password));
            if (entry == null) throw new IllegalStateException();
            if (!fingerprint(entry.getCertificate()).equals(arguments[2])) {
                System.err.println("Signing certificate differs from the pinned release identity");
                System.exit(2);
            }
            var signer = new JarSigner.Builder(entry).digestAlgorithm("SHA-256").signerName("RELEASE").build();
            try (var source = new ZipFile(arguments[0]);
                 OutputStream output = Files.newOutputStream(Path.of(arguments[1]), StandardOpenOption.CREATE_NEW)) {
                signer.sign(source, output);
            }
            try (var signed = new JarFile(arguments[1], true)) {
                var entries = signed.entries();
                while (entries.hasMoreElements()) {
                    var item = entries.nextElement();
                    if (item.isDirectory()) continue;
                    try (InputStream input = signed.getInputStream(item)) {
                        input.transferTo(OutputStream.nullOutputStream());
                    }
                    String name = item.getName().toUpperCase(java.util.Locale.ROOT);
                    boolean signature = name.equals("META-INF/MANIFEST.MF") || name.startsWith("META-INF/") && name.indexOf('/', 9) < 0 &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") || name.substring(9).startsWith("SIG-"));
                    if (signature) continue;
                    var identities = item.getCodeSigners();
                    if (identities == null || identities.length != 1 || !fingerprint(identities[0].getSignerCertPath().getCertificates().get(0)).equals(arguments[2])) {
                        throw new IllegalStateException();
                    }
                }
            }
            System.out.println("Bundle signature and payload coverage verified");
        } catch (Exception failure) {
            System.err.println("Bundle signing or signature verification failed");
            System.exit(1);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }
}
