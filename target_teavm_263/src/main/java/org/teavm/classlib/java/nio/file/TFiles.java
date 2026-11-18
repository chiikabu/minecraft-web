// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.teavm.classlib.java.nio.file.attribute.TBasicFileAttributes;
import org.teavm.classlib.java.nio.file.attribute.TFileAttribute;
import org.teavm.classlib.java.nio.file.attribute.TFileAttributeView;
import org.teavm.classlib.java.nio.file.attribute.TFileTime;
import org.teavm.classlib.java.nio.file.attribute.TPosixFilePermission;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;

public final class TFiles {
	private TFiles() {
	}

	private static final long FAKE_SPACE = 4L * 1024L * 1024L * 1024L;

	public static TFileStore getFileStore(TPath path) throws IOException {
		TVfs.requireExisting(path);
		return new TFileStore() {
			@Override
			public long getUsableSpace() {
				return FAKE_SPACE;
			}

			@Override
			public long getTotalSpace() {
				return FAKE_SPACE;
			}

			@Override
			public String name() {
				return "memory";
			}
		};
	}

	public static boolean exists(TPath path, TLinkOption... options) {
		return TVfs.exists(TVfs.at(path));
	}

	public static boolean notExists(TPath path, TLinkOption... options) {
		return !exists(path, options);
	}

	public static boolean isDirectory(TPath path, TLinkOption... options) {
		return TVfs.at(path).isDirectory();
	}

	public static boolean isRegularFile(TPath path, TLinkOption... options) {
		return TVfs.at(path).isFile();
	}

	public static boolean isReadable(TPath path) {
		return TVfs.exists(TVfs.at(path));
	}

	public static boolean isWritable(TPath path) {
		return TVfs.at(path).canWrite();
	}

	public static boolean isSymbolicLink(TPath path) {
		return false;
	}

	public static TPath readSymbolicLink(TPath path) throws IOException {
		throw new IOException("not a symbolic link: " + path);
	}

	public static TPath createDirectory(TPath path, TFileAttribute<?>... attrs) throws IOException {
		String key = TVfs.key(path);
		if (TVfs.exists(TVfs.at(key))) {
			throw new TFileAlreadyExistsException(path.toString());
		}
		TVfs.parentOf(path).createDirectory(TVfs.nameOf(key));
		return path;
	}

	public static TPath createDirectories(TPath path, TFileAttribute<?>... attrs) throws IOException {
		String key = TVfs.key(path);
		StringBuilder built = new StringBuilder();
		for (String part : key.split("/")) {
			if (part.isEmpty()) {
				continue;
			}
			String parent = built.length() == 0 ? "/" : built.toString();
			built.append('/').append(part);
			VirtualFile here = TVfs.at(built.toString());
			if (here.isDirectory()) {
				continue;
			}
			if (here.isFile()) {
				throw new TFileAlreadyExistsException(built.toString());
			}
			TVfs.at(parent).createDirectory(part);
		}
		return path;
	}

	public static TPath createFile(TPath path, TFileAttribute<?>... attrs) throws IOException {
		TVfs.open(path, false, true, false, true, false, false).close();
		return path;
	}

	public static TPath createLink(TPath link, TPath existing) throws IOException {
		throw new IOException("hard links unavailable in browser: " + link);
	}

	public static TPath createTempFile(TPath dir, String prefix, String suffix, TFileAttribute<?>... attrs)
			throws IOException {
		return TVfs.createTemp(dir, prefix, suffix, false);
	}

	public static TPath createTempDirectory(String prefix, TFileAttribute<?>... attrs) throws IOException {
		TPath tmp = TPath.of("/tmp");
		createDirectories(tmp);
		return TVfs.createTemp(tmp, prefix, "", true);
	}

	public static TPath createTempDirectory(TPath dir, String prefix, TFileAttribute<?>... attrs) throws IOException {
		return TVfs.createTemp(dir, prefix, "", true);
	}

	public static InputStream newInputStream(TPath path, TOpenOption... options) throws IOException {
		return new java.io.ByteArrayInputStream(TVfs.readAll(path));
	}

	public static OutputStream newOutputStream(TPath path, TOpenOption... options) throws IOException {
		boolean append = TVfs.has(options, TStandardOpenOption.APPEND);
		boolean createNew = TVfs.has(options, TStandardOpenOption.CREATE_NEW);
		final VirtualFileAccessor accessor = TVfs.open(path, false, true, true, createNew, append, !append);
		return new OutputStream() {
			private final byte[] one = new byte[1];

			@Override
			public void write(int b) throws IOException {
				one[0] = (byte)b;
				accessor.write(one, 0, 1);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				accessor.write(b, off, len);
			}

			@Override
			public void flush() throws IOException {
				accessor.flush();
			}

			@Override
			public void close() throws IOException {
				accessor.close();
			}
		};
	}

