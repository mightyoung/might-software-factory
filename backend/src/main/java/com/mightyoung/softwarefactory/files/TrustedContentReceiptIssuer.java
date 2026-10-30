package com.mightyoung.softwarefactory.files;

/**
 * Out-of-transaction seam for a trusted storage verifier to hash bytes and register a receipt after its
 * upload/scanning workflow has placed a file in READY state. Never call this from a business command.
 */
public interface TrustedContentReceiptIssuer {

    ContentVerificationReceipt verifyAndRegister(FileObjectMetadata file);
}
