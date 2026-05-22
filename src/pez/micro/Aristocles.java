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
	static final double MAX_TRIES = 125;
	static final double REVERSE_TUNER = 0.4;
	static final double WALL_BOUNCE_TUNER = 0.7;

	static final int DISTANCE_INDEXES = 10;
	static final int VELOCITY_INDEXES = 10;
	static final int LAST_VELOCITY_INDEXES = 10;
	static final int DECCEL_TIME_INDEXES = 10;
	static final int FACTORS = 25;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;

	static Point2D enemyLocation;
	static int lastVelocityIndex;
	static int timeSinceDeccel;
	static double enemyBearingDirection;
	static int[][][][][] aimFactors = new int[DISTANCE_INDEXES][VELOCITY_INDEXES][LAST_VELOCITY_INDEXES][DECCEL_TIME_INDEXES][FACTORS];
	static double direction = 0.4;
	static double enemyFirePower;
	static int GF1Hits;
	static int tries;

	public void run() {
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
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
		tries = 0;
		while (!fieldRectangle.contains(robotDestination = project(enemyLocation, enemyAbsoluteBearing + Math.PI + direction,
				enemyDistance * (1.2 - tries / 100.0))) && tries < MAX_TRIES) {
			tries++;
		}
		double bv = bulletVelocity(enemyFirePower);
		if (GF1Hits > 2 && (Math.random() < (bv / REVERSE_TUNER) / enemyDistance ||
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
		int velocityIndex = (int)(Math.abs(enemyVelocity) / (MAX_VELOCITY / VELOCITY_INDEXES));
		if (velocityIndex < lastVelocityIndex) {
			timeSinceDeccel = 0;
		}

		if (enemyVelocity != 0) {
			enemyBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR, enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		}
		wave.bearingDirection = enemyBearingDirection;

		int distanceIndex;
		wave.bulletPower = Math.min(getEnergy() / 2, Math.min(e.getEnergy() / 4,
				(distanceIndex = (int)(enemyDistance / (MAX_DISTANCE / DISTANCE_INDEXES))) > 1 ? BULLET_POWER : MAX_BULLET_POWER));
		//wave.bulletPower = MAX_BULLET_POWER; // TargetingChallenge

		wave.factors = aimFactors[distanceIndex][velocityIndex][lastVelocityIndex][Math.min(DECCEL_TIME_INDEXES - 1, timeSinceDeccel++ / 13)];
		lastVelocityIndex = velocityIndex;

		wave.startBearing = enemyAbsoluteBearing;

		int mostVisited = MIDDLE_FACTOR, i = FACTORS;
		do  {
			if (wave.factors[--i] > wave.factors[mostVisited]) {
				mostVisited = i;
			}
		} while (i > 0);

		setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
				wave.bearingDirection * (mostVisited - MIDDLE_FACTOR)));

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

	class Wave extends Condition {
		double bulletPower;
		Point2D gunLocation;
		double startBearing;
		double bearingDirection;
		int[] factors;
		double distanceFromGun;

		public boolean test() {
			if ((distanceFromGun += bulletVelocity(bulletPower)) > gunLocation.distance(enemyLocation) - 18) {
				try {
				int gf = (int)Math.round(((Utils.normalRelativeAngle(absoluteBearing(gunLocation, enemyLocation) - startBearing)) /
						bearingDirection) + MIDDLE_FACTOR);
				factors[gf] += 2;
				try { factors[gf - 1]++; } catch (Exception ex) {}
				try { factors[gf + 1]++; } catch (Exception ex) {}
				}
				catch (Exception e) {
				}
				removeCustomEvent(this);
			}
			return false;
		}
	}
}
