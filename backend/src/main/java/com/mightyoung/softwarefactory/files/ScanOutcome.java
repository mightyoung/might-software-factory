package com.mightyoung.softwarefactory.files;

import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Files-only validated successful scan capability; public DTO construction is insufficient. */
public final class ScanOutcome {
    private final SignedScanProofV1 proof;
    private final RecordedExecution execution;
    private final KernelExecutionFacts kernelFacts;
    ScanOutcome(SignedScanProofV1 proof, RecordedExecution execution, KernelExecutionFacts kernelFacts) {
        required(proof,execution,kernelFacts);
        IngestionProofCodec.requireCleanExecution(proof,execution,kernelFacts);
        this.proof=proof;this.execution=execution;this.kernelFacts=kernelFacts;
    }
    public SignedScanProofV1 proof(){return proof;}
    public RecordedExecution execution(){return execution;}
    public KernelExecutionFacts kernelFacts(){return kernelFacts;}
    @Override public String toString(){return "ScanOutcome[proofId="+proof.proofId()+"]";}
}
