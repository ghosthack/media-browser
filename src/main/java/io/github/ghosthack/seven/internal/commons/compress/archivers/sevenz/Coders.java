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

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import io.github.ghosthack.seven.internal.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import io.github.ghosthack.seven.internal.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import io.github.ghosthack.seven.internal.xz.SimpleInputStream;
import io.github.ghosthack.seven.internal.xz.simple.ARM;
import io.github.ghosthack.seven.internal.xz.simple.ARMThumb;
import io.github.ghosthack.seven.internal.xz.simple.IA64;
import io.github.ghosthack.seven.internal.xz.simple.PowerPC;
import io.github.ghosthack.seven.internal.xz.simple.SPARC;
import io.github.ghosthack.seven.internal.xz.simple.SimpleFilter;
import io.github.ghosthack.seven.internal.xz.simple.X86;

final class Coders {

    static final class BCJDecoder extends AbstractCoder {
        private final Supplier<SimpleFilter> filterFactory;

        BCJDecoder(final Supplier<SimpleFilter> filterFactory) {
            this.filterFactory = filterFactory;
        }

        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] password,
                final int maxMemoryLimitKiB) throws IOException {
            return new SimpleInputStream(in, filterFactory.get());
        }

    }

    static final class BZIP2Decoder extends AbstractCoder {
        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] password,
                final int maxMemoryLimitKiB) throws IOException {
            return new BZip2CompressorInputStream(in);
        }

    }

    static final class CopyDecoder extends AbstractCoder {
        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] password,
                final int maxMemoryLimitKiB) throws IOException {
            return in;
        }

    }

    static final class Deflate64Decoder extends AbstractCoder {
        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] password,
                final int maxMemoryLimitKiB) throws IOException {
            return new Deflate64CompressorInputStream(in);
        }
    }

    static final class DeflateDecoder extends AbstractCoder {

        static final class DeflateDecoderInputStream extends FilterInputStream {

            Inflater inflater;

            DeflateDecoderInputStream(final InflaterInputStream inflaterInputStream, final Inflater inflater) {
                super(inflaterInputStream);
                this.inflater = inflater;
            }

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    inflater.end();
                }
            }

        }

        private static final byte[] ONE_ZERO_BYTE = new byte[1];

        @Override
        InputStream decode(final String archiveName, final InputStream in, final long uncompressedLength, final Coder coder, final byte[] password,
                final int maxMemoryLimitKiB) throws IOException {
            final Inflater inflater = new Inflater(true);
            // Inflater with nowrap=true has this odd contract for a zero padding
            // byte following the data stream; this used to be zlib's requirement
            // and has been fixed a long time ago, but the contract persists so
            // we comply.
            // https://docs.oracle.com/javase/8/docs/api/java/util/zip/Inflater.html#Inflater(boolean)
            final InflaterInputStream inflaterInputStream = new InflaterInputStream(new SequenceInputStream(in, new ByteArrayInputStream(ONE_ZERO_BYTE)),
                    inflater);
            return new DeflateDecoderInputStream(inflaterInputStream, inflater);
        }

    }

    private static final Map<SevenZMethod, AbstractCoder> CODER_MAP =
            Map.ofEntries(
                    Map.entry(SevenZMethod.COPY, new CopyDecoder()),
                    Map.entry(SevenZMethod.LZMA, new LZMADecoder()),
                    Map.entry(SevenZMethod.LZMA2, new LZMA2Decoder()),
                    Map.entry(SevenZMethod.DEFLATE, new DeflateDecoder()),
                    Map.entry(SevenZMethod.DEFLATE64, new Deflate64Decoder()),
                    Map.entry(SevenZMethod.BZIP2, new BZIP2Decoder()),
                    Map.entry(SevenZMethod.AES256SHA256, new AES256SHA256Decoder()),
                    Map.entry(
                            SevenZMethod.BCJ_X86_FILTER,
                            new BCJDecoder(() -> new X86(false, 0))),
                    Map.entry(
                            SevenZMethod.BCJ_PPC_FILTER,
                            new BCJDecoder(() -> new PowerPC(false, 0))),
                    Map.entry(
                            SevenZMethod.BCJ_IA64_FILTER,
                            new BCJDecoder(() -> new IA64(false, 0))),
                    Map.entry(
                            SevenZMethod.BCJ_ARM_FILTER,
                            new BCJDecoder(() -> new ARM(false, 0))),
                    Map.entry(
                            SevenZMethod.BCJ_ARM_THUMB_FILTER,
                            new BCJDecoder(() -> new ARMThumb(false, 0))),
                    Map.entry(
                            SevenZMethod.BCJ_SPARC_FILTER,
                            new BCJDecoder(() -> new SPARC(false, 0))),
                    Map.entry(SevenZMethod.DELTA_FILTER, new DeltaDecoder()));

    static InputStream addDecoder(final String archiveName, final InputStream is, final long uncompressedLength, final Coder coder, final byte[] password,
            final int maxMemoryLimitKiB) throws IOException {
        final AbstractCoder cb = findByMethod(SevenZMethod.byId(coder.decompressionMethodId));
        if (cb == null) {
            throw new IOException("Unsupported compression method " + Arrays.toString(coder.decompressionMethodId) + " used in " + archiveName);
        }
        return cb.decode(archiveName, is, uncompressedLength, coder, password, maxMemoryLimitKiB);
    }

    static AbstractCoder findByMethod(final SevenZMethod method) {
        return CODER_MAP.get(method);
    }

}
