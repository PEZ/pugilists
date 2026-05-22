package pez.micro;
import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;
import java.util.ArrayList;

// Aristocles, by PEZ. Shared DC gun + surf.

public class Aristocles extends AdvancedRobot {
	static final int FACTORS = 25;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
	static final double BULLET_POWER = 1.9;
	static final double WALL_MARGIN = 18;
	// Weights: distance, accel, velocity, recency
	static final String W = "" + (char)1 + (char)100 + (char)50 + (char)20;

	static Aristocles robot;
	static Point2D enemyLocation;
	static ArrayList<double[]> gunObss = new ArrayList<>();
	static double[] scores;
	static double direction = 0.4;
	static double enemyBearingDirection;
	static double previousVelocity;
	static double enemyFirePower;
	static int GF1Hits;

	public void run() {
		setAdjustRadarForGunTurn(true);
		setAdjustGunForRobotTurn(true);
		robot = this;
		turnRadarRightRadians(Double.POSITIVE_INFINITY);
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		double enemyDistance = e.getDistance();
		double enemyVelocity = e.getVelocity();
		Point2D gunLocation = new Point2D.Double(getX(), getY());
		enemyLocation = project(gunLocation, enemyAbsoluteBearing, enemyDistance);

		// <movement> (unchanged random orbital from baseline)
		Point2D robotDestination;
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				800 - WALL_MARGIN * 2, 600 - WALL_MARGIN * 2);
		int tries = 0;
		while (!fieldRectangle.contains(robotDestination = project(enemyLocation, enemyAbsoluteBearing + Math.PI + direction,
				enemyDistance * (1.2 - tries / 100.0))) && tries < 125) {
			tries++;
		}
		double bulletVelocity = 20 - 3 * enemyFirePower;
		if (GF1Hits > 2 && (Math.random() < (bulletVelocity / 0.421075) / enemyDistance ||
				tries > (enemyDistance / bulletVelocity / 0.699484))) {
			direction = -direction;
		}
		double angle;
		setAhead(Math.cos(angle = absoluteBearing(gunLocation, robotDestination) - getHeadingRadians()) * 100);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun> DC targeting
		if (enemyVelocity != 0) {
			enemyBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR, enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		}

		Wave w = new Wave();
		w.gunLocation = gunLocation;
		w.startBearing = enemyAbsoluteBearing;
		w.bearingDirection = enemyBearingDirection;
		double absoluteVelocity = Math.abs(enemyVelocity);
		double bulletPower;
		w.bulletVelocity = 20 - 3 * (bulletPower = Math.min(e.getEnergy() / 4, enemyDistance > 360 ? BULLET_POWER : 3.0));
		w.obs = new double[]{0, enemyDistance, previousVelocity - absoluteVelocity, absoluteVelocity};
		previousVelocity = absoluteVelocity;

		dcFill(w.obs);
		int best = bestGF();

		setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
				enemyBearingDirection * (best - MIDDLE_FACTOR)));

		if (getEnergy() >= BULLET_POWER) {
			addCustomEvent(w);
			setFire(bulletPower);
		}
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
	}

	public void onHitByBullet(HitByBulletEvent e) {
		GF1Hits++;
		enemyFirePower = e.getPower();
	}

	static void dcFill(double[] query) {
		scores = new double[FACTORS];
		try { for (int i = 0; ; i++) {
			double[] obs = gunObss.get(i);
			double distance = 0.01;
			for (int j = 1; j < 4; j++)
				distance += Math.abs(obs[j] - query[j]) * W.charAt(j - 1);
			scores[(int) obs[0]] += (W.charAt(3) + i) / (distance * distance);
		} } catch (Exception e) {}
	}

	static int bestGF() {
		int best = MIDDLE_FACTOR;
		try { for (int i = 0; ; i++) {
			if (scores[i] > scores[best]) best = i;
		} } catch (Exception e) {}
		return best;
	}

	static Point2D project(Point2D s, double a, double l) {
		return new Point2D.Double(s.getX() + Math.sin(a) * l, s.getY() + Math.cos(a) * l);
	}

	static double absoluteBearing(Point2D s, Point2D t) {
		return Math.atan2(t.getX() - s.getX(), t.getY() - s.getY());
	}

	static class Wave extends Condition {
		double bulletVelocity;
		Point2D gunLocation;
		double startBearing, bearingDirection, distanceFromGun;
		double[] obs;

		public boolean test() {
			if ((distanceFromGun += bulletVelocity) > gunLocation.distance(enemyLocation) - 18) {
				obs[0] = (int) Math.clamp((long)(
					Utils.normalRelativeAngle(absoluteBearing(gunLocation, enemyLocation) - startBearing) / bearingDirection + MIDDLE_FACTOR + 0.5), 0, FACTORS - 1);
				gunObss.add(obs);
				robot.removeCustomEvent(this);
			}
			return false;
		}
	}
}
