package com.mightyoung.softwarefactory.files;

import java.io.InputStream;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Imported before legacy fallback beans; explicit enabled configuration still requires actual resource controls. */
@Configuration(proxyBeanMethods=false)
public class FileIngestionConfiguration {
    @Bean @ConditionalOnMissingBean
    FileIngestionProperties fileIngestionProperties(Environment environment){return FileIngestionProperties.from(environment);}

    @Bean @ConditionalOnMissingBean
    IngestionProofCodec ingestionProofCodec(Environment environment) {
        Map<String,String> encoded=Binder.get(environment).bind("software-factory.files.ingestion.verification-keys",Bindable.mapOf(String.class,String.class)).orElse(Map.of());
        String signing=environment.getProperty("software-factory.files.ingestion.signing-key-id");
        if(encoded.isEmpty()&&signing==null)return IngestionProofCodec.unavailable();
        try {
            var keys=new HashMap<String,byte[]>();encoded.forEach((id,value)->keys.put(id,Base64.getDecoder().decode(value)));
            return new IngestionProofCodec(keys,signing,Clock.systemUTC());
        } catch(IllegalArgumentException|NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid ingestion trust key configuration");
        }
    }

    @Bean
    @ConditionalOnProperty(prefix="software-factory.files.ingestion",name="enabled",havingValue="true")
    @ConditionalOnMissingBean({FileIngestionPort.class,UploadProducerReadiness.class})
    LocalPrivateUploadProducer localPrivateUploadProducer(FileIngestionProperties properties,IngestionProofCodec codec) {
        return new LocalPrivateUploadProducer(properties,codec);
    }

    @Bean @ConditionalOnMissingBean(FileIngestionPort.class)
    FileIngestionPort unconfiguredFileIngestionPort(){return new UnconfiguredFileIngestionPort();}

    private static final class UnconfiguredFileIngestionPort implements FileIngestionPort {
        public ProducerResources currentResources(){deny();throw unavailable();}
        public QuarantinedObject receive(ReceiveAllocation allocation,InputStream input,Deadline deadline){deny();throw unavailable();}
        public ScanOutcome scan(QuarantinedObject source,ScanAllocation allocation,AcceptedGeneration generation,TechnicalFence fence,RecordedExecution execution,PreparedSlotLease lease,Deadline deadline){deny();throw unavailable();}
        public ImmutableObjectAndReceipt publishImmutable(QuarantinedObject source,ScanOutcome outcome,CopyAllocation copy,ReceiptAllocation receipt,Deadline deadline){deny();throw unavailable();}
        public PreparedSlotLease prepareSlot(ScanPlan plan,Deadline deadline){deny();throw unavailable();}
        public RecoveryObservation observeRecovery(RecordedAllocation allocation,java.util.Optional<RecordedExecution> execution,RecoveryBinding binding,Deadline deadline){deny();throw unavailable();}
        public DisposalObservation dispose(DisposalClaim claim,RecoveryBinding binding,Deadline deadline){deny();throw unavailable();}
        private static void deny(){LocalPrivateUploadProducer.noTransaction();}
        private static FileEvidenceTrustUnavailableException unavailable(){return new FileEvidenceTrustUnavailableException();}
    }
}
