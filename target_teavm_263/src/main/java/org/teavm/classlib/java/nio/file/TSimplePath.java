// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.teavm.classlib.java.io.TFile;

public final class TSimplePath implements TPath {
	private final String path;

	public TSimplePath(String path) {
		this.path = clean(path);
	}

	private static String clean(String p) {
		String out = p.replace('\\', '/');
		while (out.length() > 1 && out.endsWith("/")) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	private List<String> names() {
		List<String> out = new ArrayList<>();
		for (String part : path.split("/")) {
			if (!part.isEmpty()) {
				out.add(part);
			}
		}
		return out;
	}

	private static TPath fromNames(List<String> names, boolean absolute) {
		String joined = String.join("/", names);
		return new TSimplePath(absolute ? "/" + joined : joined);
	}

	@Override
	public String toString() {
		return path;
	}

	@Override
	public TPath resolve(String other) {
		if (other == null || other.isEmpty()) {
			return this;
		}
		if (other.startsWith("/")) {
			return new TSimplePath(other);
		}
		return new TSimplePath(path.isEmpty() || path.equals("/") ? path + other : path + "/" + other);
	}

	@Override
	public TPath resolve(String first, String... more) {
		TPath p = resolve(first);
		for (String m : more) {
			p = p.resolve(m);
		}
		return p;
	}

	@Override
	public TPath resolve(TPath other) {
		return resolve(other.toString());
	}

	@Override
	public TPath resolveSibling(String other) {
		TPath parent = getParent();
		return parent == null ? new TSimplePath(other) : parent.resolve(other);
	}

	@Override
	public TPath resolveSibling(TPath other) {
		return resolveSibling(other.toString());
	}

	@Override
	public TPath relativize(TPath other) {
		if (isAbsolute() != other.isAbsolute()) {
			throw new IllegalArgumentException("'other' is different type of Path");
		}
		List<String> mine = names();
		List<String> theirs = new TSimplePath(other.toString()).names();
		int common = 0;
		while (common < mine.size() && common < theirs.size() && mine.get(common).equals(theirs.get(common))) {
			common++;
		}
		List<String> out = new ArrayList<>();
		for (int i = common; i < mine.size(); i++) {
			out.add("..");
		}
		out.addAll(theirs.subList(common, theirs.size()));
		return fromNames(out, false);
	}

	@Override
	public TPath normalize() {
		List<String> out = new ArrayList<>();
		for (String part : names()) {
			if (part.equals(".")) {
				continue;
			}
			if (part.equals("..") && !out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
				out.remove(out.size() - 1);
			} else {
				out.add(part);
			}
		}
		return fromNames(out, isAbsolute());
	}

	@Override
	public TPath getParent() {
		int idx = path.lastIndexOf('/');
		if (idx <= 0) {
			return null;
		}
		return new TSimplePath(path.substring(0, idx));
	}

	@Override
	public TPath getFileName() {
		int idx = path.lastIndexOf('/');
		return idx < 0 ? this : new TSimplePath(path.substring(idx + 1));
	}

	@Override
	public TPath toAbsolutePath() {
		return isAbsolute() ? this : new TSimplePath("/" + path);
	}

	@Override
	public boolean isAbsolute() {
		return path.startsWith("/");
	}

	@Override
	public boolean startsWith(TPath other) {
		return startsWith(other.toString());
	}

	@Override
	public boolean startsWith(String other) {
		String prefix = clean(other);
		return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
	}

	@Override
	public int getNameCount() {
		return names().size();
	}

	@Override
	public TPath getName(int index) {
		return new TSimplePath(names().get(index));
	}

	@Override
	public Iterator<TPath> iterator() {
		List<TPath> parts = new ArrayList<>();
		for (String n : names()) {
			parts.add(new TSimplePath(n));
		}
		return parts.iterator();
	}

	@Override
	public TFile toFile() {
		return new TFile(path);
	}

	@Override
	public URI toUri() {
		try {
			return new URI("file://" + toAbsolutePath());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(path, e);
		}
	}

	@Override
	public TFileSystem getFileSystem() {
		return TFileSystem.DEFAULT;
	}

	@Override
	public TPath toRealPath(TLinkOption... options) {
		return toAbsolutePath();
	}

	@Override
	public int compareTo(TPath o) {
		return path.compareTo(o.toString());
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof TSimplePath && ((TSimplePath)o).path.equals(path);
	}

	@Override
	public int hashCode() {
		return path.hashCode();
	}
}
