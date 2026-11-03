package com.mightyoung.softwarefactory.files;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileUploadProducerReadinessTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileEvidenceVerificationAutoConfiguration.class));
    @Test void fixedVersionReaderAndReceiptIssuerDoNotEnableUploads() {
        runner.withBean(VerifiedStorageVersionReadPort.class,() -> mock(VerifiedStorageVersionReadPort.class))
                .withBean(TrustedContentReceiptIssuer.class,() -> mock(TrustedContentReceiptIssuer.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var producer=context.getBean(UploadProducerReadiness.class);
                    assertThat(producer).isInstanceOf(UnconfiguredUploadProducerReadiness.class);
                    assertThatThrownBy(producer::requireReady).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                    assertThat(producer.isCurrent(null)).isFalse();
                });
    }
    @Test void actualProducerCapabilityBeanReplacesOnlyDenyingDefault() {
        var producer=mock(UploadProducerReadiness.class);
        runner.withBean(UploadProducerReadiness.class,() -> producer).run(context -> {
            assertThat(context).hasSingleBean(UploadProducerReadiness.class);
            assertThat(context.getBean(UploadProducerReadiness.class)).isSameAs(producer);
        });
    }
}
