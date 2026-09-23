package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CloudInitSeedTest {

    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample vm-testcontainers";

    @Test
    void metaDataHasInstanceIdAndHostname() {
        CloudInitSeed seed = new CloudInitSeed("vmtc-1", "box", "tc", KEY, null);
        assertThat(seed.metaData()).isEqualTo("instance-id: vmtc-1\nlocal-hostname: box\n");
    }

    @Test
    void withoutCallerDataUserDataIsPlainCloudConfig() {
        CloudInitSeed seed = new CloudInitSeed("i", "h", "tc", KEY, null);
        String ud = seed.userData();
        assertThat(ud).startsWith("#cloud-config\n");
        assertThat(ud).contains("merge_how: \"" + CloudInitSeed.MERGE_HOW + "\"");
        assertThat(ud).contains("  - default\n");
        assertThat(ud).contains("  - name: tc\n");
        assertThat(ud).contains("sudo: \"ALL=(ALL) NOPASSWD:ALL\"");
        assertThat(ud).contains("      - \"" + KEY + "\"\n");
        assertThat(ud).contains("ssh_pwauth: false");
    }

    @Test
    void withCloudConfigCallerDataProducesMultipartWithModulePartLast() {
        String caller = "#cloud-config\npackages:\n  - nginx\n";
        CloudInitSeed seed = new CloudInitSeed("i", "h", "tc", KEY, caller);
        String ud = seed.userData();
        assertThat(ud).startsWith("Content-Type: multipart/mixed; boundary=");
        assertThat(ud).contains("MIME-Version: 1.0");
        int callerIdx = ud.indexOf("packages:\n  - nginx");
        int moduleIdx = ud.indexOf("merge_how:");
        assertThat(callerIdx).isPositive();
        assertThat(moduleIdx).isGreaterThan(callerIdx);
        assertThat(ud).contains("Content-Type: text/cloud-config; charset=\"utf-8\"");
        assertThat(ud.lines().filter(l -> l.startsWith("--==vm-testcontainers-boundary==")).count()).isEqualTo(3);
        assertThat(ud).endsWith("--==vm-testcontainers-boundary==--\n");
    }

    @Test
    void shellScriptCallerDataIsTypedAsShellScript() {
        CloudInitSeed seed = new CloudInitSeed("i", "h", "tc", KEY, "#!/bin/bash\necho hi\n");
        assertThat(seed.userData()).contains("Content-Type: text/x-shellscript; charset=\"utf-8\"");
    }

    @Test
    void unknownCallerDataIsRejected() {
        assertThatThrownBy(() -> new CloudInitSeed("i", "h", "tc", KEY, "packages: [nginx]").userData())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#cloud-config");
    }

    @Test
    void userNamesAreValidated() {
        assertThatThrownBy(() -> new CloudInitSeed("i", "h", "Bad User", KEY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudInitSeed("i", "h", "tc", "line1\nline2", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
