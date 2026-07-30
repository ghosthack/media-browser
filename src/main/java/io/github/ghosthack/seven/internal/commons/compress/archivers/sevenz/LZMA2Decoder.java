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

import java.io.IOException;
import java.io.InputStream;

import io.github.ghosthack.seven.internal.commons.compress.MemoryLimitException;
import io.github.ghosthack.seven.internal.xz.LZMA2InputStream;

final class LZMA2Decoder extends AbstractCoder {

    @Override
    InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] password,
            final int maxMemoryLimitKiB) throws IOException {
        try {
            final int dictionarySize = getDictionarySize(coder);
            final int memoryUsageInKiB = LZMA2InputStream.getMemoryUsage(dictionarySize);
            if (memoryUsageInKiB > maxMemoryLimitKiB) {
                throw new MemoryLimitException(memoryUsageInKiB, maxMemoryLimitKiB);
            }
            return new LZMA2InputStream(in, dictionarySize);
        } catch (final IllegalArgumentException ex) { // NOSONAR
            throw new IOException(ex);
        }
    }

    private int getDictionarySize(final Coder coder) throws IOException {
        if (coder.properties == null) {
            throw new IOException("Missing LZMA2 properties");
        }
        if (coder.properties.length < 1) {
            throw new IOException("LZMA2 properties too short");
        }
        final int dictionarySizeBits = 0xff & coder.properties[0];
        if ((dictionarySizeBits & ~0x3f) != 0) {
            throw new IOException("Unsupported LZMA2 property bits");
        }
        if (dictionarySizeBits > 40) {
            throw new IOException("Dictionary larger than 4GiB maximum size");
        }
        if (dictionarySizeBits == 40) {
            return 0xFFFFffff;
        }
        return (2 | dictionarySizeBits & 0x1) << dictionarySizeBits / 2 + 11;
    }

}
