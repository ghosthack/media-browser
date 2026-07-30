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

/**
 * Abstracts a base Codec class.
 */
abstract class AbstractCoder {
    /**
     * Decodes using stream that reads from in using the configured coder and password.
     *
     * @return a stream that reads from in using the configured coder and password.
     */
    abstract InputStream decode(String archiveName, InputStream in, long uncompressedLength, Coder coder, byte[] password, int maxMemoryLimitKiB)
            throws IOException;
}
