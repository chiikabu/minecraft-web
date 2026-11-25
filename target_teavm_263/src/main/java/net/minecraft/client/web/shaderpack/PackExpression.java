// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * The expression language of {@code shaders.properties}: custom uniforms and variables
 * ({@code uniform.float.x = ...}, {@code variable.vec3.y = ...}) and program switches
 * ({@code program.composite1.enabled = BLOOM && !DOF}).
 *
 * <p>Values are float scalars, vectors (2..4), or column-major square matrices (mat2=4, mat3=9,
 * mat4=16). Booleans are 0/1, ints are truncated by the caller. Supports arithmetic, bitwise,
 * comparison and logic operators, ternary {@code ?:}, {@code if()} chains, swizzles, matrix
 * bracket indexing ({@code mat[i]}, {@code mat[i][j]}, {@code mat.1.1}), matrix*vector and
 * matrix*matrix multiplication, constructors ({@code vec2..4}, {@code ivec2..4}, {@code bvec2..4},
 * {@code mat2..4}, {@code float}, {@code int}, {@code bool}), and the OptiFine/Iris function set.
 */
public final class PackExpression {
	/** Supplies named values; returns null for an unknown name. */
	public interface Scope {
		float[] get(String name);
	}

	private abstract static class Node {
		abstract float[] eval(Context ctx);

		boolean isMatrix() {
			return false;
		}
	}

	/** Per-evaluation state: the scope plus smoothing memory and the frame time. */
	public static final class Context {
		public Scope scope;
		public float frameTime;
		final Map<String, float[]> smoothState = new HashMap<>();
	}

	private final Node root;
	private final String text;
	private final Set<String> vars;
	/** Every smooth() call in every expression gets its own state slot in the shared Context. */
	private static int smoothCounter;

	private PackExpression(final String text, final Node root, final Set<String> vars) {
		this.text = text;
		this.root = root;
		this.vars = Collections.unmodifiableSet(vars);
	}

	public String text() {
		return this.text;
	}

	/** All variable names referenced by this expression. */
	public Set<String> referencedVars() {
		return this.vars;
	}

	public float[] eval(final Context ctx) {
		try {
			return this.root.eval(ctx);
		} catch (RuntimeException e) {
			return new float[] {0.0F};
		}
	}

	/** Parses; throws IllegalArgumentException on syntax errors. */
	public static PackExpression parse(final String text) {
		Parser p = new Parser(text);
		Node n = p.ternaryOrOr();
		if (p.pos < p.tokens.size()) {
			throw new IllegalArgumentException("unexpected '" + p.tokens.get(p.pos) + "' in " + text);
		}
		return new PackExpression(text, n, p.vars);
	}

	// ---- parser ----

