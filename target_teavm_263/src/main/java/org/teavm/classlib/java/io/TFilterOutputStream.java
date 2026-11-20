/*
 *  Copyright 2013 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.java.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Classlib twin: only {@link #close()} differs from upstream.
 *
 * <p>Upstream's {@code close()} flushed the stream UNDERNEATH ({@code out.flush()}) and never this
 * one, where java.io calls {@code this.flush()} first. A {@code BufferedOutputStream} that is closed
 * therefore threw away whatever was still in its own buffer, with no error.
 *
 * <p>What that broke: every compressed NBT file. {@code NbtIo.writeCompressed(tag, path)} is
 * {@code DataOutputStream(BufferedOutputStream(GZIPOutputStream(BufferedOutputStream(file))))}.
 * Closing the two outer wrappers flushes the gzip stream twice, and one flush of
 * {@code TGZIPOutputStream} emits at most its 512-byte buffer; everything else (the rest of the
 * compressed data, the final block, the CRC and length) is written by {@code finish()} when the gzip
 * stream closes, into the inner {@code BufferedOutputStream}, which was then closed unflushed. So a
 * file kept its 10-byte header and at most 1,024 bytes of data: small files were whole but had no
 * gzip end (the game's own reader does not mind), and anything that compresses to more than 1,024
 * bytes was cut off there and could not be read again.
 *
 * <p>A player's data file crosses that line with two or three kinds of item in the inventory. From
 * then on every save of that player was unreadable ("Failed to load player data for ..." on the next
 * join, the {@code .dat_old} copy being cut the same way), and the player came back with nothing, or
 * with what an older and smaller save held: guests of a LAN world when they joined again, the host
 * when the world was opened again. Measured 2026-11-19 with a guest and a host holding 15 kinds of
 * item: both files 1,034 bytes, NBT ending mid-tag (3,265 and 3,701 bytes readable); the guest left
 * with Disconnect, joined again ten seconds later and had an empty inventory at the spawn point.
 * With 5 diamonds and 3 gold ingots alone (1,028 bytes) everything survived, which is how this
 * passed every quick test. Any other compressed NBT file over that size was cut the same way.
 *
 * <p>The guard against a second {@code close()} is java.io's too; without it a stream closed twice
 * would flush into wrapped streams that are already closed.
 */
public class TFilterOutputStream extends TOutputStream {
    protected OutputStream out;
    private boolean closed;

    public TFilterOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        } catch (IOException e) {
            // as upstream: the stream underneath is still closed
        }
        out.close();
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }
}
