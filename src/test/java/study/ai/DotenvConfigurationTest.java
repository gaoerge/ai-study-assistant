package study.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DotenvConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void missingOptionalFileKeepsFakeDefault() {
        try (var context = start(directory.resolve("missing.env"), Map.of())) {
            assertEquals("fake", context.getEnvironment().getProperty("study.llm.mode"));
            assertEquals("", context.getEnvironment().getProperty("study.llm.deepseek.api-key"));
        }
    }

    @Test
    void readsModeAndKeyFromExtensionlessLocalFile() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "STUDY_LLM_MODE=deepseek\nDEEPSEEK_API_KEY=test-file-key\n");
        try (var context = start(file, Map.of())) {
            assertEquals("deepseek", context.getEnvironment().getProperty("study.llm.mode"));
            assertEquals("test-file-key", context.getEnvironment().getProperty("study.llm.deepseek.api-key"));
        }
    }

    @Test
    void environmentVariablesOverrideLocalFile() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "STUDY_LLM_MODE=deepseek\nDEEPSEEK_API_KEY=test-file-key\n");
        try (var context = start(file, Map.of(
                "STUDY_LLM_MODE", "fake", "DEEPSEEK_API_KEY", "test-environment-key"))) {
            assertEquals("fake", context.getEnvironment().getProperty("study.llm.mode"));
            assertEquals("test-environment-key", context.getEnvironment().getProperty("study.llm.deepseek.api-key"));
        }
    }

    private ConfigurableApplicationContext start(Path file, Map<String, Object> variables) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("systemEnvironment", variables));
        var app = new SpringApplication(EmptyConfiguration.class);
        app.setEnvironment(environment);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        return app.run("--spring.config.import=optional:" + file.toUri() + "[.properties]");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
