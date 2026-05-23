package pez.micro;

import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;

// This code is released under the RoboWiki Public Code Licence (RWPCL), datailed on:
// http://robowiki.net/?RWPCL
//
// Aristocles, by PEZ. What you see is always an imperfect copy of the form. 
// $Id: Aristocles.java,v 1.11 2004/02/22 20:10:06 peter Exp $

public class Jackson extends AdvancedRobot {
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
	static final int FACTORS = 37;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;

	static Point2D currentEnemyLocation;
	static int lastVelocityIndex;
	static int timeSinceVChange;
	static double enemyBearingDirection;
	static int[][][][][] aimFactors = new int[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][VCHANGE_TIME_INDEXES][FACTORS];
	static int[][] realMovementFactors = new int[VELOCITY_INDEXES][FACTORS];
	static double direction = 1;
	static double enemyEnergy;
	static double enemyFirePower = BULLET_POWER;
	static double lastVelocity;
	static Wave surfWave;
	static int tries;

	public void run() {
		setAdjustRadarForGunTurn(true);
		setAdjustGunForRobotTurn(true);
		enemyEnergy = 100;
		turnRadarRightRadians(Double.POSITIVE_INFINITY);
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		Wave wave = new Wave();
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		double enemyDistance;
		currentEnemyLocation = project(wave.gunLocation = new Point2D.Double(getX(), getY()), enemyAbsoluteBearing,
				enemyDistance = e.getDistance());
		int distanceIndex = (int) (enemyDistance / (MAX_DISTANCE / DISTANCE_INDEXES));
		int movementVelocityIndex = (int) (Math.abs(lastVelocity) / (MAX_VELOCITY / VELOCITY_INDEXES));
		double movementStartBearing = absoluteBearing(currentEnemyLocation, wave.gunLocation);
		double movementBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
				lastVelocity * Math.sin(getHeadingRadians() - movementStartBearing));
		lastVelocity = getVelocity();
		double enemyDeltaEnergy = enemyEnergy - e.getEnergy();
		if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= MAX_BULLET_POWER) {
			enemyFirePower = enemyDeltaEnergy;
			Wave enemyWave = new Wave();
			enemyWave.isSurfWave = true;
			enemyWave.surfFactors = realMovementFactors[movementVelocityIndex];
			enemyWave.gunLocation = currentEnemyLocation;
			enemyWave.bulletPower = enemyDeltaEnergy;
			enemyWave.startBearing = movementStartBearing;
			enemyWave.bearingDirection = movementBearingDirection;
			enemyWave.distanceFromGun = 2 * bulletVelocity(enemyDeltaEnergy);
			addCustomEvent(enemyWave);
		}
		enemyEnergy = e.getEnergy();

		// <movement>
		Point2D robotDestination;
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
		tries = 0;
		while (!fieldRectangle.contains(robotDestination = project(wave.gunLocation,
				enemyAbsoluteBearing - direction * (Math.PI / 2 - tries / 100.0), 160)) && tries++ < 125)
			;
		int forwardTries = tries;
		Point2D reverseDestination;
		tries = 0;
		while (!fieldRectangle.contains(reverseDestination = project(wave.gunLocation,
				enemyAbsoluteBearing + direction * (Math.PI / 2 - tries / 100.0), 160)) && tries++ < 125)
			;
		int reverseTries = tries;
		Wave movementWave = surfWave;
		double forwardDanger = movementDanger(movementWave, predictPosition(movementWave, direction, fieldRectangle));
		double reverseDanger = movementDanger(movementWave, predictPosition(movementWave, -direction, fieldRectangle));

		if (forwardTries > 65 || reverseDanger < forwardDanger) {
			direction = -direction;
			robotDestination = reverseDestination;
			tries = reverseTries;
		} else {
			tries = forwardTries;
		}
		// Jamougha's cool way
		double angle;
		setAhead(Math.cos(angle = absoluteBearing(wave.gunLocation, robotDestination) - getHeadingRadians()) * 200);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun>
		double enemyVelocity = e.getVelocity();
		int velocityIndex = (int) (Math.abs(enemyVelocity) / (MAX_VELOCITY / VELOCITY_INDEXES));
		if (velocityIndex != lastVelocityIndex) {
			timeSinceVChange = 0;
		}

		if (enemyVelocity != 0) {
			enemyBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
					enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		}
		wave.bearingDirection = enemyBearingDirection;

		wave.bulletPower = Math.min(getEnergy() / 2, enemyFirePower);
		// wave.bulletPower = MAX_BULLET_POWER; // TargetingChallenge

		wave.factors = aimFactors[distanceIndex][velocityIndex][lastVelocityIndex][Math.min(VCHANGE_TIME_INDEXES - 1,
				timeSinceVChange++ / 13)];
		lastVelocityIndex = velocityIndex;

		wave.startBearing = enemyAbsoluteBearing;

		int mostVisited = MIDDLE_FACTOR, i = FACTORS;
		do {
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
		surfWave = null;
	}

	public void onHitByBullet(HitByBulletEvent e) {
		try {
			surfWave.surfFactors[movementIndex(surfWave.gunLocation,
					new Point2D.Double(getX(), getY()), surfWave.startBearing,
					surfWave.bearingDirection)]++;
		} catch (Exception ex) {
		}
	}

	public void onBulletHit(BulletHitEvent e) {
		enemyEnergy -= Rules.getBulletDamage(e.getBullet().getPower());
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

	int movementIndex(Point2D source, Point2D target, double startBearing, double bearingDirection) {
		return (int) Math.round(((Utils.normalRelativeAngle(absoluteBearing(source, target) - startBearing)) /
				bearingDirection) + MIDDLE_FACTOR);
	}

	double movementDanger(Wave wave, Point2D destination) {
		try {
			int index = movementIndex(wave.gunLocation, destination, wave.startBearing, wave.bearingDirection);
			double danger = 0;
			int i = FACTORS;
			do {
				danger += wave.surfFactors[--i] / (Math.abs(index - i) + 1.0);
			} while (i > 0);
			return danger;
		} catch (Exception e) {
			return 0;
		}
	}

	Point2D predictPosition(Wave wave, double movementDirection, Rectangle2D fieldRectangle) {
		if (wave == null) {
			return new Point2D.Double(getX(), getY());
		}
		Point2D robotLocation = new Point2D.Double(getX(), getY());
		double heading = absoluteBearing(robotLocation, currentEnemyLocation) - movementDirection * Math.PI / 2;
		int ticks = Math.max(0, (int) ((wave.gunLocation.distance(robotLocation) - wave.distanceFromGun) /
				bulletVelocity(wave.bulletPower)));
		return project(robotLocation, heading, Math.min(ticks * (MAX_VELOCITY - 2), 160));
	}

	class Wave extends Condition {
		double bulletPower;
		Point2D gunLocation;
		double startBearing;
		double bearingDirection;
		int[] factors;
		int[] surfFactors;
		double distanceFromGun;
		boolean isSurfWave;

		public boolean test() {
			if (isSurfWave) {
				distanceFromGun += bulletVelocity(bulletPower);
				double distance = gunLocation.distance(new Point2D.Double(getX(), getY()));
				if (distanceFromGun < distance + 50 && surfWave == null) {
					surfWave = this;
				}
				if (distanceFromGun > distance + 50) {
					removeCustomEvent(this);
				}
			} else if ((distanceFromGun += bulletVelocity(bulletPower)) > gunLocation.distance(currentEnemyLocation) - 18) {
				try {
					factors[(int) Math
							.round(((Utils.normalRelativeAngle(absoluteBearing(gunLocation, currentEnemyLocation) - startBearing)) /
									bearingDirection) + MIDDLE_FACTOR)]++;
				} catch (Exception e) {
				}
				removeCustomEvent(this);
			}
			return false;
		}
	}
}
