package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class VmSshTest {

    @Test
    void safeArgumentsAreNotQuoted() {
        assertThat(VmSsh.shellJoin(List.of("systemctl", "is-active", "nginx.service"))).isEqualTo("systemctl is-active nginx.service");
        assertThat(VmSsh.shellQuote("/usr/bin/env")).isEqualTo("/usr/bin/env");
        assertThat(VmSsh.shellQuote("a=b")).isEqualTo("a=b");
    }

    @Test
    void unsafeArgumentsAreSingleQuoted() {
        assertThat(VmSsh.shellQuote("hello world")).isEqualTo("'hello world'");
        assertThat(VmSsh.shellQuote("")).isEqualTo("''");
        assertThat(VmSsh.shellQuote("it's")).isEqualTo("'it'\\''s'");
        assertThat(VmSsh.shellQuote("$HOME; rm -rf /")).isEqualTo("'$HOME; rm -rf /'");
        assertThat(VmSsh.shellJoin(List.of("bash", "-c", "echo $((1+1)) > /tmp/x")))
                .isEqualTo("bash -c 'echo $((1+1)) > /tmp/x'");
    }
}