	public static java.io.BufferedReader newBufferedReader(TPath path) throws IOException {
		return newBufferedReader(path, StandardCharsets.UTF_8);
	}

	public static java.io.BufferedReader newBufferedReader(TPath path, Charset charset) throws IOException {
		return new java.io.BufferedReader(new java.io.InputStreamReader(newInputStream(path), charset));
	}

	public static java.io.BufferedWriter newBufferedWriter(TPath path, TOpenOption... options) throws IOException {
		return newBufferedWriter(path, StandardCharsets.UTF_8, options);
	}

	public static java.io.BufferedWriter newBufferedWriter(TPath path, Charset charset, TOpenOption... options)
			throws IOException {
		return new java.io.BufferedWriter(new java.io.OutputStreamWriter(newOutputStream(path, options), charset));
	}

	public static void delete(TPath path) throws IOException {
		VirtualFile file = TVfs.requireExisting(path);
		if (!file.delete()) {
			throw new IOException("directory not empty: " + path);
		}
	}

	public static boolean deleteIfExists(TPath path) throws IOException {
		VirtualFile file = TVfs.at(path);
		if (!TVfs.exists(file)) {
			return false;
		}
		if (!file.delete()) {
			throw new IOException("directory not empty: " + path);
		}
		return true;
	}

	public static TPath move(TPath source, TPath target, TCopyOption... options) throws IOException {
		VirtualFile from = TVfs.requireExisting(source);
		String targetKey = TVfs.key(target);
		if (TVfs.key(source).equals(targetKey)) {
			return target;
		}
		if (TVfs.exists(TVfs.at(targetKey))) {
			if (!TVfs.has(options, TStandardCopyOption.REPLACE_EXISTING)) {
				throw new TFileAlreadyExistsException(target.toString());
			}
			deleteIfExists(target);
		}
		if (!TVfs.parentOf(target).adopt(from, TVfs.nameOf(targetKey))) {
			throw new IOException("could not move " + source + " to " + target);
		}
		return target;
	}

	public static TPath copy(TPath source, TPath target, TCopyOption... options) throws IOException {
		VirtualFile from = TVfs.requireExisting(source);
		if (from.isDirectory()) {
			createDirectories(target);
			return target;
		}
		if (TVfs.exists(TVfs.at(target)) && !TVfs.has(options, TStandardCopyOption.REPLACE_EXISTING)) {
			throw new TFileAlreadyExistsException(target.toString());
		}
		TVfs.writeAll(target, TVfs.readAll(source), false, false);
		return target;
	}

