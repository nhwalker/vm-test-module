package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

class VmContainerConfigTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(VmContainer.RUNNER_IMAGE_PROPERTY);
    }

    @Test
    void libraryVersionIsBakedIntoTheJar() {
        String v = VmContainer.libraryVersion();
        assertThat(v).isNotBlank().doesNotContain("${");
    }

    @Test
    void defaultRunnerImageUsesLibraryVersionTag() {
        DockerImageName name = VmContainer.defaultRunnerImage();
        assertThat(name.getUnversionedPart()).isEqualTo(VmContainer.DEFAULT_RUNNER_IMAGE);
        assertThat(name.getVersionPart()).isEqualTo(VmContainer.libraryVersion());
    }

    @Test
    void systemPropertyOverridesRunnerImage() {
        System.setProperty(VmContainer.RUNNER_IMAGE_PROPERTY, "localhost/vm-runner:dev");
        assertThat(VmContainer.defaultRunnerImage().asCanonicalNameString()).isEqualTo("localhost/vm-runner:dev");
    }
}
