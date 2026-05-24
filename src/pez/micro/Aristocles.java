package pez.micro;
import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;

// This code is released under the RoboWiki Public Code Licence (RWPCL), datailed on:
// http://robowiki.net/?RWPCL
//
// Aristocles, by PEZ. What you see is always an imperfect copy of the form. 
// $Id: Aristocles.java,v 1.11 2004/02/22 20:10:06 peter Exp $
public class Aristocles extends AdvancedRobot {
	static final double BATTLE_FIELD_WIDTH = 800;
	static final double BATTLE_FIELD_HEIGHT = 600;

	static final double MAX_DISTANCE = 900;
	static final double MAX_VELOCITY = 10;
	static final double MAX_BULLET_POWER = 3.0;
	static final double BULLET_POWER = 1.9;
	static final double WALL_MARGIN = 18;
	static final double REVERSE_TUNER = 0.421075;
	static final double WALL_BOUNCE_TUNER = 0.699484;

	static final int DISTANCE_INDEXES = 10;
	static final int VELOCITY_INDEXES = 10;
	static final int VCHANGE_TIME_INDEXES = 10;
	static final int FACTORS = 101;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;

	static Point2D enemyLocation;
	static int lastVelocityIndex;
	static int timeSinceVChange;
	static double enemyBearingDirection;
	static int[][][][][] aimFactors = new int[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][VCHANGE_TIME_INDEXES][FACTORS];
	static double direction = 0.4;
	static double enemyFirePower;
	static int GF1Hits;
	static int tries;
	static Aristocles robot;

	public void run() {
		robot = this;
		setAdjustRadarForGunTurn(true);
		setAdjustGunForRobotTurn(true);

		turnRadarRightRadians(Double.POSITIVE_INFINITY); 
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		Wave wave = new Wave();
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		double enemyDistance;
		enemyLocation = project(wave.gunLocation = new Point2D.Double(getX(), getY()), enemyAbsoluteBearing, enemyDistance = e.getDistance());

		// <movement>
		Point2D robotDestination;
		tries = 0;
		while (!new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2).contains(robotDestination = project(enemyLocation,
				enemyAbsoluteBearing + Math.PI + direction, enemyDistance * (1.2 - tries / 100.0)))
				&& tries++ < 125);
		double bv = bulletVelocity(enemyFirePower);
		if (GF1Hits > 4 && (Math.random() < (bv / REVERSE_TUNER) / enemyDistance ||
				tries > (enemyDistance / bv / WALL_BOUNCE_TUNER))) {
			direction = -direction;
		}
		// Jamougha's cool way
		double angle;
		setAhead(Math.cos(angle = absoluteBearing(wave.gunLocation, robotDestination) - getHeadingRadians()) * 100);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun>
		double enemyVelocity = e.getVelocity();
		int velocityIndex = (int) (Math.abs(enemyVelocity) / (MAX_VELOCITY / VELOCITY_INDEXES));
		if (velocityIndex != lastVelocityIndex) {
			timeSinceVChange = 0;
		}
		int distanceIndex;
		wave.bulletPower = Math.min(getEnergy() / 2, Math.min(e.getEnergy() / 4,
				(distanceIndex = (int)(enemyDistance / (MAX_DISTANCE / DISTANCE_INDEXES))) > 1 ? BULLET_POWER : MAX_BULLET_POWER));
		//wave.bulletPower = MAX_BULLET_POWER; // TargetingChallenge

		if (enemyVelocity != 0) {
			enemyBearingDirection = Math.copySign(Math.asin(8 / bulletVelocity(wave.bulletPower)) / MIDDLE_FACTOR, enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		}
		wave.bearingDirection = enemyBearingDirection;

		wave.factors = aimFactors[distanceIndex][velocityIndex][lastVelocityIndex][Math.min(VCHANGE_TIME_INDEXES - 1,
				timeSinceVChange++ / 13)];
		lastVelocityIndex = velocityIndex;

		wave.startBearing = enemyAbsoluteBearing;

		int mostVisited = MIDDLE_FACTOR, i = FACTORS;
		do  {
			if (wave.factors[--i] > wave.factors[mostVisited]) {
				mostVisited = i;
			}
		} while (i > 0);

		setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
				enemyBearingDirection * (mostVisited - MIDDLE_FACTOR)));

		setFire(wave.bulletPower);
		addCustomEvent(wave);
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
	}

	public void onHitByBullet(HitByBulletEvent e) {
		if (tries < 30) {
			GF1Hits++;
		}
		enemyFirePower = e.getPower();
	}

	static double bulletVelocity(double power) {
		return 20 - 3 * power;
	}

	static Point2D project(Point2D sourceLocation, double angle, double length) {
		return new Point2D.Double(sourceLocation.getX() + Math.sin(angle) * length,
				sourceLocation.getY() + Math.cos(angle) * length);
	}

	static double absoluteBearing(Point2D source, Point2D target) {
		return Math.atan2(target.getX() - source.getX(), target.getY() - source.getY());
	}

	static class Wave extends Condition {
		double bulletPower;
		Point2D gunLocation;
		double startBearing;
		double bearingDirection;
		int[] factors;
		double distanceFromGun;

		public boolean test() {
			if ((distanceFromGun += bulletVelocity(bulletPower)) > gunLocation.distance(enemyLocation)) {
				int gf = (int)Math.round(((Utils.normalRelativeAngle(absoluteBearing(gunLocation, enemyLocation) - startBearing)) /
						bearingDirection) + MIDDLE_FACTOR);
				for (int s = FACTORS; --s >= 0;) {
					factors[s] += FACTORS / (Math.abs(gf - s) + 1);
				}
				robot.removeCustomEvent(this);
			}
			return false;
		}
	}
}
