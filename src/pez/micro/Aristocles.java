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

	static final int DISTANCE_INDEXES = 10;
	static final int VELOCITY_INDEXES = 10;
	static final int VCHANGE_TIME_INDEXES = 10;
	static final int FACTORS = 37;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;

	static Point2D enemyLocation;
	static int lastVelocityIndex;
	static int timeSinceVChange;
	static double enemyBearingDirection;
	static int[][][][][] aimFactors = new int[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][VCHANGE_TIME_INDEXES][FACTORS];
	static Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
			BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
	static Point2D robotLocation;
	static int[] surfFactors = new int[FACTORS];
	static double direction = 1;
	static double enemyFirePower = BULLET_POWER;
	static double dangerForward;
	static double dangerReverse;
	static Wave passingWave;

	public void run() {
		setAdjustRadarForGunTurn(true);
		setAdjustGunForRobotTurn(true);

		turnRadarRightRadians(Double.POSITIVE_INFINITY); 
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		Wave wave = new Wave();
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		double enemyDistance;
		robotLocation = wave.gunLocation = new Point2D.Double(getX(), getY());
		enemyLocation = project(robotLocation, enemyAbsoluteBearing, enemyDistance = e.getDistance());

		Wave surfWave = new Wave();
		surfWave.gunLocation = enemyLocation;
		surfWave.startBearing = enemyAbsoluteBearing + Math.PI;
		surfWave.bulletPower = enemyFirePower;
		surfWave.bearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
				getVelocity() * Math.sin(getHeadingRadians() - surfWave.startBearing));
		surfWave.factors = surfFactors;
		addCustomEvent(surfWave);

		// <movement>
		if (dangerReverse < dangerForward) {
			direction = -direction;
		}
		Point2D robotDestination = wallSmoothedDestination(direction);
		dangerForward = dangerReverse = 0;
		// Jamougha's cool way
		double angle;
		setAhead(Math.cos(angle = absoluteBearing(robotLocation, robotDestination) - getHeadingRadians()) * 200);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun>
		double enemyVelocity = e.getVelocity();
		int velocityIndex = (int) (Math.abs(enemyVelocity) / (MAX_VELOCITY / VELOCITY_INDEXES));
		if (velocityIndex != lastVelocityIndex) {
			timeSinceVChange = 0;
		}

		if (enemyVelocity != 0) {
			enemyBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR, enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		}
		wave.bearingDirection = enemyBearingDirection;

		int distanceIndex;
		wave.bulletPower = Math.min(getEnergy() / 2, Math.min(e.getEnergy() / 4,
				(distanceIndex = (int)(enemyDistance / (MAX_DISTANCE / DISTANCE_INDEXES))) > 1 ? BULLET_POWER : MAX_BULLET_POWER));
		//wave.bulletPower = MAX_BULLET_POWER; // TargetingChallenge

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
				wave.bearingDirection * (mostVisited - MIDDLE_FACTOR)));

		setFire(wave.bulletPower);
		addCustomEvent(wave);
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
	}

	public void onHitByBullet(HitByBulletEvent e) {
		enemyFirePower = e.getPower();
		try {
			passingWave.factors[passingWave.visitingIndex(robotLocation)]++;
		}
		catch (Exception ex) {
		}
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

	static Point2D wallSmoothedDestination(double movementDirection) {
		int tries = 0;
		Point2D destination;
		double enemyAbsoluteBearing = absoluteBearing(robotLocation, enemyLocation);
		while (!fieldRectangle.contains(destination = project(robotLocation,
				enemyAbsoluteBearing - movementDirection * (Math.PI / 2 - tries / 100.0), 160)) && tries++ < 125);
		return destination;
	}

	class Wave extends Condition {
		double bulletPower;
		Point2D gunLocation;
		double startBearing;
		double bearingDirection;
		int[] factors;
		double distanceFromGun;

		int visitingIndex(Point2D location) {
			return (int)Math.round(((Utils.normalRelativeAngle(absoluteBearing(gunLocation, location) - startBearing)) /
					bearingDirection) + MIDDLE_FACTOR);
		}

		double danger(Point2D location) {
			try {
				return factors[visitingIndex(location)];
			}
			catch (Exception e) {
				return 0;
			}
		}

		public boolean test() {
			distanceFromGun += bulletVelocity(bulletPower);
			if (factors == surfFactors) {
				double gap = gunLocation.distance(robotLocation) - distanceFromGun;
				if (gap > -25) {
					dangerForward += danger(wallSmoothedDestination(direction)) / (gap * gap + 1);
					dangerReverse += danger(wallSmoothedDestination(-direction)) / (gap * gap + 1);
					if (gap < 18) {
						passingWave = this;
					}
				}
				else {
					if (passingWave == this) {
						passingWave = null;
					}
					removeCustomEvent(this);
				}
			}
			else if (distanceFromGun > gunLocation.distance(enemyLocation) - 18) {
				try {
					factors[visitingIndex(enemyLocation)]++;
				}
				catch (Exception e) {
				}
				removeCustomEvent(this);
			}
			return false;
		}
	}
}
