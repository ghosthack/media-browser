/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
// Modified and relocated for robust-seven; see PROVENANCE.toml.
package io.github.ghosthack.seven.internal.commons.compress.archivers.sevenz;

import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.ghosthack.seven.internal.commons.compress.PasswordRequiredException;

final class AES256SHA256Decoder extends AbstractCoder {

    private static final class AES256SHA256DecoderInputStream extends InputStream {
        private final InputStream in;
        private final Coder coder;
        private final String archiveName;
        private final byte[] passwordBytes;
        private boolean isInitialized;
        private CipherInputStream cipherInputStream;

        private AES256SHA256DecoderInputStream(final InputStream in, final Coder coder, final String archiveName, final byte[] passwordBytes) {
            this.in = in;
            this.coder = coder;
            this.archiveName = archiveName;
            this.passwordBytes = passwordBytes;
        }

        @Override
        public void close() throws IOException {
            if (cipherInputStream != null) {
                cipherInputStream.close();
            }
        }

        private CipherInputStream init() throws IOException {
            if (isInitialized) {
                return cipherInputStream;
            }
            if (coder.properties == null) {
                throw new IOException("Missing AES256 properties in " + archiveName);
            }
            if (coder.properties.length < 2) {
                throw new IOException("AES256 properties too short in " + archiveName);
            }
            final int byte0 = 0xff & coder.properties[0];
            final int numCyclesPower = byte0 & 0x3f;
            final int byte1 = 0xff & coder.properties[1];
            final int ivSize = (byte0 >> 6 & 1) + (byte1 & 0x0f);
            final int saltSize = (byte0 >> 7 & 1) + (byte1 >> 4);
            if (2 + saltSize + ivSize > coder.properties.length) {
                throw new IOException("Salt size + IV size too long in " + archiveName);
            }
            final byte[] salt = new byte[saltSize];
            System.arraycopy(coder.properties, 2, salt, 0, saltSize);
            final byte[] iv = new byte[16];
            System.arraycopy(coder.properties, 2 + saltSize, iv, 0, ivSize);

            if (passwordBytes == null) {
                throw new PasswordRequiredException(archiveName);
            }
            final byte[] aesKeyBytes;
            if (numCyclesPower == 0x3f) {
                aesKeyBytes = new byte[32];
                System.arraycopy(salt, 0, aesKeyBytes, 0, saltSize);
                System.arraycopy(passwordBytes, 0, aesKeyBytes, saltSize, Math.min(passwordBytes.length, aesKeyBytes.length - saltSize));
            } else {
                aesKeyBytes = sha256Password(passwordBytes, numCyclesPower, salt);
            }

            final SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            try {
                final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
                cipherInputStream = new CipherInputStream(in, cipher);
                isInitialized = true;
                return cipherInputStream;
            } catch (final GeneralSecurityException generalSecurityException) {
                throw new IllegalStateException("Decryption error (do you have the JCE Unlimited Strength Jurisdiction Policy Files installed?)",
                        generalSecurityException);
            }
        }

        @SuppressWarnings("resource") // Closed in close()
        @Override
        public int read() throws IOException {
            return init().read();
        }

        @SuppressWarnings("resource") // Closed in close()
        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            return init().read(b, off, len);
        }
    }

    static byte[] sha256Password(final byte[] password, final int numCyclesPower, final byte[] salt) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 is unsupported by your Java implementation", noSuchAlgorithmException);
        }
        final byte[] extra = new byte[8];
        for (long j = 0; j < 1L << numCyclesPower; j++) {
            digest.update(salt);
            digest.update(password);
            digest.update(extra);
            for (int k = 0; k < extra.length; k++) {
                ++extra[k];
                if (extra[k] != 0) {
                    break;
                }
            }
        }
        return digest.digest();
    }

    static byte[] sha256Password(final char[] password, final int numCyclesPower, final byte[] salt) {
        return sha256Password(utf16Decode(password), numCyclesPower, salt);
    }

    /**
     * Convenience method that encodes Unicode characters into bytes in UTF-16 (little-endian byte order) charset
     *
     * @param chars characters to encode
     * @return encoded characters
     * @since 1.23
     */
    static byte[] utf16Decode(final char[] chars) {
        if (chars == null) {
            return null;
        }
        final ByteBuffer encoded = UTF_16LE.encode(CharBuffer.wrap(chars));
        if (encoded.hasArray()) {
            return encoded.array();
        }
        final byte[] e = new byte[encoded.remaining()];
        encoded.get(e);
        return e;
    }

    @Override
    InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] passwordBytes,
            final int maxMemoryLimitKiB) {
        return new AES256SHA256DecoderInputStream(in, coder, archiveName, passwordBytes);
    }

}
