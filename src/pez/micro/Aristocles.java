package pez.micro;
import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;
import java.util.ArrayList;

// Aristocles, by PEZ. 0-D DC (recency only) GF gun + surf.

public class Aristocles extends AdvancedRobot {
	static final int FACTORS = 25;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
	static final double BULLET_POWER = 1.9;
	static final double WALL_MARGIN = 18;

	static Aristocles robot;
	static Point2D enemyLocation;
	static ArrayList<double[]> gunObss = new ArrayList<>();
	static ArrayList<double[]> surfObss = new ArrayList<>();
	static double[] scores;
	static double direction = 0.4;
	static double enemyBearingDirection;
	static double enemyEnergy;

	public void run() {
		setAdjustRadarForGunTurn(true);
		setAdjustGunForRobotTurn(true);
		robot = this;
		do {
			turnRadarRightRadians(Double.POSITIVE_INFINITY);
		} while (true);
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		double enemyDistance = e.getDistance();
		double enemyVelocity = e.getVelocity();
		double robotVelocity = getVelocity();
		Point2D robotLocation = new Point2D.Double(getX(), getY());
		enemyLocation = project(robotLocation, enemyAbsoluteBearing, enemyDistance);

		// <movement> 0-D DC surf
		if (Wave.surfWave != null && surfObss.size() > 3) {
			dcFill(Wave.surfWave.obs, surfObss);
			int pk = bestGF(scores);
			if (pk != MIDDLE_FACTOR)
				direction = Math.copySign(0.4, (MIDDLE_FACTOR - pk) * Wave.surfWave.bearingDirection);
		}
		Wave.surfWave = null;

		double energyDelta = enemyEnergy - (enemyEnergy = e.getEnergy());
		if (energyDelta > 0 && energyDelta <= 3) {
			Wave sw = new Wave();
			sw.gunLocation = enemyLocation;
			sw.startBearing = enemyAbsoluteBearing + Math.PI;
			sw.bearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR, robotVelocity * Math.sin(getHeadingRadians() - enemyAbsoluteBearing - Math.PI));
			sw.bulletVelocity = 20 - 3 * energyDelta;
			sw.obs = new double[]{0};
			sw.surfable = true;
			addCustomEvent(sw);
		}

		Point2D robotDestination;
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				800 - WALL_MARGIN * 2, 600 - WALL_MARGIN * 2);
		int tries = 0;
		while (!fieldRectangle.contains(robotDestination = project(enemyLocation, enemyAbsoluteBearing + Math.PI + direction,
				enemyDistance * (1.2 - tries / 100.0))) && tries < 125) {
			tries++;
		}
		if (tries > 70) {
			direction = -direction;
		}
		double angle;
		setAhead(Math.cos(angle = absoluteBearing(robotLocation, robotDestination) - getHeadingRadians()) * 100);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun> 0-D DC GF targeting
		if (enemyVelocity != 0) {
			enemyBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR, enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		}

		Wave w = new Wave();
		w.gunLocation = robotLocation;
		w.startBearing = enemyAbsoluteBearing;
		w.bearingDirection = enemyBearingDirection;
		double bulletPower;
		w.bulletVelocity = 20 - 3 * (bulletPower = Math.min(e.getEnergy() / 4, enemyDistance > 360 ? BULLET_POWER : 3.0));
		w.obs = new double[]{0};

		dcFill(w.obs, gunObss);
		int best = bestGF(scores);

		setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
				enemyBearingDirection * (best - MIDDLE_FACTOR)));

		setFire(bulletPower);
		if (getEnergy() >= BULLET_POWER) {
			addCustomEvent(w);
		}
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
	}

	public void onHitByBullet(HitByBulletEvent e) {
		if (Wave.passingWave != null) {
			Point2D h = new Point2D.Double(getX(), getY());
			Wave.passingWave.obs[0] = (int) Math.clamp((long)(
				Utils.normalRelativeAngle(absoluteBearing(Wave.passingWave.gunLocation, h) - Wave.passingWave.startBearing)
				/ Wave.passingWave.bearingDirection + MIDDLE_FACTOR + 0.5), 0, FACTORS - 1);
			surfObss.add(Wave.passingWave.obs);
		}
	}

	static void dcFill(double[] q, ArrayList<double[]> list) {
		scores = new double[FACTORS];
		try { for (int i = 0; ; i++) {
			scores[(int) list.get(i)[0]] += 20 + i;
		} } catch (Exception e) {}
	}

	static int bestGF(double[] s) {
		int b = MIDDLE_FACTOR;
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
		static Wave passingWave;
		static Wave surfWave;
		double bulletVelocity;
		Point2D gunLocation;
		double startBearing, bearingDirection, distanceFromGun;
		double[] obs;
		boolean surfable;

		public boolean test() {
			distanceFromGun += bulletVelocity;
			if (surfable) {
				double dist = gunLocation.distance(robot.getX(), robot.getY());
				if (distanceFromGun > dist + 25) {
					robot.removeCustomEvent(this);
				} else {
					if (distanceFromGun > dist - 20) passingWave = this;
					if (distanceFromGun < dist && (surfWave == null ||
							dist - distanceFromGun < surfWave.gunLocation.distance(robot.getX(), robot.getY()) - surfWave.distanceFromGun))
						surfWave = this;
				}
			} else if (distanceFromGun > gunLocation.distance(enemyLocation) - 18) {
				obs[0] = (int) Math.clamp((long)(
					Utils.normalRelativeAngle(absoluteBearing(gunLocation, enemyLocation) - startBearing) / bearingDirection + MIDDLE_FACTOR + 0.5), 0, FACTORS - 1);
				gunObss.add(obs);
				robot.removeCustomEvent(this);
			}
			return false;
		}
	}
}
