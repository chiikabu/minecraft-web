// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

public final class Credits {
	public static final String AUTHOR = "chiikabu";
	public static final String GITHUB = "github.com/chiikabu";
	public static final String PROJECT = "minecraft-web";
	public static final String LINE = PROJECT + " by " + AUTHOR;
	public static final String URL = "https://" + GITHUB;
	public static final String BUTTON = "Credits";

	private Credits() {
	}

	public static int key() {
		String text = AUTHOR + '\n' + GITHUB + '\n' + PROJECT;
		int hash = 0x811C9DC5;
		for (int i = 0; i < text.length(); i++) {
			hash = (hash ^ text.charAt(i)) * 0x01000193;
		}
		return hash;
	}

	public static String open(final int... sealed) {
		int state = key();
		char[] out = new char[sealed.length];
		for (int i = 0; i < sealed.length; i++) {
			state = state * 1103515245 + 12345;
			out[i] = (char) ((sealed[i] ^ (state >>> 16)) & 0xFFFF);
		}
		return new String(out);
	}

	public static int n(final int sealed) {
		return sealed ^ key();
	}

	public static String banner() {
		return LINE + " (" + URL + ")";
	}
}
