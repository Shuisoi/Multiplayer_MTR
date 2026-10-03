import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;

import java.lang.reflect.Field;

/**
 * MTR 轨道几何自检：对**发布的引擎 jar** 跑真 RailMath，核对本工作区认定的几何规则。
 *
 * <p>为什么要有这个：轨道形状完全由「两端位置 + 两端方块朝向」决定，而引擎对**非法组合不报错**
 * （见 .dsh/skills/mmtr-track-building ：along != lateral 时静默给「直线 + R=min」，
 * 横向错开为 0 却要求 90 度转向时给零长轨）。所以几何假设必须能被反复验证，而不是靠读源码推断。
 *
 * <p>复现：pwsh -File mmtr\tools\rail-geometry-probe\run.ps1
 * 退出码 0 = 全部通过；1 = 有 MISMATCH（会逐条打印）。
 *
 * <p>编译注意（踩过）：
 * <ul>
 *   <li>引擎 jar 是 <b>shaded</b> 的，getAngles 返回 {@code org.mtr.libraries.it.unimi...}
 *       （带前缀），而引擎源码树里是不带前缀的 —— 对 jar 写探针要用带前缀的。</li>
 *   <li>PATH 上 javac 与 java 可能不同版本 ⇒ 必须 {@code --release 21}。</li>
 * </ul>
 */
public class RailProbe {

	private static int checks = 0;
	private static int failures = 0;

	// ---------- RailMath 读数 ----------

