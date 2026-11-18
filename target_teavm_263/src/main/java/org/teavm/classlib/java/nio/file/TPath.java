// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.IOException;
import java.net.URI;
import org.teavm.classlib.java.io.TFile;

public interface TPath extends Comparable<TPath>, Iterable<TPath>, TWatchable {
	String toString();
	TPath resolve(String other);
	TPath resolve(String first, String... more);
	TPath resolve(TPath other);
	TPath resolveSibling(String other);
	TPath resolveSibling(TPath other);
	TPath relativize(TPath other);
	TPath normalize();
	TPath getParent();
	TPath getFileName();
	TPath toAbsolutePath();
	boolean isAbsolute();
	boolean startsWith(TPath other);
	boolean startsWith(String other);
	int getNameCount();
	int compareTo(TPath other);
	TPath getName(int index);
	TFile toFile();
	URI toUri();
	TFileSystem getFileSystem();
	TPath toRealPath(TLinkOption... options);

	default boolean endsWith(TPath other) {
		String self = toString();
		String suffix = other.toString();
		return self.equals(suffix) || self.endsWith("/" + suffix);
	}

	default boolean endsWith(String other) {
		return endsWith(TPath.of(other));
	}

	@Override
	default TWatchKey register(TWatchService watcher, TWatchEvent.Kind<?>... events) throws IOException {
		return TWatchKey.NONE;
	}

	static TPath of(String first, String... more) {
		TPath p = new TSimplePath(first);
		for (String m : more) {
			p = p.resolve(m);
		}
		return p;
	}
}
