package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;

import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.junit.jupiter.api.Test;

class SshKeyPairTest {

    @Test
    void publicKeyLineIsOpenSshFormat() throws Exception {
        SshKeyPair kp = SshKeyPair.generate();
        String line = kp.openSshPublicKey("comment here");
        String[] parts = line.split(" ", 3);
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("ssh-ed25519");
        assertThat(parts[2]).isEqualTo("comment here");

        byte[] blob = Base64.getDecoder().decode(parts[1]);
        // wire format: uint32 len, "ssh-ed25519", uint32 32, key
        assertThat(blob).hasSize(4 + 11 + 4 + 32);
        assertThat(new String(blob, 4, 11, StandardCharsets.US_ASCII)).isEqualTo("ssh-ed25519");
        assertThat(blob[18]).isEqualTo((byte) 32);
    }

    @Test
    void rawEncodingRoundTripsThroughTheJdk() throws Exception {
        SshKeyPair kp = SshKeyPair.generate();
        EdECPublicKey pub = (EdECPublicKey) kp.keyPair().getPublic();
        byte[] raw = SshKeyPair.rawPublicKey(pub);
        assertThat(raw).hasSize(32);

        // EdECPoint does not override equals(); compare the fields.
        EdECPoint point = SshKeyPair.pointFromRaw(raw);
        assertThat(point.getY()).isEqualTo(pub.getPoint().getY());
        assertThat(point.isXOdd()).isEqualTo(pub.getPoint().isXOdd());
        // The JDK's X.509 encoding ends with the same 32 raw bytes.
        byte[] encoded = pub.getEncoded();
        assertThat(raw).isEqualTo(java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        PublicKey rebuilt = KeyFactory.getInstance("Ed25519")
                .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
        assertThat(rebuilt.getEncoded()).isEqualTo(pub.getEncoded());
    }

    @Test
    void publicKeyLineMatchesSshjEncoding() throws Exception {
        SshKeyPair kp = SshKeyPair.generate();
        String ours = kp.openSshPublicKey(null);
        byte[] sshjBlob = new Buffer.PlainBuffer().putPublicKey(kp.keyPair().getPublic()).getCompactData();
        assertThat(ours).isEqualTo("ssh-ed25519 " + Base64.getEncoder().encodeToString(sshjBlob));
        assertThat(KeyType.fromKey(kp.keyPair().getPublic())).isEqualTo(KeyType.ED25519);
    }

    @Test
    void sshjCanSignWithTheGeneratedKey() throws Exception {
        // Exercises the exact path sshj uses during public-key authentication.
        SshKeyPair kp = SshKeyPair.generate();
        KeyProvider provider = VmSsh.keyProvider(kp);
        assertThat(provider.getType()).isEqualTo(KeyType.ED25519);

        byte[] data = "vm-testcontainers".getBytes(StandardCharsets.UTF_8);
        net.schmizz.sshj.signature.Signature sshjSig = new com.hierynomus.sshj.signature.SignatureEdDSA.Factory().create();
        sshjSig.initSign(provider.getPrivate());
        sshjSig.update(data);
        byte[] sig = sshjSig.sign();

        Signature jdk = Signature.getInstance("Ed25519");
        jdk.initVerify(kp.keyPair().getPublic());
        jdk.update(data);
        assertThat(jdk.verify(sig)).isTrue();
    }
}