	private static Object f(Object o, String n) throws Exception {
		Class<?> c = o.getClass();
		while (c != null) {
			try {
				final Field fd = c.getDeclaredField(n);
				fd.setAccessible(true);
				return fd.get(o);
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(n);
	}

	private static final class Shape {
		final boolean straight1;
		final boolean straight2;
		final double r1;
		final double r2;
		final double len1;
		final double len2;
		final double total;
		final Angle a1;
		final Angle a2;

		Shape(boolean s1, boolean s2, double r1, double r2, double l1, double l2, double total, Angle a1, Angle a2) {
			this.straight1 = s1;
			this.straight2 = s2;
			this.r1 = r1;
			this.r2 = r2;
			this.len1 = l1;
			this.len2 = l2;
			this.total = total;
			this.a1 = a1;
			this.a2 = a2;
		}

		double radius() {
			return Math.max(r1, r2);
		}

		double straightLength() {
			return (straight1 ? len1 : 0) + (straight2 ? len2 : 0);
		}

		double arcLength() {
			return (straight1 ? 0 : len1) + (straight2 ? 0 : len2);
		}

		String layout() {
			return (straight1 ? "STR" : "ARC") + " + " + (straight2 ? "STR" : "ARC");
		}
	}

	/** h = 节点方块朝向角（度，mod 180 语义）。走的是玩家那条 getAngles 通路。 */
	private static Shape build(long x1, long z1, float h1, long x2, long z2, float h2) throws Exception {
		final Position p1 = new Position(x1, 65, z1);
		final Position p2 = new Position(x2, 65, z2);
		final ObjectObjectImmutablePair<Angle, Angle> a = Rail.getAngles(p1, h1, p2, h2);
		final RailMath m = new RailMath(p1, a.left(), p2, a.right(), Rail.Shape.QUADRATIC, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		return new Shape(
			(Boolean) f(m, "isStraight1"), (Boolean) f(m, "isStraight2"),
			((Number) f(m, "r1")).doubleValue(), ((Number) f(m, "r2")).doubleValue(),
			Math.abs(((Number) f(m, "tEnd1")).doubleValue() - ((Number) f(m, "tStart1")).doubleValue()),
			Math.abs(((Number) f(m, "tEnd2")).doubleValue() - ((Number) f(m, "tStart2")).doubleValue()),
			m.getLength(), a.left(), a.right());
	}

	// ---------- 断言 ----------

	private static void check(String label, boolean ok, String detail) {
		checks++;
		if (!ok) {
			failures++;
		}
		System.out.printf("  %s %-46s %s%n", ok ? "[ OK ]" : "[FAIL]", label, detail);
	}

	private static void close(String label, double got, double want, double tol) {
		check(label, Math.abs(got - want) <= tol, String.format("got %.2f  want %.2f (+-%.2f)", got, want, tol));
	}

	// ---------- 各套用例 ----------

	/** A. 直线：两端同朝向且共线 ⇒ 一段直线，长度 = 距离。 */
	private static void suiteStraight() throws Exception {
		System.out.println("\nA. 直线（两端朝向相同 + 共线）");
		final Shape s = build(0, 0, 0, 100, 0, 0);
		check("沿 X 100 m", s.straight1 && s.straight2, s.layout());
		close("  长度", s.total, 100, 0.01);
		final Shape t = build(0, 0, 90, 0, 220, 90);
		check("沿 Z 220 m", t.straight1 && t.straight2, t.layout());
		close("  长度", t.total, 220, 0.01);
	}

	/** B. S 弯：两端同朝向 + 横向错开 o、纵向 L ⇒ 两段弧，R = (L^2 + o^2) / (4 o)。 */
	private static void suiteSCurve() throws Exception {
		System.out.println("\nB. S 弯（两端朝向相同 + 横向错开）  R = (L^2 + o^2) / (4 o)");
		final long[][] cases = {{200, 10}, {200, 5}, {100, 10}, {400, 20}, {200, 40}};
		for (final long[] c : cases) {
			final Shape s = build(0, 0, 0, c[0], c[1], 0);
			final double want = ((double) c[0] * c[0] + (double) c[1] * c[1]) / (4.0 * c[1]);
			check(String.format("L=%d o=%d", c[0], c[1]), !s.straight1 && !s.straight2, s.layout());
			close("  R", s.radius(), want, 0.01);
		}
	}

	/** C. 纯 90 度圆角：8 个朝向组合 + 多个半径，弧长必须是 pi*R/2。 */
	private static void suiteQuarterCircle() throws Exception {
		System.out.println("\nC. 纯 90 度圆角：8 个朝向组合，R = min(along, lateral)，弧长 = pi*R/2");
		final double[][] unit = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
		for (final double r : new double[]{50, 100, 200, 400, 800}) {
			for (int i = 0; i < 4; i++) {
				final float h1 = (i % 2 == 0) ? 0F : 90F;
				for (final int sense : new int[]{1, -1}) {
					final int j = ((i + sense) % 4 + 4) % 4;
					final long ex = Math.round(r * unit[j][0] + r * unit[i][0]);
					final long ez = Math.round(r * unit[j][1] + r * unit[i][1]);
					final float h2 = (j % 2 == 0) ? 0F : 90F;
					final Shape s = build(0, 0, h1, ex, ez, h2);
					final String label = String.format("R=%.0f d1=%3d turn%+4d", r, i * 90, sense * 90);
					// 纯圆角 = 没有任何直线段，且确实有一段弧（排除零长轨那种"两段都空"）
					final boolean pure = s.straightLength() < 0.01 && s.arcLength() > 0.01;
					check(label, pure && Math.abs(s.radius() - r) < 0.01,
						String.format("%s R=%.2f arc=%.2f", s.layout(), s.radius(), s.arcLength()));
					close("  arc = pi*R/2", s.arcLength(), Math.PI * r / 2, 0.01);
				}
			}
		}
	}

	/** D. 90 度转角的网格规则：R = min(|along|,|lateral|)，直线段 = 两者之差。 */
	private static void suiteCornerGrid() throws Exception {
		System.out.println("\nD. 90 度转角网格：R = min(|along|,|lateral|)，直线段 = ||along|-|lateral||");
		final long[] lat = {50, 100, 200, 400};
		final double[] mul = {0, 0.25, 0.5, 1.0, 2.0};
		for (final long la : lat) {
			for (final double mu : mul) {
				final long along = Math.round(la * mu);
				final Shape s = build(0, 0, 0, along, la, 90);
				final double wantR = Math.min(Math.abs(along), Math.abs(la));
				final double wantStraight = Math.abs(Math.abs(along) - Math.abs(la));
				final String label = String.format("along=%-5d lateral=%-5d", along, la);
				final boolean ok = Math.abs(s.radius() - wantR) <= 0.01
					&& Math.abs(s.straightLength() - wantStraight) <= 0.51
					&& Math.abs(s.arcLength() - Math.PI * wantR / 2) <= 0.51;
				check(label, ok, String.format("%s R=%.2f str=%.2f arc=%.2f  (want R=%.0f str=%.0f)",
					s.layout(), s.radius(), s.straightLength(), s.arcLength(), wantR, wantStraight));
			}
		}
	}

	/** E. 带引线的转角：一条命令 = 直线 + 圆弧。 */
	private static void suiteLead() throws Exception {
		System.out.println("\nE. 带引线的转角（一条 rail add）");
		final Shape s = build(0, 0, 0, 300, 200, 90);
		check("(0,0,h0)->(300,200,h90)", s.straight1 && !s.straight2, s.layout());
		close("  直线段", s.len1, 100, 0.01);
		close("  半径", s.r2, 200, 0.01);
		final Shape u = build(0, 0, 0, 200, 400, 90);
		check("(0,0,h0)->(200,400,h90) 弧在前", !u.straight1 && u.straight2, u.layout());
		close("  直线段", u.len2, 200, 0.01);
		close("  半径", u.r1, 200, 0.01);
	}

	/** F. 退化：几何自相矛盾时给零长轨（不报错）。 */
	private static void suiteDegenerate() throws Exception {
		System.out.println("\nF. 退化（引擎不报错，只给零长/极小轨）");
		final Shape z = build(0, 0, 0, 200, 0, 90);
		check("横向错开 0 却要 90 度转向", z.total < 0.01, String.format("总长 %.4f", z.total));
		final Shape tiny = build(0, 0, 0, 1, 1, 90);
		check("相邻一格的转角仍可建", tiny.arcLength() > 1.5 && tiny.radius() < 1.01,
			String.format("R=%.2f arc=%.2f", tiny.radius(), tiny.arcLength()));
	}

	/** G. 静默变形：along != lateral 时引擎不拒绝，而是给「直线 + R=min」。 */
	private static void suiteSilentDeform() throws Exception {
		System.out.println("\nG. 静默变形（★ 最危险：不报错，但半径不是你要的）");
		final Shape s = build(0, 0, 0, 200, 50, 90);
		check("(0,0,h0)->(200,50,h90) 不是 R200",
			Math.abs(s.radius() - 50) < 0.01 && Math.abs(s.straightLength() - 150) < 0.51,
			String.format("%s R=%.2f str=%.2f  ← 半径被压到 min(200,50)=50",
				s.layout(), s.radius(), s.straightLength()));
	}

	public static void main(String[] args) throws Exception {
		System.out.println("MTR 轨道几何自检（对发布的引擎 jar 跑真 RailMath）");
		suiteStraight();
		suiteSCurve();
		suiteQuarterCircle();
		suiteCornerGrid();
		suiteLead();
		suiteDegenerate();
		suiteSilentDeform();

		System.out.printf("%n===== %d 项检查 / %d 项失败 =====%n", checks, failures);
		if (failures > 0) {
			System.out.println("几何假设与引擎实际行为不一致 —— 先修 .dsh/skills/mmtr-track-building 再动工。");
			System.exit(1);
		}
		System.out.println("全部通过：几何规则与引擎实际行为一致。");
	}
}
