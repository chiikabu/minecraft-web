// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PackExpression {
	public interface Scope {
		float[] get(String name);
	}

	private abstract static class Node {
		abstract float[] eval(Context ctx);
	}

	public static final class Context {
		public Scope scope;
		public float frameTime;
		final Map<String, float[]> smoothState = new HashMap<>();
	}

	private final Node root;
	private final String text;
	private static int smoothCounter;

	private PackExpression(final String text, final Node root) {
		this.text = text;
		this.root = root;
	}

	public String text() {
		return this.text;
	}

	public float[] eval(final Context ctx) {
		try {
			return this.root.eval(ctx);
		} catch (RuntimeException e) {
			return new float[] {0.0F};
		}
	}

	public static PackExpression parse(final String text) {
		Parser p = new Parser(text);
		Node n = p.ternaryOrOr();
		if (p.pos < p.tokens.size()) {
			throw new IllegalArgumentException("unexpected '" + p.tokens.get(p.pos) + "' in " + text);
		}
		return new PackExpression(text, n);
	}

	private static final class Parser {
		final List<String> tokens = new ArrayList<>();
		int pos;

		Parser(final String text) {
			List<String> raw = GlslPreprocessor.tokenize(text);
			for (int i = 0; i < raw.size(); i++) {
				String t = raw.get(i);
				if (t.trim().isEmpty()) {
					continue;
				}
				if (i + 1 < raw.size()) {
					String two = t + raw.get(i + 1);
					if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
						this.tokens.add(two);
						i++;
						continue;
					}
				}
				this.tokens.add(t);
			}
		}

		String peek() {
			return this.pos < this.tokens.size() ? this.tokens.get(this.pos) : "";
		}

		boolean eat(final String t) {
			if (this.peek().equals(t)) {
				this.pos++;
				return true;
			}
			return false;
		}

		Node ternaryOrOr() {
			Node c = this.binary(0);
			if (this.eat("?")) {
				Node a = this.ternaryOrOr();
				this.eat(":");
				Node b = this.ternaryOrOr();
				return call("if", List.of(c, a, b), this);
			}
			return c;
		}

		static final String[][] LEVELS = {{"||"}, {"&&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"+", "-"}, {"*", "/", "%"}};

		Node binary(final int level) {
			if (level >= LEVELS.length) {
				return this.unary();
			}
			Node left = this.binary(level + 1);
			while (true) {
				String op = null;
				for (String o : LEVELS[level]) {
					if (this.peek().equals(o)) {
						op = o;
						break;
					}
				}
				if (op == null) {
					return left;
				}
				this.pos++;
				Node right = this.binary(level + 1);
				left = new BinOp(op, left, right);
			}
		}

		Node unary() {
			if (this.eat("-")) {
				Node inner = this.unary();
				return new BinOp("-", new Const(0.0F), inner);
			}
			if (this.eat("+")) {
				return this.unary();
			}
			if (this.eat("!")) {
				Node inner = this.unary();
				return new Not(inner);
			}
			return this.postfix(this.primary());
		}

		Node postfix(Node n) {
			String t = this.peek();
			if (t.startsWith(".") && t.length() > 1 && Character.isDigit(t.charAt(1))) {
				String[] parts = t.substring(1).split("[.]");
				if (parts.length == 2) {
					this.pos++;
					int index = Integer.parseInt(parts[0]) * 4 + Integer.parseInt(parts[1]);
					return new Swizzle(n, index);
				}
			}
			while (this.peek().equals(".")) {
				this.pos++;
				String swizzle = this.peek();
				this.pos++;
				n = new Swizzle(n, swizzle);
			}
			return n;
		}

		Node primary() {
			String t = this.peek();
			if (t.isEmpty()) {
				throw new IllegalArgumentException("unexpected end");
			}
			this.pos++;
			if (t.equals("(")) {
				Node n = this.ternaryOrOr();
				this.eat(")");
				return n;
			}
			char c = t.charAt(0);
			if (Character.isDigit(c) || c == '.') {
				return new Const((float) GlslPreprocessor.parseNumber(t));
			}
			if (GlslPreprocessor.isIdentStart(c)) {
				if (this.eat("(")) {
					List<Node> args = new ArrayList<>();
					if (!this.eat(")")) {
						do {
							args.add(this.ternaryOrOr());
						} while (this.eat(","));
						this.eat(")");
					}
					return call(t, args, this);
				}
				switch (t) {
					case "true": return new Const(1.0F);
					case "false": return new Const(0.0F);
					case "pi": return new Const((float) Math.PI);
					default: return new Var(t);
				}
			}
			throw new IllegalArgumentException("unexpected '" + t + "'");
		}
	}

	private static Node call(final String name, final List<Node> args, final Parser parser) {
		if (name.equals("smooth")) {
			int id = smoothCounter++;
			return new Smooth(id, args);
		}
		return new Call(name, args);
	}

	private static final class Const extends Node {
		final float[] v;

		Const(final float v) {
			this.v = new float[] {v};
		}

		@Override
		float[] eval(final Context ctx) {
			return this.v;
		}
	}

	private static final class Var extends Node {
		final String name;

		Var(final String name) {
			this.name = name;
		}

		@Override
		float[] eval(final Context ctx) {
			float[] v = ctx.scope.get(this.name);
			return v == null ? new float[] {0.0F} : v;
		}
	}

	private static final class Swizzle extends Node {
		final Node inner;
		final int[] idx;

		Swizzle(final Node inner, final int element) {
			this.inner = inner;
			this.idx = new int[] {element};
		}

		Swizzle(final Node inner, final String swizzle) {
			this.inner = inner;
			this.idx = new int[swizzle.length()];
			for (int i = 0; i < swizzle.length(); i++) {
				char c = swizzle.charAt(i);
				this.idx[i] = "xyzw".indexOf(c) >= 0 ? "xyzw".indexOf(c) : "rgba".indexOf(c) >= 0 ? "rgba".indexOf(c) : Math.max(0, "stpq".indexOf(c));
			}
		}

		@Override
		float[] eval(final Context ctx) {
			float[] v = this.inner.eval(ctx);
			float[] r = new float[this.idx.length];
			for (int i = 0; i < r.length; i++) {
				r[i] = this.idx[i] < v.length ? v[this.idx[i]] : 0.0F;
			}
			return r;
		}
	}

	private static final class Not extends Node {
		final Node inner;

		Not(final Node inner) {
			this.inner = inner;
		}

		@Override
		float[] eval(final Context ctx) {
			return new float[] {this.inner.eval(ctx)[0] != 0.0F ? 0.0F : 1.0F};
		}
	}

	private static final class BinOp extends Node {
		final String op;
		final Node a;
		final Node b;

		BinOp(final String op, final Node a, final Node b) {
			this.op = op;
			this.a = a;
			this.b = b;
		}

		@Override
		float[] eval(final Context ctx) {
			float[] x = this.a.eval(ctx);
			if (this.op.equals("&&") && x[0] == 0.0F) {
				return new float[] {0.0F};
			}
			if (this.op.equals("||") && x[0] != 0.0F) {
				return new float[] {1.0F};
			}
			float[] y = this.b.eval(ctx);
			int n = Math.max(x.length, y.length);
			if (this.op.equals("==") || this.op.equals("!=") || this.op.equals("<") || this.op.equals(">") || this.op.equals("<=")
				|| this.op.equals(">=") || this.op.equals("&&") || this.op.equals("||")) {
				float l = x[0];
				float r = y[0];
				boolean v;
				switch (this.op) {
					case "==": v = l == r; break;
					case "!=": v = l != r; break;
					case "<": v = l < r; break;
					case ">": v = l > r; break;
					case "<=": v = l <= r; break;
					case ">=": v = l >= r; break;
					case "&&": v = l != 0.0F && r != 0.0F; break;
					default: v = l != 0.0F || r != 0.0F; break;
				}
				return new float[] {v ? 1.0F : 0.0F};
			}
			float[] out = new float[n];
			for (int i = 0; i < n; i++) {
				float l = x[Math.min(i, x.length - 1)];
				float r = y[Math.min(i, y.length - 1)];
				switch (this.op) {
					case "+": out[i] = l + r; break;
					case "-": out[i] = l - r; break;
					case "*": out[i] = l * r; break;
					case "/": out[i] = r == 0.0F ? 0.0F : l / r; break;
					default: out[i] = r == 0.0F ? 0.0F : l % r; break;
				}
			}
			return out;
		}
	}

	private static final class Smooth extends Node {
		final String key;
		final List<Node> args;

		Smooth(final int id, final List<Node> args) {
			this.key = "s" + id;
			this.args = args;
		}

		@Override
		float[] eval(final Context ctx) {
			Node valueNode;
			float up = 1.0F;
			float down;
			int n = this.args.size();
			if (n >= 4) {
				valueNode = this.args.get(1);
				up = this.args.get(2).eval(ctx)[0];
				down = this.args.get(3).eval(ctx)[0];
			} else if (n == 3) {
				valueNode = this.args.get(0);
				up = this.args.get(1).eval(ctx)[0];
				down = this.args.get(2).eval(ctx)[0];
			} else if (n == 2) {
				valueNode = this.args.get(0);
				up = this.args.get(1).eval(ctx)[0];
				down = up;
			} else if (n == 1) {
				valueNode = this.args.get(0);
				down = up;
			} else {
				return new float[] {0.0F};
			}
			float[] target = valueNode.eval(ctx);
			float[] state = ctx.smoothState.get(this.key);
			if (state == null || state.length != target.length) {
				state = target.clone();
				ctx.smoothState.put(this.key, state);
				return state.clone();
			}
			for (int i = 0; i < state.length; i++) {
				float t = target[i] > state[i] ? up : down;
				float k = t <= 0.0F ? 1.0F : (float) (1.0 - Math.exp(-ctx.frameTime * Math.log(100.0) / t));
				state[i] += (target[i] - state[i]) * k;
			}
			return state.clone();
		}
	}

	private static final class Call extends Node {
		final String name;
		final List<Node> args;

		Call(final String name, final List<Node> args) {
			this.name = name;
			this.args = args;
		}

		private float a(final Context ctx, final int i) {
			return i < this.args.size() ? this.args.get(i).eval(ctx)[0] : 0.0F;
		}

		@Override
		float[] eval(final Context ctx) {
			switch (this.name) {
				case "if": {
					int i = 0;
					for (; i + 1 < this.args.size(); i += 2) {
						if (this.args.get(i).eval(ctx)[0] != 0.0F) {
							return this.args.get(i + 1).eval(ctx);
						}
					}
					return i < this.args.size() ? this.args.get(i).eval(ctx) : new float[] {0.0F};
				}
				case "vec2":
				case "vec3":
				case "vec4": {
					int n = this.name.charAt(3) - '0';
					float[] out = new float[n];
					int k = 0;
					for (Node arg : this.args) {
						for (float f : arg.eval(ctx)) {
							if (k < n) {
								out[k++] = f;
							}
						}
					}
					if (k == 1) {
						for (int i = 1; i < n; i++) {
							out[i] = out[0];
						}
					}
					return out;
				}
				case "in": {
					float v = this.a(ctx, 0);
					for (int i = 1; i < this.args.size(); i++) {
						if (this.a(ctx, i) == v) {
							return new float[] {1.0F};
						}
					}
					return new float[] {0.0F};
				}
				case "between": {
					float v = this.a(ctx, 0);
					return new float[] {v >= this.a(ctx, 1) && v <= this.a(ctx, 2) ? 1.0F : 0.0F};
				}
				case "equals":
					return new float[] {Math.abs(this.a(ctx, 0) - this.a(ctx, 1)) <= this.a(ctx, 2) ? 1.0F : 0.0F};
				case "clamp": return vec1(Math.max(this.a(ctx, 1), Math.min(this.a(ctx, 2), this.a(ctx, 0))));
				case "frac": { float v = this.a(ctx, 0); return vec1((float) (v - Math.floor(v))); }
				case "min": {
					float m = this.a(ctx, 0);
					for (int i = 1; i < this.args.size(); i++) {
						m = Math.min(m, this.a(ctx, i));
					}
					return vec1(m);
				}
				case "max": {
					float m = this.a(ctx, 0);
					for (int i = 1; i < this.args.size(); i++) {
						m = Math.max(m, this.a(ctx, i));
					}
					return vec1(m);
				}
				case "abs": return vec1(Math.abs(this.a(ctx, 0)));
				case "sin": return vec1((float) Math.sin(this.a(ctx, 0)));
				case "cos": return vec1((float) Math.cos(this.a(ctx, 0)));
				case "tan": return vec1((float) Math.tan(this.a(ctx, 0)));
				case "asin": return vec1((float) Math.asin(this.a(ctx, 0)));
				case "acos": return vec1((float) Math.acos(this.a(ctx, 0)));
				case "atan": return this.args.size() > 1 ? vec1((float) Math.atan2(this.a(ctx, 0), this.a(ctx, 1))) : vec1((float) Math.atan(this.a(ctx, 0)));
				case "atan2": return vec1((float) Math.atan2(this.a(ctx, 0), this.a(ctx, 1)));
				case "torad": return vec1((float) Math.toRadians(this.a(ctx, 0)));
				case "todeg": return vec1((float) Math.toDegrees(this.a(ctx, 0)));
				case "pow": return vec1((float) Math.pow(this.a(ctx, 0), this.a(ctx, 1)));
				case "sqrt": return vec1((float) Math.sqrt(this.a(ctx, 0)));
				case "exp": return vec1((float) Math.exp(this.a(ctx, 0)));
				case "exp2": return vec1((float) Math.pow(2.0, this.a(ctx, 0)));
				case "exp10": return vec1((float) Math.pow(10.0, this.a(ctx, 0)));
				case "log": return vec1((float) Math.log(this.a(ctx, 0)));
				case "log2": return vec1((float) (Math.log(this.a(ctx, 0)) / Math.log(2.0)));
				case "log10": return vec1((float) Math.log10(this.a(ctx, 0)));
				case "floor": return vec1((float) Math.floor(this.a(ctx, 0)));
				case "ceil": return vec1((float) Math.ceil(this.a(ctx, 0)));
				case "round": return vec1((float) Math.round(this.a(ctx, 0)));
				case "signum": return vec1(Math.signum(this.a(ctx, 0)));
				case "fmod": {
					float x = this.a(ctx, 0);
					float y = this.a(ctx, 1);
					return vec1(y == 0.0F ? 0.0F : (float) (x - y * Math.floor(x / y)));
				}
				case "hypot": return vec1((float) Math.hypot(this.a(ctx, 0), this.a(ctx, 1)));
				case "random": return vec1((float) Math.random());
				case "defined": return vec1(this.a(ctx, 0) != 0.0F ? 1.0F : 0.0F);
				default: return vec1(0.0F);
			}
		}

		private static float[] vec1(final float v) {
			return new float[] {v};
		}
	}
}