	public static long copy(InputStream in, TPath target, TCopyOption... options) throws IOException {
		if (TVfs.exists(TVfs.at(target)) && !TVfs.has(options, TStandardCopyOption.REPLACE_EXISTING)) {
			throw new TFileAlreadyExistsException(target.toString());
		}
		OutputStream out = newOutputStream(target);
		long total = 0;
		try {
			byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) > 0) {
				out.write(buffer, 0, n);
				total += n;
			}
		} finally {
			out.close();
		}
		return total;
	}

	public static Stream<TPath> find(TPath start, int maxDepth,
			java.util.function.BiPredicate<TPath, ?> matcher, TFileVisitOption... options) throws IOException {
		return Stream.empty();
	}

	public static byte[] readAllBytes(TPath path) throws IOException {
		return TVfs.readAll(path);
	}

	public static String readString(TPath path) throws IOException {
		return readString(path, StandardCharsets.UTF_8);
	}

	public static String readString(TPath path, Charset charset) throws IOException {
		return new String(TVfs.readAll(path), charset);
	}

	public static List<String> readAllLines(TPath path) throws IOException {
		return readAllLines(path, StandardCharsets.UTF_8);
	}

	public static List<String> readAllLines(TPath path, Charset charset) throws IOException {
		List<String> lines = new ArrayList<>();
		java.io.BufferedReader reader = newBufferedReader(path, charset);
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				lines.add(line);
			}
		} finally {
			reader.close();
		}
		return lines;
	}

	public static TPath write(TPath path, byte[] bytes, TOpenOption... options) throws IOException {
		TVfs.writeAll(path, bytes, TVfs.has(options, TStandardOpenOption.APPEND),
				TVfs.has(options, TStandardOpenOption.CREATE_NEW));
		return path;
	}

	public static TPath writeString(TPath path, CharSequence contents, TOpenOption... options) throws IOException {
		return writeString(path, contents, StandardCharsets.UTF_8, options);
	}

	public static TPath writeString(TPath path, CharSequence contents, Charset charset, TOpenOption... options)
			throws IOException {
		return write(path, contents.toString().getBytes(charset), options);
	}

	public static long size(TPath path) throws IOException {
		return TVfs.requireExisting(path).length();
	}

	public static TFileTime getLastModifiedTime(TPath path, TLinkOption... options) throws IOException {
		return TFileTime.fromMillis(TVfs.requireExisting(path).lastModified());
	}

	public static TPath setLastModifiedTime(TPath path, TFileTime time) throws IOException {
		TVfs.requireExisting(path).setLastModified(time.toMillis());
		return path;
	}

	@SuppressWarnings("unchecked")
	public static <A extends TBasicFileAttributes> A readAttributes(TPath path, Class<A> type, TLinkOption... options)
			throws IOException {
		return (A)TVfs.attributes(TVfs.requireExisting(path));
	}

	public static <V extends TFileAttributeView> V getFileAttributeView(TPath path, Class<V> type,
			TLinkOption... options) {
		return null;
	}

	public static Set<TPosixFilePermission> getPosixFilePermissions(TPath path, TLinkOption... options)
			throws IOException {
		TVfs.requireExisting(path);
		return Collections.emptySet();
	}

	public static TPath setPosixFilePermissions(TPath path, Set<TPosixFilePermission> perms) throws IOException {
		TVfs.requireExisting(path);
		return path;
	}

	public static Stream<TPath> list(TPath dir) throws IOException {
		VirtualFile file = TVfs.requireDirectory(dir);
		List<TPath> out = new ArrayList<>();
		for (String name : TVfs.children(file)) {
			out.add(dir.resolve(name));
		}
		return out.stream();
	}

	public static TDirectoryStream<TPath> newDirectoryStream(TPath dir, String glob) throws IOException {
		return newDirectoryStream(dir);
	}

	public static TDirectoryStream<TPath> newDirectoryStream(TPath dir) throws IOException {
		VirtualFile file = TVfs.requireDirectory(dir);
		final List<TPath> entries = new ArrayList<>();
		for (String name : TVfs.children(file)) {
			entries.add(dir.resolve(name));
		}
		return new TDirectoryStream<TPath>() {
			@Override
			public Iterator<TPath> iterator() {
				return entries.iterator();
			}

			@Override
			public void close() {
			}
		};
	}

	public static Stream<TPath> walk(TPath start, TFileVisitOption... options) throws IOException {
		List<TPath> out = new ArrayList<>();
		collect(start, out);
		return out.stream();
	}

	private static void collect(TPath path, List<TPath> out) {
		VirtualFile file = TVfs.at(path);
		if (!TVfs.exists(file)) {
			return;
		}
		out.add(path);
		if (file.isDirectory()) {
			for (String name : TVfs.children(file)) {
				collect(path.resolve(name), out);
			}
		}
	}

	public static TPath walkFileTree(TPath start, TFileVisitor<? super TPath> visitor) throws IOException {
		visit(start, visitor);
		return start;
	}

	private static TFileVisitResult visit(TPath path, TFileVisitor<? super TPath> visitor) throws IOException {
		VirtualFile file = TVfs.at(path);
		if (!TVfs.exists(file)) {
			return visitor.visitFileFailed(path, new TNoSuchFileException(path.toString()));
		}
		if (!file.isDirectory()) {
			return visitor.visitFile(path, TVfs.attributes(file));
		}
		TFileVisitResult result = visitor.preVisitDirectory(path, TVfs.attributes(file));
		if (result == TFileVisitResult.TERMINATE || result == TFileVisitResult.SKIP_SIBLINGS) {
			return result;
		}
		if (result == TFileVisitResult.SKIP_SUBTREE) {
			return TFileVisitResult.CONTINUE;
		}
		for (String name : TVfs.children(file)) {
			TFileVisitResult child = visit(path.resolve(name), visitor);
			if (child == TFileVisitResult.TERMINATE) {
				return child;
			}
			if (child == TFileVisitResult.SKIP_SIBLINGS) {
				break;
			}
		}
		return visitor.postVisitDirectory(path, null);
	}
}
