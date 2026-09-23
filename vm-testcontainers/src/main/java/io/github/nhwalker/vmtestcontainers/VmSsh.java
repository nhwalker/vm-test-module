package io.github.nhwalker.vmtestcontainers;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around an sshj {@link SSHClient} connected to one guest. One instance per
 * {@link VmContainer}; a new SSH session is opened per command.
 *
 * <p>Host keys are not verified: the guest is ephemeral and generated its host key at first boot.
 */
public final class VmSsh implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(VmSsh.class);
    private static final Pattern SAFE_ARG = Pattern.compile("[A-Za-z0-9_./:=@%+,-]+");

    private final String host;
    private final int port;
    private final String user;
    private final KeyProvider keyProvider;
    private final Duration connectTimeout;

    private SSHClient client;

    public VmSsh(String host, int port, String user, KeyProvider keyProvider, Duration connectTimeout) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.user = Objects.requireNonNull(user, "user");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    }

    /**
     * Builds an sshj key provider from a JDK key pair. The keys are re-created through sshj's configured
     * security provider (Bouncy Castle when present) so that signing uses key objects that provider
     * understands.
     */
    public static KeyProvider keyProvider(SshKeyPair keyPair) {
        KeyPair kp = keyPair.keyPair();
        try {
            KeyFactory kf = SecurityUtils.getKeyFactory("Ed25519");
            KeyPair converted = new KeyPair(
                    kf.generatePublic(new X509EncodedKeySpec(kp.getPublic().getEncoded())),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(kp.getPrivate().getEncoded())));
            return new KeyPairWrapper(converted);
        } catch (GeneralSecurityException e) {
            LOG.debug("could not convert Ed25519 key through sshj's provider, using JDK key objects directly", e);
            return new KeyPairWrapper(kp);
        }
    }

    /** Key provider for a private key file on disk (OpenSSH / PEM / PKCS#8 formats supported by sshj). */
    public static KeyProvider keyProvider(Path privateKeyFile) throws IOException {
        SSHClient tmp = new SSHClient();
        return tmp.loadKeys(privateKeyFile.toAbsolutePath().toString());
    }

    /** Connects and authenticates if not already connected. Throws on any failure. */
    public synchronized SSHClient ensureConnected() throws IOException {
        if (client != null && client.isConnected() && client.isAuthenticated()) {
            return client;
        }
        closeQuietly();
        SSHClient c = new SSHClient();
        c.addHostKeyVerifier(new PromiscuousVerifier());
        c.setConnectTimeout((int) connectTimeout.toMillis());
        c.setTimeout(0); // no socket read timeout: long-running commands are bounded by exec timeouts
        try {
            c.connect(host, port);
            c.authPublickey(user, keyProvider);
        } catch (IOException | RuntimeException e) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
        client = c;
        return c;
    }

    public synchronized boolean isConnected() {
        return client != null && client.isConnected() && client.isAuthenticated();
    }

    /** Runs a shell command line (already quoted) and captures its output. */
    public VmExecResult exec(String commandLine, Duration timeout) throws IOException {
        SSHClient c = ensureConnected();
        try (Session session = c.startSession()) {
            Session.Command cmd = session.exec(commandLine);
            // Drain stderr on its own thread so a chatty stderr cannot stall the channel window while
            // we are blocked reading stdout.
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread errReader = Thread.ofVirtual().name("vm-ssh-stderr").start(() -> copyQuietly(cmd.getErrorStream(), errBuf));
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            copyQuietly(cmd.getInputStream(), outBuf);
            cmd.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                errReader.join(timeout.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Integer status = cmd.getExitStatus();
            return new VmExecResult(status == null ? -1 : status,
                    outBuf.toString(StandardCharsets.UTF_8),
                    errBuf.toString(StandardCharsets.UTF_8));
        }
    }

    public void put(Path localFile, String remotePath) throws IOException {
        SSHClient c = ensureConnected();
        try (SFTPClient sftp = c.newSFTPClient()) {
            sftp.put(new FileSystemFile(localFile.toFile()), remotePath);
        }
    }

    public void get(String remotePath, Path localFile) throws IOException {
        SSHClient c = ensureConnected();
        try (SFTPClient sftp = c.newSFTPClient()) {
            sftp.get(remotePath, new FileSystemFile(localFile.toFile()));
        }
    }

    /** POSIX-shell quoting of an argument list into one command line. */
    public static String shellJoin(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(shellQuote(a));
        }
        return sb.toString();
    }

    public static String shellQuote(String arg) {
        Objects.requireNonNull(arg, "arg");
        if (!arg.isEmpty() && SAFE_ARG.matcher(arg).matches()) {
            return arg;
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    private static void copyQuietly(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException e) {
            LOG.debug("stream ended with error", e);
        }
    }

    private void closeQuietly() {
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                LOG.debug("error closing ssh client", e);
            }
            client = null;
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }
}
