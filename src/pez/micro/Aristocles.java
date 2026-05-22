package pez.micro;
import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;

// Aristocles, by PEZ. 1-D binned (distance) GF gun + surf.

public class Aristocles extends AdvancedRobot {
	static final int F = 25;
	static final int M = (F - 1) / 2;
	static final double BULLET_POWER = 1.9;
	static final double WALL_MARGIN = 18;

	static Aristocles R;
	static Point2D eL;
	static double[][] gGF = new double[3][F];
	static double[][] sGF = new double[3][F];
	static double dir = 0.4;
	static double bD;
	static double eE;

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
		double myV = getVelocity();
		Point2D gL = new Point2D.Double(getX(), getY());
		eL = project(gL, eAB, eD);

		// <movement> 1-D binned surf
		if (Wave.surfWave != null) {
			int pk = bestGF(sGF[(int) Math.min(Wave.surfWave.eD / 250, 2)]);
			if (pk != M)
				dir = Math.copySign(0.4, (M - pk) * Wave.surfWave.bD);
		}
		Wave.surfWave = null;

		double dE = eE - (eE = e.getEnergy());
		if (dE > 0 && dE <= 3) {
			Wave sw = new Wave();
			sw.gL = eL;
			sw.b = eAB + Math.PI;
			sw.bD = Math.copySign(0.7 / M, myV * Math.sin(getHeadingRadians() - eAB - Math.PI));
			sw.bv = 20 - 3 * dE;
			sw.eD = eD;
			sw.s = true;
			addCustomEvent(sw);
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

		// <gun> 1-D binned GF targeting
		if (eV != 0) {
			bD = Math.copySign(0.7 / M, eV * Math.sin(e.getHeadingRadians() - eAB));
		}

		Wave w = new Wave();
		w.gL = gL;
		w.b = eAB;
		w.bD = bD;
		double bp;
		w.bv = 20 - 3 * (bp = Math.min(e.getEnergy() / 4, eD > 360 ? BULLET_POWER : 3.0));
		w.eD = eD;

		int best = bestGF(gGF[(int) Math.min(eD / 250, 2)]);

		setTurnGunRightRadians(Utils.normalRelativeAngle(eAB - getGunHeadingRadians() +
				bD * (best - M)));

		setFire(bp);
		if (getEnergy() >= BULLET_POWER) {
			addCustomEvent(w);
		}
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(eAB - getRadarHeadingRadians()) * 2);
	}

	public void onHitByBullet(HitByBulletEvent e) {
		if (Wave.pW != null) {
			Point2D h = new Point2D.Double(getX(), getY());
			sGF[(int) Math.min(Wave.pW.eD / 250, 2)][(int) Math.clamp((long)(
				Utils.normalRelativeAngle(absoluteBearing(Wave.pW.gL, h) - Wave.pW.b)
				/ Wave.pW.bD + M + 0.5), 0, F - 1)]++;
		}
	}

	static int bestGF(double[] s) {
		int b = M;
		try { for (int i = 0; ; i++) {
			if (s[i] > s[b]) b = i;
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
		static Wave pW;
		static Wave surfWave;
		double bv;
		Point2D gL;
		double b, bD, d, eD;
		boolean s;

		public boolean test() {
			d += bv;
			if (s) {
				double dist = gL.distance(R.getX(), R.getY());
				if (d > dist + 25) {
					R.removeCustomEvent(this);
				} else {
					if (d > dist - 20) pW = this;
					if (d < dist && (surfWave == null ||
							dist - d < surfWave.gL.distance(R.getX(), R.getY()) - surfWave.d))
						surfWave = this;
				}
			} else if (d > gL.distance(eL) - 18) {
				gGF[(int) Math.min(eD / 250, 2)][(int) Math.clamp((long)(
					Utils.normalRelativeAngle(absoluteBearing(gL, eL) - b) / bD + M + 0.5), 0, F - 1)]++;
				R.removeCustomEvent(this);
			}
			return false;
		}
	}
}
