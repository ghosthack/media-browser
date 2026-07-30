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
import io.github.ghosthack.seven.internal.xz.LZMAInputStream;

final class LZMADecoder extends AbstractCoder {

    @Override
    InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] password,
            final int maxMemoryLimitKiB) throws IOException {
        if (coder.properties == null) {
            throw new IOException("Missing LZMA properties");
        }
        if (coder.properties.length < 5) {
            throw new IOException("LZMA properties too short");
        }
        final byte propsByte = coder.properties[0];
        final int dictSize = getDictionarySize(coder);
        if (dictSize > LZMAInputStream.DICT_SIZE_MAX) {
            throw new IOException("Dictionary larger than 4GiB maximum size used in " + archiveName);
        }
        final int memoryUsageInKiB = LZMAInputStream.getMemoryUsage(dictSize, propsByte);
        if (memoryUsageInKiB > maxMemoryLimitKiB) {
            throw new MemoryLimitException(memoryUsageInKiB, maxMemoryLimitKiB);
        }
        final LZMAInputStream lzmaIn = new LZMAInputStream(in, uncompressedLength, propsByte, dictSize);
        lzmaIn.enableRelaxedEndCondition();
        return lzmaIn;
    }

    private int getDictionarySize(final Coder coder) throws IllegalArgumentException {
        int dictionarySize = 0;
        for (int index = 0; index < 4; index++) {
            dictionarySize |=
                    (coder.properties[index + 1] & 0xff) << (index * 8);
        }
        return dictionarySize;
    }

}
