package com.mercedesbenz.sechub.commons.core.security.persistence;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Providing access to AES-GCM-SIV
 * 
 * AES-GCM-SIV is a nonce misuse-resistant authenticated encryption algorithm.
 * 
 * Refer to <a href="https://datatracker.ietf.org/doc/html/rfc8452">RFC 8452</a>
 * 
 * @author Jeremias Eppler
 */
public class AesGcmSiv implements PersistenceCipher {
    private SecretKey secret;
    private Provider cryptoProvider;
    private static PersistenceCipherType cipherType;

    private static final String ALGORITHM = "AES/GCM-SIV/NoPadding";

    // The recommended initialization vector (iv) for AES-GCM-SIV is 12 bytes or 96
    // bits.
    // For an explanation have a look at:
    // - https://datatracker.ietf.org/doc/html/rfc8452#section-4
    // -
    // https://crypto.stackexchange.com/questions/41601/aes-gcm-recommended-iv-size-why-12-bytes
    public static final int IV_LENGTH_IN_BYTES = 12;

    public static final int AUTHENTICATION_TAG_LENGTH_IN_BITS = 16 * 8; // 16 bytes (128 bits)

    private AesGcmSiv(SecretKey secret) {
        this.secret = secret;
        cryptoProvider = new BouncyCastleProvider();
        Security.addProvider(cryptoProvider);
    }

    public static AesGcmSiv create(String b64Secret) throws InvalidKeyException {
        AesGcmSiv instance = null;

        byte[] rawSecret = Base64.getDecoder().decode(b64Secret);

        if (rawSecret.length == 32 || rawSecret.length == 16) {
            SecretKey secret = new SecretKeySpec(rawSecret, 0, rawSecret.length, "AES");
            instance = new AesGcmSiv(secret);

            cipherType = (rawSecret.length == 32) ? PersistenceCipherType.AES_256_GCM_SIV : PersistenceCipherType.AES_128_GCM_SIV;
        } else {
            throw new InvalidKeyException("The secret has to be 128 or 256 bits long.");
        }

        return instance;
    }

    public static String generateNewInitializationVector() {
        byte[] initializationVector = new byte[IV_LENGTH_IN_BYTES];

        SecureRandom random = new SecureRandom();
        random.nextBytes(initializationVector);

        String b64InitializationVector = Base64.getEncoder().encodeToString(initializationVector);

        return b64InitializationVector;
    }

    public String encrypt(String plainText, String b64InitializationVector) throws InvalidAlgorithmParameterException, InvalidKeyException {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM, cryptoProvider);

            SecretKeySpec keySpec = new SecretKeySpec(secret.getEncoded(), "AES");

            GCMParameterSpec gcmParameterSpec = getParameterSpec(b64InitializationVector);

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmParameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes());

            String b64CipherText = Base64.getEncoder().encodeToString(cipherText);

            return b64CipherText;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException providerException) {
            throw new IllegalStateException("Encryption not possible, please check the provider", providerException);
        } catch (BadPaddingException | IllegalBlockSizeException paddingBlockException) {
            throw new IllegalStateException("Should not occure. AES in GCM-SIV mode does not require padding.", paddingBlockException);
        }
    }

    public String decrypt(String b64CipherText, String b64InitializationVector)
            throws InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(ALGORITHM, cryptoProvider);

            SecretKeySpec keySpec = new SecretKeySpec(secret.getEncoded(), "AES");

            GCMParameterSpec gcmParameterSpec = getParameterSpec(b64InitializationVector);

            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmParameterSpec);

            byte[] cipherText = Base64.getDecoder().decode(b64CipherText);

            byte[] plainTextBytes = cipher.doFinal(cipherText);

            String plainText = new String(plainTextBytes);

            return plainText;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException providerException) {
            throw new IllegalStateException("Decryption not possible, please check the provider", providerException);
        }
    }

    private GCMParameterSpec getParameterSpec(String b64InitializationVector) {
        byte[] initializationVector = Base64.getDecoder().decode(b64InitializationVector);

        return new GCMParameterSpec(AUTHENTICATION_TAG_LENGTH_IN_BITS, initializationVector);
    }

    @Override
    public PersistenceCipherType getCipher() {
        return cipherType;
    }
}