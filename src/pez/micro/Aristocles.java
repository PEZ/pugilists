package pez.micro;
import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;
import java.util.ArrayList;

// Aristocles, by PEZ. Shared DC gun + surf.

public class Aristocles extends AdvancedRobot {
	static final int F = 25;
	static final int M = (F - 1) / 2;
	static final double BULLET_POWER = 1.9;
	static final double WALL_MARGIN = 18;
	// Weights: distance, accel, velocity, recency
	static final String W = "" + (char)1 + (char)100 + (char)50 + (char)20;

	static Aristocles R;
	static Point2D eL;
	static ArrayList<double[]> obss = new ArrayList<>();
	static double[] scores;
	static double dir = 0.4;
	static double bD;
	static double eE;
	static double pV;

	public void run() {
		setAdjustRadarForGunTurn(true);
		setAdjustGunForRobotTurn(true);
		R = this;
		do {
			turnRadarRightRadians(Double.POSITIVE_INFINITY);
		} while (true);
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		double eAB = getHeadingRadians() + e.getBearingRadians();
		double eD = e.getDistance();
		double eV = e.getVelocity();
		Point2D gL = new Point2D.Double(getX(), getY());
		eL = project(gL, eAB, eD);

		// <movement> energy-drop-timed reversal
		double dE = eE - (eE = e.getEnergy());
		if (dE > 0 && dE <= 3 && Math.random() < 0.4) {
			dir = -dir;
		}

		Point2D robotDestination;
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				800 - WALL_MARGIN * 2, 600 - WALL_MARGIN * 2);
		int tries = 0;
		while (!fieldRectangle.contains(robotDestination = project(eL, eAB + Math.PI + dir,
				eD * (1.2 - tries / 100.0))) && tries < 125) {
			tries++;
		}
		if (tries > 70) {
			dir = -dir;
		}
		double angle;
		setAhead(Math.cos(angle = absoluteBearing(gL, robotDestination) - getHeadingRadians()) * 100);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun> DC targeting
		if (eV != 0) {
			bD = Math.copySign(0.7 / M, eV * Math.sin(e.getHeadingRadians() - eAB));
		}

		Wave w = new Wave();
		w.gL = gL;
		w.b = eAB;
		w.bD = bD;
		double aV = Math.abs(eV);
		double bp;
		w.bv = 20 - 3 * (bp = Math.min(e.getEnergy() / 4, eD > 360 ? BULLET_POWER : 3.0));
		w.o = new double[]{0, eD, pV - aV, aV};
		pV = aV;

		dcFill(w.o);
		int best = bestGF();

		setTurnGunRightRadians(Utils.normalRelativeAngle(eAB - getGunHeadingRadians() +
				bD * (best - M)));

		setFire(bp);
		if (getEnergy() >= BULLET_POWER) {
			addCustomEvent(w);
		}
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(eAB - getRadarHeadingRadians()) * 2);
	}

	static void dcFill(double[] q) {
		scores = new double[F];
		try { for (int i = 0; ; i++) {
			double[] o = obss.get(i);
			double d = 0.01;
			for (int j = 1; j < 4; j++)
				d += Math.abs(o[j] - q[j]) * W.charAt(j - 1);
			scores[(int) o[0]] += (W.charAt(3) + i) / (d * d);
		} } catch (Exception e) {}
	}

	static int bestGF() {
		int b = M;
		try { for (int i = 0; ; i++) {
			if (scores[i] > scores[b]) b = i;
		} } catch (Exception e) {}
		return b;
	}

	static Point2D project(Point2D s, double a, double l) {
		return new Point2D.Double(s.getX() + Math.sin(a) * l, s.getY() + Math.cos(a) * l);
	}

	static double absoluteBearing(Point2D s, Point2D t) {
		return Math.atan2(t.getX() - s.getX(), t.getY() - s.getY());
	}

	static class Wave extends Condition {
		double bv;
		Point2D gL;
		double b, bD, d;
		double[] o;

		public boolean test() {
			if ((d += bv) > gL.distance(eL) - 18) {
				o[0] = (int) Math.clamp((long)(
					Utils.normalRelativeAngle(absoluteBearing(gL, eL) - b) / bD + M + 0.5), 0, F - 1);
				obss.add(o);
				R.removeCustomEvent(this);
			}
			return false;
		}
	}
}
