// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Set;
import org.teavm.classlib.java.nio.file.TOpenOption;
import org.teavm.classlib.java.nio.file.TPath;
import org.teavm.classlib.java.nio.file.TStandardOpenOption;
import org.teavm.classlib.java.nio.file.TVfs;
import org.teavm.classlib.java.nio.file.attribute.TFileAttribute;
import org.teavm.runtime.fs.VirtualFileAccessor;

public class TFileChannel implements ByteChannel {
	private final TPath path;
	private final String key;
	private final VirtualFileAccessor accessor;
	private final boolean readable;
	private final boolean writable;
	private boolean open = true;
	private TFileLock held;
	private Runnable onClose;
	private boolean autoFlush;

	private TFileChannel(TPath path, String key, VirtualFileAccessor accessor, boolean readable, boolean writable) {
		this.path = path;
		this.key = key;
		this.accessor = accessor;
		this.readable = readable;
		this.writable = writable;
	}

	public static TFileChannel open(TPath path, TOpenOption... options) throws IOException {
		return openWith(path, options);
	}

	public static TFileChannel forRandomAccessFile(TPath path, VirtualFileAccessor accessor,
			boolean writable, boolean autoFlush, Runnable onClose) {
		TFileChannel channel = new TFileChannel(path, TVfs.key(path), accessor, true, writable);
		channel.onClose = java.util.Objects.requireNonNull(onClose);
		channel.autoFlush = autoFlush;
		return channel;
	}

	public static TFileChannel open(TPath path, Set<? extends TOpenOption> options, TFileAttribute<?>... attrs)
			throws IOException {
		return openWith(path, options == null ? new TOpenOption[0] : options.toArray(new TOpenOption[0]));
	}

	private static TFileChannel openWith(TPath path, TOpenOption[] options) throws IOException {
		boolean write = TVfs.has(options, TStandardOpenOption.WRITE)
				|| TVfs.has(options, TStandardOpenOption.APPEND);
		boolean read = TVfs.has(options, TStandardOpenOption.READ) || !write;
		boolean create = TVfs.has(options, TStandardOpenOption.CREATE);
		boolean createNew = TVfs.has(options, TStandardOpenOption.CREATE_NEW);
		boolean append = TVfs.has(options, TStandardOpenOption.APPEND);
		boolean truncate = TVfs.has(options, TStandardOpenOption.TRUNCATE_EXISTING);
		VirtualFileAccessor accessor = TVfs.open(path, read, write, create, createNew, append, truncate);
		TFileChannel channel = new TFileChannel(path, TVfs.key(path), accessor, read, write);
		accessor.seek(append ? accessor.size() : 0);
		return channel;
	}

	private void check() throws IOException {
		if (!open) {
			throw new IOException("channel closed: " + path);
		}
	}

	public int read(ByteBuffer dst) throws IOException {
		long position = position();
		int n = read(dst, position);
		if (n > 0) {
			position(position + n);
		}
		return n;
	}

	public int read(ByteBuffer dst, long position) throws IOException {
		check();
		int offset = checkedOffset(position);
		if (!readable) {
			throw new TNonReadableChannelException();
		}
		int want = dst.remaining();
		if (want == 0) {
			return 0;
		}
		byte[] tmp = new byte[want];
		int previous = accessor.tell();
		int n;
		try {
			accessor.seek(offset);
			n = accessor.read(tmp, 0, want);
		} finally {
			accessor.seek(previous);
		}
		if (n <= 0) {
			return -1;
		}
		dst.put(tmp, 0, n);
		return n;
	}

	public int write(ByteBuffer src) throws IOException {
		long position = position();
		int n = write(src, position);
		position(position + n);
		return n;
	}

	public int write(ByteBuffer src, long position) throws IOException {
		check();
		int offset = checkedOffset(position);
		if (!writable) {
			throw new TNonWritableChannelException();
		}
		int n = src.remaining();
		if (n == 0) {
			return 0;
		}
		byte[] tmp = new byte[n];
		src.get(tmp, 0, n);
		int previous = accessor.tell();
		try {
			accessor.seek(offset);
			accessor.write(tmp, 0, n);
			if (autoFlush) accessor.flush();
		} finally {
			accessor.seek(previous);
		}
		return n;
	}

	public long position() throws IOException {
		check();
		return accessor.tell();
	}

	public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
		java.util.Objects.requireNonNull(target);
		if (position < 0 || count < 0) throw new IllegalArgumentException("Negative transfer range");
		if (!open || !target.isOpen()) throw new ClosedChannelException();
		if (!readable) throw new TNonReadableChannelException();
		if (target instanceof TFileChannel && !((TFileChannel) target).writable) {
			throw new TNonWritableChannelException();
		}
		long remaining = Math.min(count, Math.max(0L, size() - position));
		if (remaining == 0) return 0;
		ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(8192, remaining));
		long transferred = 0;
		while (transferred < remaining) {
			buffer.clear().limit((int) Math.min(buffer.capacity(), remaining - transferred));
			int read = read(buffer, position + transferred);
			if (read <= 0) break;
			buffer.flip();
			int written = target.write(buffer);
			transferred += written;
			if (written < read) break;
		}
		return transferred;
	}

	public TFileChannel position(long newPosition) throws IOException {
		check();
		accessor.seek(checkedOffset(newPosition));
		return this;
	}

	public long size() throws IOException {
		check();
		return accessor.size();
	}

	public TFileChannel truncate(long size) throws IOException {
		check();
		int length = checkedOffset(size);
		if (!writable) throw new TNonWritableChannelException();
		if (size < accessor.size()) {
			accessor.resize(length);
			if (autoFlush) accessor.flush();
		}
		if (accessor.tell() > size) {
			accessor.seek(length);
		}
		return this;
	}

	private static int checkedOffset(long offset) throws IOException {
		if (offset < 0) throw new IllegalArgumentException("Negative file offset");
		if (offset > Integer.MAX_VALUE) throw new IOException("Browser file offset exceeds 2 GiB limit");
		return (int) offset;
	}

	public void force(boolean metaData) throws IOException {
		check();
		accessor.flush();
	}

	public TFileLock tryLock() throws IOException {
		return tryLock(0L, Long.MAX_VALUE, false);
	}

	public TFileLock tryLock(long position, long size, boolean shared) throws IOException {
		check();
		if (held != null && held.isValid()) {
			return null;
		}
		if (!TVfs.tryLock(key)) {
			return null;
		}
		held = new TFileLock(key);
		return held;
	}

	public TFileLock lock() throws IOException {
		TFileLock lock = tryLock();
		if (lock == null) {
			throw new IOException("already locked: " + path);
		}
		return lock;
	}

	public boolean isOpen() {
		return open;
	}

	@Override
	public void close() throws IOException {
		if (!open) {
			return;
		}
		open = false;
		if (held != null) {
			held.release();
			held = null;
		}
		try {
			accessor.close();
		} finally {
			if (onClose != null) onClose.run();
		}
	}
}
