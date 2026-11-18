// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.teavm.classlib.java.nio.file.attribute.TBasicFileAttributes;
import org.teavm.classlib.java.nio.file.attribute.TFileTime;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;
import org.teavm.runtime.fs.VirtualFileSystem;
import org.teavm.runtime.fs.VirtualFileSystemProvider;

public final class TVfs {
	private TVfs() {
	}

	private static final Map<String, TPath> LOCKS = new HashMap<>();

	private static String[] foreign = new String[0];

	private static int tempCounter;

	public static void disown(String prefix) {
		String[] grown = Arrays.copyOf(foreign, foreign.length + 1);
		grown[foreign.length] = prefix;
		foreign = grown;
	}

	public static void reown(String prefix) {
		int keep = 0;
		for (String p : foreign) {
			if (!p.equals(prefix)) {
				keep++;
			}
		}
		if (keep == foreign.length) {
			return;
		}
		String[] shrunk = new String[keep];
		int i = 0;
		for (String p : foreign) {
			if (!p.equals(prefix)) {
				shrunk[i++] = p;
			}
		}
		foreign = shrunk;
	}

	public static boolean owns(String key) {
		for (String prefix : foreign) {
			if (key.equals(prefix) || key.startsWith(prefix + "/")) {
				return false;
			}
		}
		return true;
	}

	private static void requireOwned(String key) throws IOException {
		if (!owns(key)) {
			throw new IOException(key + " belongs to another realm in worker mode; ask it over the "
					+ "control channel instead of writing here");
		}
	}

	public static VirtualFileSystem fs() {
		return VirtualFileSystemProvider.getInstance();
	}

	public static String key(TPath path) {
		String s = path.toAbsolutePath().normalize().toString();
		return s.isEmpty() ? "/" : s;
	}

	public static VirtualFile at(TPath path) {
		return fs().getFile(key(path));
	}

	public static VirtualFile at(String key) {
		return fs().getFile(key);
	}

	public static boolean exists(VirtualFile file) {
		return file.isFile() || file.isDirectory();
	}

	public static VirtualFile requireExisting(TPath path) throws IOException {
		VirtualFile file = at(path);
		if (!exists(file)) {
			throw new TNoSuchFileException(path.toString());
		}
		return file;
	}

	public static VirtualFile requireDirectory(TPath path) throws IOException {
		VirtualFile file = requireExisting(path);
		if (!file.isDirectory()) {
			throw new TNotDirectoryException(path.toString());
		}
		return file;
	}

	public static String parentKey(String key) {
		int idx = key.lastIndexOf('/');
		return idx <= 0 ? "/" : key.substring(0, idx);
	}

	public static String nameOf(String key) {
		return key.substring(key.lastIndexOf('/') + 1);
	}

	public static VirtualFile parentOf(TPath path) throws IOException {
		requireOwned(key(path));
		String parent = parentKey(key(path));
		VirtualFile dir = at(parent);
		if (!dir.isDirectory()) {
			throw new TNoSuchFileException(parent);
		}
		return dir;
	}

	public static String[] children(VirtualFile dir) {
		String[] names = dir.listFiles();
		if (names == null) {
			return new String[0];
		}
		Arrays.sort(names);
		return names;
	}

	public static boolean has(Object[] options, Object wanted) {
		if (options == null) {
			return false;
		}
		for (Object o : options) {
			if (o == wanted) {
				return true;
			}
		}
		return false;
	}

	public static VirtualFileAccessor open(TPath path, boolean read, boolean write, boolean create,
			boolean createNew, boolean append, boolean truncate) throws IOException {
		String k = key(path);
		if (write) {
			requireOwned(k);
		}
		VirtualFile file = at(k);
		boolean directory;
		try {
			directory = file.isDirectory();
		} catch (UnsupportedOperationException error) {
			throw new TNotDirectoryException(parentKey(k));
		}
		if (directory) {
			throw new IOException("is a directory: " + path);
		}
		if (!file.isFile()) {
			if (!write || !(create || createNew)) {
				throw new TNoSuchFileException(path.toString());
			}
			VirtualFile dir = at(parentKey(k));
			if (!dir.isDirectory()) {
				throw new TNoSuchFileException(parentKey(k));
			}
			dir.createFile(nameOf(k));
			file = at(k);
		} else if (createNew) {
			throw new TFileAlreadyExistsException(path.toString());
		}
		VirtualFileAccessor accessor = file.createAccessor(read, write, write);
		if (accessor == null) {
			throw new IOException("cannot open " + path);
		}
		if (write && truncate && !append) {
			accessor.resize(0);
		}
		if (!append) {
			accessor.seek(0);
		}
		return accessor;
	}

	public static byte[] readAll(TPath path) throws IOException {
		VirtualFile file = requireExisting(path);
		if (!file.isFile()) {
			throw new IOException("is a directory: " + path);
		}
		VirtualFileAccessor accessor = open(path, true, false, false, false, false, false);
		try {
			byte[] out = new byte[accessor.size()];
			int off = 0;
			while (off < out.length) {
				int n = accessor.read(out, off, out.length - off);
				if (n <= 0) {
					break;
				}
				off += n;
			}
			return off == out.length ? out : Arrays.copyOf(out, off);
		} finally {
			accessor.close();
		}
	}

	public static void writeAll(TPath path, byte[] bytes, boolean append, boolean createNew) throws IOException {
		VirtualFileAccessor accessor = open(path, false, true, true, createNew, append, !append);
		try {
			accessor.write(bytes, 0, bytes.length);
		} finally {
			accessor.close();
		}
	}

	public static TPath createTemp(TPath dir, String prefix, String suffix, boolean directory) throws IOException {
		VirtualFile parent = requireDirectory(dir);
		String pre = prefix == null ? "" : prefix;
		String suf = suffix == null ? ".tmp" : suffix;
		for (int i = 0; i < 10000; i++) {
			String name = pre + (tempCounter++) + suf;
			boolean made = directory ? parent.createDirectory(name) : parent.createFile(name);
			if (made) {
				return dir.resolve(name);
			}
		}
		throw new IOException("could not create a temporary entry in " + dir);
	}

	public static boolean tryLock(String key) {
		if (!owns(key) || LOCKS.containsKey(key)) {
			return false;
		}
		LOCKS.put(key, null);
		return true;
	}

	public static void unlock(String key) {
		LOCKS.remove(key);
	}

	public static TBasicFileAttributes attributes(VirtualFile file) {
		return new Attributes(file);
	}

	static final class Attributes implements TBasicFileAttributes {
		private final boolean directory;
		private final long size;
		private final TFileTime modified;

		Attributes(VirtualFile file) {
			this.directory = file.isDirectory();
			this.size = file.length();
			this.modified = TFileTime.fromMillis(file.lastModified());
		}

		@Override
		public TFileTime creationTime() {
			return modified;
		}

		@Override
		public TFileTime lastAccessTime() {
			return modified;
		}

		@Override
		public TFileTime lastModifiedTime() {
			return modified;
		}

		@Override
		public boolean isDirectory() {
			return directory;
		}

		@Override
		public boolean isRegularFile() {
			return !directory;
		}

		@Override
		public boolean isSymbolicLink() {
			return false;
		}

		@Override
		public boolean isOther() {
			return false;
		}

		@Override
		public long size() {
			return size;
		}

		@Override
		public Object fileKey() {
			return null;
		}
	}
}
