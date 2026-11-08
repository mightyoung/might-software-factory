package com.mightyoung.softwarefactory.files;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileIngestionConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileEvidenceVerificationAutoConfiguration.class));
    @Test void defaultPortsDenyBeforeReadingStreamAndCannotBeEnabledByLegacyReader() {
        var input=mock(InputStream.class);
        runner.withBean(VerifiedStorageVersionReadPort.class,()->mock(VerifiedStorageVersionReadPort.class))
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(FileIngestionPort.class).hasSingleBean(UploadProducerReadiness.class);
                    var port=context.getBean(FileIngestionPort.class);
                    assertThatThrownBy(port::currentResources).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                    assertThatThrownBy(()->port.receive(null,input,null)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                    assertThatThrownBy(()->port.scan(null,null,null,null,null,null,null)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                    assertThatThrownBy(()->port.publishImmutable(null,null,null,null,null)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                    assertThatThrownBy(()->port.dispose(null,null,null)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                    assertThat(port.isCurrentGeneration(null)).isFalse();
                    assertThat(context.getBean(UploadProducerReadiness.class)).isInstanceOf(UnconfiguredUploadProducerReadiness.class);
                    assertThatThrownBy(()->context.getBean(IngestionProofCodec.class).requireSigningAvailable()).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                });
        verifyNoInteractions(input);
    }
    @Test void explicitExternalPortsReplaceDefaultsWithoutDuplicateReadiness() {
        var port=mock(FileIngestionPort.class);var readiness=mock(UploadProducerReadiness.class);
        runner.withBean(FileIngestionPort.class,()->port).withBean(UploadProducerReadiness.class,()->readiness).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(FileIngestionPort.class).hasSingleBean(UploadProducerReadiness.class);
            assertThat(context.getBean(FileIngestionPort.class)).isSameAs(port);assertThat(context.getBean(UploadProducerReadiness.class)).isSameAs(readiness);
        });
    }
    @Test void declaredConfiguredProducerProvidesOneCoherentPortAndReadinessWithoutResourcePromotion(){
        runner.withPropertyValues("software-factory.files.ingestion.enabled=true")
                .withBean(FileIngestionProperties.class,ClamScanProcessAdapterTest::properties)
                .withBean(IngestionProofCodec.class,IngestionProofCodec::unavailable).run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(FileIngestionPort.class).hasSingleBean(UploadProducerReadiness.class);
                    assertThat(context.getBean(FileIngestionPort.class)).isSameAs(context.getBean(UploadProducerReadiness.class)).isInstanceOf(LocalPrivateUploadProducer.class);
                    assertThatThrownBy(context.getBean(UploadProducerReadiness.class)::requireReady).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                });
    }
    @Test void enablingWithoutRequiredResourceConfigurationCannotPromoteReadiness() {
        runner.withPropertyValues("software-factory.files.ingestion.enabled=true").run(context->assertThat(context).hasFailed());
    }
    @Test void malformedTrustKeysFailWithoutIncludingTheSuppliedSecretInTheSafeException() {
        runner.withPropertyValues("software-factory.files.ingestion.signing-key-id=key-one",
                "software-factory.files.ingestion.verification-keys.key-one=not-base64-secret")
                .run(context->{assertThat(context).hasFailed();
                    Throwable failure=context.getStartupFailure();while(failure.getCause()!=null)failure=failure.getCause();
                    assertThat(failure.getMessage()).isEqualTo("Invalid ingestion trust key configuration").doesNotContain("not-base64-secret");
                });
    }
}