	private static final class Parser {
		final List<String> tokens = new ArrayList<>();
		final Set<String> vars = new LinkedHashSet<>();
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
					if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
						|| two.equals("<=") || two.equals(">=") || two.equals("<<") || two.equals(">>")) {
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
				return call("if", List.of(c, a, b));
			}
			return c;
		}

		static final String[][] LEVELS = {
			{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}
		};

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
			if (this.eat("~")) {
				Node inner = this.unary();
				return new BitNot(inner);
			}
			return this.postfix(this.primary());
		}

		Node postfix(Node n) {
			while (true) {
				String t = this.peek();
				if (t.startsWith(".") && t.length() > 1 && Character.isDigit(t.charAt(1))) {
					// matrix element: gbufferProjection.1.1 (column, then row)
					String[] parts = t.substring(1).split("[.]");
					if (parts.length == 2) {
						this.pos++;
						n = new MatrixElement(n, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
						continue;
					}
				}
				if (t.equals(".")) {
					this.pos++;
					String swizzle = this.peek();
					this.pos++;
					n = new Swizzle(n, swizzle);
					continue;
				}
				if (t.equals("[")) {
					this.pos++;
					Node idx = this.ternaryOrOr();
					this.eat("]");
					n = new Index(n, idx);
					continue;
				}
				break;
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
					return call(t, args);
				}
				switch (t) {
					case "true": return new Const(1.0F);
					case "false": return new Const(0.0F);
					case "pi": return new Const((float) Math.PI);
					default:
						this.vars.add(t);
						return new Var(t);
				}
			}
			throw new IllegalArgumentException("unexpected '" + t + "'");
		}
	}

	private static Node call(final String name, final List<Node> args) {
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
			float[] v = ctx.scope == null ? null : ctx.scope.get(this.name);
			return v == null ? new float[] {0.0F} : v;
		}
	}

	private static final class MatrixElement extends Node {
		final Node inner;
		final int col;
		final int row;

		MatrixElement(final Node inner, final int col, final int row) {
			this.inner = inner;
			this.col = col;
			this.row = row;
		}

		@Override
		float[] eval(final Context ctx) {
			float[] v = this.inner.eval(ctx);
			int dim = v.length == 16 ? 4 : v.length == 9 ? 3 : v.length == 4 ? 2 : 4;
			int idx = this.col * dim + this.row;
			return new float[] {idx >= 0 && idx < v.length ? v[idx] : 0.0F};
		}
	}

	private static final class Index extends Node {
		final Node target;
		final Node index;

		Index(final Node target, final Node index) {
			this.target = target;
			this.index = index;
		}

		@Override
		float[] eval(final Context ctx) {
			float[] v = this.target.eval(ctx);
			int i = (int) this.index.eval(ctx)[0];
			if (v.length == 16) {
				if (i < 0 || i >= 4) {
					return new float[] {0, 0, 0, 0};
				}
				return new float[] {v[i * 4], v[i * 4 + 1], v[i * 4 + 2], v[i * 4 + 3]};
			}
			if (v.length == 9) {
				if (i < 0 || i >= 3) {
					return new float[] {0, 0, 0};
				}
				return new float[] {v[i * 3], v[i * 3 + 1], v[i * 3 + 2]};
			}
			if (v.length == 4 && this.target.isMatrix()) {
				if (i < 0 || i >= 2) {
					return new float[] {0, 0};
				}
				return new float[] {v[i * 2], v[i * 2 + 1]};
			}
			return new float[] {i >= 0 && i < v.length ? v[i] : 0.0F};
		}
	}

	private static final class Swizzle extends Node {
		final Node inner;
		final int[] idx;

		Swizzle(final Node inner, final String swizzle) {
			this.inner = inner;
			this.idx = new int[swizzle.length()];
			for (int i = 0; i < swizzle.length(); i++) {
				char c = swizzle.charAt(i);
				int xyzw = "xyzw".indexOf(c);
				int rgba = "rgba".indexOf(c);
				this.idx[i] = xyzw >= 0 ? xyzw : rgba >= 0 ? rgba : Math.max(0, "stpq".indexOf(c));
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
			float[] v = this.inner.eval(ctx);
			float[] out = new float[v.length];
			for (int i = 0; i < v.length; i++) {
				out[i] = v[i] != 0.0F ? 0.0F : 1.0F;
			}
			return out;
		}
	}

	private static final class BitNot extends Node {
		final Node inner;

		BitNot(final Node inner) {
			this.inner = inner;
		}

		@Override
		float[] eval(final Context ctx) {
			float[] v = this.inner.eval(ctx);
			float[] out = new float[v.length];
			for (int i = 0; i < v.length; i++) {
				out[i] = (float) (~(int) v[i]);
			}
			return out;
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
		boolean isMatrix() {
			if (this.op.equals("*")) {
				return this.a.isMatrix() && this.b.isMatrix();
			}
			if (this.op.equals("+") || this.op.equals("-") || this.op.equals("/")) {
				return this.a.isMatrix() || this.b.isMatrix();
			}
			return false;
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
			if (this.op.equals("==") || this.op.equals("!=") || this.op.equals("<") || this.op.equals(">")
				|| this.op.equals("<=") || this.op.equals(">=") || this.op.equals("&&") || this.op.equals("||")) {
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
			if (this.op.equals("*")) {
				float[] matProd = tryMatrixMultiply(x, y, this.a.isMatrix(), this.b.isMatrix());
				if (matProd != null) {
					return matProd;
				}
			}
			int n = Math.max(x.length, y.length);
			float[] out = new float[n];
			for (int i = 0; i < n; i++) {
				float l = x[Math.min(i, x.length - 1)];
				float r = y[Math.min(i, y.length - 1)];
				switch (this.op) {
					case "+": out[i] = l + r; break;
					case "-": out[i] = l - r; break;
					case "*": out[i] = l * r; break;
					case "/": out[i] = r == 0.0F ? 0.0F : l / r; break;
					case "%": out[i] = r == 0.0F ? 0.0F : l % r; break;
					case "&": out[i] = (float) ((int) l & (int) r); break;
					case "|": out[i] = (float) ((int) l | (int) r); break;
					case "^": out[i] = (float) ((int) l ^ (int) r); break;
					case "<<": out[i] = (float) ((int) l << (int) r); break;
					case ">>": out[i] = (float) ((int) l >> (int) r); break;
					default: out[i] = 0.0F; break;
				}
			}
			return out;
		}
	}

	private static float[] tryMatrixMultiply(final float[] x, final float[] y, final boolean xIsMat, final boolean yIsMat) {
		// mat4 * mat4 / mat4 * vec4 / vec4 * mat4
		if (x.length == 16 && y.length == 16) {
			return matMul(x, y, 4);
		}
		if (x.length == 16 && y.length == 4) {
			return matVecMul(x, y, 4);
		}
		if (x.length == 4 && y.length == 16) {
			return vecMatMul(x, y, 4);
		}
		// mat3 * mat3 / mat3 * vec3 / vec3 * mat3
		if (x.length == 9 && y.length == 9) {
			return matMul(x, y, 3);
		}
		if (x.length == 9 && y.length == 3) {
			return matVecMul(x, y, 3);
		}
		if (x.length == 3 && y.length == 9) {
			return vecMatMul(x, y, 3);
		}
		// mat2 * mat2 / mat2 * vec2 / vec2 * mat2
		if (x.length == 4 && y.length == 4 && xIsMat && yIsMat) {
			return matMul(x, y, 2);
		}
		if (x.length == 4 && y.length == 2) {
			return matVecMul(x, y, 2);
		}
		if (x.length == 2 && y.length == 4) {
			return vecMatMul(x, y, 2);
		}
		return null;
	}

	private static float[] matMul(final float[] a, final float[] b, final int d) {
		float[] out = new float[d * d];
		for (int col = 0; col < d; col++) {
			for (int row = 0; row < d; row++) {
				float sum = 0.0F;
				for (int k = 0; k < d; k++) {
					sum += a[k * d + row] * b[col * d + k];
				}
				out[col * d + row] = sum;
			}
		}
		return out;
	}

	private static float[] matVecMul(final float[] m, final float[] v, final int d) {
		float[] out = new float[d];
		for (int row = 0; row < d; row++) {
			float sum = 0.0F;
			for (int col = 0; col < d; col++) {
				sum += m[col * d + row] * v[col];
			}
			out[row] = sum;
		}
		return out;
	}

	private static float[] vecMatMul(final float[] v, final float[] m, final int d) {
		float[] out = new float[d];
		for (int col = 0; col < d; col++) {
			float sum = 0.0F;
			for (int row = 0; row < d; row++) {
				sum += v[row] * m[col * d + row];
			}
			out[col] = sum;
		}
		return out;
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

		@Override
		boolean isMatrix() {
			return this.name.equals("mat2") || this.name.equals("mat3") || this.name.equals("mat4")
				|| this.name.equals("transpose") || this.name.equals("inverse");
		}

		private float[] v(final Context ctx, final int i) {
			return i < this.args.size() ? this.args.get(i).eval(ctx) : new float[] {0.0F};
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
				case "float":
					return vec1(this.a(ctx, 0));
				case "int":
					return vec1((float) (int) this.a(ctx, 0));
				case "bool":
					return vec1(this.a(ctx, 0) != 0.0F ? 1.0F : 0.0F);
				case "vec2":
				case "vec3":
				case "vec4":
					return packVec(this.args, ctx, this.name.charAt(3) - '0', 0);
				case "ivec2":
				case "ivec3":
				case "ivec4":
					return packVec(this.args, ctx, this.name.charAt(4) - '0', 1);
				case "bvec2":
				case "bvec3":
				case "bvec4":
					return packVec(this.args, ctx, this.name.charAt(4) - '0', 2);
				case "mat2":
				case "mat3":
				case "mat4":
					return packMat(this.args, ctx, this.name.charAt(3) - '0');
				case "transpose":
					return transpose(this.v(ctx, 0));
				case "determinant":
					return vec1(determinant(this.v(ctx, 0)));
				case "inverse":
					return inverse(this.v(ctx, 0));
				case "in": {
					float val = this.a(ctx, 0);
					for (int i = 1; i < this.args.size(); i++) {
						if (this.a(ctx, i) == val) {
							return new float[] {1.0F};
						}
					}
					return new float[] {0.0F};
				}
				case "between": {
					float val = this.a(ctx, 0);
					return new float[] {val >= this.a(ctx, 1) && val <= this.a(ctx, 2) ? 1.0F : 0.0F};
				}
				case "equals":
					return new float[] {Math.abs(this.a(ctx, 0) - this.a(ctx, 1)) <= this.a(ctx, 2) ? 1.0F : 0.0F};
				case "clamp": {
					float[] x = this.v(ctx, 0);
					float[] lo = this.v(ctx, 1);
					float[] hi = this.v(ctx, 2);
					int n = Math.max(x.length, Math.max(lo.length, hi.length));
					float[] out = new float[n];
					for (int i = 0; i < n; i++) {
						out[i] = Math.max(at(lo, i), Math.min(at(hi, i), at(x, i)));
					}
					return out;
				}
				case "mix":
				case "lerp": {
					float[] a = this.v(ctx, 0);
					float[] b = this.v(ctx, 1);
					float[] t = this.v(ctx, 2);
					int n = Math.max(a.length, Math.max(b.length, t.length));
					float[] out = new float[n];
					for (int i = 0; i < n; i++) {
						float av = at(a, i);
						out[i] = av + (at(b, i) - av) * at(t, i);
					}
					return out;
				}
				case "smoothstep": {
					float[] e0 = this.v(ctx, 0);
					float[] e1 = this.v(ctx, 1);
					float[] x = this.v(ctx, 2);
					int n = Math.max(x.length, Math.max(e0.length, e1.length));
					float[] out = new float[n];
					for (int i = 0; i < n; i++) {
						float lo = at(e0, i);
						float hi = at(e1, i);
						float xv = at(x, i);
						if (hi == lo) {
							out[i] = xv < lo ? 0.0F : 1.0F;
						} else {
							float t = Math.max(0.0F, Math.min(1.0F, (xv - lo) / (hi - lo)));
							out[i] = t * t * (3.0F - 2.0F * t);
						}
					}
					return out;
				}
				case "step": {
					float[] edge = this.v(ctx, 0);
					float[] x = this.v(ctx, 1);
					int n = Math.max(edge.length, x.length);
					float[] out = new float[n];
					for (int i = 0; i < n; i++) {
						out[i] = at(x, i) < at(edge, i) ? 0.0F : 1.0F;
					}
					return out;
				}
				case "min":
				case "max": {
					if (this.args.isEmpty()) {
						return vec1(0.0F);
					}
					boolean isMin = this.name.equals("min");
					float[][] vals = new float[this.args.size()][];
					int n = 1;
					for (int i = 0; i < this.args.size(); i++) {
						vals[i] = this.args.get(i).eval(ctx);
						n = Math.max(n, vals[i].length);
					}
					float[] out = new float[n];
					for (int c = 0; c < n; c++) {
						float acc = at(vals[0], c);
						for (int i = 1; i < vals.length; i++) {
							acc = isMin ? Math.min(acc, at(vals[i], c)) : Math.max(acc, at(vals[i], c));
						}
						out[c] = acc;
					}
					return out;
				}
				case "frac":
				case "fract":
					return map1(this.v(ctx, 0), v -> v - Math.floor(v));
				case "abs": return map1(this.v(ctx, 0), Math::abs);
				case "sin": return map1(this.v(ctx, 0), Math::sin);
				case "cos": return map1(this.v(ctx, 0), Math::cos);
				case "tan": return map1(this.v(ctx, 0), Math::tan);
				case "asin": return map1(this.v(ctx, 0), Math::asin);
				case "acos": return map1(this.v(ctx, 0), Math::acos);
				case "sinh": return map1(this.v(ctx, 0), Math::sinh);
				case "cosh": return map1(this.v(ctx, 0), Math::cosh);
				case "tanh": return map1(this.v(ctx, 0), Math::tanh);
				case "atan":
					return this.args.size() > 1
						? map2(this.v(ctx, 0), this.v(ctx, 1), Math::atan2)
						: map1(this.v(ctx, 0), Math::atan);
				case "atan2": return map2(this.v(ctx, 0), this.v(ctx, 1), Math::atan2);
				case "torad":
				case "radians":
					return map1(this.v(ctx, 0), Math::toRadians);
				case "todeg":
				case "degrees":
					return map1(this.v(ctx, 0), Math::toDegrees);
				case "pow": return map2(this.v(ctx, 0), this.v(ctx, 1), Math::pow);
				case "sqrt": return map1(this.v(ctx, 0), Math::sqrt);
				case "inversesqrt": return map1(this.v(ctx, 0), v -> v > 0.0 ? 1.0 / Math.sqrt(v) : 0.0);
				case "exp": return map1(this.v(ctx, 0), Math::exp);
				case "exp2": return map1(this.v(ctx, 0), v -> Math.pow(2.0, v));
				case "exp10": return map1(this.v(ctx, 0), v -> Math.pow(10.0, v));
				case "log": return map1(this.v(ctx, 0), Math::log);
				case "log2": return map1(this.v(ctx, 0), v -> Math.log(v) / Math.log(2.0));
				case "log10": return map1(this.v(ctx, 0), Math::log10);
				case "floor": return map1(this.v(ctx, 0), Math::floor);
				case "ceil": return map1(this.v(ctx, 0), Math::ceil);
				case "round": return map1(this.v(ctx, 0), v -> (double) Math.round(v));
				case "trunc": return map1(this.v(ctx, 0), v -> v >= 0.0 ? Math.floor(v) : Math.ceil(v));
				case "sign":
				case "signum":
					return map1(this.v(ctx, 0), Math::signum);
				case "fmod":
				case "mod":
					return map2(this.v(ctx, 0), this.v(ctx, 1), (x, y) -> y == 0.0 ? 0.0 : x - y * Math.floor(x / y));
				case "hypot": return map2(this.v(ctx, 0), this.v(ctx, 1), Math::hypot);
				case "length": {
					float[] x = this.v(ctx, 0);
					double sum = 0.0;
					for (float f : x) {
						sum += (double) f * f;
					}
					return vec1((float) Math.sqrt(sum));
				}
				case "distance": {
					float[] a = this.v(ctx, 0);
					float[] b = this.v(ctx, 1);
					int n = Math.max(a.length, b.length);
					double sum = 0.0;
					for (int i = 0; i < n; i++) {
						double d = at(a, i) - at(b, i);
						sum += d * d;
					}
					return vec1((float) Math.sqrt(sum));
				}
				case "dot": {
					float[] a = this.v(ctx, 0);
					float[] b = this.v(ctx, 1);
					int n = Math.max(a.length, b.length);
					double sum = 0.0;
					for (int i = 0; i < n; i++) {
						sum += (double) at(a, i) * at(b, i);
					}
					return vec1((float) sum);
				}
				case "cross": {
					float[] a = this.v(ctx, 0);
					float[] b = this.v(ctx, 1);
					float ax = at(a, 0), ay = a.length > 1 ? a[1] : 0.0F, az = a.length > 2 ? a[2] : 0.0F;
					float bx = at(b, 0), by = b.length > 1 ? b[1] : 0.0F, bz = b.length > 2 ? b[2] : 0.0F;
					return new float[] {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
				}
				case "normalize": {
					float[] x = this.v(ctx, 0);
					double sum = 0.0;
					for (float f : x) {
						sum += (double) f * f;
					}
					float[] out = new float[x.length];
					if (sum > 0.0) {
						float inv = (float) (1.0 / Math.sqrt(sum));
						for (int i = 0; i < x.length; i++) {
							out[i] = x[i] * inv;
						}
					}
					return out;
				}
				case "reflect": {
					float[] inc = this.v(ctx, 0);
					float[] norm = this.v(ctx, 1);
					int n = Math.max(inc.length, norm.length);
					double d = 0.0;
					for (int i = 0; i < n; i++) {
						d += (double) at(norm, i) * at(inc, i);
					}
					float[] out = new float[n];
					for (int i = 0; i < n; i++) {
						out[i] = (float) (at(inc, i) - 2.0 * d * at(norm, i));
					}
					return out;
				}
				case "random": return vec1((float) Math.random());
				case "defined": return vec1(this.a(ctx, 0) != 0.0F ? 1.0F : 0.0F);
				default: return vec1(0.0F);
			}
		}

		private static float at(final float[] a, final int i) {
			return a[Math.min(i, a.length - 1)];
		}

		private static float[] map1(final float[] a, final DoubleUnaryOperator fn) {
			float[] out = new float[a.length];
			for (int i = 0; i < a.length; i++) {
				out[i] = (float) fn.applyAsDouble(a[i]);
			}
			return out;
		}

		@FunctionalInterface
		private interface DoubleBinOp {
			double apply(double a, double b);
		}

		private static float[] map2(final float[] a, final float[] b, final DoubleBinOp fn) {
			int n = Math.max(a.length, b.length);
			float[] out = new float[n];
			for (int i = 0; i < n; i++) {
				out[i] = (float) fn.apply(at(a, i), at(b, i));
			}
			return out;
		}

		private static float[] packVec(final List<Node> args, final Context ctx, final int n, final int mode) {
			float[] out = new float[n];
			int k = 0;
			for (Node arg : args) {
				for (float f : arg.eval(ctx)) {
					if (k < n) {
						out[k++] = mode == 1 ? (float) (int) f : mode == 2 ? (f != 0.0F ? 1.0F : 0.0F) : f;
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

		private static float[] packMat(final List<Node> args, final Context ctx, final int dim) {
			float[] out = new float[dim * dim];
			if (args.size() == 1) {
				float[] v = args.get(0).eval(ctx);
				if (v.length == 1) {
					for (int i = 0; i < dim; i++) {
						out[i * dim + i] = v[0];
					}
					return out;
				}
				int srcDim = v.length == 16 ? 4 : v.length == 9 ? 3 : v.length == 4 ? 2 : 0;
				if (srcDim > 0) {
					for (int i = 0; i < dim; i++) {
						out[i * dim + i] = 1.0F;
					}
					int m = Math.min(srcDim, dim);
					for (int col = 0; col < m; col++) {
						for (int row = 0; row < m; row++) {
							out[col * dim + row] = v[col * srcDim + row];
						}
					}
					return out;
				}
			}
			int k = 0;
			for (Node arg : args) {
				for (float f : arg.eval(ctx)) {
					if (k < out.length) {
						out[k++] = f;
					}
				}
			}
			return out;
		}

		private static float[] transpose(final float[] m) {
			int d = m.length == 16 ? 4 : m.length == 9 ? 3 : m.length == 4 ? 2 : 0;
			if (d == 0) {
				return m.clone();
			}
			float[] out = new float[m.length];
			for (int col = 0; col < d; col++) {
				for (int row = 0; row < d; row++) {
					out[col * d + row] = m[row * d + col];
				}
			}
			return out;
		}

		private static float determinant(final float[] m) {
			if (m.length == 4) {
				return m[0] * m[3] - m[2] * m[1];
			}
			if (m.length == 9) {
				double a = m[0], d = m[1], g = m[2];
				double b = m[3], e = m[4], h = m[5];
				double c = m[6], f = m[7], i = m[8];
				return (float) (a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g));
			}
			if (m.length == 16) {
				double s0 = (double) m[0] * m[5] - (double) m[4] * m[1];
				double s1 = (double) m[0] * m[6] - (double) m[4] * m[2];
				double s2 = (double) m[0] * m[7] - (double) m[4] * m[3];
				double s3 = (double) m[1] * m[6] - (double) m[5] * m[2];
				double s4 = (double) m[1] * m[7] - (double) m[5] * m[3];
				double s5 = (double) m[2] * m[7] - (double) m[6] * m[3];
				double c5 = (double) m[10] * m[15] - (double) m[14] * m[11];
				double c4 = (double) m[9] * m[15] - (double) m[13] * m[11];
				double c3 = (double) m[9] * m[14] - (double) m[13] * m[10];
				double c2 = (double) m[8] * m[15] - (double) m[12] * m[11];
				double c1 = (double) m[8] * m[14] - (double) m[12] * m[10];
				double c0 = (double) m[8] * m[13] - (double) m[12] * m[9];
				return (float) (s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0);
			}
			return m.length > 0 ? m[0] : 0.0F;
		}

		private static float[] inverse(final float[] m) {
			if (m.length == 4) {
				double det = (double) m[0] * m[3] - (double) m[2] * m[1];
				if (Math.abs(det) < 1e-12) {
					return new float[] {1, 0, 0, 1};
				}
				double inv = 1.0 / det;
				return new float[] {(float) (m[3] * inv), (float) (-m[1] * inv), (float) (-m[2] * inv), (float) (m[0] * inv)};
			}
			if (m.length == 9) {
				double a = m[0], b = m[3], c = m[6];
				double d = m[1], e = m[4], f = m[7];
				double g = m[2], h = m[5], i = m[8];
				double c00 = e * i - f * h, c01 = -(d * i - f * g), c02 = d * h - e * g;
				double c10 = -(b * i - c * h), c11 = a * i - c * g, c12 = -(a * h - b * g);
				double c20 = b * f - c * e, c21 = -(a * f - c * d), c22 = a * e - b * d;
				double det = a * c00 + b * c01 + c * c02;
				if (Math.abs(det) < 1e-12) {
					return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
				}
				double inv = 1.0 / det;
				return new float[] {
					(float) (c00 * inv), (float) (c01 * inv), (float) (c02 * inv),
					(float) (c10 * inv), (float) (c11 * inv), (float) (c12 * inv),
					(float) (c20 * inv), (float) (c21 * inv), (float) (c22 * inv)
				};
			}
			if (m.length == 16) {
				double s0 = (double) m[0] * m[5] - (double) m[4] * m[1];
				double s1 = (double) m[0] * m[6] - (double) m[4] * m[2];
				double s2 = (double) m[0] * m[7] - (double) m[4] * m[3];
				double s3 = (double) m[1] * m[6] - (double) m[5] * m[2];
				double s4 = (double) m[1] * m[7] - (double) m[5] * m[3];
				double s5 = (double) m[2] * m[7] - (double) m[6] * m[3];
				double c5 = (double) m[10] * m[15] - (double) m[14] * m[11];
				double c4 = (double) m[9] * m[15] - (double) m[13] * m[11];
				double c3 = (double) m[9] * m[14] - (double) m[13] * m[10];
				double c2 = (double) m[8] * m[15] - (double) m[12] * m[11];
				double c1 = (double) m[8] * m[14] - (double) m[12] * m[10];
				double c0 = (double) m[8] * m[13] - (double) m[12] * m[9];
				double det = s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0;
				if (Math.abs(det) < 1e-12) {
					return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
				}
				double inv = 1.0 / det;
				return new float[] {
					(float) ((m[5] * c5 - m[6] * c4 + m[7] * c3) * inv),
					(float) ((-m[1] * c5 + m[2] * c4 - m[3] * c3) * inv),
					(float) ((m[13] * s5 - m[14] * s4 + m[15] * s3) * inv),
					(float) ((-m[9] * s5 + m[10] * s4 - m[11] * s3) * inv),
					(float) ((-m[4] * c5 + m[6] * c2 - m[7] * c1) * inv),
					(float) ((m[0] * c5 - m[2] * c2 + m[3] * c1) * inv),
					(float) ((-m[12] * s5 + m[14] * s2 - m[15] * s1) * inv),
					(float) ((m[8] * s5 - m[10] * s2 + m[11] * s1) * inv),
					(float) ((m[4] * c4 - m[5] * c2 + m[7] * c0) * inv),
					(float) ((-m[0] * c4 + m[1] * c2 - m[3] * c0) * inv),
					(float) ((m[12] * s4 - m[13] * s2 + m[15] * s0) * inv),
					(float) ((-m[8] * s4 + m[9] * s2 - m[11] * s0) * inv),
					(float) ((-m[4] * c3 + m[5] * c1 - m[6] * c0) * inv),
					(float) ((m[0] * c3 - m[1] * c1 + m[2] * c0) * inv),
					(float) ((-m[12] * s3 + m[13] * s1 - m[14] * s0) * inv),
					(float) ((m[8] * s3 - m[9] * s1 + m[10] * s0) * inv)
				};
			}
			return m.clone();
		}

		private static float[] vec1(final float v) {
			return new float[] {v};
		}
	}
}
